package app.rasul.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.ui.test.onNodeWithText
import com.github.takahirom.roborazzi.captureScreenRoboImage
import app.rasul.ui.theme.RasulTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real plus-menu windows in both app languages and themes. */
@OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h700dp")
class BotsDarkScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun plusMenu(language: String, dark: Boolean = false) {
        RuntimeEnvironment.setQualifiers("+$language-w390dp-h700dp")
        // Freeze the popup's continuous location polling while capturing its real window.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RasulTheme(dark) {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize().background(app.rasul.ui.theme.Rasul.colors.bg)) {
                    app.rasul.ui.components.ScreenHeader(androidx.compose.ui.res.stringResource(app.rasul.R.string.tab_bots)) {
                        app.rasul.ui.PrimaryScreenActions({}, {}, {}, menuInitiallyExpanded = true)
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.onNodeWithText(context.getString(app.rasul.R.string.new_chat)).assertExists()
        compose.onNodeWithText(context.getString(app.rasul.R.string.new_bot)).assertExists()
        captureScreenRoboImage(ScreenshotTest.fileName(if (language == "ar") "PlusMenuAr" else "PlusMenuEn", dark))
    }

    @Test fun plusMenuEn() = plusMenu("en")
    @Test fun plusMenuAr() = plusMenu("ar")

    @Test fun plusMenuDarkEn() = plusMenu("en", true)
    @Test fun plusMenuDarkAr() = plusMenu("ar", true)
}
