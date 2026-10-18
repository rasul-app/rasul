package app.rasul

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.rasul.bridge.*
import app.rasul.data.SecretCipher
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** One approvals stream per bridge (bridge-wide routes), split into per-bot feeds; older bridges fall back. */
@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalsBridgeWideTest {
    @get:Rule val tmp = TemporaryFolder()
    private val cipher = object : SecretCipher {
        override fun encrypt(plain: String) = plain.reversed()
        override fun decrypt(token: String) = token.reversed()
    }
    private fun bridge() = SavedBridge(UUID.randomUUID().toString(), "Bridge", listOf("wss://example.invalid"),
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32)), UUID.randomUUID().toString(), UUID.randomUUID().toString())
    private fun item(id: Char, profile: String) =
        ApprovalItemDto(id.toString().repeat(32), "sk", profile, "shell", "cmd", "desc", true, false, 4_000_000_000.0)

    private class FakeBridgeFeed : ApprovalsFeedApi {
        val events = Channel<ApprovalsEvent>(Channel.UNLIMITED)
        val opens = AtomicInteger()
        val lists = AtomicInteger()
        @Volatile var eventsFailure: Exception? = null
        @Volatile var listResult: List<ApprovalItemDto> = emptyList()
        /** When set, [list] suspends on it after being counted (a request in flight). */
        @Volatile var listGate: CompletableDeferred<Unit>? = null
        @Volatile var listFailure: Exception? = null
        override suspend fun list(): List<ApprovalItemDto> {
            lists.incrementAndGet()
            listGate?.await()
            listFailure?.let { throw it }
            return listResult
        }
        override fun events(): Flow<ApprovalsEvent> = flow {
            opens.incrementAndGet()
            eventsFailure?.let { throw it }
            events.receiveAsFlow().collect { emit(it) }
        }
    }

    private class FakeProfileApi(val profile: String, val perProfileOpens: AtomicInteger, val items: List<ApprovalItemDto>) : ApprovalsApi {
        override suspend fun list() = items
        override fun events(): Flow<ApprovalsEvent> = flow {
            perProfileOpens.incrementAndGet()
            emit(ApprovalsEvent.Snapshot(items))
            awaitCancellation()
        }
        override suspend fun answer(requestId: String, choice: String) = ApprovalAnsweredDto(requestId, "answered")
    }

    private class Env(
        val repository: ApprovalsRepository, val bridgeId: String, val feed: FakeBridgeFeed, val perProfileOpens: AtomicInteger,
        private val bots: MutableStateFlow<BridgeBotsState>,
    ) {
        fun setProfiles(profiles: List<String>) {
            bots.value = BridgeBotsState(listOf(
                BridgeGroup(bridgeId, "Bridge", profiles.map { BridgeProfile(it, it, it, true, true) }, loading = false)))
        }
        fun removeBridge() { bots.value = BridgeBotsState(emptyList()) }
        fun keys() = repository.state.value.keys
    }

    private suspend fun TestScope.exercise(
        profiles: List<String>,
        feed: FakeBridgeFeed,
        perProfileItems: Map<String, List<ApprovalItemDto>> = emptyMap(),
        block: suspend TestScope.(Env) -> Unit,
    ) {
        val ds = PreferenceDataStoreFactory.create(scope = backgroundScope) { tmp.root.resolve(UUID.randomUUID().toString() + ".preferences_pb") }
        val store = BridgeStore(ds, cipher)
        val saved = bridge()
        store.save(saved)
        val transports = mutableListOf<BridgeTransport>()
        val clients = BridgeClients(store, backgroundScope, { _, urls, _ ->
            BridgeTransport(urls, ByteArray(32), null, newContext = { javax.net.ssl.SSLContext.getInstance("TLSv1.3") }).also { transports.add(it) }
        }, {})
        val bots = MutableStateFlow(BridgeBotsState(listOf(
            BridgeGroup(saved.bridgeId, "Bridge", profiles.map { BridgeProfile(it, it, it, true, true) }, loading = false))))
        val perProfileOpens = AtomicInteger()
        val perProfile = ConcurrentHashMap<String, FakeProfileApi>()
        val repository = ApprovalsRepository(store, clients, bots, { _ -> feed }) { _, profile ->
            perProfile.getOrPut(profile) { FakeProfileApi(profile, perProfileOpens, perProfileItems[profile].orEmpty()) }
        }
        val following = backgroundScope.launch { repository.follow() }
        try { block(Env(repository, saved.bridgeId, feed, perProfileOpens, bots)) }
        finally {
            following.cancel()
            transports.forEach { it.close() }
        }
    }

    private suspend fun Env.feedOf(
        profile: String,
        until: (ApprovalsRepository.Feed) -> Boolean = { !it.loading },
    ) = repository.state.first { it[bridgeBotId(bridgeId, profile)]?.let(until) == true }.getValue(bridgeBotId(bridgeId, profile))

    @Test fun bridgeWideSnapshotIsSplitIntoTheBotFeeds() = runTest {
        val feed = FakeBridgeFeed()
        exercise(listOf("alpha", "beta"), feed) { env ->
            val a = item('a', "alpha"); val b = item('b', "beta"); val c = item('c', "alpha")
            feed.events.send(ApprovalsEvent.Snapshot(listOf(a, b, c)))
            assertEquals(listOf(a, c), env.feedOf("alpha").items)
            assertEquals(listOf(b), env.feedOf("beta").items)
            assertEquals(0, env.perProfileOpens.get())
        }
    }

    @Test fun anEventForOneProfileOnlyTouchesThatProfilesFeed() = runTest {
        val feed = FakeBridgeFeed()
        exercise(listOf("alpha", "beta"), feed) { env ->
            val a1 = item('a', "alpha"); val a2 = item('c', "alpha"); val b1 = item('b', "beta"); val b2 = item('d', "beta")
            feed.events.send(ApprovalsEvent.Snapshot(listOf(a1, b1)))
            env.feedOf("alpha"); env.feedOf("beta")
            // pending for alpha: the GET returns beta's newer item too, but beta's feed is not rewritten.
            feed.listResult = listOf(a1, a2, b1, b2)
            feed.events.send(ApprovalsEvent.New(a2.requestId, "alpha"))
            advanceUntilIdle()
            assertEquals(listOf(a1, a2), env.feedOf("alpha") { it.items.size == 2 }.items)
            assertEquals(listOf(b1), env.repository.state.value.getValue(bridgeBotId(env.bridgeId, "beta")).items)
            // resolved for alpha
            feed.listResult = listOf(a2, b1, b2)
            feed.events.send(ApprovalsEvent.Resolved(a1.requestId, "alpha"))
            advanceUntilIdle()
            assertEquals(listOf(a2), env.feedOf("alpha") { it.items.size == 1 }.items)
            assertEquals(listOf(b1), env.repository.state.value.getValue(bridgeBotId(env.bridgeId, "beta")).items)
            assertEquals(1, feed.opens.get())
        }
    }

    @Test fun itemsForProfilesOutsideTheBotsListAreIgnored() = runTest {
        val feed = FakeBridgeFeed()
        exercise(listOf("alpha"), feed) { env ->
            val a = item('a', "alpha"); val stranger = item('b', "stranger")
            feed.events.send(ApprovalsEvent.Snapshot(listOf(a, stranger)))
            assertEquals(listOf(a), env.feedOf("alpha").items)
            assertEquals(setOf(bridgeBotId(env.bridgeId, "alpha")), env.repository.state.value.keys)
        }
    }

    @Test fun a404OnTheBridgeWideRouteFallsBackToPerProfileStreamsAndStaysThere() = runTest {
        val feed = FakeBridgeFeed().apply { eventsFailure = ApprovalsException(ApprovalsError.Unsupported) }
        val items = mapOf("alpha" to listOf(item('a', "alpha")), "beta" to listOf(item('b', "beta")), "gamma" to listOf(item('c', "gamma")))
        exercise(listOf("alpha", "beta", "gamma"), feed, items) { env ->
            for ((profile, list) in items) assertEquals(list, env.feedOf(profile) { it.items.isNotEmpty() }.items)
            assertEquals(3, env.perProfileOpens.get())
            assertEquals(1, feed.opens.get())
            // Retry wakes the per-profile followers; the bridge-wide route is not probed again.
            env.repository.refresh(env.bridgeId, "alpha")
            advanceUntilIdle()
            assertEquals(1, feed.opens.get())
            assertEquals(3, env.perProfileOpens.get())
        }
    }

    @Test fun a404ReportedAsNotFoundAlsoFallsBack() = runTest {
        val feed = FakeBridgeFeed().apply { eventsFailure = ApprovalsException(ApprovalsError.NotFound) }
        val a = item('a', "alpha")
        exercise(listOf("alpha"), feed, mapOf("alpha" to listOf(a))) { env ->
            assertEquals(listOf(a), env.feedOf("alpha") { it.items.isNotEmpty() }.items)
            assertEquals(1, env.perProfileOpens.get())
        }
    }

    @Test fun aTransportErrorBacksOffOnTheBridgeWideStreamAndRefreshWakesIt() = runTest {
        val feed = FakeBridgeFeed().apply { eventsFailure = java.io.IOException("offline") }
        exercise(listOf("alpha", "beta"), feed) { env ->
            assertEquals(ApprovalsError.Unreachable, env.feedOf("alpha") { it.error != null }.error)
            assertEquals(ApprovalsError.Unreachable, env.feedOf("beta") { it.error != null }.error)
            assertEquals(0, env.perProfileOpens.get())
            // The follower is backing off; Retry on one bot reopens the one bridge-wide stream.
            feed.eventsFailure = null
            env.repository.refresh(env.bridgeId, "beta")
            feed.events.send(ApprovalsEvent.Snapshot(emptyList()))
            env.feedOf("alpha") { !it.loading && it.error == null }
            env.feedOf("beta") { !it.loading && it.error == null }
            assertTrue(feed.opens.get() >= 2)
            assertEquals(0, env.perProfileOpens.get())
        }
    }

    @Test fun threeBotsOnTheNewBridgeHoldExactlyOneOpenStream() = runTest {
        val feed = FakeBridgeFeed()
        exercise(listOf("alpha", "beta", "gamma"), feed) { env ->
            feed.events.send(ApprovalsEvent.Snapshot(emptyList()))
            for (p in listOf("alpha", "beta", "gamma")) env.feedOf(p)
            advanceUntilIdle()
            assertEquals(1, feed.opens.get())
            assertEquals(0, env.perProfileOpens.get())
        }
    }

    @Test fun aBotRemovedWhileTheListIsInFlightGetsNoFeedBack() = runTest {
        val feed = FakeBridgeFeed()
        exercise(listOf("alpha", "beta"), feed) { env ->
            feed.events.send(ApprovalsEvent.Snapshot(emptyList()))
            env.feedOf("alpha"); env.feedOf("beta")
            val gate = CompletableDeferred<Unit>().also { feed.listGate = it }
            env.repository.refresh(env.bridgeId, "alpha")
            delay(1_000)
            assertEquals(1, feed.lists.get())
            env.setProfiles(listOf("alpha"))
            delay(1_000)
            assertEquals(setOf(bridgeBotId(env.bridgeId, "alpha")), env.keys())
            feed.listResult = listOf(item('a', "alpha"), item('b', "beta"))
            gate.complete(Unit)
            delay(1_000)
            assertEquals(setOf(bridgeBotId(env.bridgeId, "alpha")), env.keys())
            assertEquals(listOf(item('a', "alpha")), env.feedOf("alpha").items)
        }
    }

    @Test fun aBotRemovedWhileTheListIsInFlightGetsNoErrorFeedEither() = runTest {
        val feed = FakeBridgeFeed()
        exercise(listOf("alpha", "beta"), feed) { env ->
            feed.events.send(ApprovalsEvent.Snapshot(emptyList()))
            env.feedOf("alpha"); env.feedOf("beta")
            val gate = CompletableDeferred<Unit>().also { feed.listGate = it }
            env.repository.refresh(env.bridgeId, "alpha")
            delay(1_000)
            env.setProfiles(listOf("alpha"))
            delay(1_000)
            feed.listFailure = java.io.IOException("offline")
            gate.complete(Unit)
            delay(1_000)
            assertEquals(setOf(bridgeBotId(env.bridgeId, "alpha")), env.keys())
        }
    }

    @Test fun anEventForAProfileOutsideTheBotsListMakesNoListRequest() = runTest {
        val feed = FakeBridgeFeed()
        exercise(listOf("alpha"), feed) { env ->
            feed.events.send(ApprovalsEvent.Snapshot(emptyList()))
            env.feedOf("alpha")
            feed.events.send(ApprovalsEvent.New(item('s', "stranger").requestId, "stranger"))
            feed.events.send(ApprovalsEvent.Resolved(item('t', "stranger").requestId, "stranger"))
            delay(1_000)
            assertEquals(0, feed.lists.get())
            assertEquals(setOf(bridgeBotId(env.bridgeId, "alpha")), env.keys())
        }
    }

    @Test fun theFallbackIsForgottenWhenTheBridgeLeavesTheWantedSet() = runTest {
        val feed = FakeBridgeFeed().apply { eventsFailure = ApprovalsException(ApprovalsError.Unsupported) }
        val a = item('a', "alpha")
        exercise(listOf("alpha"), feed, mapOf("alpha" to listOf(a))) { env ->
            assertEquals(listOf(a), env.feedOf("alpha") { it.items.isNotEmpty() }.items)
            assertEquals(1, feed.opens.get())
            assertEquals(1, env.perProfileOpens.get())
            env.removeBridge()
            delay(1_000)
            assertTrue(env.keys().isEmpty())
            feed.eventsFailure = null
            env.setProfiles(listOf("alpha"))
            feed.events.send(ApprovalsEvent.Snapshot(emptyList()))
            env.feedOf("alpha")
            assertEquals(2, feed.opens.get())
            assertEquals(1, env.perProfileOpens.get())
        }
    }

    @Test fun aBotAddedOnTheBridgeWideStreamGetsAFeedWithoutAnotherStream() = runTest {
        val feed = FakeBridgeFeed()
        exercise(listOf("alpha"), feed) { env ->
            feed.events.send(ApprovalsEvent.Snapshot(emptyList()))
            env.feedOf("alpha")
            val b = item('b', "beta")
            feed.listResult = listOf(b)
            env.setProfiles(listOf("alpha", "beta"))
            delay(1_000)
            assertEquals(listOf(b), env.feedOf("beta") { it.items.isNotEmpty() }.items)
            assertEquals(1, feed.opens.get())
            assertEquals(0, env.perProfileOpens.get())
        }
    }

    @Test fun aBotRemovedOnTheBridgeWideStreamLosesItsFeedAndKeepsTheOneStream() = runTest {
        val feed = FakeBridgeFeed()
        exercise(listOf("alpha", "beta"), feed) { env ->
            feed.events.send(ApprovalsEvent.Snapshot(listOf(item('a', "alpha"), item('b', "beta"))))
            env.feedOf("alpha"); env.feedOf("beta")
            env.setProfiles(listOf("alpha"))
            delay(1_000)
            assertEquals(setOf(bridgeBotId(env.bridgeId, "alpha")), env.keys())
            assertEquals(1, feed.opens.get())
            assertEquals(0, env.perProfileOpens.get())
        }
    }
}
