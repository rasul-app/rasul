package app.rasul

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import app.rasul.bridge.JobRunDto
import app.rasul.jobs.JobAlertsHint
import app.rasul.jobs.JobDto
import app.rasul.jobs.JobList
import app.rasul.jobs.LastResult
import app.rasul.ui.theme.RasulTheme
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The job card's last-run line, error line and "Last result" block, and the one-time alerts hint. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h900dp")
class JobCardResultUiTest {
    @get:Rule val compose = createComposeRule()

    private val ok = JobDto("1", "Daily log summary", "Every day", enabled = true,
        lastRunAt = JsonPrimitive("2030-03-17T03:00:00+00:00"), lastStatus = "ok")
    private val failed = JobDto("2", "Clean old builds", "Every 6 hours", enabled = true,
        lastRunAt = JsonPrimitive("2030-03-17T02:00:00+00:00"), lastStatus = "error", lastError = JsonPrimitive("disk quota exceeded"))
    private val never = JobDto("3", "Fresh job", "Hourly", enabled = true)
    private fun string(id: Int, vararg args: Any) = RuntimeEnvironment.getApplication().getString(id, *args)

    private fun show(
        jobs: List<JobDto>, history: Boolean = true, results: Map<String, LastResult> = emptyMap(),
        onLast: (JobDto) -> Unit = {}, onHistory: (JobDto) -> Unit = {}, hint: Boolean = false,
        onTurnOn: () -> Unit = {}, onNotNow: () -> Unit = {}, fontScale: Float = 1f, dark: Boolean = false,
    ) = compose.setContent {
        RasulTheme(dark) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                JobList(jobs, busy = false, historyAvailable = history, onHistory = onHistory, lastResults = results,
                    onLastResult = onLast, alertsHint = hint, onAlertsTurnOn = onTurnOn, onAlertsHintDismiss = onNotNow,
                    onToggle = {}, onRunNow = {})
            }
        }
    }

    @Test fun cardShowsWhenAndHowTheLastRunEnded() {
        show(listOf(ok, failed, never))
        val time = compose.onAllNodesWithText("Last run:", substring = true)
        time.assertCountEquals(2) // the job that never ran shows no line
        compose.onNodeWithText("· Succeeded", substring = true).assertIsDisplayed()
        compose.onNodeWithText("· Failed", substring = true).assertIsDisplayed()
        compose.onNodeWithText("disk quota exceeded").assertIsDisplayed()
    }

    @Test fun aSuccessfulRunShowsNoErrorLineEvenWithStaleErrorText() {
        show(listOf(ok.copy(lastError = JsonPrimitive("stale"))))
        compose.onAllNodesWithText("stale").assertCountEquals(0)
    }

    @Test fun deliveryFailureAndUnknownStatusUseTheirOwnWords() {
        show(listOf(ok.copy(lastStatus = "delivery_failed"), failed.copy(id = "4", lastStatus = "timeout", lastError = null)))
        compose.onNodeWithText("· Delivered with problems", substring = true).assertExists()
        compose.onNodeWithText("· timeout", substring = true).assertExists()
    }

    @Test fun lastResultActionExistsOnlyForBridgeConnections() {
        show(listOf(ok), history = true)
        compose.onAllNodesWithText("Last result").assertCountEquals(1)
        compose.onNodeWithText("Last result").assertHasContentDescriptionOfJob("Daily log summary")
    }

    @Test fun directConnectionHasNoLastResultAction() {
        show(listOf(ok), history = false)
        compose.onAllNodesWithText("Last result").assertCountEquals(0)
    }

    @Test fun tappingLastResultReportsTheJobAndNothingIsFetchedByTheList() {
        val tapped = mutableListOf<String>()
        show(listOf(ok, failed), onLast = { tapped += it.id })
        assertTrue(tapped.isEmpty())
        compose.onAllNodesWithText("Last result")[1].performClick()
        assertEquals(listOf("2"), tapped)
    }

    @Test fun expandedResultShowsPlainTextAtMostSixLinesAndOpensHistory() {
        val opened = mutableListOf<String>()
        val text = (1..12).joinToString("\n") { "line $it" }
        val run = JobRunDto("r1", "x", JsonPrimitive(1), null, false, text)
        show(listOf(ok), results = mapOf("1" to LastResult.Ready(run)), onHistory = { opened += it.id })
        compose.onNodeWithTag("job_last_result_text", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Open history").performClick()
        assertEquals(listOf("1"), opened)
    }

    @Test fun rateLimitCopyAndLoadingAndEmptyStates() {
        show(listOf(ok, failed), results = mapOf(
            "1" to LastResult.Failed(app.rasul.bridge.DesktopError.RateLimited),
            "2" to LastResult.Loading,
        ))
        compose.onNodeWithText(string(R.string.desktop_rate_limited)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.job_last_result_loading)).assertIsDisplayed()
    }

    @Test fun noRunsAndBlankPreviewHaveTheirOwnLines() {
        show(listOf(ok, failed), results = mapOf(
            "1" to LastResult.Ready(null),
            "2" to LastResult.Ready(JobRunDto("r", null, null, null, false, "  ")),
        ))
        compose.onNodeWithText(string(R.string.job_last_result_none)).assertExists()
        compose.onNodeWithText(string(R.string.job_last_result_empty)).assertExists()
    }

    @Test fun hintShowsTurnOnAndNotNowAndNothingElseWhenOff() {
        var turnedOn = 0
        var dismissed = 0
        show(listOf(ok), hint = true, onTurnOn = { turnedOn++ }, onNotNow = { dismissed++ })
        compose.onNodeWithText("Get a notice when a job finishes").assertIsDisplayed()
        compose.onNodeWithText("Turn on").performClick()
        compose.onNodeWithText("Not now").performClick()
        assertEquals(1, turnedOn)
        assertEquals(1, dismissed)
    }

    @Test fun noHintWhenNotRequested() {
        show(listOf(ok), hint = false)
        compose.onAllNodesWithText("Get a notice when a job finishes").assertCountEquals(0)
    }

    @Test fun largeFontKeepsTheCardWholeInArabic() {
        RuntimeEnvironment.setQualifiers("+ar-w390dp-h900dp")
        show(listOf(failed), fontScale = 1.5f, hint = true)
        compose.onNodeWithText(string(R.string.job_alerts_hint)).assertIsDisplayed()
        compose.onNodeWithText("disk quota exceeded").assertIsDisplayed()
        compose.onNodeWithText(string(R.string.job_alerts_hint_turn_on)).assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertHasContentDescriptionOfJob(name: String) {
        compose.onAllNodesWithContentDescription(string(R.string.job_last_result_named, name)).assertCountEquals(1)
    }
}
