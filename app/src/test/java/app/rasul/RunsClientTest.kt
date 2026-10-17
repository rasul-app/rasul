package app.rasul

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.rasul.chat.modelChoices
import app.rasul.chat.toRunModelSelection
import app.rasul.data.ApprovalChoice
import app.rasul.data.ConnectionStore
import app.rasul.data.HermesError
import app.rasul.data.HermesResult
import app.rasul.data.HermesTarget
import app.rasul.data.ModelOptionsDto
import app.rasul.data.ModelOptionsProviderDto
import app.rasul.data.RunEvent
import app.rasul.data.RunModelSelection
import app.rasul.data.RunStreamException
import app.rasul.data.RunTokenUsage
import app.rasul.data.RunsClient
import app.rasul.data.SecretCipher
import app.rasul.data.TokenUsageSummary
import app.rasul.data.sumRunTokenUsage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.concurrent.TimeUnit

class RunsClientTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private val client = RunsClient()
    private val cipher = object : SecretCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(token: String) = token
    }

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun target(profile: String? = null) = HermesTarget("http://localhost:${server.port}/", profile, "secret-key")

    @Test fun selectedProviderIsSentEvenWhenTwoProvidersOfferSameModel() = runBlocking {
        val options = ModelOptionsDto(
            providers = listOf(
                ModelOptionsProviderDto("provider-one", "Provider One", listOf("shared-model")),
                ModelOptionsProviderDto("provider-two", "Provider Two", listOf("shared-model")),
            ),
        )
        val selected = modelChoices(options).single { it.providerSlug == "provider-two" }
        client.selectModelForChat("session-1", selected.toRunModelSelection())
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"run_provider"}"""))

        client.startRun(target(), "hello", "session-1")

        assertEquals(
            """{"input":"hello","session_id":"session-1","provider":"provider-two","model":"shared-model"}""",
            server.takeRequest().body.readUtf8(),
        )
    }

    @Test fun restoredClientRecordsCompletedUsageOnce() = runBlocking {
        val file = tmp.root.resolve("run-usage.preferences_pb")
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var restoredScope: CoroutineScope? = null
        var verifiedScope: CoroutineScope? = null
        try {
            val firstStore = ConnectionStore(PreferenceDataStoreFactory.create(scope = firstScope) { file }, cipher)
            val firstClient = RunsClient(usageStore = firstStore, scope = firstScope)
            server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"run_restore"}"""))
            firstClient.startRun(target(), "hello", "session-restore")
            server.takeRequest()

            firstScope.cancel()
            firstScope.coroutineContext[Job]!!.join()
            restoredScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val restoredStore = ConnectionStore(PreferenceDataStoreFactory.create(scope = restoredScope!!) { file }, cipher)
            val restoredClient = RunsClient(usageStore = restoredStore, scope = restoredScope)
            repeat(2) {
                server.enqueue(MockResponse().setBody(
                    """{"status":"completed","output":"Done","usage":{"input_tokens":40,"output_tokens":20,"total_tokens":60}}""",
                ))
                restoredClient.status(target(), "run_restore")
                server.takeRequest()
            }

            val expected = TokenUsageSummary(completedRuns = 1, reportedRuns = 1, inputTokens = 40, outputTokens = 20, totalTokens = 60)
            assertEquals(expected, restoredClient.usageBySession.value["session-restore"])
            restoredScope!!.cancel()
            restoredScope!!.coroutineContext[Job]!!.join()

            verifiedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val verifiedStore = ConnectionStore(PreferenceDataStoreFactory.create(scope = verifiedScope!!) { file }, cipher)
            val verifiedClient = RunsClient(usageStore = verifiedStore, scope = verifiedScope)
            val restoredTotals = withTimeout(5000) {
                verifiedClient.usageBySession.first { it["session-restore"] == expected }
            }
            assertEquals(expected, restoredTotals["session-restore"])
            server.enqueue(MockResponse().setBody(
                """{"status":"completed","output":"Done","usage":{"input_tokens":40,"output_tokens":20,"total_tokens":60}}""",
            ))
            verifiedClient.status(target(), "run_restore")
            server.takeRequest()
            assertEquals(expected, verifiedClient.usageBySession.value["session-restore"])

            val writeScope = CoroutineScope(Job() + Dispatchers.Unconfined)
            val writeDataStore = PreferenceDataStoreFactory.create(scope = writeScope) {
                tmp.root.resolve("run-usage-write.preferences_pb")
            }
            val failingWriteDataStore = object : DataStore<Preferences> by writeDataStore {
                override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                    throw IOException("store write failed")
            }
            val writeClient = RunsClient(
                usageStore = ConnectionStore(failingWriteDataStore, cipher),
                scope = writeScope,
            )
            server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"run_restore"}"""))
            val writeResult = runCatching { writeClient.startRun(target(), "hello", "session-restore") }.getOrNull()
            server.takeRequest()
            val writeFailureContained = writeResult is HermesResult.Ok && writeResult.value.runId == "run_restore"
            writeScope.cancel()

            val readJob = Job()
            val readScope = CoroutineScope(readJob + Dispatchers.Unconfined)
            val failingReadDataStore = object : DataStore<Preferences> {
                override val data: Flow<Preferences> = flow { throw IOException("store read failed") }
                override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                    throw IOException("store read failed")
            }
            val readClient = RunsClient(
                usageStore = ConnectionStore(failingReadDataStore, cipher),
                scope = readScope,
            )
            val readFailureContained = readJob.isActive && readClient.usageBySession.value.isEmpty()
            readScope.cancel()

            val storageFailures = buildList {
                if (!writeFailureContained) add("accepted run id did not survive a usage write failure")
                if (!readFailureContained) add("usage read failure escaped its collector")
            }
            assertEquals(emptyList<String>(), storageFailures)
        } finally {
            firstScope.cancel()
            restoredScope?.cancel()
            verifiedScope?.cancel()
        }
    }

    @Test fun startRunPostsInputAndSessionThenEventsStreamToCompleted() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"run_1","status":"started","replayed":false}"""))
        val started = (client.startRun(target("bob"), "hello", "sess-1") as HermesResult.Ok).value
        assertEquals("run_1", started.runId)
        val post = server.takeRequest()
        assertEquals("POST", post.method)
        assertEquals("/p/bob/v1/runs", post.path)
        assertEquals("Bearer secret-key", post.getHeader("Authorization"))
        assertEquals("""{"input":"hello","session_id":"sess-1"}""", post.body.readUtf8())

        val sse = ": open\n\n" +
            "id: 0\ndata: {\"event\":\"tool.started\",\"run_id\":\"run_1\",\"tool\":\"bash\",\"preview\":\"ls\"}\n\n" +
            "id: 1\ndata: {\"event\":\"message.delta\",\"run_id\":\"run_1\",\"delta\":\"Hi\"}\n\n" +
            ": keepalive\n\n" +
            "id: 2\ndata: {\"event\":\"run.completed\",\"run_id\":\"run_1\",\"output\":\"Hi\",\"usage\":{\"input_tokens\":50,\"output_tokens\":200,\"total_tokens\":250}}\n\n: stream closed\n\n"
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse))
        val frames = client.events(target("bob"), "run_1", 5L).toList()
        assertEquals(listOf(0L, 1L, 2L), frames.map { it.seq })
        assertEquals(RunEvent.Completed("Hi", null, RunTokenUsage(50, 200, 250)), frames.last().event)
        assertEquals(
            TokenUsageSummary(completedRuns = 1, reportedRuns = 1, inputTokens = 50, outputTokens = 200, totalTokens = 250),
            client.usageBySession.value["sess-1"],
        )
        val get = server.takeRequest()
        assertEquals("/p/bob/v1/runs/run_1/events", get.path)
        assertEquals("5", get.getHeader("Last-Event-ID"))

        client.selectModelForChat("sess-1", RunModelSelection("provider-one", "example-model"))
        server.enqueue(MockResponse().setHeadersDelay(500, TimeUnit.MILLISECONDS)
            .setResponseCode(202).setBody("""{"run_id":"run_2","status":"started"}"""))
        val pending = async(Dispatchers.Default) { client.startRun(target("bob"), "next", "sess-1") }
        val selectedRequest = server.takeRequest()
        client.selectModelForChat("sess-1", null)
        assertEquals("POST", selectedRequest.method)
        assertEquals("/p/bob/v1/runs", selectedRequest.path)
        assertEquals("""{"input":"next","session_id":"sess-1","provider":"provider-one","model":"example-model"}""", selectedRequest.body.readUtf8())
        assertTrue(pending.await() is HermesResult.Ok)

        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"event\":\"run.completed\",\"run_id\":\"run_2\",\"output\":\"Done\",\"usage\":{}}\n\n",
        ))
        client.events(target("bob"), "run_2").toList()
        assertEquals("/p/bob/v1/runs/run_2/events", server.takeRequest().path)
        assertEquals(
            TokenUsageSummary(completedRuns = 2, reportedRuns = 1, inputTokens = 50, outputTokens = 200, totalTokens = 250),
            client.usageBySession.value["sess-1"],
        )

        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"run_3","status":"started"}"""))
        assertTrue(client.startRun(target("bob"), "other", "sess-2") is HermesResult.Ok)
        val defaultRequest = server.takeRequest()
        assertEquals("POST", defaultRequest.method)
        assertEquals("/p/bob/v1/runs", defaultRequest.path)
        assertEquals("""{"input":"other","session_id":"sess-2"}""", defaultRequest.body.readUtf8())
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"event\":\"run.completed\",\"run_id\":\"run_3\",\"output\":\"Done\"}\n\n",
        ))
        client.events(target("bob"), "run_3").toList()
        assertEquals("/p/bob/v1/runs/run_3/events", server.takeRequest().path)
        assertEquals(TokenUsageSummary(completedRuns = 1), client.usageBySession.value["sess-2"])
        assertEquals(
            TokenUsageSummary(completedRuns = 2, reportedRuns = 1, inputTokens = 2, totalTokens = 2),
            sumRunTokenUsage(listOf(null, RunTokenUsage(inputTokens = 2, totalTokens = 2))),
        )
    }

    @Test fun stopSteerAndApprovalBodies() = runBlocking {
        repeat(3) { server.enqueue(MockResponse().setBody("{}")) }
        assertTrue(client.stop(target(), "r1") is HermesResult.Ok)
        assertTrue(client.steer(target(), "r1", "use python") is HermesResult.Ok)
        assertTrue(client.approve(target(), "r1", ApprovalChoice.Once, "q1") is HermesResult.Ok)
        val stop = server.takeRequest()
        assertEquals("/v1/runs/r1/stop", stop.path)
        assertEquals("POST", stop.method)
        val steer = server.takeRequest()
        assertEquals("/v1/runs/r1/steer", steer.path)
        assertEquals("""{"input":"use python"}""", steer.body.readUtf8())
        val ap = server.takeRequest()
        assertEquals("/v1/runs/r1/approval", ap.path)
        assertEquals("""{"choice":"once","request_id":"q1"}""", ap.body.readUtf8())
    }

    @Test fun approvalDenyHasNoRequestIdWhenNoneGiven() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        client.approve(target(), "r1", ApprovalChoice.Deny)
        assertEquals("""{"choice":"deny"}""", server.takeRequest().body.readUtf8())
    }

    @Test fun unauthorizedAndConflictAreSpecific() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(HermesResult.Err(HermesError.Unauthorized), client.startRun(target(), "x", null))
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":{"code":"run_not_accepting_steer"}}"""))
        assertEquals(HermesResult.Err(HermesError.Server(409)), client.steer(target(), "r1", "x"))
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(HermesResult.Err(HermesError.NotFound), client.stop(target(), "gone"))
    }

    @Test fun unauthorizedOnEventsThrowsStreamException() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        try {
            client.events(target(), "r1").toList()
            fail("expected RunStreamException")
        } catch (e: RunStreamException) {
            assertEquals(HermesError.Unauthorized, e.error)
        }
    }

    @Test fun badRunIdBodyIsIncompatibleAndPolicyBlocksBeforeAnyRequest() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202).setBody("{}"))
        assertTrue((client.startRun(target(), "x", null) as HermesResult.Err).error is HermesError.Incompatible)
        server.takeRequest()
        val bad = HermesTarget("http://example.com/", null, "k")
        assertEquals(HermesResult.Err(HermesError.AddressRejected), client.startRun(bad, "x", null))
        assertEquals(1, server.requestCount)
    }

    @Test fun startRunSendsTheIdempotencyKeyHeader() = runBlocking {
        val retryClient = RunsClient(base = OkHttpClient.Builder().readTimeout(100, TimeUnit.MILLISECONDS).build())
        val originalSelection = RunModelSelection("provider-one", "model-one")
        retryClient.selectModelForChat("session-1", originalSelection)
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        assertTrue(retryClient.startRun(target(), "hello", "session-1", "key-1") is HermesResult.Err)
        val first = server.takeRequest()
        val originalBody = """{"input":"hello","session_id":"session-1","provider":"provider-one","model":"model-one"}"""
        assertEquals("key-1", first.getHeader("Idempotency-Key"))
        assertEquals(originalBody, first.body.readUtf8())

        retryClient.selectModelForChat("session-1", RunModelSelection("provider-two", "model-two"))
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"run_1"}"""))
        assertTrue(retryClient.startRun(target(), "hello", "session-1", "key-1") is HermesResult.Ok)
        val retry = server.takeRequest()
        assertEquals("key-1", retry.getHeader("Idempotency-Key"))
        assertEquals(originalBody, retry.body.readUtf8())

        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"run_2"}"""))
        retryClient.startRun(target(), "hello", null)
        assertEquals(null, server.takeRequest().getHeader("Idempotency-Key"))
    }

    @Test fun statusParsesTheOpenApprovalAndPendingSteer() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"waiting_for_approval","approval":{"event":"approval.request","request_id":"q1","command":"rm x","description":"d","choices":["once","deny"]}}"""))
        val ok = (client.status(target(), "r1") as HermesResult.Ok).value
        assertEquals(RunEvent.ApprovalRequest("q1", "rm x", "d", listOf("once", "deny")), ok.approval)
        server.enqueue(MockResponse().setBody("""{"status":"completed","output":"x","pending_steer":"late","usage":{"input_tokens":3,"output_tokens":4,"total_tokens":7}}"""))
        val done = (client.status(target(), "r1") as HermesResult.Ok).value
        assertEquals(RunEvent.Completed("x", "late", RunTokenUsage(3, 4, 7)), done.terminalEvent())
    }
}
