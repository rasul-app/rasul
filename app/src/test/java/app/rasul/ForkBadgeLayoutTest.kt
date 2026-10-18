package app.rasul

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import app.rasul.chat.ChatTopBar
import app.rasul.ui.theme.RasulTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp")
class ForkBadgeLayoutTest {
    @get:Rule val compose = createComposeRule()

    private val longName = "A very long chat name that cannot fit on one line of the header pill at all"

    private fun show(forkTitle: String) = compose.setContent {
        RasulTheme {
            Box(Modifier.fillMaxWidth()) {
                ChatTopBar(
                    title = longName, active = false, selectedModel = null, modelPickerEnabled = false,
                    onBack = {}, onChooseModel = {}, onShowUsage = {}, forkTitle = forkTitle,
                )
            }
        }
    }

    @Test fun blankParentTitleGivesTheNameAllTheSpaceTheBadgeDoesNotNeed() {
        show("")
        val pill = compose.onNodeWithTag("chat_header_name", useUnmergedTree = true).getUnclippedBoundsInRoot()
        // A long name beside a short badge must fill the pill's whole slot (about 246dp on a 390dp screen);
        // a half-split of the free space stops the name early and leaves the pill much narrower.
        assertTrue("pill width was ${pill.width}", pill.width >= 240.dp)
    }

    @Test fun badgeTruncatesFirstWhenBothAreLong() {
        show("A very long parent chat title that also cannot fit anywhere")
        val name = compose.onNodeWithText(longName, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val badge = compose.onNodeWithTag("chat_fork_title", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("badge ${badge.width} name ${name.width}", badge.width <= 100.dp && name.width >= 85.dp)
    }
}
