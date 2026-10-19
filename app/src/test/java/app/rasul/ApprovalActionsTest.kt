package app.rasul

import android.app.Notification
import android.content.Context
import android.content.Intent
import app.rasul.bridge.ApprovalAnsweredDto
import app.rasul.bridge.ApprovalItemDto
import app.rasul.bridge.ApprovalsApi
import app.rasul.bridge.ApprovalsError
import app.rasul.bridge.ApprovalsEvent
import app.rasul.bridge.ApprovalsException
import app.rasul.notifications.ACTION_APPROVAL_ANSWER
import app.rasul.notifications.ApprovalAlert
import app.rasul.notifications.ApprovalAnswerRequest
import app.rasul.notifications.ApprovalAnswerUi
import app.rasul.notifications.ApprovalAnswerer
import app.rasul.notifications.EXTRA_APPROVAL_BRIDGE
import app.rasul.notifications.EXTRA_APPROVAL_CHOICE
import app.rasul.notifications.EXTRA_APPROVAL_PROFILE
import app.rasul.notifications.EXTRA_APPROVAL_REQUEST
import app.rasul.notifications.buildApprovalFailedNotification
import app.rasul.notifications.buildApprovalNotification
import app.rasul.notifications.parseApprovalAnswer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalActionsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val id = "0123456789abcdef0123456789abcdef"
    private val bridge = "11111111-2222-3333-4444-555555555555"

    private fun intent(
        action: String? = ACTION_APPROVAL_ANSWER, bridge: String? = this.bridge, profile: String? = "default",
        request: String? = id, choice: String? = "once",
    ) = Intent().setAction(action).apply {
        bridge?.let { putExtra(EXTRA_APPROVAL_BRIDGE, it) }
        profile?.let { putExtra(EXTRA_APPROVAL_PROFILE, it) }
        request?.let { putExtra(EXTRA_APPROVAL_REQUEST, it) }
        choice?.let { putExtra(EXTRA_APPROVAL_CHOICE, it) }
    }

    private fun alert() = ApprovalAlert("tag", bridge, "default",
        ApprovalItemDto(id, "sk", "default", "shell", "SECRET-COMMAND-TEXT", "Run the cleanup.", true, true, 4_000_000_000.0))

    @Test fun validExtrasParse() {
        val request = parseApprovalAnswer(intent())!!
        assertEquals(ApprovalAnswerRequest(bridge, "default", id, "once"), request)
        assertEquals("deny", parseApprovalAnswer(intent(choice = "deny"))!!.choice)
    }

    @Test fun malformedExtrasAreIgnored() {
        assertNull(parseApprovalAnswer(null))
        assertNull(parseApprovalAnswer(intent(action = "other")))
        assertNull(parseApprovalAnswer(intent(action = null)))
        assertNull(parseApprovalAnswer(intent(bridge = null)))
        assertNull(parseApprovalAnswer(intent(profile = null)))
        assertNull(parseApprovalAnswer(intent(request = null)))
        assertNull(parseApprovalAnswer(intent(choice = null)))
        assertNull(parseApprovalAnswer(intent(request = id.uppercase())))
        assertNull(parseApprovalAnswer(intent(request = id.dropLast(1))))
        assertNull(parseApprovalAnswer(intent(request = "$id/../x")))
        assertNull(parseApprovalAnswer(intent(profile = "Bad Profile")))
        assertNull(parseApprovalAnswer(intent(profile = "a/b")))
        assertNull(parseApprovalAnswer(intent(bridge = "a/b")))
        assertNull(parseApprovalAnswer(intent(bridge = "")))
        assertNull(parseApprovalAnswer(intent(choice = "session")))
        assertNull(parseApprovalAnswer(intent(choice = "always")))
        assertNull(parseApprovalAnswer(intent(choice = "ONCE")))
    }

    @Test fun builtAlertHasBothButtonsRequiringUnlock() {
        val notification = buildApprovalNotification(context, alert(), "Research", withActions = true)
        val actions = notification.actions
        assertEquals(listOf(context.getString(R.string.approval_action_once), context.getString(R.string.approval_action_deny)),
            actions.map { it.title.toString() })
        assertTrue(actions.all { it.isAuthenticationRequired })
        val choices = actions.map { action ->
            val shadow = org.robolectric.Shadows.shadowOf(action.actionIntent)
            assertTrue(shadow.isService)
            val saved = shadow.savedIntent
            assertEquals("app.rasul.notifications.ApprovalWatchService", saved.component?.className)
            assertEquals(ACTION_APPROVAL_ANSWER, saved.action)
            val parsed = parseApprovalAnswer(saved)!!
            assertEquals(id, parsed.requestId)
            parsed.choice
        }
        assertEquals(listOf("once", "deny"), choices)
        assertTrue(actions.all { (it.actionIntent.let { pi -> org.robolectric.Shadows.shadowOf(pi).flags } and android.app.PendingIntent.FLAG_IMMUTABLE) != 0 })
    }

    @Test @Config(sdk = [30]) fun androidBelow12GetsNoButtonsBecauseUnlockCannotBeRequired() {
        val notification = buildApprovalNotification(context, alert(), "Research", withActions = true)
        assertTrue((notification.actions ?: emptyArray()).isEmpty())
        assertEquals("Run the cleanup.", notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertNotNull(notification.contentIntent)
    }

    @Test fun alertsStayOnThePhoneAndRepostsDoNotRingAgain() {
        val first = buildApprovalNotification(context, alert(), "Research", withActions = true)
        assertTrue(first.flags and Notification.FLAG_LOCAL_ONLY != 0)
        assertTrue(first.flags and Notification.FLAG_ONLY_ALERT_ONCE == 0)
        val again = buildApprovalNotification(context, alert(), "Research", withActions = true, onlyAlertOnce = true)
        assertTrue(again.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        // Without the buttons (public only) the alert is the plain one and may reach a watch.
        assertTrue(buildApprovalNotification(context, alert(), "Research", publicOnly = true).flags and Notification.FLAG_LOCAL_ONLY == 0)
    }

    @Test @Config(sdk = [30]) fun androidBelow12AlertIsNotLocalOnly() {
        val notification = buildApprovalNotification(context, alert(), "Research", withActions = true)
        assertTrue(notification.flags and Notification.FLAG_LOCAL_ONLY == 0)
        assertTrue(notification.publicVersion.flags and Notification.FLAG_LOCAL_ONLY == 0)
    }

    @Test fun failedFallbackAlertHasTheReasonAndNoButtons() {
        val n = buildApprovalFailedNotification(context, "b1", "default", "PC offline")
        assertEquals("PC offline", n.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertTrue((n.actions ?: emptyArray()).isEmpty())
        assertNotNull(n.contentIntent)
        assertTrue(n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(app.rasul.notifications.APPROVAL_NOTICE_TIMEOUT_MS, n.timeoutAfter)
    }

    @Test fun publicOrPlainAlertsCarryNoButtons() {
        val publicOnly = buildApprovalNotification(context, alert(), "Research", publicOnly = true, withActions = true)
        assertTrue((publicOnly.actions ?: emptyArray()).isEmpty())
        val full = buildApprovalNotification(context, alert(), "Research", withActions = true)
        assertTrue((full.publicVersion.actions ?: emptyArray()).isEmpty())
        assertTrue((buildApprovalNotification(context, alert(), "Research").actions ?: emptyArray()).isEmpty())
    }

    @Test fun errorTextLeadsTheRepostedAlertWithoutTheCommand() {
        val text = buildApprovalNotification(context, alert(), "Research", withActions = true, errorText = "No route")
            .extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(text.startsWith("No route"))
        assertFalse(text.contains("SECRET-COMMAND-TEXT"))
    }

    private class RecordingUi : ApprovalAnswerUi {
        val events = mutableListOf<String>()
        override fun sending(tag: String) { events += "sending" }
        override fun finished(tag: String) { events += "finished" }
        override fun settledElsewhere(tag: String, textRes: Int) { events += "notice:$textRes" }
        override fun failed(tag: String, error: ApprovalsError) { events += "failed:$error" }
    }

    private class FakeApi(val result: suspend () -> ApprovalAnsweredDto) : ApprovalsApi {
        var calls = 0
        override suspend fun list() = emptyList<ApprovalItemDto>()
        override fun events(): kotlinx.coroutines.flow.Flow<ApprovalsEvent> = kotlinx.coroutines.flow.emptyFlow()
        override suspend fun answer(requestId: String, choice: String): ApprovalAnsweredDto { calls++; return result() }
    }

    private val request get() = ApprovalAnswerRequest(bridge, "default", id, "once")

    private suspend fun outcome(throwable: Throwable?): List<String> {
        val ui = RecordingUi()
        val api = FakeApi { throwable?.let { throw it }; ApprovalAnsweredDto(id, "answered") }
        ApprovalAnswerer({ _, _ -> api }, ui).handle(request)
        return ui.events
    }

    @Test fun okCancelsTheAlert() = runTest {
        assertEquals(listOf("sending", "finished"), outcome(null))
    }

    @Test fun conflictNoticesAlreadyAnswered() = runTest {
        assertEquals(listOf("sending", "notice:${R.string.approval_notice_answered}"),
            outcome(ApprovalsException(ApprovalsError.Conflict)))
    }

    @Test fun notFoundAndExpiredNoticeExpiry() = runTest {
        val expected = listOf("sending", "notice:${R.string.approval_notice_expired}")
        assertEquals(expected, outcome(ApprovalsException(ApprovalsError.NotFound)))
        assertEquals(expected, outcome(ApprovalsException(ApprovalsError.Expired)))
    }

    @Test fun transportErrorRepostsWithTheError() = runTest {
        assertEquals(listOf("sending", "failed:${ApprovalsError.Unreachable}"), outcome(IOException("down")))
    }

    @Test fun forbiddenRepostsWithTheRevokedError() = runTest {
        assertEquals(listOf("sending", "failed:${ApprovalsError.DeviceRevoked}"),
            outcome(ApprovalsException(ApprovalsError.DeviceRevoked)))
    }

    @Test fun removedBridgeRepostsWithTheError() = runTest {
        val ui = RecordingUi()
        ApprovalAnswerer({ _, _ -> null }, ui).handle(request)
        assertEquals(listOf("sending", "failed:${ApprovalsError.BridgeRemoved}"), ui.events)
    }

    @Test fun secondTapWhileInFlightIsIgnored() = runTest {
        val gate = CompletableDeferred<Unit>()
        val ui = RecordingUi()
        val api = FakeApi { gate.await(); ApprovalAnsweredDto(id, "answered") }
        val answerer = ApprovalAnswerer({ _, _ -> api }, ui)
        val first = launch { answerer.handle(request) }
        runCurrent()
        answerer.handle(request.copy(choice = "deny"))
        assertEquals(1, api.calls)
        assertEquals(listOf("sending"), ui.events)
        gate.complete(Unit)
        first.join()
        assertEquals(listOf("sending", "finished"), ui.events)
        // Once settled, the same id may be answered again (e.g. after a failed attempt re-posted the alert).
        answerer.handle(request)
        assertEquals(2, api.calls)
    }
}
