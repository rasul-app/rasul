package app.rasul.channels

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import app.rasul.R
import app.rasul.bridge.ChannelDto
import app.rasul.bridge.ChannelError
import app.rasul.bridge.ChannelFailure
import app.rasul.bridge.ChannelFieldDto
import app.rasul.bridge.MAX_SETTING_CHANGES
import app.rasul.bridge.MAX_WHATSAPP_NUMBERS
import app.rasul.bridge.NumbersProblem
import app.rasul.bridge.SettingProblem
import app.rasul.bridge.WhatsappMode
import app.rasul.bridge.WhatsappStatus
import app.rasul.ui.components.PrimaryButton
import app.rasul.ui.components.SecondaryButton
import app.rasul.ui.components.SmallOutlineButton
import app.rasul.ui.theme.Rasul
import app.rasul.ui.theme.RasulShapes
import app.rasul.ui.theme.RasulTheme
import app.rasul.ui.theme.RasulType

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/**
 * While this is in the composition the window cannot be captured or mirrored (the QR links an account) and the screen
 * stays on so the code is not hidden mid-scan. A window that was already secure stays secure afterwards.
 */
@Composable
private fun SecureScreenEffect() {
    if (LocalInspectionMode.current) return
    val window = LocalContext.current.activity()?.window ?: return
    DisposableEffect(window) {
        val wasSecure = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (!wasSecure) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

/** The sentence for a failed WhatsApp setup; an expired code has its own words. */
@Composable
internal fun whatsappFailureText(failure: ChannelFailure): String =
    if (failure.error == ChannelError.SetupExpired) stringResource(R.string.chan_wa_expired) else failureText(failure)

private val QR_SIZE = 280.dp

@Composable
internal fun WhatsappSetupBody(setup: WhatsappSetup, state: ChannelsState, actions: ChannelsActions) {
    val c = Rasul.colors
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (setup) {
            is WhatsappSetup.Options -> OptionsBody(setup, actions)
            is WhatsappSetup.Active -> ActiveBody(setup, actions)
            is WhatsappSetup.Connected -> ConnectedBody(setup, state, actions)
            is WhatsappSetup.Done -> WhatsappDoneBody(setup, state, actions)
            is WhatsappSetup.Failed -> {
                Text(whatsappFailureText(setup.failure), style = RasulType.body, color = c.ink)
                PrimaryButton(stringResource(R.string.chan_tg_again), actions.onWaAgain, modifier = Modifier.fillMaxWidth())
                SecondaryButton(stringResource(R.string.cancel), actions.onWaCancel, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ModeChoice(selected: Boolean, title: Int, help: Int, enabled: Boolean, onSelect: () -> Unit) {
    val c = Rasul.colors
    Row(
        Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = RasulType.rowTitle, color = c.ink)
            Text(stringResource(help), style = RasulType.bodySmall, color = c.muted)
        }
    }
}

@Composable
private fun OptionsBody(setup: WhatsappSetup.Options, actions: ChannelsActions) {
    val c = Rasul.colors
    Text(stringResource(R.string.chan_wa_mode_label), style = RasulType.rowTitle, color = c.ink)
    ModeChoice(setup.mode == WhatsappMode.Bot, R.string.chan_wa_mode_bot, R.string.chan_wa_mode_bot_help, !setup.starting) { actions.onWaMode(WhatsappMode.Bot) }
    ModeChoice(setup.mode == WhatsappMode.SelfChat, R.string.chan_wa_mode_self, R.string.chan_wa_mode_self_help, !setup.starting) { actions.onWaMode(WhatsappMode.SelfChat) }
    OutlinedTextField(
        setup.numbers, actions.onWaNumbers,
        label = { Text(stringResource(R.string.chan_wa_numbers_label)) },
        supportingText = {
            Text(
                when (setup.numbersProblem) {
                    NumbersProblem.Format -> stringResource(R.string.chan_wa_numbers_invalid)
                    NumbersProblem.TooMany -> stringResource(R.string.chan_wa_numbers_too_many, MAX_WHATSAPP_NUMBERS)
                    NumbersProblem.TooLong -> stringResource(R.string.chan_wa_numbers_too_long)
                    null -> stringResource(R.string.chan_wa_numbers_help, MAX_WHATSAPP_NUMBERS)
                },
            )
        },
        isError = setup.numbersProblem != null, enabled = !setup.starting, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, autoCorrectEnabled = false),
        textStyle = RasulType.body.copy(textDirection = TextDirection.Ltr),
        modifier = Modifier.fillMaxWidth(),
    )
    setup.error?.let { Text(failureText(it), style = RasulType.bodySmall, color = c.dangerText) }
    PrimaryButton(
        stringResource(if (setup.starting) R.string.chan_wa_starting else R.string.chan_wa_start), actions.onWaBegin,
        enabled = !setup.starting, modifier = Modifier.fillMaxWidth(),
    )
    SecondaryButton(stringResource(R.string.cancel), actions.onWaCancel, enabled = !setup.starting, modifier = Modifier.fillMaxWidth())
}

private enum class StepState { Done, Current, Pending }

@Composable
private fun SetupSteps(status: String) {
    val current = when (status) {
        WhatsappStatus.INSTALLING -> 0
        WhatsappStatus.STARTING -> 1
        else -> 2
    }
    val steps = listOf(
        R.string.chan_wa_step_installing, R.string.chan_wa_step_starting, R.string.chan_wa_step_waiting, R.string.chan_wa_step_connected,
    )
    Column(
        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        steps.forEachIndexed { index, label ->
            StepRow(stringResource(label), if (index < current) StepState.Done else if (index == current) StepState.Current else StepState.Pending)
        }
    }
}

@Composable
private fun StepRow(text: String, state: StepState) {
    val c = Rasul.colors
    val description = stringResource(
        when (state) {
            StepState.Done -> R.string.chan_wa_step_done
            StepState.Current -> R.string.chan_wa_step_current
            StepState.Pending -> R.string.chan_wa_step_pending
        },
        text,
    )
    Row(
        Modifier.semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val dot = Modifier.size(12.dp)
        when (state) {
            StepState.Done -> Box(dot.background(c.success, CircleShape))
            StepState.Current -> Box(dot.background(c.accent, CircleShape))
            StepState.Pending -> Box(dot.border(1.5.dp, c.muted, CircleShape))
        }
        Text(text, style = RasulType.body, color = if (state == StepState.Pending) c.muted else c.ink)
    }
}

@Composable
private fun ActiveBody(setup: WhatsappSetup.Active, actions: ChannelsActions) {
    val c = Rasul.colors
    SecureScreenEffect()
    SetupSteps(setup.status)
    if (setup.status == WhatsappStatus.WAITING && setup.qrPayload != null) {
        Text(stringResource(R.string.chan_wa_scan_title), style = RasulType.rowTitle, color = c.ink)
        Text(stringResource(R.string.chan_wa_scan_body), style = RasulType.body, color = c.muted)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            QrCode(setup.qrPayload, stringResource(R.string.chan_wa_qr_desc), QR_SIZE, Modifier.border(1.dp, c.hairline, RasulShapes.card))
        }
        Text(stringResource(R.string.chan_wa_qr_note), style = RasulType.bodySmall, color = c.muted)
    }
    setup.pollError?.let { Text(failureText(it), style = RasulType.bodySmall, color = c.muted) }
    SecondaryButton(stringResource(R.string.chan_wa_cancel), actions.onWaCancel, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun ConnectedBody(setup: WhatsappSetup.Connected, state: ChannelsState, actions: ChannelsActions) {
    val c = Rasul.colors
    val waiting = (state.busy as? BusyState.Waiting)?.action == BusyAction.WhatsappApply
    val locked = state.acting || state.busy != BusyState.None
    SecureScreenEffect()
    Text(stringResource(R.string.chan_wa_connected_title), style = RasulType.rowTitle, color = c.ink)
    setup.accountName?.let { Text(stringResource(R.string.chan_wa_connected_account, bridgeText(it)), style = RasulType.body, color = c.ink) }
    setup.phoneLast4?.let { Text(stringResource(R.string.chan_wa_connected_last4, it), style = RasulType.body, color = c.ink) }
    Text(stringResource(R.string.chan_wa_connected_body), style = RasulType.body, color = c.muted)
    if (waiting) {
        WaitingBlock(actions.onCancelBusy)
    } else {
        PrimaryButton(stringResource(R.string.chan_wa_apply), actions.onWaApply, enabled = !locked, modifier = Modifier.fillMaxWidth())
        SecondaryButton(stringResource(R.string.chan_wa_cancel), actions.onWaCancel, enabled = !locked, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun WhatsappDoneBody(setup: WhatsappSetup.Done, state: ChannelsState, actions: ChannelsActions) {
    val c = Rasul.colors
    val waiting = (state.busy as? BusyState.Waiting)?.action == BusyAction.Restart
    Text(stringResource(R.string.chan_wa_done_title), style = RasulType.rowTitle, color = c.ink)
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
    PrimaryButton(stringResource(R.string.chan_tg_done), actions.onWaDone, modifier = Modifier.fillMaxWidth())
}

// ---------- channel settings ----------

@Composable
private fun problemText(problem: SettingProblem): String = stringResource(
    when (problem) {
        SettingProblem.BadKey -> R.string.chan_set_p_badkey
        SettingProblem.TooLong -> R.string.chan_set_p_toolong
        SettingProblem.NotAscii -> R.string.chan_set_p_nonascii
        SettingProblem.Masked -> R.string.chan_set_p_masked
    },
)

@Composable
internal fun SettingsBody(form: SettingsForm, actions: ChannelsActions) {
    val c = Rasul.colors
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.chan_set_intro), style = RasulType.body, color = c.muted)
        form.fields.forEach { field -> SettingField(field, form, actions) }
        form.error?.let { Text(failureText(it), style = RasulType.bodySmall, color = c.dangerText) }
        if (form.tooMany) Text(stringResource(R.string.chan_set_too_many, MAX_SETTING_CHANGES), style = RasulType.bodySmall, color = c.dangerText)
        PrimaryButton(
            stringResource(if (form.saving) R.string.chan_set_saving else R.string.chan_set_save), actions.onSettingsSave,
            enabled = form.changed && !form.saving, modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SettingField(field: ChannelFieldDto, form: SettingsForm, actions: ChannelsActions) {
    val c = Rasul.colors
    val name = field.prompt?.takeIf { it.isNotBlank() } ?: field.key
    val clearing = field.key in form.clearing
    val problem = form.problems[field.key]
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(bridgeText(name), style = RasulType.rowTitle, color = c.ink, modifier = Modifier.weight(1f, fill = false))
            Text(
                stringResource(if (field.isSet) R.string.chan_set_is_set else R.string.chan_set_not_set),
                style = RasulType.meta, color = if (field.isSet) c.success else c.muted,
            )
            if (field.required) Text(stringResource(R.string.chan_set_required), style = RasulType.meta, color = c.muted)
        }
        if (clearing) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.chan_set_will_clear), style = RasulType.body, color = c.dangerText, modifier = Modifier.weight(1f))
                val undo = stringResource(R.string.chan_set_undo_for, name)
                Box(Modifier.semantics(mergeDescendants = true) { contentDescription = undo }) {
                    SmallOutlineButton(stringResource(R.string.chan_set_undo), { actions.onSettingClear(field.key) }, enabled = !form.saving)
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                OutlinedTextField(
                    form.inputs[field.key].orEmpty(), { actions.onSettingInput(field.key, it) },
                    label = { Text(stringResource(R.string.chan_set_new_value)) },
                    supportingText = {
                        Text(
                            when {
                                problem != null -> problemText(problem)
                                field.isList -> stringResource(R.string.chan_set_list_help)
                                else -> field.key
                            },
                        )
                    },
                    isError = problem != null, enabled = !form.saving, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password, capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                    ),
                    textStyle = RasulType.body.copy(textDirection = TextDirection.Ltr),
                    modifier = Modifier.weight(1f).semantics { contentDescription = name },
                )
                if (field.isSet) {
                    val clear = stringResource(R.string.chan_set_clear_for, name)
                    Box(Modifier.padding(top = 6.dp).semantics(mergeDescendants = true) { contentDescription = clear }) {
                        SmallOutlineButton(stringResource(R.string.chan_set_clear), { actions.onSettingClear(field.key) }, enabled = !form.saving)
                    }
                }
            }
        }
    }
}

// ---------- previews ----------

private const val FAKE_QR = "2@fakePayloadForPreviewOnly,AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=,BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=,1"

private val previewActions = ChannelsActions()

@Composable
private fun WhatsappPreview(setup: WhatsappSetup) =
    ChannelsContent(ChannelsState(channels = emptyList(), whatsapp = setup), "My PC", previewActions)

private val waWaiting = WhatsappSetup.Active("pair_abcdef123456", WhatsappStatus.WAITING, FAKE_QR, null)
private val waConnected = WhatsappSetup.Connected("pair_abcdef123456", "Work phone", "0000")
private val waError = WhatsappSetup.Failed(ChannelFailure(ChannelError.WhatsappLoggedOut))

@Preview(showBackground = true, widthDp = 390, heightDp = 880, name = "WhatsappWaiting")
@Composable
private fun WhatsappWaitingEn() = RasulTheme(false) { WhatsappPreview(waWaiting) }

@Preview(showBackground = true, widthDp = 390, heightDp = 880, locale = "ar", name = "WhatsappWaiting")
@Composable
private fun WhatsappWaitingAr() = RasulTheme(false) { WhatsappPreview(waWaiting) }

@Preview(showBackground = true, widthDp = 390, heightDp = 520, name = "WhatsappConnected")
@Composable
private fun WhatsappConnectedEn() = RasulTheme(false) { WhatsappPreview(waConnected) }

@Preview(showBackground = true, widthDp = 390, heightDp = 520, locale = "ar", name = "WhatsappConnected")
@Composable
private fun WhatsappConnectedAr() = RasulTheme(false) { WhatsappPreview(waConnected) }

@Preview(showBackground = true, widthDp = 390, heightDp = 420, name = "WhatsappError")
@Composable
private fun WhatsappErrorEn() = RasulTheme(false) { WhatsappPreview(waError) }

@Preview(showBackground = true, widthDp = 390, heightDp = 420, locale = "ar", name = "WhatsappError")
@Composable
private fun WhatsappErrorAr() = RasulTheme(false) { WhatsappPreview(waError) }

private val formFields = listOf(
    ChannelFieldDto("BOT_TOKEN", required = true, isSet = true, prompt = "Bot token"),
    ChannelFieldDto("ALLOWED_USERS", isSet = true, isList = true, prompt = "Allowed users"),
    ChannelFieldDto("HOME_CHANNEL", isSet = false, prompt = "Home channel"),
)

private val settingsForm = SettingsForm(
    "discord", "Discord", formFields,
    inputs = mapOf("HOME_CHANNEL" to "sample-value"),
    clearing = setOf("ALLOWED_USERS"),
)

@Composable
private fun SettingsPreview() =
    ChannelsContent(ChannelsState(channels = emptyList(), settings = settingsForm), "My PC", previewActions)

@Preview(showBackground = true, widthDp = 390, heightDp = 760, name = "ChannelSettings")
@Composable
private fun ChannelSettingsEn() = RasulTheme(false) { SettingsPreview() }

@Preview(showBackground = true, widthDp = 390, heightDp = 760, locale = "ar", name = "ChannelSettings")
@Composable
private fun ChannelSettingsAr() = RasulTheme(false) { SettingsPreview() }

private val rowsWithSettings = listOf(
    ChannelDto("discord", "Discord", "Talk to Hermes from a Discord server.", enabled = true, configured = true, state = "connected", fields = formFields),
    ChannelDto("whatsapp", "WhatsApp", "Message Hermes on WhatsApp.", enabled = false, configured = false, state = "not_configured", fields = formFields.take(1)),
)

@Preview(showBackground = true, widthDp = 390, heightDp = 420, name = "ChannelsSettingsRow")
@Composable
private fun ChannelsSettingsRowEn() = RasulTheme(false) { ChannelsContent(ChannelsState(channels = rowsWithSettings), "My PC", previewActions) }

@Preview(showBackground = true, widthDp = 390, heightDp = 420, locale = "ar", name = "ChannelsSettingsRow")
@Composable
private fun ChannelsSettingsRowAr() = RasulTheme(false) { ChannelsContent(ChannelsState(channels = rowsWithSettings), "My PC", previewActions) }
