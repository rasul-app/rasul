package app.rasul.bridge

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.rasul.R
import app.rasul.data.HermesError
import app.rasul.ui.BotsListContent
import app.rasul.ui.ConnectionsState
import app.rasul.ui.components.*
import app.rasul.ui.text
import app.rasul.ui.theme.*

fun LazyListScope.bridgeBotItems(
    state: BridgeBotsState,
    onOpen: (String, String) -> Unit,
    onCreate: (String) -> Unit = {},
    pendingApprovals: Map<String, Int> = emptyMap(),
    onApprovals: (String, String) -> Unit = { _, _ -> },
) {
    if (state.storageError) item {
        Text(stringResource(R.string.bridge_bots_storage_error), color = Rasul.colors.danger, modifier = Modifier.padding(20.dp))
    }
    state.groups.forEach { group ->
        item(key = "group:" + group.id) {
            androidx.compose.foundation.layout.Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 16.dp)) {
                Text(group.name, style = RasulType.rowTitle, color = Rasul.colors.ink)
                SecondaryButton(stringResource(R.string.create_on_bridge, group.name), { onCreate(group.id) }, modifier = Modifier.fillMaxWidth(), enabled = group.error != HermesError.DeviceRevoked && group.error != HermesError.DeviceKeyRepair)
            }
        }
        item(key = "status:" + group.id) {
            when {
                group.error != null -> Text(group.error.text(), color = Rasul.colors.danger, style = RasulType.body, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                group.loading -> Text(stringResource(R.string.bridge_loading), color = Rasul.colors.muted, modifier = Modifier.padding(20.dp))
                group.profiles.isEmpty() -> Text(stringResource(R.string.bridge_empty), color = Rasul.colors.muted, modifier = Modifier.padding(20.dp))
            }
        }
        items(group.profiles, key = { bridgeBotId(group.id, it.id) }) { profile ->
            val enabled = profile.ready && !group.loading && group.error == null
            RasulRow(
                onClick = if (enabled) ({ onOpen(group.id, profile.id) }) else null,
                hairlineStart = 20.dp, hairlineEnd = 70.dp,
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 0.dp, bottom = 16.dp),
                textSpacing = if (profile.ready) 3.dp else 4.dp,
                leading = { BotAvatar(avatarStyleFor(bridgeBotId(group.id, profile.id))) },
                trailing = {
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                    ) {
                        pendingApprovals[bridgeBotId(group.id, profile.id)]?.let { count ->
                            ApprovalsCountBadge(count) { onApprovals(group.id, profile.id) }
                        }
                        StatusChip(stringResource(if (profile.ready) R.string.bridge_ready else R.string.bridge_not_ready),
                        if (profile.ready) ChipKind.Done else ChipKind.Neutral,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.widthIn(min = if (LocalConfiguration.current.locales[0].language == "ar") {
                            if (profile.ready) 48.dp else 72.dp
                        } else if (profile.ready) 51.dp else 79.dp))
                    }
                },
            ) {
                RowTitle(profile.displayName)
                if (!profile.ready) RowSub(stringResource(R.string.bridge_profile_not_ready), maxLines = Int.MAX_VALUE)
            }
        }
    }
}

private fun sampleBridge(error: HermesError? = null, ready: Boolean = true) = BridgeBotsState(listOf(
    BridgeGroup("sample", "Bridge", listOf(BridgeProfile("default", "default", "default", ready, true)), false, error),
))

@Composable private fun BridgePreview(error: HermesError? = null, ready: Boolean = true) = RasulTheme(false) {
    BotsListContent(ConnectionsState.Loaded(emptyList()), {}, {}, {}, {}, bridges = sampleBridge(error, ready))
}

@Preview(widthDp = 390, heightDp = 700)
@Composable private fun BridgeBotsReadyEn() = BridgePreview()
@Preview(widthDp = 390, heightDp = 700, locale = "ar")
@Composable private fun BridgeBotsReadyAr() = BridgePreview()
@Preview(widthDp = 390, heightDp = 700)
@Composable private fun BridgeBotsNotReadyEn() = BridgePreview(ready = false)
@Preview(widthDp = 390, heightDp = 700, locale = "ar")
@Composable private fun BridgeBotsNotReadyAr() = BridgePreview(ready = false)
@Preview(widthDp = 390, heightDp = 700)
@Composable private fun BridgeBotsUnreachableEn() = BridgePreview(HermesError.BridgeUnreachable)
@Preview(widthDp = 390, heightDp = 700, locale = "ar")
@Composable private fun BridgeBotsUnreachableAr() = BridgePreview(HermesError.BridgeUnreachable)
