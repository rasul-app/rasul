package app.rasul.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import app.rasul.data.PendingAttachment
import app.rasul.data.RunInputLimits
import app.rasul.data.dataUrlBytes
import app.rasul.data.decodeSampled
import app.rasul.data.isTextFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Picked attachments fully processed, or the one specific reason the pick failed. */
sealed interface AttachLoad {
    data class Ok(val items: List<PendingAttachment>) : AttachLoad
    data class Failed(val failure: ChatFailure) : AttachLoad
}

sealed interface PhotoResult {
    data class Ok(val jpeg: ByteArray, val thumb: ByteArray) : PhotoResult
    data object Unreadable : PhotoResult
    data object TooLarge : PhotoResult
}

/** What the chat ViewModel needs to turn picked URIs into staged attachments (platform impl: [ChatAttachmentLoader]). */
interface AttachmentLoader {
    /** [photoBudget] is the raw-byte cap each encoded photo must fit (RunInputLimits.photoBudget). */
    suspend fun loadPhotos(uris: List<Uri>, photoBudget: Int): AttachLoad
    suspend fun loadFile(uri: Uri): AttachLoad

    /** Re-encodes an already staged [photo] to fit [budget] raw bytes; null when it cannot. */
    suspend fun fit(photo: PendingAttachment.Photo, budget: Int): PendingAttachment.Photo?
}

private class OverLimit : Exception()

/** Read at most [max] bytes; throws [OverLimit] when the stream holds more. */
private fun readWithLimit(stream: InputStream, max: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    var total = 0
    while (true) {
        val n = stream.read(buf, 0, minOf(buf.size, max + 1 - total))
        if (n < 0) break
        total += n
        if (total > max) throw OverLimit()
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/** Picked media can be large; over this it is refused before decode even starts. */
private const val MAX_PICK_BYTES = 32 * 1024 * 1024

/** Encode passes tried in order until the JPEG fits the budget. */
private val PHOTO_ATTEMPTS = listOf(
    RunInputLimits.PHOTO_LONG_EDGE to RunInputLimits.PHOTO_JPEG_QUALITY,
    RunInputLimits.PHOTO_LONG_EDGE to 70,
    RunInputLimits.PHOTO_LONG_EDGE to 60,
    RunInputLimits.PHOTO_FALLBACK_LONG_EDGE to 60,
)

/**
 * Downscale + re-encode one image until its JPEG is within [maxBytes]: long edge
 * [RunInputLimits.PHOTO_LONG_EDGE] at quality 80, then 70 and 60, and last
 * [RunInputLimits.PHOTO_FALLBACK_LONG_EDGE] at 60. A fresh JPEG carries no EXIF — GPS/location
 * metadata is gone by construction; the orientation flag is applied to pixels first so it is not lost.
 */
internal fun reencodePhoto(source: ByteArray, maxBytes: Int = RunInputLimits.MAX_PHOTO_BYTES): PhotoResult {
    val decoded = decodeSampled(source, RunInputLimits.PHOTO_LONG_EDGE) ?: return PhotoResult.Unreadable
    val oriented = applyOrientation(decoded, readOrientation(source))
    var jpeg = ByteArray(0)
    var fits = false
    for ((edge, quality) in PHOTO_ATTEMPTS) {
        jpeg = ByteArrayOutputStream().also {
            scaleTo(oriented, edge).compress(Bitmap.CompressFormat.JPEG, quality, it)
        }.toByteArray()
        if (jpeg.size <= maxBytes) { fits = true; break }
    }
    if (!fits) return PhotoResult.TooLarge
    val thumb = scaleTo(oriented, RunInputLimits.THUMB_LONG_EDGE)
        .let { scaled -> ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, RunInputLimits.THUMB_JPEG_QUALITY, it) }.toByteArray() }
    return PhotoResult.Ok(jpeg, thumb)
}

private fun readOrientation(source: ByteArray): Int = try {
    ExifInterface(ByteArrayInputStream(source)).getAttributeInt(
        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
} catch (_: Exception) {
    ExifInterface.ORIENTATION_NORMAL
}

private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    val m = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        else -> return bitmap
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
}

private fun scaleTo(bitmap: Bitmap, maxEdge: Int): Bitmap {
    val longEdge = maxOf(bitmap.width, bitmap.height)
    if (longEdge <= maxEdge) return bitmap
    val ratio = maxEdge.toFloat() / longEdge
    return Bitmap.createScaledBitmap(
        bitmap,
        (bitmap.width * ratio).toInt().coerceAtLeast(1),
        (bitmap.height * ratio).toInt().coerceAtLeast(1),
        true,
    )
}

/** Chip/bubble decode of the small JPEG made at pick time. */
internal fun decodeThumb(jpeg: ByteArray): Bitmap? =
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)

/** Bubble decode of a stored `data:image/...` URL from history; sampled down for memory. */
internal fun decodeRemoteThumb(dataUrl: String): Bitmap? =
    dataUrlBytes(dataUrl)?.let { decodeSampled(it, RunInputLimits.THUMB_LONG_EDGE) }

private fun decodeUtf8(bytes: ByteArray): String? = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
} catch (_: CharacterCodingException) {
    null
}

/** Turns picker URIs into staged attachments on a worker dispatcher; never logs content. */
class ChatAttachmentLoader(private val context: Context) : AttachmentLoader {

    override suspend fun loadPhotos(uris: List<Uri>, photoBudget: Int): AttachLoad = withContext(Dispatchers.IO) {
        val items = mutableListOf<PendingAttachment>()
        for (uri in uris.take(RunInputLimits.MAX_PHOTOS)) {
            val bytes = try {
                context.contentResolver.openInputStream(uri)?.use { readWithLimit(it, MAX_PICK_BYTES) }
                    ?: return@withContext AttachLoad.Failed(ChatFailure.PhotoUnreadable)
            } catch (e: OverLimit) {
                return@withContext AttachLoad.Failed(ChatFailure.PhotoTooLarge)
            } catch (e: Exception) {
                return@withContext AttachLoad.Failed(ChatFailure.PhotoUnreadable)
            }
            when (val r = reencodePhoto(bytes, photoBudget)) {
                PhotoResult.Unreadable -> return@withContext AttachLoad.Failed(ChatFailure.PhotoUnreadable)
                PhotoResult.TooLarge -> return@withContext AttachLoad.Failed(ChatFailure.PhotoTooLarge)
                is PhotoResult.Ok -> items += PendingAttachment.Photo(PendingAttachment.newKey(), r.jpeg, r.thumb)
            }
        }
        AttachLoad.Ok(items)
    }

    override suspend fun fit(photo: PendingAttachment.Photo, budget: Int): PendingAttachment.Photo? =
        withContext(Dispatchers.IO) {
            (reencodePhoto(photo.jpeg, budget) as? PhotoResult.Ok)?.let { photo.copy(jpeg = it.jpeg) }
        }

    override suspend fun loadFile(uri: Uri): AttachLoad = withContext(Dispatchers.IO) {
        val name = displayName(uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
            ?: "file"
        val mime = try {
            context.contentResolver.getType(uri)
        } catch (_: Exception) {
            null
        }
        if (!isTextFile(mime, name)) return@withContext AttachLoad.Failed(ChatFailure.FileTypeUnsupported)
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { readWithLimit(it, RunInputLimits.MAX_FILE_BYTES) }
                ?: return@withContext AttachLoad.Failed(ChatFailure.FileUnreadable)
        } catch (e: OverLimit) {
            return@withContext AttachLoad.Failed(ChatFailure.FileTooLarge)
        } catch (e: Exception) {
            return@withContext AttachLoad.Failed(ChatFailure.FileUnreadable)
        }
        val text = decodeUtf8(bytes)
        if (text == null) {
            return@withContext AttachLoad.Failed(ChatFailure.FileTypeUnsupported)
        }
        AttachLoad.Ok(listOf(PendingAttachment.TextFile(PendingAttachment.newKey(), name, text)))
    }

    private fun displayName(uri: Uri): String? = try {
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index)?.takeIf { it.isNotBlank() } else null
        }
    } catch (_: Exception) {
        null
    }
}
