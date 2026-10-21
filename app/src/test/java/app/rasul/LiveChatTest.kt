package app.rasul

import app.rasul.bridge.BridgeEndpoint
import app.rasul.data.HermesTarget
import app.rasul.data.LiveApi
import app.rasul.data.LiveError
import app.rasul.data.LiveEvent
import app.rasul.data.LiveFrame
import app.rasul.data.LiveStreamException
import app.rasul.data.liveApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `GET /v1/profiles/{profile}/live/{session_id}`: every projected event parses, unknown ones are
 * dropped, a 404 is Unsupported, a 429 keeps its Retry-After, and `Last-Event-ID` goes on the wire.
 */
class LiveChatTest {

    private class Server {
        var code = 200
        var body = ""
        var retryAfter: String? = null
        val seen = mutableListOf<Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            seen += request
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(code).message("m")
                .apply { retryAfter?.let { header("Retry-After", it) } }
                .body(body.toResponseBody("text/event-stream".toMediaType()))
                .build()
        }.build()
        val api = HermesTarget("http://192.0.2.7/", null, "key", BridgeEndpoint(http, "home")).liveApi()!!
    }

    private suspend fun failureOf(api: LiveApi, sessionId: String): LiveStreamException {
        try {
            api.events(sessionId, null).toList()
        } catch (e: LiveStreamException) {
            return e
        }
        throw AssertionError("expected the stream to throw")
    }

    @Test
    fun directTargetHasNoLiveApi() {
        assertNull(HermesTarget("http://192.0.2.7/", null, "key").liveApi())
    }

    @Test
    fun parsesEveryEventType() = runBlocking {
        val server = Server()
        server.body = """
id: 9:1
event: snapshot
data: {"running":true,"turn_started_at":1760000000.5}

id: 9:2
event: message.start
data: {}

id: 9:3
event: message.delta
data: {"text":"Hel"}

id: 9:4
event: tool.start
data: {"tool_id":"t1","name":"search"}

id: 9:5
event: status.update
data: {"text":"Reading files"}

id: 9:6
event: tool.complete
data: {"tool_id":"t1","name":"search","summary":"3 files"}

id: 9:7
event: message.complete
data: {"status":"done","text":"Hello"}

id: 9:8
event: reset
data: {}

: keepalive
""".trimIndent()
        val frames = server.api.events("s1", null).toList()
        assertEquals(
            listOf(
                LiveFrame("9:1", LiveEvent.Snapshot(running = true, turnStartedAtMs = 1760000000500L)),
                LiveFrame("9:2", LiveEvent.MessageStart),
                LiveFrame("9:3", LiveEvent.MessageDelta("Hel")),
                LiveFrame("9:4", LiveEvent.ToolStart("t1", "search")),
                LiveFrame("9:5", LiveEvent.StatusUpdate("Reading files")),
                LiveFrame("9:6", LiveEvent.ToolComplete("t1", "search", "3 files")),
                LiveFrame("9:7", LiveEvent.MessageComplete(status = "done", text = "Hello", error = null)),
                LiveFrame("9:8", LiveEvent.Reset),
            ),
            frames,
        )
        assertEquals("/v1/profiles/home/live/s1", server.seen.single().url.encodedPath)
        assertNull(server.seen.single().header("Last-Event-ID"))
    }

    @Test
    fun unknownEventsAndBadDataAreDropped() = runBlocking {
        val server = Server()
        server.body = """
id: 9:1
event: turn.queued
data: {"x":1}

id: 9:2
event: message.delta
data: {"text":"hi"}

id: 9:3
event: message.delta
data: not-json-at-all

id: 9:4
data: {"text":"no event name"}

id: 9:5
event: unavailable
data: {"reason":"desktop-not-running"}

id: 9:6
event: idle
data: {}
""".trimIndent() + "\n\n"
        assertEquals(
            listOf(
                LiveFrame("9:2", LiveEvent.MessageDelta("hi")),
                LiveFrame("9:5", LiveEvent.Unavailable("desktop-not-running")),
                LiveFrame("9:6", LiveEvent.Idle),
            ),
            server.api.events("s1", null).toList(),
        )
    }

    @Test
    fun route404IsUnsupported() = runBlocking {
        val server = Server()
        server.code = 404
        assertEquals(LiveError.Unsupported, failureOf(server.api, "s1").error)
    }

    @Test
    fun route429IsLimitedAndKeepsRetryAfter() = runBlocking {
        val server = Server()
        server.code = 429
        server.retryAfter = "7"
        assertEquals(LiveError.Limited(7_000L), failureOf(server.api, "s1").error)

        // The bridge always sends Retry-After: 30; a missing or non-integer one falls back to it.
        server.retryAfter = null
        assertEquals(LiveError.Limited(30_000L), failureOf(server.api, "s1").error)

        server.retryAfter = "soon"
        assertEquals(LiveError.Limited(30_000L), failureOf(server.api, "s1").error)
    }

    @Test
    fun otherFailuresAreFailed() = runBlocking {
        val server = Server()
        server.code = 503
        assertTrue(failureOf(server.api, "s1").error is LiveError.Failed)
    }

    @Test
    fun lastEventIdGoesOnTheWireVerbatim() = runBlocking {
        val server = Server()
        server.api.events("s1", "1760000000:42").toList()
        assertEquals("1760000000:42", server.seen.single().header("Last-Event-ID"))
    }
}
