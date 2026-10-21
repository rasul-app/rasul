package app.rasul.notifications

import android.content.Intent
import android.net.Uri
import app.rasul.R
import app.rasul.bridge.APPROVAL_REQUEST_ID
import app.rasul.bridge.ApprovalsApi
import app.rasul.bridge.ApprovalsError
import app.rasul.bridge.ApprovalsException
import app.rasul.bridge.approvalsTransportError
import app.rasul.bridge.bridgeBotId
import kotlinx.coroutines.CancellationException

internal const val ACTION_APPROVAL_ANSWER = "app.rasul.notifications.APPROVAL_ANSWER"
internal const val EXTRA_APPROVAL_BRIDGE = "approval_bridge"
internal const val EXTRA_APPROVAL_PROFILE = "approval_profile"
internal const val EXTRA_APPROVAL_REQUEST = "approval_request"
internal const val EXTRA_APPROVAL_CHOICE = "approval_choice"

internal const val APPROVAL_NOTICE_ID = 7
internal const val APPROVAL_NOTICE_TIMEOUT_MS = 10_000L
internal const val APPROVAL_SENDING_TIMEOUT_MS = 60_000L

/** The only choices a notification button carries; Session and Always stay in the approvals sheet. */
internal val NOTIFICATION_CHOICES = setOf("once", "deny")

/** A validated tap on an Approve once / Deny button. */
internal data class ApprovalAnswerRequest(val bridgeId: String, val profileId: String, val requestId: String, val choice: String) {
    val botKey get() = bridgeBotId(bridgeId, profileId)
    val tag get() = approvalAlertTag(botKey, requestId)
}

/** Reads the answer extras; anything malformed (or a choice other than once/deny) yields null. */
internal fun parseApprovalAnswer(intent: Intent?): ApprovalAnswerRequest? {
    if (intent?.action != ACTION_APPROVAL_ANSWER) return null
    val bridge = intent.getStringExtra(EXTRA_APPROVAL_BRIDGE) ?: return null
    val profile = intent.getStringExtra(EXTRA_APPROVAL_PROFILE) ?: return null
    val request = intent.getStringExtra(EXTRA_APPROVAL_REQUEST) ?: return null
    val choice = intent.getStringExtra(EXTRA_APPROVAL_CHOICE) ?: return null
    if (!APPROVAL_REQUEST_ID.matches(request) || choice !in NOTIFICATION_CHOICES) return null
    // Bridge ids are plain tokens: nothing that needs percent-encoding.
    if (Uri.encode(bridge) != bridge) return null
    if (!isApprovalsRoute("approvals/" + Uri.encode(bridge) + "/" + Uri.encode(profile))) return null
    return ApprovalAnswerRequest(bridge, profile, request, choice)
}

/** What the answerer shows; the service maps these onto notifications. */
internal interface ApprovalAnswerUi {
    /** The alert becomes "Sending…" without buttons. */
    fun sending(tag: String)

    /** The request is settled: remove its notification. */
    fun finished(tag: String)

    /** Remove the alert and post a short silent notice. */
    fun settledElsewhere(tag: String, textRes: Int)

    /** Put the alert back with its buttons and [error] as the reason. */
    fun failed(tag: String, error: ApprovalsError)
}

/**
 * Sends one notification answer through the bot's own [ApprovalsApi]. A request id with an answer
 * in flight ignores further taps. The command text is never read here, so it cannot reach a log.
 */
internal class ApprovalAnswerer(
    private val apiFor: suspend (bridgeId: String, profileId: String) -> ApprovalsApi?,
    private val ui: ApprovalAnswerUi,
) {
    private val inFlight = mutableSetOf<String>()

    suspend fun handle(request: ApprovalAnswerRequest) {
        // Keyed by request id alone: ids are random 128-bit values, one answer per request.
        if (!inFlight.add(request.requestId)) return
        try {
            ui.sending(request.tag)
            try {
                val api = apiFor(request.bridgeId, request.profileId)
                if (api == null) { ui.failed(request.tag, ApprovalsError.BridgeRemoved); return }
                api.answer(request.requestId, request.choice)
                ui.finished(request.tag)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                when (val error = (e as? ApprovalsException)?.error ?: approvalsTransportError(e)) {
                    ApprovalsError.Conflict -> ui.settledElsewhere(request.tag, R.string.approval_notice_answered)
                    ApprovalsError.NotFound, ApprovalsError.Expired -> ui.settledElsewhere(request.tag, R.string.approval_notice_expired)
                    else -> ui.failed(request.tag, error)
                }
            }
        } finally {
            inFlight.remove(request.requestId)
        }
    }
}
