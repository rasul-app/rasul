package app.rasul

import app.rasul.bridge.DesktopError
import app.rasul.bridge.McpCatalogEntryDto
import app.rasul.bridge.McpServerDto
import app.rasul.bridge.McpTestDto
import app.rasul.bridge.McpToolDto
import app.rasul.data.ActiveTarget
import app.rasul.data.CapabilitiesDto
import app.rasul.data.CapabilityEndpointDto
import app.rasul.data.HermesResult
import app.rasul.data.HermesTarget
import app.rasul.data.SkillDto
import app.rasul.data.ToolsetDto
import app.rasul.skills.ListState
import app.rasul.skills.McpCatalogState
import app.rasul.skills.McpTestState
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

@OptIn(ExperimentalCoroutinesApi::class)
class McpManageViewModelTest {
    private val sched = TestCoroutineScheduler()

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher(sched))
    @After fun tearDown() = Dispatchers.resetMain()
    private fun idle() = sched.advanceUntilIdle()

    private class Host : SkillsHostApi {
        override suspend fun capabilities(target: HermesTarget) = HermesResult.Ok(
            CapabilitiesDto(endpoints = mapOf("skills" to CapabilityEndpointDto(), "toolsets" to CapabilityEndpointDto())),
        )
        override suspend fun skills(target: HermesTarget) = HermesResult.Ok(emptyList<SkillDto>())
        override suspend fun toolsets(target: HermesTarget) = HermesResult.Ok(emptyList<ToolsetDto>())
    }

    private val target = ActiveTarget.Ready("id", "PC", HermesTarget("http://example.invalid/", null, "k"))

    private fun content(vm: SkillsToolsViewModel) = vm.state.value as SkillsToolsState.Content

    private fun fake() = FakeDesktop().apply {
        servers = listOf(
            McpServerDto("files", "stdio", enabled = true, source = "config"),
            McpServerDto("web", "http", enabled = false, source = "config"),
            McpServerDto("plug", "stdio", enabled = true, source = "plugin", plugin = "search-plugin"),
        )
        mcpEntries = listOf(
            McpCatalogEntryDto("notes", "Notes", "Local notes", installed = false, installable = true),
            McpCatalogEntryDto("mail", "Mail", "Needs a login", installed = false, installable = false),
            McpCatalogEntryDto("fs", "Files", installed = true, installable = false),
        )
    }

    private fun vm(fake: FakeDesktop = fake()): Pair<FakeDesktop, SkillsToolsViewModel> {
        val viewModel = SkillsToolsViewModel(target, Host(), { fake }, pause = {}, now = { 0L })
        idle()
        return fake to viewModel
    }

    private fun serverEnabled(vm: SkillsToolsViewModel, name: String) =
        (content(vm).mcp as ListState.Ready).values.first { it.name == name }.enabled

    @Test fun switchSendsThePutAndUpdatesTheRowFromItsAnswer() {
        val (fake, vm) = vm()
        fake.mcpError = DesktopError.DesktopFailed // a re-read would fail; the PUT answer is enough
        vm.toggleMcp("web", true); idle()
        assertEquals(listOf("web" to true), fake.mcpToggles)
        assertEquals(true, serverEnabled(vm, "web"))
        assertEquals(true, serverEnabled(vm, "files"))
        assertNull(content(vm).busyMcp)
        assertNull(content(vm).actionError)
    }

    @Test fun installSucceedsEvenWhenTheRereadFails() {
        val (fake, vm) = vm()
        vm.openMcpCatalog(); idle()
        fake.mcpError = DesktopError.Unreachable
        vm.installMcp((content(vm).mcpCatalog as McpCatalogState.Open).entries.first { it.id == "notes" }); idle()
        assertEquals(listOf("notes"), fake.mcpInstalls)
        assertNull(content(vm).actionError)
        assertNull(content(vm).installingMcp)
        assertTrue(content(vm).mcpStale)
        val entry = (content(vm).mcpCatalog as McpCatalogState.Open).entries.first { it.id == "notes" }
        assertTrue(entry.installed)
    }

    private fun staleAfterInstall(): SkillsToolsViewModel {
        val (fake, vm) = vm()
        vm.openMcpCatalog(); idle()
        fake.mcpError = DesktopError.Unreachable
        vm.installMcp((content(vm).mcpCatalog as McpCatalogState.Open).entries.first { it.id == "notes" }); idle()
        assertTrue(content(vm).mcpStale)
        return vm
    }

    @Test fun aLaterSuccessfulSwitchClearsTheStaleNote() {
        val vm = staleAfterInstall()
        vm.toggleMcp("web", true); idle()
        assertFalse(content(vm).mcpStale)
    }

    @Test fun aLaterSuccessfulTestClearsTheStaleNote() {
        val vm = staleAfterInstall()
        vm.testMcp("files"); idle()
        assertFalse(content(vm).mcpStale)
    }

    @Test fun installWithGoodRereadIsNotStale() {
        val (_, vm) = vm()
        vm.openMcpCatalog(); idle()
        vm.installMcp((content(vm).mcpCatalog as McpCatalogState.Open).entries.first { it.id == "notes" }); idle()
        assertFalse(content(vm).mcpStale)
    }

    @Test fun switchFailureKeepsTheOldStateAndShowsTheSpecificError() {
        val (fake, vm) = vm()
        fake.mcpToggleError = DesktopError.McpRateLimited
        vm.toggleMcp("web", true); idle()
        assertEquals(DesktopError.McpRateLimited, content(vm).actionError)
        assertEquals(false, serverEnabled(vm, "web"))
        assertNull(content(vm).busyMcp)
    }

    @Test fun aSecondSwitchWhileOneRunsIsIgnored() {
        val (fake, vm) = vm()
        vm.toggleMcp("web", true); vm.toggleMcp("files", false); idle()
        assertEquals(listOf("web" to true), fake.mcpToggles)
    }

    @Test fun testShowsOkWithTheToolCount() {
        val (fake, vm) = vm()
        fake.mcpTestResult = McpTestDto("files", true, listOf(McpToolDto("read"), McpToolDto("list")))
        vm.testMcp("files")
        assertEquals(McpTestState.Testing("files"), content(vm).mcpTest)
        idle()
        assertEquals(listOf("files"), fake.mcpTests)
        val result = content(vm).mcpTest as McpTestState.Result
        assertTrue(result.result.ok); assertEquals(2, result.result.tools.size)
        vm.dismissMcpTest()
        assertEquals(McpTestState.None, content(vm).mcpTest)
    }

    @Test fun testShowsTheServerErrorText() {
        val (fake, vm) = vm()
        fake.mcpTestResult = McpTestDto("web", false, error = "connection refused")
        vm.testMcp("web"); idle()
        val result = content(vm).mcpTest as McpTestState.Result
        assertFalse(result.result.ok); assertEquals("connection refused", result.result.error)
    }

    @Test fun testRateLimitIsSpecific() {
        val (fake, vm) = vm()
        fake.mcpTestError = DesktopError.McpRateLimited
        vm.testMcp("files"); idle()
        assertEquals(DesktopError.McpRateLimited, content(vm).actionError)
        assertEquals(McpTestState.None, content(vm).mcpTest)
    }

    @Test fun catalogOpensWithEntriesAndErrorsShowTheirMessage() {
        val (fake, vm) = vm()
        vm.openMcpCatalog(); idle()
        assertEquals(3, (content(vm).mcpCatalog as McpCatalogState.Open).entries.size)
        vm.closeMcpCatalog()
        assertEquals(McpCatalogState.Closed, content(vm).mcpCatalog)
        fake.mcpCatalogError = DesktopError.NotFound
        vm.openMcpCatalog(); idle()
        assertEquals(McpCatalogState.Error(DesktopError.NotFound), content(vm).mcpCatalog)
    }

    @Test fun onlyAnInstallableEntryOfTheShownCatalogIsInstalled() {
        val (fake, vm) = vm()
        vm.openMcpCatalog(); idle()
        val open = (content(vm).mcpCatalog as McpCatalogState.Open).entries
        vm.installMcp(open.first { it.id == "mail" }); idle()
        vm.installMcp(open.first { it.id == "fs" }); idle()
        vm.installMcp(McpCatalogEntryDto("ghost", "Ghost", installable = true)); idle()
        assertTrue(fake.mcpInstalls.isEmpty())
        vm.installMcp(open.first { it.id == "notes" }); idle()
        assertEquals(listOf("notes"), fake.mcpInstalls)
        val after = (content(vm).mcpCatalog as McpCatalogState.Open).entries.first { it.id == "notes" }
        assertTrue(after.installed)
        assertNull(content(vm).installingMcp)
    }

    @Test fun installFailureShowsTheErrorAndKeepsTheCatalogOpen() {
        val (fake, vm) = vm()
        fake.mcpInstallError = DesktopError.McpRateLimited
        vm.openMcpCatalog(); idle()
        vm.installMcp((content(vm).mcpCatalog as McpCatalogState.Open).entries.first { it.id == "notes" }); idle()
        assertEquals(DesktopError.McpRateLimited, content(vm).actionError)
        assertNull(content(vm).installingMcp)
        assertTrue(content(vm).mcpCatalog is McpCatalogState.Open)
    }

    @Test fun directConnectionOffersNoMcpActions() {
        val viewModel = SkillsToolsViewModel(target, Host(), null, pause = {}, now = { 0L })
        idle()
        viewModel.toggleMcp("files", true); viewModel.testMcp("files"); viewModel.openMcpCatalog(); idle()
        assertNull(content(viewModel).mcp)
        assertEquals(McpCatalogState.Closed, content(viewModel).mcpCatalog)
    }
}
