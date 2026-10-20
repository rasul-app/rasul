package app.rasul.chat

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.rasul.data.BridgeProcess
import app.rasul.data.HermesError
import app.rasul.data.HermesResult
import app.rasul.ui.theme.RasulTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The paperclip sheet (Camera / Photos / Files) and the background tasks line and sheet, through the chat screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp")
class ChatAttachSheetUiTest {
    @get:Rule val compose = createComposeRule()

    private val picks = mutableListOf<String>()
    private var stops = 0

    private fun show(state: ChatState, filesViaBridge: Boolean = true) = compose.setContent {
        RasulTheme(false) {
            ChatScreenContent(
                state = state,
                title = "Chat",
                onBack = {},
                onDraft = {},
                onSend = {},
                onStop = { stops++ },
                onReconnect = {},
                onRefresh = {},
                onAnswerApproval = { _, _ -> },
                onRetryHistory = {},
                onCamera = { picks += "camera" },
                onPickPhotos = { picks += "photos" },
                onPickAnyFile = { picks += "files" },
                onPickFile = { picks += "text" },
                filesViaBridge = filesViaBridge,
                modelOptionsLoader = { HermesResult.Err(HermesError.NotFound) },
                selectedModel = null,
                tokenUsage = null,
                onSelectModel = {},
            )
        }
    }

    private fun ready(run: ActiveRun? = null) = ChatState(load = ChatLoad.Ready, sessionId = "s", run = run)

    private fun open() = compose.onNodeWithTag("chat_attach_button").performClick()

    @Test fun theSheetIsClosedUntilThePaperclipIsTapped() {
        show(ready())
        compose.onNodeWithTag("chat_attach_sheet").assertDoesNotExist()
        open()
        compose.onNodeWithTag("chat_attach_sheet").assertExists()
    }

    @Test fun eachTileRoutesOnABridgeChatAndClosesTheSheet() {
        show(ready())
        for ((tag, name) in listOf("chat_attach_camera" to "camera", "chat_attach_photos" to "photos", "chat_attach_any_file" to "files")) {
            open()
            compose.onNodeWithTag(tag).assertIsEnabled().performClick()
            compose.waitForIdle()
            assertEquals(name, picks.last())
            compose.onNodeWithTag("chat_attach_sheet").assertDoesNotExist()
        }
        assertEquals(listOf("camera", "photos", "files"), picks)
    }

    @Test fun aDirectChatKeepsCameraPhotosAndTheTextFileAndSwitchesFilesOff() {
        show(ready(), filesViaBridge = false)
        open()
        compose.onNodeWithTag("chat_attach_any_file").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("chat_attach_camera").assertIsEnabled().performClick()
        open()
        compose.onNodeWithTag("chat_attach_photos").performClick()
        open()
        compose.onNodeWithTag("chat_attach_text_file").performClick()
        assertEquals(listOf("camera", "photos", "text"), picks)
    }

    @Test fun theCloseButtonDismissesWithoutPicking() {
        show(ready())
        open()
        compose.onNodeWithTag("chat_attach_close").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("chat_attach_sheet").assertDoesNotExist()
        assertEquals(emptyList<String>(), picks)
    }

    @Test fun noPillWhenNothingRuns() {
        show(ready())
        compose.onNodeWithTag("chat_tasks_pill").assertDoesNotExist()
    }

    @Test fun theWorkingPillOpensTheSheetWithTheStopButton() {
        val tasks = BackgroundTasks(
            listOf(
                BgItem("a", BgKind.Process, "npm run build", 1L, BgStatus.Running, "proc_a"),
                BgItem("h", BgKind.Helper, "Research helper", 1L, BgStatus.Running),
            ),
        )
        show(ready(ActiveRun("run-1", 0L, StatusLabel.Thinking)).copy(background = tasks))
        compose.onNodeWithTag("chat_tasks_pill").assertExists().performClick()
        compose.onNodeWithTag("chat_tasks_sheet").assertExists()
        compose.onNodeWithTag("chat_tasks_stop").assertIsEnabled().performClick()
        assertEquals(1, stops)
    }

    @Test fun afterTheRunThePillShowsAndTheSheetHasNoStop() {
        show(ready().copy(remoteProcesses = listOf(BridgeProcess("proc_a", "npm run build", 1L, "s"))))
        compose.onNodeWithTag("chat_tasks_pill").assertExists().performClick()
        compose.onNodeWithTag("chat_tasks_sheet").assertExists()
        compose.onNodeWithTag("chat_tasks_stop").assertDoesNotExist()
    }
}
