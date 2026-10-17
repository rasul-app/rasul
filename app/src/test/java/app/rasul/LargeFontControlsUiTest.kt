package app.rasul

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.rasul.data.Load
import app.rasul.ui.LoadView
import app.rasul.ui.components.SegmentedSwitch
import app.rasul.ui.theme.RasulTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h700dp")
class LargeFontControlsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun segmentedEnglishAndArabicLabelsFitAndKeepLargeTouchTargets() {
        compose.setContent {
            RasulTheme(false) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    SegmentedSwitch(listOf("en" to "English", "ar" to "العربية"), "en", {})
                }
            }
        }

        listOf("English", "العربية").forEach { label ->
            val matcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton) and hasText(label)
            val option = compose.onNode(matcher).assertExists().fetchSemanticsNode()
            val labelNode = compose.onNodeWithText(label, useUnmergedTree = true).assertExists().fetchSemanticsNode()
            assertTrue("$label target height=" + option.boundsInRoot.height, option.boundsInRoot.height >= with(compose.density) { 48.dp.toPx() })
            assertTrue("$label target width=" + option.boundsInRoot.width, option.boundsInRoot.width >= with(compose.density) { 48.dp.toPx() })
            assertTrue("$label text bounds=" + labelNode.boundsInRoot + ", option=" + option.boundsInRoot, (labelNode.boundsInRoot.left >= option.boundsInRoot.left && labelNode.boundsInRoot.top >= option.boundsInRoot.top && labelNode.boundsInRoot.right <= option.boundsInRoot.right && labelNode.boundsInRoot.bottom <= option.boundsInRoot.bottom))
        }
    }

    @Test fun bridgeUnreachableRetryFitsAndKeepsFortyEightDpTargetAtLargeFontEnglish() =
        assertRetryFitsAtLargeFont("en")

    @Test fun bridgeUnreachableRetryFitsAndKeepsFortyEightDpTargetAtLargeFontArabic() =
        assertRetryFitsAtLargeFont("ar")

    private fun assertRetryFitsAtLargeFont(language: String) {
        RuntimeEnvironment.setQualifiers("+$language-w390dp-h700dp")
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val pcName = if (language == "ar") "حاسوب الاستوديو" else "Studio PC"
        compose.setContent {
            RasulTheme(false) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    Box(Modifier.fillMaxSize()) {
                        LoadView(Load.BridgeUnreachable(pcName), {}, content = {})
                    }
                }
            }
        }

        val message = context.getString(app.rasul.R.string.bridge_pc_unreachable, pcName)
        val retryText = context.getString(app.rasul.R.string.retry)
        compose.onNodeWithText(message).assertExists()
        val retryButton = compose.onNode(hasClickAction() and hasText(retryText)).assertExists().fetchSemanticsNode()
        val retryLabel = compose.onNodeWithText(retryText, useUnmergedTree = true).assertExists().fetchSemanticsNode()
        assertTrue("Retry target height=" + retryButton.boundsInRoot.height, retryButton.boundsInRoot.height >= with(compose.density) { 48.dp.toPx() })
        assertTrue("Retry text bounds=" + retryLabel.boundsInRoot + ", button=" + retryButton.boundsInRoot, (retryLabel.boundsInRoot.left >= retryButton.boundsInRoot.left && retryLabel.boundsInRoot.top >= retryButton.boundsInRoot.top && retryLabel.boundsInRoot.right <= retryButton.boundsInRoot.right && retryLabel.boundsInRoot.bottom <= retryButton.boundsInRoot.bottom))
    }
}
