package app.rasul.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import app.rasul.bridge.*
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

@OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp")
class ApprovalsScreenshotTest(private val language: String, private val dark: Boolean, private val kind: String) {
    @get:Rule val compose = createComposeRule()
    private fun item(id: String, surface: String, allowPermanent: Boolean = true) = ApprovalItemDto(
        id, "sample-session", "default", surface,
        "rclone move ./reports drive:backups --dry-run", "Sync the new reports folder to cloud storage.",
        true, allowPermanent, 1_900_000_000.0)

    @Test fun capture() {
        RuntimeEnvironment.setQualifiers("+$language-w390dp-h844dp")
        val cards = when (kind) {
            "empty" -> emptyList()
            "sending" -> listOf(
                ApprovalCardState(item("a".repeat(32), "gateway")),
                ApprovalCardState(item("b".repeat(32), "shell", allowPermanent = false), sending = true),
            )
            else -> listOf(
                ApprovalCardState(item("a".repeat(32), "gateway")),
                ApprovalCardState(item("b".repeat(32), "files")),
            )
        }
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                RasulTheme(dark) {
                    ApprovalsContent(ApprovalsScreenState(loading = false, cards = cards),
                        if (language == "ar") "البحث" else "Research", nowEpochSec = 1_899_999_700)
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/approvals-$kind-$language${if (dark) "-dark" else ""}.png")
    }

    companion object {
        @JvmStatic @Parameters(name = "{0}, dark={1}, {2}")
        fun cases(): List<Array<Any>> = listOf("en", "ar").flatMap { language ->
            listOf(false, true).flatMap { dark -> listOf("cards", "empty", "sending").map { arrayOf(language, dark, it) } }
        }
    }
}
