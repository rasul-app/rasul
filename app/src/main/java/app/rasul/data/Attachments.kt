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

    /**
     * A file of any type sent through the bridge: its bytes never sit in memory here, they stream to the
     * PC while [state] moves from uploading to ready (the PC file id) or failed.
     */
    data class UploadFile(override val key: Long, val name: String, val size: Long, val state: UploadState) : PendingAttachment

    companion object {
        private val nextKey = AtomicLong(1)
        fun newKey(): Long = nextKey.getAndIncrement()
    }
}

/** Where one [PendingAttachment.UploadFile] is. */
sealed interface UploadState {
    /** [sent] of the file's bytes were accepted by the bridge. */
    data class Uploading(val sent: Long = 0) : UploadState

    /** The bridge holds the whole file as [fileId]; this is what `rasul_files` carries. */
    data class Ready(val fileId: String) : UploadState
    data class Failed(val failure: UploadFailure) : UploadState
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

    /** A file sent through the bridge: shown as a card with its name and size, without a download link. [size] is [SIZE_UNKNOWN] in history. */
    data class SentFile(val name: String, val size: Long) : SentAttachment
}

/** The size of a sent file that session history does not carry; the card then shows the name alone. */
const val SIZE_UNKNOWN = -1L

/** What the composer stages turn into on the sent bubble. */
fun PendingAttachment.toSent(): SentAttachment = when (this) {
    is PendingAttachment.Photo -> SentAttachment.LocalPhoto(thumb)
    is PendingAttachment.TextFile -> SentAttachment.NamedFile(name)
    is PendingAttachment.UploadFile -> SentAttachment.SentFile(name, size)
}
