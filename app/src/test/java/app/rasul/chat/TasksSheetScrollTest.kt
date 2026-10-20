package app.rasul.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.rasul.ui.theme.RasulTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.NumberFormat
import java.util.Locale

/** The tasks sheet when its rows do not fit, the row labels, and the digits of the "Started" line. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp")
class TasksSheetScrollTest {
    @get:Rule val compose = createComposeRule()

    private val now = 1_792_100_000_000L

    private fun rows(count: Int) = (1..count).map {
        BgItem("proc_$it", if (it % 3 == 0) BgKind.Helper else BgKind.Process, "task number $it", now - it * 60_000L, BgStatus.Running, "proc_$it")
    }

    private fun show(items: List<BgItem>, fontScale: Float = 1f, heightDp: Int = 600) = compose.setContent {
        RasulTheme(false) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                Box(Modifier.height(heightDp.dp)) {
                    TasksSheetBody(items, runLive = true, stopEnabled = true, onStop = {}, nowMs = now)
                }
            }
        }
    }

    @Test fun fourteenRowsAtLargeFontScrollAndStopStaysInView() {
        show(rows(14), fontScale = 1.3f)
        compose.onNodeWithTag("chat_tasks_stop").assertIsDisplayed()
        compose.onNodeWithTag("chat_tasks_rows").assert(hasScrollAction())
    }

    @Test fun aFewRowsDoNotNeedToScrollAndStopIsThere() {
        show(rows(2))
        compose.onNodeWithTag("chat_tasks_stop").assertIsDisplayed()
        compose.onAllNodesWithTag("chat_task_row").onFirst().assertIsDisplayed()
    }

    @Test fun theKindIconHasALabelForTalkBack() {
        show(rows(3))
        compose.onAllNodes(hasContentDescription("Process"), useUnmergedTree = true).assertCountEquals(2)
        compose.onAllNodes(hasContentDescription("Helper agent"), useUnmergedTree = true).assertCountEquals(1)
    }
}

/** The "Started N min ago" digits are the phone's own, the way the relative times in the chat list are. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-w390dp-h844dp")
class TasksSheetDigitsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theMinutesAreWrittenWithTheLocaleDigits() {
        val now = 1_792_100_000_000L
        compose.setContent {
            RasulTheme(false) {
                TasksSheetBody(
                    listOf(BgItem("p", BgKind.Process, "x", now - 3 * 60_000L, BgStatus.Running, "p")),
                    runLive = false, stopEnabled = false, onStop = {}, nowMs = now,
                )
            }
        }
        val digits = NumberFormat.getIntegerInstance(Locale.forLanguageTag("ar")).format(3)
        compose.onNodeWithText("بدأت قبل $digits دقائق").assertExists()
    }
}
