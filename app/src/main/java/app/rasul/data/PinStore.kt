package app.rasul.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Pinned chats per bot and profile: session ids only, in the order they were pinned. The `pins_<bot>_` key prefix
 * means [clearBot] drops them with a removed PC or bridge. Lives in the chat-extras DataStore, apart from run breadcrumbs.
 */
class PinStore(private val ds: DataStore<Preferences>) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(String.serializer())

    private fun key(bot: String, profile: String?) = stringPreferencesKey("pins_${bot}_${profile.orEmpty()}")

    private fun Preferences.pins(key: Preferences.Key<String>): List<String> =
        this[key]?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()

    fun pinned(bot: String, profile: String?): Flow<List<String>> {
        val key = key(bot, profile)
        return ds.data.map { it.pins(key) }.distinctUntilChanged()
    }

    /**
     * False when the bot already has [MAX_PER_BOT] pins. Pinning a pinned chat changes nothing. With [visible] (the ids
     * the loaded list shows) only pins of those chats count toward the cap: a pin of a chat the list no longer reaches
     * is out of sight and could not be unpinned, so it must not block a new pin. The stored list stays under [MAX_STORED].
     */
    suspend fun pin(bot: String, profile: String?, session: String, visible: Set<String>? = null): Boolean {
        val key = key(bot, profile)
        var ok = true
        ds.edit { p ->
            val current = p.pins(key)
            if (session in current) return@edit
            val counted = if (visible == null) current.size else current.count { it in visible }
            if (counted >= MAX_PER_BOT) {
                ok = false
                return@edit
            }
            var next = current + session
            val extra = next.size - MAX_STORED
            if (extra > 0) {
                val drop = next.filter { visible == null || it !in visible }.take(extra).toSet()
                next = next.filterNot { it in drop }
            }
            p[key] = json.encodeToString(serializer, next)
        }
        return ok
    }

    /** Drops pins whose chat is not in [existing]; call it only with a list known to hold every chat of the bot. */
    suspend fun retain(bot: String, profile: String?, existing: Set<String>) {
        val key = key(bot, profile)
        ds.edit { p ->
            val current = p.pins(key)
            val kept = current.filter { it in existing }
            if (kept.size != current.size) write(p, key, kept)
        }
    }

    /** A removed PC or bridge leaves no pins ([prefix] is the bot id followed by `_`, or `bridge:<id>:`). */
    suspend fun clearBot(prefix: String) {
        ds.edit { p -> p.asMap().keys.filter { it.name.startsWith("pins_$prefix") }.toList().forEach { p.remove(it) } }
    }

    suspend fun unpin(bot: String, profile: String?, session: String) {
        val key = key(bot, profile)
        ds.edit { p -> write(p, key, p.pins(key) - session) }
    }

    /** A deleted chat leaves every pin list. */
    suspend fun removeSession(session: String) {
        ds.edit { p ->
            p.asMap().keys.filter { it.name.startsWith("pins_") }.forEach { raw ->
                val key = stringPreferencesKey(raw.name)
                val current = p.pins(key)
                if (session in current) write(p, key, current - session)
            }
        }
    }

    private fun write(p: androidx.datastore.preferences.core.MutablePreferences, key: Preferences.Key<String>, list: List<String>) {
        if (list.isEmpty()) p.remove(key) else p[key] = json.encodeToString(serializer, list)
    }

    companion object {
        const val MAX_PER_BOT = 20
        const val MAX_STORED = 100
    }
}

/**
 * Pinned chats first in pin order, then the rest in their given order. A pin whose chat is not in [list] is ignored
 * (and kept: the list shows only the newest chats, so an older pinned chat is not shown until it is among them).
 */
fun <T> pinnedFirst(list: List<T>, pinned: List<String>, id: (T) -> String): List<T> {
    if (pinned.isEmpty()) return list
    val byId = list.associateBy(id)
    val head = pinned.mapNotNull { byId[it] }
    val headIds = head.map(id).toSet()
    return head + list.filterNot { id(it) in headIds }
}
