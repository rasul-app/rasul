package app.rasul

import app.rasul.bridge.*
import app.rasul.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import java.util.concurrent.TimeUnit
import java.io.IOException

class BridgeProfilesTest {
    private val body = """{"revision":1,"profiles":[{"id":"default","name":"default","display_name":"default","ready":true,"default":true}]}"""

    @Test fun snapshotFieldsMatchBridgeResponse() {
        val value = parseProfiles(body)
        assertEquals(1L, value.revision)
        assertEquals(BridgeProfile("default", "default", "default", true, true), value.profiles.single())
        assertFalse(parseProfiles(body.replace("true,", "false,")).profiles.single().ready)
    }

    @Test fun malformedMissingFieldsAndUnsafeIdsAreRejected() {
        val invalid = listOf(body.replace("\"revision\":1,", ""), body.replace("\"ready\":true,", ""),
            body.replace("\"id\":\"default\"", "\"id\":\"../default\""), body.replace("\"revision\":1", "\"revision\":-1"))
        invalid.forEach { raw -> assertTrue(runCatching { parseProfiles(raw) }.isFailure) }
    }

    @Test fun listAndSseUseExactRoutesAndSnapshotShape() = runBlocking {
        val requests = mutableListOf<Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            synchronized(requests) { requests.add(request) }
            val payload = if (request.url.encodedPath.endsWith("profile-events"))
                ": keepalive\n\nevent: profiles\ndata: $body\n\n" else body
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("").body(payload.toResponseBody()).build()
        }.build()
        val api = BridgeProfiles(http)
        assertEquals(parseProfiles(body), api.list())
        assertEquals(parseProfiles(body), withTimeout(5000) { api.events().first() })
        assertEquals(listOf("/v1/profiles", "/v1/profile-events"), requests.map { it.url.encodedPath })
        assertTrue(requests.all { it.header("Authorization") == null })
    }

    @Test fun droppedStreamReconnectsWithCappedBackoffAndFreshSnapshot() = runTest {
        var opens = 0
        val waits = mutableListOf<Long>()
        val api = object : ProfilesApi {
            override suspend fun list() = parseProfiles(body)
            override fun events(): Flow<ProfileSnapshot> = flow {
                opens++
                if (opens < 6) throw IOException()
                emit(parseProfiles(body.replace("\"revision\":1", "\"revision\":2")))
                awaitCancellation()
            }
        }
        val values = followProfiles(api) { waits.add(it); delay(it) }.take(7).toList()
        assertEquals(6, opens)
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 8000L), waits)
        assertEquals(2L, (values.last() as BridgeGroupUpdate.Snapshot).value.revision)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun normalCompletionKeepsSnapshotsAndResetsBackoff() = runTest {
        var opens = 0
        val waits = mutableListOf<Long>()
        val updates = mutableListOf<BridgeGroupUpdate>()
        val api = object : ProfilesApi {
            override suspend fun list() = parseProfiles(body)
            override fun events(): Flow<ProfileSnapshot> = flow {
                opens++
                if (opens <= 2) throw IOException()
                emit(parseProfiles(body))
            }
        }
        val consumer = launch {
            followProfiles(api) { waits.add(it); if (waits.size == 4) awaitCancellation() }
                .collect { updates.add(it) }
        }
        runCurrent()
        consumer.cancelAndJoin()
        assertEquals(4, opens)
        assertEquals(2, updates.filterIsInstance<BridgeGroupUpdate.Failed>().size)
        assertEquals(3, updates.filterIsInstance<BridgeGroupUpdate.Snapshot>().size)
        assertEquals(listOf(1000L, 2000L, 1000L, 1000L), waits)
    }

    @Test fun forbiddenPublishesRevokedAndDoesNotReconnect() = runTest {
        var opens = 0
        val api = object : ProfilesApi {
            override suspend fun list(): ProfileSnapshot { throw ProfileStreamException(HermesError.DeviceRevoked) }
            override fun events(): Flow<ProfileSnapshot> { opens++; return emptyFlow() }
        }
        assertEquals(listOf(BridgeGroupUpdate.Failed(HermesError.DeviceRevoked)), followProfiles(api) { fail("no retry") }.toList())
        assertEquals(0, opens)
    }

    @Test fun actualSseEofReconnectsToSameRouteAndClient() = runBlocking {
        val server = MockWebServer()
        val waits = mutableListOf<Long>()
        try {
            server.start()
            server.enqueue(MockResponse().setBody(body))
            server.enqueue(MockResponse().setBody("event: profiles\ndata: $body\n\n"))
            val next = body.replace("\"revision\":1", "\"revision\":2")
            server.enqueue(MockResponse().setBody("event: profiles\ndata: $next\n\n"))
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                chain.proceed(request.newBuilder().url(server.url(request.url.encodedPath)).build())
            }.build()
            val snapshots = withTimeout(5000) {
                followProfiles(BridgeProfiles(http)) { waits.add(it) }
                    .filterIsInstance<BridgeGroupUpdate.Snapshot>().take(3).toList()
            }
            assertEquals(listOf(1L, 1L, 2L), snapshots.map { it.value.revision })
            assertEquals(listOf(1000L), waits)
            assertEquals(listOf("/v1/profiles", "/v1/profile-events", "/v1/profile-events"), (1..3).map { server.takeRequest().path })
        } finally { server.shutdown() }
    }

    @Test fun cancellingVisibleConsumerClosesRealBlockingSseRead() = runBlocking {
        val server = MockWebServer()
        try {
            server.start()
            val frame = "event: profiles\ndata: $body\n\n"
            server.enqueue(MockResponse().setBody(frame + ": keepalive\n\n".repeat(10000))
                .throttleBody(frame.toByteArray().size.toLong(), 100, TimeUnit.MILLISECONDS))
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                chain.proceed(request.newBuilder().url(server.url(request.url.encodedPath)).build())
            }.build()
            val received = CompletableDeferred<ProfileSnapshot>()
            val consumer = launch { BridgeProfiles(http).events().collect { received.complete(it) } }
            try {
                assertEquals(1L, withTimeout(5000) { received.await() }.revision)
                withTimeout(5000) { consumer.cancelAndJoin() }
                assertEquals(0, http.dispatcher.runningCallsCount())
            } finally { consumer.cancelAndJoin() }
        } finally { server.shutdown() }
    }
}
