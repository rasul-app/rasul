package app.rasul

import app.rasul.data.HermesClient
import app.rasul.data.HermesError
import app.rasul.data.HermesResult
import app.rasul.data.HermesTarget
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HermesClientTest {
    private lateinit var server: MockWebServer
    private val client = HermesClient()

    // Shape copied from the reference doc ("GET /v1/capabilities"), plus the run_stop flag and an unknown key.
    private val capsJson = """
        {"object":"hermes.api_server.capabilities","platform":"hermes-agent","model":"hermes-agent",
         "auth":{"type":"bearer","required":true},
         "features":{"chat_completions":true,"responses_api":true,"run_submission":true,"run_status":true,
                     "run_events_sse":true,"run_stop":true,"reasoning_streaming":true},
          "session_key_header":"X-Hermes-Session-Key",
          "endpoints":{"skills":{"method":"GET","path":"/v1/skills"},
                       "toolsets":{"method":"GET","path":"/v1/toolsets"}}}
    """.trimIndent()

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun target(profile: String? = null) =
        HermesTarget("http://localhost:${server.port}/", profile, "secret-key")

    @Test fun capabilitiesParsedAndBearerSent() = runBlocking {
        server.enqueue(MockResponse().setBody(capsJson))
        val r = client.capabilities(target()) as HermesResult.Ok
        assertEquals("hermes-agent", r.value.platform)
        assertTrue("run_stop" in r.value.enabledFeatures())
        assertTrue(r.value.advertisesEndpoint("skills"))
        assertTrue(r.value.advertisesEndpoint("toolsets"))
        val req = server.takeRequest()
        assertEquals("/v1/capabilities", req.path)
        assertEquals("Bearer secret-key", req.getHeader("Authorization"))

        server.enqueue(MockResponse().setBody(
            """{"object":"list","data":[{"name":"sample-skill","description":"Example skill","category":"example"}]}""",
        ))
        val skills = (client.skills(target()) as HermesResult.Ok).value
        assertEquals("sample-skill", skills.single().name)
        assertEquals("Example skill", skills.single().description)
        val skillsRequest = server.takeRequest()
        assertEquals("GET", skillsRequest.method)
        assertEquals("/v1/skills", skillsRequest.path)

        server.enqueue(MockResponse().setBody(
            """{"object":"list","platform":"api_server","data":[{"name":"sample-tools","label":"Example tools","description":"Example toolset","enabled":true,"configured":true,"tools":["example_action_one","example_action_two"]}]}""",
        ))
        val toolsets = (client.toolsets(target()) as HermesResult.Ok).value
        assertEquals(listOf("example_action_one", "example_action_two"), toolsets.single().tools)
        val toolsetsRequest = server.takeRequest()
        assertEquals("GET", toolsetsRequest.method)
        assertEquals("/v1/toolsets", toolsetsRequest.path)

        server.enqueue(MockResponse().setBody("""{"providers":[],"model":"example-model","provider":null}"""))
        assertTrue(client.modelOptions(target()) is HermesResult.Ok)
        val optionsRequest = server.takeRequest()
        assertEquals("GET", optionsRequest.method)
        assertEquals("/api/model/options", optionsRequest.path)
        server.enqueue(MockResponse().setBody("""{"providers":[],"model":"example-model","provider":null}"""))
        assertTrue(client.modelOptions(target(), refresh = true) is HermesResult.Ok)
        val refreshRequest = server.takeRequest()
        assertEquals("GET", refreshRequest.method)
        assertEquals("/api/model/options?refresh=1", refreshRequest.path)
    }

    @Test fun modelOptionsReturnsProviderRows() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"providers":[{"slug":"example-provider","name":"Example provider","models":["example-model"]}],"model":"example-model","provider":"example-provider"}""",
        ))
        val options = (client.modelOptions(target()) as HermesResult.Ok).value
        assertEquals("example-model", options.model)
        assertEquals("example-provider", options.provider)
        assertEquals("example-provider", options.providers.single().slug)
        assertEquals("Example provider", options.providers.single().name)
        assertEquals(listOf("example-model"), options.providers.single().models)
        assertEquals("/api/model/options", server.takeRequest().path)
    }

    @Test fun profileAddsPrefix() = runBlocking {
        server.enqueue(MockResponse().setBody(capsJson))
        client.capabilities(target("alice"))
        assertEquals("/p/alice/v1/capabilities", server.takeRequest().path)
    }

    @Test fun healthHitsHealthPath() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        assertTrue(client.health(target()) is HermesResult.Ok)
        assertEquals("/health", server.takeRequest().path)
    }

    @Test fun unauthorized401And403() = runBlocking {
        for (code in listOf(401, 403)) {
            server.enqueue(MockResponse().setResponseCode(code))
            val r = client.capabilities(target()) as HermesResult.Err
            assertEquals(HermesError.Unauthorized, r.error)
        }
    }

    @Test fun flatAndNestedErrorEnvelopesBothMapToResourceNotFound() = runBlocking {
        // Flat (jobs, api_server.py:3852) and nested (sessions, api_server.py:908 / :3044) both carry a message.
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"Job not found"}"""))
        assertEquals(HermesError.ResourceNotFound, (client.capabilities(target("bob")) as HermesResult.Err).error)
        server.enqueue(MockResponse().setResponseCode(404).setBody(
            """{"error":{"message":"Session not found: x","code":"session_not_found"}}""",
        ))
        assertEquals(HermesError.ResourceNotFound, (client.capabilities(target("bob")) as HermesResult.Err).error)
        // A nested envelope carrying the unknown-profile text still maps to ProfileUnknown.
        server.enqueue(MockResponse().setResponseCode(404).setBody(
            """{"error":{"message":"Unknown or unconfigured profile","code":null}}""",
        ))
        assertEquals(HermesError.ProfileUnknown("bob"), (client.capabilities(target("bob")) as HermesResult.Err).error)
    }

    @Test fun notFoundVsProfileUnknown() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(HermesError.NotFound, (client.capabilities(target()) as HermesResult.Err).error)
        // The server's own text for an unrouted /p/<x>/ (api_server.py:1700).
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"Unknown or unconfigured profile"}"""))
        assertEquals(
            HermesError.ProfileUnknown("bob"),
            (client.capabilities(target("bob")) as HermesResult.Err).error,
        )
        // A valid profile with an unknown route and no JSON error stays a plain NotFound.
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(HermesError.NotFound, (client.capabilities(target("bob")) as HermesResult.Err).error)
    }

    @Test fun serverErrorCarriesCode() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(HermesError.Server(503), (client.capabilities(target()) as HermesResult.Err).error)
    }

    @Test fun connectionRefusedIsUnreachable() = runBlocking {
        val t = target()
        server.shutdown()
        val r = client.capabilities(t) as HermesResult.Err
        assertTrue(r.error is HermesError.Unreachable)
    }

    @Test fun missingRequiredFeatureIsIncompatible() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"object":"hermes.api_server.capabilities","features":{"run_submission":true}}""",
            ),
        )
        val r = client.capabilities(target()) as HermesResult.Err
        assertEquals(HermesError.Incompatible(listOf("run_events_sse", "run_stop")), r.error)
    }

    @Test fun nonHermesBodyIsIncompatible() = runBlocking {
        server.enqueue(MockResponse().setBody("<html>hi</html>"))
        assertTrue((client.capabilities(target()) as HermesResult.Err).error is HermesError.Incompatible)
    }

    @Test fun badBaseUrlIsInvalidAddress() = runBlocking {
        val r = client.health(HermesTarget("not a url", null, "k")) as HermesResult.Err
        assertTrue(r.error is HermesError.InvalidAddress)
    }

    @Test fun policyDeniedUrlIsRejectedBeforeAnyRequest() = runBlocking {
        val r = client.health(HermesTarget("http://example.com", null, "k")) as HermesResult.Err
        assertEquals(HermesError.AddressRejected, r.error)
        assertEquals(0, server.requestCount)
    }

    @Test fun redirectIsNotFollowed() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://example.com/"))
        val r = client.capabilities(target()) as HermesResult.Err
        assertEquals(HermesError.Redirected(302), r.error)
        assertEquals(1, server.requestCount)
    }

    @Test fun keyWithInternalSpaceIsAcceptedAndSentExactly() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        val r = client.health(HermesTarget("http://localhost:${server.port}/", null, "has space"))
        assertTrue(r is HermesResult.Ok)
        assertEquals("Bearer has space", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun nonAsciiOrBadKeyIsInvalidKeyAndNeverSent() = runBlocking {
        for (bad in listOf("كلمة-سر", "a\nb", "a\rb", "a\tb", " lead", "trail ", "")) {
            val r = client.health(HermesTarget("http://localhost:${server.port}/", null, bad)) as HermesResult.Err
            assertEquals(HermesError.InvalidKey, r.error)
        }
        assertEquals(0, server.requestCount)
    }
}
