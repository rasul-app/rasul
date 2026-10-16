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
    suspend fun client(bridge: SavedBridge): BridgeTransport = mutex.withLock {
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

fun deleteDeviceKey(alias: String) {
    KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
}
