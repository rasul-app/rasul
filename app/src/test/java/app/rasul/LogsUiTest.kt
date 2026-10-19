package app.rasul

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.rasul.bridge.DesktopError
import app.rasul.bridge.LogFile
import app.rasul.bridge.LogLevel
import app.rasul.desktop.LogsContent
import app.rasul.desktop.LogsState
import app.rasul.ui.theme.RasulTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h700dp")
class LogsUiTest {
    @get:Rule val compose = createComposeRule()

    private val files = mutableListOf<LogFile>()
    private val levels = mutableListOf<LogLevel>()
    private var retries = 0

    private fun show(state: LogsState) {
        compose.setContent {
            RasulTheme(false) { LogsContent(state, "PC", {}, { retries++ }, { files += it }, { levels += it }) }
        }
        compose.waitForIdle()
    }

    @Test fun linesAreShownWithTheControls() {
        show(LogsState(lines = listOf("2026-10-18 09:14:51,977 ERROR agent.tools: failed", "2026-10-18 09:15:02,310 INFO agent.run: ok"), loaded = true))
        compose.onNodeWithText("2026-10-18 09:14:51,977 ERROR agent.tools: failed").assertExists()
        compose.onNodeWithText("Errors").assertIsSelected()
        compose.onNodeWithText("All").assertIsSelected()
        compose.onNodeWithText("Copy all").assertExists()
    }

    @Test fun choosingAFileOrLevelReportsIt() {
        show(LogsState(lines = listOf("x"), loaded = true))
        compose.onNodeWithText("Gateway").performClick()
        compose.onNodeWithText("Warning").performClick()
        assertEquals(listOf(LogFile.Gateway), files)
        assertEquals(listOf(LogLevel.Warning), levels)
    }

    @Test fun copyShowsACopiedConfirmation() {
        show(LogsState(lines = listOf("x"), loaded = true))
        compose.onNodeWithText("Copy all").performClick()
        compose.onNodeWithText("Copied").assertExists()
    }

    @Test fun copyPutsExactlyTheShownLinesOnTheClipboard() {
        val lines = listOf("2026-10-18 09:14:51,977 ERROR agent.tools: failed", "2026-10-18 09:15:02,310 INFO agent.run: ok")
        show(LogsState(lines = lines, loaded = true))
        compose.onNodeWithText("Copy all").performClick()
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        assertEquals(lines.joinToString("\n"), clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test fun emptyAnswerSaysNoLinesYet() {
        show(LogsState(loaded = true))
        compose.onNodeWithText("No lines yet").assertExists()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
    }

    @Test fun loadingShowsNoErrorOrLines() {
        show(LogsState(loading = true, lines = emptyList()))
        compose.onNodeWithText("Loading logs…").assertExists()
        compose.onAllNodesWithText("Retry").assertCountEquals(0)
    }

    @Test fun missingLogHasItsOwnMessageAndRetry() {
        show(LogsState(error = DesktopError.LogsNotFound))
        compose.onNodeWithText("This log does not exist on your PC yet. It appears once Hermes writes to it.").assertExists()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
    }

    @Test fun rateLimitedAndDesktopErrorsKeepTheExistingCopy() {
        show(LogsState(error = DesktopError.RateLimited))
        compose.onNodeWithText("Too many requests. Wait a little, then retry.").assertExists()
    }

    @Test fun directConnectionShowsOnlyTheBridgeNotice() {
        show(LogsState(direct = true))
        compose.onNodeWithText("Hermes logs need Rasul Bridge on the PC.").assertExists()
        compose.onAllNodesWithText("Gateway").assertCountEquals(0)
    }
}
