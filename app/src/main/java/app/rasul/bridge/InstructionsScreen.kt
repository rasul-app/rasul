package app.rasul.bridge

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import app.rasul.R
import app.rasul.ui.components.PrimaryButton
import app.rasul.ui.components.RasulIcons
import app.rasul.ui.components.SecondaryButton
import app.rasul.ui.theme.Rasul
import app.rasul.ui.theme.RasulType

@Composable
fun InstructionsScreen(vm: InstructionsViewModel, botName: String, onBack: () -> Unit) {
    val state by vm.state.collectAsState()
    InstructionsContent(state, botName, vm::edit, vm::save, vm::reload, onBack)
}

@Composable
fun InstructionsContent(
    state: InstructionsState,
    botName: String,
    onText: (String) -> Unit = {},
    onSave: () -> Unit = {},
    onReload: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    var discardAction by rememberSaveable { mutableStateOf<String?>(null) }
    fun back() {
        if (state.saving) return
        if (state.dirty) discardAction = "back" else onBack()
    }
    fun reload() {
        if (state.busy) return
        if (state.dirty) discardAction = "reload" else onReload()
    }
    BackHandler { back() }
    val colors = Rasul.colors
    val backName = stringResource(R.string.back)
    Column(Modifier.fillMaxSize().background(colors.bg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = ::back, enabled = !state.saving, modifier = Modifier.size(48.dp).semantics { contentDescription = backName }) {
                Icon(RasulIcons.Back, contentDescription = null, tint = colors.accent)
            }
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text(stringResource(R.string.instructions_title), style = RasulType.sheetTitle, color = colors.ink)
                Text(botName, style = RasulType.meta, color = colors.muted)
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.direct) {
                Text(stringResource(R.string.instructions_direct), style = RasulType.body, color = colors.ink)
            } else {
                Text(stringResource(R.string.instructions_timing), style = RasulType.body, color = colors.muted)
                if (state.loading) {
                    val loadingName = stringResource(R.string.instructions_loading)
                    CircularProgressIndicator(color = colors.accent, modifier = Modifier.semantics { contentDescription = loadingName })
                }
                if (state.loaded) {
                    OutlinedTextField(
                        state.text, onText,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.instructions_label)) },
                        enabled = !state.busy,
                        minLines = 7,
                        maxLines = 12,
                        textStyle = RasulType.body.copy(textDirection = TextDirection.Content),
                        isError = state.error == InstructionsError.TooLarge,
                    )
                    Text(stringResource(R.string.instructions_limit), style = RasulType.bodySmall, color = colors.muted)
                    Text(stringResource(R.string.instructions_template_notice), style = RasulType.bodySmall, color = colors.muted)
                }
                state.error?.let { error ->
                    Text(stringResource(error.message), style = RasulType.body, color = colors.dangerText,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                if (state.saved) Text(stringResource(R.string.instructions_saved), style = RasulType.body, color = colors.successText,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (state.saving) Text(stringResource(R.string.instructions_saving), style = RasulType.body, color = colors.muted,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (state.loaded) PrimaryButton(stringResource(R.string.save), onSave, enabled = state.canSave, modifier = Modifier.fillMaxWidth())
                if (state.error != null || state.loaded) SecondaryButton(stringResource(R.string.instructions_reload), ::reload,
                    enabled = !state.busy, modifier = Modifier.fillMaxWidth())
            }
        }
    }
    if (discardAction != null) {
        AlertDialog(
            onDismissRequest = { discardAction = null },
            title = { Text(stringResource(R.string.instructions_unsaved_title)) },
            text = { Text(stringResource(R.string.instructions_unsaved_body)) },
            confirmButton = {
                TextButton(onClick = {
                    val action = discardAction
                    discardAction = null
                    if (action == "reload") onReload() else onBack()
                }) { Text(stringResource(R.string.instructions_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { discardAction = null }) { Text(stringResource(R.string.instructions_keep_editing)) }
            },
        )
    }
}
