package app.rasul.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.util.Base64

/** `data:...;base64,` payload bytes, or null when the value is not a base64 data URL. */
internal fun dataUrlBytes(dataUrl: String): ByteArray? {
    val comma = dataUrl.indexOf(',')
    if (!dataUrl.startsWith("data:") || comma < 0) return null
    return try {
        Base64.getDecoder().decode(dataUrl.substring(comma + 1))
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** Decode [bytes] subsampled so the long edge lands near [maxEdge] without a full-size decode. */
internal fun decodeSampled(bytes: ByteArray, maxEdge: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

/** A stored `data:image/...` URL decoded and shrunk to a small JPEG, so history stays bounded. */
internal fun dataUrlJpegThumb(dataUrl: String, maxEdge: Int, quality: Int): ByteArray? =
    dataUrlBytes(dataUrl)?.let { bytes ->
        decodeSampled(bytes, maxEdge)?.let { bitmap ->
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
        }
    }
