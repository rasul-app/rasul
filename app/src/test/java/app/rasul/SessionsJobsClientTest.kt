package app.rasul

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import app.rasul.data.HermesClient
import app.rasul.data.HermesError
import app.rasul.data.HermesResult
import app.rasul.data.HermesTarget
import app.rasul.jobs.JobState
import app.rasul.jobs.JobDeleteConfirmDialog
import app.rasul.jobs.JobDto
import app.rasul.jobs.JobFormContent
import app.rasul.jobs.JobsClient
import app.rasul.sessions.SessionDeleteConfirmDialog
import app.rasul.sessions.SessionDto
import app.rasul.sessions.SessionList
import app.rasul.sessions.SessionRenameContent
import app.rasul.sessions.SessionsClient
import app.rasul.settings.LanguageOption
import app.rasul.ui.theme.RasulTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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

        server.enqueue(MockResponse().setBody("""{"object":"hermes.session","session":{"id":"api_1_ab","title":"Updated"}}"""))
        val renamed = sessions.rename(target("alice"), "api_1_ab", "Updated") as HermesResult.Ok
        assertEquals("Updated", renamed.value.title)
        val rename = server.takeRequest()
        assertEquals("PATCH", rename.method)
        assertEquals("/p/alice/api/sessions/api_1_ab", rename.path)
        assertEquals("""{"title":"Updated"}""", rename.body.readUtf8())

        server.enqueue(MockResponse().setBody(
            """{"object":"hermes.session.deleted","id":"api_1_ab","deleted":true}""",
        ))
        assertTrue(sessions.delete(target("alice"), "api_1_ab") is HermesResult.Ok)
        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("/p/alice/api/sessions/api_1_ab", delete.path)
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
          {"id":"j1","name":"Daily","schedule":{"kind":"cron","expr":"0 9 * * *"},"schedule_display":"0 9 * * *","prompt":"Summarize recent activity",
           "enabled":true,"state":"scheduled","next_run_at":"2026-10-16T09:00:00+02:00"},
          {"id":"j2","name":"Weekly","schedule_display":"every 7d","enabled":false,"state":"paused","next_run_at":null}
        ]}
    """.trimIndent()

    @Test fun jobsListParsedWithDisabledIncluded() = runBlocking {
        server.enqueue(MockResponse().setBody(jobsJson))
        val r = jobs.list(target()) as HermesResult.Ok
        assertEquals(listOf(false, true), r.value.map { it.isPaused })
        assertEquals("0 9 * * *", r.value[0].scheduleInput)
        assertEquals("Summarize recent activity", r.value[0].prompt)
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

        server.enqueue(MockResponse().setBody("""{"job":{"id":"j1","name":"Daily","enabled":true,"state":"scheduled"}}"""))
        assertTrue(jobs.create(target(), "Daily", "0 9 * * *", "Example prompt") is HermesResult.Ok)
        val create = server.takeRequest()
        assertEquals("POST", create.method)
        assertEquals("/api/jobs", create.path)
        assertEquals("""{"name":"Daily","schedule":"0 9 * * *","prompt":"Example prompt"}""", create.body.readUtf8())

        server.enqueue(MockResponse().setBody("""{"job":{"id":"j1","name":"Daily","enabled":true,"state":"scheduled"}}"""))
        assertTrue(jobs.update(target(), "j1", "Daily", "0 9 * * *", "Example prompt") is HermesResult.Ok)
        val update = server.takeRequest()
        assertEquals("PATCH", update.method)
        assertEquals("/api/jobs/j1", update.path)
        assertEquals("""{"name":"Daily","schedule":"0 9 * * *","prompt":"Example prompt"}""", update.body.readUtf8())

        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        assertTrue(jobs.delete(target(), "j1") is HermesResult.Ok)
        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("/api/jobs/j1", delete.path)
    }

    @Test fun structuredSessionValidationCodeIsPreserved() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"error":{"message":"Title too long (201 chars, max 200)","type":"invalid_request_error","param":null,"code":"invalid_title"}}""",
            ),
        )

        assertEquals(
            HermesError.Server(400, "Title too long (201 chars, max 200)", "invalid_title"),
            (sessions.rename(target(), "s1", "A title") as HermesResult.Err).error,
        )
    }

    @Test fun jobs401And404() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(HermesError.Unauthorized, (jobs.pause(target(), "j1") as HermesResult.Err).error)
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(HermesError.NotFound, (jobs.runNow(target(), "gone") as HermesResult.Err).error)
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"Name is required"}"""))
        assertEquals(
            HermesError.Server(400, "Name is required"),
            (jobs.create(target(), "", "", "") as HermesResult.Err).error,
        )
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h700dp")
class SessionsJobsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun jobFormBlocksEmptyNameAndSchedule() {
        var sends = 0
        compose.setContent {
            RasulTheme(false) {
                JobFormContent(null, saving = false, serverError = null, onDismiss = {}) { _, _, _ -> sends++ }
            }
        }
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Enter a name.").assertExists()
        compose.onNodeWithText("Enter a schedule.").assertExists()
        assertEquals(0, sends)

    }

    @Test fun jobFormBlocksBlankPromptWithLocalizedMessage() {
        var sends = 0
        compose.setContent {
            RasulTheme(false) {
                JobFormContent(null, saving = false, serverError = null, onDismiss = {}) { _, _, _ -> sends++ }
            }
        }
        compose.onNodeWithText("Name").performTextInput("Daily summary")
        compose.onNodeWithText("Schedule").performTextInput("0 9 * * *")
        compose.onNodeWithText("Prompt").performTextInput("   ")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Enter a prompt.").assertExists()
        assertEquals(0, sends)
    }

    @Test fun jobFormShowsServerValidationMessage() {
        compose.setContent {
            RasulTheme(false) {
                JobFormContent(
                    null, saving = false, serverError = "Schedule is invalid", onDismiss = {}, onSave = { _, _, _ -> },
                )
            }
        }
        compose.onNodeWithText("Schedule is invalid").assertExists()
    }

    @Test fun cancelOnJobAndSessionDialogsDoesNotCallAction() {
        var jobDeletes = 0
        var sessionDeletes = 0
        var renames = 0
        compose.setContent {
            RasulTheme(false) {
                var stage by remember { mutableStateOf(0) }
                when (stage) {
                    0 -> JobDeleteConfirmDialog("Morning summary", { stage = 1 }, { jobDeletes++ })
                    1 -> SessionDeleteConfirmDialog("Current title", { stage = 2 }, { sessionDeletes++ })
                    else -> SessionRenameContent(
                        "Current title", saving = false, serverError = null, onCancel = { stage = 3 },
                        onSave = { renames++ },
                    )
                }
            }
        }
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, jobDeletes)
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, sessionDeletes)
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, renames)
    }

    @Test fun jobFormCapturesSubmittedValuesAndLocksFieldsWhileRequestIsSuspended() {
        val request = SuspendedJobRequest()
        compose.setContent {
            RasulTheme(false) {
                var saving by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                JobFormContent(
                    JobDto(
                        "job-one", "Morning summary", "0 9 * * *", schedule = JsonPrimitive("0 9 * * *"),
                        prompt = "Original prompt",
                    ),
                    saving = saving,
                    serverError = null,
                    onDismiss = {},
                    onSave = { name, schedule, prompt ->
                        saving = true
                        scope.launch {
                            request.submit(name, schedule, prompt)
                            saving = false
                        }
                    },
                )
            }
        }
        compose.onNode(hasText("Morning summary") and hasSetTextAction()).performTextReplacement("Evening summary")
        compose.onNode(hasText("0 9 * * *") and hasSetTextAction()).performTextReplacement("0 10 * * *")
        compose.onNode(hasText("Original prompt") and hasSetTextAction()).performTextReplacement("Updated prompt")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { request.started.isCompleted }
        assertEquals(Triple("Evening summary", "0 10 * * *", "Updated prompt"), request.values)
        compose.onNodeWithText("Name").assertIsNotEnabled()
        compose.onNodeWithText("Schedule").assertIsNotEnabled()
        compose.onNodeWithText("Prompt").assertIsNotEnabled()
        request.release.complete(Unit)
        compose.waitForIdle()
    }

    @Test fun sessionRenameCapturesTitleAndLocksFieldWhileRequestIsSuspended() {
        val request = SuspendedTitleRequest()
        compose.setContent {
            RasulTheme(false) {
                var saving by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                SessionRenameContent(
                    "Current title", saving = saving, serverError = null, onCancel = {}, onSave = { title ->
                        saving = true
                        scope.launch {
                            request.submit(title)
                            saving = false
                        }
                    },
                )
            }
        }
        compose.onNode(hasText("Current title") and hasSetTextAction()).performTextReplacement("Updated title")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { request.started.isCompleted }
        assertEquals("Updated title", request.title)
        compose.onNodeWithText("Title").assertIsNotEnabled()
        request.release.complete(Unit)
        compose.waitForIdle()
    }

    @Test fun sessionRowLongPressOpensNamedRenameAction() {
        var renamed: String? = null
        compose.setContent {
            RasulTheme(false) {
                SessionList(
                    listOf(SessionDto("session-one", "Current title")), "source", 1_760_000_000_000L, onOpen = {},
                    onRename = { renamed = it.title },
                )
            }
        }
        compose.onNode(hasText("Current title") and hasClickAction()).performTouchInput { longClick() }
        compose.onNodeWithText("Rename")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Button))
            .performClick()
        assertEquals("Current title", renamed)
    }

    private class SuspendedJobRequest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var values: Triple<String, String, String>? = null

        suspend fun submit(name: String, schedule: String, prompt: String) {
            values = Triple(name, schedule, prompt)
            started.complete(Unit)
            release.await()
        }
    }

    private class SuspendedTitleRequest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var title: String? = null

        suspend fun submit(value: String) {
            title = value
            started.complete(Unit)
            release.await()
        }
    }
}
