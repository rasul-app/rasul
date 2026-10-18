package app.rasul.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import app.rasul.data.HermesError
import app.rasul.files.FileCard
import app.rasul.files.FileFailure
import app.rasul.files.FileLink
import app.rasul.files.FileState
import app.rasul.ui.theme.Rasul
import app.rasul.ui.theme.RasulTheme
import app.rasul.ui.theme.RasulType
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

/** File card goldens, numbered 90: idle, downloading, done, error and the muted "not available" text. */
@OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp")
class FileCardScreenshotTest(private val language: String, private val dark: Boolean, private val kind: String) {
    @get:Rule val compose = createComposeRule()

    private val token = "k".repeat(43)

    @Test fun capture() {
        RuntimeEnvironment.setQualifiers("+$language-w390dp-h844dp")
        val ar = language == "ar"
        val name = if (ar) "تقرير الربع الأول.pdf" else "quarterly-report.pdf"
        val size = if (ar) "1.2 ميغابايت" else "1.2 MB"
        val link = FileLink(token, name, size)
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides if (ar) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                RasulTheme(dark) {
                    Column(
                        Modifier.fillMaxSize().background(Rasul.colors.bubbleAssistant).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        when (kind) {
                            "idle" -> card(link, FileState.Idle)
                            "downloading" -> card(link, FileState.Downloading(512 * 1024, 1_258_291))
                            "done" -> {
                                card(link, FileState.Done(File(name), name, "application/pdf", 1_258_291))
                                card(FileLink(token, "page.html", null), FileState.Done(File("page.html"), "page.html", "application/octet-stream", 2048))
                            }
                            "error" -> {
                                card(link, FileState.Failed(FileFailure.Expired))
                                card(link, FileState.Failed(FileFailure.Transport(HermesError.BridgeLinkDropped)))
                            }
                            else -> Text(
                                if (ar) "الملف غير متاح: ملاحظات.zip" else "File not available: notes.zip",
                                style = RasulType.bodySmall, color = Rasul.colors.muted,
                            )
                        }
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/90-filecard-$kind-$language${if (dark) "-dark" else ""}.png")
    }

    @androidx.compose.runtime.Composable
    private fun card(link: FileLink, state: FileState) =
        FileCard(link, state, null, onDownload = {}, onCancel = {}, onOpen = {}, onSave = {}, onShare = {})

    companion object {
        @JvmStatic @Parameters(name = "{0}, dark={1}, {2}")
        fun cases(): List<Array<Any>> = listOf("en", "ar").flatMap { language ->
            listOf(false, true).flatMap { dark ->
                listOf("idle", "downloading", "done", "error", "unavailable").map { arrayOf(language, dark, it) }
            }
        }
    }
}
