package app.rasul

import app.rasul.bridge.BridgeEndpoint
import app.rasul.chat.BgKind
import app.rasul.chat.BgStatus
import app.rasul.chat.ChatViewModel
import app.rasul.chat.OrderedWriter
import app.rasul.chat.TargetLoader
import app.rasul.chat.TasksPill
import app.rasul.chat.tasksPill
import app.rasul.data.BridgeProcess
import app.rasul.data.HermesResult
import app.rasul.data.HermesTarget
import app.rasul.data.ProcessesApi
import app.rasul.data.ProcessesResult
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
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Background tasks seen on a run, and the bridge's list of what still runs after it. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatBackgroundViewModelTest {
    private val sched = TestCoroutineScheduler()
    private val fake = FakeRuns()
    private val sessions = FakeSessions()
    private val flows = mutableListOf<MutableSharedFlow<RunUpdate>>()
    private val writer = OrderedWriter(CoroutineScope(StandardTestDispatcher(sched)))
    private var keyN = 0

    private class Processes : ProcessesApi {
        var calls = 0
        var answer: ProcessesResult = ProcessesResult.Ok(emptyList())
        override suspend fun list(): ProcessesResult {
            calls++
            return answer
        }
    }

    private val api = Processes()

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher(sched))

    @After fun tearDown() = Dispatchers.resetMain()

    private fun idle() = sched.advanceUntilIdle()

    private fun advance(ms: Long) {
        sched.advanceTimeBy(ms)
        sched.runCurrent()
    }

    private fun build(direct: Boolean = false): ChatViewModel {
        val target = if (direct) HermesTarget("http://example.invalid/", null, "k") else HermesTarget("", null, "", BridgeEndpoint(OkHttpClient(), "default"))
        return ChatViewModel(
            TargetLoader { HermesResult.Ok(target) }, fake, sessions, FakeMemory(), writer, now = { 5_000L },
            follow = { _, _, _ -> MutableSharedFlow<RunUpdate>(replay = 64).also { flows += it } },
            newKey = { "key${++keyN}" },
            workDispatcher = StandardTestDispatcher(sched),
            attachLoader = FakeAttachLoader(),
            processesApiFor = { if (it.bridge != null) api else null },
        ).also { idle() }
    }

    private fun ChatViewModel.type() {
        onDraft("run it")
        send()
        idle()
    }

    /** Runs what is due now without moving the clock: a running poll would never let advanceUntilIdle finish. */
    private fun emit(e: RunEvent) {
        flows.last().tryEmit(RunUpdate.Event(e, null))
        sched.runCurrent()
    }

    /** The chat is open (the bridge has nothing running yet: one ask, then it stops) and a run has started. */
    private fun openWithRun(): ChatViewModel {
        val v = build()
        v.onScreenActive(true)
        idle()
        v.type()
        return v
    }

    private fun startBackgroundProcess() {
        emit(RunEvent.ToolStarted("terminal", "npm run build"))
        emit(RunEvent.ToolCompleted("terminal", 0.2, false, """{"output": "Background process started", "session_id": "proc_a1b2c3d4e5f6", "pid": 4242, "exit_code": 0, "error": null}"""))
    }

    private fun mine(vararg ids: String) = ids.map { BridgeProcess(it, "npm run build", 1_000L, "sess-1") }

    @Test fun liveRunBuildsTheListAndTheWorkingPill() {
        val v = build()
        v.type()
        startBackgroundProcess()
        emit(RunEvent.Subagent(true, "Research", null, null, "sa-1"))
        val items = v.state.value.background.items
        assertEquals(listOf(BgKind.Process, BgKind.Helper), items.map { it.kind })
        assertEquals("npm run build", items[0].name)
        assertEquals(TasksPill.Working(2), tasksPill(v.state.value))
        emit(RunEvent.Subagent(false, "Research", "done", "completed", "sa-1"))
        assertEquals(TasksPill.Working(1), tasksPill(v.state.value))
    }

    @Test fun aToolCallThatStartsNothingAddsNothing() {
        val v = build()
        v.type()
        emit(RunEvent.ToolStarted("terminal", "ls"))
        emit(RunEvent.ToolCompleted("terminal", 0.1, false, "file.txt"))
        assertEquals(emptyList<Any>(), v.state.value.background.items)
        assertNull(tasksPill(v.state.value))
    }

    @Test fun afterTheRunTheBridgeListDecidesAndOnlyThisChatsProcessesCount() {
        val v = openWithRun()
        startBackgroundProcess()
        val before = api.calls
        api.answer = ProcessesResult.Ok(mine("proc_a1b2c3d4e5f6") + BridgeProcess("proc_zz", "other", 1L, "sess-2"))
        emit(RunEvent.Completed("ok", null, null))
        assertEquals(before + 1, api.calls)
        assertEquals(TasksPill.StillRunning(1), tasksPill(v.state.value))
    }

    @Test fun pollsEveryFifteenSecondsAndStopsAtZero() {
        val v = openWithRun()
        api.answer = ProcessesResult.Ok(mine("proc_a1b2c3d4e5f6"))
        val before = api.calls
        emit(RunEvent.Completed("ok", null, null))
        assertEquals(before + 1, api.calls)
        advance(14_999)
        assertEquals(before + 1, api.calls)
        advance(1)
        assertEquals(before + 2, api.calls)
        api.answer = ProcessesResult.Ok(emptyList())
        advance(15_000)
        assertEquals(before + 3, api.calls)
        assertNull(tasksPill(v.state.value))
        advance(120_000)
        assertEquals(before + 3, api.calls)
    }

    @Test fun noPollingWhileTheScreenIsClosedAndItStopsWhenItCloses() {
        api.answer = ProcessesResult.Ok(mine("proc_a1b2c3d4e5f6"))
        val v = build()
        v.type()
        emit(RunEvent.Completed("ok", null, null))
        advance(60_000)
        assertEquals(0, api.calls)
        v.onScreenActive(true)
        sched.runCurrent()
        assertEquals(1, api.calls)
        v.onScreenActive(false)
        advance(60_000)
        assertEquals(1, api.calls)
    }

    @Test fun anOldBridgeWithoutTheRouteHidesTheLineAndStopsAsking() {
        val v = openWithRun()
        api.answer = ProcessesResult.Unsupported
        val before = api.calls
        emit(RunEvent.Completed("ok", null, null))
        assertNull(v.state.value.remoteProcesses)
        assertNull(tasksPill(v.state.value))
        advance(120_000)
        assertEquals(before + 1, api.calls)
    }

    @Test fun aFailedAskKeepsTheLastAnswerAndTriesAgain() {
        val v = openWithRun()
        api.answer = ProcessesResult.Ok(mine("proc_a1b2c3d4e5f6"))
        emit(RunEvent.Completed("ok", null, null))
        api.answer = ProcessesResult.Failed
        advance(15_000)
        assertEquals(TasksPill.StillRunning(1), tasksPill(v.state.value))
        api.answer = ProcessesResult.Ok(mine("proc_a1b2c3d4e5f6", "proc_d4"))
        advance(15_000)
        assertEquals(TasksPill.StillRunning(2), tasksPill(v.state.value))
    }

    @Test fun aDirectChatIsNeverAsked() {
        api.answer = ProcessesResult.Ok(mine("proc_a1b2c3d4e5f6"))
        val v = build(direct = true)
        v.onScreenActive(true)
        v.type()
        emit(RunEvent.Completed("ok", null, null))
        advance(60_000)
        assertEquals(0, api.calls)
        assertNull(tasksPill(v.state.value))
    }

    @Test fun theNextRunStartsTheListOver() {
        val v = build()
        v.type()
        startBackgroundProcess()
        emit(RunEvent.Completed("ok", null, null))
        assertEquals(1, v.state.value.background.items.size)
        assertEquals(BgStatus.Running, v.state.value.background.items.single().status)
        v.type()
        assertEquals(emptyList<Any>(), v.state.value.background.items)
    }
}
