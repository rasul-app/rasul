package app.rasul

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
}
