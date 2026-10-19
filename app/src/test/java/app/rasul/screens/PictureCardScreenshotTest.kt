package app.rasul.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.rasul.files.FileCard
import app.rasul.files.FileLink
import app.rasul.files.FileState
import app.rasul.files.PictureCard
import app.rasul.files.PicturePlaceholder
import app.rasul.ui.theme.Rasul
import app.rasul.ui.theme.RasulTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Picture goldens, numbered 130: loading, shown (landscape and tall) and the finished cards with Save and Share. */
@OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp")
class PictureCardScreenshotTest(private val language: String, private val dark: Boolean, private val kind: String) {
    @get:Rule val compose = createComposeRule()

    private val token = "k".repeat(43)

    private fun bitmap(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), AColor.rgb(250, 160, 80), AColor.rgb(60, 90, 200), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null
        paint.color = AColor.WHITE
        canvas.drawCircle(w * 0.3f, h * 0.35f, h * 0.12f, paint)
        paint.color = AColor.argb(200, 20, 40, 60)
        canvas.drawRect(w * 0.1f, h * 0.7f, w * 0.9f, h * 0.9f, paint)
        return bmp
    }

    @Test fun capture() {
        RuntimeEnvironment.setQualifiers("+$language-w390dp-h844dp")
        val ar = language == "ar"
        val photo = if (ar) "صورة الغروب.png" else "sunset.png"
        val pdf = if (ar) "تقرير الربع الأول.pdf" else "quarterly-report.pdf"
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides if (ar) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                RasulTheme(dark) {
                    Column(
                        Modifier.fillMaxSize().background(Rasul.colors.bubbleAssistant).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        when (kind) {
                            "loading" -> {
                                FileCard(FileLink(token, photo, "1.2 MB"), FileState.Downloading(512 * 1024, 1_258_291), null, {}, {}, {}, {}, {})
                                PicturePlaceholder(photo)
                            }
                            "shown" -> {
                                PictureCard(photo, bitmap(1200, 800).asImageBitmap(), {}, {}, {})
                                PictureCard(photo, bitmap(600, 1000).asImageBitmap(), {}, {}, {})
                            }
                            else -> {
                                FileCard(FileLink(token, pdf, null), FileState.Done(File(pdf), pdf, "application/pdf", 1_258_291), null, {}, {}, {}, {}, {})
                                FileCard(FileLink(token, photo, null), FileState.Done(File(photo), photo, "image/png", 523_000), bitmap(1200, 800).asImageBitmap(), {}, {}, {}, {}, {})
                            }
                        }
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/${number()}-filepic-$kind-$language${if (dark) "-dark" else ""}.png")
    }

    private fun number() = when (kind) { "loading" -> 130; "shown" -> 131; else -> 132 }

    companion object {
        @JvmStatic @Parameters(name = "{0}, dark={1}, {2}")
        fun cases(): List<Array<Any>> = listOf("en", "ar").flatMap { language ->
            listOf(false, true).flatMap { dark -> listOf("loading", "shown", "done").map { arrayOf(language, dark, it) } }
        }
    }
}
