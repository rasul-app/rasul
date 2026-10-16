package app.rasul.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.rasul.data.Connection
import app.rasul.ui.BotsListContent
import app.rasul.ui.ConnectionsState
import app.rasul.ui.theme.RasulTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Dark theme of the Bots list (no dark @Preview exists; main files are not touched in this slice). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h700dp")
class BotsDarkScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val sample = ConnectionsState.Loaded(
        listOf(
            Connection("a", "Hermes", "http://100.64.0.7:8642"),
            Connection("b", "Research", "http://100.64.0.7:8642", "research"),
            Connection("c", "Deploys", "http://100.64.0.9:8642", "deploys"),
        ),
    )

    private fun shot(lang: String) {
        RuntimeEnvironment.setQualifiers("+$lang-w390dp-h700dp")
        compose.setContent { RasulTheme(true) { BotsListContent(sample, {}, {}, {}, {}) } }
        compose.onRoot().captureRoboImage("src/test/screenshots/01-bots-$lang-dark.png")
    }

    @Test fun botsDarkEn() = shot("en")
    @Test fun botsDarkAr() = shot("ar")
}
