package app.rasul

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.rasul.bridge.*
import app.rasul.data.HermesError
import app.rasul.ui.*
import app.rasul.ui.theme.RasulTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h700dp")
class BridgeBotsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun readyBotHasButtonRoleNameAndCapturedBridgeProfileAction() {
        var opened: Pair<String, String>? = null
        compose.setContent {
            RasulTheme(false) {
                BotsListContent(ConnectionsState.Loaded(emptyList()), {}, {}, {}, {},
                    bridges = BridgeBotsState(listOf(BridgeGroup("a", "Bridge", listOf(BridgeProfile("default", "default", "default", true, true)), false))),
                    onBridgeOpen = { bridge, profile -> opened = bridge to profile })
            }
        }
        compose.onNode(hasText("default") and hasClickAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).performClick()
        assertEquals("a" to "default", opened)
    }

    @Test fun notReadyBotShowsSpecificSetupMessageAndHasNoOpenAction() {
        compose.setContent {
            RasulTheme(false) {
                BotsListContent(ConnectionsState.Loaded(emptyList()), {}, {}, {}, {},
                    bridges = BridgeBotsState(listOf(BridgeGroup("a", "Bridge", listOf(BridgeProfile("default", "default", "default", false, true)), false))))
            }
        }
        compose.onNodeWithText("default").assertHasNoClickAction()
        compose.onNodeWithText("This bot is not ready. Complete its setup on the computer.").assertExists()
    }

    @Test fun revokedBridgeShowsSpecificMessageAndDisablesCachedBot() {
        compose.setContent {
            RasulTheme(false) {
                BotsListContent(ConnectionsState.Loaded(emptyList()), {}, {}, {}, {},
                    bridges = BridgeBotsState(listOf(BridgeGroup("a", "Bridge", listOf(BridgeProfile("default", "default", "default", true, true)), false, HermesError.DeviceRevoked))))
            }
        }
        compose.onNodeWithText("This device no longer has access. Remove the bridge and pair again.").assertExists()
        compose.onNodeWithText("default").assertHasNoClickAction()
    }
}
