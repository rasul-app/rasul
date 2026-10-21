package app.rasul

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.rasul.bridge.CatalogSkillDto
import app.rasul.bridge.DesktopError
import app.rasul.bridge.DesktopSkillDto
import app.rasul.bridge.McpServerDto
import app.rasul.data.CapabilitiesDto
import app.rasul.data.CapabilityEndpointDto
import app.rasul.data.ActiveTarget
import app.rasul.data.HermesResult
import app.rasul.data.HermesTarget
import app.rasul.data.SkillDto
import app.rasul.data.ToolsetDto
import app.rasul.skills.SkillsHostApi
import app.rasul.skills.SkillsToolsScreen
import app.rasul.skills.SkillsToolsViewModel
import app.rasul.ui.theme.RasulTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** Drives the real screen through a ViewModel backed by fakes, so clicks exercise the whole flow. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h700dp")
class SkillsToolsUiTest {
    @get:Rule val compose = createComposeRule()
    private val sched = TestCoroutineScheduler()

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher(sched))
    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeHost : SkillsHostApi {
        override suspend fun capabilities(target: HermesTarget) = HermesResult.Ok(
            CapabilitiesDto(endpoints = mapOf("skills" to CapabilityEndpointDto(), "toolsets" to CapabilityEndpointDto())),
        )
        override suspend fun skills(target: HermesTarget) = HermesResult.Ok(listOf(SkillDto("legacy")))
        override suspend fun toolsets(target: HermesTarget) = HermesResult.Ok(emptyList<ToolsetDto>())
    }

    private val target = ActiveTarget.Ready("id", "PC", HermesTarget("http://example.invalid/", null, "k"))

    private fun show(vm: SkillsToolsViewModel) {
        compose.setContent { RasulTheme(false) { SkillsToolsScreen(vm, {}) } }
        sched.advanceUntilIdle(); compose.waitForIdle()
    }

    /** Same screen under an ar-locale context so stringResource resolves values-ar. */
    private fun showArabic(vm: SkillsToolsViewModel) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val arabic = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("ar"))
        })
        compose.setContent {
            CompositionLocalProvider(LocalContext provides arabic) {
                RasulTheme(false) { SkillsToolsScreen(vm, {}) }
            }
        }
        sched.advanceUntilIdle(); compose.waitForIdle()
    }

    private fun vm(fake: FakeDesktop?): SkillsToolsViewModel {
        var clock = 0L
        return SkillsToolsViewModel(
            target, FakeHost(), fake?.let { f -> { f } },
            pause = { clock += it }, now = { clock },
        ).also { sched.advanceUntilIdle() }
    }

    private fun bridgeVm() = vm(FakeDesktop().apply {
        skills = listOf(
            DesktopSkillDto("review", "checks code", "dev", enabled = false),
            DesktopSkillDto("core", "keeps the bot running", "core", enabled = true, essential = true),
        )
        servers = listOf(McpServerDto("files", "stdio", enabled = true, source = "config", plugin = "fs-plugin"))
        catalog = listOf(CatalogSkillDto("official/notes", "Notes helper", "reads notes", installed = false))
    })

    @Test fun togglingASkillAsksForConfirmationBeforeSendingAnything() {
        val fake = FakeDesktop().apply { skills = listOf(DesktopSkillDto("review", enabled = false)) }
        show(vm(fake))
        compose.onNodeWithContentDescription("review").performClick()
        sched.advanceUntilIdle(); compose.waitForIdle()
        // The confirmation names the skill and explains the risk; nothing was sent yet.
        compose.onNodeWithText("Turn on review?").assertExists()
        compose.onNodeWithText("Turned-on skills can run actions on your PC.").assertExists()
        assertTrue(fake.toggles.isEmpty())
        compose.onNodeWithText("Turn on").performClick()
        sched.advanceUntilIdle(); compose.waitForIdle()
        assertEquals(listOf("review" to true), fake.toggles)
        assertEquals(2, fake.skillsReads)
    }

    @Test fun essentialSkillsExplainWhyAndDoNotSwitch() {
        show(bridgeVm())
        compose.onNodeWithText("Essential skills stay on.").assertExists()
        compose.onNodeWithContentDescription("core").assertIsNotEnabled()
    }

    @Test fun installingFromTheCatalogConfirmsTheSkillNameAndWarnsAboutDownloadedCode() {
        val fake = FakeDesktop().apply {
            skills = listOf(DesktopSkillDto("core", enabled = true, essential = true))
            catalog = listOf(CatalogSkillDto("official/notes", "Notes helper", "reads notes", installed = false))
        }
        show(vm(fake))
        compose.onNodeWithText("Add skill").performClick()
        sched.advanceUntilIdle(); compose.waitForIdle()
        // Catalog shows official entries only; the install tap opens a named confirmation, not a request.
        compose.onNodeWithText("Notes helper").assertExists()
        compose.onNodeWithText("Install").performClick()
        compose.onNodeWithText("Install Notes helper?").assertExists()
        compose.onNodeWithText("This downloads code that your bots can run on your PC.").assertExists()
        assertTrue(fake.installs.isEmpty())
        // Two "Install" texts exist now (sheet row + dialog); the dialog one sits beside "Cancel".
        compose.onNode(hasText("Install").and(hasAnySibling(hasText("Cancel")))).performClick()
        sched.advanceUntilIdle(); compose.waitForIdle()
        assertEquals(listOf("official/notes"), fake.installs)
    }

    @Test fun theCatalogHasNoFreeTextOrUrlEntry() {
        show(bridgeVm())
        compose.onNodeWithText("Add skill").performClick()
        sched.advanceUntilIdle(); compose.waitForIdle()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test fun directBotsShowOneBridgeLineAndNoSwitches() {
        show(vm(null))
        compose.onNodeWithText("Skill switches, installs and MCP servers need Rasul on your PC.").assertExists()
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
        compose.onNodeWithText("Add skill").assertDoesNotExist()
    }

    @Test fun desktopUnavailableShowsTheApiListReadOnlyUnderTheNotice() {
        val fake = FakeDesktop().apply { skillsError = DesktopError.Unavailable }
        show(vm(fake))
        compose.onNodeWithText("Open the Rasul desktop app on your PC to see this").assertExists()
        compose.onNodeWithText("legacy").assertExists()
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
    }

    @Test fun everySwitchIsDisabledWhileAnotherActionRuns() {
        val fake = FakeDesktop().apply {
            skills = listOf(DesktopSkillDto("review", enabled = false), DesktopSkillDto("notes", enabled = true))
        }
        show(vm(fake))
        compose.onNodeWithContentDescription("review").performClick()
        compose.onNodeWithText("Turn on").performClick()
        compose.waitForIdle()
        // The PUT is still queued on the test scheduler, so busySkill stays set.
        compose.onNodeWithContentDescription("review").assertIsNotEnabled()
        compose.onNodeWithContentDescription("notes").assertIsNotEnabled()
        sched.advanceUntilIdle(); compose.waitForIdle()
        assertEquals(listOf("review" to true), fake.toggles)
        compose.onNodeWithContentDescription("notes").assertIsEnabled()
    }

    @Test fun everySwitchIsDisabledWhileAnInstallIsRunning() {
        val fake = FakeDesktop().apply {
            skills = listOf(DesktopSkillDto("review", enabled = false))
            catalog = listOf(CatalogSkillDto("official/notes", "Notes helper", "reads notes", installed = false))
        }
        show(vm(fake))
        compose.onNodeWithText("Add skill").performClick()
        sched.advanceUntilIdle(); compose.waitForIdle()
        compose.onNodeWithText("Install").performClick()
        compose.onNode(hasText("Install").and(hasAnySibling(hasText("Cancel")))).performClick()
        compose.waitForIdle()
        // The install call is still queued on the test scheduler, so installing stays set.
        compose.onNodeWithContentDescription("review").assertIsNotEnabled()
        sched.advanceUntilIdle(); compose.waitForIdle()
        assertEquals(listOf("official/notes"), fake.installs)
    }

    @Test fun mcpServersListUnderSkillsWithPluginBadge() {
        show(bridgeVm())
        compose.onNodeWithText("MCP servers").assertExists()
        compose.onNodeWithText("files").performScrollTo().assertExists()
        compose.onNodeWithText("Plugin: fs-plugin", substring = true).assertExists()
    }

    @Test fun refreshDuringAToggleKeepsEverySwitchDisabled() {
        val fake = FakeDesktop().apply {
            skills = listOf(DesktopSkillDto("review", enabled = false), DesktopSkillDto("notes", enabled = true))
            mcpError = DesktopError.Unavailable
        }
        val viewModel = vm(fake)
        show(viewModel)
        compose.onNodeWithContentDescription("review").performClick()
        compose.onNodeWithText("Turn on").performClick()
        compose.waitForIdle()
        // The PUT is still queued on the test scheduler: Refresh and the section Retry must be disabled too.
        compose.onNodeWithText("Refresh").assertIsNotEnabled()
        compose.onNodeWithText("Retry").assertIsNotEnabled()
        // Even a direct load() must not lift the lock while the action runs.
        viewModel.load()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("notes").assertIsNotEnabled()
        sched.advanceUntilIdle(); compose.waitForIdle()
        assertEquals(listOf("review" to true), fake.toggles)
        compose.onNodeWithContentDescription("notes").assertIsEnabled()
        compose.onNodeWithText("Refresh").assertIsEnabled()
    }

    @Test fun refreshDuringAnInstallKeepsEverySwitchDisabled() {
        val fake = FakeDesktop().apply {
            skills = listOf(DesktopSkillDto("review", enabled = false))
            catalog = listOf(CatalogSkillDto("official/notes", "Notes helper", "reads notes", installed = false))
        }
        val viewModel = vm(fake)
        show(viewModel)
        compose.onNodeWithText("Add skill").performClick()
        sched.advanceUntilIdle(); compose.waitForIdle()
        compose.onNodeWithText("Install").performClick()
        compose.onNode(hasText("Install").and(hasAnySibling(hasText("Cancel")))).performClick()
        compose.waitForIdle()
        // The install call is still queued on the test scheduler, so installing stays set.
        compose.onNodeWithText("Refresh").assertIsNotEnabled()
        viewModel.load()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("review").assertIsNotEnabled()
        sched.advanceUntilIdle(); compose.waitForIdle()
        assertEquals(listOf("official/notes"), fake.installs)
    }

    @Test fun anOlderBridgeSaysToUpdateItInsteadOfJobOrSession() {
        show(vm(FakeDesktop().apply {
            skillsError = DesktopError.NotFound
            mcpError = DesktopError.NotFound
        }))
        // The skills notice and the MCP section both point at the bridge update, not a job/session 404.
        compose.onAllNodesWithText("Update Rasul on your PC to use this.").assertCountEquals(2)
        compose.onAllNodesWithText("The server does not have that job or session any more", substring = true).assertCountEquals(0)
        // The read-only v1 list still shows underneath.
        compose.onNodeWithText("legacy").assertExists()
    }

    @Test fun anOlderBridgeSaysToUpdateItInArabic() {
        showArabic(vm(FakeDesktop().apply {
            skillsError = DesktopError.NotFound
            mcpError = DesktopError.NotFound
        }))
        compose.onAllNodesWithText("حدّث رسول على حاسوبك لاستخدام هذه الميزة.").assertCountEquals(2)
    }

    @Test fun theAddSkillSheetSaysToUpdateAnOlderBridgeToo() {
        show(vm(FakeDesktop().apply {
            skillsError = DesktopError.NotFound
            catalogError = DesktopError.NotFound
        }))
        compose.onNodeWithText("Add skill").performClick()
        sched.advanceUntilIdle(); compose.waitForIdle()
        // The skills notice and the sheet both point at the bridge update, not a job/session 404.
        compose.onAllNodesWithText("Update Rasul on your PC to use this.").assertCountEquals(2)
        compose.onAllNodesWithText("The server does not have that job or session any more", substring = true).assertCountEquals(0)
    }
}
