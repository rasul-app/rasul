package app.rasul

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.rasul.bridge.ApprovalItemDto
import app.rasul.bridge.ApprovalsApi
import app.rasul.bridge.ApprovalsEvent
import app.rasul.bridge.ApprovalsRepository
import app.rasul.bridge.ApprovalAnsweredDto
import app.rasul.bridge.BridgeBotsRepository
import app.rasul.bridge.BridgeClients
import app.rasul.bridge.BridgeProfile
import app.rasul.bridge.BridgeStore
import app.rasul.bridge.BridgeTransport
import app.rasul.bridge.ProfileSnapshot
import app.rasul.bridge.ProfilesApi
import app.rasul.bridge.SavedBridge
import app.rasul.data.HermesResult
import app.rasul.data.HermesTarget
import app.rasul.data.SecretCipher
import app.rasul.jobs.JobDto
import app.rasul.jobs.JobsApi
import app.rasul.notifications.APPROVAL_ALERT_ID
import app.rasul.notifications.ApprovalAlerts
import app.rasul.notifications.ApprovalWatchService
import app.rasul.notifications.JOB_ALERT_ID
import app.rasul.notifications.JOB_RESULTS_CHANNEL
import app.rasul.notifications.JOB_RUNS_ROUTE
import app.rasul.notifications.JOB_SUMMARY_ID
import app.rasul.notifications.JobSeenStore
import app.rasul.notifications.isJobRunsRoute
import app.rasul.security.AppLockController
import app.rasul.security.AppLockSettings
import app.rasul.settings.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.Base64
import java.util.UUID

/** The one watcher service serves approvals and job results; each setting does only its own work. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JobAlertsServiceTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val context: Context get() = org.robolectric.RuntimeEnvironment.getApplication()
    private val container get() = (context.applicationContext as RasulApp).container
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    private val cipher = object : SecretCipher {
        override fun encrypt(plain: String) = plain.reversed()
        override fun decrypt(token: String) = token.reversed()
    }

    private fun bridge() = SavedBridge(UUID.randomUUID().toString(), "Studio PC", listOf("wss://example.invalid"),
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32)), UUID.randomUUID().toString(), UUID.randomUUID().toString())

    private class FakeProfiles : ProfilesApi {
        override suspend fun list() = ProfileSnapshot(1, listOf(BridgeProfile("default", "default", "Research", true, true)))
        override fun events(): Flow<ProfileSnapshot> = flow { awaitCancellation() }
    }

    private class FakeApprovals : ApprovalsApi {
        val events = Channel<ApprovalsEvent>(Channel.UNLIMITED)
        val opened = CompletableDeferred<Unit>()
        override suspend fun list() = emptyList<ApprovalItemDto>()
        override fun events(): Flow<ApprovalsEvent> = flow {
            opened.complete(Unit)
            events.receiveAsFlow().collect { emit(it) }
        }
        override suspend fun answer(requestId: String, choice: String) = ApprovalAnsweredDto(requestId, "answered")
    }

    private class FakeJobs : JobsApi {
        var jobs = listOf<JobDto>()
        var lists = 0
        override suspend fun list(target: HermesTarget): HermesResult<List<JobDto>> { lists++; return HermesResult.Ok(jobs) }
        override suspend fun create(target: HermesTarget, name: String, schedule: String, prompt: String) = error("unused")
        override suspend fun update(target: HermesTarget, id: String, name: String?, schedule: String?, prompt: String?) = error("unused")
        override suspend fun delete(target: HermesTarget, id: String) = error("unused")
        override suspend fun pause(target: HermesTarget, id: String) = error("unused")
        override suspend fun resume(target: HermesTarget, id: String) = error("unused")
        override suspend fun runNow(target: HermesTarget, id: String) = error("unused")
    }

    private fun fakeStore(): DataStore<Preferences> {
        val data = MutableStateFlow<Preferences>(emptyPreferences())
        return object : DataStore<Preferences> {
            override val data: Flow<Preferences> = data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                val next = transform(data.value); data.value = next; return next
            }
        }
    }

    private fun install(field: String, value: Any) {
        AppContainer::class.java.getDeclaredField(field).apply { isAccessible = true }.set(container, value)
    }

    private fun job(id: String, runAt: String, status: String = "ok", error: String? = null, name: String = "Backup $id") =
        JobDto(id, name, lastRunAt = JsonPrimitive(runAt), lastStatus = status, lastError = error?.let(::JsonPrimitive))

    private fun jobAlerts() = manager.activeNotifications.filter { it.id == JOB_ALERT_ID }

    private class Wired(val approvals: FakeApprovals, val jobs: FakeJobs)

    private suspend fun TestScope.wire(approvalsOn: Boolean, jobsOn: Boolean, lockOn: Boolean = false, saveBridge: Boolean = true): Wired {
        container.appLock.setEnabled(false)
        val lock = AppLockController(AppLockSettings(fakeStore()), scope)
        lock.setEnabled(lockOn)
        install("appLock", lock)
        val settings = AppSettings(fakeStore())
        val bridgeStore = BridgeStore(fakeStore(), cipher)
        if (saveBridge) {
            bridgeStore.save(bridge())
            bridgeStore.bridges.first { it.isNotEmpty() }
        }
        val approvalsApi = FakeApprovals()
        val clients = BridgeClients(bridgeStore, scope, { _, urls, _ ->
            BridgeTransport(urls, ByteArray(32), null, newContext = { javax.net.ssl.SSLContext.getInstance("TLSv1.3") })
        }, {})
        val bots = BridgeBotsRepository(bridgeStore, clients) { FakeProfiles() }
        val approvals = ApprovalsRepository(bridgeStore, clients, bots.state) { _, _ -> approvalsApi }
        val jobs = FakeJobs()
        install("settings", settings); install("bridges", bridgeStore)
        install("bridgeBots", bots); install("approvals", approvals)
        install("jobs", jobs); install("jobSeen", JobSeenStore(fakeStore()))
        if (approvalsOn) { settings.setApprovalAlerts(true); settings.values.first { it.approvalAlerts } }
        if (jobsOn) { settings.setJobAlerts(true); settings.values.first { it.jobAlerts } }
        return Wired(approvalsApi, jobs)
    }

    // The saved-bridge lookups hop to Dispatchers.IO, which the test scheduler does not drive: give them real time.
    private fun TestScope.settle() { repeat(25) { runCurrent(); Thread.sleep(8) } }
    // Waits in real time without suspending the test body: a suspended body lets the scheduler skip virtual time, which
    // fires the service's own timeouts while the IO work they guard is still running. The ceiling only bounds a hang.
    private fun TestScope.awaitUntil(ceilingMs: Long = 30_000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ceilingMs
        while (!condition() && System.currentTimeMillis() < end) { runCurrent(); Thread.sleep(5) }
        runCurrent()
        return condition()
    }
    private fun TestScope.awaitPolls(w: Wired, n: Int) {
        awaitUntil { w.jobs.lists >= n }
        settle()
    }
    private fun TestScope.pump() { advanceTimeBy(400); settle() }
    private fun TestScope.tick() { advanceTimeBy(5 * 60_000L + 1); settle() }

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { scope.cancel(); Dispatchers.resetMain() }

    private suspend fun TestScope.started(wired: Wired): org.robolectric.android.controller.ServiceController<ApprovalWatchService> {
        val controller = Robolectric.buildService(ApprovalWatchService::class.java).create()
        assertTrue("the bots never loaded", awaitUntil { container.bridgeBots.state.value.groups.any { g -> g.profiles.isNotEmpty() } })
        pump()
        if (container.settings.values.first().jobAlerts) awaitPolls(wired, 1)
        return controller
    }

    @Test fun firstPollSeedsThenANewRunPostsOnePrivateNotificationWithAPublicVersion() = runTest(dispatcher) {
        val w = wire(approvalsOn = false, jobsOn = true)
        w.jobs.jobs = listOf(job("j1", "2030-01-01T00:00:00+00:00"))
        val controller = started(w)
        try {
            assertEquals(1, w.jobs.lists)
            assertTrue("the first poll only seeds", jobAlerts().isEmpty())
            w.jobs.jobs = listOf(job("j1", "2030-01-01T06:00:00+00:00", "error", "Command exited with code 2"))
            tick()
            val shown = jobAlerts().single().notification
            assertEquals(JOB_RESULTS_CHANNEL, shown.channelId)
            assertEquals(Notification.VISIBILITY_PRIVATE, shown.visibility)
            assertEquals(context.getString(R.string.job_alert_failed, "Backup j1"), shown.extras.getCharSequence(Notification.EXTRA_TITLE))
            assertEquals("Command exited with code 2", shown.extras.getCharSequence(Notification.EXTRA_TEXT))
            assertEquals("Job failed", shown.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE))
            assertNull(shown.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT))
            // Tap opens that job's run history through a validated route.
            val route = shadowOf(shown.contentIntent).savedIntent.getStringExtra(JOB_RUNS_ROUTE)!!
            assertTrue(isJobRunsRoute(route))
            assertTrue(route.startsWith("job-runs/j1?"))
            // The same snapshot again posts nothing new.
            manager.cancelAll()
            tick()
            assertTrue(jobAlerts().isEmpty())
            assertEquals(3, w.jobs.lists)
        } finally { controller.destroy() }
    }

    @Test fun aSuccessfulRunHasNoTextAndADeliveryFailureHasItsOwnTitle() = runTest(dispatcher) {
        val w = wire(approvalsOn = false, jobsOn = true)
        w.jobs.jobs = listOf(job("j1", "2030-01-01T00:00:00+00:00"), job("j2", "2030-01-01T00:00:00+00:00"))
        val controller = started(w)
        try {
            w.jobs.jobs = listOf(
                job("j1", "2030-01-02T00:00:00+00:00", "ok"),
                job("j2", "2030-01-02T00:00:00+00:00", "delivery_failed", "no channel"),
            )
            tick()
            val byTag = jobAlerts().associateBy { it.tag.substringAfterLast(':') }
            val ok = byTag.getValue("j1").notification
            assertEquals(context.getString(R.string.job_alert_finished, "Backup j1"), ok.extras.getCharSequence(Notification.EXTRA_TITLE))
            assertNull(ok.extras.getCharSequence(Notification.EXTRA_TEXT))
            assertEquals("Job finished", ok.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE))
            val delivery = byTag.getValue("j2").notification
            assertEquals(context.getString(R.string.job_alert_delivery_failed, "Backup j2"),
                delivery.extras.getCharSequence(Notification.EXTRA_TITLE))
            // Two at once are folded into one summary.
            assertEquals(1, manager.activeNotifications.count { it.id == JOB_SUMMARY_ID })
        } finally { controller.destroy() }
    }

    @Test fun appLockOnPostsOnlyThePublicText() = runTest(dispatcher) {
        val w = wire(approvalsOn = false, jobsOn = true, lockOn = true)
        w.jobs.jobs = listOf(job("j1", "2030-01-01T00:00:00+00:00"))
        val controller = started(w)
        try {
            w.jobs.jobs = listOf(job("j1", "2030-01-02T00:00:00+00:00", "error", "SECRET-ERROR", name = "Payroll export"))
            tick()
            val shown = jobAlerts().single().notification
            assertEquals("Job failed", shown.extras.getCharSequence(Notification.EXTRA_TITLE))
            val everything = shown.extras.toString()
            assertFalse(everything.contains("SECRET-ERROR"))
            assertFalse(everything.contains("Payroll"))
        } finally { controller.destroy() }
    }

    @Test fun settingOffMeansNoJobPollingWhileApprovalsStillAlert() = runTest(dispatcher) {
        val w = wire(approvalsOn = true, jobsOn = false)
        w.jobs.jobs = listOf(job("j1", "2030-01-01T00:00:00+00:00"))
        val controller = started(w)
        try {
            assertTrue("the approvals stream never opened", awaitUntil { w.approvals.opened.isCompleted })
            tick(); tick()
            assertEquals(0, w.jobs.lists)
            val requestId = "a".repeat(32)
            w.approvals.events.send(ApprovalsEvent.Snapshot(listOf(
                ApprovalItemDto(requestId, "sk", "default", "shell", "cmd", "Run the cleanup.", true, false, 4_000_000_000.0))))
            pump()
            assertEquals(1, manager.activeNotifications.count { it.id == APPROVAL_ALERT_ID })
            assertTrue(jobAlerts().isEmpty())
        } finally { controller.destroy() }
    }

    @Test fun turningJobResultsOffStopsPollingAndLeavesApprovalsRunning() = runTest(dispatcher) {
        val w = wire(approvalsOn = true, jobsOn = true)
        w.jobs.jobs = listOf(job("j1", "2030-01-01T00:00:00+00:00"))
        val controller = started(w)
        try {
            assertTrue("the approvals stream never opened", awaitUntil { w.approvals.opened.isCompleted })
            assertEquals(1, w.jobs.lists)
            container.settings.setJobAlerts(false)
            pump()
            val after = w.jobs.lists
            tick(); tick()
            assertEquals(after, w.jobs.lists)
            assertFalse(shadowOf(controller.get()).isStoppedBySelf)
            w.approvals.events.send(ApprovalsEvent.Snapshot(listOf(
                ApprovalItemDto("b".repeat(32), "sk", "default", "shell", "cmd", "Run the cleanup.", true, false, 4_000_000_000.0))))
            pump()
            assertEquals(1, manager.activeNotifications.count { it.id == APPROVAL_ALERT_ID })
        } finally { controller.destroy() }
    }

    @Test fun turningApprovalsOffKeepsPollingJobs() = runTest(dispatcher) {
        val w = wire(approvalsOn = true, jobsOn = true)
        w.jobs.jobs = listOf(job("j1", "2030-01-01T00:00:00+00:00"))
        val controller = started(w)
        try {
            container.settings.setApprovalAlerts(false)
            pump()
            assertFalse(shadowOf(controller.get()).isStoppedBySelf)
            val before = w.jobs.lists
            tick()
            assertTrue(w.jobs.lists > before)
        } finally { controller.destroy() }
    }

    @Test fun comingToTheForegroundPollsOnceNow() = runTest(dispatcher) {
        val w = wire(approvalsOn = false, jobsOn = true)
        w.jobs.jobs = listOf(job("j1", "2030-01-01T00:00:00+00:00"))
        val controller = started(w)
        try {
            assertEquals(1, w.jobs.lists)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(61))
            controller.startCommand(0, 2)
            pump()
            assertEquals(2, w.jobs.lists)
        } finally { controller.destroy() }
    }

    @Test fun comingToTheForegroundRightAfterAReadDoesNotReadAgain() = runTest(dispatcher) {
        val w = wire(approvalsOn = false, jobsOn = true)
        w.jobs.jobs = listOf(job("j1", "2030-01-01T00:00:00+00:00"))
        val controller = started(w)
        try {
            assertEquals(1, w.jobs.lists)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(30))
            controller.startCommand(0, 2)
            pump()
            assertEquals(1, w.jobs.lists)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
            controller.startCommand(0, 3)
            pump()
            assertEquals(2, w.jobs.lists)
        } finally { controller.destroy() }
    }

    @Test fun deletedJobsDropOutOfTheSavedMap() = runTest(dispatcher) {
        val w = wire(approvalsOn = false, jobsOn = true)
        w.jobs.jobs = listOf(job("j1", "2030-01-01T00:00:00+00:00"))
        val controller = started(w)
        try {
            w.jobs.jobs = emptyList()
            tick()
            assertFalse(container.jobSeen.load().keys.any { it.endsWith(":j1") })
        } finally { controller.destroy() }
    }

    @Test fun syncStartsTheServiceForJobResultsAloneAndStopsWhenBothAreOff() = runTest(dispatcher) {
        wire(approvalsOn = false, jobsOn = true)
        ApprovalAlerts(container, context).sync()
        assertEquals(ApprovalWatchService::class.java.name,
            shadowOf(context.applicationContext as Application).peekNextStartedService()?.component?.className)
        container.settings.setJobAlerts(false)
        container.settings.values.first { !it.jobAlerts }
        ApprovalAlerts(container, context).sync()
        assertEquals(ApprovalWatchService::class.java.name,
            shadowOf(context.applicationContext as Application).nextStoppedService?.component?.className)
    }

    @Test fun staleJobSettingClearsWhenNoBridgeRemains() = runTest(dispatcher) {
        wire(approvalsOn = false, jobsOn = true, saveBridge = false)
        ApprovalAlerts(container, context).sync()
        assertFalse(container.settings.values.first().jobAlerts)
    }
}
