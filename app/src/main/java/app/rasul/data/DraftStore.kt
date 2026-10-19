package app.rasul.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Hex of the UTF-8 bytes: safe inside a preference key name, and never contains '_'. */
internal fun hexOf(value: String) = value.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }

internal fun unhex(enc: String): String? {
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

/**
 * Unsent composer text, one per chat (bot id + profile + session id), encrypted with the same cipher as the API keys.
 * The key names carry ids only; the text exists in the DataStore as ciphertext. A `draft_<bot>_` prefix means
 * [clearBot] drops a removed PC or bridge's drafts. Lives in the chat-extras DataStore, apart from run breadcrumbs.
 */
class DraftStore(
    private val ds: DataStore<Preferences>,
    private val cipher: SecretCipher,
    private val now: () -> Long = System::currentTimeMillis,
    /** Keystore work (encrypt, decrypt) runs here; tests pass their own dispatcher. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private fun suffix(bot: String, profile: String?, session: String) = "${bot}_${profile.orEmpty()}_${hexOf(session)}"
    private fun textKey(suffix: String) = stringPreferencesKey("draft_$suffix")
    private fun atKey(suffix: String) = longPreferencesKey("draftat_$suffix")
    private fun sentKey(suffix: String) = longPreferencesKey("draftsent_$suffix")

    /** Ciphertext -> first line, so a list refresh does not decrypt the same draft again. */
    private val lines = ConcurrentHashMap<String, String>()

    /** Null when there is no draft or it can no longer be decrypted (Keystore reset). */
    suspend fun load(bot: String, profile: String?, session: String): String? {
        val enc = ds.data.first()[textKey(suffix(bot, profile, session))] ?: return null
        return withContext(io) { decrypt(enc) }
    }

    /** Stores [text] cut to [MAX_CHARS]; blank text removes the draft. The oldest drafts go once there are more than [MAX_DRAFTS]. */
    suspend fun save(bot: String, profile: String?, session: String, text: String) {
        if (text.isBlank()) return clear(bot, profile, session)
        val enc = try {
            withContext(io) { cipher.encrypt(cut(text)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        val suffix = suffix(bot, profile, session)
        ds.edit { p ->
            p[textKey(suffix)] = enc
            p[atKey(suffix)] = now()
            val all = p.asMap().keys.filter { it.name.startsWith("draftat_") }
            if (all.size > MAX_DRAFTS) {
                val times = all.associateWith { p[longPreferencesKey(it.name)] ?: 0L }
                all.sortedBy { times.getValue(it) }.take(all.size - MAX_DRAFTS).forEach { old ->
                    p.remove(old)
                    p.remove(stringPreferencesKey("draft_" + old.name.removePrefix("draftat_")))
                }
            }
        }
    }

    /** Whether the server already took the first message of [session] (a "New chat" id): from then on it is a real chat. */
    suspend fun isAccepted(bot: String, profile: String?, session: String): Boolean =
        ds.data.first()[sentKey(suffix(bot, profile, session))] != null

    /** Remembers across restarts that [session] was accepted. The oldest marks go once there are more than [MAX_DRAFTS]. */
    suspend fun markAccepted(bot: String, profile: String?, session: String) {
        val suffix = suffix(bot, profile, session)
        ds.edit { p ->
            p[sentKey(suffix)] = now()
            val all = p.asMap().keys.filter { it.name.startsWith("draftsent_") }
            if (all.size > MAX_DRAFTS) {
                val times = all.associateWith { p[longPreferencesKey(it.name)] ?: 0L }
                all.sortedBy { times.getValue(it) }.take(all.size - MAX_DRAFTS).forEach { p.remove(it) }
            }
        }
    }

    suspend fun clear(bot: String, profile: String?, session: String) {
        val suffix = suffix(bot, profile, session)
        ds.edit { p ->
            p.remove(textKey(suffix))
            p.remove(atKey(suffix))
        }
    }

    /** A removed PC or bridge leaves no drafts ([prefix] is the bot id followed by `_`, or `bridge:<id>:`). */
    suspend fun clearBot(prefix: String) {
        ds.edit { p ->
            p.asMap().keys.filter { listOf("draft_", "draftat_", "draftsent_").any { head -> it.name.startsWith(head + prefix) } }
                .toList().forEach { p.remove(it) }
        }
    }

    /** A deleted chat leaves no draft behind, whichever bot it was under. */
    suspend fun clearSession(session: String) {
        val tail = "_" + hexOf(session)
        ds.edit { p ->
            p.asMap().keys.filter { listOf("draft_", "draftat_", "draftsent_").any { head -> it.name.startsWith(head) } && it.name.endsWith(tail) }
                .toList().forEach { p.remove(it) }
        }
    }

    /** Session id -> first line of its draft, for one bot; the chat list shows it under the chat. */
    fun firstLines(bot: String, profile: String?): Flow<Map<String, String>> {
        val prefix = "draft_${bot}_${profile.orEmpty()}_"
        return ds.data.map { p ->
            p.asMap().entries.mapNotNull { (key, value) ->
                if (!key.name.startsWith(prefix) || value !is String) return@mapNotNull null
                val session = unhex(key.name.removePrefix(prefix)) ?: return@mapNotNull null
                val line = lines[value] ?: decrypt(value)?.let(::firstLine)?.also {
                    if (lines.size > MAX_DRAFTS * 2) lines.clear()
                    lines[value] = it
                } ?: return@mapNotNull null
                session to line
            }.toMap()
        }.flowOn(io)
    }

    private fun decrypt(enc: String): String? = try {
        cipher.decrypt(enc)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        const val MAX_CHARS = 20_000
        const val MAX_DRAFTS = 200
        private const val LINE_CHARS = 200

        /** [text] cut to [MAX_CHARS], never between the two halves of an emoji. */
        fun cut(text: String): String {
            val t = text.take(MAX_CHARS)
            return if (t.isNotEmpty() && t.length < text.length && t.last().isHighSurrogate()) t.dropLast(1) else t
        }

        fun firstLine(text: String): String? =
            text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(LINE_CHARS)
    }
}
