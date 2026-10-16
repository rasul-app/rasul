package app.rasul

import app.rasul.bridge.*
import app.rasul.data.*
import app.rasul.sessions.SessionsClient
import app.rasul.jobs.JobsClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID

class BridgeRoutingTest {
    @Test fun bridgeTransportErrorsReconnectThroughTheRunLoop() = runBlocking {
        for (failure in listOf(IOException(), java.net.SocketTimeoutException())) {
            var attempts = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                if (++attempts == 1) throw failure
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("")
                    .body("event: run.completed\ndata: {\"seq\":1}\n\n".toResponseBody()).build()
            }.build()
            try {
                val target = HermesTarget("", null, "", BridgeEndpoint(client, "default"))
                val updates = withTimeout(5000) { followRun({ RunsClient().events(target, "one", it) }, wait = {}).toList() }
                assertEquals(2, attempts)
                assertTrue(updates.first() is RunUpdate.Reconnecting)
                assertTrue(updates.last() is RunUpdate.Event)
                assertFalse(updates.any { it is RunUpdate.Lost })
            } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }

    @Test fun pairedCertificateAlertsRequireTwoConsecutiveAttempts() {
        for (alert in listOf("bad_certificate", "certificate_unknown", "unknown_ca", "access_denied")) {
            val tracker = BridgeRejectionTracker(true)
            tracker.beginAttempt(); tracker.outerConnected(); tracker.serverVerified()
            val failure = javax.net.ssl.SSLHandshakeException("Received fatal alert: $alert")
            assertEquals(HermesError.BridgeUnreachable, bridgeError(tracker.rejected(failure)))
            assertEquals(HermesError.DeviceRevoked, bridgeError(tracker.rejected(failure)))
            tracker.reset()
            assertEquals(HermesError.BridgeUnreachable, bridgeError(tracker.rejected(failure)))
            tracker.rejected(IOException())
            assertEquals(HermesError.BridgeUnreachable, bridgeError(tracker.rejected(failure)))
            val unpaired = BridgeRejectionTracker(false)
            repeat(2) { assertEquals(HermesError.BridgeUnreachable, bridgeError(unpaired.rejected(failure))) }
        }
        val tracker = BridgeRejectionTracker(true)
        repeat(3) {
            tracker.beginAttempt()
            tracker.outerConnected()
            assertEquals(HermesError.BridgeUnreachable, bridgeError(tracker.callFailed(IOException("Connection reset"))))
        }
        tracker.beginAttempt() // Outer TLS itself failed, so this is not an inner certificate rejection.
        val alert = javax.net.ssl.SSLHandshakeException("Received fatal alert: unknown_ca")
        repeat(2) { assertEquals(HermesError.BridgeUnreachable, bridgeError(tracker.callFailed(alert))) }
        tracker.beginAttempt(); tracker.outerConnected()
        repeat(2) { assertEquals(HermesError.BridgeUnreachable, bridgeError(tracker.rejected(alert))) }
    }

    @Test fun bridgeChatSessionsJobsAndStreamUseSelectedClientAndPrefix() = runBlocking {
        val requests = mutableListOf<Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            synchronized(requests) { requests.add(request) }
            val body = when {
                request.url.encodedPath.endsWith("/events") -> "event: run.completed\ndata: {\"seq\":1}\n\n"
                request.url.encodedPath.endsWith("/v1/runs") -> """{"run_id":"one"}"""
                request.url.encodedPath.endsWith("/messages") -> """{"data":[]}"""
                request.url.encodedPath.contains("/api/jobs/") -> """{"job":{"id":"one","enabled":false,"state":"paused"}}"""
                request.url.encodedPath.endsWith("/api/sessions") -> """{"data":[]}"""
                request.url.encodedPath.endsWith("/api/jobs") -> """{"jobs":[]}"""
                else -> """{"status":"completed"}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("").body(body.toResponseBody()).build()
        }.build()
        val unused = OkHttpClient.Builder().addInterceptor { error("wrong client") }.build()
        val target = HermesTarget("", null, "", BridgeEndpoint(http, "default"))
        assertSame(http, target.bridge!!.client)
        val runs = RunsClient(unused)
        assertTrue(runs.startRun(target, "", null) is HermesResult.Ok)
        assertTrue(runs.status(target, "one") is HermesResult.Ok)
        assertTrue(runs.stop(target, "one") is HermesResult.Ok)
        assertTrue(runs.steer(target, "one", "") is HermesResult.Ok)
        assertTrue(runs.approve(target, "one", ApprovalChoice.Once) is HermesResult.Ok)
        withTimeout(5000) { runs.events(target, "one", 3L).first() }
        val client = HermesClient(unused)
        assertTrue(client.health(target) is HermesResult.Ok)
        assertTrue(SessionsClient(client).list(target) is HermesResult.Ok)
        assertTrue(SessionsClient(client).messages(target, "one") is HermesResult.Ok)
        assertTrue(JobsClient(client).list(target) is HermesResult.Ok)
        assertTrue(JobsClient(client).pause(target, "one") is HermesResult.Ok)
        assertTrue(JobsClient(client).resume(target, "one") is HermesResult.Ok)
        assertTrue(JobsClient(client).runNow(target, "one") is HermesResult.Ok)
        assertEquals(listOf("v1/runs", "v1/runs/one", "v1/runs/one/stop", "v1/runs/one/steer", "v1/runs/one/approval", "v1/runs/one/events",
            "health", "api/sessions", "api/sessions/one/messages", "api/jobs", "api/jobs/one/pause", "api/jobs/one/resume", "api/jobs/one/run"),
            requests.map { it.url.encodedPath.removePrefix("/v1/profiles/default/hermes/") })
        assertTrue(requests.all { it.header("Authorization") == null })
        assertEquals("3", requests.first { it.url.encodedPath.endsWith("/events") }.header("Last-Event-ID"))
        assertEquals("50", requests.first { it.url.encodedPath.endsWith("/api/sessions") }.url.queryParameter("limit"))
        assertEquals("false", requests.first { it.url.encodedPath.endsWith("/messages") }.url.queryParameter("inline_images"))
        assertEquals("true", requests.first { it.url.encodedPath.endsWith("/api/jobs") }.url.queryParameter("include_disabled"))
    }

    @Test fun bridgeErrorsRemainSpecificAcrossRequestClientsAndSse() = runBlocking {
        for ((code, error) in listOf(403 to HermesError.DeviceRevoked, 503 to HermesError.ProfileNotReady, 502 to HermesError.BridgeUnreachable)) {
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("").body("".toResponseBody()).build()
            }.build()
            val target = HermesTarget("", null, "", BridgeEndpoint(http, "default"))
            assertEquals(error, (HermesClient().health(target) as HermesResult.Err).error)
            assertEquals(error, (RunsClient().startRun(target, "", null) as HermesResult.Err).error)
            assertEquals(error, (RunsClient().status(target, "one") as HermesResult.Err).error)
            try { withTimeout(5000) { RunsClient().events(target, "one").first() }; fail("expected stream error") }
            catch (e: RunStreamException) { assertEquals(error, e.error) }
            try { BridgeProfiles(http).list(); fail("expected profile error") }
            catch (e: ProfileStreamException) { assertEquals(error, e.error) }
        }
        val http = OkHttpClient.Builder().addInterceptor { throw IOException() }.build()
        val target = HermesTarget("", null, "", BridgeEndpoint(http, "default"))
        assertEquals(HermesError.BridgeUnreachable, (HermesClient().health(target) as HermesResult.Err).error)
    }

    @Test fun directTargetRetainsOriginalRouteHeaderAndForbiddenResult() = runBlocking {
        val server = MockWebServer()
        try {
            server.start()
            val key = UUID.randomUUID().toString()
            val target = HermesTarget(server.url("/").toString(), "default", key)
            server.enqueue(MockResponse().setResponseCode(403))
            assertEquals(HermesError.Unauthorized, (HermesClient().health(target) as HermesResult.Err).error)
            val request = server.takeRequest()
            assertEquals("/p/default/health", request.path)
            assertEquals("Bearer $key", request.getHeader("Authorization"))
        } finally { server.shutdown() }
    }

    @Test fun missingRunPreservesChatNotFoundContract() = runBlocking {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(404).message("").body("".toResponseBody()).build()
        }.build()
        val target = HermesTarget("", null, "", BridgeEndpoint(http, "default"))
        assertEquals(HermesError.NotFound, (RunsClient().status(target, "one") as HermesResult.Err).error)
        assertEquals(HermesError.ResourceNotFound, (HermesClient().health(target) as HermesResult.Err).error)
        try { BridgeProfiles(http).list(); fail("expected unsupported route") }
        catch (e: ProfileStreamException) { assertEquals(HermesError.BridgeResponseInvalid, e.error) }
    }
}
