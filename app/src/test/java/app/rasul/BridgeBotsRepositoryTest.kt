package app.rasul

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.rasul.bridge.*
import app.rasul.data.*
import app.rasul.ui.ActivePcHolder
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
            holder.selectBridge(a.bridgeId, "default")
            withTimeout(5000) { holder.state.first { it.target is ActiveTarget.Ready } }
            assertEquals(first, (holder.currentTarget() as ActiveTarget.Ready).target)
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
            a.setActiveRun("one")
            assertEquals("one", same.activeRun())
            assertNull(otherProfile.activeRun())
            assertNull(otherBridge.activeRun())
        } finally { scope.cancel(); scope.coroutineContext[Job]!!.join() }
    }
}
