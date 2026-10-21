package app.rasul

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.rasul.bridge.*
import app.rasul.ui.*
import app.rasul.ui.theme.RasulTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h700dp")
class CreateBotUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun everyBridgeHasItsOwnNamedButtonAndTheDirectActionStillOpens() {
        val creates = mutableListOf<String>()
        var opened: String? = null
        compose.setContent { RasulTheme(false) {
            BotsListContent(ConnectionsState.Loaded(listOf(app.rasul.data.Connection("direct", "Direct", ""))), {}, {}, {}, {},
                onOpen = { opened = it }, bridges = BridgeBotsState(listOf(BridgeGroup("first", "First"), BridgeGroup("second", "Second"))),
                onBridgeCreate = { creates += it })
        } }
        for (name in listOf("First", "Second")) {
            compose.onNodeWithText("New bot on $name").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).performClick()
        }
        compose.onNodeWithText("Direct", substring = false).performScrollTo().performClick()
        assertEquals(listOf("first", "second"), creates)
        assertEquals("direct", opened)
    }
    @Test fun formHasLabelledNameRadioTemplateAndConfirmButton() {
        var selected: String? = null
        var sent = false
        compose.setContent { RasulTheme(false) {
            CreateBotContent(CreateBotState("new-profile", listOf(ProfileTemplate("blank", "Blank")), "blank", false), "Bridge",
                onTemplate = { selected = it }, onSubmit = { sent = true })
        } }
        compose.onNodeWithText("Bot name").assertExists()
        compose.onNode(hasText("Blank") and hasClickAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).performClick()
        compose.onNodeWithText("Confirm and create").performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).performClick()
        assertEquals("blank", selected)
        assertTrue(sent)
    }
    @Test fun blankOwnerTemplateLabelFallsBackToItsIdForTheSelectableName() {
        compose.setContent { RasulTheme(false) {
            CreateBotContent(CreateBotState("new-profile", listOf(ProfileTemplate("blank", "")), "blank", false), "Bridge")
        } }
        compose.onNode(hasText("blank") and hasClickAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
    }
    @Test fun unknownTemplateOffersReloadForTheRetainedAttempt() {
        var reloaded = false
        compose.setContent { RasulTheme(false) {
            CreateBotContent(CreateBotState("new-profile", listOf(ProfileTemplate("blank", "Blank")), "blank", false,
                CreateBotPhase.Failed, CreateBotError.TemplateUnknown, hasAttempt = true), "Bridge", onLoadTemplates = { reloaded = true })
        } }
        compose.onNodeWithText("Reload templates").performScrollTo().performClick()
        assertTrue(reloaded)
    }
    @Test fun doneOperationOpensOnlyAfterTheReadyBotArrivesInTheLiveList() {
        val store = androidx.lifecycle.ViewModelStore()
        val vm = CreateBotViewModel(service = { object : CreateBotApi {
            override suspend fun templates() = listOf(ProfileTemplate("blank", "Blank"))
            override suspend fun create(request: CreateBotRequest) = java.util.UUID.randomUUID().toString().replace("-", "")
            override suspend fun operation(id: String) = CreateBotOperation("done", "new-profile")
        } })
        store.put("create", vm)
        var group by mutableStateOf(BridgeGroup("sample", "Bridge", loading = false))
        var opened: String? = null
        try {
            compose.setContent { RasulTheme(false) { CreateBotScreen(vm, group, { success, _ -> success() }, true, {}, { opened = it }) } }
            compose.waitUntil { !vm.state.value.loading }
            compose.runOnIdle { vm.name("new-profile"); vm.submit { success, _ -> success() } }
            compose.waitUntil { vm.state.value.phase == CreateBotPhase.WaitingForBot }
            assertNull(opened)
            compose.runOnIdle { group = group.copy(profiles = listOf(BridgeProfile("new-profile", "new-profile", "new-profile", false, false))) }
            compose.waitForIdle(); assertNull(opened)
            compose.runOnIdle { group = group.copy(profiles = listOf(BridgeProfile("new-profile", "new-profile", "new-profile", true, false))) }
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals(CreateBotPhase.Complete, vm.state.value.phase)
                assertEquals("new-profile", opened)
            }
        } finally { compose.runOnIdle { store.clear() } }
    }
    @Test fun completedOperationCanLeaveWhileLiveListReconnects() {
        var left = false
        compose.setContent { RasulTheme(false) {
            CreateBotContent(CreateBotState("new-profile", listOf(ProfileTemplate("blank", "Blank")), "blank", false,
                CreateBotPhase.WaitingForBot, CreateBotError.Unreachable, "new-profile", true), "Bridge", onBack = { left = true })
        } }
        compose.onNodeWithText("Bot created. Waiting for the live list…").assertExists()
        compose.onNodeWithText("Cannot reach this PC. Check that it is online.").assertExists()
        compose.onNodeWithText("Back").performScrollTo().performClick()
        assertTrue(left)
    }
    @Test fun creatingDisablesTheNameAndTemplateAndShowsProgress() {
        compose.setContent { RasulTheme(false) {
            CreateBotContent(CreateBotState("new-profile", listOf(ProfileTemplate("blank", "Blank")), "blank", false, CreateBotPhase.Creating, hasAttempt = true), "Bridge")
        } }
        compose.onNodeWithText("Bot name").assertIsNotEnabled()
        compose.onNodeWithText("Blank").assertHasNoClickAction()
        compose.onNodeWithText("Creating bot… Waiting for your PC.").assertExists()
        compose.onNodeWithText("Confirm and create").assertDoesNotExist()
    }
}
