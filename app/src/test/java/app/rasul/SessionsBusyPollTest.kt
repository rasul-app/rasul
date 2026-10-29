package app.rasul

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.rasul.data.ActiveTarget
import app.rasul.data.BusyChatsApi
import app.rasul.data.BusyChatsResult
import app.rasul.data.BusyWhere
import app.rasul.data.busyApi
import app.rasul.data.Connection
import app.rasul.data.ConnectionStore
import app.rasul.data.HermesError
import app.rasul.data.HermesResult
import app.rasul.data.HermesTarget
import app.rasul.data.Load
import app.rasul.data.SecretCipher
import app.rasul.sessions.SessionDto
import app.rasul.sessions.SessionsManagementApi
import app.rasul.sessions.SessionsViewModel
import app.rasul.ui.ActivePcHolder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The sessions screen keeps itself fresh while it is open: a quiet refresh of the list and the
 * bridge's busy map every poll tick, stopped the moment the screen is not visible.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionsBusyPollTest {

    @get:Rule val tmp = TemporaryFolder()

    private val cipher = object : SecretCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(token: String) = token
    }
    private val ds: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO),
            produceFile = { File(tmp.newFolder("ds"), "test.preferences_pb") },
        )
    }
    private val sched = TestCoroutineScheduler()
    private val chats = FakeSessionList()
    private val busy = FakeBusyChats()

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher(sched))

    @After
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun open(
        busyFor: (HermesTarget) -> BusyChatsApi? = { busy },
    ): SessionsViewModel {
        val store = ConnectionStore(ds, cipher)
        store.save(Connection("", "A", "http://192.0.2.7/"), "k")
        val holder = ActivePcHolder(store, CoroutineScope(Job() + Dispatchers.Unconfined))
        waitFor { holder.state.value.loaded }
        return SessionsViewModel(store, holder, chats, busyApiFor = busyFor, pollMs = SessionsViewModel.LIST_POLL_MS).also {
            sched.runCurrent()
            assertTrue(it.state.value is Load.Ready)
        }
    }

    private fun waitFor(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue("timed out", cond())
    }

    @Test
    fun refreshesListAndBusyMapEveryFiveSecondsWhileVisible() = runBlocking {
        chats.list = listOf(row("s1", "First"))
        busy.next = BusyChatsResult.Ok(mapOf("s1" to BusyWhere.Desktop))
        val vm = open()
        assertEquals(1, chats.calls)
        vm.onVisible()
        sched.advanceTimeBy(5_000); sched.runCurrent()
        assertEquals(2, chats.calls)
        assertEquals(mapOf("s1" to BusyWhere.Desktop), vm.busyChats.value)
        chats.list = listOf(row("s1", "First"), row("s2", "Second"))
        sched.advanceTimeBy(10_000); sched.runCurrent()
        assertEquals(4, chats.calls)
        assertEquals(listOf("s1", "s2"), (vm.state.value as Load.Ready).value.map { it.id })
    }

    @Test
    fun stopsRefreshingWhenNotVisible() = runBlocking {
        val vm = open()
        vm.onVisible()
        sched.advanceTimeBy(5_000); sched.runCurrent()
        vm.onHidden()
        sched.runCurrent()
        val calls = chats.calls
        val asks = busy.calls
        sched.advanceTimeBy(30_000); sched.runCurrent()
        assertEquals(calls, chats.calls)
        assertEquals(asks, busy.calls)
    }

    @Test
    fun neverPollsWhileItWasNeverVisible() = runBlocking {
        open()
        sched.advanceTimeBy(60_000); sched.runCurrent()
        assertEquals(1, chats.calls) // only the opening load
        assertEquals(0, busy.calls)
    }

    @Test
    fun aFailedTickKeepsTheShownListAndBusyMap() = runBlocking {
        chats.list = listOf(row("s1", "First"))
        busy.next = BusyChatsResult.Ok(mapOf("s1" to BusyWhere.App))
        val vm = open()
        vm.onVisible()
        sched.advanceTimeBy(5_000); sched.runCurrent()
        val shown = (vm.state.value as Load.Ready).value
        chats.fail = true
        busy.next = BusyChatsResult.Failed
        sched.advanceTimeBy(10_000); sched.runCurrent()
        assertEquals(shown, (vm.state.value as Load.Ready).value)
        assertEquals(mapOf("s1" to BusyWhere.App), vm.busyChats.value)
    }

    @Test
    fun anOldBridgeKeepsBusyOffAndStopsAsking() = runBlocking {
        busy.next = BusyChatsResult.Unsupported
        val vm = open()
        vm.onVisible()
        sched.advanceTimeBy(5_000); sched.runCurrent()
        assertEquals(emptyMap<String, BusyWhere>(), vm.busyChats.value)
        assertEquals(1, busy.calls)
        sched.advanceTimeBy(20_000); sched.runCurrent()
        assertEquals(1, busy.calls)
    }

    @Test
    fun aFailedAskKeepsTheLastKnownMap() = runBlocking {
        busy.next = BusyChatsResult.Ok(mapOf("s1" to BusyWhere.Desktop))
        val vm = open()
        vm.onVisible()
        sched.advanceTimeBy(5_000); sched.runCurrent()
        assertEquals(mapOf("s1" to BusyWhere.Desktop), vm.busyChats.value)
        busy.next = BusyChatsResult.Failed
        sched.advanceTimeBy(10_000); sched.runCurrent()
        assertEquals(mapOf("s1" to BusyWhere.Desktop), vm.busyChats.value)
    }

    @Test
    fun aStaleUnsupportedDoesNotMuteTheNewTarget() = runBlocking {
        chats.list = listOf(row("s1", "First"))
        val store = ConnectionStore(ds, cipher)
        store.save(Connection("a", "A", "http://192.0.2.7/"), "k")
        store.save(Connection("b", "B", "http://192.0.2.8/"), "k")
        val holder = ActivePcHolder(store, CoroutineScope(Job() + Dispatchers.Unconfined))
        waitFor { holder.state.value.loaded }
        val apiA = FakeBusyChats().apply {
            next = BusyChatsResult.Unsupported
            gate = CompletableDeferred()
        }
        val apiB = FakeBusyChats().apply {
            next = BusyChatsResult.Ok(mapOf("s1" to BusyWhere.Desktop))
        }
        val vm = SessionsViewModel(store, holder, chats,
            busyApiFor = { t -> if (t.baseUrl == "http://192.0.2.7/") apiA else apiB })
        sched.runCurrent()
        assertTrue(vm.state.value is Load.Ready)

        // A poll tick asks the old bridge and stays suspended inside it.
        vm.onVisible()
        sched.advanceTimeBy(5_000); sched.runCurrent()
        assertEquals(1, apiA.calls)
        assertEquals(0, apiB.calls)

        // The user switches PCs while that ask is still in flight.
        vm.selectPc("b")
        // The pill shows the pick at once; the target follows once the pick is saved.
        waitFor { (holder.state.value.target as? ActiveTarget.Ready)?.id == "b" }
        sched.runCurrent()
        assertEquals("b", (vm.state.value as Load.Ready).source.id)

        // The old bridge's 404 lands now: it belongs to A, so B keeps polling and shows badges.
        apiA.gate!!.complete(Unit)
        sched.runCurrent()
        sched.advanceTimeBy(5_000); sched.runCurrent()
        assertEquals(1, apiB.calls)
        assertEquals(mapOf("s1" to BusyWhere.Desktop), vm.busyChats.value)
    }

    @Test
    fun aDirectTargetNeverAsksTheBridge() = runBlocking {
        var consulted = 0
        val vm = open(busyFor = { t -> consulted++; t.busyApi() }) // no bridge on this target -> null
        vm.onVisible()
        sched.advanceTimeBy(20_000); sched.runCurrent()
        assertTrue(consulted >= 1)
        assertEquals(emptyMap<String, BusyWhere>(), vm.busyChats.value)
        assertEquals(0, busy.calls)
    }

    private fun row(id: String, title: String) =
        SessionDto(id, title, startedAt = JsonPrimitive(1_760_000_000), messageCount = 1)
}

internal class FakeSessionList : SessionsManagementApi {
    var calls = 0
    var list: List<SessionDto> = emptyList()
    var fail = false
    var botCalls = 0
    var botFor: (HermesTarget) -> HermesResult<SessionDto?> = { HermesResult.Ok(null) }

    /** When set, botChat() waits on it first — a look-up still in flight when the test moves on. */
    var botGate: CompletableDeferred<Unit>? = null

    override suspend fun list(target: HermesTarget, limit: Int): HermesResult<List<SessionDto>> {
        calls++
        return if (fail) HermesResult.Err(HermesError.Unreachable("down")) else HermesResult.Ok(list)
    }

    override suspend fun botChat(target: HermesTarget): HermesResult<SessionDto?> {
        botCalls++
        botGate?.await()
        return botFor(target)
    }

    override suspend fun rename(target: HermesTarget, sessionId: String, title: String) =
        HermesResult.Err(HermesError.NotFound)

    override suspend fun delete(target: HermesTarget, sessionId: String) =
        HermesResult.Err(HermesError.NotFound)
}

internal class FakeBusyChats : BusyChatsApi {
    var calls = 0
    var next: BusyChatsResult = BusyChatsResult.Ok(emptyMap())

    /** When set, list() waits on it first — an ask that is still in flight when the test moves on. */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun list(): BusyChatsResult {
        calls++
        gate?.await()
        return next
    }
}
