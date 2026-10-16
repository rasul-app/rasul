package app.rasul.bridge

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalInspectionMode
import app.rasul.data.HermesError
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.rasul.R
import app.rasul.ui.components.*
import app.rasul.ui.theme.*

@Composable
fun CreateBotScreen(
    vm: CreateBotViewModel,
    group: BridgeGroup?,
    authenticate: (() -> Unit, () -> Unit) -> Unit,
    authenticationAvailable: Boolean,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val state by vm.state.collectAsState()
    LaunchedEffect(group?.profiles, group?.error, state.profileId, state.phase) {
        if (group?.error == null) vm.botVisible(group?.profiles.orEmpty())
    }
    LaunchedEffect(state.phase) {
        if (state.phase == CreateBotPhase.Complete) state.profileId?.let(onOpen)
    }
    BackHandler(state.phase == CreateBotPhase.Confirming || state.phase == CreateBotPhase.Creating) { }
    val listError = if (state.phase == CreateBotPhase.WaitingForBot) when (group?.error) {
        HermesError.DeviceRevoked -> CreateBotError.DeviceRevoked
        HermesError.DeviceKeyRepair -> CreateBotError.DeviceKeyRepair
        HermesError.BridgeTimeout -> CreateBotError.Timeout
        HermesError.ProfileNotReady -> CreateBotError.NotReady
        HermesError.BridgeResponseInvalid -> CreateBotError.InvalidResponse
        null -> null
        else -> CreateBotError.Unreachable
    } else null
    CreateBotContent(state.copy(error = state.error ?: listError), group?.name.orEmpty(), vm::name, vm::template, {
        if (authenticationAvailable) vm.submit(authenticate) else vm.authenticationUnavailable()
    }, vm::refreshTemplates, vm::editDetails, onBack)
}

@Composable
fun CreateBotContent(
    state: CreateBotState,
    bridgeName: String,
    onName: (String) -> Unit = {},
    onTemplate: (String) -> Unit = {},
    onSubmit: () -> Unit = {},
    onLoadTemplates: () -> Unit = {},
    onEdit: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    val colors = Rasul.colors
    Column(Modifier.fillMaxSize().background(colors.bg).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.create_bot), style = RasulType.title, color = colors.ink)
        Text(bridgeName, style = RasulType.body, color = colors.muted)
        OutlinedTextField(
            state.name, onName,
            label = { Text(stringResource(R.string.create_name)) },
            supportingText = { Text(stringResource(R.string.create_invalid_name)) },
            isError = state.name.isNotEmpty() && !validBotName(state.name),
            enabled = state.editable && !state.loading,
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
            textStyle = RasulType.body.copy(textDirection = TextDirection.Ltr),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.create_template), style = RasulType.rowTitle, color = colors.ink)
        if (state.loading) CreateBotProgress()
        else if (state.templates.isEmpty() && state.error == null) {
            Text(stringResource(R.string.create_no_templates), style = RasulType.body, color = colors.muted)
        }
        Column(Modifier.selectableGroup()) {
            state.templates.forEach { template ->
                RasulRow(
                    selected = state.template == template.id,
                    onClick = if (state.editable) ({ onTemplate(template.id) }) else null,
                    hairlineStart = 0.dp,
                ) {
                    RowTitle(template.label.ifBlank { template.id })
                    if (state.template == template.id) RowSub(stringResource(R.string.create_selected))
                }
            }
        }
        state.error?.let { error ->
            Text(stringResource(error.message), style = RasulType.body, color = colors.danger,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (state.busy) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CreateBotProgress(Modifier.size(24.dp))
                Text(stringResource(when (state.phase) {
                    CreateBotPhase.Confirming -> R.string.create_confirming
                    CreateBotPhase.WaitingForBot -> R.string.create_waiting
                    else -> R.string.create_progress
                }), style = RasulType.body, color = colors.ink,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
        if (state.phase == CreateBotPhase.WaitingForBot) {
            SecondaryButton(stringResource(R.string.back), onBack, modifier = Modifier.fillMaxWidth())
        }
        if (!state.busy && state.phase != CreateBotPhase.Complete) {
            if (state.templates.isEmpty() || state.error == CreateBotError.TemplateUnknown || (!state.hasAttempt && state.error != null)) {
                SecondaryButton(stringResource(R.string.create_reload_templates), onLoadTemplates, modifier = Modifier.fillMaxWidth(), enabled = !state.loading)
            }
            if (state.error !in setOf(CreateBotError.CreationFailed, CreateBotError.OutcomeUncertain, CreateBotError.OperationUnknown)) {
                PrimaryButton(stringResource(if (state.hasAttempt) R.string.create_retry else R.string.create_confirm), onSubmit,
                    enabled = state.canSubmit, modifier = Modifier.fillMaxWidth())
            }
            if (state.hasAttempt) SecondaryButton(stringResource(R.string.create_edit), onEdit, modifier = Modifier.fillMaxWidth())
            SecondaryButton(stringResource(R.string.cancel), onBack, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable private fun CreateBotProgress(modifier: Modifier = Modifier) {
    if (LocalInspectionMode.current) CircularProgressIndicator(progress = { 0.35f }, modifier = modifier)
    else CircularProgressIndicator(modifier)
}

private val previewTemplates = listOf(ProfileTemplate("blank", "Blank"))
@Composable private fun CreatePreview(phase: CreateBotPhase = CreateBotPhase.Editing, name: String = "", error: CreateBotError? = null) = RasulTheme(false) {
    CompositionLocalProvider(LocalInspectionMode provides true) {
        CreateBotContent(CreateBotState(name, previewTemplates, "blank", false, phase, error, hasAttempt = phase != CreateBotPhase.Editing), "Bridge")
    }
}
@Preview(widthDp = 390, heightDp = 700)
@Composable private fun CreateBotEmptyEn() = CreatePreview()
@Preview(widthDp = 390, heightDp = 700, locale = "ar")
@Composable private fun CreateBotEmptyAr() = CreatePreview()
@Preview(widthDp = 390, heightDp = 700)
@Composable private fun CreateBotInvalidEn() = CreatePreview(name = "A")
@Preview(widthDp = 390, heightDp = 700, locale = "ar")
@Composable private fun CreateBotInvalidAr() = CreatePreview(name = "A")
@Preview(widthDp = 390, heightDp = 700)
@Composable private fun CreateBotCreatingEn() = CreatePreview(CreateBotPhase.Creating, "new-profile")
@Preview(widthDp = 390, heightDp = 700, locale = "ar")
@Composable private fun CreateBotCreatingAr() = CreatePreview(CreateBotPhase.Creating, "new-profile")
@Preview(widthDp = 390, heightDp = 700)
@Composable private fun CreateBotErrorEn() = CreatePreview(CreateBotPhase.Failed, "new-profile", CreateBotError.NameTaken)
@Preview(widthDp = 390, heightDp = 700, locale = "ar")
@Composable private fun CreateBotErrorAr() = CreatePreview(CreateBotPhase.Failed, "new-profile", CreateBotError.NameTaken)
