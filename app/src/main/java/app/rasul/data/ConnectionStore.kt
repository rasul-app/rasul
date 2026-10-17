package app.rasul.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

data class Connection(val id: String, val name: String, val baseUrl: String, val profile: String? = null)

/** On-disk form: the key is only ever present encrypted. */
@Serializable
private data class StoredConnection(
    val id: String,
    val name: String,
    val baseUrl: String,
    val profile: String? = null,
    val encKey: String? = null,
)

/** The Keystore could not encrypt the API key (as opposed to the storage write failing). */
class SecretEncryptionException(cause: Throwable) : Exception("Could not encrypt the API key", cause)

class ConnectionStore(private val ds: DataStore<Preferences>, private val cipher: SecretCipher) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(StoredConnection.serializer())
    private val listKey = stringPreferencesKey("connections_v1")
    private val activeKey = stringPreferencesKey("active_connection_v1")
    private val runUsageKey = stringPreferencesKey("run_usage_v1")

    @Serializable
    private data class StoredRunUsage(
        val sessionByRun: Map<String, String> = emptyMap(),
        val recordedRunIds: Set<String> = emptySet(),
        val usageBySession: Map<String, TokenUsageSummary> = emptyMap(),
    )

    private fun Preferences.runUsage(): StoredRunUsage = this[runUsageKey]?.let {
        runCatching { json.decodeFromString(StoredRunUsage.serializer(), it) }.getOrNull()
    } ?: StoredRunUsage()

    val runUsageBySession: Flow<Map<String, TokenUsageSummary>> = ds.data.map { it.runUsage().usageBySession }

    private fun Preferences.stored(): List<StoredConnection> =
        this[listKey]?.let { json.decodeFromString(serializer, it) } ?: emptyList()

    val connections: Flow<List<Connection>> = ds.data.map { p ->
        p.stored().map { Connection(it.id, it.name, it.baseUrl, it.profile) }
    }

    /** Id the user picked in the PC switcher, or null (then the first saved connection is the active one). */
    val activeId: Flow<String?> = ds.data.map { it[activeKey] }

    /** The connection the Sessions and Jobs tabs use: the picked one if it still exists, else the first. */
    suspend fun active(): Connection? {
        val all = connections.first()
        val picked = ds.data.first()[activeKey]
        return all.firstOrNull { it.id == picked } ?: all.firstOrNull()
    }

    suspend fun setActive(id: String) {
        ds.edit { it[activeKey] = id }
    }

    suspend fun get(id: String): Connection? = connections.first().firstOrNull { it.id == id }

    /** Null when no key is stored or it can no longer be decrypted (Keystore reset). */
    suspend fun apiKey(id: String): String? = decrypt(ds.data.first().stored().firstOrNull { it.id == id }?.encKey)

    private suspend fun decrypt(enc: String?): String? {
        if (enc == null) return null
        return try {
            withContext(Dispatchers.IO) { cipher.decrypt(enc) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** Saved PCs and the resolved active target, both built from ONE DataStore snapshot (key decrypted on IO). */
    data class Snapshot(val pcs: List<Connection>, val activeId: String?, val target: ActiveTarget)

    val snapshots: Flow<Snapshot> = ds.data.map { p ->
        val stored = p.stored()
        val picked = p[activeKey]
        val s = stored.firstOrNull { it.id == picked } ?: stored.firstOrNull()
        Snapshot(
            stored.map { Connection(it.id, it.name, it.baseUrl, it.profile) },
            s?.id,
            if (s == null) ActiveTarget.None else readyOrNoKey(Connection(s.id, s.name, s.baseUrl, s.profile), decrypt(s.encKey)),
        )
    }

    /** Insert or update. [apiKey] null keeps the key already stored. Returns the connection id. */
    suspend fun save(c: Connection, apiKey: String?): String {
        val id = c.id.ifBlank { UUID.randomUUID().toString() }
        val enc = apiKey?.let {
            withContext(Dispatchers.IO) {
                try {
                    cipher.encrypt(it)
                } catch (e: Exception) {
                    throw SecretEncryptionException(e)
                }
            }
        }
        ds.edit { p ->
            val list = p.stored()
            val oldKey = list.firstOrNull { it.id == id }?.encKey
            val row = StoredConnection(
                id, c.name.trim(), c.baseUrl.trim(), c.profile?.trim()?.takeIf { it.isNotEmpty() },
                enc ?: oldKey,
            )
            val next = if (list.any { it.id == id }) list.map { if (it.id == id) row else it } else list + row
            p[listKey] = json.encodeToString(serializer, next)
        }
        return id
    }

    suspend fun currentSessionId(id: String, profile: String?): String? =
        ds.data.first()[stringPreferencesKey("session_${id}_${profile.orEmpty()}")]

    /** One stable session id per connection and profile (created on first use), so Back does not start a new Hermes session. */
    suspend fun sessionId(id: String, profile: String?): String {
        val key = stringPreferencesKey("session_${id}_${profile.orEmpty()}")
        ds.data.first()[key]?.let { return it }
        var out = ""
        ds.edit { p -> out = p[key] ?: ("rasul-" + UUID.randomUUID()).also { p[key] = it } }
        return out
    }

    suspend fun setCurrentSessionRun(id: String, profile: String?, sessionId: String, runId: String?, lastEventId: Long? = null, anchorKey: String? = null) {
        val sessionKey = stringPreferencesKey("session_" + id + "_" + profile.orEmpty())
        val key = runKey(id, profile)
        val eventKey = runEventKey(id, profile)
        val anchor = runAnchorKey(id, profile)
        ds.edit { p ->
            p[sessionKey] = sessionId
            if (runId == null) {
                p.remove(key)
                p.remove(eventKey)
                p.remove(anchor)
            } else {
                p[key] = runId
                if (anchorKey == null) p.remove(anchor) else p[anchor] = anchorKey
                if (lastEventId == null) p.remove(eventKey) else p[eventKey] = lastEventId
            }
        }
    }

    /** Clear matching direct or bridge session pointers and their run resume state atomically. */
    suspend fun clearSessionPointers(sessionId: String) {
        ds.edit { p ->
            val suffixes = p.asMap()
                .filter { (key, value) -> key.name.startsWith("session_") && value == sessionId }
                .keys
                .map { it.name.removePrefix("session_") }
            suffixes.forEach { suffix ->
                p.remove(stringPreferencesKey("session_$suffix"))
                p.remove(stringPreferencesKey("run_$suffix"))
                p.remove(longPreferencesKey("run_event_$suffix"))
                p.remove(stringPreferencesKey("run_anchor_$suffix"))
            }
        }
    }

    /** The run left going when the chat was closed, if any. Keyed like the session: connection id + profile. */
    suspend fun activeRun(id: String, profile: String?): String? = ds.data.first()[runKey(id, profile)]

    data class PersistedRun(val id: String, val profile: String?)

    suspend fun persistedRuns(): List<PersistedRun> {
        val prefs = ds.data.first()
        val ids = prefs.stored().map { it.id }.sortedByDescending { it.length }
        return prefs.asMap().keys.mapNotNull { key ->
            if (!key.name.startsWith("run_") || !prefs.contains(stringPreferencesKey("session_" + key.name.removePrefix("run_")))) return@mapNotNull null
            val suffix = key.name.removePrefix("run_")
            val id = ids.firstOrNull { suffix.startsWith(it + "_") }
            if (id != null) return@mapNotNull PersistedRun(id, suffix.removePrefix(id + "_").ifEmpty { null })
            if (!suffix.startsWith("bridge:")) return@mapNotNull null
            val split = suffix.indexOf(':', "bridge:".length)
            if (split < 0) return@mapNotNull null
            val repeated = suffix.substring(split + 1)
            val middle = repeated.length / 2
            if (repeated.length % 2 != 1 || repeated[middle] != '_' || repeated.take(middle) != repeated.drop(middle + 1)) return@mapNotNull null
            val profile = repeated.take(middle)
            PersistedRun(suffix.take(split + 1) + profile, profile)
        }
    }

    suspend fun lastEventId(id: String, profile: String?): Long? = ds.data.first()[runEventKey(id, profile)]

    suspend fun runAnchor(id: String, profile: String?): String? = ds.data.first()[runAnchorKey(id, profile)]

    private fun runAnchorKey(id: String, profile: String?) = stringPreferencesKey("run_anchor_${id}_${profile.orEmpty()}")
    private fun runKey(id: String, profile: String?) = stringPreferencesKey("run_${id}_${profile.orEmpty()}")
    private fun runEventKey(id: String, profile: String?) = longPreferencesKey("run_event_${id}_${profile.orEmpty()}")

    suspend fun setActiveRun(id: String, profile: String?, runId: String?, lastEventId: Long? = null, anchorKey: String? = null) {
        val key = runKey(id, profile)
        val eventKey = runEventKey(id, profile)
        val anchor = runAnchorKey(id, profile)
        ds.edit { p ->
            if (runId == null) {
                p.remove(key)
                p.remove(eventKey)
                p.remove(anchor)
            } else {
                p[key] = runId
                if (anchorKey == null) p.remove(anchor) else p[anchor] = anchorKey
                if (lastEventId == null) p.remove(eventKey) else p[eventKey] = lastEventId
            }
        }
    }

    suspend fun rememberRunSession(runId: String, sessionId: String) {
        require(runId.isNotBlank() && sessionId.isNotBlank())
        ds.edit { p ->
            val current = p.runUsage()
            if (runId !in current.recordedRunIds) {
                p[runUsageKey] = json.encodeToString(
                    StoredRunUsage.serializer(),
                    current.copy(sessionByRun = current.sessionByRun + (runId to sessionId)),
                )
            }
        }
    }

    suspend fun recordCompletedRunUsage(runId: String, usage: RunTokenUsage?): Map<String, TokenUsageSummary>? {
        var result: Map<String, TokenUsageSummary>? = null
        ds.edit { p ->
            val current = p.runUsage()
            val sessionId = current.sessionByRun[runId]
            if (sessionId != null && runId !in current.recordedRunIds) {
                val nextUsage = current.usageBySession + (
                    sessionId to (current.usageBySession[sessionId] ?: TokenUsageSummary()).include(usage)
                )
                p[runUsageKey] = json.encodeToString(
                    StoredRunUsage.serializer(),
                    current.copy(
                        sessionByRun = current.sessionByRun - runId,
                        recordedRunIds = current.recordedRunIds + runId,
                        usageBySession = nextUsage,
                    ),
                )
                result = nextUsage
            }
        }
        return result
    }

    suspend fun delete(id: String) {
        ds.edit { p -> p[listKey] = json.encodeToString(serializer, p.stored().filterNot { it.id == id }) }
    }
}
