package app.rasul.share

import app.rasul.bridge.BridgeBotsState
import app.rasul.bridge.BridgeGroup
import app.rasul.bridge.BridgeProfile
import app.rasul.data.Connection
import app.rasul.data.HermesError
import app.rasul.data.HermesResult
import app.rasul.sessions.SessionDto
import app.rasul.ui.ChatBotChoice
import app.rasul.ui.ConnectionsState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SendToTest {
    private val sched = TestCoroutineScheduler()

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher(sched))
    @After fun tearDown() = Dispatchers.resetMain()

    private fun profile(id: String, ready: Boolean = true) = BridgeProfile(id, id, id.replaceFirstChar { it.uppercase() }, ready, false)
    private val direct = ConnectionsState.Loaded(listOf(Connection("c1", "Home bot", "http://example.invalid/")))
    private fun session(id: String, last: Double? = null, source: String? = null) =
        SessionDto(id, "title $id", source, lastActive = last?.let { JsonPrimitive(it) })

    @Test fun botsAreDirectBotsAndReadyBridgeProfiles() {
        val bridges = BridgeBotsState(
            groups = listOf(
                BridgeGroup("b1", "Study PC", listOf(profile("research"), profile("idle", ready = false)), loading = false),
                BridgeGroup("b2", "Broken PC", listOf(profile("x")), loading = false, error = HermesError.BridgeUnreachable),
                BridgeGroup("b3", "Waking PC", listOf(profile("y")), loading = true),
            ),
            loaded = true,
        )
        val bots = sendToBots(direct, bridges)
        assertEquals(listOf("c1", "bridge:b1:research"), bots.map { it.id })
        assertEquals(ChatBotChoice("bridge:b1:research", "Research", "b1", "research", "Study PC"), bots[1])
    }

    @Test fun botsAreEmptyUntilSomethingLoads() {
        assertTrue(sendToBots(ConnectionsState.Loading, BridgeBotsState()).isEmpty())
        assertTrue(sendToBotsLoading(ConnectionsState.Loading, BridgeBotsState()))
        assertFalse(sendToBotsLoading(direct, BridgeBotsState(loaded = true)))
    }

    @Test fun recentChatsAreTheTwentyNewestWithoutJobRuns() {
        val all = (1..30).map { session("s$it", last = it.toDouble()) } + session("job", last = 99.0, source = "cron")
        val recent = recentChats(all)
        assertEquals(SEND_TO_RECENT_CHATS, recent.size)
        assertEquals("s30", recent.first().id)
        assertEquals("s11", recent.last().id)
        assertTrue(recent.none { it.id == "job" })
    }

    @Test fun recentChatsKeepServerOrderWithoutTimes() {
        assertEquals(listOf("a", "b", "c"), recentChats(listOf(session("a"), session("b"), session("c"))).map { it.id })
    }

    private val bot = ChatBotChoice("c1", "Home bot")
    private val other = ChatBotChoice("c2", "Other bot")

    @Test fun pickingABotLoadsItsChatsThenBackReturnsToTheBotList() {
        val vm = SendToViewModel { HermesResult.Ok(listOf(session("a", 1.0), session("b", 2.0))) }
        assertEquals(SendToState(), vm.state.value)
        vm.pick(bot)
        assertEquals(SendToState(bot, RecentChats.Loading), vm.state.value)
        sched.advanceUntilIdle()
        assertEquals(listOf("b", "a"), (vm.state.value.recents as RecentChats.Ready).chats.map { it.id })
        assertTrue(vm.back())
        assertEquals(SendToState(), vm.state.value)
        assertFalse(vm.back())
    }

    @Test fun aFailedLoadCanBeRetried() {
        var calls = 0
        val vm = SendToViewModel { if (++calls == 1) HermesResult.Err(HermesError.BridgeTimeout) else HermesResult.Ok(listOf(session("a"))) }
        vm.pick(bot)
        sched.advanceUntilIdle()
        assertEquals(RecentChats.Failed(HermesError.BridgeTimeout), vm.state.value.recents)
        vm.retry()
        assertEquals(RecentChats.Loading, vm.state.value.recents)
        sched.advanceUntilIdle()
        assertEquals(1, (vm.state.value.recents as RecentChats.Ready).chats.size)
    }

    @Test fun aSlowLoadForAnEarlierBotNeverOverwritesTheLaterOne() {
        val gate = CompletableDeferred<Unit>()
        val vm = SendToViewModel { b ->
            if (b == bot) gate.await()
            HermesResult.Ok(listOf(session(b.id)))
        }
        vm.pick(bot)
        sched.advanceUntilIdle()
        vm.back()
        vm.pick(other)
        gate.complete(Unit)
        sched.advanceUntilIdle()
        assertEquals(other, vm.state.value.bot)
        assertEquals(listOf("c2"), (vm.state.value.recents as RecentChats.Ready).chats.map { it.id })
    }

    @Test fun aThrowingLoaderBecomesAnError() {
        val vm = SendToViewModel { error("boom") }
        vm.pick(bot)
        sched.advanceUntilIdle()
        assertTrue(vm.state.value.recents is RecentChats.Failed)
    }
}
