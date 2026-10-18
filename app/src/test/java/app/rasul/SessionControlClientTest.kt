package app.rasul

import app.rasul.bridge.BridgeEndpoint
import app.rasul.data.HermesClient
import app.rasul.data.HermesError
import app.rasul.data.HermesResult
import app.rasul.data.HermesTarget
import app.rasul.sessions.SessionControlClient
import app.rasul.sessions.SessionControlSupport
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionControlClientTest {
    private lateinit var server: MockWebServer
    private val control = SessionControlClient(HermesClient())

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun target(profile: String? = null) = HermesTarget("http://localhost:${server.port}/", profile, "sample-key")

    private fun caps(vararg features: String) =
        """{"object":"hermes.api_server.capabilities","features":{"run_submission":true,"run_events_sse":true,"run_stop":true,
            ${features.joinToString(",") { "\"$it\":true" }}}}""".replace(Regex(",\\s*}"), "}")

    @Test fun supportComesFromAdvertisedFeaturesOnly() = runBlocking {
        server.enqueue(MockResponse().setBody(caps("session_fork", "session_model_lock")))
        assertEquals(SessionControlSupport(fork = true), control.support(target()))
        assertEquals("/v1/capabilities", server.takeRequest().path)

        server.enqueue(MockResponse().setBody(caps("session_model_lock")))
        assertEquals(SessionControlSupport(fork = false), control.support(target()))
        server.takeRequest()

        // An older Hermes that advertises no fork feature: it stays hidden.
        server.enqueue(MockResponse().setBody("""{"object":"hermes.api_server.capabilities","features":{"run_submission":true,"run_events_sse":true,"run_stop":true}}"""))
        assertEquals(SessionControlSupport(), control.support(target()))
        server.takeRequest()

        // A server that cannot be asked is unsupported, never a crash.
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(SessionControlSupport(), control.support(target()))
    }

    @Test fun forkRequestShapeAndReply() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"object":"hermes.session","session":{"id":"fork-one","title":"Sample fork","parent_session_id":"one"}}"""))
        val forked = (control.fork(target("example"), "one") as HermesResult.Ok).value
        assertEquals("fork-one", forked.id)
        assertEquals("Sample fork", forked.title)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/p/example/api/sessions/one/fork", request.path)
        assertEquals("Bearer sample-key", request.getHeader("Authorization"))
        assertEquals("{}", request.body.readUtf8())
    }

    @Test fun forkRejectsAReplyThatIsNotANewSession() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"session":{"id":"one"}}"""))
        assertTrue(control.fork(target(), "one") is HermesResult.Err)
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"nothing":true}"""))
        assertTrue((control.fork(target(), "one") as HermesResult.Err).error is HermesError.Incompatible)
        // An id that is not a plain id never reaches the network.
        assertEquals(HermesError.ResourceNotFound, (control.fork(target(), "../x") as HermesResult.Err).error)
        assertEquals(2, server.requestCount)
    }

    @Test fun forkErrorsStaySpecific() = runBlocking {
        // Route missing on an older Hermes: a bare 404.
        server.enqueue(MockResponse().setResponseCode(404).setBody("404: Not Found"))
        assertEquals(HermesError.NotFound, (control.fork(target(), "one") as HermesResult.Err).error)
        // Session missing: Hermes answers a JSON error.
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"message":"Session not found: one","code":"session_not_found"}}"""))
        assertEquals(HermesError.ResourceNotFound, (control.fork(target(), "one") as HermesResult.Err).error)
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":{"message":"Session already exists","code":"session_exists"}}"""))
        assertEquals(HermesError.Server(409, "Session already exists", "session_exists"), (control.fork(target(), "one") as HermesResult.Err).error)
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"fork_rate_limited"}"""))
        assertEquals(HermesError.Server(429, "fork_rate_limited", null), (control.fork(target(), "one") as HermesResult.Err).error)
    }

    @Test fun infoReadsTitleAndParent() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"object":"hermes.session","session":{"id":"fork-one","title":"Sample fork","parent_session_id":"one","source":"api_server"}}"""))
        val info = (control.info(target(), "fork-one") as HermesResult.Ok).value
        assertEquals("Sample fork", info.title)
        assertEquals("one", info.parentSessionId)
        assertEquals("GET", server.takeRequest().method)
        server.enqueue(MockResponse().setBody("""{"session":{"id":"one"}}"""))
        assertNull((control.info(target(), "one") as HermesResult.Ok).value.parentSessionId)
    }

    @Test fun bridgeTargetsUseTheBridgeRoutes() = runBlocking {
        val seen = mutableListOf<Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            seen += chain.request()
            val path = chain.request().url.encodedPath
            val (code, body) = when {
                path.endsWith("/fork") -> 201 to """{"session":{"id":"fork-one","title":"Sample fork"}}"""
                else -> 200 to caps("session_fork", "session_model_lock")
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("").body(body.toResponseBody()).build()
        }.build()
        val bridge = HermesTarget("", null, "", BridgeEndpoint(http, "default"))
        assertEquals(SessionControlSupport(fork = true), control.support(bridge))
        assertEquals("fork-one", (control.fork(bridge, "one") as HermesResult.Ok).value.id)
        assertEquals(
            listOf("v1/capabilities", "api/sessions/one/fork"),
            seen.map { it.url.encodedPath.removePrefix("/v1/profiles/default/hermes/") },
        )
        assertTrue(seen.all { it.header("Authorization") == null })
    }
}
