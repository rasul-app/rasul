package app.rasul

import app.rasul.chat.Anchor
import app.rasul.chat.ChatFailure
import app.rasul.chat.ChatLoad
import app.rasul.chat.ChatState
import app.rasul.chat.ChatViewModel
import app.rasul.chat.OrderedWriter
import app.rasul.chat.RunMemory
import app.rasul.chat.StatusLabel
import app.rasul.chat.TargetLoader
import app.rasul.data.ApprovalChoice
import app.rasul.data.HermesError
import app.rasul.data.HermesResult
import app.rasul.data.HermesTarget
import app.rasul.data.RunFrame
import app.rasul.data.RunStatus
import app.rasul.data.RunsApi
import app.rasul.data.StartedRun
import app.rasul.sessions.ChatSessionControl
import app.rasul.sessions.ForkedSession
import app.rasul.sessions.SessionControlSupport
import app.rasul.sessions.SessionInfo
import app.rasul.sessions.SessionMessageDto
import app.rasul.sessions.SessionsApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class QuietRuns : RunsApi {
    override suspend fun startRun(target: HermesTarget, input: kotlinx.serialization.json.JsonElement, sessionId: String?, idempotencyKey: String?): HermesResult<StartedRun> =
        HermesResult.Ok(StartedRun("run-1"))
    override suspend fun status(target: HermesTarget, runId: String): HermesResult<RunStatus> = HermesResult.Ok(RunStatus("running", null, null))
    override suspend fun stop(target: HermesTarget, runId: String): HermesResult<Unit> = HermesResult.Ok(Unit)
    override suspend fun steer(target: HermesTarget, runId: String, text: String): HermesResult<Unit> = HermesResult.Ok(Unit)
    override suspend fun approve(target: HermesTarget, runId: String, choice: ApprovalChoice, requestId: String?): HermesResult<Unit> = HermesResult.Ok(Unit)
    override fun events(target: HermesTarget, runId: String, lastSeq: Long?): Flow<RunFrame> = kotlinx.coroutines.flow.flow { kotlinx.coroutines.awaitCancellation() }
}

private class EmptySessions : SessionsApi {
    override suspend fun messages(target: HermesTarget, sessionId: String): HermesResult<List<SessionMessageDto>> = HermesResult.Ok(emptyList())
}

private class SessionMemory(private val session: String = "sess-1") : RunMemory {
    override suspend fun sessionId() = session
    override suspend fun currentSessionId() = session
    override suspend fun activeRun(): String? = null
    override suspend fun lastEventId(): Long? = null
    override suspend fun setActiveRun(runId: String?, lastEventId: Long?, anchor: Anchor) = Unit
}

private class FakeControl(var support: SessionControlSupport) : ChatSessionControl {
    val calls = mutableListOf<String>()
    var forkReply: HermesResult<ForkedSession> = HermesResult.Ok(ForkedSession("fork-1", "Sample fork"))
    var forkGate: CompletableDeferred<Unit>? = null
    var infoReply: HermesResult<SessionInfo> = HermesResult.Err(HermesError.NotFound)

    override suspend fun support(target: HermesTarget): SessionControlSupport = support
    override suspend fun fork(target: HermesTarget, sessionId: String): HermesResult<ForkedSession> {
        calls += "fork $sessionId"
        forkGate?.await()
        return forkReply
    }
    override suspend fun info(target: HermesTarget, sessionId: String): HermesResult<SessionInfo> {
        calls += "info $sessionId"
        return infoReply
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSessionControlViewModelTest {
    private val sched = TestCoroutineScheduler()

    private fun build(control: ChatSessionControl?): ChatViewModel {
        val scope = CoroutineScope(StandardTestDispatcher(sched))
        val target = HermesTarget("http://example.invalid/", null, "sample")
        val vm = if (control == null) {
            ChatViewModel(TargetLoader { HermesResult.Ok(target) }, QuietRuns(), EmptySessions(), SessionMemory(), OrderedWriter(scope), scope = scope)
        } else {
            ChatViewModel(TargetLoader { HermesResult.Ok(target) }, QuietRuns(), EmptySessions(), SessionMemory(), OrderedWriter(scope), scope = scope, control = control)
        }
        sched.advanceUntilIdle()
        return vm
    }

    @Test fun forkIsHiddenAndInertWhenTheServerDoesNotAdvertiseIt() = runBlocking {
        val control = FakeControl(SessionControlSupport())
        val vm = build(control)
        assertEquals(ChatLoad.Ready, vm.state.value.load)
        assertFalse(vm.state.value.supportsFork)
        assertFalse(vm.state.value.canFork)
        vm.fork()
        sched.advanceUntilIdle()
        assertNull(vm.state.value.forkedSessionId)
        assertTrue(control.calls.none { it.startsWith("fork") })
    }

    @Test fun flagsOffMeansTheOldBehaviour() = runBlocking {
        // No control wired at all: no fork is possible and the chat state is the plain one.
        val vm = build(null)
        assertFalse(vm.state.value.supportsFork)
        assertTrue(vm.state.value.canChooseModel)
        vm.fork()
        sched.advanceUntilIdle()
        assertNull(vm.state.value.forkedSessionId)
        assertNull(vm.state.value.actionFailure)
    }

    @Test fun forkSuccessPublishesTheNewSessionUntilTheScreenOpensIt() = runBlocking {
        val control = FakeControl(SessionControlSupport(fork = true))
        val vm = build(control)
        assertTrue(vm.state.value.canFork)
        vm.fork()
        sched.advanceUntilIdle()
        assertEquals(listOf("info sess-1", "fork sess-1"), control.calls)
        assertEquals("fork-1", vm.state.value.forkedSessionId)
        assertFalse(vm.state.value.forking)
        assertNull(vm.state.value.actionFailure)
        vm.forkOpened()
        assertNull(vm.state.value.forkedSessionId)
    }

    @Test fun forkFailureKeepsTheSpecificCauseAndCanBeDismissed() = runBlocking {
        val control = FakeControl(SessionControlSupport(fork = true))
        val vm = build(control)
        control.forkReply = HermesResult.Err(HermesError.Server(429, "fork_rate_limited"))
        vm.fork()
        sched.advanceUntilIdle()
        assertNull(vm.state.value.forkedSessionId)
        assertEquals(ChatFailure.ForkFailed(HermesError.Server(429, "fork_rate_limited")), vm.state.value.actionFailure)
        assertFalse(vm.state.value.forking)
        vm.dismissActionFailure()
        assertNull(vm.state.value.actionFailure)

        control.forkReply = HermesResult.Err(HermesError.NotFound)
        vm.fork()
        sched.advanceUntilIdle()
        assertEquals(ChatFailure.ForkFailed(HermesError.NotFound), vm.state.value.actionFailure)
    }

    @Test fun forkIsRefusedWhileAReplyIsRunning() = runBlocking {
        val control = FakeControl(SessionControlSupport(fork = true))
        val vm = build(control)
        vm.onDraft("hello")
        vm.send()
        sched.advanceUntilIdle()
        assertTrue(vm.state.value.run != null)
        assertFalse(vm.state.value.canFork)
        vm.fork()
        sched.advanceUntilIdle()
        assertTrue(control.calls.none { it.startsWith("fork") })
    }

    // Cause B2: the screen's scope was cancelled mid-request (back or rotation) and the flag stayed true for good.
    @Test fun aScreenLeavingDuringAForkDoesNotStickTheFlagAndTheForkIsStillOpened() = runBlocking {
        val control = FakeControl(SessionControlSupport(fork = true))
        control.forkGate = CompletableDeferred()
        val vm = build(control)
        val screenScope = CoroutineScope(StandardTestDispatcher(sched))
        val screen = screenScope.launch { vm.fork() }
        sched.advanceUntilIdle()
        assertTrue(vm.state.value.forking)
        screen.cancel()
        screenScope.cancel()
        sched.advanceUntilIdle()
        // The request belongs to the chat, not to the screen: it is still running.
        assertTrue(vm.state.value.forking)
        control.forkGate!!.complete(Unit)
        sched.advanceUntilIdle()
        assertFalse(vm.state.value.forking)
        assertEquals("fork-1", vm.state.value.forkedSessionId)
    }

    @Test fun aCancelledForkRequestStillResetsTheFlag() = runBlocking {
        val control = FakeControl(SessionControlSupport(fork = true))
        control.forkGate = CompletableDeferred()
        val vm = build(control)
        vm.fork()
        sched.advanceUntilIdle()
        assertTrue(vm.state.value.forking)
        vm.close()
        sched.advanceUntilIdle()
        assertFalse(vm.state.value.forking)
    }

    @Test fun aForkedChatShowsItsTitleFromTheServer() = runBlocking {
        val control = FakeControl(SessionControlSupport(fork = true))
        control.infoReply = HermesResult.Ok(SessionInfo("Sample fork", "parent-1"))
        assertEquals("Sample fork", build(control).state.value.forkTitle)

        val plain = FakeControl(SessionControlSupport(fork = true))
        plain.infoReply = HermesResult.Ok(SessionInfo("Sample", null))
        assertNull(build(plain).state.value.forkTitle)
    }

    @Test fun controlsStayOffUntilTheChatIsReady() {
        assertFalse(ChatState(supportsFork = true).canFork)
        assertFalse(ChatState(load = ChatLoad.Ready, sessionId = "s", supportsFork = true, forking = true).canFork)
        assertFalse(ChatState(load = ChatLoad.Ready, sessionId = "s", supportsFork = true, run = app.rasul.chat.ActiveRun("r", 0, StatusLabel.Thinking)).canFork)
        assertTrue(ChatState(load = ChatLoad.Ready, sessionId = "s", supportsFork = true).canFork)
    }
}
