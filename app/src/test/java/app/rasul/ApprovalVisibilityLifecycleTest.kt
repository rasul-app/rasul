package app.rasul

import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.testing.TestNavHostController
import app.rasul.bridge.ApprovalItemDto
import app.rasul.bridge.ApprovalsRepository
import app.rasul.bridge.bridgeBotId
import app.rasul.notifications.ApprovalAlertDecider
import app.rasul.ui.RasulNav
import app.rasul.ui.theme.RasulTheme
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The "approvals screen is open" flag must follow the real activity lifecycle, not only composition. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h700dp")
class ApprovalVisibilityLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun stoppingTheActivityOnTheApprovalsScreenLetsTheNextApprovalAlert() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val container = AppContainer(context)
        lateinit var nav: TestNavHostController
        try {
            compose.setContent {
                nav = remember {
                    TestNavHostController(context).apply { navigatorProvider.addNavigator(ComposeNavigator()) }
                }
                RasulTheme(false) { RasulNav(container, false, false, {}, { _, cancel -> cancel() }, nav = nav) }
            }
            compose.runOnIdle { nav.navigate("approvals/b1/default") }
            compose.waitForIdle()
            val bot = bridgeBotId("b1", "default")
            assertEquals(bot, container.approvalsVisible.value)

            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.waitForIdle()
            assertNull(container.approvalsVisible.value)
            val feeds = mapOf(bot to ApprovalsRepository.Feed(
                items = listOf(ApprovalItemDto("a".repeat(32), "sk", "default", "shell", "cmd", "desc", true, false, 4_000_000_000.0)),
                loading = false))
            assertEquals(1, ApprovalAlertDecider().diff(feeds, container.approvalsVisible.value, 1_000).first.size)

            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitForIdle()
            assertEquals(bot, container.approvalsVisible.value)
        } finally {
            container.appScope.cancel()
        }
    }
}
