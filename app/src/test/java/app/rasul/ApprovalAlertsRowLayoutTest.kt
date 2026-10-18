package app.rasul

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import app.rasul.settings.LanguageOption
import app.rasul.settings.MoreContent
import app.rasul.ui.theme.RasulTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The approval-alerts settings row must show its whole title and battery line, never an ellipsis. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ApprovalAlertsRowLayoutTest {
    @get:Rule val compose = createComposeRule()

    private fun assertWholeText(node: SemanticsNodeInteraction, what: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.assertExists().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("$what has no layout", layouts.isNotEmpty())
        layouts.forEach { assertFalse("$what is cut off (lines=${it.lineCount})", it.hasVisualOverflow) }
    }

    private fun check(language: String, dark: Boolean, fontScale: Float) {
        RuntimeEnvironment.setQualifiers("+$language-w390dp-h900dp")
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.setContent {
            RasulTheme(dark) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    MoreContent(
                        emptyList(), null, LanguageOption.System, {}, {}, {},
                        showApprovalAlerts = true, approvalAlerts = true, showAppLockSetting = true,
                    )
                }
            }
        }
        val title = context.getString(R.string.approval_alerts)
        val summary = context.getString(R.string.approval_alerts_summary)
        assertWholeText(compose.onNodeWithText(title, useUnmergedTree = true), "$language dark=$dark x$fontScale title")
        assertWholeText(compose.onNodeWithText(summary, useUnmergedTree = true), "$language dark=$dark x$fontScale summary")
    }

    @Test fun enLight() = check("en", false, 1f)
    @Test fun enDark() = check("en", true, 1f)
    @Test fun arLight() = check("ar", false, 1f)
    @Test fun arDark() = check("ar", true, 1f)
    @Test fun enLightLargeFont() = check("en", false, 1.3f)
    @Test fun enDarkLargeFont() = check("en", true, 1.3f)
    @Test fun arLightLargeFont() = check("ar", false, 1.3f)
    @Test fun arDarkLargeFont() = check("ar", true, 1.3f)
}
