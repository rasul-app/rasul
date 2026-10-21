package app.rasul

import app.rasul.chat.ChatViewModel
import app.rasul.chat.OrderedWriter
import app.rasul.chat.TargetLoader
import app.rasul.data.BusyChatsApi
import app.rasul.data.BusyChatsResult
import app.rasul.data.BusyWhere
import app.rasul.data.HermesResult
import app.rasul.data.HermesTarget
import app.rasul.data.busyApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * An open chat watches the bridge's turn leases while it is on screen: a desktop lease on this
 * chat shows the "replying on PC" banner, a busy -> idle edge reloads the messages once, and a
 * direct (bridge-less) target never asks anything.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatBusyOnPcTest {
    private val sched = TestCoroutineScheduler()
    private val runs = FakeRuns()
    private val sessions = FakeSessions()
    private val memory = FakeMemory()
    private val busy = FakeBusyChats()
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

    private fun vm(
        api: BusyChatsApi? = busy,
        mem: FakeMemory = memory,
        busyApiFor: (HermesTarget) -> BusyChatsApi? = { api },
    ): ChatViewModel {
        val t = HermesTarget("http://example.invalid/", null, "k")
        return ChatViewModel(
            TargetLoader { HermesResult.Ok(t) },
            runs, sessions, mem, writer, now = { 0L },
            busyApiFor = busyApiFor,
            busyPollMs = ChatViewModel.BUSY_POLL_MS,
        ).also { settle() }
    }

    @Test
    fun desktopBusyShowsTheBannerAndPollsEveryFiveSeconds() {
        busy.next = BusyChatsResult.Ok(mapOf("sess-1" to BusyWhere.Desktop))
        val v = vm()
        v.onScreenActive(true); settle()
        assertTrue(v.state.value.busyOnPc)
        assertEquals(1, busy.calls)
        pass(5_000)
        assertEquals(2, busy.calls)
    }

    @Test
    fun appBusyAndIdleShowNoBanner() {
        busy.next = BusyChatsResult.Ok(mapOf("sess-1" to BusyWhere.App))
        val v = vm()
        v.onScreenActive(true); settle()
        assertFalse(v.state.value.busyOnPc)
        busy.next = BusyChatsResult.Ok(mapOf("other-chat" to BusyWhere.Desktop))
        pass(5_000)
        assertFalse(v.state.value.busyOnPc)
    }

    @Test
    fun busyToIdleReloadsTheChatExactlyOnce() {
        busy.next = BusyChatsResult.Ok(mapOf("sess-1" to BusyWhere.Desktop))
        val v = vm()
        val reads = sessions.calls.size
        v.onScreenActive(true); settle()
        assertTrue(v.state.value.busyOnPc)
        busy.next = BusyChatsResult.Ok(emptyMap())
        pass(5_000)
        assertFalse(v.state.value.busyOnPc)
        assertEquals(reads + 1, sessions.calls.size)
        assertEquals("sess-1", sessions.calls.last())
        pass(15_000)
        assertEquals(reads + 1, sessions.calls.size) // not again while it stays idle
    }

    @Test
    fun anOldBridgeEndsTheFeatureQuietly() {
        busy.next = BusyChatsResult.Unsupported
        val v = vm()
        v.onScreenActive(true); settle()
        assertFalse(v.state.value.busyOnPc)
        pass(20_000)
        assertEquals(1, busy.calls)
    }

    @Test
    fun aFailedAskKeepsTheLastKnownAnswer() {
        busy.next = BusyChatsResult.Ok(mapOf("sess-1" to BusyWhere.Desktop))
        val v = vm()
        v.onScreenActive(true); settle()
        assertTrue(v.state.value.busyOnPc)
        busy.next = BusyChatsResult.Failed
        pass(10_000)
        assertTrue(v.state.value.busyOnPc)
        assertTrue(busy.calls >= 3)
    }

    @Test
    fun thisPhonesOwnRunKeepsTheAskAndBannerOff() {
        val mem = FakeMemory(active = "run_live", cursor = 3L)
        busy.next = BusyChatsResult.Ok(mapOf("sess-1" to BusyWhere.Desktop))
        val v = vm(mem = mem)
        assertTrue(v.state.value.run != null)
        v.onScreenActive(true); settle()
        pass(20_000)
        assertEquals(0, busy.calls)
        assertFalse(v.state.value.busyOnPc)
    }

    @Test
    fun noAsksAndNoBannerOnADirectTarget() {
        var consulted = 0
        val v = vm(busyApiFor = { t -> consulted++; t.busyApi() }) // bridge == null -> null
        v.onScreenActive(true); settle()
        pass(20_000)
        assertTrue(consulted >= 1)
        assertFalse(v.state.value.busyOnPc)
        assertEquals(0, busy.calls)
        val reads = sessions.calls.size
        pass(20_000)
        assertEquals(reads, sessions.calls.size)
    }

    @Test
    fun anAskThatLandsAfterChatSwitchKeepsPollingForTheNewChat() {
        busy.next = BusyChatsResult.Ok(mapOf("sess-1" to BusyWhere.Desktop))
        val v = vm()
        v.onScreenActive(true); settle()
        assertTrue(v.state.value.busyOnPc)
        assertEquals(1, busy.calls)

        // The next ask hangs in flight; the user switches chats while it is still suspended.
        busy.gate = CompletableDeferred()
        pass(5_000)
        assertEquals(2, busy.calls)
        memory.storedSession = "sess-2"
        v.retryHistory()
        settle()
        assertFalse(v.state.value.busyOnPc)

        // The answer for the old chat lands now: it is dropped and the loop polls the new chat
        // right away (the dropped answer skips straight to the next tick).
        busy.gate!!.complete(Unit)
        settle()
        assertEquals(3, busy.calls)
        assertFalse(v.state.value.busyOnPc) // sess-1's lease does not apply to sess-2
        busy.next = BusyChatsResult.Ok(mapOf("sess-2" to BusyWhere.Desktop))
        pass(5_000)
        assertEquals(4, busy.calls)
        assertTrue(v.state.value.busyOnPc)
    }

    @Test
    fun hidingTheScreenStopsTheAsks() {
        busy.next = BusyChatsResult.Ok(mapOf("sess-1" to BusyWhere.Desktop))
        val v = vm()
        v.onScreenActive(true); settle()
        v.onScreenActive(false); settle()
        val asks = busy.calls
        pass(30_000)
        assertEquals(asks, busy.calls)
    }
}
