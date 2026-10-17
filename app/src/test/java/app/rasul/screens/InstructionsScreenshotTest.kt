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
class InstructionsScreenshotTest(private val language: String, private val dark: Boolean, private val kind: String) {
    @get:Rule val compose = createComposeRule()
    @Test fun capture() {
        RuntimeEnvironment.setQualifiers("+$language-w390dp-h844dp")
        val text = if (language == "ar") "أجب بوضوح وإيجاز.\nاطلب التوضيح عندما يكون الطلب غير واضح." else
            "Be clear and concise.\nAsk when a request is unclear."
        val state = if (kind == "direct") InstructionsState(direct = true, loading = false) else
            InstructionsState(loading = false, loaded = true, text = text, savedText = "", revision = instructionsRevision(""),
                error = if (kind == "conflict") InstructionsError.Conflict else null)
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                RasulTheme(dark) { InstructionsContent(state, if (language == "ar") "مثال" else "Sample") }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/instructions-$kind-$language${if (dark) "-dark" else ""}.png")
    }
    companion object {
        @JvmStatic @Parameters(name = "{0}, dark={1}, {2}")
        fun cases(): List<Array<Any>> = listOf("en", "ar").flatMap { language ->
            listOf(false, true).flatMap { dark -> listOf("editor", "conflict", "direct").map { arrayOf(language, dark, it) } }
        }
    }
}
