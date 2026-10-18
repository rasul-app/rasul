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

@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalsRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val cipher = object : SecretCipher {
        override fun encrypt(plain: String) = plain.reversed()
        override fun decrypt(token: String) = token.reversed()
    }
    private fun bridge() = SavedBridge(UUID.randomUUID().toString(), "Bridge", listOf("wss://example.invalid"),
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32)), UUID.randomUUID().toString(), UUID.randomUUID().toString())
    private fun item(id: String, expires: Double = 4_000_000_000.0) =
        ApprovalItemDto(id, "sk", "default", "shell", "cmd", "desc", true, false, expires)

    private class FakeApprovals : ApprovalsApi {
        val events = Channel<ApprovalsEvent>(Channel.UNLIMITED)
        var opens = 0
        var eventsFailure: Exception? = null
        var listResult: List<ApprovalItemDto> = emptyList()
        var listFailure: Exception? = null
        var listCalls = 0
        var opened2 = CompletableDeferred<Unit>()
        override suspend fun list(): List<ApprovalItemDto> {
            listCalls++
            listFailure?.let { throw it }
            return listResult
        }
        override fun events(): Flow<ApprovalsEvent> = flow {
            opens++
            if (opens == 2) opened2.complete(Unit)
            eventsFailure?.let { throw it }
            events.receiveAsFlow().collect { emit(it) }
        }
        override suspend fun answer(requestId: String, choice: String) = ApprovalAnsweredDto(requestId, "answered")
    }

    private suspend fun TestScope.exercise(
        api: ApprovalsApi,
        block: suspend TestScope.(ApprovalsRepository, String) -> Unit,
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
            BridgeGroup(saved.bridgeId, "Bridge", listOf(BridgeProfile("default", "default", "Research", true, true)), loading = false))))
        val repository = ApprovalsRepository(store, clients, bots) { _, _ -> api }
        val following = backgroundScope.launch { repository.follow() }
        try { block(repository, saved.bridgeId) }
        finally {
            following.cancel()
            transports.forEach { it.close() }
        }
    }

    private suspend fun feed(
        repository: ApprovalsRepository, bridgeId: String,
        until: (ApprovalsRepository.Feed) -> Boolean = { it.items.isNotEmpty() || it.error != null || it.unsupported },
    ) = repository.state.first {
        it[bridgeBotId(bridgeId, "default")]?.let(until) == true
    }.getValue(bridgeBotId(bridgeId, "default"))

    @Test fun snapshotNewAndResolvedReconcileThePendingList() = runTest {
        val api = FakeApprovals()
        exercise(api) { repository, bridgeId ->
            val a = item("a".repeat(32)); val b = item("b".repeat(32))
            api.listResult = listOf(a)
            api.events.send(ApprovalsEvent.Snapshot(listOf(a)))
            val first = feed(repository, bridgeId)
            assertEquals(listOf(a), first.items)
            assertFalse(first.loading)
            // `new` carries only ids, so the follower refetches the list (docs/bridge-approvals.md:88-100).
            api.listResult = listOf(a, b)
            api.events.send(ApprovalsEvent.New(b.requestId))
            advanceUntilIdle()
            assertEquals(listOf(a, b), feed(repository, bridgeId) { it.items.size == 2 }.items)
            assertTrue(api.listCalls >= 1)
            // `resolved` removes nothing locally; only the refetched snapshot drops the item.
            api.listResult = listOf(b)
            api.events.send(ApprovalsEvent.Resolved(a.requestId))
            runCurrent()
            assertEquals(listOf(a, b), repository.state.value[bridgeBotId(bridgeId, "default")]!!.items)
            advanceUntilIdle()
            assertEquals(listOf(b), feed(repository, bridgeId) { it.items.size == 1 }.items)
        }
    }

    @Test fun refreshWhileTheStreamIsConnectedRunsAFreshGet() = runTest {
        val api = FakeApprovals()
        exercise(api) { repository, bridgeId ->
            val a = item("a".repeat(32)); val b = item("b".repeat(32))
            api.listResult = listOf(a)
            api.events.send(ApprovalsEvent.Snapshot(listOf(a)))
            feed(repository, bridgeId)
            // A failed refetch after `new` only sets the error; the stream stays connected.
            api.listFailure = java.io.IOException("offline")
            api.events.send(ApprovalsEvent.New(b.requestId))
            assertEquals(ApprovalsError.Unreachable, feed(repository, bridgeId) { it.error != null }.error)
            assertEquals(listOf(a), repository.state.value[bridgeBotId(bridgeId, "default")]!!.items)
            // Retry runs a fresh GET without reopening the stream.
            api.listFailure = null
            api.listResult = listOf(a, b)
            repository.refresh(bridgeId, "default")
            assertEquals(listOf(a, b), feed(repository, bridgeId) { it.items.size == 2 && it.error == null }.items)
            assertEquals(1, api.opens)
        }
    }

    @Test fun rateLimitedRefetchShowsTheContractErrorAndRetryStillWorks() = runTest {
        val api = FakeApprovals()
        exercise(api) { repository, bridgeId ->
            val a = item("a".repeat(32))
            api.listResult = listOf(a)
            api.events.send(ApprovalsEvent.Snapshot(listOf(a)))
            feed(repository, bridgeId)
            api.listFailure = ApprovalsException(ApprovalsError.RateLimited)
            api.events.send(ApprovalsEvent.New("b".repeat(32)))
            assertEquals(ApprovalsError.RateLimited, feed(repository, bridgeId) { it.error != null }.error)
            api.listFailure = null
            repository.refresh(bridgeId, "default")
            advanceUntilIdle()
            assertNull(feed(repository, bridgeId) { it.error == null }.error)
        }
    }

    @Test fun droppedStreamReconnectsAndSnapshotReplacesState() = runTest {
        val inner = FakeApprovals()
        var dropped = false
        val flaky = object : ApprovalsApi by inner {
            override fun events(): Flow<ApprovalsEvent> = flow {
                inner.opens++
                if (!dropped) { dropped = true; throw java.io.IOException("dropped") }
                inner.events.receiveAsFlow().collect { emit(it) }
            }
        }
        exercise(flaky) { repository, bridgeId ->
            val a = item("a".repeat(32))
            inner.listResult = listOf(a)
            inner.events.send(ApprovalsEvent.Snapshot(listOf(a)))
            val feed = feed(repository, bridgeId) { it.items.isNotEmpty() }
            assertEquals(listOf(a), feed.items)
            assertNull(feed.error)
            assertEquals(2, inner.opens)
            // A later snapshot replaces the whole list without another GET.
            inner.listResult = emptyList()
            inner.events.send(ApprovalsEvent.Snapshot(emptyList()))
            assertEquals(emptyList<ApprovalItemDto>(), feed(repository, bridgeId) { !it.loading && it.items.isEmpty() }.items)
        }
    }

    @Test fun unsupportedMarksTheFeedAndRefreshReopensIt() = runTest {
        val api = FakeApprovals().apply { eventsFailure = ApprovalsException(ApprovalsError.Unsupported) }
        exercise(api) { repository, bridgeId ->
            val failed = feed(repository, bridgeId)
            assertTrue(failed.unsupported)
            assertEquals(ApprovalsError.Unsupported, failed.error)
            assertEquals(1, api.opens)
            api.eventsFailure = null
            repository.refresh(bridgeId, "default")
            // The follower crosses real DataStore/SSL threads; await the collector, not idle time.
            api.opened2.await()
            assertEquals(2, api.opens)
        }
    }

    @Test fun unreachableBridgeSurfacesAnErrorAndRetryWakesTheFollower() = runTest {
        val api = FakeApprovals().apply { eventsFailure = java.io.IOException("offline") }
        exercise(api) { repository, bridgeId ->
            assertEquals(ApprovalsError.Unreachable, feed(repository, bridgeId).error)
            // The follower is backing off; the screen's Retry emits a refresh that reopens the stream.
            api.eventsFailure = null
            repository.refresh(bridgeId, "default")
            api.events.send(ApprovalsEvent.Snapshot(emptyList()))
            feed(repository, bridgeId) { !it.loading && it.error == null }
            assertTrue(api.opens >= 2)
        }
    }

    @Test fun notReadyProfilesNeverGetAFeed() = runTest {
        val ds = PreferenceDataStoreFactory.create(scope = backgroundScope) { tmp.root.resolve(UUID.randomUUID().toString() + ".preferences_pb") }
        val store = BridgeStore(ds, cipher)
        val saved = bridge()
        store.save(saved)
        val transports = mutableListOf<BridgeTransport>()
        val clients = BridgeClients(store, backgroundScope, { _, urls, _ ->
            BridgeTransport(urls, ByteArray(32), null, newContext = { javax.net.ssl.SSLContext.getInstance("TLSv1.3") }).also { transports.add(it) }
        }, {})
        val api = FakeApprovals()
        // The group is still loading / its only profile is not ready: no approvals stream opens.
        val bots = MutableStateFlow(BridgeBotsState(listOf(
            BridgeGroup(saved.bridgeId, "Bridge", listOf(BridgeProfile("default", "default", "Research", false, true)), loading = false))))
        val repository = ApprovalsRepository(store, clients, bots) { _, _ -> api }
        val following = backgroundScope.launch { repository.follow() }
        try {
            advanceUntilIdle()
            assertEquals(0, api.opens)
            assertTrue(repository.state.value.isEmpty())
        } finally {
            following.cancel()
            transports.forEach { it.close() }
        }
    }
}
