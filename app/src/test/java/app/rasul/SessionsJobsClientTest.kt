package app.rasul

import app.rasul.data.HermesClient
import app.rasul.data.HermesError
import app.rasul.data.HermesResult
import app.rasul.data.HermesTarget
import app.rasul.jobs.JobState
import app.rasul.jobs.JobsClient
import app.rasul.sessions.SessionsClient
import app.rasul.settings.LanguageOption
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionsJobsClientTest {
    private lateinit var server: MockWebServer
    private val hermes = HermesClient()
    private val sessions = SessionsClient(hermes)
    private val jobs = JobsClient(hermes)

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun target(profile: String? = null) = HermesTarget("http://localhost:${server.port}/", profile, "secret-key")

    // Shapes follow api_server.py `_session_response` / `_message_response` (times are epoch seconds).
    private val sessionsJson = """
        {"object":"list","data":[
          {"id":"api_1_ab","source":"api_server","title":"Hello","started_at":1760000000.5,"last_active":1760000100.0,
           "ended_at":null,"message_count":4,"has_system_prompt":true,"is_internal_child":false},
          {"id":"s2","title":null,"started_at":1760000000,"ended_at":1760000050,"end_reason":"branched","message_count":0}
        ],"limit":50,"offset":0,"has_more":false}
    """.trimIndent()

    private val messagesJson = """
        {"object":"list","session_id":"api_1_ab","data":[
          {"id":1,"role":"user","content":"hi","timestamp":1760000001.0},
          {"id":2,"role":"assistant","content":[{"type":"text","text":"hello"},{"type":"text","text":"there"}]},
          {"id":3,"role":"tool","tool_name":"ls","content":null}
        ],"pagination":{"limit":500,"offset":0,"order":"latest","returned":3}}
    """.trimIndent()

    @Test fun sessionsListParsedWithAuthAndQuery() = runBlocking {
        server.enqueue(MockResponse().setBody(sessionsJson))
        val r = sessions.list(target("alice")) as HermesResult.Ok
        assertEquals(2, r.value.size)
        assertTrue(r.value[0].isActive)
        assertFalse(r.value[1].isActive)
        assertEquals(4, r.value[0].messageCount)
        val req = server.takeRequest()
        assertEquals("/p/alice/api/sessions?limit=50", req.path)
        assertEquals("Bearer secret-key", req.getHeader("Authorization"))
    }

    @Test fun messagesParsedStringAndPartsContent() = runBlocking {
        server.enqueue(MockResponse().setBody(messagesJson))
        val r = sessions.messages(target(), "api_1_ab") as HermesResult.Ok
        assertEquals(listOf("hi", "hello\nthere", ""), r.value.map { it.text() })
        assertEquals("/api/sessions/api_1_ab/messages?inline_images=false", server.takeRequest().path)
    }

    @Test fun sessions401IsUnauthorizedAndBadBodyIsIncompatible() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(HermesError.Unauthorized, (sessions.list(target()) as HermesResult.Err).error)
        server.enqueue(MockResponse().setBody("<html>not hermes</html>"))
        assertTrue((sessions.list(target()) as HermesResult.Err).error is HermesError.Incompatible)
    }

    @Test fun unsafeSessionIdNeverReachesTheServer() = runBlocking {
        val r = sessions.messages(target(), "../v1/runs") as HermesResult.Err
        assertEquals(HermesError.NotFound, r.error)
        assertEquals(0, server.requestCount)
    }

    private val jobsJson = """
        {"jobs":[
          {"id":"j1","name":"Daily","schedule":{"kind":"cron","expr":"0 9 * * *"},"schedule_display":"0 9 * * *",
           "enabled":true,"state":"scheduled","next_run_at":"2026-10-16T09:00:00+02:00"},
          {"id":"j2","name":"Weekly","schedule_display":"every 7d","enabled":false,"state":"paused","next_run_at":null}
        ]}
    """.trimIndent()

    @Test fun jobsListParsedWithDisabledIncluded() = runBlocking {
        server.enqueue(MockResponse().setBody(jobsJson))
        val r = jobs.list(target()) as HermesResult.Ok
        assertEquals(listOf(false, true), r.value.map { it.isPaused })
        assertEquals("/api/jobs?include_disabled=true", server.takeRequest().path)
    }

    @Test fun pauseResumeRunArePostsToTheRightPaths() = runBlocking {
        for (verb in listOf("pause", "resume", "run")) {
            server.enqueue(MockResponse().setBody("""{"job":{"id":"j1","name":"Daily","enabled":false,"state":"paused"}}"""))
            val r = (when (verb) {
                "pause" -> jobs.pause(target(), "j1")
                "resume" -> jobs.resume(target(), "j1")
                else -> jobs.runNow(target(), "j1")
            }) as HermesResult.Ok
            assertTrue(r.value.isPaused)
            val req = server.takeRequest()
            assertEquals("POST", req.method)
            assertEquals("/api/jobs/j1/$verb", req.path)
            assertEquals("{}", req.body.readUtf8())
            assertEquals("Bearer secret-key", req.getHeader("Authorization"))
        }
    }

    @Test fun jobs401And404() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(HermesError.Unauthorized, (jobs.pause(target(), "j1") as HermesResult.Err).error)
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(HermesError.NotFound, (jobs.runNow(target(), "gone") as HermesResult.Err).error)
    }

    @Test fun missingJobOnAValidProfileIsResourceNotFoundNotProfileUnknown() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"Job not found"}"""))
        assertEquals(HermesError.ResourceNotFound, (jobs.runNow(target("alice"), "gone") as HermesResult.Err).error)
        // A real session 404 uses the nested error envelope.
        server.enqueue(MockResponse().setResponseCode(404).setBody(
            """{"error":{"message":"Session not found: gone","type":"invalid_request_error","param":null,"code":"session_not_found"}}""",
        ))
        assertEquals(HermesError.ResourceNotFound, (sessions.messages(target("alice"), "gone") as HermesResult.Err).error)
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"Unknown or unconfigured profile"}"""))
        assertEquals(HermesError.ProfileUnknown("alice"), (jobs.list(target("alice")) as HermesResult.Err).error)
    }

    @Test fun wrongShapeIsIncompatibleNotEmpty() = runBlocking {
        for (body in listOf("{}", """{"error":"nope"}""", """{"jobs":null,"data":null}""")) {
            server.enqueue(MockResponse().setBody(body))
            assertTrue((jobs.list(target()) as HermesResult.Err).error is HermesError.Incompatible)
            server.enqueue(MockResponse().setBody(body))
            assertTrue((sessions.list(target()) as HermesResult.Err).error is HermesError.Incompatible)
            server.enqueue(MockResponse().setBody(body))
            assertTrue((sessions.messages(target(), "s1") as HermesResult.Err).error is HermesError.Incompatible)
        }
        // An explicit empty array is still a valid empty result.
        server.enqueue(MockResponse().setBody("""{"jobs":[]}"""))
        assertEquals(0, (jobs.list(target()) as HermesResult.Ok).value.size)
    }

    // cron/jobs.py:1627-1629: a finished job is kept as enabled=false, state="completed".
    @Test fun completedJobIsTerminalNotPaused() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"jobs":[{"id":"c1","enabled":false,"state":"completed","next_run_at":null},
                    {"id":"e1","enabled":false,"state":"error"},
                    {"id":"p1","enabled":false,"state":"paused"},
                    {"id":"s1","enabled":true,"state":"paused"},
                    {"id":"n1","enabled":false}]}""",
            ),
        )
        val r = (jobs.list(target()) as HermesResult.Ok).value
        assertEquals(
            listOf(JobState.Completed, JobState.Error, JobState.Paused, JobState.Scheduled, JobState.Paused),
            r.map { it.jobState },
        )
        assertEquals(listOf(true, true, false, false, false), r.map { it.jobState.isTerminal })
        assertFalse(r[0].isPaused)
    }

    @Test fun languageOptionMapping() {
        assertTrue(LanguageOption.System.toLocales().isEmpty)
        assertEquals("en", LanguageOption.English.toLocales().toLanguageTags())
        assertEquals("ar", LanguageOption.Arabic.toLocales().toLanguageTags())
        assertEquals(LanguageOption.System, LanguageOption.fromTags(""))
        assertEquals(LanguageOption.Arabic, LanguageOption.fromTags("ar-SA"))
        assertEquals(LanguageOption.English, LanguageOption.fromTags("en-US,ar"))
        assertEquals(LanguageOption.System, LanguageOption.fromTags("fr"))
    }
}
