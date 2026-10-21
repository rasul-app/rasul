package app.rasul

import app.rasul.chat.ChatViewModel
import app.rasul.chat.MessageKind
import app.rasul.chat.OrderedWriter
import app.rasul.chat.TargetLoader
import app.rasul.data.BusyChatsResult
import app.rasul.data.BusyWhere
import app.rasul.data.HermesError
import app.rasul.data.HermesResult
import app.rasul.data.HermesTarget
import app.rasul.data.LiveApi
import app.rasul.data.LiveError
import app.rasul.data.LiveEvent
import app.rasul.data.LiveFrame
import app.rasul.data.LiveStreamException
import app.rasul.data.liveApi
import app.rasul.sessions.SessionMessageDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A [LiveApi] whose streams the test drives frame by frame; each close is observable. */
internal class FakeLive : LiveApi {
    data class Call(val sessionId: String, val lastEventId: String?)

    class Stream {
        internal val channel = Channel<LiveFrame>(Channel.UNLIMITED)
        var open = true
        fun send(id: String?, event: LiveEvent) {
            check(channel.trySend(LiveFrame(id, event)).isSuccess)
        }
        fun finish(cause: Throwable? = null) {
            channel.close(cause)
        }
    }

    val calls = mutableListOf<Call>()
    val streams = mutableListOf<Stream>()
    var failOnOpen: LiveStreamException? = null

    override fun events(sessionId: String, lastEventId: String?): Flow<LiveFrame> {
        calls += Call(sessionId, lastEventId)
        failOnOpen?.let { failure -> return flow { throw failure } }
        val stream = Stream()
        streams += stream
        return channelFlow {
            try {
                for (frame in stream.channel) send(frame)
            } finally {
                stream.open = false
            }
        }
    }
}

/**
 * While the desktop answers the open chat the phone follows its live stream: deltas grow a mirrored
 * reply, tool and status frames drive the usual progress card, `idle`/`reset`/drops and every way
 * of leaving the chat behave per the bridge contract — while an unavailable or old bridge leaves
 * today's polling and banner untouched.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatPcLiveTest {
    private val sched = TestCoroutineScheduler()
    private val runs = FakeRuns()
    private val sessions = FakeSessions()
    private val memory = FakeMemory()
    private val busy = FakeBusyChats()
    private val live = FakeLive()
    private val writer = OrderedWriter(CoroutineScope(StandardTestDispatcher(sched)))

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher(sched))

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun settle() = sched.runCurrent()
    private fun pass(ms: Long) {
        sched.advanceTimeBy(ms)
        sched.runCurrent()
    }

    private fun busyOnPc(vararg sessions: String) =
        BusyChatsResult.Ok(sessions.associateWith { BusyWhere.Desktop })

    private fun vm(
        liveApiFor: (HermesTarget) -> LiveApi? = { live },
    ): ChatViewModel {
        val t = HermesTarget("http://example.invalid/", null, "k")
        return ChatViewModel(
            TargetLoader { HermesResult.Ok(t) },
            runs, sessions, memory, writer, now = { 0L },
            busyApiFor = { busy },
            liveApiFor = liveApiFor,
            busyPollMs = ChatViewModel.BUSY_POLL_MS,
        ).also { settle() }
    }

    /** A chat busy on the PC with one attached stream; returns it. */
    private fun attached(v: ChatViewModel): FakeLive.Stream {
        busy.next = busyOnPc("sess-1")
        v.onScreenActive(true); settle()
        assertTrue(v.state.value.busyOnPc)
        assertEquals(listOf(FakeLive.Call("sess-1", null)), live.calls)
        return live.streams.single()
    }

    @Test
    fun busyOnPcOpensTheLiveStream() {
        val v = vm()
        val stream = attached(v)
        assertTrue(stream.open)
        assertNull(v.state.value.pcLive)
    }

    @Test
    fun aTargetWithoutTheLiveRouteKeepsJustTheBanner() {
        var consulted = 0
        val v = vm(liveApiFor = { t -> consulted++; t.liveApi() }) // no bridge on this target -> null
        busy.next = busyOnPc("sess-1")
        v.onScreenActive(true); settle()
        assertTrue(consulted >= 1)
        assertTrue(v.state.value.busyOnPc)
        assertNull(v.state.value.pcLive)
    }

    @Test
    fun deltasBuildTheVisibleReplyInOrder() {
        val v = vm()
        val s = attached(v)
        s.send("1:1", LiveEvent.Snapshot(running = true, turnStartedAtMs = null))
        s.send("1:2", LiveEvent.MessageStart)
        s.send("1:3", LiveEvent.MessageDelta("Hel"))
        s.send("1:4", LiveEvent.MessageDelta("lo"))
        settle()
        val reply = v.state.value.messages.last()
        assertEquals(MessageKind.Assistant, reply.kind)
        assertEquals("Hello", reply.text)
        s.send("1:5", LiveEvent.MessageComplete(status = "done", text = null, error = null))
        settle()
        assertEquals("Hello", v.state.value.messages.last().text)
    }

    @Test
    fun aFreshSegmentStartsAfterAToolStart() {
        val v = vm()
        val s = attached(v)
        s.send("1:1", LiveEvent.Snapshot(true, null))
        s.send("1:2", LiveEvent.MessageDelta("first"))
        s.send("1:3", LiveEvent.ToolStart("t1", "search"))
        s.send("1:4", LiveEvent.MessageDelta("second"))
        settle()
        assertEquals(listOf("first", "second"), v.state.value.messages.takeLast(2).map { it.text })
    }

    @Test
    fun toolAndStatusFramesUpdateTheProgressCard() {
        val v = vm()
        val s = attached(v)
        s.send("1:1", LiveEvent.Snapshot(true, null))
        s.send("1:2", LiveEvent.ToolStart("t1", "search"))
        s.send("1:3", LiveEvent.StatusUpdate("Reading files"))
        settle()
        var live = v.state.value.pcLive!!
        assertEquals("Reading files", live.statusText)
        assertEquals("search", live.progress.steps.single().tool)
        assertTrue(live.progress.steps.single().running)
        s.send("1:4", LiveEvent.ToolComplete("t1", "search", "3 files"))
        settle()
        live = v.state.value.pcLive!!
        assertFalse(live.progress.steps.single().running)
        assertEquals("3 files", live.progress.steps.single().preview)
    }

    @Test
    fun aDroppedConnectionReconnectsWithTheLastEventId() {
        val v = vm()
        val s = attached(v)
        s.send("10:1", LiveEvent.Snapshot(true, null))
        s.send("10:7", LiveEvent.MessageDelta("hi"))
        settle()
        s.finish()
        settle()
        pass(999)
        assertEquals(1, live.calls.size)
        pass(1)
        assertEquals(listOf(FakeLive.Call("sess-1", null), FakeLive.Call("sess-1", "10:7")), live.calls)
    }

    @Test
    fun aFailedOpenRetriesWithBackoff() {
        live.failOnOpen = LiveStreamException(LiveError.Failed(HermesError.BridgeUnreachable))
        val v = vm()
        busy.next = busyOnPc("sess-1")
        v.onScreenActive(true); settle()
        assertEquals(1, live.calls.size)
        pass(1_000)
        assertEquals(2, live.calls.size)
        pass(2_000)
        assertEquals(3, live.calls.size)
        pass(4_000)
        assertEquals(4, live.calls.size)
    }

    @Test
    fun limitedWaitsRetryAfterBeforeTryingAgain() {
        live.failOnOpen = LiveStreamException(LiveError.Limited(7_000))
        val v = vm()
        busy.next = busyOnPc("sess-1")
        v.onScreenActive(true); settle()
        assertEquals(1, live.calls.size)
        pass(6_999)
        assertEquals(1, live.calls.size)
        pass(1)
        assertEquals(2, live.calls.size)
    }

    @Test
    fun anUnavailableEventEndsLiveButKeepsPollingAndBanner() {
        val v = vm()
        val s = attached(v)
        s.send("1:1", LiveEvent.Unavailable("desktop-not-running"))
        settle()
        assertFalse(s.open)
        pass(20_000)
        assertTrue(v.state.value.busyOnPc)
        assertTrue(busy.calls >= 4)
        assertEquals(1, live.calls.size)
    }

    @Test
    fun anUnsupportedOpenEndsLiveButKeepsPollingAndBanner() {
        live.failOnOpen = LiveStreamException(LiveError.Unsupported)
        val v = vm()
        busy.next = busyOnPc("sess-1")
        v.onScreenActive(true); settle()
        assertTrue(v.state.value.busyOnPc)
        assertEquals(1, live.calls.size)
        pass(20_000)
        assertTrue(v.state.value.busyOnPc)
        assertTrue(busy.calls >= 4)
        assertEquals(1, live.calls.size)
    }

    @Test
    fun thisPhonesOwnRunCancelsTheMirror() {
        val v = vm()
        val s = attached(v)
        s.send("1:1", LiveEvent.Snapshot(true, null))
        s.send("1:2", LiveEvent.MessageDelta("pc text"))
        settle()
        v.onDraft("take over")
        v.send()
        settle()
        assertFalse(s.open)
        assertNull(v.state.value.pcLive)
        assertTrue(v.state.value.run != null)
        pass(30_000)
        assertEquals(1, live.calls.size)
    }

    private fun busyOn(session: String) = BusyChatsResult.Ok(mapOf(session to BusyWhere.Desktop))

    private fun finalReply() = HermesResult.Ok(
        listOf(
            SessionMessageDto(id = JsonPrimitive(1), role = "user", content = JsonPrimitive("q")),
            SessionMessageDto(id = JsonPrimitive(2), role = "assistant", content = JsonPrimitive("FINAL")),
        ),
    )

    private fun texts(v: ChatViewModel) = v.state.value.messages.map { it.text }
    private fun savedFinalShown(v: ChatViewModel) = v.state.value.messages.any { it.id < 0 && it.text == "FINAL" }

    @Test
    fun idleClosesTheStreamKeepsTheMirrorAndLeavesTheReloadToTheBusyEdge() {
        val v = vm()
        val s = attached(v)
        val reads = sessions.calls.size
        s.send("1:1", LiveEvent.Snapshot(true, null))
        s.send("1:2", LiveEvent.MessageDelta("done"))
        settle()
        s.send(null, LiveEvent.Idle)
        settle()
        assertFalse(s.open)
        assertEquals("done", v.state.value.messages.last().text)
        assertEquals(reads, sessions.calls.size)
        // The lease can outlive the stream: while it still says busy, no new stream and no reload.
        pass(20_000)
        assertTrue(v.state.value.busyOnPc)
        assertEquals(1, live.calls.size)
        assertEquals(reads, sessions.calls.size)
        // The busy -> idle edge reloads the turn exactly once.
        sessions.result = finalReply()
        busy.next = BusyChatsResult.Ok(emptyMap())
        pass(5_000)
        assertFalse(v.state.value.busyOnPc)
        assertEquals(reads + 1, sessions.calls.size)
        assertEquals(listOf("q", "FINAL"), texts(v))
        // A later turn opens the live view again.
        busy.next = busyOnPc("sess-1")
        pass(5_000)
        assertEquals(2, live.calls.size)
    }

    @Test
    fun idleShowsTheReplyOnceWhenTheLeaseEnds() {
        val v = vm()
        val s = attached(v)
        s.send("1:1", LiveEvent.Snapshot(true, null))
        s.send("1:2", LiveEvent.MessageDelta("FINAL"))
        s.send("1:3", LiveEvent.MessageComplete("done", "FINAL", null))
        settle()
        sessions.result = finalReply()
        s.send(null, LiveEvent.Idle)
        settle()
        busy.next = BusyChatsResult.Ok(emptyMap())
        pass(5_000)
        pass(5_000)
        assertEquals(listOf("q", "FINAL"), texts(v))
        assertNull(v.state.value.pcLive)
    }

    @Test
    fun leavingTheScreenDropsTheMirroredText() {
        val v = vm()
        val s = attached(v)
        s.send("1:1", LiveEvent.Snapshot(true, null))
        s.send("1:2", LiveEvent.MessageDelta("partial"))
        settle()
        assertEquals("partial", v.state.value.messages.last().text)
        v.onScreenActive(false)
        settle()
        assertFalse(s.open)
        assertNull(v.state.value.pcLive)
        assertTrue(texts(v).toString(), texts(v).none { it == "partial" })
        pass(30_000)
        assertEquals(1, live.calls.size)
    }

    @Test
    fun anIdleInFlightReloadCancelledByThePollStillLoadsTheAnswer() {
        val v = vm()
        val s = attached(v)
        s.send("1:1", LiveEvent.Snapshot(true, null))
        settle()
        sessions.result = finalReply()
        sessions.historyGate = CompletableDeferred()
        s.send(null, LiveEvent.Idle)
        settle()
        busy.next = BusyChatsResult.Ok(emptyMap())
        pass(5_000)
        sessions.historyGate!!.complete(Unit)
        sessions.historyGate = null
        pass(30_000)
        assertTrue(texts(v).toString(), savedFinalShown(v))
    }

    @Test
    fun idleAtAttachStillReloadsWhenTheTurnEnds() {
        val v = vm()
        val s = attached(v)
        s.send(null, LiveEvent.Idle)
        settle()
        pass(20_000)
        sessions.result = finalReply()
        busy.next = BusyChatsResult.Ok(emptyMap())
        pass(5_000)
        pass(5_000)
        assertTrue(texts(v).toString(), savedFinalShown(v))
    }

    @Test
    fun anIdleMidTurnReloadsOncePerTurnNotAtTheIdle() {
        val v = vm()
        val s = attached(v)
        val reads = sessions.calls.size
        s.send("1:1", LiveEvent.Snapshot(true, null))
        s.send(null, LiveEvent.Idle)
        settle()
        assertEquals(reads, sessions.calls.size)
        pass(15_000)
        assertEquals(reads, sessions.calls.size)
        sessions.result = finalReply()
        busy.next = BusyChatsResult.Ok(emptyMap())
        pass(5_000)
        pass(10_000)
        assertEquals(reads + 1, sessions.calls.size)
        assertTrue(texts(v).toString(), savedFinalShown(v))
    }

    @Test
    fun resetReloadsOnceClearsTheMirrorAndKeepsReadingTheSameStream() {
        val v = vm()
        val s = attached(v)
        val reads = sessions.calls.size
        s.send("1:1", LiveEvent.Snapshot(true, null))
        s.send("1:5", LiveEvent.MessageDelta("part"))
        settle()
        s.send(null, LiveEvent.Reset)
        settle()
        assertTrue(s.open)
        assertNull(v.state.value.pcLive)
        assertTrue(texts(v).none { it == "part" })
        assertEquals(reads + 1, sessions.calls.size)
        s.send("2:1", LiveEvent.MessageDelta("again"))
        settle()
        assertEquals("again", v.state.value.messages.last().text)
        pass(30_000)
        assertEquals(1, live.calls.size)
        assertEquals(reads + 1, sessions.calls.size)
        // The stored Last-Event-ID was cleared by the reset: the next one is the stream's own.
        s.finish()
        settle()
        pass(1_000)
        assertEquals(FakeLive.Call("sess-1", "2:1"), live.calls.last())
    }

    @Test
    fun aDropRightAfterAResetReconnectsWithoutAnId() {
        val v = vm()
        val s = attached(v)
        s.send("1:5", LiveEvent.MessageDelta("part"))
        s.send(null, LiveEvent.Reset)
        settle()
        s.finish()
        settle()
        pass(1_000)
        assertEquals(FakeLive.Call("sess-1", null), live.calls.last())
    }

    @Test
    fun aSnapshotThatIsNotRunningShowsNoCard() {
        val v = vm()
        val s = attached(v)
        s.send("1:1", LiveEvent.Snapshot(running = false, turnStartedAtMs = null))
        settle()
        assertNull(v.state.value.pcLive)
        s.send("1:2", LiveEvent.MessageDelta("late"))
        settle()
        assertEquals("late", v.state.value.messages.last().text)
        assertTrue(v.state.value.pcLive != null)
    }

    @Test
    fun theElapsedStartNeverLiesAheadOfThePhoneClock() {
        val v = vm()
        val s = attached(v)
        s.send("1:1", LiveEvent.Snapshot(true, 9_000_000L))
        settle()
        assertEquals(0L, v.state.value.pcLive!!.startedAtMs)
    }

    @Test
    fun repeatedDropsBackOffAndOnlyRelayedEventsResetIt() {
        val v = vm()
        busy.next = busyOnPc("sess-1")
        v.onScreenActive(true); settle()
        // Every attempt gets a snapshot, then the stream closes: no relayed event, so no reset.
        val waits = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L)
        for ((i, wait) in waits.withIndex()) {
            live.streams.last().send("1:1", LiveEvent.Snapshot(true, null))
            live.streams.last().finish()
            settle()
            assertEquals(i + 1, live.calls.size)
            pass(wait - 1)
            assertEquals("attempt ${i + 1} waits $wait", i + 1, live.calls.size)
            pass(1)
            assertEquals(i + 2, live.calls.size)
        }
        // One relayed event resets the backoff to 1 s.
        live.streams.last().send("1:2", LiveEvent.MessageDelta("x"))
        live.streams.last().finish()
        settle()
        val n = live.calls.size
        pass(999)
        assertEquals(n, live.calls.size)
        pass(1)
        assertEquals(n + 1, live.calls.size)
    }

    @Test
    fun unavailableDoesNotMuteTheNextChat() {
        val v = vm()
        val s = attached(v)
        s.send(null, LiveEvent.Unavailable("desktop-not-running"))
        settle()
        memory.storedSession = "sess-2"
        v.retryHistory()
        settle()
        busy.next = busyOn("sess-2")
        pass(5_000)
        assertEquals(FakeLive.Call("sess-2", null), live.calls.last())
    }

    @Test
    fun aMissingLiveRouteStaysOffAcrossChats() {
        live.failOnOpen = LiveStreamException(LiveError.Unsupported)
        val v = vm()
        busy.next = busyOnPc("sess-1")
        v.onScreenActive(true); settle()
        assertEquals(1, live.calls.size)
        live.failOnOpen = null
        memory.storedSession = "sess-2"
        v.retryHistory()
        settle()
        busy.next = busyOn("sess-2")
        pass(10_000)
        assertEquals(1, live.calls.size)
    }

    @Test
    fun switchingChatsCancelsTheStreamAndAppliesNothingToTheNewChat() {
        val v = vm()
        val s = attached(v)
        s.send("1:1", LiveEvent.Snapshot(true, null))
        s.send("1:2", LiveEvent.MessageDelta("old chat"))
        settle()
        assertEquals("old chat", v.state.value.messages.last().text)
        memory.storedSession = "sess-2"
        v.retryHistory()
        // The old stream keeps talking in the same tick as the switch, and after it.
        s.send("1:3", LiveEvent.Snapshot(true, null))
        s.send("1:4", LiveEvent.MessageDelta("stale"))
        settle()
        s.send("1:5", LiveEvent.MessageDelta("stale again"))
        settle()
        assertFalse(s.open)
        assertNull(v.state.value.pcLive)
        assertEquals("sess-2", v.state.value.sessionId)
        assertTrue(texts(v).toString(), texts(v).none { "stale" in it || "old chat" in it })
        // The new chat follows its own lease only.
        busy.next = busyOn("sess-2")
        pass(5_000)
        assertTrue(v.state.value.busyOnPc)
        assertEquals(FakeLive.Call("sess-2", null), live.calls.last())
    }
}
