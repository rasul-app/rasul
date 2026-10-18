package app.rasul

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.rasul.bridge.*
import app.rasul.data.*
import app.rasul.ui.ActivePcHolder
import app.rasul.ui.pendingLoad
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64
import java.util.UUID

class BridgeBotsRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val cipher = object : SecretCipher {
        override fun encrypt(plain: String) = plain.reversed()
        override fun decrypt(token: String) = token.reversed()
    }
    private fun bridge() = SavedBridge(UUID.randomUUID().toString(), "Bridge", listOf("wss://example.invalid"),
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32)), UUID.randomUUID().toString(), UUID.randomUUID().toString())
    private fun profile(id: String = "default", ready: Boolean = true) = BridgeProfile(id, id, id, ready, id == "default")

    @Test fun offlineSavedSelectionStaysInSwitcherAlongsideWorkingDirectPc() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ds = PreferenceDataStoreFactory.create(scope = scope) { tmp.root.resolve("offline.preferences_pb") }
        val bridgeStore = BridgeStore(ds, cipher)
        val connections = ConnectionStore(ds, cipher)
        val directId = connections.save(Connection("", "Direct PC", "http://example.invalid"), "token")
        connections.setActive(directId)
        val savedBridge = bridge()
        bridgeStore.save(savedBridge)
        bridgeStore.setActiveSelection(BridgeSelection(savedBridge.bridgeId, "default"))
        val transports = mutableListOf<BridgeTransport>()
        val clients = BridgeClients(bridgeStore, scope, { _, urls, _ ->
            BridgeTransport(urls, ByteArray(32), null, newContext = { javax.net.ssl.SSLContext.getInstance("TLSv1.3") }).also { transports.add(it) }
        }, {})
        val repository = BridgeBotsRepository(bridgeStore, clients) { object : ProfilesApi {
            override suspend fun list(): ProfileSnapshot = throw ProfileStreamException(HermesError.BridgeUnreachable)
            override fun events(): Flow<ProfileSnapshot> = flow { awaitCancellation() }
        } }
        val holder = ActivePcHolder(connections, scope, repository)
        var following: Job? = null
        try {
            following = launch { repository.follow() }
            val picker = withTimeout(5000) {
                holder.state.first {
                    it.activeId == bridgeBotId(savedBridge.bridgeId, "default") &&
                        it.targetLoaded && it.bridgeNotice == app.rasul.ui.PcBridgeNotice.Unreachable(savedBridge.name)
                }
            }

            assertTrue(picker.pcs.any { it.id == directId })
            assertTrue(picker.pcs.any { it.id == bridgeBotId(savedBridge.bridgeId, "default") })
            assertEquals(Load.BridgeUnreachable(savedBridge.name), picker.pendingLoad())

            holder.selectPc(bridgeBotId(savedBridge.bridgeId, "default"))
            delay(100)
            assertEquals(directId, connections.active()?.id)
            assertEquals(BridgeSelection(savedBridge.bridgeId, "default"), repository.activeSelection.first())
        } finally {
            following?.cancelAndJoin()
            transports.forEach { it.close() }
            scope.cancel()
            scope.coroutineContext[Job]!!.join()
        }
    }

    @Test fun pairedBridgeWithNoReadyProfilesDoesNotBecomeNoConnection() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ds = PreferenceDataStoreFactory.create(scope = scope) { tmp.root.resolve("empty-profiles.preferences_pb") }
        val bridgeStore = BridgeStore(ds, cipher)
        val savedBridge = bridge()
        bridgeStore.save(savedBridge)
        val transports = mutableListOf<BridgeTransport>()
        val clients = BridgeClients(bridgeStore, scope, { _, urls, _ ->
            BridgeTransport(urls, ByteArray(32), null, newContext = { javax.net.ssl.SSLContext.getInstance("TLSv1.3") }).also { transports.add(it) }
        }, {})
        val repository = BridgeBotsRepository(bridgeStore, clients) { object : ProfilesApi {
            override suspend fun list() = ProfileSnapshot(1, emptyList())
            override fun events(): Flow<ProfileSnapshot> = flow { awaitCancellation() }
        } }
        val holder = ActivePcHolder(ConnectionStore(ds, cipher), scope, repository)
        var following: Job? = null
        try {
            following = launch { repository.follow() }
            val picker = withTimeout(5000) { holder.state.first { it.bridgeNotice is app.rasul.ui.PcBridgeNotice.NoReadyBots } }

            assertEquals(ActiveTarget.None, picker.target)
            assertEquals(Load.BridgeNoReadyBots(savedBridge.name), picker.pendingLoad())
        } finally {
            following?.cancelAndJoin()
            transports.forEach { it.close() }
            scope.cancel()
            scope.coroutineContext[Job]!!.join()
        }
    }

    @Test fun selectedNonDefaultProfileRestoresWithFreshStoreAndRepositoryInstances() = runBlocking {
        val file = tmp.root.resolve("fresh-instances.preferences_pb")
        val savedBridge = bridge()
        val profileList = ProfileSnapshot(1, listOf(profile(), profile("alternate")))
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val firstDs = PreferenceDataStoreFactory.create(scope = firstScope) { file }
        val firstStore = BridgeStore(firstDs, cipher)
        val firstTransports = mutableListOf<BridgeTransport>()
        val firstClients = BridgeClients(firstStore, firstScope, { _, urls, _ ->
            BridgeTransport(urls, ByteArray(32), null, newContext = { javax.net.ssl.SSLContext.getInstance("TLSv1.3") }).also { firstTransports.add(it) }
        }, {})
        val firstRepository = BridgeBotsRepository(firstStore, firstClients) { object : ProfilesApi {
            override suspend fun list() = profileList
            override fun events(): Flow<ProfileSnapshot> = flow { awaitCancellation() }
        } }
        var firstFollowing: Job? = null
        var secondScope: CoroutineScope? = null
        var secondFollowing: Job? = null
        val secondTransports = mutableListOf<BridgeTransport>()
        try {
            firstStore.save(savedBridge)
            firstFollowing = launch { firstRepository.follow() }
            val firstHolder = ActivePcHolder(ConnectionStore(firstDs, cipher), firstScope, firstRepository)
            withTimeout(5000) {
                firstHolder.state.first { it.target is ActiveTarget.Ready && it.activeId == bridgeBotId(savedBridge.bridgeId, "default") }
            }
            firstHolder.selectBridge(savedBridge.bridgeId, "alternate")
            withTimeout(5000) {
                firstHolder.state.first { it.target is ActiveTarget.Ready && it.activeId == bridgeBotId(savedBridge.bridgeId, "alternate") }
            }

            firstFollowing.cancelAndJoin()
            firstFollowing = null
            firstTransports.forEach { it.close() }
            firstTransports.clear()
            firstScope.cancel()
            firstScope.coroutineContext[Job]!!.join()

            secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val secondDs = PreferenceDataStoreFactory.create(scope = secondScope!!) { file }
            val secondStore = BridgeStore(secondDs, cipher)
            val secondClients = BridgeClients(secondStore, secondScope!!, { _, urls, _ ->
                BridgeTransport(urls, ByteArray(32), null, newContext = { javax.net.ssl.SSLContext.getInstance("TLSv1.3") }).also { secondTransports.add(it) }
            }, {})
            val secondRepository = BridgeBotsRepository(secondStore, secondClients) { object : ProfilesApi {
                override suspend fun list() = profileList
                override fun events(): Flow<ProfileSnapshot> = flow { awaitCancellation() }
            } }
            assertNotSame(firstStore, secondStore)
            assertNotSame(firstRepository, secondRepository)
            secondFollowing = launch { secondRepository.follow() }
            val restored = ActivePcHolder(ConnectionStore(secondDs, cipher), secondScope!!, secondRepository).state.first {
                it.target is ActiveTarget.Ready && it.activeId == bridgeBotId(savedBridge.bridgeId, "alternate")
            }

            assertEquals("alternate", (restored.target as ActiveTarget.Ready).target.bridge?.profileId)
            assertEquals(BridgeSelection(savedBridge.bridgeId, "alternate"), withTimeout(5000) { secondRepository.activeSelection.first { it != null } })
        } finally {
            firstFollowing?.cancelAndJoin()
            secondFollowing?.cancelAndJoin()
            firstTransports.forEach { it.close() }
            secondTransports.forEach { it.close() }
            firstScope.cancel()
            firstScope.coroutineContext[Job]!!.join()
            secondScope?.cancel()
            secondScope?.coroutineContext?.get(Job)?.join()
        }
    }

    @Test fun groupedListReplacesAddedRemovedAndNotReadySnapshotsAndStopsOnCancel() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ds = PreferenceDataStoreFactory.create(scope = scope) { tmp.root.resolve("bots.preferences_pb") }
        val store = BridgeStore(ds, cipher)
        val transports = mutableListOf<BridgeTransport>()
        val clients = BridgeClients(store, scope, { _, urls, _ ->
            BridgeTransport(urls, ByteArray(32), null, newContext = { javax.net.ssl.SSLContext.getInstance("TLSv1.3") }).also { transports.add(it) }
        }, {})
        val events = Channel<ProfileSnapshot>(Channel.UNLIMITED)
        var lists = 0
        val resumedList = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val repository = BridgeBotsRepository(store, clients) { object : ProfilesApi {
            override suspend fun list(): ProfileSnapshot {
                if (++lists > 1) { resumedList.complete(Unit); awaitCancellation() }
                return ProfileSnapshot(1, listOf(profile()))
            }
            override fun events(): Flow<ProfileSnapshot> = flow {
                entered.complete(Unit)
                try { events.receiveAsFlow().collect { emit(it) } } finally { cancelled.complete(Unit) }
            }
        } }
        val a = bridge()
        var following: Job? = null
        val holder = ActivePcHolder(ConnectionStore(ds, cipher), scope, repository)
        try {
            store.save(a)
            following = launch { repository.follow() }
            withTimeout(5000) { entered.await() }
            val initial = withTimeout(5000) { repository.state.first { it.groups.singleOrNull()?.profiles?.size == 1 } }
            assertEquals(a.name, initial.groups.single().name)
            val first = (repository.target(a.bridgeId, "default") as HermesResult.Ok).value
            assertSame(clients.client(a).client, first.bridge!!.client)
            val initialTarget = withTimeout(5000) {
                holder.state.first { it.target is ActiveTarget.Ready && it.activeId == bridgeBotId(a.bridgeId, "default") }.target as ActiveTarget.Ready
            }
            assertEquals("default", initialTarget.target.bridge?.profileId)
            assertEquals(first, (holder.currentTarget() as ActiveTarget.Ready).target)
            val restarted = ActivePcHolder(ConnectionStore(ds, cipher), scope, repository)
            val restartedTarget = withTimeout(5000) {
                restarted.state.first { it.target is ActiveTarget.Ready && it.activeId == bridgeBotId(a.bridgeId, "default") }.target as ActiveTarget.Ready
            }
            assertEquals("default", restartedTarget.target.bridge?.profileId)
            assertEquals(BridgeSelection(a.bridgeId, "default"), withTimeout(5000) { repository.activeSelection.first { it != null } })
            events.send(ProfileSnapshot(2, listOf(profile(), profile("new-profile"))))
            withTimeout(5000) { repository.state.first { it.groups.single().profiles.size == 2 } }
            events.send(ProfileSnapshot(3, listOf(profile("new-profile", false))))
            val changed = withTimeout(5000) { repository.state.first { it.groups.single().profiles.singleOrNull()?.id == "new-profile" } }
            assertFalse(changed.groups.single().profiles.single().ready)
            assertEquals(HermesError.ProfileUnknown("default"), (repository.target(a.bridgeId, "default") as HermesResult.Err).error)
            assertEquals(HermesError.ProfileNotReady, (repository.target(a.bridgeId, "new-profile") as HermesResult.Err).error)
            following.cancelAndJoin()
            withTimeout(5000) { cancelled.await() }
            assertEquals(1, transports.size)
            assertSame(first.bridge!!.client, clients.client(a).client)
            following = launch { repository.follow() }
            withTimeout(5000) { resumedList.await() }
            assertEquals(changed.groups.single().profiles, repository.state.value.groups.single().profiles)
            following.cancelAndJoin()
            assertSame(first.bridge!!.client, clients.client(a).client)
        } finally {
            following?.cancelAndJoin()
            transports.forEach { it.close() }
            scope.cancel()
            scope.coroutineContext[Job]!!.join()
        }
    }

    @Test fun forgettingSelectedBridgeClearsSelectionAndFallsBackToDirectPc() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ds = PreferenceDataStoreFactory.create(scope = scope) { tmp.root.resolve("forget.preferences_pb") }
        val store = BridgeStore(ds, cipher)
        val connections = ConnectionStore(ds, cipher)
        val directId = connections.save(Connection("", "Direct PC", "http://example.invalid"), "token")
        connections.setActive(directId)
        val bridge = bridge()
        val transports = mutableListOf<BridgeTransport>()
        val clients = BridgeClients(store, scope, { _, urls, _ ->
            BridgeTransport(urls, ByteArray(32), null, newContext = { javax.net.ssl.SSLContext.getInstance("TLSv1.3") }).also { transports.add(it) }
        }, {})
        val repository = BridgeBotsRepository(store, clients) { object : ProfilesApi {
            override suspend fun list() = ProfileSnapshot(1, listOf(profile()))
            override fun events(): Flow<ProfileSnapshot> = flow { awaitCancellation() }
        } }
        var following: Job? = null
        try {
            store.save(bridge)
            following = launch { repository.follow() }
            withTimeout(5000) { repository.state.first { it.groups.singleOrNull()?.profiles?.isNotEmpty() == true } }
            val holder = ActivePcHolder(connections, scope, repository)
            holder.selectBridge(bridge.bridgeId, "default")
            withTimeout(5000) { holder.state.first { it.activeId == bridgeBotId(bridge.bridgeId, "default") && it.target is ActiveTarget.Ready } }

            clients.forget(bridge)

            assertNull(withTimeout(5000) { repository.activeSelection.first { it == null } })
            withTimeout(5000) { holder.state.first { it.activeId == directId && it.target is ActiveTarget.Ready } }
            Unit
        } finally {
            following?.cancelAndJoin()
            transports.forEach { it.close() }
            scope.cancel()
            scope.coroutineContext[Job]!!.join()
        }
    }

    @Test fun everySavedBridgeIsIndependentAndForbiddenBecomesRevoked() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ds = PreferenceDataStoreFactory.create(scope = scope) { tmp.root.resolve("groups.preferences_pb") }
        val store = BridgeStore(ds, cipher)
        val a = bridge()
        val b = bridge()
        val transports = mutableListOf<BridgeTransport>()
        val revoked = Channel<ProfileSnapshot>()
        val clients = BridgeClients(store, scope, { _, urls, _ ->
            BridgeTransport(urls, ByteArray(32), null, newContext = { javax.net.ssl.SSLContext.getInstance("TLSv1.3") }).also { transports.add(it) }
        }, {})
        try {
            store.save(a); store.save(b)
            val firstClient = clients.client(a).client
            val repository = BridgeBotsRepository(store, clients) { http -> object : ProfilesApi {
                override suspend fun list() = ProfileSnapshot(1, listOf(profile()))
                override fun events(): Flow<ProfileSnapshot> = if (http === firstClient) revoked.receiveAsFlow() else flow { awaitCancellation() }
            } }
            val following = launch { repository.follow() }
            try {
                withTimeout(5000) { repository.state.first { it.groups.size == 2 && it.groups.all { g -> !g.loading } } }
                revoked.close(ProfileStreamException(HermesError.DeviceRevoked))
                val state = withTimeout(5000) { repository.state.first { it.groups.first { g -> g.id == a.bridgeId }.error == HermesError.DeviceRevoked } }
                assertNull(state.groups.first { it.id == b.bridgeId }.error)
                assertEquals(HermesError.DeviceRevoked, (repository.target(a.bridgeId, "default") as HermesResult.Err).error)
                assertTrue(repository.target(b.bridgeId, "default") is HermesResult.Ok)
                store.delete(a.bridgeId)
                withTimeout(5000) { repository.state.first { it.groups.size == 1 && it.groups.single().id == b.bridgeId } }
                assertEquals(HermesError.ConnectionChanged, (repository.target(a.bridgeId, "default") as HermesResult.Err).error)
            } finally { following.cancelAndJoin() }
        } finally { transports.forEach { it.close() }; scope.cancel(); scope.coroutineContext[Job]!!.join() }
    }

    @Test fun bridgeRunMemoryIsPartitionedByBridgeAndProfile() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ds = PreferenceDataStoreFactory.create(scope = scope) { tmp.root.resolve("memory.preferences_pb") }
        try {
            val store = ConnectionStore(ds, cipher)
            val id = UUID.randomUUID().toString()
            val a = bridgeRunMemory(store, id, "default")
            val same = bridgeRunMemory(store, id, "default")
            val otherProfile = bridgeRunMemory(store, id, "new-profile")
            val otherBridge = bridgeRunMemory(store, UUID.randomUUID().toString(), "default")
            assertEquals(a.sessionId(), same.sessionId())
            assertNotEquals(a.sessionId(), otherProfile.sessionId())
            assertNotEquals(a.sessionId(), otherBridge.sessionId())
            a.setSessionId(a.sessionId())
            a.setActiveRun("one")
            assertEquals("one", same.activeRun())
            assertNull(otherProfile.activeRun())
            assertNull(otherBridge.activeRun())
        } finally { scope.cancel(); scope.coroutineContext[Job]!!.join() }
    }
}
