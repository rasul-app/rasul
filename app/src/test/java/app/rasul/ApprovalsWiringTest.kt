package app.rasul

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import app.rasul.bridge.*
import app.rasul.data.HermesError
import app.rasul.data.SecretCipher
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64
import java.util.UUID

/**
 * Combined ViewModel + repository + fake SSE wiring: the screen's cards must reflect what the
 * repository publishes, through the real feed flow (not a stubbed layer).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalsWiringTest {
    @get:Rule val tmp = TemporaryFolder()
    private val cipher = object : SecretCipher {
        override fun encrypt(plain: String) = plain.reversed()
        override fun decrypt(token: String) = token.reversed()
    }
    private fun bridge() = SavedBridge(UUID.randomUUID().toString(), "Bridge", listOf("wss://example.invalid"),
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32)), UUID.randomUUID().toString(), UUID.randomUUID().toString())
    private fun item(id: String, expires: Double = 4_000_000_000.0) =
        ApprovalItemDto(id, "sk", "default", "shell", "cmd", "desc", true, false, expires)
    private fun profile(id: String = "default", ready: Boolean = true) =
        BridgeProfile(id, id, "Research", ready, true)
    private fun group(bridgeId: String, error: HermesError? = null, profiles: List<BridgeProfile> =
        listOf(profile())) =
        BridgeGroup(bridgeId, "Bridge", profiles, loading = false, error = error)

    private class FakeApprovals : ApprovalsApi {
        val events = Channel<ApprovalsEvent>(Channel.UNLIMITED)
        var opens = 0
        val opensFlow = MutableStateFlow(0)
        var eventsFailure: Exception? = null
        var listResult: List<ApprovalItemDto> = emptyList()
        val listResults = ArrayDeque<List<ApprovalItemDto>>()
        val listFailures = ArrayDeque<Exception>()
        var listDelayMs = 0L
        var listFailure: Exception? = null
        var listCalls = 0
        val answers = mutableListOf<Pair<String, String>>()
        var answerFailure: Exception? = null
        override suspend fun list(): List<ApprovalItemDto> {
            listCalls++
            if (listDelayMs > 0) delay(listDelayMs)
            listFailures.removeFirstOrNull()?.let { throw it }
            listFailure?.let { throw it }
            return listResults.removeFirstOrNull() ?: listResult
        }
        override fun events(): Flow<ApprovalsEvent> = flow {
            opens++
            opensFlow.value = opens
            eventsFailure?.let { throw it }
            events.receiveAsFlow().collect { emit(it) }
        }
        override suspend fun answer(requestId: String, choice: String): ApprovalAnsweredDto {
            answers += requestId to choice
            answerFailure?.let { throw it }
            return ApprovalAnsweredDto(requestId, "answered")
        }
    }

    private fun feedKey(bridgeId: String, profileId: String = "default") = bridgeBotId(bridgeId, profileId)

    // Every wait is a foreground suspension: while the test body suspends the runner keeps
    // pumping backgroundScope work and the real DataStore/SSL continuations (rule 24).
    private suspend fun awaitCards(vm: ApprovalsViewModel, vararg ids: String) =
        vm.state.first { s -> s.cards.map { it.item.requestId } == ids.toList() }

    private suspend fun TestScope.clientsAndStore(saved: List<SavedBridge>): Pair<Pair<BridgeStore, BridgeClients>, List<BridgeTransport>> {
        val ds = PreferenceDataStoreFactory.create(scope = backgroundScope) { tmp.root.resolve(UUID.randomUUID().toString() + ".preferences_pb") }
        val store = BridgeStore(ds, cipher)
        saved.forEach { store.save(it) }
        val transports = mutableListOf<BridgeTransport>()
        val clients = BridgeClients(store, backgroundScope, { _, urls, _ ->
            BridgeTransport(urls, ByteArray(32), null, newContext = { javax.net.ssl.SSLContext.getInstance("TLSv1.3") }).also { transports.add(it) }
        }, {})
        return (store to clients) to transports
    }

    private suspend fun TestScope.exercise(
        api: FakeApprovals,
        initialBots: (String) -> BridgeBotsState = { BridgeBotsState(listOf(group(it)), loaded = true) },
        block: suspend TestScope.(ApprovalsRepository, MutableStateFlow<BridgeBotsState>, String, ApprovalsViewModel) -> Unit,
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val saved = bridge()
        val (sc, transports) = clientsAndStore(listOf(saved))
        val (store, clients) = sc
        val bots = MutableStateFlow(initialBots(saved.bridgeId))
        val repository = ApprovalsRepository(store, clients, bots) { _, _ -> api }
        val following = backgroundScope.launch { repository.follow() }
        val store4vm = ViewModelStore()
        var discoveries = 0
        val vm = ApprovalsViewModel(
            repository.feed(saved.bridgeId, "default"), { api }, { repository.refresh(saved.bridgeId, "default") },
            bots = bots, bridgeId = saved.bridgeId, profileId = "default", retryDiscovery = { discoveries++ })
        store4vm.put("approvals", vm)
        try { runCurrent(); block(repository, bots, saved.bridgeId, vm) }
        finally {
            store4vm.clear()
            following.cancel()
            transports.forEach { it.close() }
            Dispatchers.resetMain()
        }
    }

    /** Two saved PCs with one ready profile each; fakes are keyed by each bridge's own client. */
    private suspend fun TestScope.exerciseTwo(
        apiA: FakeApprovals,
        apiB: FakeApprovals,
        block: suspend TestScope.(ApprovalsRepository, MutableStateFlow<BridgeBotsState>, SavedBridge, SavedBridge) -> Unit,
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val a = bridge(); val b = bridge()
        val (sc, transports) = clientsAndStore(listOf(a, b))
        val (store, clients) = sc
        val apis = java.util.concurrent.ConcurrentHashMap<OkHttpClient, FakeApprovals>()
        apis[clients.client(a).client] = apiA
        apis[clients.client(b).client] = apiB
        val bots = MutableStateFlow(BridgeBotsState())
        val repository = ApprovalsRepository(store, clients, bots) { client, _ -> apis.getValue(client) }
        val following = backgroundScope.launch { repository.follow() }
        try { runCurrent(); block(repository, bots, a, b) }
        finally { following.cancel(); transports.forEach { it.close() }; Dispatchers.resetMain() }
    }

    @Test fun eventBurstWithSlowGetStillShowsTheItem() = runTest {
        val api = FakeApprovals()
        api.listDelayMs = 300
        exercise(api) { repository, _, bridgeId, vm ->
            val a = item("a".repeat(32))
            api.listResult = listOf(a)
            api.events.send(ApprovalsEvent.Snapshot(emptyList()))
            api.opensFlow.first { it >= 1 }
            // Sustained churn: a change event every 100 ms while each GET takes 300 ms.
            repeat(30) {
                api.events.send(ApprovalsEvent.New(a.requestId))
                advanceTimeBy(100)
            }
            // The serial writer keeps applying GETs; the item must not wait for the burst to end.
            assertTrue("item visible mid-burst, calls=${api.listCalls}",
                vm.state.value.cards.any { it.item.requestId == a.requestId })
            assertTrue("GET count stayed inside the paced budget, was ${api.listCalls}", api.listCalls in 1..2)
        }
    }

    @Test fun snapshotAppliesWithoutAListGet() = runTest {
        val api = FakeApprovals()
        exercise(api) { repository, _, bridgeId, vm ->
            val a = item("a".repeat(32))
            api.listResult = emptyList()
            api.events.send(ApprovalsEvent.Snapshot(listOf(a)))
            awaitCards(vm, a.requestId)
            assertEquals("snapshot carried the items; no GET was needed", 0, api.listCalls)
        }
    }

    @Test fun failedGetRequeuesItselfAndRecovers() = runTest {
        val api = FakeApprovals()
        exercise(api) { repository, _, bridgeId, vm ->
            val a = item("a".repeat(32))
            val b = item("b".repeat(32))
            api.events.send(ApprovalsEvent.Snapshot(listOf(a)))
            awaitCards(vm, a.requestId)
            api.listFailures.addLast(java.io.IOException("offline"))
            api.listResult = listOf(a, b)
            api.events.send(ApprovalsEvent.New(b.requestId))
            // One failed GET, then the writer's own re-queue lands b without another event.
            repository.state.first {
                it[feedKey(bridgeId)]?.let { f -> !f.loading && f.items.map { i -> i.requestId } == listOf(a.requestId, b.requestId) } == true
            }
            assertEquals("one failed GET plus its queued retry", 2, api.listCalls)
            awaitCards(vm, a.requestId, b.requestId)
        }
    }

    @Test fun onePcsDiscoveryFlapLeavesTheOtherStreamAndGetAlone() = runTest {
        val apiA = FakeApprovals()
        val apiB = FakeApprovals()
        exerciseTwo(apiA, apiB) { repository, bots, a, b ->
            bots.value = BridgeBotsState(listOf(group(a.bridgeId), group(b.bridgeId)), loaded = true)
            apiA.opensFlow.first { it >= 1 }; apiB.opensFlow.first { it >= 1 }
            val itemA = item("a".repeat(32))
            val itemA2 = item("c".repeat(32))
            apiA.listDelayMs = 500
            apiA.listResults.addLast(listOf(itemA, itemA2))
            apiA.events.send(ApprovalsEvent.Snapshot(listOf(itemA)))
            repository.state.first { it[feedKey(a.bridgeId)]?.items?.isNotEmpty() == true }
            // A GET on PC A is in flight (the `new` coalesces into one list call).
            apiA.events.send(ApprovalsEvent.New(itemA2.requestId))
            advanceTimeBy(400)
            // PC B's profile stream flaps twice while A's GET is still running.
            bots.value = BridgeBotsState(listOf(group(a.bridgeId), group(b.bridgeId, error = HermesError.BridgeUnreachable)), loaded = true)
            advanceTimeBy(50)
            bots.value = BridgeBotsState(listOf(group(a.bridgeId), group(b.bridgeId)), loaded = true)
            // A's GET finishes undisturbed and applies; neither stream reopened.
            repository.state.first {
                it[feedKey(a.bridgeId)]?.let { f -> !f.loading && f.items.map { i -> i.requestId } == listOf(itemA.requestId, itemA2.requestId) } == true
            }
            assertEquals(1, apiA.opens)
            assertEquals(1, apiB.opens)
        }
    }

    @Test fun removedProfileOnOnePcDropsOnlyItsOwnFeed() = runTest {
        val apiA = FakeApprovals()
        val apiB = FakeApprovals()
        exerciseTwo(apiA, apiB) { repository, bots, a, b ->
            bots.value = BridgeBotsState(listOf(group(a.bridgeId), group(b.bridgeId)), loaded = true)
            apiA.opensFlow.first { it >= 1 }; apiB.opensFlow.first { it >= 1 }
            val itemB = item("b".repeat(32))
            apiB.events.send(ApprovalsEvent.Snapshot(listOf(itemB)))
            repository.state.first { it[feedKey(b.bridgeId)]?.items?.isNotEmpty() == true }
            // PC B's listing confirms the profile is gone: only B's feed and job end.
            bots.value = BridgeBotsState(listOf(group(a.bridgeId), group(b.bridgeId, profiles = emptyList())), loaded = true)
            repository.state.first { it[feedKey(b.bridgeId)] == null }
            assertNotNull(repository.state.value[feedKey(a.bridgeId)])
            assertEquals(1, apiA.opens)
            assertEquals(1, apiB.opens)
        }
    }

    @Test fun backgroundGetsStayInsideThePcRequestBudget() = runTest {
        // One PC, two profiles: both feeds draw on the same per-PC slot.
        val apiDefault = FakeApprovals()
        val apiWork = FakeApprovals()
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val saved = bridge()
        val (sc, transports) = clientsAndStore(listOf(saved))
        val (store, clients) = sc
        val apis = java.util.concurrent.ConcurrentHashMap<Pair<OkHttpClient, String>, FakeApprovals>()
        val client = clients.client(saved).client
        apis[client to "default"] = apiDefault
        apis[client to "work"] = apiWork
        val bots = MutableStateFlow(BridgeBotsState(
            listOf(group(saved.bridgeId, profiles = listOf(profile("default"), profile("work")))), loaded = true))
        val repository = ApprovalsRepository(store, clients, bots) { c, p -> apis.getValue(c to p) }
        val following = backgroundScope.launch { repository.follow() }
        try {
            runCurrent()
            apiDefault.opensFlow.first { it >= 1 }
            apiWork.opensFlow.first { it >= 1 }
            // A minute of events on both feeds: the per-PC slot holds the total under 30/min
            // (bridge budget is 60/min shared with answers, docs/bridge-approvals.md:127-129).
            repeat(600) {
                apiDefault.events.trySend(ApprovalsEvent.New("a".repeat(32)))
                apiWork.events.trySend(ApprovalsEvent.New("b".repeat(32)))
                advanceTimeBy(100)
            }
            val total = apiDefault.listCalls + apiWork.listCalls
            assertTrue("per-PC background traffic stayed <=30/min, was $total", total <= 30)
        } finally {
            following.cancel()
            transports.forEach { it.close() }
            Dispatchers.resetMain()
        }
    }

    @Test fun answerThroughTheRealRepositoryDisablesTheCardUntilTheSnapshot() = runTest {
        val api = FakeApprovals()
        exercise(api) { repository, _, bridgeId, vm ->
            val a = item("a".repeat(32))
            val b = item("b".repeat(32))
            api.listResult = listOf(a)
            api.events.send(ApprovalsEvent.Snapshot(listOf(a)))
            awaitCards(vm, a.requestId)
            // The post-answer refetch sees a different list — the answered request plus a new one —
            // so the stale GET really emits and the VM re-processes a list still containing it.
            api.listResults.addLast(listOf(a, b))
            vm.answer(a.requestId, "once")
            awaitCards(vm, a.requestId, b.requestId)
            assertEquals(listOf(a.requestId to "once"), api.answers)
            // The card for the answered request stays disabled and no second answer can leave.
            assertTrue(vm.state.value.cards.first { it.item.requestId == a.requestId }.sending)
            assertFalse(vm.state.value.cards.first { it.item.requestId == b.requestId }.sending)
            vm.answer(a.requestId, "deny")
            runCurrent()
            assertEquals(1, api.answers.size)
            api.listResults.addLast(emptyList())
            api.events.send(ApprovalsEvent.Resolved(a.requestId))
            awaitCards(vm)
        }
    }

    @Test fun terminalAnswerErrorThroughTheRealRepositoryClosesTheCardAndRefetches() = runTest {
        val api = FakeApprovals()
        exercise(api) { repository, _, bridgeId, vm ->
            val a = item("a".repeat(32))
            api.listResult = listOf(a)
            api.events.send(ApprovalsEvent.Snapshot(listOf(a)))
            awaitCards(vm, a.requestId)
            api.answerFailure = ApprovalsException(ApprovalsError.Conflict)
            api.listResults.addLast(emptyList())   // the refetch confirms the request is gone
            vm.answer(a.requestId, "once")
            vm.state.first { s -> s.cards.singleOrNull()?.error == ApprovalsError.Conflict }
            assertTrue(vm.state.value.cards.single().sending)
            awaitCards(vm)
        }
    }

    @Test fun restoredScreenForARemovedProfileShowsTheBotGone() = runTest {
        val api = FakeApprovals()
        // Process-death restore: discovery finishes listing without this profile; no feed exists.
        exercise(api, initialBots = { BridgeBotsState(listOf(group(it, profiles = emptyList())), loaded = true) }) {
            repository, _, _, vm ->
            vm.state.first { it.feedError == ApprovalsError.BotUnavailable && !it.loading }
            assertTrue(repository.state.value.isEmpty())
            assertEquals(0, api.opens)
        }
    }

    @Test fun restoredScreenAfterDiscoveryFailureShowsTheError() = runTest {
        val api = FakeApprovals()
        exercise(api, initialBots = {
            BridgeBotsState(listOf(group(it, error = HermesError.BridgeUnreachable, profiles = emptyList())), loaded = true)
        }) { repository, _, _, vm ->
            vm.state.first { it.feedError == ApprovalsError.Unreachable && !it.loading }
            assertTrue(repository.state.value.isEmpty())
            assertEquals(0, api.opens)
        }
    }

    @Test fun discoveryErrorKeepsTheFeedAndRetryStillWorks() = runTest {
        val api = FakeApprovals()
        exercise(api) { repository, bots, bridgeId, vm ->
            val a = item("a".repeat(32))
            api.listResult = listOf(a)
            api.events.send(ApprovalsEvent.Snapshot(listOf(a)))
            awaitCards(vm, a.requestId)

            // A transient profile-events failure annotates the feed but never restarts its stream.
            bots.value = BridgeBotsState(listOf(group(bridgeId, error = HermesError.BridgeUnreachable)), loaded = true)
            vm.state.first { it.feedError == ApprovalsError.Unreachable }
            assertNotNull(repository.state.value[feedKey(bridgeId)])
            assertEquals(listOf(a.requestId), vm.state.value.cards.map { it.item.requestId })
            advanceTimeBy(2_000)
            assertEquals("the follower job survived the discovery error", 1, api.opens)

            // Retry signals the follower, whose GET still works when approvals is reachable.
            repository.refresh(bridgeId, "default")
            vm.state.first { s ->
                s.feedError == null && s.cards.map { it.item.requestId } == listOf(a.requestId)
            }

            // Recovery plus a real `new` event keeps driving the same wiring.
            val b = item("b".repeat(32))
            api.listResult = listOf(a, b)
            bots.value = BridgeBotsState(listOf(group(bridgeId)), loaded = true)
            api.events.send(ApprovalsEvent.New(b.requestId))
            awaitCards(vm, a.requestId, b.requestId)
            assertEquals(1, api.opens)

            // Only a confirmed removal (profile gone in a successful listing) reports removal.
            bots.value = BridgeBotsState(listOf(group(bridgeId, profiles = emptyList())), loaded = true)
            vm.state.first { it.feedError == ApprovalsError.BotUnavailable }
        }
    }

    @Test fun recoveredDiscoveryClearsTheFeedErrorWithoutARefresh() = runTest {
        val api = FakeApprovals()
        exercise(api) { repository, bots, bridgeId, vm ->
            val a = item("a".repeat(32))
            api.listResult = listOf(a)
            api.events.send(ApprovalsEvent.Snapshot(listOf(a)))
            awaitCards(vm, a.requestId)

            // A profile-list failure annotates the live feed.
            bots.value = BridgeBotsState(listOf(group(bridgeId, error = HermesError.BridgeUnreachable)), loaded = true)
            vm.state.first { it.feedError == ApprovalsError.Unreachable }

            // Discovery recovering clears the annotation by itself: no refresh call, no stream
            // event, no GET — the feed just stops carrying the stale error.
            bots.value = BridgeBotsState(listOf(group(bridgeId)), loaded = true)
            withTimeout(10_000) {
                vm.state.first { s ->
                    s.feedError == null && s.cards.map { it.item.requestId } == listOf(a.requestId)
                }
            }
            assertEquals(1, api.opens)
            assertEquals(0, api.listCalls)
        }
    }
}
