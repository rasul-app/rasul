package app.rasul.bridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import app.rasul.R
import app.rasul.ui.components.PrimaryButton
import app.rasul.ui.components.SecondaryButton
import app.rasul.ui.components.ConfirmDialog
import app.rasul.ui.components.RasulNavBar
import app.rasul.ui.components.RowSub
import app.rasul.ui.components.RowTitle
import app.rasul.ui.theme.Rasul
import app.rasul.ui.theme.RasulType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun SavedBridgesScreen(
    store: BridgeStore,
    bridgeBots: BridgeBotsRepository,
    onScan: () -> Unit,
    onDirect: () -> Unit,
    onClose: () -> Unit,
    selectedBridgeId: String? = null,
    botCount: Int? = null,
    onAdd: () -> Unit = onScan,
) {
    var bridges by remember { mutableStateOf<List<SavedBridge>>(emptyList()) }
    var error by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<SavedBridge?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(store) {
        try { store.bridges.collect { bridges = it } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = true }
    }
    val selectedBridge = selectedBridgeId?.let { id -> bridges.firstOrNull { it.bridgeId == id } }
    if (selectedBridgeId != null) {
        Column(Modifier.fillMaxSize().background(Rasul.colors.bg).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            RasulNavBar(stringResource(R.string.bridge_detail_title), stringResource(R.string.back), onClose)
            if (error) Text(stringResource(R.string.bridge_storage_error), color = Rasul.colors.danger)
            selectedBridge?.let { bridge ->
                RowTitle(bridge.name)
                RowSub(stringResource(R.string.bridge_status_paired))
                botCount?.let { RowSub(pluralStringResource(R.plurals.bridge_bot_count, it, it)) }
                SecondaryButton(stringResource(R.string.bridge_forget_this_pc), { pending = bridge }, modifier = Modifier.fillMaxWidth(), enabled = !removing)
                PrimaryButton(stringResource(R.string.add_pc), onAdd, modifier = Modifier.fillMaxWidth())
            }
        }
    } else {
        Column(Modifier.fillMaxSize().background(Rasul.colors.bg).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.saved_bridges), style = RasulType.title, color = Rasul.colors.ink)
            if (error) Text(stringResource(R.string.bridge_storage_error), color = Rasul.colors.danger)
            bridges.forEach { bridge ->
                Text(bridge.name, style = RasulType.rowTitle, color = Rasul.colors.ink)
                SecondaryButton(stringResource(R.string.bridge_forget, bridge.name), { pending = bridge }, modifier = Modifier.fillMaxWidth(), enabled = !removing)
            }
            PrimaryButton(stringResource(R.string.add_pc), onAdd)
            SecondaryButton(stringResource(R.string.bridge_direct), onDirect, modifier = Modifier.fillMaxWidth())
            SecondaryButton(stringResource(R.string.back), onClose, modifier = Modifier.fillMaxWidth())
        }
    }
    pending?.let { bridge ->
        ConfirmDialog(stringResource(R.string.bridge_forget, bridge.name), stringResource(R.string.bridge_forget_body),
            stringResource(R.string.cancel), stringResource(R.string.delete), { pending = null }, {
                val target = bridge
                val closeAfterForget = selectedBridgeId != null
                pending = null
                removing = true
                scope.launch {
                    try {
                        bridgeBots.forget(target)
                        if (closeAfterForget) onClose()
                    }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { error = true }
                    finally { removing = false }
                }
            }, destructive = true)
    }
}
