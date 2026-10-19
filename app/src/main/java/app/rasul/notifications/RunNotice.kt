package app.rasul.notifications

import android.content.Context
import app.rasul.R
import app.rasul.chat.ChatFailure
import app.rasul.files.FileLinks
import app.rasul.chat.ChatState
import app.rasul.chat.MessageKind
import app.rasul.chat.StatusLabel
import app.rasul.data.HermesError
import app.rasul.data.RunEvent
import app.rasul.ui.rateLimitMessage

data class RunNotice(val active: Boolean, val text: String, val approval: Boolean = false, val openOnly: Boolean = false)

fun ChatState.hasActiveWork(): Boolean =
    run?.status != StatusLabel.Lost && run?.status != StatusLabel.LiveUpdatesUnavailable &&
        (run != null || queuedReplies > 0 || preparingSend || uploading)

/** Nothing runs, waits to be sent or uploads: the chat may be replaced or closed without losing work. */
fun ChatState.isIdle(): Boolean = run == null && queuedReplies == 0 && !preparingSend && !uploading

fun notificationText(publicText: String, privateText: String, lockEnabled: Boolean): String =
    if (lockEnabled) publicText else privateText

/** A monotonic clock gate; completion is published separately from silent progress. */
class UpdateThrottle(private val intervalMs: Long = 1_000L) {
    private var last: Long? = null
    fun allow(now: Long): Boolean {
        if (last?.let { now - it < intervalMs } == true) return false
        last = now
        return true
    }
}

fun runNotice(context: Context, state: ChatState): RunNotice {
    fun text(id: Int, vararg args: Any) = context.getString(id, *args)
    val run = state.run
    if (run == null && state.terminal == null && state.terminalFailure == null && (state.preparingSend || state.queuedReplies > 0)) return RunNotice(true, text(R.string.status_thinking))
    if (run == null && state.terminal == null && state.terminalFailure == null && state.uploading) return RunNotice(true, text(R.string.chat_uploading))
    if (run?.status == StatusLabel.Lost) return RunNotice(false, text(R.string.run_connection_lost), openOnly = true)
    if (run?.status == StatusLabel.LiveUpdatesUnavailable) return RunNotice(false, text(R.string.run_live_updates_stopped), openOnly = true)
    if (run != null) {
        if (state.approval != null) return RunNotice(true, text(R.string.status_waiting_approval), true)
        val activity = when (val status = run.label) {
            is StatusLabel.Tool -> text(R.string.status_tool, status.name)
            StatusLabel.Writing -> state.messages.lastOrNull { it.kind == MessageKind.Assistant }?.text
                ?.let { FileLinks.plainText(it) }?.lineSequence()?.filter { it.isNotBlank() }?.lastOrNull()?.take(600) ?: text(R.string.status_writing)
            StatusLabel.Stopping -> text(R.string.status_stopping)
            is StatusLabel.Reconnecting -> text(R.string.status_reconnecting, status.attempt, status.max)
            StatusLabel.Lost -> state.messages.lastOrNull { it.error != null }?.error?.let { failureText(context, it) }
                ?: text(R.string.status_lost)
            StatusLabel.LiveUpdatesUnavailable -> text(R.string.status_live_unavailable)
            else -> text(R.string.status_thinking)
        }
        return RunNotice(true, activity)
    }
    val body = when (val event = state.terminal) {
        is RunEvent.Completed -> FileLinks.plainText(event.output).take(600).ifBlank { text(R.string.run_reply_complete) }
        is RunEvent.Failed -> text(R.string.chat_err_run_failed, event.error.orEmpty())
        is RunEvent.Interrupted -> text(R.string.chat_err_run_interrupted, event.error.orEmpty())
        is RunEvent.Cancelled -> text(R.string.run_reply_stopped)
        else -> state.terminalFailure?.let { failureText(context, it) }
            ?: (state.load as? app.rasul.chat.ChatLoad.Failed)?.failure?.let { failureText(context, it) }
            ?: state.historyFailure?.let { failureText(context, it) }
            ?: text(R.string.run_reply_complete)
    }
    return RunNotice(false, body.take(600))
}

fun failureText(context: Context, failure: ChatFailure): String {
    fun text(id: Int, vararg args: Any) = context.getString(id, *args)
    return when (failure) {
        is ChatFailure.Api -> when (val error = failure.error) {
            HermesError.BridgeUnreachable -> text(R.string.bridge_unreachable)
            HermesError.DeviceRevoked -> text(R.string.bridge_revoked)
            HermesError.DeviceKeyRepair -> text(R.string.bridge_key_repair)
            HermesError.BridgeTimeout -> text(R.string.bridge_timeout)
            HermesError.BridgeLinkDropped -> text(R.string.bridge_link_dropped)
            HermesError.BridgeSecure -> text(R.string.bridge_secure_failed)
            HermesError.BridgeAddressUnknown -> text(R.string.bridge_address_unknown)
            HermesError.BridgeRefused -> text(R.string.bridge_refused)
            HermesError.ProfileNotReady -> text(R.string.bridge_profile_not_ready)
            HermesError.BridgeResponseInvalid -> text(R.string.bridge_response_invalid)
            HermesError.Unauthorized -> text(R.string.err_unauthorized)
            HermesError.NotFound -> text(R.string.chat_err_run_gone)
            HermesError.ResourceNotFound -> text(R.string.err_resource_not_found)
            HermesError.ConnectionChanged -> text(R.string.err_connection_changed)
            HermesError.ChatBusy -> text(R.string.err_chat_busy)
            is HermesError.Storage -> text(R.string.connections_load_error, error.detail)
            is HermesError.ProfileUnknown -> text(R.string.err_profile_unknown, error.profile)
            is HermesError.Unreachable -> text(R.string.err_unreachable, error.detail)
            is HermesError.Incompatible -> text(R.string.err_incompatible, error.missing.joinToString())
            is HermesError.Server -> rateLimitMessage(error, context) ?: text(R.string.err_server, error.code)
            is HermesError.InvalidAddress -> text(R.string.addr_invalid)
            HermesError.AddressRejected -> text(R.string.addr_cleartext_rejected)
            HermesError.InvalidKey -> text(R.string.err_invalid_key)
            is HermesError.Redirected -> text(R.string.err_redirected, error.code)
        }
        ChatFailure.SteerRejected -> text(R.string.chat_err_steer_rejected)
        is ChatFailure.RunFailed -> text(R.string.chat_err_run_failed, failure.detail.orEmpty())
        is ChatFailure.RunInterrupted -> text(R.string.chat_err_run_interrupted, failure.detail.orEmpty())
        ChatFailure.ConnectionMissing -> text(R.string.chat_err_connection_missing)
        ChatFailure.KeyMissing -> text(R.string.chat_err_key_missing)
        ChatFailure.PreviousRunActive -> text(R.string.chat_err_previous_run_active)
        ChatFailure.SessionUnavailable -> text(R.string.chat_err_session_unavailable)
        ChatFailure.AttachmentTooLarge -> text(R.string.chat_err_attach_too_large)
        ChatFailure.FileTypeUnsupported -> text(R.string.chat_err_attach_file_type)
        ChatFailure.FileTooLarge -> text(R.string.chat_err_attach_file_too_large)
        ChatFailure.PhotoTooLarge -> text(R.string.chat_err_attach_photo_too_large)
        ChatFailure.PhotoUnreadable -> text(R.string.chat_err_attach_photo_unreadable)
        ChatFailure.FileUnreadable -> text(R.string.chat_err_attach_file_unreadable)
        ChatFailure.AttachmentKindMixed -> text(R.string.chat_err_attach_mixed)
        ChatFailure.AttachmentTooManyPhotos -> text(R.string.chat_err_attach_too_many)
        ChatFailure.AttachmentRunActive -> text(R.string.chat_err_attach_run_active)
        ChatFailure.FilesNeedBridge -> text(R.string.chat_attach_needs_bridge)
        is ChatFailure.UploadTooLarge -> text(R.string.chat_err_upload_too_large, failure.name)
        is ChatFailure.UploadEmpty -> text(R.string.chat_err_upload_empty, failure.name)
        ChatFailure.TooManyFiles -> text(R.string.chat_err_upload_too_many)
        ChatFailure.FilesMixed -> text(R.string.chat_err_upload_mixed)
        ChatFailure.BridgeNeedsUpdate -> text(R.string.chat_err_upload_old_bridge)
        ChatFailure.PcDiskFull -> text(R.string.chat_err_upload_disk_full)
        ChatFailure.TooManyOpenUploads -> text(R.string.chat_err_upload_open_limit)
        ChatFailure.PcSaveFailed -> text(R.string.chat_err_upload_save_failed)
        ChatFailure.UploadBotMissing -> text(R.string.chat_err_upload_bot_missing)
        ChatFailure.RunInputRefused -> text(R.string.chat_err_run_input_refused)
        is ChatFailure.FilesGone ->
            if (failure.names.size == 1) text(R.string.chat_err_upload_gone, failure.names.single())
            else text(R.string.chat_err_upload_gone_many, failure.names.joinToString(", "))
        ChatFailure.ReplyKept -> text(R.string.chat_err_reply_kept)
        is ChatFailure.ForkFailed -> app.rasul.chat.sessionActionMessage(context, failure.error)
            ?: failureText(context, ChatFailure.Api(failure.error))
        is ChatFailure.ShareItemsDropped -> context.resources.getQuantityString(R.plurals.share_dropped, failure.count, failure.count) +
            failure.other?.let { " " + failureText(context, it) }.orEmpty()
    }
}
