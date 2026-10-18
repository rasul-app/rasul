package app.rasul

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.rasul.bridge.ApprovalAnsweredDto
import app.rasul.bridge.ApprovalItemDto
import app.rasul.bridge.ApprovalsApi
import app.rasul.bridge.ApprovalsEvent
import app.rasul.bridge.ApprovalsRepository
import app.rasul.bridge.BridgeBotsRepository
import app.rasul.bridge.BridgeBotsState
import app.rasul.bridge.BridgeClients
import app.rasul.bridge.BridgeGroup
import app.rasul.bridge.BridgeProfile
import app.rasul.bridge.BridgeStore
import app.rasul.bridge.BridgeTransport
import app.rasul.bridge.ProfileSnapshot
import app.rasul.bridge.ProfilesApi
import app.rasul.bridge.SavedBridge
import app.rasul.bridge.bridgeBotId
import app.rasul.data.SecretCipher
import app.rasul.notifications.APPROVALS_CHANNEL
import app.rasul.notifications.APPROVALS_ROUTE
import app.rasul.notifications.APPROVAL_ALERT_ID
import app.rasul.notifications.ApprovalAlert
import app.rasul.notifications.ApprovalAlertDecider
import app.rasul.notifications.ApprovalAlerts
import app.rasul.notifications.ApprovalWatchService
import app.rasul.notifications.PAUSED_TAG
import app.rasul.notifications.WATCH_CHANNEL
import app.rasul.notifications.WATCH_ID
import app.rasul.security.AppLockController
import app.rasul.security.AppLockSettings
import app.rasul.notifications.approvalAlertTag
import app.rasul.notifications.buildApprovalNotification
import app.rasul.notifications.isApprovalsRoute
import app.rasul.settings.AppSettings
import app.rasul.settings.approvalAlertsRowVisible
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
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Base64
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalAlertsTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val container get() = (context.applicationContext as RasulApp).container
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    private val cipher = object : SecretCipher {
        override fun encrypt(plain: String) = plain.reversed()
        override fun decrypt(token: String) = token.reversed()
    }
    private fun bridge() = SavedBridge(UUID.randomUUID().toString(), "Studio PC", listOf("wss://example.invalid"),
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32)), UUID.randomUUID().toString(), UUID.randomUUID().toString())
    private fun item(id: String, expires: Double = 4_000_000_000.0) =
        ApprovalItemDto(id, "sk", "default", "shell", "SECRET-COMMAND-TEXT", "Run the cleanup.", true, false, expires)
    private fun feed(vararg items: ApprovalItemDto) =
        ApprovalsRepository.Feed(items = items.toList(), loading = false)

    private class FakeProfiles : ProfilesApi {
        val opened = CompletableDeferred<Unit>()
        override suspend fun list() = ProfileSnapshot(1, listOf(BridgeProfile("default", "default", "Research", true, true)))
        override fun events(): Flow<ProfileSnapshot> = flow { opened.complete(Unit); awaitCancellation() }
    }

    private class FakeApprovals : ApprovalsApi {
        val events = Channel<ApprovalsEvent>(Channel.UNLIMITED)
        val opened = CompletableDeferred<Unit>()
        var listResult: List<ApprovalItemDto> = emptyList()
        override suspend fun list() = listResult
        override fun events(): Flow<ApprovalsEvent> = flow {
            opened.complete(Unit)
            events.receiveAsFlow().collect { emit(it) }
        }
        override suspend fun answer(requestId: String, choice: String) = ApprovalAnsweredDto(requestId, "answered")
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

    private fun shadowContext() = shadowOf(context.applicationContext as Application)
    private fun alerts() = manager.activeNotifications.filter { it.id == APPROVAL_ALERT_ID }

    private suspend fun TestScope.wireService(
        settingOn: Boolean,
        saveBridge: Boolean = true,
        lockOn: Boolean = false,
        profiles: () -> ProfilesApi = { FakeProfiles() },
    ): FakeApprovals {
        // The container's own controller (watched by the run notifier) stays unlocked so only the service can redact.
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
        val bots = BridgeBotsRepository(bridgeStore, clients) { profiles() }
        val approvals = ApprovalsRepository(bridgeStore, clients, bots.state) { _, _ -> approvalsApi }
        install("settings", settings); install("bridges", bridgeStore)
        install("bridgeBots", bots); install("approvals", approvals)
        if (settingOn) {
            settings.setApprovalAlerts(true)
            settings.values.first { it.approvalAlerts }
        }
        return approvalsApi
    }

    /** Lets the feed writer's refetch debounce expire and the service publish. */
    private fun TestScope.pump() {
        advanceTimeBy(400); runCurrent(); runCurrent()
    }

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { scope.cancel(); Dispatchers.resetMain() }

    @Test fun newApprovalAlertsOnceAndSnapshotsDoNotRealert() {
        val key = bridgeBotId("b1", "default")
        val decider = ApprovalAlertDecider()
        val feeds = mapOf(key to feed(item("a".repeat(32))))
        val first = decider.diff(feeds, null, 1_000)
        assertEquals(1, first.first.size)
        assertEquals(approvalAlertTag(key, "a".repeat(32)), first.first.single().tag)
        assertTrue(first.second.isEmpty())
        val repeat = decider.diff(feeds, null, 1_001)
        assertTrue(repeat.first.isEmpty())
        assertTrue(repeat.second.isEmpty())
    }

    @Test fun approvalLeavingPendingCancelsItsNotification() {
        val key = bridgeBotId("b1", "default")
        val requestId = "a".repeat(32)
        val decider = ApprovalAlertDecider()
        decider.diff(mapOf(key to feed(item(requestId))), null, 1_000)
        val (alerts, gone) = decider.diff(mapOf(key to feed()), null, 1_001)
        assertTrue(alerts.isEmpty())
        assertEquals(listOf(approvalAlertTag(key, requestId)), gone)
        // A request returning as pending is a genuinely new alert.
        assertEquals(1, decider.diff(mapOf(key to feed(item(requestId))), null, 1_002).first.size)
    }

    @Test fun visibleApprovalsScreenSuppressesWithoutMarking() {
        val key = bridgeBotId("b1", "default")
        val decider = ApprovalAlertDecider()
        val feeds = mapOf(key to feed(item("a".repeat(32))))
        assertTrue(decider.diff(feeds, key, 1_000).first.isEmpty())
        // Leaving the screen while the request stays pending still posts it once.
        assertEquals(1, decider.diff(feeds, null, 1_001).first.size)
    }

    @Test fun expiredApprovalsNeverAlert() {
        val key = bridgeBotId("b1", "default")
        val decider = ApprovalAlertDecider()
        val feeds = mapOf(key to feed(item("a".repeat(32), expires = 999.0)))
        assertTrue(decider.diff(feeds, null, 1_000).first.isEmpty())
    }

    @Test fun approvalAlertsRowNeedsABridgeBot() {
        assertFalse(approvalAlertsRowVisible(BridgeBotsState()))
        assertFalse(approvalAlertsRowVisible(BridgeBotsState(listOf(BridgeGroup("b1", "PC", emptyList(), loading = false)))))
        assertTrue(approvalAlertsRowVisible(BridgeBotsState(listOf(
            BridgeGroup("b1", "PC", listOf(BridgeProfile("default", "default", "Research", true, true)), loading = false)))))
    }

    @Test fun settingOffLeavesTheServiceUnstarted() = runTest(dispatcher) {
        wireService(settingOn = false)
        ApprovalAlerts(container, context).sync()
        assertNull(shadowContext().peekNextStartedService())
        assertEquals(ApprovalWatchService::class.java.name,
            shadowContext().nextStoppedService?.component?.className)
    }

    @Test fun syncStartsTheServiceWhenEnabledAndBridged() = runTest(dispatcher) {
        wireService(settingOn = true)
        ApprovalAlerts(container, context).sync()
        assertEquals(ApprovalWatchService::class.java.name,
            shadowContext().peekNextStartedService()?.component?.className)
    }

    @Test fun staleSettingClearsWhenNoBridgeRemains() = runTest(dispatcher) {
        val settings = AppSettings(fakeStore())
        val bridgeStore = BridgeStore(fakeStore(), cipher)
        install("settings", settings); install("bridges", bridgeStore)
        settings.setApprovalAlerts(true)
        settings.values.first { it.approvalAlerts }
        ApprovalAlerts(container, context).sync()
        assertFalse(settings.values.first().approvalAlerts)
        assertEquals(ApprovalWatchService::class.java.name,
            shadowContext().nextStoppedService?.component?.className)
    }

    @Test fun newPendingApprovalPostsOnePrivateAlertAndResolveCancelsIt() = runTest(dispatcher) {
        val api = wireService(settingOn = true)
        val controller = Robolectric.buildService(ApprovalWatchService::class.java).create()
        try {
            container.bridgeBots.state.first { it.groups.any { g -> g.profiles.isNotEmpty() } }
            api.opened.await()
            val requestId = "a".repeat(32)
            api.events.send(ApprovalsEvent.Snapshot(listOf(item(requestId))))
            pump()
            val posted = alerts()
            assertEquals(1, posted.size)
            val notification = posted.single().notification
            assertEquals(APPROVALS_CHANNEL, notification.channelId)
            assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
            assertEquals(context.getString(R.string.approval_alert_title, "Research", "shell"),
                notification.extras.getCharSequence(Notification.EXTRA_TITLE))
            assertEquals("Run the cleanup.", notification.extras.getCharSequence(Notification.EXTRA_TEXT))
            assertTrue((notification.actions ?: emptyArray()).isEmpty())
            assertEquals(context.getString(R.string.approval_alert_waiting),
                notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE))
            // The ongoing watcher counts paired PCs and offers Stop.
            val watch = manager.activeNotifications.single { it.id == WATCH_ID }.notification
            assertEquals(WATCH_CHANNEL, watch.channelId)
            assertEquals(context.resources.getQuantityString(R.plurals.approval_watching, 1, 1),
                watch.extras.getCharSequence(Notification.EXTRA_TITLE))
            assertEquals(context.getString(R.string.approval_watch_stop), watch.actions?.single()?.title)
            // A repeated snapshot must not re-alert.
            api.events.send(ApprovalsEvent.Snapshot(listOf(item(requestId))))
            pump()
            assertEquals(1, alerts().size)
            // The request leaving the pending list cancels its notification.
            api.events.send(ApprovalsEvent.Snapshot(emptyList()))
            pump()
            assertTrue(alerts().isEmpty())
        } finally { controller.destroy() }
    }

    @Test fun approvalsScreenVisibleSuppressesTheSystemAlert() = runTest(dispatcher) {
        val api = wireService(settingOn = true)
        val controller = Robolectric.buildService(ApprovalWatchService::class.java).create()
        try {
            container.bridgeBots.state.first { it.groups.any { g -> g.profiles.isNotEmpty() } }
            api.opened.await()
            val key = container.approvals.state.value.keys.single()
            container.approvalsVisible.value = key
            val requestId = "b".repeat(32)
            api.events.send(ApprovalsEvent.Snapshot(listOf(item(requestId))))
            pump()
            assertTrue(alerts().isEmpty())
            // Closing the screen while the request is still pending posts it once.
            container.approvalsVisible.value = null
            pump()
            assertEquals(1, alerts().size)
        } finally { controller.destroy() }
    }

    @Test fun onTimeoutPostsOnePausedNoticeAndKeepsTheSettingOn() = runTest(dispatcher) {
        wireService(settingOn = true)
        val controller = Robolectric.buildService(ApprovalWatchService::class.java).create()
        try {
            controller.get().onTimeout(0, 0)
            val paused = manager.activeNotifications.filter { it.tag == PAUSED_TAG }
            assertEquals(1, paused.size)
            assertEquals(WATCH_CHANNEL, paused.single().notification.channelId)
            assertEquals(context.getString(R.string.approval_paused),
                paused.single().notification.extras.getCharSequence(Notification.EXTRA_TITLE))
            assertTrue(shadowOf(controller.get()).isStoppedBySelf)
            assertTrue(container.settings.values.first().approvalAlerts)
        } finally { controller.destroy() }
    }

    private class EmptyProfiles : ProfilesApi {
        override suspend fun list() = ProfileSnapshot(1, emptyList())
        override fun events(): Flow<ProfileSnapshot> = flow { awaitCancellation() }
    }

    @Test fun appLockOnPostsOnlyThePublicApprovalAlert() = runTest(dispatcher) {
        val api = wireService(settingOn = true, lockOn = true)
        val controller = Robolectric.buildService(ApprovalWatchService::class.java).create()
        try {
            container.bridgeBots.state.first { it.groups.any { g -> g.profiles.isNotEmpty() } }
            api.opened.await()
            api.events.send(ApprovalsEvent.Snapshot(listOf(item("a".repeat(32)))))
            pump()
            val shown = alerts().single().notification
            assertEquals(context.getString(R.string.approval_alert_waiting),
                shown.extras.getCharSequence(Notification.EXTRA_TITLE))
            assertNull(shown.extras.getCharSequence(Notification.EXTRA_TEXT))
            val everything = shown.extras.toString()
            assertFalse(everything.contains("Research"))
            assertFalse(everything.contains("Run the cleanup."))
            assertFalse(everything.contains("shell"))
        } finally { controller.destroy() }
    }

    @Test fun lockTurningOnRedactsAnAlreadyPostedApprovalAlert() = runTest(dispatcher) {
        val api = wireService(settingOn = true, lockOn = false)
        val controller = Robolectric.buildService(ApprovalWatchService::class.java).create()
        try {
            container.bridgeBots.state.first { it.groups.any { g -> g.profiles.isNotEmpty() } }
            api.opened.await()
            api.events.send(ApprovalsEvent.Snapshot(listOf(item("a".repeat(32)))))
            pump()
            assertEquals(context.getString(R.string.approval_alert_title, "Research", "shell"),
                alerts().single().notification.extras.getCharSequence(Notification.EXTRA_TITLE))
            container.appLock.setEnabled(true)
            pump()
            val shown = alerts().single().notification
            assertEquals(context.getString(R.string.approval_alert_waiting),
                shown.extras.getCharSequence(Notification.EXTRA_TITLE))
            assertFalse(shown.extras.toString().contains("Run the cleanup."))
        } finally { controller.destroy() }
    }

    @Test fun onTimeoutCancelsPostedApprovalAlerts() = runTest(dispatcher) {
        val api = wireService(settingOn = true)
        val controller = Robolectric.buildService(ApprovalWatchService::class.java).create()
        try {
            container.bridgeBots.state.first { it.groups.any { g -> g.profiles.isNotEmpty() } }
            api.opened.await()
            api.events.send(ApprovalsEvent.Snapshot(listOf(item("a".repeat(32)))))
            pump()
            assertEquals(1, alerts().size)
            controller.get().onTimeout(0, 0)
            assertTrue(alerts().isEmpty())
        } finally { controller.destroy() }
    }

    @Test fun deniedNotificationsDoNotMarkAnApprovalAsAlerted() = runTest(dispatcher) {
        val api = wireService(settingOn = true)
        val controller = Robolectric.buildService(ApprovalWatchService::class.java).create()
        try {
            container.bridgeBots.state.first { it.groups.any { g -> g.profiles.isNotEmpty() } }
            api.opened.await()
            shadowOf(manager).setNotificationsEnabled(false)
            api.events.send(ApprovalsEvent.Snapshot(listOf(item("a".repeat(32)))))
            pump()
            assertTrue(alerts().isEmpty())
            shadowOf(manager).setNotificationsEnabled(true)
            advanceTimeBy(30_001); runCurrent(); runCurrent()
            assertEquals(1, alerts().size)
        } finally { controller.destroy() }
    }

    @Test fun syncStopsWithoutChangingTheSettingWhenNotificationsAreDenied() = runTest(dispatcher) {
        wireService(settingOn = true)
        shadowOf(manager).setNotificationsEnabled(false)
        ApprovalAlerts(container, context).sync()
        assertNull(shadowContext().peekNextStartedService())
        assertEquals(ApprovalWatchService::class.java.name,
            shadowContext().nextStoppedService?.component?.className)
        assertTrue(container.settings.values.first().approvalAlerts)
    }

    @Test fun savedBridgeReadErrorNeverTurnsTheSettingOff() = runTest(dispatcher) {
        val settings = AppSettings(fakeStore())
        val store = fakeStore()
        BridgeStore(store, cipher).save(bridge())
        val broken = BridgeStore(store, object : SecretCipher {
            override fun encrypt(plain: String) = plain
            override fun decrypt(token: String): String = throw IllegalStateException("keystore unavailable")
        })
        install("settings", settings); install("bridges", broken)
        settings.setApprovalAlerts(true)
        settings.values.first { it.approvalAlerts }
        ApprovalAlerts(container, context).sync()
        assertTrue(settings.values.first().approvalAlerts)
    }

    @Test fun serviceStopsWhenNoBotRemainsButKeepsTheSetting() = runTest(dispatcher) {
        wireService(settingOn = true, profiles = { EmptyProfiles() })
        val controller = Robolectric.buildService(ApprovalWatchService::class.java).create()
        try {
            container.bridgeBots.state.first { it.loaded && it.groups.isNotEmpty() && it.groups.none { g -> g.loading } }
            pump()
            assertTrue(shadowOf(controller.get()).isStoppedBySelf)
            assertTrue(container.settings.values.first().approvalAlerts)
        } finally { controller.destroy() }
    }

    @Test fun arabicWatcherTextIsPermanentAndSingularForLargeCounts() {
        val configuration = android.content.res.Configuration(context.resources.configuration)
        configuration.setLocale(java.util.Locale.forLanguageTag("ar"))
        val arabic = context.createConfigurationContext(configuration)
        assertTrue(arabic.getString(R.string.approval_alerts_summary).contains("مستمرًا"))
        assertFalse(arabic.getString(R.string.approval_alerts_summary).contains("دائمًا"))
        val many = arabic.resources.getQuantityString(R.plurals.approval_watching, 100, 100)
        assertTrue(many, many.endsWith(" حاسوب"))
    }

    @Test fun notificationContentNeverExposesTheCommandText() {
        val requestId = "a".repeat(32)
        val alert = ApprovalAlert(approvalAlertTag("bridge:b1:default", requestId), "b1", "default", item(requestId))
        val notification = buildApprovalNotification(context, alert, "Research")
        assertEquals(context.getString(R.string.approval_alert_title, "Research", "shell"),
            notification.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertEquals("Run the cleanup.", notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals("Run the cleanup.", notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertFalse(notification.extras.toString().contains("SECRET-COMMAND-TEXT"))
        assertFalse(notification.publicVersion.extras.toString().contains("SECRET-COMMAND-TEXT"))
        assertEquals(context.getString(R.string.approval_alert_waiting),
            notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE))
        val saved = shadowOf(notification.contentIntent).savedIntent
        assertEquals("approvals/b1/default", saved.getStringExtra(APPROVALS_ROUTE))
    }

    @Test fun approvalsRoutesRequireWholeCanonicalSegments() {
        assertTrue(isApprovalsRoute("approvals/b1/default"))
        assertTrue(isApprovalsRoute("approvals/" + Uri.encode("id with space") + "/default"))
        listOf("approvals/", "approvals/a", "approvals/a/b/c", "approvals/%61/default", "other/a/b", "approvals/a/Default")
            .forEach { assertFalse(it, isApprovalsRoute(it)) }
    }
}
