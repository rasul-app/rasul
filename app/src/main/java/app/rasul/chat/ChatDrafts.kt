package app.rasul.chat

import app.rasul.data.DraftStore
import kotlinx.coroutines.CompletableDeferred

/**
 * Where one chat keeps its unsent composer text between visits. [save] and [clear] are queued and never block
 * the composer; a blank [save] clears. The text only ever leaves memory encrypted.
 */
interface ChatDrafts {
    suspend fun load(session: String): String?
    fun save(session: String, text: String)
    fun clear(session: String)

    /** The server took the first message of [session]; a new chat's shared draft was sent, so it is dropped here. */
    fun accepted(session: String) = Unit
}

/**
 * [DraftStore] bound to one bot; writes go through the application-wide ordered queue so they outlive the screen.
 * A "New chat" route gets a fresh session id on every tap, so until its first message is accepted ([newSession]) its
 * draft is kept under the bot's empty session id: the next New chat opens with the same text. The route keeps
 * `newSession=` after the first send, so "accepted" is stored ([DraftStore.markAccepted]) and every rebuild of the
 * same route (restart, new revision, failed load) keys the chat under its own id from then on.
 */
class StoredChatDrafts(
    private val store: DraftStore,
    private val bot: String,
    private val profile: String?,
    private val writer: OrderedWriter,
    private val newSession: String? = null,
) : ChatDrafts {
    /** Accepted by this object. */
    @Volatile private var sent = false

    /** Accepted before this object existed. Only a true answer is kept: a false one is read again, since the mark may still be queued. */
    @Volatile private var storedSent = false

    /** [sentNow] is [sent] as it was when the call was made, so a write queued before the send keeps its slot. */
    private suspend fun keyOf(session: String, sentNow: Boolean): String {
        if (newSession == null || session != newSession || sentNow) return session
        if (!storedSent) storedSent = store.isAccepted(bot, profile, newSession)
        return if (storedSent) session else NEW_CHAT_KEY
    }

    /** Runs behind every queued write (a mark, a save), so a rebuilt chat reads after the mark of the chat it replaces. */
    override suspend fun load(session: String): String? {
        val sentNow = sent
        val result = CompletableDeferred<String?>()
        writer.submit {
            try {
                result.complete(store.load(bot, profile, keyOf(session, sentNow)))
            } catch (e: Throwable) {
                result.completeExceptionally(e)
                throw e
            }
        }
        return result.await()
    }

    override fun save(session: String, text: String) {
        val sentNow = sent
        writer.submit { store.save(bot, profile, keyOf(session, sentNow), text) }
    }
    override fun clear(session: String) {
        val sentNow = sent
        writer.submit { store.clear(bot, profile, keyOf(session, sentNow)) }
    }

    override fun accepted(session: String) {
        if (newSession == null || sent || session != newSession) return
        sent = true
        writer.submit {
            // A rebuilt object may be told again about a chat that was accepted long ago: the shared slot is then
            // some other New chat's draft and stays.
            if (store.isAccepted(bot, profile, newSession)) return@submit
            store.markAccepted(bot, profile, newSession)
            store.clear(bot, profile, NEW_CHAT_KEY)
        }
    }

    private companion object {
        const val NEW_CHAT_KEY = ""
    }
}
