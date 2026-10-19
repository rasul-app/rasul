package app.rasul.channels

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.rasul.R
import app.rasul.bridge.ChannelDto
import app.rasul.bridge.ChannelError
import app.rasul.bridge.ChannelFailure
import app.rasul.bridge.ChannelState
import app.rasul.bridge.WhatsappMode
import app.rasul.desktop.DesktopHeader
import app.rasul.desktop.DesktopMessage
import app.rasul.ui.components.ChipKind
import app.rasul.ui.components.ConfirmDialog
import app.rasul.ui.components.InsetGroup
import app.rasul.ui.components.NoticeDialog
import app.rasul.ui.components.PrimaryButton
import app.rasul.ui.components.RasulRow
import app.rasul.ui.components.RasulToggle
import app.rasul.ui.components.RowSub
import app.rasul.ui.components.RowTitle
import app.rasul.ui.components.SecondaryButton
import app.rasul.ui.components.SmallOutlineButton
import app.rasul.ui.components.StatusChip
import app.rasul.ui.theme.Rasul
import app.rasul.ui.theme.RasulShapes
import app.rasul.ui.theme.RasulTheme
import app.rasul.ui.theme.RasulType

/** The chip text for a channel state: a string resource, or the bridge's own text when the state is not a known one. */
internal data class ChannelChip(val label: Int?, val raw: String?, val kind: ChipKind)

internal fun channelChip(state: String?): ChannelChip = when (state) {
    ChannelState.CONNECTED -> ChannelChip(R.string.chan_chip_connected, null, ChipKind.Done)
    ChannelState.DISABLED -> ChannelChip(R.string.chan_chip_off, null, ChipKind.Neutral)
    ChannelState.NOT_CONFIGURED -> ChannelChip(R.string.chan_chip_needs_setup, null, ChipKind.Neutral)
    ChannelState.PENDING_RESTART -> ChannelChip(R.string.chan_chip_restart, null, ChipKind.Running)
    ChannelState.GATEWAY_STOPPED -> ChannelChip(R.string.chan_chip_stopped, null, ChipKind.Neutral)
    ChannelState.STARTUP_FAILED -> ChannelChip(R.string.chan_chip_failed, null, ChipKind.Failed)
    null, "" -> ChannelChip(R.string.chan_chip_unknown, null, ChipKind.Neutral)
    else -> ChannelChip(null, state, ChipKind.Neutral)
}

/** What the screen calls back; every default is a no-op so previews stay short. */
internal class ChannelsActions(
    val onBack: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onToggle: (String, Boolean) -> Unit = { _, _ -> },
    val onTest: (String) -> Unit = {},
    val onRestart: () -> Unit = {},
    val onConnect: () -> Unit = {},
    val onCancelBusy: () -> Unit = {},
    val onBotName: (String) -> Unit = {},
    val onBegin: () -> Unit = {},
    val onOpenTelegram: () -> Unit = {},
    val onCancelSetup: () -> Unit = {},
    val onUserIds: (String) -> Unit = {},
    val onApply: () -> Unit = {},
    val onAgain: () -> Unit = {},
    val onDone: () -> Unit = {},
    val onSettings: (String) -> Unit = {},
    val onWaStart: () -> Unit = {},
    val onWaMode: (WhatsappMode) -> Unit = {},
    val onWaNumbers: (String) -> Unit = {},
    val onWaBegin: () -> Unit = {},
    val onWaApply: () -> Unit = {},
    val onWaCancel: () -> Unit = {},
    val onWaAgain: () -> Unit = {},
    val onWaDone: () -> Unit = {},
    val onSettingInput: (String, String) -> Unit = { _, _ -> },
    val onSettingClear: (String) -> Unit = {},
    val onSettingsSave: () -> Unit = {},
)

@Composable
internal fun failureText(failure: ChannelFailure): String = when {
    failure.error == ChannelError.Conflict && failure.detail != null ->
        stringResource(R.string.chan_err_conflict_detail, failure.detail)
    failure.error == ChannelError.SetupNotReady && failure.detail != null ->
        stringResource(R.string.chan_err_setup_not_ready_detail, failure.detail)
    else -> stringResource(failure.error.message)
}

@Composable
internal fun busyBody(agents: Int?): String =
    if (agents == null) stringResource(R.string.chan_busy_unknown) else pluralStringResource(R.plurals.chan_busy_body, agents, agents)

@Composable
private fun noticeText(notice: ChannelNotice): String = when (notice) {
    ChannelNotice.Restarted -> stringResource(R.string.chan_restarted)
    is ChannelNotice.SettingsSaved -> stringResource(R.string.chan_set_saved, notice.name)
    is ChannelNotice.Test -> when {
        notice.ok && notice.message != null -> stringResource(R.string.chan_test_ok_msg, notice.name, notice.message)
        notice.ok -> stringResource(R.string.chan_test_ok, notice.name)
        notice.message != null -> stringResource(R.string.chan_test_failed_msg, notice.name, notice.message)
        else -> stringResource(R.string.chan_test_failed, notice.name)
    }
}

/** Messaging channels of one PC: list, switches, tests, restart, and the Telegram setup. */
@Composable
fun ChannelsScreen(vm: ChannelsViewModel, targetName: String, onBack: () -> Unit, onOpenLink: (String) -> Unit) {
    val state by vm.state.collectAsState()
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
    val leaveSetup = {
        when {
            state.setup != null -> if (state.setup is TelegramSetup.Done) vm.finishSetup() else vm.cancelSetup()
            state.whatsapp != null -> if (state.whatsapp is WhatsappSetup.Done) vm.finishWhatsapp() else vm.cancelWhatsapp()
            else -> vm.closeSettings()
        }
    }
    val inSubScreen = state.setup != null || state.whatsapp != null || state.settings != null
    BackHandler(enabled = inSubScreen) { leaveSetup() }
    val actions = ChannelsActions(
        onBack = { if (inSubScreen) leaveSetup() else onBack() },
        onRetry = vm::refresh,
        onToggle = vm::toggle,
        onTest = vm::test,
        onRestart = vm::restart,
        onConnect = vm::startSetup,
        onCancelBusy = vm::cancelBusy,
        onBotName = vm::setBotName,
        onBegin = vm::beginTelegram,
        onOpenTelegram = {
            vm.openTelegramLink()?.let(onOpenLink)
        },
        onCancelSetup = vm::cancelSetup,
        onUserIds = vm::setUserIds,
        onApply = vm::applyTelegram,
        onAgain = vm::restartSetup,
        onDone = vm::finishSetup,
        onSettings = vm::openSettings,
        onWaStart = vm::startWhatsapp,
        onWaMode = vm::setWhatsappMode,
        onWaNumbers = vm::setWhatsappNumbers,
        onWaBegin = vm::beginWhatsapp,
        onWaApply = vm::applyWhatsapp,
        onWaCancel = vm::cancelWhatsapp,
        onWaAgain = vm::restartWhatsapp,
        onWaDone = vm::finishWhatsapp,
        onSettingInput = vm::setSettingInput,
        onSettingClear = vm::toggleSettingClear,
        onSettingsSave = vm::saveSettings,
    )
    ChannelsContent(state, targetName, actions)
    (state.busy as? BusyState.Asking)?.let { asking ->
        val whatsapp = asking.action == BusyAction.WhatsappApply
        ConfirmDialog(
            title = stringResource(R.string.chan_busy_title),
            body = if (whatsapp) stringResource(R.string.chan_wa_busy_body) else busyBody(asking.activeAgents),
            cancelText = stringResource(R.string.chan_busy_wait),
            confirmText = stringResource(if (whatsapp) R.string.chan_wa_busy_now else R.string.chan_busy_now),
            onCancel = vm::waitForIdle,
            onDismiss = vm::cancelBusy,
            onConfirm = vm::restartNow,
            destructive = true,
        )
    }
    state.notice?.let { NoticeDialog(noticeText(it), stringResource(R.string.ok), vm::dismissNotice) }
    state.actionError?.let { NoticeDialog(failureText(it), stringResource(R.string.ok), vm::dismissActionError) }
}

@Composable
internal fun ChannelsContent(state: ChannelsState, targetName: String, actions: ChannelsActions) {
    val c = Rasul.colors
    Column(Modifier.fillMaxSize().background(c.bg)) {
        val setup = state.setup
        if (setup != null) {
            DesktopHeader(R.string.chan_tg_title, targetName, actions.onBack)
            TelegramSetupBody(setup, state, actions)
            return@Column
        }
        val whatsapp = state.whatsapp
        if (whatsapp != null) {
            DesktopHeader(R.string.chan_wa_title, targetName, actions.onBack)
            WhatsappSetupBody(whatsapp, state, actions)
            return@Column
        }
        val form = state.settings
        if (form != null) {
            DesktopHeader(R.string.chan_set_title, form.channelName, actions.onBack)
            SettingsBody(form, actions)
            return@Column
        }
        DesktopHeader(R.string.chan_title, targetName, actions.onBack, actions.onRetry.takeIf { !state.direct && !state.requestRunning }, R.string.refresh)
        val channels = state.channels
        when {
            state.direct -> DesktopMessage(stringResource(R.string.chan_needs_bridge))
            channels == null && state.loading -> LoadingBlock(R.string.chan_loading)
            channels == null -> DesktopMessage(failureText(state.error ?: ChannelFailure(ChannelError.DesktopFailed)), actions.onRetry)
            else -> ListBody(state, channels, actions)
        }
    }
}

@Composable
private fun LoadingBlock(label: Int) {
    val c = Rasul.colors
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Spinner()
            Text(stringResource(label), style = RasulType.body, color = c.muted)
        }
    }
}

/** An endless animation never lets a screenshot settle, so previews draw a fixed ring. */
@Composable
internal fun Spinner() {
    val c = Rasul.colors
    if (LocalInspectionMode.current) {
        CircularProgressIndicator(progress = { 0.3f }, modifier = Modifier.size(24.dp), color = c.accent)
    } else {
        CircularProgressIndicator(Modifier.size(24.dp), color = c.accent)
    }
}

@Composable
private fun ListBody(state: ChannelsState, channels: List<ChannelDto>, actions: ChannelsActions) {
    val c = Rasul.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        state.error?.let {
            Text(
                failureText(it), style = RasulType.bodySmall, color = c.muted,
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp),
            )
        }
        val waitingRestart = (state.busy as? BusyState.Waiting)?.action == BusyAction.Restart
        if (state.restartNeeded || waitingRestart) RestartBanner(state, actions)
        if (channels.isEmpty()) {
            Text(
                stringResource(R.string.chan_empty), style = RasulType.body, color = c.muted,
                modifier = Modifier.fillMaxWidth().padding(24.dp),
            )
        } else {
            Column(Modifier.padding(top = 12.dp)) {
                InsetGroup {
                    channels.forEachIndexed { index, channel ->
                        ChannelRow(channel, state, index == channels.lastIndex, actions)
                    }
                }
            }
        }
    }
}

@Composable
private fun RestartBanner(state: ChannelsState, actions: ChannelsActions) {
    val c = Rasul.colors
    val waiting = (state.busy as? BusyState.Waiting)?.action == BusyAction.Restart
    Column(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp)
            .clip(RasulShapes.card).background(c.accentSoft).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (waiting) {
            WaitingBlock(actions.onCancelBusy)
        } else {
            Text(stringResource(R.string.chan_restart_title), style = RasulType.rowTitle, color = c.ink)
            Text(stringResource(R.string.chan_restart_body), style = RasulType.bodySmall, color = c.muted)
            PrimaryButton(
                stringResource(R.string.chan_restart), actions.onRestart,
                enabled = !state.requestRunning && state.busy == BusyState.None, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** "Waiting for Hermes to be idle…" with a Cancel that stops the repeats. */
@Composable
internal fun WaitingBlock(onCancel: () -> Unit) {
    val c = Rasul.colors
    Row(
        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Spinner()
        Text(stringResource(R.string.chan_waiting), style = RasulType.body, color = c.ink)
    }
    SecondaryButton(stringResource(R.string.cancel), onCancel, modifier = Modifier.fillMaxWidth())
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChannelRow(channel: ChannelDto, state: ChannelsState, last: Boolean, actions: ChannelsActions) {
    val c = Rasul.colors
    val locked = state.requestRunning || state.busy != BusyState.None
    val chip = channelChip(channel.state)
    val testing = state.testingId == channel.id
    RasulRow(
        last = last, hairlineStart = 16.dp, hairlineEnd = 16.dp, textSpacing = 4.dp,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        trailing = {
            RasulToggle(channel.enabled, label = channel.name, enabled = !locked) { wanted -> actions.onToggle(channel.id, wanted) }
        },
        bottom = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (testing) {
                    Text(stringResource(R.string.chan_testing), style = RasulType.bodySmall, color = c.accent, modifier = Modifier.align(Alignment.CenterVertically))
                } else {
                    val testLabel = stringResource(R.string.chan_test_for, channel.name)
                    Box(Modifier.semantics(mergeDescendants = true) { contentDescription = testLabel }) {
                        SmallOutlineButton(stringResource(R.string.chan_test), { actions.onTest(channel.id) }, enabled = !locked)
                    }
                }
                if (channel.canConnectTelegram()) {
                    SmallOutlineButton(stringResource(R.string.chan_tg_connect), actions.onConnect, enabled = !locked)
                }
                if (channel.canConnectWhatsapp()) {
                    SmallOutlineButton(stringResource(R.string.chan_wa_connect), actions.onWaStart, enabled = !locked)
                }
                if (channel.fields.isNotEmpty()) {
                    val settingsLabel = stringResource(R.string.chan_settings_for, channel.name)
                    Box(Modifier.semantics(mergeDescendants = true) { contentDescription = settingsLabel }) {
                        SmallOutlineButton(stringResource(R.string.chan_settings), { actions.onSettings(channel.id) }, enabled = !locked)
                    }
                }
            }
        },
    ) {
        RowTitle(channel.name)
        channel.description?.takeIf { it.isNotBlank() }?.let { RowSub(bridgeText(it), maxLines = 2) }
        StatusChip(chip.label?.let { stringResource(it) } ?: bridgeText(chip.raw.orEmpty()), chip.kind)
        if (channel.state == ChannelState.STARTUP_FAILED) {
            (channel.errorMessage ?: channel.errorCode)?.takeIf { it.isNotBlank() }?.let { RowSub(bridgeText(it), color = c.dangerText, maxLines = 3) }
        }
    }
}

// ---------- Telegram setup ----------

@Composable
private fun TelegramSetupBody(setup: TelegramSetup, state: ChannelsState, actions: ChannelsActions) {
    val c = Rasul.colors
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (setup) {
            is TelegramSetup.Name -> {
                OutlinedTextField(
                    setup.botName, actions.onBotName,
                    label = { Text(stringResource(R.string.chan_tg_name_label)) },
                    supportingText = { Text(stringResource(R.string.chan_tg_name_help)) },
                    enabled = !setup.starting, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    textStyle = RasulType.body.copy(textDirection = TextDirection.Ltr),
                    modifier = Modifier.fillMaxWidth(),
                )
                setup.error?.let { Text(failureText(it), style = RasulType.bodySmall, color = c.dangerText) }
                PrimaryButton(
                    stringResource(if (setup.starting) R.string.chan_tg_starting else R.string.chan_tg_start), actions.onBegin,
                    enabled = !setup.starting, modifier = Modifier.fillMaxWidth(),
                )
                SecondaryButton(stringResource(R.string.cancel), actions.onCancelSetup, enabled = !setup.starting, modifier = Modifier.fillMaxWidth())
            }
            is TelegramSetup.Waiting -> {
                Text(stringResource(R.string.chan_tg_waiting_title), style = RasulType.rowTitle, color = c.ink)
                Text(stringResource(R.string.chan_tg_waiting_body), style = RasulType.body, color = c.muted)
                setup.suggestedUsername?.let {
                    Text(stringResource(R.string.chan_tg_username, it), style = RasulType.body, color = c.ink)
                }
                PrimaryButton(stringResource(R.string.chan_tg_open), actions.onOpenTelegram, modifier = Modifier.fillMaxWidth())
                setup.linkProblem?.let {
                    val message = if (it == LinkProblem.Rejected) R.string.chan_tg_link_rejected else R.string.chan_tg_no_app
                    Text(stringResource(message), style = RasulType.bodySmall, color = c.dangerText)
                }
                setup.pollError?.let { Text(failureText(it), style = RasulType.bodySmall, color = c.muted) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spinner()
                    Text(stringResource(R.string.chan_tg_checking), style = RasulType.bodySmall, color = c.muted)
                }
                SecondaryButton(stringResource(R.string.chan_tg_cancel), actions.onCancelSetup, modifier = Modifier.fillMaxWidth())
            }
            is TelegramSetup.Ready -> ReadyBody(setup, state, actions)
            is TelegramSetup.Done -> DoneBody(setup, state, actions)
            is TelegramSetup.Failed -> {
                Text(failureText(setup.failure), style = RasulType.body, color = c.ink)
                PrimaryButton(stringResource(R.string.chan_tg_again), actions.onAgain, modifier = Modifier.fillMaxWidth())
                SecondaryButton(stringResource(R.string.cancel), actions.onCancelSetup, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

private fun atName(username: String) = "@" + username.removePrefix("@")

/** Text the bridge sent (descriptions, states, error lines) is not Arabic: an LTR isolate keeps its words and full stops in place in an RTL layout. */
@Composable
internal fun bridgeText(text: String): String =
    if (LocalLayoutDirection.current == LayoutDirection.Rtl) "⁦$text⁩" else text

@Composable
private fun ReadyBody(setup: TelegramSetup.Ready, state: ChannelsState, actions: ChannelsActions) {
    val c = Rasul.colors
    val waiting = (state.busy as? BusyState.Waiting)?.action == BusyAction.Apply
    val locked = state.acting || state.busy != BusyState.None
    Text(stringResource(R.string.chan_tg_ready_title), style = RasulType.rowTitle, color = c.ink)
    setup.botUsername?.let { Text(stringResource(R.string.chan_tg_ready_bot, atName(it)), style = RasulType.body, color = c.ink) }
    OutlinedTextField(
        setup.userIds, actions.onUserIds,
        label = { Text(stringResource(R.string.chan_tg_ids_label)) },
        supportingText = {
            Text(if (setup.idsInvalid) stringResource(R.string.chan_tg_ids_invalid, MAX_USER_IDS) else stringResource(R.string.chan_tg_ids_help))
        },
        isError = setup.idsInvalid, enabled = !locked, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, autoCorrectEnabled = false),
        textStyle = RasulType.body.copy(textDirection = TextDirection.Ltr),
        modifier = Modifier.fillMaxWidth(),
    )
    if (waiting) {
        WaitingBlock(actions.onCancelBusy)
    } else {
        PrimaryButton(stringResource(R.string.chan_tg_apply), actions.onApply, enabled = !locked, modifier = Modifier.fillMaxWidth())
        SecondaryButton(stringResource(R.string.chan_tg_cancel), actions.onCancelSetup, enabled = !locked, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun DoneBody(setup: TelegramSetup.Done, state: ChannelsState, actions: ChannelsActions) {
    val c = Rasul.colors
    val waiting = (state.busy as? BusyState.Waiting)?.action == BusyAction.Restart
    Text(stringResource(R.string.chan_tg_done_title), style = RasulType.rowTitle, color = c.ink)
    setup.botUsername?.let { Text(stringResource(R.string.chan_tg_done_body, atName(it)), style = RasulType.body, color = c.ink) }
    when {
        setup.restartStarted -> Text(stringResource(R.string.chan_tg_done_restarting), style = RasulType.body, color = c.muted)
        setup.needsRestart && waiting -> WaitingBlock(actions.onCancelBusy)
        setup.needsRestart -> {
            Text(stringResource(R.string.chan_tg_done_restart), style = RasulType.body, color = c.muted)
            PrimaryButton(
                stringResource(R.string.chan_restart), actions.onRestart,
                enabled = !state.requestRunning && state.busy == BusyState.None, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    PrimaryButton(stringResource(R.string.chan_tg_done), actions.onDone, modifier = Modifier.fillMaxWidth())
}

// ---------- previews ----------

private fun row(id: String, name: String, description: String, state: String?, enabled: Boolean, configured: Boolean = true, error: String? = null) =
    ChannelDto(
        id = id, name = name, description = description, enabled = enabled, configured = configured, state = state,
        errorMessage = error,
    )

private val mixed = listOf(
    row("telegram", "Telegram", "Chat with your agent in Telegram.", ChannelState.NOT_CONFIGURED, enabled = false, configured = false),
    row("discord", "Discord", "Talk to Hermes from a Discord server.", ChannelState.CONNECTED, enabled = true),
    row("slack", "Slack", "Use Hermes in a Slack workspace.", ChannelState.DISABLED, enabled = false),
    row("matrix", "Matrix", "Reach Hermes over a Matrix room.", ChannelState.STARTUP_FAILED, enabled = true, error = "Login was refused by the server."),
    row("signal", "Signal", "Message Hermes on Signal.", ChannelState.GATEWAY_STOPPED, enabled = true),
    row("irc", "IRC", "Join an IRC channel.", "reconnecting", enabled = true),
)

private val withRestart = listOf(
    row("discord", "Discord", "Talk to Hermes from a Discord server.", ChannelState.CONNECTED, enabled = true),
    row("slack", "Slack", "Use Hermes in a Slack workspace.", ChannelState.PENDING_RESTART, enabled = true),
)

private val previewActions = ChannelsActions()

@Composable
private fun ListPreview(channels: List<ChannelDto>) =
    ChannelsContent(ChannelsState(channels = channels), "My PC", previewActions)

@Preview(showBackground = true, widthDp = 390, heightDp = 1000, name = "ChannelsList")
@Composable
private fun ChannelsListEn() = RasulTheme(false) { ListPreview(mixed) }

@Preview(showBackground = true, widthDp = 390, heightDp = 1000, locale = "ar", name = "ChannelsList")
@Composable
private fun ChannelsListAr() = RasulTheme(false) { ListPreview(mixed) }

@Preview(showBackground = true, widthDp = 390, heightDp = 560, name = "ChannelsRestart")
@Composable
private fun ChannelsRestartEn() = RasulTheme(false) { ListPreview(withRestart) }

@Preview(showBackground = true, widthDp = 390, heightDp = 560, locale = "ar", name = "ChannelsRestart")
@Composable
private fun ChannelsRestartAr() = RasulTheme(false) { ListPreview(withRestart) }

@Composable
private fun BusyPreview() {
    ListPreview(withRestart)
    ConfirmDialog(
        title = stringResource(R.string.chan_busy_title), body = busyBody(2),
        cancelText = stringResource(R.string.chan_busy_wait), confirmText = stringResource(R.string.chan_busy_now),
        onCancel = {}, onConfirm = {}, destructive = true,
    )
}

@Preview(showBackground = true, widthDp = 390, heightDp = 560, name = "ChannelsBusy")
@Composable
private fun ChannelsBusyEn() = RasulTheme(false) { BusyPreview() }

@Preview(showBackground = true, widthDp = 390, heightDp = 560, locale = "ar", name = "ChannelsBusy")
@Composable
private fun ChannelsBusyAr() = RasulTheme(false) { BusyPreview() }

@Composable
private fun SetupPreview(setup: TelegramSetup) =
    ChannelsContent(ChannelsState(channels = mixed, setup = setup), "My PC", previewActions)

private val setupWaiting = TelegramSetup.Waiting("pair_abcdef123456", "https://t.me/hermes_helper_bot?start=abc", "hermes_helper_bot", null)
private val setupReady = TelegramSetup.Ready("pair_abcdef123456", "hermes_helper_bot", "123456789, 987654321")

@Preview(showBackground = true, widthDp = 390, heightDp = 640, name = "TelegramSetupWaiting")
@Composable
private fun TelegramSetupWaitingEn() = RasulTheme(false) { SetupPreview(setupWaiting) }

@Preview(showBackground = true, widthDp = 390, heightDp = 640, locale = "ar", name = "TelegramSetupWaiting")
@Composable
private fun TelegramSetupWaitingAr() = RasulTheme(false) { SetupPreview(setupWaiting) }

@Preview(showBackground = true, widthDp = 390, heightDp = 640, name = "TelegramSetupReady")
@Composable
private fun TelegramSetupReadyEn() = RasulTheme(false) { SetupPreview(setupReady) }

@Preview(showBackground = true, widthDp = 390, heightDp = 640, locale = "ar", name = "TelegramSetupReady")
@Composable
private fun TelegramSetupReadyAr() = RasulTheme(false) { SetupPreview(setupReady) }
