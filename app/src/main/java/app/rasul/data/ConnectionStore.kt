package app.rasul.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.UUID

data class Connection(val id: String, val name: String, val baseUrl: String, val profile: String? = null)

internal fun revisionKey(id: String) = stringPreferencesKey("conn_rev_" +
    if (id.startsWith("bridge:")) java.security.MessageDigest.getInstance("SHA-256")
        .digest(id.substringBeforeLast(':').toByteArray()).joinToString("") { "%02x".format(it) } else id)

internal fun clearConnectionState(p: MutablePreferences, suffix: String) {
    val prefixes = listOf("session_", "run_", "run_event_", "run_anchor_", "run_source_")
    p.asMap().keys.filter { key -> prefixes.any { key.name.startsWith(it + suffix) } }.forEach { p.remove(it) }
}

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
    private val runKeysV2Key = booleanPreferencesKey("runkeys_v2")
    private var migratedRunKeys = false

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

    @Serializable
    private data class StoredModel(val provider: String, val model: String)

    private val chatModelsKey = stringPreferencesKey("chat_models_v1")

    private fun Preferences.chatModels(): Map<String, StoredModel> = this[chatModelsKey]?.let {
        runCatching { json.decodeFromString(MapSerializer(String.serializer(), StoredModel.serializer()), it) }.getOrNull()
    } ?: emptyMap()

    /** The model picked per chat (session id), kept on the phone: Hermes ignores a saved model lock on /v1/runs. */
    suspend fun chatModels(): Map<String, RunModelSelection> =
        ds.data.first().chatModels().mapValues { RunModelSelection(it.value.provider, it.value.model) }

    /** [selection] null = server default: the chat's entry is removed. */
    suspend fun setChatModel(sessionId: String, selection: RunModelSelection?) {
        require(sessionId.isNotBlank())
        ds.edit { p ->
            val next = p.chatModels().let {
                if (selection == null) it - sessionId else it + (sessionId to StoredModel(selection.provider, selection.model))
            }
            if (next.isEmpty()) p.remove(chatModelsKey)
            else p[chatModelsKey] = json.encodeToString(MapSerializer(String.serializer(), StoredModel.serializer()), next)
        }
    }

    private fun MutablePreferences.dropChatModel(sessionId: String) {
        val next = chatModels() - sessionId
        if (next.isEmpty()) remove(chatModelsKey)
        else this[chatModelsKey] = json.encodeToString(MapSerializer(String.serializer(), StoredModel.serializer()), next)
    }

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
            if (s == null) ActiveTarget.None else readyOrNoKey(Connection(s.id, s.name, s.baseUrl, s.profile), decrypt(s.encKey), p[revisionKey(s.id)] ?: "0"),
        )
    }

    suspend fun revision(id: String): String = ds.data.first()[revisionKey(id)] ?: "0"

    /** Address, profile, decrypted key and revision all come from one preferences snapshot. */
    suspend fun target(id: String): HermesResult<HermesTarget> {
        val p = ds.data.first()
        val c = p.stored().firstOrNull { it.id == id } ?: return HermesResult.Err(HermesError.NotFound)
        val key = decrypt(c.encKey) ?: return HermesResult.Err(HermesError.InvalidKey)
        return HermesResult.Ok(HermesTarget(c.baseUrl, c.profile, key, revision = p[revisionKey(id)] ?: "0"))
    }

    /** Why a transcript Continue was accepted or refused. */
    enum class ContinueResult { Selected, Busy, RevisionMismatch }

    /** The current-session pointer a `newSession=` route saw when it was opened; [expected] may be null. */
    data class PointerClaim(val expected: String?)

    /** Select a transcript only while it still belongs to this connection revision. */
    suspend fun continueSession(id: String, profile: String?, expectedRev: String, sessionId: String): ContinueResult {
        ensureRunKeysMigrated()
        var result = ContinueResult.RevisionMismatch
        ds.edit { p ->
            if ((p[revisionKey(id)] ?: "0") != expectedRev) return@edit
            // Re-selecting the session that owns the chat keeps its running reply's restore state.
            // Another session of the same connection/profile holding a run refuses the switch.
            if (p[sessionKey(id, profile)] != sessionId) {
                if (hasOtherSessionRun(p, id, profile, sessionId)) {
                    result = ContinueResult.Busy
                    return@edit
                }
                p[sessionKey(id, profile)] = sessionId
            }
            result = ContinueResult.Selected
        }
        return result
    }

    /** Any stored run owned by a session other than [sessionId] of this connection/profile. */
    private fun hasOtherSessionRun(p: MutablePreferences, id: String, profile: String?, sessionId: String): Boolean {
        val prefix = "run_${id}_${profile.orEmpty()}_"
        return p.asMap().keys.any { key ->
            key.name.startsWith(prefix) && !key.name.startsWith("run_event_") &&
                !key.name.startsWith("run_anchor_") && !key.name.startsWith("run_source_") &&
                decSession(key.name.removePrefix(prefix))?.let { it != sessionId } == true
        }
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
            val old = list.firstOrNull { it.id == id }
            val oldKey = old?.encKey
            val row = StoredConnection(
                id, c.name.trim(), c.baseUrl.trim(), c.profile?.trim()?.takeIf { it.isNotEmpty() },
                enc ?: oldKey,
            )
            if (old != null && (old.baseUrl != row.baseUrl || old.profile != row.profile || apiKey != null)) {
                clearConnectionState(p, "${id}_")
                p[revisionKey(id)] = UUID.randomUUID().toString()
            }
            val next = if (list.any { it.id == id }) list.map { if (it.id == id) row else it } else list + row
            p[listKey] = json.encodeToString(serializer, next)
        }
        return id
    }

    suspend fun currentSessionId(id: String, profile: String?): String? =
        ds.data.first()[sessionKey(id, profile)]

    /** One stable session id per connection and profile (created on first use), so Back does not start a new Hermes session. */
    suspend fun sessionId(id: String, profile: String?, expectedRev: String? = null): String {
        ensureRunKeysMigrated()
        val key = sessionKey(id, profile)
        var out = ""
        ds.edit { p ->
            check(expectedRev == null || (p[revisionKey(id)] ?: "0") == expectedRev)
            out = p[key] ?: ("rasul-" + UUID.randomUUID()).also { p[key] = it }
        }
        return out
    }

    /** Persists [sessionId]'s own run keys. A [claim] (new-chat routes only) moves the pointer once, inside this
     *  same edit, only while it still equals the value seen at tap time and no other session holds a run. */
    suspend fun setCurrentSessionRun(id: String, profile: String?, sessionId: String, runId: String?, lastEventId: Long? = null, anchorKey: String? = null, expectedRev: String? = null, claim: PointerClaim? = null) {
        ensureRunKeysMigrated()
        ds.edit { p ->
            if (expectedRev != null && (p[revisionKey(id)] ?: "0") != expectedRev) return@edit
            writeRunKeys(p, id, profile, sessionId, runId, lastEventId, anchorKey)
            val key = sessionKey(id, profile)
            if (runId != null && claim != null && p[key] == claim.expected && !hasOtherSessionRun(p, id, profile, sessionId)) {
                p[key] = sessionId
            }
        }
    }

    /** Clear matching direct or bridge session pointers and every run key that session owns, atomically. */
    suspend fun clearSessionPointers(sessionId: String) {
        ensureRunKeysMigrated()
        ds.edit { p ->
            p.dropChatModel(sessionId)
            p.asMap()
                .filter { (key, value) -> key.name.startsWith("session_") && value == sessionId }
                .keys.toList().forEach { p.remove(it) }
            val enc = "_" + encSession(sessionId)
            p.asMap().keys.filter { key ->
                key.name.startsWith("run_") && !key.name.startsWith("run_source_") && key.name != "run_usage_v1" &&
                    key.name.endsWith(enc)
            }.toList().forEach { p.remove(it) }
        }
    }

    /** The run [sessionId] left going when its chat was closed, if any. */
    suspend fun activeRun(id: String, profile: String?, sessionId: String): String? {
        ensureRunKeysMigrated()
        return ds.data.first()[runKey(id, profile, sessionId)]
    }

    /** Sessions of this connection/profile that still hold a stored run. */
    suspend fun activeRunSessions(id: String, profile: String?): Set<String> {
        ensureRunKeysMigrated()
        val prefix = "run_${id}_${profile.orEmpty()}_"
        return ds.data.first().asMap().keys.mapNotNull { key ->
            if (key.name.startsWith(prefix) && !key.name.startsWith("run_event_") &&
                !key.name.startsWith("run_anchor_") && !key.name.startsWith("run_source_")
            ) decSession(key.name.removePrefix(prefix)) else null
        }.toSet()
    }

    data class PersistedRun(val id: String, val profile: String?, val session: String)

    suspend fun persistedRuns(): List<PersistedRun> {
        ensureRunKeysMigrated()
        val prefs = ds.data.first()
        val ids = prefs.stored().map { it.id }.sortedByDescending { it.length }
        return prefs.asMap().keys.mapNotNull { key ->
            val name = key.name
            if (!name.startsWith("run_") || name.startsWith("run_event_") || name.startsWith("run_anchor_") ||
                name.startsWith("run_source_") || name == "run_usage_v1"
            ) return@mapNotNull null
            val suffix = name.removePrefix("run_")
            val session = decSession(suffix.substringAfterLast('_')) ?: return@mapNotNull null
            val rest = suffix.substringBeforeLast('_')
            val id = ids.firstOrNull { rest.startsWith(it + "_") }
            if (id != null) return@mapNotNull PersistedRun(id, rest.removePrefix(id + "_").ifEmpty { null }, session)
            if (!rest.startsWith("bridge:")) return@mapNotNull null
            val split = rest.indexOf(':', "bridge:".length)
            if (split < 0) return@mapNotNull null
            val repeated = rest.substring(split + 1)
            val middle = repeated.length / 2
            if (repeated.length % 2 != 1 || repeated[middle] != '_' || repeated.take(middle) != repeated.drop(middle + 1)) return@mapNotNull null
            val profile = repeated.take(middle)
            PersistedRun(rest.take(split + 1) + profile, profile, session)
        }
    }

    suspend fun lastEventId(id: String, profile: String?, sessionId: String): Long? {
        ensureRunKeysMigrated()
        return ds.data.first()[runEventKey(id, profile, sessionId)]
    }

    suspend fun runAnchor(id: String, profile: String?, sessionId: String): String? {
        ensureRunKeysMigrated()
        return ds.data.first()[runAnchorKey(id, profile, sessionId)]
    }

    /** The session segment is hex of the session's UTF-8 bytes, so '_' in session ids cannot corrupt key parsing. */
    private fun encSession(session: String) =
        session.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }

    private fun decSession(enc: String): String? {
        if (enc.isEmpty() || enc.length % 2 != 0) return null
        val bytes = ByteArray(enc.length / 2)
        for (i in bytes.indices) {
            val hi = Character.digit(enc[i * 2], 16)
            val lo = Character.digit(enc[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            bytes[i] = (hi shl 4 or lo).toByte()
        }
        return String(bytes, Charsets.UTF_8)
    }

    /** Legacy `run_<id>_<profile>` triples move under their stored pointer's session once, atomically.
     *  Every `run*` key present is unkeyed here: v2 writers always migrate before they write. */
    private suspend fun ensureRunKeysMigrated() {
        if (migratedRunKeys) return
        ds.edit { p ->
            if (p[runKeysV2Key] == true) return@edit
            p.asMap().keys.map { it.name }.mapNotNull { name ->
                when {
                    name.startsWith("run_event_") -> name.removePrefix("run_event_")
                    name.startsWith("run_anchor_") -> name.removePrefix("run_anchor_")
                    name.startsWith("run_source_") || name == "run_usage_v1" -> null
                    name.startsWith("run_") -> name.removePrefix("run_")
                    else -> null
                }
            }.toSet().forEach { suffix ->
                p[stringPreferencesKey("session_$suffix")]?.let { session ->
                    val enc = encSession(session)
                    p[stringPreferencesKey("run_$suffix")]?.let { p[stringPreferencesKey("run_${suffix}_$enc")] = it }
                    p[longPreferencesKey("run_event_$suffix")]?.let { p[longPreferencesKey("run_event_${suffix}_$enc")] = it }
                    p[stringPreferencesKey("run_anchor_$suffix")]?.let { p[stringPreferencesKey("run_anchor_${suffix}_$enc")] = it }
                }
                p.remove(stringPreferencesKey("run_$suffix"))
                p.remove(longPreferencesKey("run_event_$suffix"))
                p.remove(stringPreferencesKey("run_anchor_$suffix"))
            }
            p[runKeysV2Key] = true
        }
        migratedRunKeys = true
    }

    private fun sessionKey(id: String, profile: String?) = stringPreferencesKey("session_${id}_${profile.orEmpty()}")

    private fun runAnchorKey(id: String, profile: String?, sessionId: String) = stringPreferencesKey("run_anchor_${id}_${profile.orEmpty()}_${encSession(sessionId)}")
    private fun runKey(id: String, profile: String?, sessionId: String) = stringPreferencesKey("run_${id}_${profile.orEmpty()}_${encSession(sessionId)}")
    private fun runEventKey(id: String, profile: String?, sessionId: String) = longPreferencesKey("run_event_${id}_${profile.orEmpty()}_${encSession(sessionId)}")

    private fun writeRunKeys(p: MutablePreferences, id: String, profile: String?, sessionId: String, runId: String?, lastEventId: Long?, anchorKey: String?) {
        val key = runKey(id, profile, sessionId)
        val eventKey = runEventKey(id, profile, sessionId)
        val anchor = runAnchorKey(id, profile, sessionId)
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

    suspend fun setActiveRun(id: String, profile: String?, sessionId: String, runId: String?, lastEventId: Long? = null, anchorKey: String? = null, expectedRev: String? = null) {
        ensureRunKeysMigrated()
        ds.edit { p ->
            if (expectedRev != null && (p[revisionKey(id)] ?: "0") != expectedRev) return@edit
            writeRunKeys(p, id, profile, sessionId, runId, lastEventId, anchorKey)
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
        ds.edit { p ->
            clearConnectionState(p, "${id}_")
            p[revisionKey(id)] = UUID.randomUUID().toString()
            p[listKey] = json.encodeToString(serializer, p.stored().filterNot { it.id == id })
        }
    }
}
