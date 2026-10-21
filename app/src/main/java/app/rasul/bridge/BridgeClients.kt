package app.rasul.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.KeyStore
import java.util.Base64

class BridgeClients(
    private val store: BridgeStore,
    private val scope: CoroutineScope,
    private val create: (SavedBridge, List<String>, (String) -> Unit) -> BridgeTransport = { bridge, urls, working ->
        BridgeTransport(urls, Base64.getUrlDecoder().decode(bridge.pin), KeystoreDeviceIdentity(bridge.keyAlias).also { it.privateKey }, onWorkingUrl = working)
    },
    private val deleteKey: (String) -> Unit = ::deleteDeviceKey,
) {
    private val mutex = Mutex()
    private val clients = mutableMapOf<String, BridgeTransport>()
    /** Every protocol-1 route builds its transport here, so a protocol-2 PC can never be sent a /v1 request. */
    suspend fun client(bridge: SavedBridge): BridgeTransport = mutex.withLock {
        if (bridge.protocol != 1) throw NewerHostException()
        clients.getOrPut(bridge.bridgeId) {
            val urls = listOfNotNull(bridge.lastWorkingUrl).plus(bridge.urls).distinct()
            create(bridge, urls) { url -> scope.launch {
                try { store.remember(bridge.bridgeId, url) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { /* Route preference is optional; the saved URL list remains available. */ }
            } }
        }
    }
    suspend fun forget(bridge: SavedBridge): Unit = mutex.withLock {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
            clients.remove(bridge.bridgeId)?.close()
            deleteKey(bridge.keyAlias)
            store.delete(bridge.bridgeId)
            Unit
        }
    }
}

/** The saved PC speaks a newer protocol than the routes behind [BridgeClients.client]. */
class NewerHostException : java.io.IOException()

fun deleteDeviceKey(alias: String) {
    KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
}
