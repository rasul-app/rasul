package app.rasul

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.rasul.chat.ApprovalSheet
import app.rasul.chat.ApprovalUi
import app.rasul.ui.theme.RasulTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A very long command must never push the two answer buttons off a small screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatApprovalSheetTest {
    @get:Rule val compose = createComposeRule()

    private val longCommand = (1..80).joinToString("\n") { "line $it: " + "x".repeat(40) } // about 4000 characters

    @Test fun bothButtonsAreReachableAndClickableWithALongCommand() {
        var once = 0
        var denied = 0
        compose.setContent {
            RasulTheme(false) {
                ApprovalSheet(ApprovalUi("r1", longCommand, "Run a long script"), busy = false, onOnce = { once++ }, onDeny = { denied++ }, onHide = {})
            }
        }
        compose.onNodeWithText("Approve once").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithText("Deny").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(1, once)
        assertEquals(1, denied)
    }
}
