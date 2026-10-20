package app.rasul

import androidx.lifecycle.ViewModelStore
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import app.rasul.data.Connection
import app.rasul.data.ConnectionStore
import app.rasul.data.ActiveTarget
import app.rasul.data.HermesClient
import app.rasul.data.Load
import app.rasul.data.SecretCipher
import app.rasul.bridge.BridgeBotsRepository
import app.rasul.bridge.BridgeClients
import app.rasul.bridge.BridgeProfile
import app.rasul.bridge.BridgeSelection
import app.rasul.bridge.BridgeStore
import app.rasul.bridge.BridgeTransport
import app.rasul.bridge.ProfileSnapshot
import app.rasul.bridge.ProfilesApi
import app.rasul.bridge.SavedBridge
import app.rasul.bridge.bridgeBotId
import app.rasul.jobs.JobsClient
import app.rasul.jobs.JobsViewModel
import app.rasul.sessions.SessionsClient
import app.rasul.sessions.SessionsViewModel
import app.rasul.ui.ActivePcHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
class ActivePcHolderTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var a: MockWebServer
    private lateinit var b: MockWebServer

    private val cipher = object : SecretCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(token: String) = token
    }

    private fun server(tag: String) = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(
                if (request.path.orEmpty().contains("jobs")) {
                    """{"jobs":[{"id":"j$tag","name":"Job$tag","enabled":false,"state":"paused"}]}"""
                } else {
                    """{"object":"list","data":[{"id":"s$tag","title":"Sess$tag","started_at":1760000000,"message_count":1}],"limit":50,"offset":0,"has_more":false}"""
                },
            )
        }
        start()
    }

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        a = server("A")
        b = server("B")
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        runCatching { a.shutdown() }
        runCatching { b.shutdown() }
    }

    private val ds: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
            tmp.root.resolve("h.preferences_pb")
        }
    }

    private fun scope() = CoroutineScope(Job() + Dispatchers.Unconfined)

    @Test fun readFailureThenRecoveryAfterRetry() {
        val s0 = ConnectionStore(ds, cipher)
        val idA = runBlocking { s0.save(Connection("", "A", "http://localhost:${a.port}/"), "ka") }
        val idB = runBlocking { s0.save(Connection("", "B", "http://localhost:${b.port}/"), "kb") }
        var reads = 0
        val flaky = object : DataStore<Preferences> by ds {
            override val data: Flow<Preferences> = flow {
                if (reads++ < 1) throw IOException("transient")
                ds.data.collect { emit(it) }
            }
        }
        val holder = ActivePcHolder(ConnectionStore(flaky, cipher), scope())
        assertEquals("transient", holder.state.value.readError)
        assertTrue(holder.state.value.pcs.isEmpty())

        holder.retry()
        waitFor { holder.state.value.readError == null && holder.state.value.pcs.size == 2 }
        assertNull(holder.state.value.readError)
        assertEquals(idA, holder.state.value.activeId)

        holder.selectPc(idB) // the picker works again
        waitFor { holder.state.value.activeId == idB }
    }

    @Test fun switchingPcFromSessionsReloadsJobsForBWithNoARows() {
        val s = ConnectionStore(ds, cipher)
        runBlocking { s.save(Connection("", "A", "http://localhost:${a.port}/"), "ka") }
        val idB = runBlocking { s.save(Connection("", "B", "http://localhost:${b.port}/"), "kb") }
        val holder = ActivePcHolder(s, scope())
        val sessions = SessionsViewModel(s, holder, SessionsClient(HermesClient()))
        val jobs = JobsViewModel(s, holder, JobsClient(HermesClient()))
        waitFor { (jobs.state.value as? Load.Ready)?.connection == "A" && sessions.state.value is Load.Ready }
        assertEquals("JobA", (jobs.state.value as Load.Ready).value.single().name)

        sessions.selectPc(idB) // switched from the Sessions tab; Jobs is the cached tab
        waitFor { (jobs.state.value as? Load.Ready)?.connection == "B" && (sessions.state.value as? Load.Ready)?.connection == "B" }
        assertEquals("JobB", (jobs.state.value as Load.Ready).value.single().name)
        assertEquals("SessB", (sessions.state.value as Load.Ready).value.single().title)
    }

    @Test fun switchingToLoadingBridgeClearsSessionAndJobRowsImmediately() = runBlocking {
        val connections = ConnectionStore(ds, cipher)
        connections.save(Connection("", "A", "http://localhost:${a.port}/"), "ka")
        val bridgeStore = BridgeStore(ds, cipher)
        val savedBridge = SavedBridge(
            "bridge-loading", "Studio PC", listOf("wss://example.invalid"),
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32)), "device", "key",
        )
        bridgeStore.save(savedBridge)
        val listStarted = CompletableDeferred<Unit>()
        val releaseProfiles = CompletableDeferred<Unit>()
        val pcScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val clients = BridgeClients(bridgeStore, pcScope, { _, urls, _ ->
            BridgeTransport(urls, ByteArray(32), null, newContext = { javax.net.ssl.SSLContext.getInstance("TLSv1.3") })
        }, {})
        val repository = BridgeBotsRepository(bridgeStore, clients) { object : ProfilesApi {
            override suspend fun list(): ProfileSnapshot {
                listStarted.complete(Unit)
                releaseProfiles.await()
                return ProfileSnapshot(1, listOf(app.rasul.bridge.BridgeProfile("default", "default", "Default", true, true)))
            }
            override fun events(): Flow<ProfileSnapshot> = flow { awaitCancellation() }
        } }
        val following = pcScope.launch { repository.follow() }
        val holder = ActivePcHolder(connections, pcScope, repository)
        val sessions = SessionsViewModel(connections, holder, SessionsClient(HermesClient()))
        val jobs = JobsViewModel(connections, holder, JobsClient(HermesClient()))
        try {
            withTimeout(5000) { listStarted.await() }
            waitFor { (sessions.state.value as? Load.Ready)?.connection == "A" && (jobs.state.value as? Load.Ready)?.connection == "A" }

            holder.selectBridge(savedBridge.bridgeId, "default")
            withTimeout(5000) {
                holder.state.first { it.activeId == bridgeBotId(savedBridge.bridgeId, "default") && it.loaded && !it.targetLoaded }
            }
            waitFor { sessions.state.value == Load.Loading && jobs.state.value == Load.Loading }

            assertEquals(Load.Loading, sessions.state.value)
            assertEquals(Load.Loading, jobs.state.value)
        } finally {
            releaseProfiles.cancel()
            following.cancelAndJoin()
            pcScope.cancel()
            pcScope.coroutineContext[Job]!!.join()
        }
    }

    @Test fun forgettingOnlyBridgeReturnsSessionsAndJobsToPairingEmptyState() = runBlocking {
        val pcScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataStore = PreferenceDataStoreFactory.create(scope = pcScope) {
            tmp.root.resolve("forget-only-bridge.preferences_pb")
        }
        val connections = ConnectionStore(dataStore, cipher)
        val bridgeStore = BridgeStore(dataStore, cipher)
        val savedBridge = SavedBridge(
            "only-bridge", "One PC", listOf("wss://example.invalid"),
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32)), "device", "key",
        )
        val transports = mutableListOf<BridgeTransport>()
        val clients = BridgeClients(bridgeStore, pcScope, { _, urls, _ ->
            BridgeTransport(urls, ByteArray(32), null, newContext = { javax.net.ssl.SSLContext.getInstance("TLSv1.3") }).also { transports.add(it) }
        }, {})
        val repository = BridgeBotsRepository(bridgeStore, clients) { object : ProfilesApi {
            override suspend fun list() = ProfileSnapshot(1, listOf(BridgeProfile("default", "default", "Default", true, true)))
            override fun events(): Flow<ProfileSnapshot> = flow { awaitCancellation() }
        } }
        val holder = ActivePcHolder(connections, pcScope, repository)
        var following: Job? = null
        val viewModels = ViewModelStore()
        try {
            bridgeStore.save(savedBridge)
            following = pcScope.launch { repository.follow() }
            assertEquals(ActiveTarget.None, connections.snapshots.first().target)
            withTimeout(5000) {
                holder.state.first {
                    it.activeId == bridgeBotId(savedBridge.bridgeId, "default") &&
                        it.target is ActiveTarget.Ready && it.bridgeSelection?.bridgeId == savedBridge.bridgeId
                }
            }

            val forgetting = launch(start = CoroutineStart.UNDISPATCHED) { repository.forget(savedBridge) }
            assertTrue(repository.state.value.groups.none { it.id == savedBridge.bridgeId })
            forgetting.join()

            assertNull(withTimeout(5000) { repository.activeSelection.first { it == null } })
            val picker = withTimeout(5000) {
                holder.state.first {
                    it.loaded && it.targetLoaded && it.activeId == null && it.target == ActiveTarget.None &&
                        it.bridgeSelection == null && it.bridgeGroups.isEmpty()
                }
            }
            assertTrue(picker.pcs.isEmpty())

            val sessions = SessionsViewModel(connections, holder, SessionsClient(HermesClient()))
            val jobs = JobsViewModel(connections, holder, JobsClient(HermesClient()))
            viewModels.put("sessions", sessions)
            viewModels.put("jobs", jobs)
            assertEquals(Load.NoConnection, withTimeout(5000) { sessions.state.first { it == Load.NoConnection } })
            assertEquals(Load.NoConnection, withTimeout(5000) { jobs.state.first { it == Load.NoConnection } })
        } finally {
            viewModels.clear()
            following?.cancelAndJoin()
            transports.forEach { it.close() }
            pcScope.cancel()
            pcScope.coroutineContext[Job]!!.join()
        }
    }

    @Test fun staleStoredPickResolvesToFirstPcInHolderState() {
        val s = ConnectionStore(ds, cipher)
        val idA = runBlocking { s.save(Connection("", "A", "http://localhost:${a.port}/"), "ka") }
        runBlocking { s.setActive("deleted-id") }
        val holder = ActivePcHolder(s, scope())
        waitFor { holder.state.value.loaded }
        assertEquals(idA, holder.state.value.activeId)
    }

    /** A bridge-bot pick written before the holder's first store emission must not be overridden by the
     *  auto-pick of the default bot: the user's pick is the later write only if the holder does not
     *  enqueue a default behind it. */
    @Test fun autoDefaultDoesNotOverrideAnEarlyPick() = runBlocking {
        val ds = FakeDataStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val connections = ConnectionStore(ds, cipher)
        val store = BridgeStore(ds, cipher)
        val saved = SavedBridge(
            "bridge-b", "Bridge B", listOf("wss://example.invalid"),
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32)), "device", "key",
        )
        val clients = BridgeClients(store, scope, { _, urls, _ ->
            BridgeTransport(urls, ByteArray(32), null, newContext = { javax.net.ssl.SSLContext.getInstance("TLSv1.3") })
        }, {})
        val releaseProfiles = CompletableDeferred<Unit>()
        val repository = BridgeBotsRepository(store, clients) { object : ProfilesApi {
            override suspend fun list(): ProfileSnapshot {
                releaseProfiles.await()
                return ProfileSnapshot(1, listOf(
                    BridgeProfile("default", "default", "Default", true, true),
                    BridgeProfile("alt", "alt", "Alt", true, false),
                ))
            }
            override fun events(): Flow<ProfileSnapshot> = flow { awaitCancellation() }
        } }
        val following = scope.launch { repository.follow() }
        try {
            store.save(saved)
            // The follower reads the list on IO; a holder created before it sees the group would treat the pick's bridge as removed.
            withTimeout(5000) { repository.state.first { state -> state.groups.any { it.id == saved.bridgeId } } }
            val holder = ActivePcHolder(connections, scope, repository)
            ds.holdEmissions = true
            holder.selectBridge(saved.bridgeId, "alt")
            ds.writes.first { it >= 2 }
            releaseProfiles.complete(Unit)
            withTimeoutOrNull(3000) { ds.writes.first { it >= 3 } }
            ds.holdEmissions = false
            ds.releaseQueued()
            assertEquals("alt", ds.current[stringPreferencesKey("active_bridge_profile")])
            val picker = withTimeout(5000) {
                holder.state.first { it.activeId == bridgeBotId(saved.bridgeId, "alt") && it.loaded }
            }
            assertEquals(BridgeSelection(saved.bridgeId, "alt"), picker.bridgeSelection)
        } finally {
            following.cancelAndJoin()
            scope.cancel()
            scope.coroutineContext[Job]!!.join()
        }
    }

    /** A pick whose store write lands but whose emission never reaches the holder (a first read racing
     *  the write can miss it) must still become and stay the active one. */
    @Test fun pickWhoseStoreEmissionIsMissedStaysActive() = runBlocking {
        val ds = FakeDataStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val connections = ConnectionStore(ds, cipher)
        val store = BridgeStore(ds, cipher)
        val saved = SavedBridge(
            "bridge-b", "Bridge B", listOf("wss://example.invalid"),
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32)), "device", "key",
        )
        val clients = BridgeClients(store, scope, { _, urls, _ ->
            BridgeTransport(urls, ByteArray(32), null, newContext = { javax.net.ssl.SSLContext.getInstance("TLSv1.3") })
        }, {})
        val releaseProfiles = CompletableDeferred<Unit>()
        val repository = BridgeBotsRepository(store, clients) { object : ProfilesApi {
            override suspend fun list(): ProfileSnapshot {
                releaseProfiles.await()
                return ProfileSnapshot(1, listOf(
                    BridgeProfile("default", "default", "Default", true, true),
                    BridgeProfile("alt", "alt", "Alt", true, false),
                ))
            }
            override fun events(): Flow<ProfileSnapshot> = flow { awaitCancellation() }
        } }
        val following = scope.launch { repository.follow() }
        try {
            store.save(saved)
            // The follower reads the list on IO; a holder created before it sees the group would treat the pick's bridge as removed.
            withTimeout(5000) { repository.state.first { state -> state.groups.any { it.id == saved.bridgeId } } }
            val holder = ActivePcHolder(connections, scope, repository)
            // Profiles are gated, so nothing else can write before the pick and consume the drop.
            ds.dropNextEmission = true
            holder.selectBridge(saved.bridgeId, "alt")
            ds.writes.first { it >= 2 }
            releaseProfiles.complete(Unit)
            val picker = withTimeoutOrNull(5000) {
                holder.state.first { it.activeId == bridgeBotId(saved.bridgeId, "alt") && it.loaded && it.targetLoaded }
            }
            assertNotNull(
                "pick never became active (persisted profile=" +
                    ds.current[stringPreferencesKey("active_bridge_profile")] + ")",
                picker,
            )
        } finally {
            following.cancelAndJoin()
            scope.cancel()
            scope.coroutineContext[Job]!!.join()
        }
    }

    /** With a selection already saved, a pick taken before the holder's first emission must survive when
     *  the store emission for it is missed and the first read is the stale saved selection. */
    @Test fun earlyPickOverSavedSelectionSurvivesMissedStoreEmission() = runBlocking {
        val ds = FakeDataStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val connections = ConnectionStore(ds, cipher)
        val store = BridgeStore(ds, cipher)
        val saved = SavedBridge(
            "bridge-b", "Bridge B", listOf("wss://example.invalid"),
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32)), "device", "key",
        )
        val clients = BridgeClients(store, scope, { _, urls, _ ->
            BridgeTransport(urls, ByteArray(32), null, newContext = { javax.net.ssl.SSLContext.getInstance("TLSv1.3") })
        }, {})
        val repository = BridgeBotsRepository(store, clients) { object : ProfilesApi {
            override suspend fun list() = ProfileSnapshot(1, listOf(
                BridgeProfile("default", "default", "Default", true, true),
                BridgeProfile("alt", "alt", "Alt", true, false),
            ))
            override fun events(): Flow<ProfileSnapshot> = flow { awaitCancellation() }
        } }
        val following = scope.launch { repository.follow() }
        try {
            store.save(saved)
            withTimeout(5000) { repository.state.first { state -> state.groups.any { it.id == saved.bridgeId } } }
            store.setActiveSelection(BridgeSelection(saved.bridgeId, "default"))
            val gate = CompletableDeferred<Unit>()
            ds.subscriptionGate = gate
            val holder = ActivePcHolder(connections, scope, repository)
            val written = ds.writes.value
            ds.dropNextEmission = true
            holder.selectBridge(saved.bridgeId, "alt")
            ds.writes.first { it > written }
            gate.complete(Unit)
            val picker = withTimeoutOrNull(5000) {
                holder.state.first { it.activeId == bridgeBotId(saved.bridgeId, "alt") && it.loaded && it.targetLoaded }
            }
            assertNotNull(
                "pick never became active (persisted profile=" +
                    ds.current[stringPreferencesKey("active_bridge_profile")] + ")",
                picker,
            )
            assertEquals(BridgeSelection(saved.bridgeId, "alt"), picker!!.bridgeSelection)
        } finally {
            following.cancelAndJoin()
            scope.cancel()
            scope.coroutineContext[Job]!!.join()
        }
    }

    private fun waitFor(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue("timed out", cond())
    }
}

/** In-memory DataStore stand-in: collectors replay the last emitted value; [holdEmissions] queues new
 *  values for a later [releaseQueued], and [dropNextEmission] lets a write land without any emission —
 *  the write-applied/collector-missed split a file DataStore can hit when a first read races a write. */
private class FakeDataStore : DataStore<Preferences> {
    private val emitted = MutableSharedFlow<Preferences>(replay = 1)
    private val queued = ArrayDeque<Preferences>()
    var holdEmissions = false
    var dropNextEmission = false
    var current: Preferences = emptyPreferences()
        private set
    val writes = MutableStateFlow(0)

    init { emitted.tryEmit(current) }

    /** While set, new collectors of [data] wait for it before seeing any value. */
    var subscriptionGate: CompletableDeferred<Unit>? = null

    override val data: Flow<Preferences> = flow {
        subscriptionGate?.await()
        emitAll(emitted)
    }

    private val writeLock = Mutex()

    /** Serialised like a real DataStore: a read-modify-write never sees a value another write is replacing. */
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = writeLock.withLock {
        val next = transform(current)
        if (next == current) return@withLock current // a real DataStore skips a write that changes nothing
        current = next
        when {
            dropNextEmission -> dropNextEmission = false
            holdEmissions -> queued.add(next)
            else -> emitted.emit(next)
        }
        writes.update { it + 1 }
        next
    }

    suspend fun releaseQueued() {
        while (queued.isNotEmpty()) emitted.emit(queued.removeFirst())
    }
}
