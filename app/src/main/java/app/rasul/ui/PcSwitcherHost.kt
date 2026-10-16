package app.rasul.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.rasul.R
import app.rasul.data.ActiveTarget
import app.rasul.data.Connection
import app.rasul.data.ConnectionStore
import app.rasul.ui.components.NoticeDialog
import app.rasul.ui.components.PcSwitcher
import app.rasul.ui.components.RasulIcons
import app.rasul.ui.components.RasulRow
import app.rasul.ui.components.RasulSheet
import app.rasul.ui.components.RowSub
import app.rasul.ui.components.RowTitle
import app.rasul.ui.theme.Rasul
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import app.rasul.bridge.BridgeBotsRepository
import app.rasul.bridge.bridgeBotId
import app.rasul.data.HermesResult
import app.rasul.data.activeTarget
import kotlinx.coroutines.launch

/**
 * One published snapshot of the PC state. [activeId] and [target] come from the SAME DataStore read, so the pill, the
 * rows and any action see one consistent PC. [readError] = reading failed; [writeError] = saving a pick failed (rows
 * stay); [loaded] is false until the first read ends.
 */
data class PcPicker(
    val pcs: List<Connection> = emptyList(),
    val activeId: String? = null,
    val target: ActiveTarget = ActiveTarget.None,
    val readError: String? = null,
    val writeError: String? = null,
    val loaded: Boolean = false,
)

/**
 * The one app-lifetime owner of "which PC is active". More, Sessions and Jobs read it from here and nowhere else.
 * Picks go through one ordered channel (last pick wins, a failing write never replaces newer state);
 * [retry] restarts the observation after a read failure.
 */
class ActivePcHolder(private val store: ConnectionStore, private val scope: CoroutineScope, private val bridges: BridgeBotsRepository? = null) {
    private val selectedBridge = MutableStateFlow<Pair<String, String>?>(null)
    private val _state = MutableStateFlow(PcPicker())
    val state: StateFlow<PcPicker> = _state
    private var observing: Job? = null
    private val picks = Channel<String>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (id in picks) {
                try {
                    store.setActive(id)
                    selectedBridge.value = null
                    _state.update { it.copy(writeError = null) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(writeError = e.message ?: e.javaClass.simpleName) }
                }
            }
        }
        retry()
    }

    fun retry() {
        observing?.cancel()
        observing = scope.launch {
            try {
                if (bridges == null) {
                    store.snapshots.collect { s ->
                        _state.update { PcPicker(s.pcs, s.activeId, s.target, writeError = it.writeError, loaded = true) }
                    }
                } else combine(store.snapshots, bridges.state, selectedBridge) { s, bots, selected ->
                    Triple(s, bots, selected)
                }.collect { (s, bots, selected) ->
                    val choices = bots.groups.flatMap { group -> group.profiles.map { p ->
                        Connection(bridgeBotId(group.id, p.id), group.name + " · " + p.displayName, "", p.id)
                    } }
                    val id = selected?.let { bridgeBotId(it.first, it.second) }
                    val c = choices.firstOrNull { it.id == id }
                    val target = if (selected == null) s.target else if (c == null) ActiveTarget.None
                        else when (val r = bridges.target(selected.first, selected.second)) {
                            is HermesResult.Ok -> ActiveTarget.Ready(c.id, c.name, r.value)
                            is HermesResult.Err -> ActiveTarget.Failed(r.error)
                        }
                    _state.update { PcPicker(s.pcs + choices, id ?: s.activeId, target, writeError = it.writeError, loaded = true) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(readError = e.message ?: e.javaClass.simpleName, loaded = true) }
            }
        }
    }

    fun selectPc(id: String) {
        val choice = bridges?.state?.value?.groups?.firstNotNullOfOrNull { group ->
            group.profiles.firstOrNull { bridgeBotId(group.id, it.id) == id }?.let { group.id to it.id }
        }
        if (choice != null) selectedBridge.value = choice else picks.trySend(id)
    }

    fun selectBridge(bridgeId: String, profileId: String) { selectedBridge.value = bridgeId to profileId }

    suspend fun currentTarget(): ActiveTarget {
        val selected = selectedBridge.value ?: return store.activeTarget()
        val group = bridges?.state?.value?.groups?.firstOrNull { it.id == selected.first } ?: return ActiveTarget.None
        val profile = group.profiles.firstOrNull { it.id == selected.second } ?: return ActiveTarget.None
        return when (val r = bridges?.target(selected.first, selected.second)) {
            is HermesResult.Ok -> ActiveTarget.Ready(bridgeBotId(selected.first, selected.second), group.name + " · " + profile.displayName, r.value)
            is HermesResult.Err -> ActiveTarget.Failed(r.error)
            null -> ActiveTarget.None
        }
    }

    fun clearWriteError() = _state.update { it.copy(writeError = null) }
}

/** The PC pill under a tab title plus its bottom sheet. Picking a PC calls [onSelect] with its id. */
@Composable
fun PcSwitcherHost(picker: PcPicker, onSelect: (String) -> Unit, onClearError: () -> Unit = {}) {
    val active = picker.pcs.firstOrNull { it.id == picker.activeId }
    // Keyed on "there is an active PC": the sheet cannot stay open across a gap with no PC.
    var open by remember(active != null) { mutableStateOf(false) }
    picker.writeError?.let { NoticeDialog(stringResource(R.string.pc_switch_failed, it), stringResource(R.string.ok), onClearError) }
    if (active == null) return
    PcSwitcher(active.name, onClick = { open = true })
    if (open) {
        PcSheet(picker.pcs, active.id, onDismiss = { open = false }) { c ->
            open = false
            onSelect(c.id)
        }
    }
}

@Composable
fun PcSheet(pcs: List<Connection>, activeId: String, onDismiss: () -> Unit, onPick: (Connection) -> Unit) {
    RasulSheet(stringResource(R.string.more_pcs), onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            pcs.forEachIndexed { i, c ->
                RasulRow(
                    onClick = { onPick(c) }, hairlineStart = 0.dp, last = i == pcs.lastIndex,
                    contentPadding = PaddingValues(vertical = 14.dp),
                    selected = c.id == activeId,
                    leading = { Icon(RasulIcons.Pc, null, tint = Rasul.colors.ink, modifier = Modifier.size(24.dp)) },
                    trailing = {
                        if (c.id == activeId) Icon(RasulIcons.Check, null, tint = Rasul.colors.accent, modifier = Modifier.size(22.dp))
                    },
                ) {
                    RowTitle(c.name)
                    RowSub(c.baseUrl + (c.profile?.let { " · $it" } ?: ""), maxLines = 1)
                }
            }
        }
    }
}
