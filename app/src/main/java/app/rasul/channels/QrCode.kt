package app.rasul.channels

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Empty modules on every side, as the QR standard asks; scanners fail without them, also on a dark screen. */
internal const val QR_QUIET_ZONE = 4

/** Smallest bitmap edge in pixels; modules are scaled up by a whole number so edges stay sharp. */
internal const val QR_MIN_PIXELS = 600

/** The QR modules for [payload] including the quiet zone (true = dark), or null when it cannot be encoded. */
internal fun qrMatrix(payload: String): BitMatrix? = try {
    QRCodeWriter().encode(
        payload, BarcodeFormat.QR_CODE, 0, 0,
        mapOf<EncodeHintType, Any>(EncodeHintType.MARGIN to QR_QUIET_ZONE, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
    )
} catch (_: WriterException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

/** Black on white, always (a QR in the dark theme's colours is not read by most scanners). Safe to call off the main thread. */
internal fun qrBitmap(payload: String): Bitmap? {
    val matrix = qrMatrix(payload) ?: return null
    val scale = maxOf(1, (QR_MIN_PIXELS + matrix.width - 1) / matrix.width)
    val size = matrix.width * scale
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        val row = y / scale
        for (x in 0 until size) {
            pixels[y * size + x] = if (matrix.get(x / scale, row)) BLACK else WHITE
        }
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

private const val BLACK = 0xFF000000.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()

/**
 * Draws [payload] as a QR code. The bitmap is built on a background thread; previews build it in place so a
 * screenshot has it. [description] must not contain the payload: it is read aloud.
 */
@Composable
internal fun QrCode(payload: String, description: String, size: Dp, modifier: Modifier = Modifier) {
    val preview = LocalInspectionMode.current
    val bitmap by if (preview) {
        remember(payload) { mutableStateOf(qrBitmap(payload)) }
    } else {
        produceState<Bitmap?>(null, payload) { value = withContext(Dispatchers.Default) { qrBitmap(payload) } }
    }
    val frame = modifier.size(size).background(Color.White).semantics { contentDescription = description }
    val ready = bitmap
    if (ready != null) {
        Image(ready.asImageBitmap(), contentDescription = null, modifier = frame, filterQuality = FilterQuality.None)
    } else {
        Box(frame)
    }
}
