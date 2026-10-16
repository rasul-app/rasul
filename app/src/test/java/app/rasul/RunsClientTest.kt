package app.rasul

import app.rasul.data.ApprovalChoice
import app.rasul.data.HermesError
import app.rasul.data.HermesResult
import app.rasul.data.HermesTarget
import app.rasul.data.RunEvent
import app.rasul.data.RunStreamException
import app.rasul.data.RunsClient
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class RunsClientTest {
    private lateinit var server: MockWebServer
    private val client = RunsClient()

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun target(profile: String? = null) = HermesTarget("http://localhost:${server.port}/", profile, "secret-key")

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
            "id: 2\ndata: {\"event\":\"run.completed\",\"run_id\":\"run_1\",\"output\":\"Hi\"}\n\n: stream closed\n\n"
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse))
        val frames = client.events(target("bob"), "run_1", 5L).toList()
        assertEquals(listOf(0L, 1L, 2L), frames.map { it.seq })
        assertEquals(RunEvent.Completed("Hi"), frames.last().event)
        val get = server.takeRequest()
        assertEquals("/p/bob/v1/runs/run_1/events", get.path)
        assertEquals("5", get.getHeader("Last-Event-ID"))
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
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"run_1"}"""))
        client.startRun(target(), "hello", null, "key-1")
        assertEquals("key-1", server.takeRequest().getHeader("Idempotency-Key"))
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"run_id":"run_1"}"""))
        client.startRun(target(), "hello", null)
        assertEquals(null, server.takeRequest().getHeader("Idempotency-Key"))
    }

    @Test fun statusParsesTheOpenApprovalAndPendingSteer() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"waiting_for_approval","approval":{"event":"approval.request","request_id":"q1","command":"rm x","description":"d","choices":["once","deny"]}}"""))
        val ok = (client.status(target(), "r1") as HermesResult.Ok).value
        assertEquals(RunEvent.ApprovalRequest("q1", "rm x", "d", listOf("once", "deny")), ok.approval)
        server.enqueue(MockResponse().setBody("""{"status":"completed","output":"x","pending_steer":"late"}"""))
        val done = (client.status(target(), "r1") as HermesResult.Ok).value
        assertEquals(RunEvent.Completed("x", "late"), done.terminalEvent())
    }
}
