package app.rasul.bridge

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.rasul.data.clearConnectionState
import app.rasul.data.revisionKey
import app.rasul.data.SecretCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SavedBridge(
    val bridgeId: String,
    val name: String,
    val urls: List<String>,
    val pin: String,
    val deviceId: String,
    val keyAlias: String,
    val lastWorkingUrl: String? = null,
)

data class BridgeSelection(val bridgeId: String, val profileId: String)

/** All bridge metadata is encrypted before it enters the backup-excluded preferences. */
class BridgeStore(private val ds: DataStore<Preferences>, private val cipher: SecretCipher) {
    private val key = stringPreferencesKey("bridges_v1")
    private val activeBridgeKey = stringPreferencesKey("active_bridge_id")
    private val activeProfileKey = stringPreferencesKey("active_bridge_profile")
    private val json = Json
    private fun Preferences.read(): List<SavedBridge> = this[key]?.let {
        json.decodeFromString<List<SavedBridge>>(cipher.decrypt(it))
    } ?: emptyList()
    val bridges = ds.data.map { withContext(Dispatchers.IO) { it.read() } }
    val activeSelection = ds.data.map { p ->
        val bridgeId = p[activeBridgeKey]
        val profileId = p[activeProfileKey]
        if (bridgeId.isNullOrBlank() || profileId.isNullOrBlank()) null else BridgeSelection(bridgeId, profileId)
    }.distinctUntilChanged()
    suspend fun get(id: String): SavedBridge? = withContext(Dispatchers.IO) {
        var saved: SavedBridge? = null
        ds.edit { p ->
            saved = p.read().firstOrNull { it.bridgeId == id }
            saved?.let { bridge ->
                val revision = revisionKey("bridge:${id}:")
                if (p[revision] == null) p[revision] = bridge.keyAlias.ifEmpty { "0" }
            }
        }
        saved
    }
    suspend fun setActiveSelection(selection: BridgeSelection?) = withContext(Dispatchers.IO) {
        ds.edit { p ->
            val saved = selection?.takeIf { selected -> p.read().any { it.bridgeId == selected.bridgeId } }
            if (saved == null) {
                p.remove(activeBridgeKey)
                p.remove(activeProfileKey)
            } else {
                p[activeBridgeKey] = saved.bridgeId
                p[activeProfileKey] = saved.profileId
            }
        }
    }
    suspend fun save(bridge: SavedBridge) = withContext(Dispatchers.IO) {
        ds.edit { p ->
            val all = p.read()
            p[revisionKey("bridge:${bridge.bridgeId}:")] = bridge.keyAlias.ifEmpty { "0" }
            p[key] = cipher.encrypt(json.encodeToString(all.filterNot { it.bridgeId == bridge.bridgeId } + bridge))
        }
    }
    suspend fun remember(id: String, url: String) = withContext(Dispatchers.IO) {
        ds.edit { p ->
            p[key] = cipher.encrypt(json.encodeToString(p.read().map {
                if (it.bridgeId == id && url in it.urls) it.copy(lastWorkingUrl = url) else it
            }))
        }
    }
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        ds.edit { p ->
            clearConnectionState(p, "bridge:${id}:")
            p[revisionKey("bridge:${id}:")] = java.util.UUID.randomUUID().toString()
            p[key] = cipher.encrypt(json.encodeToString(p.read().filterNot { it.bridgeId == id }))
            if (p[activeBridgeKey] == id) {
                p.remove(activeBridgeKey)
                p.remove(activeProfileKey)
            }
        }
    }
}
