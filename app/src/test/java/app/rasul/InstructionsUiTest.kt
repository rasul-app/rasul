package app.rasul

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.rasul.bridge.*
import app.rasul.ui.theme.RasulTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h700dp")
class InstructionsUiTest {
    @get:Rule val compose = createComposeRule()
    private fun loaded(text: String = "Original", saved: String = text) = InstructionsState(
        loading = false, loaded = true, text = text, savedText = saved, revision = instructionsRevision(saved),
    )
    @Test fun directBotExplainsBridgeRequirementWithoutAnEditor() {
        compose.setContent { RasulTheme(false) { InstructionsContent(InstructionsState(direct = true, loading = false), "Sample") } }
        compose.onNodeWithText("Editing instructions needs Rasul on your PC. Connect this bot through a paired PC to edit them here.").assertExists()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("Save").assertDoesNotExist()
    }
    @Test fun loadedDraftHasAnEditorAndNamedSaveButton() {
        var saved = false
        compose.setContent { RasulTheme(false) { InstructionsContent(loaded("Draft", "Original"), "Sample", onSave = { saved = true }) } }
        compose.onNode(hasSetTextAction()).assertTextContains("Draft")
        compose.onNodeWithText("Save").performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).performClick()
        assertTrue(saved)
    }
    @Test fun conflictShowsSpecificMessageAndReloadConfirmsDraftDiscard() {
        var reloaded = false
        compose.setContent { RasulTheme(false) {
            InstructionsContent(loaded("Draft", "Original").copy(error = InstructionsError.Conflict), "Sample", onReload = { reloaded = true })
        } }
        compose.onNodeWithText("Changed on the PC since you opened it").performScrollTo().assertExists()
        compose.onNodeWithText("Reload").performScrollTo().performClick()
        assertFalse(reloaded)
        compose.onNodeWithText("Discard changes").performClick()
        assertTrue(reloaded)
    }
    @Test fun backWithUnsavedChangesAllowsKeepEditingOrDiscard() {
        var closed = false
        compose.setContent { RasulTheme(false) { InstructionsContent(loaded("Draft", "Original"), "Sample", onBack = { closed = true }) } }
        compose.onNodeWithContentDescription("Back").performClick()
        assertFalse(closed)
        compose.onNodeWithText("Keep editing").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Discard changes").performClick()
        assertTrue(closed)
    }
    @Test fun chatNameIsANamedButtonThatOpensInstructions() {
        var opened = false
        compose.setContent { RasulTheme(false) {
            app.rasul.chat.ChatTopBar("Sample", false, null, false, {}, {}, {}, onInstructions = { opened = true })
        } }
        compose.onNodeWithText("Sample").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).performClick()
        assertTrue(opened)
    }
    @Test fun systemBackWithUnsavedChangesShowsTheSameDiscardPrompt() {
        var dispatcher: androidx.activity.OnBackPressedDispatcher? = null
        var closed = false
        compose.setContent { RasulTheme(false) {
            dispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            InstructionsContent(loaded("Draft", "Original"), "Sample", onBack = { closed = true })
        } }
        compose.runOnIdle { checkNotNull(dispatcher).onBackPressed() }
        compose.onNodeWithText("Discard unsaved changes?").assertExists()
        assertFalse(closed)
        compose.onNodeWithText("Keep editing").performClick()
    }
    @Test fun savingBlocksEditingReloadAndBack() {
        var closed = false
        compose.setContent { RasulTheme(false) {
            InstructionsContent(loaded("Draft", "Original").copy(saving = true), "Sample", onBack = { closed = true })
        } }
        compose.onNodeWithText("Draft").assertIsNotEnabled()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithContentDescription("Back").assertIsNotEnabled()
        compose.onNodeWithText("Reload").performScrollTo().assertIsNotEnabled()
        assertFalse(closed)
    }
    @Test
    @Config(qualifiers = "ar-w390dp-h700dp")
    fun arabicAtLargeFontKeepsActionsReachableAndRightToLeft() {
        var saved = false
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 2f),
                androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl,
            ) { RasulTheme(true) { InstructionsContent(loaded("تعليمات جديدة", ""), "مثال", onSave = { saved = true }) } }
        }
        compose.onNodeWithText("التعليمات").assertExists()
        compose.onNodeWithText("حفظ").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("إعادة التحميل").performScrollTo().assertIsDisplayed()
        assertTrue(saved)
    }
    @Test fun chatNameHasAnAccessibleFallbackWhileItsNameIsLoading() {
        var opened = false
        compose.setContent { RasulTheme(false) {
            app.rasul.chat.ChatTopBar("", false, null, false, {}, {}, {}, onInstructions = { opened = true })
        } }
        compose.onNodeWithContentDescription("Instructions")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).performClick()
        assertTrue(opened)
    }
}
