package app.rasul.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.rasul.ui.theme.RasulTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp")
class RunProgressCardUiTest {
    @get:Rule val compose = createComposeRule()

    private val t0 = 1_000_000L
    private var now = t0

    private fun step(tool: String, preview: String? = null, i: Int = 0, failed: Boolean = false) =
        RunStep(tool, preview, t0 + i * 1_000L, t0 + i * 1_000L + 2_000L, 2_000L, failed)

    private fun run(
        phase: RunPhase,
        steps: List<RunStep> = emptyList(),
        count: Int = steps.size,
        status: StatusLabel = StatusLabel.Thinking,
        lastEventAt: Long = t0,
    ) = ActiveRun("r1", t0, status, progress = RunProgress(t0, phase, steps, count, lastEventAt))

    private var stops = 0

    private fun show(run: () -> ActiveRun, onReview: (() -> Unit)? = null) {
        compose.setContent {
            RasulTheme(dark = false) {
                RunProgressCard(run(), onStop = { stops++ }, onReconnect = {}, onRefresh = {}, onReview = onReview, clock = { now })
            }
        }
        compose.waitForIdle()
    }

    @Test fun headlineIsAPoliteLiveRegionAndStopIsReachable() {
        show({ run(RunPhase.Received, status = StatusLabel.Starting) })
        compose.onNodeWithText("Got your request").assertExists()
        compose.onNodeWithTag("run_progress_headline")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onNodeWithTag("run_progress_stop").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, stops)
    }

    private val unavailableText = "Live updates unavailable. The task is still running."

    @Test fun unavailableLiveUpdatesKeepRefreshAndTheirHeadlineWhileWriting() {
        show({ run(RunPhase.Writing, status = StatusLabel.LiveUpdatesUnavailable) })
        compose.onNodeWithTag("run_progress_collapsed").assertDoesNotExist()
        compose.onNodeWithText("Refresh").assertExists()
        compose.onNodeWithTag("run_progress_headline").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(androidx.compose.ui.text.AnnotatedString(unavailableText))),
        )
    }

    @Test fun unavailableLiveUpdatesBeatTheReconnectingHeadline() {
        show({ run(RunPhase.Reconnecting(2, 5), status = StatusLabel.LiveUpdatesUnavailable) })
        compose.onNodeWithText(unavailableText).assertExists()
        compose.onNodeWithText("Reconnecting to the PC (2/5)").assertDoesNotExist()
        compose.onNodeWithText("Refresh").assertExists()
    }

    private fun showRtl(run: () -> ActiveRun) {
        compose.setContent {
            RasulTheme(dark = false) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    RunProgressCard(run(), onStop = {}, onReconnect = {}, onRefresh = {}, onReview = null, clock = { now })
                }
            }
        }
        compose.waitForIdle()
    }

    private fun layoutDirectionOf(tag: String): LayoutDirection {
        val layout = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)
            ?.action?.invoke(layout)
        return layout.single().layoutInput.layoutDirection
    }

    @Test fun aCommandPreviewStaysLeftToRightInTheArabicLayout() {
        showRtl { run(RunPhase.Tool("terminal", "./gradlew test"), listOf(RunStep("terminal", "./gradlew test", t0))) }
        assertEquals(LayoutDirection.Ltr, layoutDirectionOf("run_progress_preview"))
        compose.onNodeWithText("Show steps").performClick()
        assertEquals(LayoutDirection.Ltr, layoutDirectionOf("run_progress_step_preview"))
    }

    @Test fun anArabicPreviewKeepsTheSurroundingDirection() {
        showRtl { run(RunPhase.Tool("terminal", "\u0627\u062e\u062a\u0628\u0627\u0631"), emptyList()) }
        assertEquals(LayoutDirection.Rtl, layoutDirectionOf("run_progress_preview"))
    }

    private val hasLiveRegion = SemanticsMatcher("has a live region") { it.config.getOrNull(SemanticsProperties.LiveRegion) != null }

    private fun liveRegionTexts() = compose.onAllNodes(hasLiveRegion).fetchSemanticsNodes()
        .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }

    @Test fun theCollapsedRowsLiveRegionDoesNotCarryTheTimer() {
        now = t0 + 72_000L
        show({ run(RunPhase.Writing, List(5) { step("terminal", i = it) }) })
        val texts = liveRegionTexts()
        // No ticking timer (1:12) in the merged row, but the step count is still read.
        assertEquals(listOf("Writing the answer", "· 5 steps"), texts)
        assertTrue(texts.none { Regex("""\d+:\d\d""").containsMatchIn(it) })
    }

    @Test fun theApprovalRowsLiveRegionDoesNotCarryTheTimer() {
        now = t0 + 5_000L
        show({ run(RunPhase.WaitingApproval, status = StatusLabel.WaitingApproval) }, onReview = {})
        assertEquals(listOf("Waiting for your approval"), liveRegionTexts())
    }

    @Test fun toolHeadlineShowsThePlainPreviewAndTheStepsLine() {
        show({
            run(
                RunPhase.Tool("terminal", "echo **hi** [x](http://example.invalid)"),
                listOf(step("web_search"), step("read_file", i = 3), step("patch", i = 6)),
            )
        })
        compose.onNodeWithText("Running: Terminal").assertExists()
        compose.onNodeWithTag("run_progress_preview").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(androidx.compose.ui.text.AnnotatedString("echo **hi** [x](http://example.invalid)"))),
        )
        compose.onNodeWithText("Steps done: 3 · last: Edit file").assertExists()
    }

    @Test fun stepListIsCollapsedByDefaultAndTheExpanderTogglesIt() {
        show({ run(RunPhase.Thinking, listOf(step("web_search", "kotlin"), step("patch", i = 3, failed = true))) })
        compose.onNodeWithTag("run_progress_step_list").assertDoesNotExist()
        compose.onNodeWithText("Show steps").performClick()
        compose.onNodeWithTag("run_progress_step_list").assertExists()
        compose.onNodeWithText("Web search").assertExists()
        compose.onAllNodesWithText("0:02", useUnmergedTree = true).assertCountEquals(2)
        compose.onNodeWithContentDescription("Failed", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Done", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Hide steps").performClick()
        compose.onNodeWithTag("run_progress_step_list").assertDoesNotExist()
    }

    @Test fun theExpandedStateStaysWhileTheSameRunKeepsProgressing() {
        var current by mutableStateOf(run(RunPhase.Thinking, listOf(step("web_search"))))
        show({ current })
        compose.onNodeWithText("Show steps").performClick()
        compose.runOnIdle { current = run(RunPhase.Tool("terminal", null), listOf(step("web_search"), step("patch", i = 3))) }
        compose.waitForIdle()
        compose.onNodeWithTag("run_progress_step_list").assertExists()
        compose.onNodeWithText("Edit file").assertExists()
    }

    @Test fun firstReplyTextCollapsesTheCardToOneLine() {
        var current by mutableStateOf(run(RunPhase.Tool("terminal", "ls"), List(5) { step("terminal", i = it) }))
        now = t0 + 72_000L
        show({ current })
        compose.onNodeWithTag("run_progress_steps_done").assertExists()
        compose.onNodeWithTag("run_progress_collapsed").assertDoesNotExist()
        compose.runOnIdle { current = run(RunPhase.Writing, List(5) { step("terminal", i = it) }) }
        compose.waitForIdle()
        compose.onNodeWithTag("run_progress_collapsed").assertExists()
        compose.onNodeWithText("Writing the answer").assertExists()
        compose.onNodeWithTag("run_progress_meta", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("run_progress_steps_done").assertDoesNotExist()
        compose.onNodeWithTag("run_progress_preview").assertDoesNotExist()
        compose.onNodeWithTag("run_progress_stop").assertHeightIsAtLeast(48.dp)
        // The collapsed line opens the step list.
        compose.onNodeWithTag("run_progress_collapsed").performClick()
        compose.onNodeWithTag("run_progress_step_list").assertExists()
    }

    @Test fun theTimerTicksInsideTheCardOnly() {
        now = t0 + 5_000L
        show({ run(RunPhase.Thinking) })
        compose.onNodeWithText("0:05").assertExists()
        now = t0 + 9_000L
        compose.mainClock.advanceTimeBy(1_100L)
        compose.waitForIdle()
        compose.onNodeWithText("0:09").assertExists()
    }

    @Test fun stalledAndLongToolNotesAppear() {
        now = t0 + 4 * 60_000L
        show({
            run(
                RunPhase.Tool("terminal", "./gradlew test"),
                listOf(RunStep("terminal", "./gradlew test", t0)),
                count = 0,
                lastEventAt = t0,
            )
        })
        compose.onNodeWithText("Still running (4 min). Long builds and tests can take a while.").assertExists()
        compose.onNodeWithTag("run_progress_note_stalled").assertDoesNotExist()
    }

    @Test fun stallNoteAppearsWhenNoToolIsRunning() {
        now = t0 + 4 * 60_000L
        show({ run(RunPhase.Thinking, lastEventAt = t0) })
        compose.onNodeWithText("No activity for 4 min. Still watching.").assertExists()
        compose.onNodeWithTag("run_progress_note_long").assertDoesNotExist()
    }

    @Test fun noStallNoteWhileTheConnectionIsLost() {
        now = t0 + 10 * 60_000L
        show({ run(RunPhase.Thinking, status = StatusLabel.Lost, lastEventAt = t0) })
        compose.onNodeWithTag("run_progress_note_stalled").assertDoesNotExist()
    }

    @Test fun toolLabelsAreFriendlyAndUnknownIdsStayRaw() {
        show({ run(RunPhase.Thinking, listOf(step("browser_click"), step("mcp_files_list", i = 1), step("my_custom_tool", i = 2), step("web_extract", i = 3))) })
        compose.onNodeWithText("Show steps").performClick()
        compose.onNodeWithText("Browser").assertExists()
        compose.onNodeWithText("MCP tool").assertExists()
        compose.onNodeWithText("my_custom_tool").assertExists()
        compose.onNodeWithText("Read web page").assertExists()
    }

    @Test fun everyListedToolIdHasALabel() {
        listOf(
            "terminal", "process", "read_file", "write_file", "patch", "search_files", "web_search", "web_extract",
            "delegate_task", "execute_code", "todo", "memory", "session_search", "send_message", "clarify",
            "vision_analyze", "skill_view", "skills_list", "cronjob", "browser", "browser_navigate", "mcp_x",
        ).forEach { assertEquals(it, true, toolLabelRes(it) != null) }
        assertEquals(null, toolLabelRes("something_else"))
    }

    @Test fun approvalHeadlineOpensTheApprovalWhenItIsHidden() {
        var reviews = 0
        show({ run(RunPhase.WaitingApproval, status = StatusLabel.WaitingApproval) }, onReview = { reviews++ })
        compose.onNodeWithText("Waiting for your approval").performClick()
        assertEquals(1, reviews)
    }

    @Test fun reconnectingHeadlineShowsTheAttempt() {
        show({ run(RunPhase.Reconnecting(2, 5), status = StatusLabel.Reconnecting(2, 5)) })
        compose.onNodeWithText("Reconnecting to the PC (2/5)").assertExists()
    }

    @Test fun stoppingReplacesTheHeadlineAndDisablesStop() {
        show({ run(RunPhase.Thinking).copy(stopRequested = true) })
        compose.onNodeWithText("Stopping…").assertExists()
        compose.onNodeWithTag("run_progress_stop").assertIsNotEnabled()
    }

    @Test fun steeredNoticeShowsBriefly() {
        show({ run(RunPhase.Thinking).let { it.copy(progress = it.progress.steered(now)) } })
        compose.onNodeWithText("Added to running task").assertExists()
    }

    @Test fun summaryLineShowsOutcomeTimeAndCountAndOpensTheSteps() {
        compose.setContent {
            RasulTheme(dark = false) {
                RunSummaryLine(RunSummary("r1", RunOutcome.Done, 84_000L, 5, List(5) { step("terminal", i = it) }))
            }
        }
        compose.onNodeWithText("Done in 1:24 · 5 steps").assertExists()
        compose.onNodeWithTag("run_progress_step_list").assertDoesNotExist()
        compose.onNodeWithTag("run_summary_line").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("run_progress_step_list").assertExists()
    }

    @Test fun summaryLineForStoppedFailedAndUnknownDuration() {
        var outcome by mutableStateOf(RunOutcome.Stopped)
        var elapsed by mutableStateOf<Long?>(61_000L)
        compose.setContent {
            RasulTheme(dark = false) { RunSummaryLine(RunSummary("r1", outcome, elapsed, 0, emptyList())) }
        }
        compose.onNodeWithText("Stopped after 1:01").assertExists()
        compose.runOnIdle { outcome = RunOutcome.Failed }
        compose.onNodeWithText("Failed after 1:01").assertExists()
        compose.runOnIdle { elapsed = null }
        compose.onNodeWithText("Failed").assertExists()
    }
}
