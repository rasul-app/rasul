package app.rasul.screens

import com.github.takahirom.roborazzi.captureRoboImage
import sergio.sastre.composable.preview.scanner.android.AndroidComposablePreviewScanner
import sergio.sastre.composable.preview.scanner.android.AndroidPreviewInfo
import sergio.sastre.composable.preview.scanner.core.preview.ComposablePreview
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.TimeZone
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders every @Preview in app.rasul (EN + AR, light) to app/src/test/screenshots on the JVM.
 * Preview functions are private, so includePrivatePreviews(). Size/locale come from each @Preview
 * (390 dp wide = phone preview width; height is the preview's own).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ScreenshotTest(private val preview: ComposablePreview<AndroidPreviewInfo>) {
    // Sessions/Jobs dates are formatted in the JVM default zone; pin UTC so PNGs do not depend on the host.
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
        preview.captureRoboImage(fileName(preview.methodName))
    }

    companion object {
        // Order of screens in the file names (nn-).
        private val order = listOf("Bots", "BotsEmpty", "Edit", "Sessions", "Jobs", "ResumeDialog", "More", "LanguageSheet")

        fun fileName(method: String): String {
            val lang = if (method.endsWith("Ar")) "ar" else "en"
            val screen = method.removeSuffix("Ar").removeSuffix("En")
            val n = when {
                screen.startsWith("Chat") -> 10
                screen == "Lock" -> 11
                screen == "LockSetting" -> 12
                screen == "ScanPermission" -> 13
                screen == "Scanning" -> 14
                screen == "CodeConfirm" -> 15
                screen == "BridgeBotsReady" -> 16
                screen == "BridgeBotsNotReady" -> 17
                screen == "BridgeBotsUnreachable" -> 18
                screen == "CreateBotEmpty" -> 19
                screen == "CreateBotInvalid" -> 20
                screen == "CreateBotCreating" -> 21
                screen == "CreateBotError" -> 22
                else -> order.indexOf(screen).let { if (it < 0) order.size else it } + 1
            }
            return "src/test/screenshots/%02d-%s-%s.png".format(n, screen.lowercase(), lang)
        }

        @JvmStatic
        @Parameters(name = "{0}")
        fun previews(): List<ComposablePreview<AndroidPreviewInfo>> =
            AndroidComposablePreviewScanner()
                .scanPackageTrees("app.rasul")
                .includePrivatePreviews()
                .getPreviews()
    }
}
