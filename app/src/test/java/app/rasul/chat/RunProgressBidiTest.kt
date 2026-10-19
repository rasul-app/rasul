package app.rasul.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.LayoutDirection
import app.rasul.ui.theme.RasulTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** What is drawn, not only what is configured: the paragraph direction and where each letter lands, in the Arabic layout. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ar-w390dp-h844dp")
class RunProgressBidiTest {
    @get:Rule val compose = createComposeRule()

    private val t0 = 1_000_000L

    private fun showRtl(preview: String) {
        val run = ActiveRun(
            "r1", t0, StatusLabel.Tool("terminal"),
            progress = RunProgress(t0, RunPhase.Tool("terminal", preview), listOf(RunStep("terminal", preview, t0)), 1, t0),
        )
        compose.setContent {
            RasulTheme(dark = false) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    RunProgressCard(run, onStop = {}, onReconnect = {}, onRefresh = {}, onReview = null, clock = { t0 })
                }
            }
        }
        compose.waitForIdle()
    }

    private fun layoutOf(tag: String): TextLayoutResult {
        val layout = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config
            .getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layout)
        return layout.single()
    }

    /** One line, base direction Ltr, every character drawn to the right of the one before it. */
    private fun assertReadsLeftToRight(layout: TextLayoutResult, text: String) {
        assertEquals(text, layout.layoutInput.text.text)
        assertEquals(ResolvedTextDirection.Ltr, layout.multiParagraph.getParagraphDirection(0))
        val xs = text.indices.map { layout.getHorizontalPosition(it, usePrimaryDirection = true) }
        text.indices.forEach { assertEquals("char $it", ResolvedTextDirection.Ltr, layout.getBidiRunDirection(it)) }
        assertTrue("positions must grow left to right: $xs", xs.zipWithNext().all { (a, b) -> b > a })
    }

    @Test fun aCommandIsDrawnLeftToRightInTheArabicLayout() {
        showRtl("./gradlew test")
        assertReadsLeftToRight(layoutOf("run_progress_preview"), "./gradlew test")
        compose.onNodeWithTag("run_progress_steps_toggle").performClick()
        assertReadsLeftToRight(layoutOf("run_progress_step_preview"), "./gradlew test")
    }

    @Test fun aPreviewWithoutAnyLetterIsStillDrawnLeftToRight() {
        showRtl("./*")
        assertReadsLeftToRight(layoutOf("run_progress_preview"), "./*")
    }
}
