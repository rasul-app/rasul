package app.rasul.data

import java.util.concurrent.atomic.AtomicLong

/** An attachment staged in the composer, not sent yet. Created fully processed (bytes ready). */
sealed interface PendingAttachment {
    /** Stable key for removal; assigned at creation by the picker layer or a test. */
    val key: Long

    /** [jpeg] is the EXIF-stripped, downscaled image sent to the agent; [thumb] a small JPEG for chips/bubbles. */
    data class Photo(override val key: Long, val jpeg: ByteArray, val thumb: ByteArray) : PendingAttachment {
        override fun equals(other: Any?) = other is Photo && key == other.key
        override fun hashCode() = key.hashCode()
    }

    /** A small UTF-8 text file; sent as a text part prefixed with its [name]. */
    data class TextFile(override val key: Long, val name: String, val text: String) : PendingAttachment

    companion object {
        private val nextKey = AtomicLong(1)
        fun newKey(): Long = nextKey.getAndIncrement()
    }
}

/**
 * Marker kept in place of a trimmed history photo thumbnail ([SentAttachment.RemotePhoto.dataUrl]);
 * it never decodes, so every place that would draw the image shows the "Photo" label instead.
 */
const val REMOTE_PHOTO_OMITTED = "rasul:photo-omitted"

/** How an attachment appears on a sent (or history-loaded) chat message. */
sealed interface SentAttachment {
    /** A photo staged on this phone; [thumb] is the small JPEG made at pick time. */
    data class LocalPhoto(val thumb: ByteArray) : SentAttachment {
        override fun equals(other: Any?) = other is LocalPhoto && thumb.contentEquals(other.thumb)
        override fun hashCode() = thumb.contentHashCode()
    }

    /** A photo coming back in session history: the stored `data:image/...` URL. */
    data class RemotePhoto(val dataUrl: String) : SentAttachment

    /** A small text file. History rows show its name as the first line of the text part instead. */
    data class NamedFile(val name: String) : SentAttachment
}

/** What the composer stages turn into on the sent bubble. */
fun PendingAttachment.toSent(): SentAttachment = when (this) {
    is PendingAttachment.Photo -> SentAttachment.LocalPhoto(thumb)
    is PendingAttachment.TextFile -> SentAttachment.NamedFile(name)
}
