package app.rasul.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.TimeZone
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sergio.sastre.composable.preview.scanner.android.AndroidComposablePreviewScanner
import sergio.sastre.composable.preview.scanner.android.AndroidPreviewInfo
import sergio.sastre.composable.preview.scanner.core.preview.ComposablePreview

/** Captures the voice-listening chat previews with a fixed animation clock. */
@OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class VoiceInputScreenshotTest(private val methodName: String, private val dark: Boolean) {
    @get:Rule val compose = createComposeRule()

    // Chat previews format times in the JVM default zone; pin UTC so PNGs do not depend on the host.
    private var savedZone: TimeZone? = null

    @Before
    fun pinUtc() {
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreZone() {
        savedZone?.let { TimeZone.setDefault(it) }
    }

    @Test
    fun capture() {
        compose.mainClock.autoAdvance = false
        val language = if (methodName.endsWith("Ar")) "ar" else "en"
        RuntimeEnvironment.setQualifiers("+${language}-w390dp-h844dp${if (dark) "-night" else "-notnight"}")
        val preview = previews().single { it.methodName == methodName }
        val themed = object : ComposablePreview<AndroidPreviewInfo> by preview {
            @Composable
            override fun invoke() {
                CompositionLocalProvider(LocalInspectionMode provides true) { preview() }
            }
        }
        compose.setContent { themed() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        captureScreenRoboImage(ScreenshotTest.fileName(methodName, dark))
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-dark={1}")
        fun cases(): List<Array<Any>> = listOf("VoiceListeningEn", "VoiceListeningAr")
            .flatMap { method -> listOf(arrayOf(method, false), arrayOf(method, true)) }

        private fun previews(): List<ComposablePreview<AndroidPreviewInfo>> =
            AndroidComposablePreviewScanner()
                .scanPackageTrees("app.rasul")
                .includePrivatePreviews()
                .getPreviews()
    }
}
