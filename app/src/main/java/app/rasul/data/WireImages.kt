package app.rasul.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.math.roundToInt

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

/** Largest power-of-two sample size that keeps the long edge of a [width] x [height] image at [maxEdge] or more. */
internal fun sampleSizeFor(width: Int, height: Int, maxEdge: Int): Int {
    var sample = 1
    while (maxOf(width, height) / (sample * 2) >= maxEdge) sample *= 2
    return sample
}

/**
 * Decode [bytes] so the long edge is exactly [maxEdge] (smaller images are left alone): a cheap subsampled decode that
 * stays at or above the limit, then one scale down. The sampled decode is under twice the limit, so a huge image
 * is never decoded at full size.
 */
internal fun decodeSampled(bytes: ByteArray, maxEdge: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val sample = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdge)
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return null
    val longEdge = maxOf(decoded.width, decoded.height)
    if (longEdge <= maxEdge) return decoded
    val ratio = maxEdge.toFloat() / longEdge
    val scaled = Bitmap.createScaledBitmap(
        decoded,
        (decoded.width * ratio).roundToInt().coerceIn(1, maxEdge),
        (decoded.height * ratio).roundToInt().coerceIn(1, maxEdge),
        true,
    )
    if (scaled !== decoded) decoded.recycle()
    return scaled
}

/** A stored `data:image/...` URL decoded and shrunk to a small JPEG, so history stays bounded. */
internal fun dataUrlJpegThumb(dataUrl: String, maxEdge: Int, quality: Int): ByteArray? =
    dataUrlBytes(dataUrl)?.let { bytes ->
        decodeSampled(bytes, maxEdge)?.let { bitmap ->
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
        }
    }
