package app.rasul

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import app.rasul.bridge.*
import app.rasul.data.SecretCipher
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class BridgeStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun encryptedRoundTripRememberAndDelete() = runBlocking {
        val job = SupervisorJob()
        val file = tmp.root.resolve("bridge.preferences_pb")
        val ds = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { file }
        val cipher = object : SecretCipher {
            override fun encrypt(plain: String) = java.util.Base64.getEncoder().encodeToString(plain.reversed().toByteArray())
            override fun decrypt(token: String) = String(java.util.Base64.getDecoder().decode(token)).reversed()
        }
        val store = BridgeStore(ds, cipher)
        val row = SavedBridge(UUID.randomUUID().toString(), "Bridge", listOf(("wss://example.invalid" + "/v1/link")),
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32)),
            UUID.randomUUID().toString().replace("-", ""), UUID.randomUUID().toString())
        try {
            assertTrue(store.bridges.first().isEmpty())
            store.save(row)
            assertEquals(row, store.bridges.first().single())
            assertFalse(file.readText().contains(row.bridgeId))
            store.remember(row.bridgeId, row.urls.single())
            assertEquals(row.urls.single(), store.get(row.bridgeId)?.lastWorkingUrl)
            store.remember(row.bridgeId, "invalid")
            assertEquals(row.urls.single(), store.get(row.bridgeId)?.lastWorkingUrl)
            store.delete(row.bridgeId)
            assertTrue(store.bridges.first().isEmpty())
        } finally { job.cancelAndJoin() }
    }
    @Test fun forgetAndPairSameBridgeClearsSessionAndRunPointers() = runBlocking {
        val job = SupervisorJob()
        val ds = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { tmp.root.resolve("state.preferences_pb") }
        val cipher = object : SecretCipher {
            override fun encrypt(plain: String) = plain
            override fun decrypt(token: String) = token
        }
        val bridges = BridgeStore(ds, cipher)
        val connections = app.rasul.data.ConnectionStore(ds, cipher)
        val row = SavedBridge("one", "Bridge", listOf("wss://example.invalid"), "", "", "one")
        try {
            bridges.save(row)
            for (profile in listOf("default", "profile-a")) {
                connections.setCurrentSessionRun("bridge:one:$profile", profile, "session-a", "run-1", 7, "anchor")
            }
            val twoSession = connections.sessionId("bridge:two:default", "default")
            connections.setCurrentSessionRun("bridge:two:default", "default", twoSession, "run-2")
            BridgeClients(bridges, CoroutineScope(job + Dispatchers.IO), deleteKey = {}).forget(row)
            bridges.save(row.copy(keyAlias = "two"))
            connections.setCurrentSessionRun("bridge:one:default", "default", "session-a", "run-1", expectedRev = "one")
            for (profile in listOf("default", "profile-a")) {
                assertNull(connections.currentSessionId("bridge:one:$profile", profile))
                assertNull(connections.activeRun("bridge:one:$profile", profile, "session-a"))
                assertNull(connections.lastEventId("bridge:one:$profile", profile, "session-a"))
                assertNull(connections.runAnchor("bridge:one:$profile", profile, "session-a"))
            }
            assertEquals(twoSession, connections.currentSessionId("bridge:two:default", "default"))
            assertEquals("run-2", connections.activeRun("bridge:two:default", "default", twoSession))
        } finally { job.cancelAndJoin() }
    }

    @Test fun savedBridgeFromEarlierVersionInitializesRevisionBeforeTargetRead() = runBlocking {
        val job = SupervisorJob()
        val ds = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { tmp.root.resolve("state.preferences_pb") }
        val cipher = object : SecretCipher {
            override fun encrypt(plain: String) = plain
            override fun decrypt(token: String) = token
        }
        val bridges = BridgeStore(ds, cipher)
        val connections = app.rasul.data.ConnectionStore(ds, cipher)
        val row = SavedBridge("one", "Bridge", listOf("wss://example.invalid"), "", "", "one")
        try {
            bridges.save(row)
            ds.edit { it.remove(app.rasul.data.revisionKey("bridge:one:default")) }
            assertEquals(row, bridges.get("one"))
            assertEquals(row.keyAlias, connections.revision("bridge:one:default"))
        } finally { job.cancelAndJoin() }
    }

}
