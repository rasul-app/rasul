package app.rasul.chat

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.rasul.data.HermesClient
import app.rasul.data.HermesError
import app.rasul.data.HermesResult
import app.rasul.data.HermesTarget
import app.rasul.data.ModelOptionsDto
import app.rasul.data.ModelOptionsProviderDto
import app.rasul.data.RunModelSelection
import app.rasul.data.RunsClient
import app.rasul.sessions.SessionControlClient
import app.rasul.ui.theme.RasulTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

private val example = RunModelSelection("example-provider", "example-model")

private fun ready(
    fork: Boolean = true,
    run: ActiveRun? = null,
    forkTitle: String? = null,
    failure: ChatFailure? = null,
    forking: Boolean = false,
) = ChatState(
    load = ChatLoad.Ready, sessionId = "sample-session", run = run,
    supportsFork = fork, forkTitle = forkTitle, actionFailure = failure, forking = forking,
)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp")
class ChatForkModelUiTest {
    @get:Rule val compose = createComposeRule()

    private fun show(
        state: ChatState,
        selected: RunModelSelection? = null,
        onFork: () -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        compose.setContent {
            RasulTheme(dark = false) {
                ChatScreenContent(
                    state = state, title = "Sample chat", onBack = {}, onDraft = {}, onSend = {}, onStop = {},
                    onReconnect = {}, onRefresh = {}, onAnswerApproval = { _, _ -> }, onRetryHistory = {},
                    modelOptionsLoader = { HermesResult.Ok(ModelOptionsDto(providers = emptyList())) },
                    selectedModel = selected, tokenUsage = null, onSelectModel = {},
                    onFork = onFork, onDismissAction = onDismiss,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun openMenu() {
        compose.onNodeWithContentDescription("Chat options").performClick()
        compose.waitForIdle()
    }

    @Test fun forkItemIsInTheOverflowMenuAndCallsFork() {
        var forks = 0
        show(ready(), onFork = { forks++ })
        openMenu()
        compose.onNodeWithTag("chat_fork_menu_item").assertIsEnabled().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Fork chat").performClick()
        assertEquals(1, forks)
    }

    @Test fun forkItemIsHiddenWhenTheServerDoesNotSupportIt() {
        show(ready(fork = false))
        openMenu()
        compose.onNodeWithText("Token use").assertExists()
        assertTrue(compose.onAllNodesWithTagCount("chat_fork_menu_item") == 0)
    }

    @Test fun forkItemIsDisabledWhileAReplyRunsAndWhileForking() {
        show(ready(run = ActiveRun("sample-run", 0L, StatusLabel.Thinking)))
        openMenu()
        compose.onNodeWithTag("chat_fork_menu_item").assertIsNotEnabled()
    }

    @Test fun forkInProgressIsShownAndDisabled() {
        show(ready(forking = true))
        openMenu()
        compose.onNodeWithText("Forking…").assertExists()
        compose.onNodeWithTag("chat_fork_menu_item").assertIsNotEnabled()
    }

    @Test fun aForkedChatShowsItsTitleUnderTheName() {
        show(ready(forkTitle = "Sample fork"))
        compose.onNodeWithTag("chat_fork_title", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Fork · Sample fork").assertExists()
    }

    @Test fun aForkWithoutATitleStillSaysFork() {
        show(ready(forkTitle = ""))
        compose.onNodeWithText("Fork").assertExists()
    }

    @Test fun anOrdinaryChatShowsNoForkLine() {
        show(ready())
        assertTrue(compose.onAllNodesWithTagCount("chat_fork_title") == 0)
    }

    @Test fun forkFailureTextIsSpecificAndDismissible() {
        var dismissed = false
        show(ready(failure = ChatFailure.ForkFailed(HermesError.Server(429, "fork_rate_limited"))), onDismiss = { dismissed = true })
        compose.onNodeWithText("Too many session forks. Wait a minute before trying again.").assertExists()
        compose.onNodeWithText("Dismiss").performClick()
        assertTrue(dismissed)
    }

    @Test fun missingForkRouteSaysUpdateHermes() {
        show(ready(failure = ChatFailure.ForkFailed(HermesError.NotFound)))
        compose.onNodeWithText("The agent doesn't support this action. Update it and try again.").assertExists()
    }

    @Test fun aPickedModelShowsItsPlainName() {
        show(ready(), selected = example)
        compose.onNodeWithTag("chat_model_label_name", useUnmergedTree = true).assert(hasText("example-model"))
    }

}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String): Int =
    onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-w390dp-h844dp")
class ChatForkModelArabicUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun arabicMenuErrorAndChipAreLocalizedAndReachable() {
        compose.setContent {
            RasulTheme(dark = false) {
                ChatScreenContent(
                    state = ready(forkTitle = "فرع تجريبي"),
                    title = "مثال", onBack = {}, onDraft = {}, onSend = {}, onStop = {}, onReconnect = {}, onRefresh = {},
                    onAnswerApproval = { _, _ -> }, onRetryHistory = {},
                    modelOptionsLoader = { HermesResult.Ok(ModelOptionsDto(providers = emptyList())) },
                    selectedModel = example, tokenUsage = null, onSelectModel = {},
                )
            }
        }
        compose.onNodeWithText("فرع · فرع تجريبي").assertExists()
        compose.onNodeWithTag("chat_model_label_name", useUnmergedTree = true).assert(hasText("example-model"))
        compose.onNodeWithContentDescription("خيارات المحادثة").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("chat_fork_menu_item").assertIsEnabled().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("إنشاء فرع من المحادثة").assertExists()
    }
}

/** Real ChatViewModel + real SessionControlClient + a local server: the whole path from a tap to the request. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp")
class ChatForkModelWiringUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private val seen = CopyOnWriteArrayList<String>()
    private val bodies = CopyOnWriteArrayList<String>()
    private var forkStatus = 201
    private var advertise = "\"session_fork\":true,"
    private val runs = RunsClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request.method + " " + request.path
                val path = request.path.orEmpty()
                return when {
                    path == "/v1/capabilities" -> MockResponse().setBody(
                        "{\"object\":\"hermes.api_server.capabilities\",\"features\":{$advertise\"run_submission\":true,\"run_events_sse\":true,\"run_stop\":true}}",
                    )
                    path.endsWith("/fork") -> {
                        bodies += request.body.readUtf8()
                        if (forkStatus == 201) MockResponse().setResponseCode(201).setBody("""{"session":{"id":"fork-1","title":"Sample fork"}}""")
                        else MockResponse().setResponseCode(forkStatus).setBody("""{"error":{"message":"too many","code":"fork_rate_limited"}}""")
                    }
                    path.endsWith("/messages") -> MockResponse().setBody("""{"data":[]}""")
                    path.startsWith("/api/sessions/") -> MockResponse().setBody("""{"session":{"id":"sess-1","title":"Sample"}}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun tearDown() {
        scope.cancel()
        runCatching { server.shutdown() }
    }

    private fun content(onOpenFork: (String, String) -> Unit): ChatViewModel {
        val target = HermesTarget("http://localhost:${server.port}/", null, "sample-key")
        val client = HermesClient()
        val vm = ChatViewModel(
            TargetLoader { HermesResult.Ok(target) },
            runs,
            app.rasul.sessions.SessionsClient(client),
            object : RunMemory {
                override suspend fun sessionId() = "sess-1"
                override suspend fun currentSessionId() = "sess-1"
                override suspend fun activeRun(): String? = null
                override suspend fun lastEventId(): Long? = null
                override suspend fun setActiveRun(runId: String?, lastEventId: Long?, anchor: Anchor) = Unit
            },
            OrderedWriter(scope),
            scope = scope,
            control = SessionControlClient(client),
        )
        compose.setContent {
            RasulTheme(dark = false) {
                ChatScreen(
                    vm = vm, title = "Sample chat", onBack = {}, runs = runs,
                    modelOptionsLoader = {
                        HermesResult.Ok(ModelOptionsDto(listOf(ModelOptionsProviderDto("example-provider", "Example provider", listOf("example-model")))))
                    },
                    onOpenFork = onOpenFork,
                )
            }
        }
        return vm
    }

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(timeoutMillis = 10_000) { condition() }

    @Test fun forkSuccessCallsTheRouteAndOpensTheNewChat() {
        var opened: String? = null
        val vm = content { id, _ -> opened = id }
        waitFor { vm.state.value.supportsFork }
        compose.onNodeWithContentDescription("Chat options").performClick()
        compose.onNodeWithText("Fork chat").performClick()
        waitFor { opened != null }
        assertEquals("fork-1", opened)
        assertTrue(seen.contains("POST /api/sessions/sess-1/fork"))
        assertEquals("{}", bodies.single())
    }

    @Test fun forkRateLimitShowsTheSpecificMessageAndOpensNothing() {
        forkStatus = 429
        var opened: String? = null
        val vm = content { id, _ -> opened = id }
        waitFor { vm.state.value.supportsFork }
        compose.onNodeWithContentDescription("Chat options").performClick()
        compose.onNodeWithText("Fork chat").performClick()
        waitFor { vm.state.value.actionFailure != null }
        compose.waitForIdle()
        compose.onNodeWithText("Too many session forks. Wait a minute before trying again.").assertExists()
        assertEquals(null, opened)
    }

    @Test fun unsupportedServerHidesForkAndNeverCallsIt() {
        advertise = ""
        val vm = content { _, _ -> }
        waitFor { vm.state.value.load == ChatLoad.Ready }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Chat options").performClick()
        compose.onNodeWithText("Token use").assertExists()
        assertEquals(0, compose.onAllNodesWithTagCount("chat_fork_menu_item"))
        assertTrue(seen.none { it.endsWith("/fork") })
    }

    private fun pickModel(vm: ChatViewModel, name: String = "example-model") {
        waitFor { vm.state.value.load == ChatLoad.Ready }
        compose.onNodeWithTag("chat_model_label").performClick()
        waitFor { compose.onAllNodes(hasText(name)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(name).performClick()
        compose.waitForIdle()
    }

    @Test fun modelPickStaysOnThePhoneAndMakesNoRequestToTheModelRoute() {
        val vm = content { _, _ -> }
        pickModel(vm)
        assertEquals(example, runs.selectedModelForChat("sess-1"))
        compose.onNodeWithTag("chat_model_label_name", useUnmergedTree = true).assert(hasText("example-model"))
        assertTrue(seen.none { it.endsWith("/model") })
    }

    @Test fun serverDefaultWorksAfterAPick() {
        val vm = content { _, _ -> }
        pickModel(vm)
        compose.onNodeWithTag("chat_model_label").performClick()
        waitFor { compose.onAllNodes(hasText("Server default")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Server default")).onLast().performClick()
        compose.waitForIdle()
        assertEquals(null, runs.selectedModelForChat("sess-1"))
        assertEquals(null, vm.state.value.actionFailure)
    }

    @Test fun aForkStartsWithTheModelOfTheChatItCameFrom() {
        var opened: String? = null
        val vm = content { id, _ -> opened = id }
        pickModel(vm)
        waitFor { vm.state.value.supportsFork }
        compose.onNodeWithContentDescription("Chat options").performClick()
        compose.onNodeWithText("Fork chat").performClick()
        waitFor { opened != null }
        assertEquals(example, runs.selectedModelForChat("fork-1"))
        waitFor { vm.state.value.forkedSessionId == null }
    }
}
