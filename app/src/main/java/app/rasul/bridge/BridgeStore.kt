package app.rasul.bridge

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.rasul.data.SecretCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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

/** All bridge metadata is encrypted before it enters the backup-excluded preferences. */
class BridgeStore(private val ds: DataStore<Preferences>, private val cipher: SecretCipher) {
    private val key = stringPreferencesKey("bridges_v1")
    private val json = Json
    private fun Preferences.read(): List<SavedBridge> = this[key]?.let {
        json.decodeFromString<List<SavedBridge>>(cipher.decrypt(it))
    } ?: emptyList()
    val bridges = ds.data.map { withContext(Dispatchers.IO) { it.read() } }
    suspend fun get(id: String) = bridges.first().firstOrNull { it.bridgeId == id }
    suspend fun save(bridge: SavedBridge) = withContext(Dispatchers.IO) {
        ds.edit { p ->
            val all = p.read()
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
        ds.edit { p -> p[key] = cipher.encrypt(json.encodeToString(p.read().filterNot { it.bridgeId == id })) }
    }
}
