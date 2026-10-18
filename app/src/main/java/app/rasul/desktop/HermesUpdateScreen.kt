package app.rasul.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.rasul.R
import app.rasul.bridge.DesktopError
import app.rasul.bridge.HermesChangeDto
import app.rasul.bridge.HermesUpdateDto
import app.rasul.bridge.HermesUpdateResultDto
import app.rasul.bridge.UPDATE_TOKENS
import app.rasul.data.timeText
import app.rasul.ui.components.ChipKind
import app.rasul.ui.components.ConfirmDialog
import app.rasul.ui.components.InsetGroup
import app.rasul.ui.components.NoticeDialog
import app.rasul.ui.components.PrimaryButton
import app.rasul.ui.components.RasulRow
import app.rasul.ui.components.RowSub
import app.rasul.ui.components.RowTitle
import app.rasul.ui.components.SecondaryButton
import app.rasul.ui.components.SectionLabel
import app.rasul.ui.components.StatusChip
import app.rasul.ui.theme.Rasul
import app.rasul.ui.theme.RasulTheme
import app.rasul.ui.theme.RasulType
import kotlinx.serialization.json.JsonPrimitive

/** The message for a status `reason` (why [HermesUpdateDto.canApply] is false), or null for an unknown token. */
internal fun updateReasonError(reason: String?): DesktopError? = reason?.let { UPDATE_TOKENS[it] }

/** Which chip an idle status gets: "Up to date" only when the bridge said so, never when it could not check. */
internal enum class IdleChip { Available, UpToDate, Unknown }

internal fun idleChip(status: HermesUpdateDto): IdleChip = when {
    status.updateAvailable -> IdleChip.Available
    status.reason == "up_to_date" -> IdleChip.UpToDate
    else -> IdleChip.Unknown
}

/** A version to show, or null when it is blank or the bridge's own "unknown" placeholder. */
internal fun knownVersion(version: String?): String? =
    version?.trim()?.takeIf { it.isNotEmpty() && !it.equals("unknown", ignoreCase = true) }

/** The fixed result sentences of the bridge (updater.py) with their own EN + AR strings. */
private val RESULT_MESSAGES: Map<String, Int> = mapOf(
    "Cancelled." to R.string.hupd_res_cancelled,
    "The queued update expired." to R.string.hupd_res_expired,
    "The update stopped unexpectedly." to R.string.hupd_res_stopped,
    "Update completed." to R.string.hupd_res_completed,
    "Update completed with some steps skipped." to R.string.hupd_res_skipped,
    "Update did not complete." to R.string.hupd_res_not_completed,
    "The update did not report a result in time." to R.string.hupd_res_no_report,
    "The update could not be started." to R.string.hupd_res_not_started,
)

internal fun updateResultMessage(message: String?): Int? = message?.let { RESULT_MESSAGES[it] }

@Composable
private fun resultText(message: String?): String =
    updateResultMessage(message)?.let { stringResource(it) } ?: message.orEmpty()

/** The small line above the versions: why the last poll or manual refresh failed (null when nothing did). */
internal fun updateNotice(state: HermesUpdateState): Int? {
    state.pollError?.let { error ->
        return when (error) {
            DesktopError.Unreachable, DesktopError.Timeout, DesktopError.Unavailable -> R.string.hupd_link_lost
            else -> error.message
        }
    }
    return state.error?.message
}

/** Hermes update screen of one PC: what is new, update when idle or now, queue and progress. */
@Composable
fun HermesUpdateScreen(vm: HermesUpdateViewModel, targetName: String, onBack: () -> Unit) {
    val state by vm.state.collectAsState()
    var nowAsk by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, vm) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> vm.onVisible()
                Lifecycle.Event.ON_STOP -> vm.onHidden()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) vm.onVisible()
        onDispose { lifecycle.removeObserver(observer); vm.onHidden() }
    }
    HermesUpdateContent(
        state, targetName, onBack, vm::refresh,
        onWhenIdle = vm::updateWhenIdle, onNowAsk = { nowAsk = true }, onCancel = vm::cancel,
    )
    if (nowAsk) {
        val agents = state.status?.activeAgents
        ConfirmDialog(
            title = stringResource(R.string.hupd_now_title),
            body = nowBody(agents),
            cancelText = stringResource(R.string.cancel),
            confirmText = stringResource(R.string.hupd_now),
            onCancel = { nowAsk = false },
            onConfirm = { nowAsk = false; vm.updateNow() },
            destructive = (agents ?: 0) > 0,
        )
    }
    state.actionError?.let { NoticeDialog(stringResource(it.message), stringResource(R.string.ok), vm::dismissActionError) }
}

@Composable
private fun nowBody(agents: Int?): String = when {
    agents == null -> stringResource(R.string.hupd_now_body_unknown)
    agents == 0 -> stringResource(R.string.hupd_now_body_idle)
    else -> pluralStringResource(R.plurals.hupd_now_body_busy, agents, agents)
}

@Composable
internal fun HermesUpdateContent(
    state: HermesUpdateState,
    targetName: String,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onWhenIdle: () -> Unit,
    onNowAsk: () -> Unit,
    onCancel: () -> Unit,
) {
    val c = Rasul.colors
    Column(Modifier.fillMaxSize().background(c.bg)) {
        DesktopHeader(R.string.hupd_title, targetName, onBack, onRetry.takeIf { !state.direct && !state.busy }, R.string.hupd_refresh)
        val status = state.status
        when {
            state.direct -> DesktopMessage(stringResource(R.string.hupd_needs_bridge))
            state.loading && status == null -> LoadingBlock(R.string.hupd_loading)
            status == null -> DesktopMessage(stringResource((state.error ?: DesktopError.DesktopFailed).message), onRetry)
            else -> StatusBody(state, status, onWhenIdle, onNowAsk, onCancel)
        }
    }
}

@Composable
private fun LoadingBlock(label: Int) {
    val c = Rasul.colors
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(color = c.accent)
            Text(stringResource(label), style = RasulType.body, color = c.muted)
        }
    }
}

@Composable
private fun StatusBody(
    state: HermesUpdateState,
    status: HermesUpdateDto,
    onWhenIdle: () -> Unit,
    onNowAsk: () -> Unit,
    onCancel: () -> Unit,
) {
    val c = Rasul.colors
    val unknown = stringResource(R.string.hupd_unknown)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        updateNotice(state)?.let { notice ->
            Text(
                stringResource(notice), style = RasulType.bodySmall, color = c.muted,
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp),
            )
        }
        SectionLabel(stringResource(R.string.hupd_versions))
        InsetGroup {
            VersionRow(R.string.hupd_current, knownVersion(status.current) ?: unknown, last = false)
            VersionRow(R.string.hupd_latest, knownVersion(status.latest?.take(7)) ?: unknown, last = false)
            VersionRow(
                R.string.hupd_agents, status.activeAgents?.let { stringResource(R.string.check_count, it) } ?: unknown, last = true,
            )
        }
        StateBlock(status)
        ChangesBlock(status.changes)
        ActionsBlock(state, status, onWhenIdle, onNowAsk, onCancel)
    }
}

@Composable
private fun VersionRow(label: Int, value: String, last: Boolean) {
    RasulRow(
        last = last, hairlineStart = 16.dp, hairlineEnd = 16.dp, minHeight = 44.dp,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        trailing = { Text(value, style = RasulType.body, color = Rasul.colors.muted, maxLines = 1) },
    ) { RowTitle(stringResource(label)) }
}

@Composable
private fun StateBlock(status: HermesUpdateDto) {
    val c = Rasul.colors
    val pad = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp)
    when (status.state) {
        HermesUpdateDto.STATE_QUEUED -> Column(pad, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusChip(stringResource(R.string.hupd_chip_queued), ChipKind.Running)
            val since = timeText(status.queuedAt?.let { JsonPrimitive(it) })
            Text(
                if (since != null) stringResource(R.string.hupd_queued_since, since) else stringResource(R.string.hupd_queued),
                style = RasulType.body, color = c.ink,
            )
            Text(stringResource(R.string.hupd_queued_hint), style = RasulType.bodySmall, color = c.muted)
        }
        HermesUpdateDto.STATE_RUNNING -> Row(pad, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            // An endless animation never lets a screenshot settle, so previews draw a fixed ring.
            if (LocalInspectionMode.current) {
                CircularProgressIndicator(progress = { 0.3f }, modifier = Modifier.size(24.dp), color = c.accent)
            } else {
                CircularProgressIndicator(Modifier.size(24.dp), color = c.accent)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.hupd_running), style = RasulType.body, color = c.ink)
                Text(stringResource(R.string.hupd_running_hint), style = RasulType.bodySmall, color = c.muted)
            }
        }
        HermesUpdateDto.STATE_DONE, HermesUpdateDto.STATE_FAILED -> ResultBlock(status, pad)
        else -> IdleBlock(status, pad)
    }
}

@Composable
private fun IdleBlock(status: HermesUpdateDto, pad: Modifier) {
    val c = Rasul.colors
    Column(pad, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (idleChip(status)) {
            IdleChip.Available -> {
                StatusChip(stringResource(R.string.hupd_chip_available), ChipKind.Running)
                if (status.behind != null && status.behind > 0) {
                    Text(pluralStringResource(R.plurals.hupd_behind, status.behind, status.behind), style = RasulType.body, color = c.ink)
                }
            }
            IdleChip.UpToDate -> StatusChip(stringResource(R.string.hupd_chip_current), ChipKind.Done)
            IdleChip.Unknown -> StatusChip(stringResource(R.string.hupd_chip_unknown), ChipKind.Neutral)
        }
        // A reason explains why nothing can be started right now; "up to date" is the normal idle line.
        if (!status.canApply && status.reason != "up_to_date") {
            val message = updateReasonError(status.reason)?.message ?: R.string.hupd_reason_unknown
            Text(stringResource(message), style = RasulType.bodySmall, color = if (status.updateAvailable) c.dangerText else c.muted)
        }
        status.result?.takeIf { !it.ok && !it.message.isNullOrBlank() }?.let {
            Text(resultText(it.message), style = RasulType.bodySmall, color = c.muted)
        }
    }
}

@Composable
private fun ResultBlock(status: HermesUpdateDto, pad: Modifier) {
    val c = Rasul.colors
    val result = status.result
    val ok = status.state == HermesUpdateDto.STATE_DONE && result?.ok != false
    Column(pad, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StatusChip(
            stringResource(if (ok) R.string.hupd_chip_done else R.string.hupd_chip_failed),
            if (ok) ChipKind.Done else ChipKind.Failed,
        )
        val from = knownVersion(result?.from)
        val to = knownVersion(result?.to)
        if (from != null && to != null) {
            Text(stringResource(R.string.hupd_from_to, from, to), style = RasulType.body, color = c.ink)
        }
        result?.message?.takeIf { it.isNotBlank() }?.let { Text(resultText(it), style = RasulType.bodySmall, color = c.muted) }
    }
}

@Composable
private fun ChangesBlock(changes: List<HermesChangeDto>) {
    val c = Rasul.colors
    SectionLabel(stringResource(R.string.hupd_whats_new), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp))
    InsetGroup {
        if (changes.isEmpty()) {
            Text(
                stringResource(R.string.hupd_changes_empty), style = RasulType.body, color = c.muted,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        } else {
            changes.forEachIndexed { index, change ->
                RasulRow(
                    last = index == changes.lastIndex, hairlineStart = 16.dp, hairlineEnd = 16.dp,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                ) { RowSub(change.title, color = c.ink, maxLines = 3) }
            }
        }
    }
}

@Composable
private fun ActionsBlock(
    state: HermesUpdateState,
    status: HermesUpdateDto,
    onWhenIdle: () -> Unit,
    onNowAsk: () -> Unit,
    onCancel: () -> Unit,
) {
    val c = Rasul.colors
    val pad = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp)
    when {
        status.isRunning -> {}
        status.isQueued -> SecondaryButton(
            stringResource(R.string.hupd_cancel), onCancel, enabled = !state.busy, modifier = pad,
        )
        status.canApply -> Column(pad, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(stringResource(R.string.hupd_when_idle), onWhenIdle, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.hupd_when_idle_hint), style = RasulType.bodySmall, color = c.muted)
            SecondaryButton(stringResource(R.string.hupd_now), onNowAsk, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
        }
    }
}

// ---------- previews ----------

private val previewChanges = listOf(
    HermesChangeDto("Faster startup for the desktop dashboard"),
    HermesChangeDto("Fix: scheduled jobs no longer skip a run after sleep"),
    HermesChangeDto("New: per-profile tool limits"),
)

private val available = HermesUpdateDto(
    current = "0.21.4", latest = "9f3c2ab4e1d0", behind = 6, canApply = true, changes = previewChanges, activeAgents = 1,
)
private val queued = available.copy(state = HermesUpdateDto.STATE_QUEUED, queuedAt = "2026-10-18T09:30:00+00:00")
private val running = available.copy(state = HermesUpdateDto.STATE_RUNNING, canApply = false, reason = "update_in_progress", activeAgents = 0)
private val done = available.copy(
    state = HermesUpdateDto.STATE_DONE, behind = 0, canApply = false, reason = "up_to_date", activeAgents = 0,
    result = HermesUpdateResultDto(true, "0.21.4", "0.22.0", "Update completed."),
)
private val failed = available.copy(
    state = HermesUpdateDto.STATE_FAILED,
    result = HermesUpdateResultDto(false, "0.21.4", "0.21.4", "The update stopped unexpectedly."),
)

@Composable
private fun UpdatePreview(status: HermesUpdateDto) = HermesUpdateContent(
    HermesUpdateState(status = status), "My PC", {}, {}, {}, {}, {},
)

@Preview(showBackground = true, widthDp = 390, heightDp = 780, name = "HermesUpdateAvailable")
@Composable
private fun HermesUpdateAvailableEn() = RasulTheme(false) { UpdatePreview(available) }

@Preview(showBackground = true, widthDp = 390, heightDp = 780, locale = "ar", name = "HermesUpdateAvailable")
@Composable
private fun HermesUpdateAvailableAr() = RasulTheme(false) { UpdatePreview(available) }

@Preview(showBackground = true, widthDp = 390, heightDp = 780, name = "HermesUpdateQueued")
@Composable
private fun HermesUpdateQueuedEn() = RasulTheme(false) { UpdatePreview(queued) }

@Preview(showBackground = true, widthDp = 390, heightDp = 780, locale = "ar", name = "HermesUpdateQueued")
@Composable
private fun HermesUpdateQueuedAr() = RasulTheme(false) { UpdatePreview(queued) }

@Preview(showBackground = true, widthDp = 390, heightDp = 780, name = "HermesUpdateRunning")
@Composable
private fun HermesUpdateRunningEn() = RasulTheme(false) { UpdatePreview(running) }

@Preview(showBackground = true, widthDp = 390, heightDp = 780, locale = "ar", name = "HermesUpdateRunning")
@Composable
private fun HermesUpdateRunningAr() = RasulTheme(false) { UpdatePreview(running) }

@Preview(showBackground = true, widthDp = 390, heightDp = 780, name = "HermesUpdateDone")
@Composable
private fun HermesUpdateDoneEn() = RasulTheme(false) { UpdatePreview(done) }

@Preview(showBackground = true, widthDp = 390, heightDp = 780, locale = "ar", name = "HermesUpdateDone")
@Composable
private fun HermesUpdateDoneAr() = RasulTheme(false) { UpdatePreview(done) }

@Preview(showBackground = true, widthDp = 390, heightDp = 780, name = "HermesUpdateFailed")
@Composable
private fun HermesUpdateFailedEn() = RasulTheme(false) { UpdatePreview(failed) }

@Preview(showBackground = true, widthDp = 390, heightDp = 780, locale = "ar", name = "HermesUpdateFailed")
@Composable
private fun HermesUpdateFailedAr() = RasulTheme(false) { UpdatePreview(failed) }

@Preview(showBackground = true, widthDp = 390, heightDp = 780, name = "HermesUpdateNowConfirm")
@Composable
private fun HermesUpdateNowConfirmEn() = RasulTheme(false) {
    UpdatePreview(available)
    ConfirmDialog(
        title = stringResource(R.string.hupd_now_title), body = nowBody(1),
        cancelText = stringResource(R.string.cancel), confirmText = stringResource(R.string.hupd_now),
        onCancel = {}, onConfirm = {}, destructive = true,
    )
}

@Preview(showBackground = true, widthDp = 390, heightDp = 780, locale = "ar", name = "HermesUpdateNowConfirm")
@Composable
private fun HermesUpdateNowConfirmAr() = RasulTheme(false) {
    UpdatePreview(available)
    ConfirmDialog(
        title = stringResource(R.string.hupd_now_title), body = nowBody(1),
        cancelText = stringResource(R.string.cancel), confirmText = stringResource(R.string.hupd_now),
        onCancel = {}, onConfirm = {}, destructive = true,
    )
}

private val unknownStatus = HermesUpdateDto(current = "0.21.4", behind = 0, reason = "dashboard_not_running", activeAgents = null)

@Composable
private fun UnknownPreview() = HermesUpdateContent(
    HermesUpdateState(status = unknownStatus, linkLost = true, pollError = DesktopError.RateLimited), "My PC", {}, {}, {}, {}, {},
)

@Preview(showBackground = true, widthDp = 390, heightDp = 780, name = "HermesUpdateUnknown")
@Composable
private fun HermesUpdateUnknownEn() = RasulTheme(false) { UnknownPreview() }

@Preview(showBackground = true, widthDp = 390, heightDp = 780, locale = "ar", name = "HermesUpdateUnknown")
@Composable
private fun HermesUpdateUnknownAr() = RasulTheme(false) { UnknownPreview() }
