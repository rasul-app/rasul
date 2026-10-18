package app.rasul

import app.rasul.bridge.CatalogSkillDto
import app.rasul.bridge.DesktopError
import app.rasul.bridge.DesktopSkillDto
import app.rasul.bridge.McpServerDto
import app.rasul.data.CapabilitiesDto
import app.rasul.data.CapabilityEndpointDto
import app.rasul.data.ActiveTarget
import app.rasul.data.HermesError
import app.rasul.data.HermesResult
import app.rasul.data.HermesTarget
import app.rasul.data.SkillDto
import app.rasul.data.ToolsetDto
import app.rasul.skills.CatalogState
import app.rasul.skills.ListState
import app.rasul.skills.SectionError
import app.rasul.skills.SkillsHostApi
import app.rasul.skills.SkillsToolsState
import app.rasul.skills.SkillsToolsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private fun capsDto() = CapabilitiesDto(
    endpoints = mapOf("skills" to CapabilityEndpointDto(), "toolsets" to CapabilityEndpointDto()),
)

@OptIn(ExperimentalCoroutinesApi::class)
class SkillsToolsViewModelTest {
    private val sched = TestCoroutineScheduler()

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher(sched))
    @After fun tearDown() = Dispatchers.resetMain()
    private fun idle() = sched.advanceUntilIdle()

    private class FakeHost : SkillsHostApi {
        var caps: HermesResult<CapabilitiesDto> = HermesResult.Ok(capsDto())
        var skillsResult: HermesResult<List<SkillDto>> = HermesResult.Ok(listOf(SkillDto("legacy", "legacy desc")))
        var skillsReads = 0
        var toolsetsResult: HermesResult<List<ToolsetDto>> = HermesResult.Ok(listOf(ToolsetDto("tools", "Tools", null, true, true, listOf("t1"))))
        override suspend fun capabilities(target: HermesTarget) = caps
        override suspend fun skills(target: HermesTarget): HermesResult<List<SkillDto>> {
            skillsReads++
            return skillsResult
        }
        override suspend fun toolsets(target: HermesTarget) = toolsetsResult
    }

    private val target = ActiveTarget.Ready("id", "PC", HermesTarget("http://example.invalid/", null, "k"))

    private fun content(vm: SkillsToolsViewModel): SkillsToolsState.Content =
        vm.state.value as SkillsToolsState.Content

    private fun vm(fake: FakeDesktop? = FakeDesktop(), host: FakeHost = FakeHost()): SkillsToolsViewModel {
        var clock = 0L
        return SkillsToolsViewModel(
            target, host, fake?.let { f -> { f } },
            pollIntervalMs = 10_000, installWindowMs = 120_000,
            pause = { clock += it }, now = { clock },
        ).also { idle() }
    }

    @Test fun toggleSendsThePutThenRereadsTheSkillsList() {
        val fake = FakeDesktop().apply {
            skills = listOf(DesktopSkillDto("review", enabled = true), DesktopSkillDto("core", enabled = true, essential = true))
        }
        val viewModel = vm(fake)
        viewModel.toggle("review", false); idle()
        assertEquals(listOf("review" to false), fake.toggles)
        assertEquals(2, fake.skillsReads)
        val skills = (content(viewModel).skills as ListState.Ready).values
        assertEquals(listOf("review", "core"), skills.map { it.name })
        assertNull(content(viewModel).busySkill)
        assertNull(content(viewModel).actionError)
    }

    @Test fun toggleAppliesTheServerStateReadAfterThePut() {
        val fake = FakeDesktop().apply {
            skills = listOf(DesktopSkillDto("review", enabled = true))
        }
        val viewModel = vm(fake)
        fake.skills = listOf(DesktopSkillDto("review", enabled = false))
        viewModel.toggle("review", false); idle()
        val skills = (content(viewModel).skills as ListState.Ready).values
        assertEquals(false, skills.single().enabled)
    }

    @Test fun toggleFailureSurfacesTheDesktopErrorWithoutLosingTheList() {
        val fake = FakeDesktop().apply {
            skills = listOf(DesktopSkillDto("review", enabled = true))
        }
        val viewModel = vm(fake)
        fake.skillsError = DesktopError.Unavailable
        viewModel.toggle("review", false); idle()
        assertEquals(DesktopError.Unavailable, content(viewModel).actionError)
        assertEquals(listOf("review"), (content(viewModel).skills as ListState.Ready).values.map { it.name })
    }

    @Test fun installOnlyAcceptsIdentifiersFromTheMostRecentCatalogRead() {
        val fake = FakeDesktop().apply {
            catalog = listOf(
                CatalogSkillDto("official/notes", "Notes", installed = false),
                CatalogSkillDto("official/dev/review", "Review", installed = false),
            )
            skills = listOf(DesktopSkillDto("notes", enabled = true))
        }
        val viewModel = vm(fake)
        viewModel.openCatalog(); idle()
        // An identifier the catalog never returned must be refused, even if it matches the shape.
        viewModel.install(CatalogSkillDto("official/evil", "Evil")); idle()
        assertTrue(fake.installs.isEmpty())
        // So is a catalog-shaped entry whose name does not match the read.
        viewModel.install(CatalogSkillDto("official/notes", "Forged")); idle()
        assertTrue(fake.installs.isEmpty())
        viewModel.install(CatalogSkillDto("official/notes", "Notes")); idle()
        assertEquals(listOf("official/notes"), fake.installs)
    }

    @Test fun installNeverFiresWhileTheCatalogWasNeverOpened() {
        val fake = FakeDesktop().apply {
            catalog = listOf(CatalogSkillDto("official/notes", "Notes"))
        }
        val viewModel = vm(fake)
        viewModel.install(CatalogSkillDto("official/notes", "Notes")); idle()
        assertTrue(fake.installs.isEmpty())
    }

    @Test fun installPollsUntilTheSkillAppearsThenStops() {
        var clock = 0L
        val pauses = mutableListOf<Long>()
        val newSkill = DesktopSkillDto("Notes", enabled = true)
        // The second skills read (first poll) still misses the skill; the third finds it.
        val fake = object : FakeDesktop() {
            override suspend fun skills(): List<DesktopSkillDto> = super.skills().also {
                if (skillsReads == 2) skills = skills + newSkill
            }
        }.apply {
            catalog = listOf(CatalogSkillDto("official/notes", "Notes"))
            skills = listOf(DesktopSkillDto("core", enabled = true))
        }
        val viewModel = SkillsToolsViewModel(
            target, FakeHost(), { fake }, pollIntervalMs = 10_000, installWindowMs = 120_000,
            pause = { pauses += it; clock += it }, now = { clock },
        ).also { idle() }
        viewModel.openCatalog(); idle()
        viewModel.install((content(viewModel).catalog as CatalogState.Open).skills.single()); idle()
        assertEquals(listOf("official/notes"), fake.installs)
        assertNull(content(viewModel).installing)
        assertNull(content(viewModel).checkAgain)
        // Three skills reads: initial load, one empty poll, the poll that finds the skill.
        assertEquals(3, fake.skillsReads)
        assertEquals(listOf(10_000L, 10_000L), pauses)
        assertTrue((content(viewModel).skills as ListState.Ready).values.any { it.name == "Notes" })
    }

    @Test fun installStopsPollingAfterTwoMinutesAndOffersCheckAgain() {
        var clock = 0L
        val pauses = mutableListOf<Long>()
        val fake = FakeDesktop().apply {
            catalog = listOf(CatalogSkillDto("official/slow", "Slow"))
            skills = emptyList()
        }
        val viewModel = SkillsToolsViewModel(
            target, FakeHost(), { fake }, pollIntervalMs = 10_000, installWindowMs = 120_000,
            pause = { pauses += it; clock += it }, now = { clock },
        ).also { idle() }
        viewModel.openCatalog(); idle()
        viewModel.install((content(viewModel).catalog as CatalogState.Open).skills.single()); idle()
        assertEquals("official/slow", content(viewModel).checkAgain)
        assertNull(content(viewModel).installing)
        // 120 s / 10 s = 12 polls at most; never more than one read per 10 s.
        assertTrue(pauses.size <= 12)
        assertTrue(pauses.all { it == 10_000L })
        // A manual check that still finds nothing restores the check-again affordance.
        viewModel.checkAgain("official/slow"); idle()
        assertEquals("official/slow", content(viewModel).checkAgain)
        // And once the skill lands, a manual check clears it.
        fake.skills = listOf(DesktopSkillDto("Slow", enabled = true))
        viewModel.checkAgain("official/slow"); idle()
        assertNull(content(viewModel).checkAgain)
    }

    @Test fun desktopUnavailableSurfacesTheSpecificMessageInTheSection() {
        val fake = FakeDesktop().apply { skillsError = DesktopError.Unavailable }
        val viewModel = vm(fake)
        // The v1 list still shows, read-only, with the desktop message as the notice above it.
        val skills = (content(viewModel).skills as ListState.Ready).values
        assertEquals(listOf("legacy"), skills.map { it.name })
        assertNull(skills.single().enabled)
        assertEquals(DesktopError.Unavailable, content(viewModel).skillsNotice)
        assertEquals(R.string.desktop_unavailable, DesktopError.Unavailable.message)
    }

    @Test fun desktopNotFoundOnAnOlderBridgeAlsoFallsBackToTheApiList() {
        val fake = FakeDesktop().apply { skillsError = DesktopError.NotFound }
        val viewModel = vm(fake)
        val skills = (content(viewModel).skills as ListState.Ready).values
        assertEquals(listOf("legacy"), skills.map { it.name })
        assertNull(skills.single().enabled)
        assertEquals(DesktopError.NotFound, content(viewModel).skillsNotice)
    }

    @Test fun otherDesktopFailuresStaySectionErrorsAndDoNotTouchTheApiList() {
        val host = FakeHost()
        val fake = FakeDesktop().apply { skillsError = DesktopError.RateLimited }
        val viewModel = vm(fake, host)
        val section = content(viewModel).skills as ListState.Error
        assertEquals(SectionError.Desktop(DesktopError.RateLimited), section.error)
        assertNull(content(viewModel).skillsNotice)
        assertEquals(0, host.skillsReads)
    }

    @Test fun mcpListIsReadOnlyAndPerSectionErrorsDoNotHideOthers() {
        val fake = FakeDesktop().apply {
            skills = listOf(DesktopSkillDto("notes", enabled = true))
            servers = listOf(McpServerDto("files", "stdio", enabled = true, source = "config", plugin = "fs-plugin"))
        }
        val viewModel = vm(fake)
        val mcp = content(viewModel).mcp as ListState.Ready
        assertEquals("files", mcp.values.single().name)
        assertEquals("fs-plugin", mcp.values.single().plugin)
        fake.mcpError = DesktopError.Unavailable
        viewModel.load(); idle()
        assertEquals(SectionError.Desktop(DesktopError.Unavailable), (content(viewModel).mcp as ListState.Error).error)
        assertTrue(content(viewModel).skills is ListState.Ready)
    }

    @Test fun directBotShowsTheBridgeLineAndReadsSkillsThroughTheApiServer() {
        val host = FakeHost()
        val viewModel = vm(fake = null, host = host)
        val shown = content(viewModel)
        assertTrue(shown.direct)
        assertNull(shown.mcp)
        val skills = (shown.skills as ListState.Ready).values
        assertEquals(listOf("legacy"), skills.map { it.name })
        assertNull(skills.single().enabled)
    }

    @Test fun refreshIsANoOpWhileAToggleIsRunning() {
        val fake = FakeDesktop().apply { skills = listOf(DesktopSkillDto("review", enabled = false)) }
        val viewModel = vm(fake)
        viewModel.toggle("review", true)
        // The PUT is still queued on the test scheduler, so busySkill stays set.
        viewModel.load()
        assertTrue(viewModel.state.value is SkillsToolsState.Content)
        assertEquals("review", content(viewModel).busySkill)
        idle()
        assertEquals(listOf("review" to true), fake.toggles)
        assertNull(content(viewModel).busySkill)
    }

    @Test fun refreshIsANoOpWhileAnInstallIsRunning() {
        val fake = FakeDesktop().apply {
            skills = listOf(DesktopSkillDto("core", enabled = true))
            catalog = listOf(CatalogSkillDto("official/notes", "Notes"))
        }
        val viewModel = vm(fake)
        viewModel.openCatalog(); idle()
        viewModel.install((content(viewModel).catalog as CatalogState.Open).skills.single())
        // The install call is still queued on the test scheduler, so installing stays set.
        viewModel.load()
        assertTrue(viewModel.state.value is SkillsToolsState.Content)
        assertEquals("official/notes", content(viewModel).installing)
        idle()
        assertEquals(listOf("official/notes"), fake.installs)
        assertNull(content(viewModel).installing)
    }

    @Test fun aSuccessfulToggleReadClearsTheFallbackNotice() {
        val fake = FakeDesktop().apply { skillsError = DesktopError.NotFound }
        val viewModel = vm(fake)
        assertEquals(DesktopError.NotFound, content(viewModel).skillsNotice)
        // The bridge is updated: the desktop route answers, so the stale notice must go.
        fake.skillsError = null
        fake.skills = listOf(DesktopSkillDto("review", enabled = true))
        viewModel.toggle("review", false); idle()
        assertEquals(listOf("review" to false), fake.toggles)
        assertNull(content(viewModel).skillsNotice)
    }

    @Test fun aSuccessfulInstallPollClearsTheFallbackNotice() {
        val fake = FakeDesktop().apply {
            skillsError = DesktopError.NotFound
            catalog = listOf(CatalogSkillDto("official/notes", "Notes"))
        }
        val viewModel = vm(fake)
        assertEquals(DesktopError.NotFound, content(viewModel).skillsNotice)
        fake.skillsError = null
        fake.skills = listOf(DesktopSkillDto("Notes", enabled = true))
        viewModel.openCatalog(); idle()
        viewModel.install((content(viewModel).catalog as CatalogState.Open).skills.single()); idle()
        assertNull(content(viewModel).installing)
        assertNull(content(viewModel).skillsNotice)
    }

    @Test fun aSuccessfulCheckAgainClearsTheFallbackNotice() {
        val fake = FakeDesktop().apply {
            skillsError = DesktopError.NotFound
            catalog = listOf(CatalogSkillDto("official/slow", "Slow"))
        }
        val viewModel = vm(fake)
        viewModel.openCatalog(); idle()
        viewModel.install((content(viewModel).catalog as CatalogState.Open).skills.single()); idle()
        assertEquals("official/slow", content(viewModel).checkAgain)
        // Every poll failed on the same old bridge, so the notice is still there.
        assertEquals(DesktopError.NotFound, content(viewModel).skillsNotice)
        fake.skillsError = null
        fake.skills = listOf(DesktopSkillDto("Slow", enabled = true))
        viewModel.checkAgain("official/slow"); idle()
        assertNull(content(viewModel).checkAgain)
        assertNull(content(viewModel).skillsNotice)
    }
}
