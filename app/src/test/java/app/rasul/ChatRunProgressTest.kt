package app.rasul

import app.rasul.chat.ChatViewModel
import app.rasul.chat.OrderedWriter
import app.rasul.chat.RunNote
import app.rasul.chat.RunOutcome
import app.rasul.chat.RunPhase
import app.rasul.chat.RunProgress
import app.rasul.chat.StatusLabel
import app.rasul.chat.TargetLoader
import app.rasul.chat.cleanPreview
import app.rasul.data.ApprovalChoice
import app.rasul.data.HermesResult
import app.rasul.data.HermesTarget
import app.rasul.data.RunEvent
import app.rasul.data.RunUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The progress model of a running task, driven by run events with a virtual clock. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatRunProgressTest {
    private val sched = TestCoroutineScheduler()
    private val fake = FakeRuns()
    private val sessions = FakeSessions()
    private val flows = mutableListOf<MutableSharedFlow<RunUpdate>>()
    private val writer = OrderedWriter(CoroutineScope(StandardTestDispatcher(sched)))
    private var clock = 10_000L
    private var keyN = 0

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher(sched))

    @After fun tearDown() = Dispatchers.resetMain()

    private fun idle() = sched.advanceUntilIdle()

    private fun build(memory: FakeMemory = FakeMemory()): ChatViewModel {
        val t = HermesTarget("http://example.invalid/", null, "k")
        return ChatViewModel(
            TargetLoader { HermesResult.Ok(t) }, fake, sessions, memory, writer, now = { clock },
            follow = { _, _, _ -> MutableSharedFlow<RunUpdate>(replay = 64).also { flows += it } },
            newKey = { "key${++keyN}" },
            workDispatcher = StandardTestDispatcher(sched),
            attachLoader = FakeAttachLoader(),
        ).also { idle() }
    }

    private fun ChatViewModel.type(text: String = "x") {
        onDraft(text)
        send()
        idle()
    }

    private fun emit(e: RunEvent, seq: Long? = null) = emit(RunUpdate.Event(e, seq))

    private fun emit(u: RunUpdate) {
        flows.last().tryEmit(u)
        idle()
    }

    private fun running(): ChatViewModel = build().also { it.type() }

    private val ChatViewModel.progress get() = state.value.run!!.progress

    @Test fun sendIsAcknowledgedBeforeAnyEvent() {
        clock = 10_000L
        val v = build()
        v.type()
        val run = v.state.value.run!!
        assertEquals("run_1", run.runId)
        assertEquals(RunPhase.Received, run.progress.phase)
        assertEquals(10_000L, run.progress.receivedAt)
        assertEquals(0, run.progress.stepCount)
        assertTrue(run.progress.steps.isEmpty())
        assertFalse(run.progress.restored)
    }

    @Test fun receivedIsStampedWhenTheServerAcceptsTheSend() {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        fake.onStart = { gate.await(); HermesResult.Ok(app.rasul.data.StartedRun("run_1")) }
        clock = 1_000L
        val v = build()
        v.type()
        assertNull(v.state.value.run!!.runId)
        assertEquals(RunPhase.Received, v.progress.phase)
        clock = 4_000L
        gate.complete(Unit)
        idle()
        assertEquals(4_000L, v.progress.receivedAt)
        assertEquals(1_000L, v.state.value.run!!.startedAtMs)
    }

    @Test fun toolStartedShowsTheToolWithATrimmedSingleLinePreview() {
        val v = running()
        val raw = "  first line\n\n**bold** [link](http://example.invalid/x)\t" + "y".repeat(200)
        emit(RunEvent.ToolStarted("terminal", raw))
        val phase = v.progress.phase as RunPhase.Tool
        assertEquals("terminal", phase.name)
        val preview = phase.preview!!
        assertTrue(preview.length <= 80)
        assertFalse(preview.contains('\n'))
        assertTrue(preview.startsWith("first line **bold** [link](http://example.invalid/x)"))
        assertEquals(preview, v.progress.steps.single().preview)
        assertTrue(v.progress.steps.single().running)
    }

    @Test fun previewIsPlainTextNeverReshaped() {
        assertEquals("# title **x** `code` [a](b)", cleanPreview("# title\n**x**\r\n`code`\t[a](b)"))
        assertNull(cleanPreview("  \n\t "))
        assertNull(cleanPreview(null))
        assertEquals(80, cleanPreview("a".repeat(500))!!.length)
        assertEquals("a".repeat(80), cleanPreview("a".repeat(80)))
    }

    @Test fun toolCompletedMakesAnOkStepAndCountsIt() {
        val v = running()
        emit(RunEvent.ToolStarted("web_search", "kotlin"))
        clock += 3_000
        emit(RunEvent.ToolCompleted("web_search", 2.5, false, null))
        val step = v.progress.steps.single()
        assertFalse(step.running)
        assertFalse(step.failed)
        assertEquals(2_500L, step.durationMs)
        assertEquals(1, v.progress.stepCount)
        assertEquals(RunPhase.Thinking, v.progress.phase)
        assertEquals("web_search", v.progress.lastDone?.tool)
    }

    @Test fun aFailedToolIsMarkedFailedAndStillCounts() {
        val v = running()
        emit(RunEvent.ToolStarted("patch", null))
        clock += 1_500
        emit(RunEvent.ToolCompleted("patch", null, true, null))
        val step = v.progress.steps.single()
        assertTrue(step.failed)
        assertEquals(1_500L, step.durationMs)
        assertEquals(1, v.progress.stepCount)
    }

    @Test fun twoToolsInFlightKeepTheOneStillRunning() {
        val v = running()
        emit(RunEvent.ToolStarted("terminal", "a"))
        emit(RunEvent.ToolStarted("web_search", "b"))
        emit(RunEvent.ToolCompleted("web_search", 1.0, false, null))
        assertEquals(RunPhase.Tool("terminal", "a"), v.progress.phase)
        emit(RunEvent.ToolCompleted("terminal", 1.0, false, null))
        assertEquals(RunPhase.Thinking, v.progress.phase)
        assertEquals(2, v.progress.stepCount)
    }

    @Test fun parallelCallsOfOneToolCompleteOldestFirst() {
        val v = running()
        emit(RunEvent.ToolStarted("web_search", "first"))
        emit(RunEvent.ToolStarted("web_search", "second"))
        emit(RunEvent.ToolCompleted("web_search", 1.0, true, null))
        val (first, second) = v.progress.steps
        assertTrue(first.failed && !first.running)
        assertTrue(second.running && !second.failed)
        assertEquals(RunPhase.Tool("web_search", "second"), v.progress.phase)
    }

    @Test fun aCompletionNamingItsPreviewClosesThatCall() {
        val v = running()
        emit(RunEvent.ToolStarted("web_search", "first"))
        emit(RunEvent.ToolStarted("web_search", "second"))
        emit(RunEvent.ToolCompleted("web_search", 1.0, false, "second"))
        val (first, second) = v.progress.steps
        assertTrue(first.running)
        assertFalse(second.running)
    }

    @Test fun anApprovalMeansWaitingAndAnAnswerGoesBack() {
        val v = running()
        emit(RunEvent.ToolStarted("terminal", "rm x"))
        emit(RunEvent.ApprovalRequest("q1", "rm x", null, listOf("once", "deny")))
        assertEquals(RunPhase.WaitingApproval, v.progress.phase)
        // Progress events while the question is open do not hide it.
        emit(RunEvent.Reasoning("hm"))
        assertEquals(RunPhase.WaitingApproval, v.progress.phase)
        v.answerApproval("q1", ApprovalChoice.Deny)
        idle()
        assertEquals(RunPhase.Tool("terminal", "rm x"), v.progress.phase)
    }

    @Test fun theFirstDeltaMeansWriting() {
        val v = running()
        emit(RunEvent.MessageDelta("Hel"))
        assertEquals(RunPhase.Writing, v.progress.phase)
        emit(RunEvent.ToolStarted("read_file", null))
        assertEquals(RunPhase.Tool("read_file", null), v.progress.phase)
    }

    @Test fun reasoningMeansThinkingButNotWhileAToolRuns() {
        val v = running()
        emit(RunEvent.Reasoning("plan"))
        assertEquals(RunPhase.Thinking, v.progress.phase)
        emit(RunEvent.ToolStarted("terminal", null))
        emit(RunEvent.Reasoning("more"))
        assertEquals(RunPhase.Tool("terminal", null), v.progress.phase)
    }

    @Test fun threeMinutesWithoutEventsIsAStalledNoteAndAnEventClearsIt() {
        val v = running()
        emit(RunEvent.Reasoning("x"))
        clock += 3 * 60_000L - 1
        assertTrue(v.progress.notes(clock, true).isEmpty())
        clock += 1
        assertEquals(listOf<RunNote>(RunNote.Stalled(3)), v.progress.notes(clock, true))
        clock += 2 * 60_000L
        assertEquals(listOf<RunNote>(RunNote.Stalled(5)), v.progress.notes(clock, true))
        // The card passes false while the connection itself is lost: the stall would be a guess.
        assertTrue(v.progress.notes(clock, false).isEmpty())
        emit(RunEvent.MessageInterim("hi", true))
        assertTrue(v.progress.notes(clock, true).isEmpty())
    }

    @Test fun waitingForTheUserIsNotAStall() {
        val v = running()
        emit(RunEvent.ApprovalRequest("q1", "cmd", null, listOf("once", "deny")))
        clock += 10 * 60_000L
        assertTrue(v.progress.notes(clock, true).isEmpty())
    }

    @Test fun aToolRunningTwoMinutesGetsTheLongNote() {
        val v = running()
        emit(RunEvent.ToolStarted("terminal", "./gradlew test"))
        clock += 2 * 60_000L - 1
        assertTrue(v.progress.notes(clock, true).none { it is RunNote.LongTool })
        clock += 1
        assertEquals(RunNote.LongTool(2), v.progress.notes(clock, true).first())
        clock += 2 * 60_000L
        assertEquals(RunNote.LongTool(4), v.progress.notes(clock, true).first())
        emit(RunEvent.ToolCompleted("terminal", 240.0, false, null))
        assertTrue(v.progress.notes(clock, true).none { it is RunNote.LongTool })
    }

    @Test fun completedKeepsADoneSummaryWithTimeAndCount() {
        val v = running()
        clock = 10_000L
        emit(RunEvent.ToolStarted("terminal", null))
        emit(RunEvent.ToolCompleted("terminal", 1.0, false, null))
        emit(RunEvent.ToolStarted("web_search", null))
        emit(RunEvent.ToolCompleted("web_search", 1.0, false, null))
        clock += 84_000L
        emit(RunEvent.Completed("answer"))
        val s = v.state.value
        assertNull(s.run)
        val summary = s.summary!!
        assertEquals(s.finishedRunId, summary.runId)
        assertEquals(RunOutcome.Done, summary.outcome)
        assertEquals(84_000L, summary.elapsedMs)
        assertEquals(2, summary.stepCount)
        assertEquals(listOf("terminal", "web_search"), summary.steps.map { it.tool })
    }

    @Test fun failedInterruptedAndCancelledEndsHaveTheirOwnOutcome() {
        listOf<Pair<RunEvent, RunOutcome>>(
            RunEvent.Failed("boom") to RunOutcome.Failed,
            RunEvent.Interrupted("gone") to RunOutcome.Failed,
            RunEvent.Cancelled() to RunOutcome.Stopped,
        ).forEach { (event, outcome) ->
            flows.clear()
            val v = running()
            clock += 5_000L
            emit(event)
            assertNull(v.state.value.run)
            assertEquals(outcome, v.state.value.summary!!.outcome)
            assertEquals(5_000L, v.state.value.summary!!.elapsedMs)
        }
    }

    @Test fun aNewSendDropsTheOldSummary() {
        val v = running()
        emit(RunEvent.Completed("a"))
        assertNotNull(v.state.value.summary)
        v.type("again")
        val s = v.state.value
        assertNull(s.finishedRunId)
        // The old summary stays in the state, but the chat only shows a summary whose run id equals finishedRunId.
        assertFalse(s.summary!!.runId == s.finishedRunId)
    }

    @Test fun reconnectingShowsTheAttemptAndTheNextEventResumes() {
        val v = running()
        emit(RunEvent.ToolStarted("terminal", "ls"))
        emit(RunUpdate.Reconnecting(2, 5))
        assertEquals(RunPhase.Reconnecting(2, 5), v.progress.phase)
        assertEquals(StatusLabel.Reconnecting(2, 5), v.state.value.run!!.status)
        emit(RunUpdate.Reconnecting(3, 5))
        assertEquals(RunPhase.Reconnecting(3, 5), v.progress.phase)
        emit(RunEvent.Reasoning("back"))
        // Reasoning does not replace a running tool: the phase returns to where it was.
        assertEquals(RunPhase.Tool("terminal", "ls"), v.progress.phase)
        assertNull(v.progress.resume)
    }

    @Test fun onlyTheLastTwentyStepsAreKeptButAllAreCounted() {
        val v = running()
        for (i in 1..25) {
            emit(RunEvent.ToolStarted("tool_$i", "p$i"))
            emit(RunEvent.ToolCompleted("tool_$i", 0.1, false, null))
        }
        assertEquals(25, v.progress.stepCount)
        assertEquals(20, v.progress.steps.size)
        assertEquals("tool_6", v.progress.steps.first().tool)
        assertEquals("tool_25", v.progress.steps.last().tool)
        assertEquals("tool_25", v.progress.lastDone?.tool)
    }

    @Test fun aCompletionWithoutAStartStillCountsAsAStep() {
        val v = running()
        emit(RunEvent.ToolCompleted("terminal", 2.0, false, "ls"))
        assertEquals(1, v.progress.stepCount)
        assertEquals("ls", v.progress.steps.single().preview)
        assertEquals(2_000L, v.progress.steps.single().durationMs)
    }

    @Test fun steeredIsShownBrieflyThenGone() {
        val v = running()
        assertFalse(v.progress.steerNoticeAt(clock))
        emit(RunEvent.Steered)
        assertTrue(v.progress.steerNoticeAt(clock))
        assertTrue(v.progress.steerNoticeAt(clock + RunProgress.STEER_NOTICE_MS - 1))
        assertFalse(v.progress.steerNoticeAt(clock + RunProgress.STEER_NOTICE_MS))
    }

    @Test fun aRestoredRunShowsNoInventedSteps() {
        val memory = FakeMemory(active = "run_old")
        val v = build(memory)
        val run = v.state.value.run!!
        assertEquals("run_old", run.runId)
        assertTrue(run.progress.restored)
        assertTrue(run.progress.steps.isEmpty())
        assertEquals(0, run.progress.stepCount)
        assertEquals(RunPhase.Thinking, run.progress.phase)
        // The replay then supplies the real steps.
        emit(RunEvent.ToolStarted("read_file", "a.kt"))
        assertEquals(listOf("read_file"), v.progress.steps.map { it.tool })
        clock += 7_000
        emit(RunEvent.Completed("done"))
        assertNull(v.state.value.summary!!.elapsedMs)
    }
}
