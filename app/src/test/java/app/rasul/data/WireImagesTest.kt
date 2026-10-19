package app.rasul.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class WireImagesTest {
    private fun png(w: Int, h: Int): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { color = Color.rgb(200, 80, 40) })
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Test fun decodedLongSideLandsExactlyOnTheLimit() {
        val out = decodeSampled(png(4000, 3000), 1280)
        assertNotNull(out)
        assertEquals(1280, maxOf(out!!.width, out.height))
        assertEquals(1280, decodeSampled(png(2600, 100), 1280)!!.width)
        assertEquals(1280, decodeSampled(png(2561, 100), 1280)!!.width)
        assertEquals(1600, decodeSampled(png(3201, 100), 1600)!!.width)
        assertEquals(640, decodeSampled(png(1500, 1000), 640)!!.width)
    }

    @Test fun floatRoundingNeverLeavesTheLongSideOnePixelShort() {
        // 2249 * (1600f / 2249) and 2128 * (1280f / 2128) come out just below the limit in float math
        assertEquals(1600, decodeSampled(png(2249, 100), 1600)!!.width)
        assertEquals(1280, decodeSampled(png(2128, 100), 1280)!!.width)
        val wide = decodeSampled(png(3201, 2000), 1600)!!
        assertEquals(1600, maxOf(wide.width, wide.height))
        assertEquals(1280, decodeSampled(png(2600, 100), 1280)!!.width)
        val tall = decodeSampled(png(100, 2249), 1600)!!
        assertEquals(1600, tall.height)
    }

    @Test fun smallImageIsNotShrunk() {
        val out = decodeSampled(png(300, 200), 1280)!!
        assertEquals(300, out.width)
    }

    @Test fun sampleSizeHandlesHugeAndBoundaryImages() {
        assertEquals(16, sampleSizeFor(30000, 30000, 1280))
        // the sampled decode stays below twice the limit, so a huge image never decodes at full size
        for (side in listOf(30000, 12000, 4000, 2561, 1281)) {
            val sample = sampleSizeFor(side, side, 1280)
            assertTrue("side $side sample $sample", (side + sample - 1) / sample < 2 * 1280)
        }
        assertEquals(2, sampleSizeFor(4000, 3000, 1280))
        assertEquals(1, sampleSizeFor(1280, 100, 1280))
        assertEquals(1, sampleSizeFor(1281, 100, 1280))
        assertEquals(1, sampleSizeFor(2559, 10, 1280))
        assertEquals(2, sampleSizeFor(2560, 10, 1280))
        assertEquals(2, sampleSizeFor(2561, 10, 1280))
        assertEquals(1, sampleSizeFor(1, 1, 1280))
    }
}
