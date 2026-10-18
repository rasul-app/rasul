package app.rasul

import app.rasul.bridge.CatalogSkillDto
import app.rasul.bridge.DesktopApi
import app.rasul.bridge.DesktopError
import app.rasul.bridge.DesktopException
import app.rasul.bridge.DesktopSkillDto
import app.rasul.bridge.JobRunDto
import app.rasul.bridge.McpServerDto
import app.rasul.bridge.SkillToggleResult
import app.rasul.bridge.UsageDayDto
import app.rasul.bridge.UsageReportDto
import app.rasul.data.HermesError
import app.rasul.data.HermesResult
import app.rasul.data.HermesTarget
import app.rasul.data.ModelOptionsDto
import app.rasul.data.ModelOptionsLimitDto
import app.rasul.data.ModelOptionsProviderDto
import app.rasul.data.ModelOptionsUsageAccountDto
import app.rasul.data.ModelOptionsUsageDto
import app.rasul.data.ModelOptionsUsageWindowDto
import app.rasul.desktop.LimitedProvider
import app.rasul.desktop.RateWindow
import app.rasul.desktop.RunHistoryViewModel
import app.rasul.desktop.UsageViewModel
import app.rasul.desktop.rateLimitInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A desktop api double; every route can either answer or fail with a desktop token. */
internal open class FakeDesktop : DesktopApi {
    var runs = listOf<JobRunDto>()
    var runsError: DesktopError? = null
    val jobIds = mutableListOf<Pair<String, Int>>()
    var report = UsageReportDto()
    var usageError: DesktopError? = null
    val daysCalls = mutableListOf<Int>()
    var skills = listOf<DesktopSkillDto>()
    var skillsError: DesktopError? = null
    var skillsReads = 0
    val toggles = mutableListOf<Pair<String, Boolean>>()
    var catalog = listOf<CatalogSkillDto>()
    var catalogError: DesktopError? = null
    var catalogReads = 0
    val installs = mutableListOf<String>()
    var installError: DesktopError? = null
    var servers = listOf<McpServerDto>()
    var mcpError: DesktopError? = null

    override suspend fun jobRuns(jobId: String, limit: Int): List<JobRunDto> {
        jobIds += jobId to limit
        runsError?.let { throw DesktopException(it) }
        return runs
    }
    override suspend fun skills(): List<DesktopSkillDto> {
        skillsReads++
        skillsError?.let { throw DesktopException(it) }
        return skills
    }
    override suspend fun toggleSkill(name: String, enabled: Boolean): SkillToggleResult {
        toggles += name to enabled
        return SkillToggleResult(name, enabled)
    }
    override suspend fun skillCatalog(): List<CatalogSkillDto> {
        catalogReads++
        catalogError?.let { throw DesktopException(it) }
        return catalog
    }
    override suspend fun installSkill(identifier: String) {
        installs += identifier
        installError?.let { throw DesktopException(it) }
    }
    override suspend fun mcpServers(): List<McpServerDto> {
        mcpError?.let { throw DesktopException(it) }
        return servers
    }
    override suspend fun usage(days: Int): UsageReportDto {
        daysCalls += days
        usageError?.let { throw DesktopException(it) }
        return report
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopViewModelTest {
    private val sched = TestCoroutineScheduler()

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher(sched))
    @After fun tearDown() = Dispatchers.resetMain()
    private fun idle() = sched.advanceUntilIdle()

    private val target = HermesTarget("http://example.invalid/", null, "k")
    private val run = JobRunDto("r1", "nightly", JsonPrimitive(1760000000), JsonPrimitive(1760000120), false, "done")

    @Test fun runHistoryLoadsRunsForTheJobId() {
        val fake = FakeDesktop().apply { runs = listOf(run) }
        val vm = RunHistoryViewModel("job-1") { fake }
        idle()
        assertEquals(listOf("job-1" to 20), fake.jobIds)
        assertEquals(listOf(run), vm.state.value.runs)
        assertTrue(vm.state.value.loaded)
        assertNull(vm.state.value.error)
    }

    @Test fun runHistoryMapsDesktopUnavailableToTheSpecificError() {
        val fake = FakeDesktop().apply { runsError = DesktopError.Unavailable }
        val vm = RunHistoryViewModel("job-1") { fake }
        idle()
        assertEquals(DesktopError.Unavailable, vm.state.value.error)
        assertTrue(vm.state.value.runs.isEmpty())
    }

    @Test fun runHistoryOnADirectBotIsOnlyTheBridgeNotice() {
        val vm = RunHistoryViewModel("job-1", null)
        idle()
        assertTrue(vm.state.value.direct)
        assertTrue(vm.state.value.runs.isEmpty())
        assertNull(vm.state.value.error)
    }

    @Test fun usageLoadsTheDefaultWindowThenRefetchesOnPeriodChange() {
        val fake = FakeDesktop().apply { report = UsageReportDto(daily = listOf(UsageDayDto("2025-01-01", 100.0, 40.0)), periodDays = 7) }
        val options: suspend (HermesTarget, Boolean) -> HermesResult<ModelOptionsDto> =
            { _, _ -> HermesResult.Ok(ModelOptionsDto(emptyList())) }
        val vm = UsageViewModel(target, options) { fake }
        idle()
        assertEquals(listOf(7), fake.daysCalls)
        assertEquals(1, vm.state.value.report?.daily?.size)
        vm.setDays(30); idle()
        assertEquals(listOf(7, 30), fake.daysCalls)
        assertEquals(30, vm.state.value.days)
        vm.setDays(30); idle()
        assertEquals(listOf(7, 30), fake.daysCalls)
    }

    @Test fun usageMapsDesktopUnavailableToTheSpecificError() {
        val fake = FakeDesktop().apply { usageError = DesktopError.Unavailable }
        val options: suspend (HermesTarget, Boolean) -> HermesResult<ModelOptionsDto> =
            { _, _ -> HermesResult.Ok(ModelOptionsDto(emptyList())) }
        val vm = UsageViewModel(target, options) { fake }
        idle()
        assertEquals(DesktopError.Unavailable, vm.state.value.error)
    }

    @Test fun usageKeepsTheChartWhenModelOptionsFailAndReportsTheError() {
        val fake = FakeDesktop().apply { report = UsageReportDto(daily = listOf(UsageDayDto("d", 1.0, 2.0))) }
        val options: suspend (HermesTarget, Boolean) -> HermesResult<ModelOptionsDto> =
            { _, _ -> HermesResult.Err(HermesError.BridgeUnreachable) }
        val vm = UsageViewModel(target, options) { fake }
        idle()
        assertTrue(vm.state.value.loaded)
        assertEquals(1, vm.state.value.report?.daily?.size)
        assertEquals(HermesError.BridgeUnreachable, vm.state.value.optionsError)
    }

    @Test fun rateLimitInfoMapsWindowsAccountsAndBenchedProviders() {
        val options = ModelOptionsDto(listOf(
            ModelOptionsProviderDto(
                slug = "a", name = "Provider A",
                usage = ModelOptionsUsageDto(
                    windows = listOf(ModelOptionsUsageWindowDto("5h", 62.0, "2025-01-08T15:00:00Z")),
                    accounts = listOf(
                        ModelOptionsUsageAccountDto(
                            id = "w1", label = "Work", state = "limited", resetsAt = "2025-01-09T00:00:00Z",
                            windows = listOf(ModelOptionsUsageWindowDto("Weekly", 24.0, "2025-01-12T00:00:00Z")),
                        ),
                        ModelOptionsUsageAccountDto(id = "h1", label = "Home", state = "active"),
                    ),
                ),
            ),
            ModelOptionsProviderDto(slug = "b", name = "Provider B", limit = ModelOptionsLimitDto(scope = "account", resetsAt = "2025-01-08T18:30:00Z")),
            ModelOptionsProviderDto(slug = "c", name = "Provider C", limit = ModelOptionsLimitDto(scope = "models", models = mapOf("m1" to "2025-01-09T01:00:00Z", "m2" to "2025-01-08T20:00:00Z"))),
        ))
        val info = rateLimitInfo(options)
        assertEquals(
            listOf(
                RateWindow("5h", 62.0, "2025-01-08T15:00:00Z"),
                RateWindow("Work · Weekly", 24.0, "2025-01-12T00:00:00Z"),
            ),
            info.windows,
        )
        assertEquals(
            listOf(
                LimitedProvider("Work", "2025-01-09T00:00:00Z"),
                LimitedProvider("Provider B", "2025-01-08T18:30:00Z"),
                LimitedProvider("Provider C", "2025-01-08T20:00:00Z"),
            ),
            info.limited,
        )
    }
}
