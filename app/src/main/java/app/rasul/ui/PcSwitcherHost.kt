package app.rasul.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.TextStyle
import app.rasul.R
import app.rasul.data.ActiveTarget
import app.rasul.data.Connection
import app.rasul.data.ConnectionStore
import app.rasul.data.HermesError
import app.rasul.ui.components.NoticeDialog
import app.rasul.ui.components.PcSwitcher
import app.rasul.ui.components.RasulIcons
import app.rasul.ui.components.RasulRow
import app.rasul.ui.components.RasulSheet
import app.rasul.ui.components.RowSub
import app.rasul.ui.components.RowTitle
import app.rasul.ui.theme.Rasul
import app.rasul.ui.theme.RasulType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import app.rasul.bridge.BridgeBotsRepository
import app.rasul.bridge.BridgeGroup
import app.rasul.bridge.BridgeSelection
import app.rasul.bridge.bridgeBotId
import app.rasul.bridge.bridgeTargetName
import app.rasul.data.HermesResult
import app.rasul.data.Load
import app.rasul.data.activeTarget
import kotlinx.coroutines.launch

/**
 * One published snapshot of the PC state. [activeId] and [target] come from the SAME DataStore read, so the pill, the
 * rows and any action see one consistent PC. [readError] = reading failed; [writeError] = saving a pick failed (rows
 * stay); [loaded] tracks the local store read, while [targetLoaded] tracks bridge discovery for the active target.
 */
data class PcPicker(
    val pcs: List<Connection> = emptyList(),
    val activeId: String? = null,
    val target: ActiveTarget = ActiveTarget.None,
    val readError: String? = null,
    val writeError: String? = null,
    val loaded: Boolean = false,
    val targetLoaded: Boolean = false,
    val bridgeNotice: PcBridgeNotice? = null,
    val bridgeGroups: List<BridgeGroup> = emptyList(),
    val bridgeSelection: BridgeSelection? = null,
)

sealed interface PcBridgeNotice {
    data class Unreachable(val pcName: String) : PcBridgeNotice
    data class NoReadyBots(val pcName: String) : PcBridgeNotice
    data class Failed(val error: HermesError) : PcBridgeNotice
}

/** A screen state before its target can be queried, or a bridge-specific empty/error state. */
fun PcPicker.pendingLoad(): Load<Nothing>? = when {
    readError != null -> Load.Failed(HermesError.Storage(readError))
    !targetLoaded -> Load.Loading
    bridgeNotice is PcBridgeNotice.Unreachable -> Load.BridgeUnreachable(bridgeNotice.pcName)
    bridgeNotice is PcBridgeNotice.NoReadyBots -> Load.BridgeNoReadyBots(bridgeNotice.pcName)
    bridgeNotice is PcBridgeNotice.Failed -> Load.Failed(bridgeNotice.error)
    else -> null
}

/**
 * The one app-lifetime owner of "which PC is active". More, Sessions and Jobs read it from here and nowhere else.
 * Picks go through one ordered channel (last pick wins, a failing write never replaces newer state);
 * [retry] restarts the observation after a read failure.
 */
class ActivePcHolder(private val store: ConnectionStore, private val scope: CoroutineScope, private val bridges: BridgeBotsRepository? = null) {
    private val _state = MutableStateFlow(PcPicker())
    val state: StateFlow<PcPicker> = _state
    private var observing: Job? = null
    private val picks = Channel<PickRequest>(Channel.UNLIMITED)

    private sealed interface PickRequest {
        data class Direct(val id: String) : PickRequest
        data class Bridge(val selection: BridgeSelection) : PickRequest
    }

    init {
        scope.launch {
            for (pick in picks) {
                try {
                    when (pick) {
                        is PickRequest.Direct -> {
                            store.setActive(pick.id)
                            bridges?.setActiveSelection(null)
                        }
                        is PickRequest.Bridge -> bridges?.setActiveSelection(pick.selection)
                    }
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
                        _state.update { PcPicker(s.pcs, s.activeId, s.target, writeError = it.writeError, loaded = true, targetLoaded = true) }
                    }
                } else {
                    var pendingDefault: BridgeSelection? = null
                    combine(store.snapshots, bridges.state, bridges.activeSelection) { s, bots, selected ->
                        Triple(s, bots, selected)
                    }.collect { (s, bots, savedSelection) ->
                    val choices = bots.groups.flatMap { group -> group.profiles.map { profile ->
                        Connection(bridgeBotId(group.id, profile.id), bridgeTargetName(group, profile.id), "", profile.id)
                    } }
                    val selection = savedSelection
                    val directActive = s.target !is ActiveTarget.None
                    pendingDefault = pendingDefault?.takeIf { pending -> bots.groups.any { it.id == pending.bridgeId } }
                    if (savedSelection != null || directActive) pendingDefault = null
                    val readyProfiles = bots.groups.flatMap { group ->
                        group.profiles.filter { it.ready && group.error == null }.map { profile -> BridgeSelection(group.id, profile.id) to profile }
                    }
                    val defaultPick = if (selection == null && !directActive && bots.loaded && bots.groups.none { it.loading }) {
                        pendingDefault ?: readyProfiles.firstOrNull { it.second.isDefault }?.first ?: readyProfiles.firstOrNull()?.first
                    } else null
                    val effectiveSelection = selection ?: defaultPick
                    if (defaultPick != null && savedSelection == null && pendingDefault != defaultPick) {
                        pendingDefault = defaultPick
                        picks.trySend(PickRequest.Bridge(defaultPick))
                    }
                    val selectedGroup = effectiveSelection?.let { picked -> bots.groups.firstOrNull { it.id == picked.bridgeId } }
                    val selectedId = effectiveSelection?.let { bridgeBotId(it.bridgeId, it.profileId) }
                    val target = if (effectiveSelection == null) s.target else when (val result = bridges.target(effectiveSelection.bridgeId, effectiveSelection.profileId)) {
                        is HermesResult.Ok -> ActiveTarget.Ready(
                            selectedId.orEmpty(),
                            bridgeTargetName(selectedGroup, effectiveSelection.profileId),
                            result.value,
                        )
                        is HermesResult.Err -> ActiveTarget.Failed(result.error)
                    }
                    val targetLoaded = when {
                        effectiveSelection != null -> bots.loaded && selectedGroup?.loading != true
                        directActive -> true
                        else -> bots.loaded && bots.groups.none { it.loading }
                    }
                    val bridgeNotice = when {
                        effectiveSelection != null && selectedGroup != null &&
                            (selectedGroup.error == HermesError.BridgeUnreachable || selectedGroup.error == HermesError.BridgeTimeout) ->
                            PcBridgeNotice.Unreachable(selectedGroup.name)
                        effectiveSelection != null && selectedGroup != null && selectedGroup.error != null ->
                            PcBridgeNotice.Failed(selectedGroup.error)
                        effectiveSelection != null && selectedGroup != null && !selectedGroup.loading && selectedGroup.error == null && selectedGroup.profiles.none { it.ready } ->
                            PcBridgeNotice.NoReadyBots(selectedGroup.name)
                        effectiveSelection == null && !directActive && bots.loaded && bots.groups.none { it.loading } -> {
                            val failed = bots.groups.firstOrNull { it.error != null }
                            val empty = bots.groups.firstOrNull { it.error == null && it.profiles.none { profile -> profile.ready } }
                            when {
                                failed != null &&
                                    (failed.error == HermesError.BridgeUnreachable || failed.error == HermesError.BridgeTimeout) -> PcBridgeNotice.Unreachable(failed.name)
                                failed != null -> PcBridgeNotice.Failed(failed.error!!)
                                empty != null -> PcBridgeNotice.NoReadyBots(empty.name)
                                else -> null
                            }
                        }
                        else -> null
                    }
                    val missingSelection = effectiveSelection?.let { picked ->
                        val id = bridgeBotId(picked.bridgeId, picked.profileId)
                        if (choices.any { it.id == id }) null else {
                            Connection(id, bridgeTargetName(selectedGroup, picked.profileId), "", picked.profileId)
                        }
                    }
                    _state.update {
                        PcPicker((s.pcs + choices + listOfNotNull(missingSelection)).distinctBy { pc -> pc.id }, selectedId ?: s.activeId, target,
                            writeError = it.writeError, loaded = true, targetLoaded = targetLoaded,
                            bridgeNotice = bridgeNotice, bridgeGroups = bots.groups, bridgeSelection = effectiveSelection)
                    }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(readError = e.message ?: e.javaClass.simpleName, loaded = true, targetLoaded = true) }
            }
        }
    }

    fun selectPc(id: String) {
        val selection = state.value.bridgeSelection
        if (selection != null && bridgeBotId(selection.bridgeId, selection.profileId) == id) {
            picks.trySend(PickRequest.Bridge(selection))
            return
        }
        val choice = bridges?.state?.value?.groups?.firstNotNullOfOrNull { group ->
            group.profiles.firstOrNull { bridgeBotId(group.id, it.id) == id }?.let { BridgeSelection(group.id, it.id) }
        }
        if (choice != null) picks.trySend(PickRequest.Bridge(choice)) else picks.trySend(PickRequest.Direct(id))
    }

    fun selectBridge(bridgeId: String, profileId: String) {
        picks.trySend(PickRequest.Bridge(BridgeSelection(bridgeId, profileId)))
    }

    suspend fun currentTarget(): ActiveTarget {
        val repository = bridges ?: return store.activeTarget()
        val selected = repository.activeSelection.first()
            ?: state.value.activeId?.takeIf { it.startsWith("bridge:") }?.let { activeId ->
                repository.state.value.groups.firstNotNullOfOrNull { group ->
                    group.profiles.firstOrNull { bridgeBotId(group.id, it.id) == activeId }?.let { BridgeSelection(group.id, it.id) }
                }
            }
            ?: return store.activeTarget()
        val group = repository.state.value.groups.firstOrNull { it.id == selected.bridgeId } ?: return ActiveTarget.None
        return when (val result = repository.target(selected.bridgeId, selected.profileId)) {
            is HermesResult.Ok -> ActiveTarget.Ready(bridgeBotId(selected.bridgeId, selected.profileId), bridgeTargetName(group, selected.profileId), result.value)
            is HermesResult.Err -> ActiveTarget.Failed(result.error)
        }
    }

    fun clearWriteError() = _state.update { it.copy(writeError = null) }
}

/** The PC pill under a tab title plus its bottom sheet. Picking a PC calls [onSelect] with its id. */
@Composable
fun PcSwitcherHost(
    picker: PcPicker, onSelect: (String) -> Unit, onClearError: () -> Unit = {},
    capsuleMinWidth: Dp = Dp.Unspecified, capsuleTextStyle: TextStyle? = null, capsulePadding: Dp = 12.dp, capsuleIconSize: Dp = 16.dp,
) {
    val active = picker.pcs.firstOrNull { it.id == picker.activeId }
    // Keyed on "there is an active PC": the sheet cannot stay open across a gap with no PC.
    var open by remember(active != null) { mutableStateOf(false) }
    picker.writeError?.let { NoticeDialog(stringResource(R.string.pc_switch_failed, it), stringResource(R.string.ok), onClearError) }
    if (active == null) return
    PcSwitcher(active.name, onClick = { open = true }, minWidth = capsuleMinWidth, textStyle = capsuleTextStyle, horizontalPadding = capsulePadding, iconSize = capsuleIconSize)
    if (open) {
        PcSheet(picker.pcs, picker.bridgeGroups, active.id, onDismiss = { open = false }) { c ->
            open = false
            onSelect(c.id)
        }
    }
}

@Composable
fun PcSheet(pcs: List<Connection>, bridgeGroups: List<BridgeGroup>, activeId: String, onDismiss: () -> Unit, onPick: (Connection) -> Unit) {
    RasulSheet(stringResource(R.string.more_pcs), onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            val directPcs = pcs.filter { it.baseUrl.isNotEmpty() }
            val bridgeRows = bridgeGroups.flatMap { group ->
                group.profiles.mapNotNull { profile -> pcs.firstOrNull { it.id == bridgeBotId(group.id, profile.id) } }
            }
            val knownBridgeIds = bridgeRows.mapTo(mutableSetOf()) { it.id }
            val unavailableRows = pcs.filter { it.id.startsWith("bridge:") && it.id !in knownBridgeIds }
            var bridgeRowIndex = 0
            bridgeGroups.forEach { group ->
                val rows = group.profiles.mapNotNull { profile ->
                    pcs.firstOrNull { it.id == bridgeBotId(group.id, profile.id) }?.let { profile to it }
                }
                if (rows.isNotEmpty()) {
                    Text(group.name, style = RasulType.sectionLabel, color = Rasul.colors.muted)
                    rows.forEach { (profile, c) ->
                        val status = when {
                            group.loading -> stringResource(R.string.bridge_loading)
                            group.error != null -> stringResource(R.string.bridge_unavailable)
                            !profile.ready -> stringResource(R.string.bridge_not_ready)
                            else -> profile.id
                        }
                        PcRow(c, activeId, last = bridgeRowIndex == bridgeRows.lastIndex && unavailableRows.isEmpty() && directPcs.isEmpty(), onPick, profile.displayName, status)
                        bridgeRowIndex++
                    }
                }
            }
            unavailableRows.forEachIndexed { index, c ->
                PcRow(c, activeId, last = index == unavailableRows.lastIndex && directPcs.isEmpty(), onPick, c.name, stringResource(R.string.bridge_unavailable))
            }
            if (directPcs.isNotEmpty()) {
                if (bridgeRows.isNotEmpty() || unavailableRows.isNotEmpty()) Text(stringResource(R.string.bridge_direct_connections), style = RasulType.sectionLabel, color = Rasul.colors.muted)
                directPcs.forEachIndexed { index, c -> PcRow(c, activeId, index == directPcs.lastIndex, onPick) }
            }
        }
    }
}

@Composable
private fun PcRow(
    c: Connection,
    activeId: String,
    last: Boolean,
    onPick: (Connection) -> Unit,
    title: String = c.name,
    subtitle: String = c.baseUrl + (c.profile?.let { " · $it" } ?: ""),
) {
    RasulRow(
        onClick = { onPick(c) }, hairlineStart = 0.dp, last = last,
        contentPadding = PaddingValues(vertical = 14.dp),
        selected = c.id == activeId,
        leading = { Icon(RasulIcons.Pc, null, tint = Rasul.colors.ink, modifier = Modifier.size(24.dp)) },
        trailing = {
            if (c.id == activeId) Icon(RasulIcons.Check, null, tint = Rasul.colors.accent, modifier = Modifier.size(22.dp))
        },
    ) {
        RowTitle(title)
        RowSub(subtitle, maxLines = 1)
    }
}
