package app.rasul

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
import app.rasul.skills.CatalogState
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private fun freshnessCaps() = CapabilitiesDto(
    endpoints = mapOf("skills" to CapabilityEndpointDto(), "toolsets" to CapabilityEndpointDto()),
)

private class FreshnessHost : SkillsHostApi {
    override suspend fun capabilities(target: HermesTarget) = HermesResult.Ok(freshnessCaps())
    override suspend fun skills(target: HermesTarget): HermesResult<List<SkillDto>> = HermesResult.Ok(emptyList())
    override suspend fun toolsets(target: HermesTarget): HermesResult<List<ToolsetDto>> = HermesResult.Ok(emptyList())
}

@OptIn(ExperimentalCoroutinesApi::class)
class SkillsInstallFreshnessTest {
    private val sched = TestCoroutineScheduler()

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher(sched))
    @After fun tearDown() = Dispatchers.resetMain()
    private fun idle() = sched.advanceUntilIdle()

    private val target = ActiveTarget.Ready("id", "PC", HermesTarget("http://example.invalid/", null, "k"))

    private fun content(vm: SkillsToolsViewModel): SkillsToolsState.Content =
        vm.state.value as SkillsToolsState.Content

    private fun vm(fake: FakeDesktop, clock: () -> Long, pause: suspend (Long) -> Unit): SkillsToolsViewModel =
        SkillsToolsViewModel(
            target, FreshnessHost(), { fake },
            pollIntervalMs = 10_000, installWindowMs = 120_000,
            pause = pause, now = clock,
        ).also { idle() }

    @Test fun installWithCatalogOlderThan45SecondsRereadsItFirst() {
        var clock = 0L
        val fake = FakeDesktop().apply {
            catalog = listOf(CatalogSkillDto("official/notes", "Notes"))
            skills = emptyList()
        }
        val viewModel = vm(fake, { clock }, { clock += it })
        viewModel.openCatalog(); idle()
        assertEquals(1, fake.catalogReads)
        clock += 61_000
        viewModel.install((content(viewModel).catalog as CatalogState.Open).skills.single()); idle()
        // A catalog read 61 s old no longer authorizes the POST: it is re-read first, then sent.
        assertEquals(2, fake.catalogReads)
        assertEquals(listOf("official/notes"), fake.installs)
    }

    @Test fun installOnStaleCatalogWithEntryGoneSendsNoPostAndShowsASpecificMessage() {
        var clock = 0L
        val fake = FakeDesktop().apply {
            catalog = listOf(CatalogSkillDto("official/notes", "Notes"))
            skills = emptyList()
        }
        val viewModel = vm(fake, { clock }, { clock += it })
        viewModel.openCatalog(); idle()
        val shown = (content(viewModel).catalog as CatalogState.Open).skills.single()
        clock += 61_000
        fake.catalog = listOf(CatalogSkillDto("official/other", "Other"))
        viewModel.install(shown); idle()
        assertTrue(fake.installs.isEmpty())
        assertNull(content(viewModel).installing)
        // A specific message, not the generic invalid-request one.
        assertEquals(DesktopError.CatalogChanged, content(viewModel).actionError)
        assertNotEquals(R.string.desktop_invalid, DesktopError.CatalogChanged.message)
    }

    @Test fun installWithin45SecondsSendsWithoutRereading() {
        var clock = 0L
        val fake = FakeDesktop().apply {
            catalog = listOf(CatalogSkillDto("official/notes", "Notes"))
            skills = emptyList()
        }
        val viewModel = vm(fake, { clock }, { clock += it })
        viewModel.openCatalog(); idle()
        clock += 30_000
        viewModel.install((content(viewModel).catalog as CatalogState.Open).skills.single()); idle()
        assertEquals(1, fake.catalogReads)
        assertEquals(listOf("official/notes"), fake.installs)
    }

    @Test fun installOnStaleCatalogWithEntryInstalledSendsNoPost() {
        var clock = 0L
        val fake = FakeDesktop().apply {
            catalog = listOf(CatalogSkillDto("official/notes", "Notes"))
            skills = emptyList()
        }
        val viewModel = vm(fake, { clock }, { clock += it })
        viewModel.openCatalog(); idle()
        val shown = (content(viewModel).catalog as CatalogState.Open).skills.single()
        clock += 61_000
        fake.catalog = listOf(CatalogSkillDto("official/notes", "Notes", installed = true))
        viewModel.install(shown); idle()
        assertTrue(fake.installs.isEmpty())
        assertEquals(DesktopError.CatalogChanged, content(viewModel).actionError)
    }
}
