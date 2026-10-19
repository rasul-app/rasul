package app.rasul.share

import android.content.Context
import android.net.Uri
import app.rasul.chat.AttachLoad
import app.rasul.chat.AttachmentLoader
import app.rasul.chat.ChatFailure
import app.rasul.data.PendingAttachment
import app.rasul.data.RunInputLimits
import app.rasul.security.AppLockState
import app.rasul.security.appLockBlocksContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Which of the shared URIs one send can carry. A send holds up to [RunInputLimits.MAX_PHOTOS] photos
 * OR one text file, never a mix, exactly like the attach button: the first URI decides the kind and
 * the first N of that kind are kept. [dropped] counts everything else.
 */
data class SharePlan(val photos: List<Uri>, val file: Uri?, val dropped: Int)

fun planShare(uris: List<Uri>, isImage: (Uri) -> Boolean): SharePlan {
    if (uris.isEmpty()) return SharePlan(emptyList(), null, 0)
    if (!isImage(uris.first())) return SharePlan(emptyList(), uris.first(), uris.size - 1)
    val photos = uris.filter(isImage).take(RunInputLimits.MAX_PHOTOS)
    return SharePlan(photos, null, uris.size - photos.size)
}

/**
 * A share after its content was read: [items] are the processed attachments (the same objects the
 * attach button stages), [failure] is why the kept item could not be staged. Nothing here refers to
 * a URI any more. It lives in memory only.
 */
data class PendingShare(
    val id: Long,
    val text: String,
    val items: List<PendingAttachment> = emptyList(),
    val failure: ChatFailure? = null,
    /** Items left out: over the limit, a different kind than the first, or refused URIs. */
    val dropped: Int = 0,
    val loading: Boolean = false,
    /** The chat route the user picked; set just before navigating, consumed by that chat. */
    val route: String? = null,
    /** The chat picked last time could not be opened; the user picks again. */
    val openFailed: Boolean = false,
) {
    /** Something that a chat can take: text or at least one staged item. */
    val canSend: Boolean get() = text.isNotEmpty() || items.isNotEmpty()
}

/** Turns an [IncomingShare] into a [PendingShare] through the loader the attach button uses. */
class ShareStager(
    private val loader: AttachmentLoader,
    private val isImage: suspend (Uri) -> Boolean,
) {
    suspend fun stage(id: Long, incoming: IncomingShare): PendingShare {
        // Types are read in order and only until planShare has what it keeps: a first item that is not a photo
        // decides the share alone, otherwise the lookups stop at the MAX_PHOTOS-th photo.
        val images = HashMap<Uri, Boolean>()
        var photos = 0
        for ((index, uri) in incoming.uris.withIndex()) {
            val image = isImage(uri)
            images[uri] = image
            if (!image && index == 0) break
            if (image && ++photos == RunInputLimits.MAX_PHOTOS) break
        }
        val plan = planShare(incoming.uris) { images[it] == true }
        val load = when {
            plan.photos.isNotEmpty() ->
                loader.loadPhotos(plan.photos, RunInputLimits.photoBudget(plan.photos.size.coerceIn(1, RunInputLimits.MAX_PHOTOS)))
            plan.file != null -> loader.loadFile(plan.file)
            else -> AttachLoad.Ok(emptyList())
        }
        return PendingShare(
            id = id,
            text = incoming.text,
            items = (load as? AttachLoad.Ok)?.items.orEmpty(),
            failure = (load as? AttachLoad.Failed)?.failure,
            dropped = plan.dropped + incoming.refused,
        )
    }
}

/** Image detection for the platform: the provider's own type, else the intent's, never a guess from the name. */
fun contentResolverIsImage(context: Context, intentType: String?): suspend (Uri) -> Boolean = { uri ->
    withContext(Dispatchers.IO) {
        val type = try { context.contentResolver.getType(uri) } catch (_: Exception) { null }
        (type ?: intentType)?.startsWith("image/", ignoreCase = true) == true
    }
}

/**
 * The one share waiting for the user, in memory only (a dying process drops it). Content is read as
 * soon as the intent arrives; the screens only show it once the app is unlocked.
 */
class ShareInbox {
    val pending = MutableStateFlow<PendingShare?>(null)
    private var nextId = 1L

    /** Starts reading [incoming] now. A newer share replaces an older one; a stale result is ignored. */
    fun receive(scope: CoroutineScope, incoming: IncomingShare, stager: ShareStager) {
        val id = synchronized(this) { nextId++ }
        pending.value = PendingShare(id, incoming.text, dropped = incoming.refused, loading = incoming.uris.isNotEmpty())
        if (incoming.uris.isEmpty()) return
        scope.launch {
            val staged = try {
                stager.stage(id, incoming)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                PendingShare(id, incoming.text, failure = ChatFailure.FileUnreadable, dropped = incoming.refused)
            }
            pending.update { if (it != null && it.id == id) staged.copy(route = it.route) else it }
        }
    }

    fun cancel() { pending.value = null }

    /** The picked destination; the chat at [route] takes the share over via [claim]. */
    fun choose(route: String) { pending.update { it?.copy(route = route, openFailed = false) } }

    /**
     * The chat at [route] could not be opened: the share goes back to the picker with a notice.
     * False when that route does not hold the share.
     */
    fun release(route: String): Boolean {
        while (true) {
            val current = pending.value ?: return false
            if (current.route != route) return false
            if (pending.compareAndSet(current, current.copy(route = null, openFailed = true))) return true
        }
    }

    /** Hands the share to the chat opened at [route] exactly once; any other route gets nothing. */
    fun claim(route: String): PendingShare? {
        while (true) {
            val current = pending.value ?: return null
            if (current.route != route) return null
            if (pending.compareAndSet(current, null)) return current
        }
    }
}

/** The Send-to screen opens only on an unlocked app: with the lock on, the share waits behind the lock screen. */
fun shareMayOpen(lock: AppLockState, share: PendingShare?): Boolean =
    share != null && lock.loaded && !appLockBlocksContent(lock)
