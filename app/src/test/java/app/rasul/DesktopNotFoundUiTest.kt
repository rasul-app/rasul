package app.rasul

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import app.rasul.bridge.DesktopError
import app.rasul.desktop.RunHistoryContent
import app.rasul.desktop.RunHistoryState
import app.rasul.desktop.UsageContent
import app.rasul.desktop.UsageState
import app.rasul.ui.theme.RasulTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** An older PC bridge answers 404 for routes it predates; each desktop screen says to update it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h700dp")
class DesktopNotFoundUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun usageOnAnOlderBridgeSaysToUpdateItInsteadOfJobOrSession() {
        compose.setContent {
            RasulTheme(false) {
                UsageContent(UsageState(error = DesktopError.NotFound), "PC", {}, {}, {})
            }
        }
        compose.waitForIdle()
        compose.onAllNodesWithText("Update Rasul on your PC to use this.").assertCountEquals(1)
        compose.onAllNodesWithText("The server does not have that job or session any more", substring = true).assertCountEquals(0)
    }

    @Test fun runHistoryOnAnOlderBridgeSaysToUpdateItInsteadOfJobOrSession() {
        compose.setContent {
            RasulTheme(false) {
                RunHistoryContent(RunHistoryState(error = DesktopError.NotFound), "job", {}, {})
            }
        }
        compose.waitForIdle()
        compose.onAllNodesWithText("Update Rasul on your PC to use this.").assertCountEquals(1)
        compose.onAllNodesWithText("The server does not have that job or session any more", substring = true).assertCountEquals(0)
    }
}
