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

class BridgeClientsTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun reusesOneClientPerBridgeRestoresRouteAndForgetsKey() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val ds = PreferenceDataStoreFactory.create(scope = scope) { tmp.root.resolve("clients.preferences_pb") }
        val store = BridgeStore(ds, object : SecretCipher {
            override fun encrypt(plain: String) = plain.reversed()
            override fun decrypt(token: String) = token.reversed()
        })
        val origin = "wss://example.invalid"
        val urls = listOf(origin, origin + "/v1/link")
        val row = SavedBridge(UUID.randomUUID().toString(), "Bridge", urls,
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32)),
            UUID.randomUUID().toString().replace("-", ""), UUID.randomUUID().toString(), urls.last())
        val deleted = mutableListOf<String>()
        val routes = mutableListOf<List<String>>()
        val links = mutableListOf<BridgeTransport>()
        var deletionUnavailable = false
        val clients = BridgeClients(store, scope, { _, ordered, _ ->
            routes.add(ordered)
            BridgeTransport(ordered, ByteArray(32), null, newContext = { javax.net.ssl.SSLContext.getInstance("TLSv1.3") }).also { links.add(it) }
        }, {
            if (deletionUnavailable) throw java.io.IOException("Deletion unavailable")
            deleted.add(it)
        })
        try {
            store.save(row)
            val first = clients.client(row)
            assertSame(first, clients.client(row))
            val other = row.copy(bridgeId = UUID.randomUUID().toString(), keyAlias = UUID.randomUUID().toString())
            store.save(other)
            assertNotSame(first, clients.client(other))
            assertEquals(listOf(urls.last(), urls.first()), routes.first())
            deletionUnavailable = true
            try { clients.forget(row); fail("expected deletion failure") }
            catch (_: java.io.IOException) { }
            assertEquals(row, store.get(row.bridgeId))
            deletionUnavailable = false
            clients.forget(row)
            assertEquals(listOf(row.keyAlias), deleted)
            assertNull(store.get(row.bridgeId))
            assertTrue(first.client.dispatcher.executorService.isShutdown)
            assertEquals(other, store.bridges.first().single())
            clients.forget(other)
        } finally { links.forEach { it.close() }; job.cancelAndJoin() }
    }
}
