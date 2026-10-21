package app.rasul.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.rasul.R
import app.rasul.data.Connection
import app.rasul.bridge.BridgeBotsState
import app.rasul.bridge.bridgeBotItems
import app.rasul.settings.LanguageOption
import app.rasul.ui.components.AvatarShape
import app.rasul.ui.components.AvatarStyle
import app.rasul.ui.components.ConfirmDialog
import app.rasul.ui.components.EmptyState
import app.rasul.ui.components.NoticeDialog
import app.rasul.ui.components.PrimaryButton
import app.rasul.ui.components.RasulIcons
import app.rasul.ui.components.RasulRow
import app.rasul.ui.components.RowSub
import app.rasul.ui.components.RowTitle
import app.rasul.ui.components.ScreenHeader
import app.rasul.ui.components.SegmentedSwitch
import app.rasul.ui.components.BotAvatar
import app.rasul.ui.components.avatarStyleFor
import app.rasul.ui.PrimaryScreenActions
import app.rasul.ui.theme.Rasul
import app.rasul.ui.theme.RasulTheme
import app.rasul.ui.theme.RasulType

/** Connection list and empty state. One row per saved connection, including its profile. */
@Composable
fun ConnectionsScreen(
    vm: ConnectionsViewModel, onAdd: () -> Unit, onEdit: (String) -> Unit, onOpen: (String) -> Unit,
    bridges: BridgeBotsState = BridgeBotsState(), onBridgeOpen: (String, String) -> Unit = { _, _ -> },
    onBridgeCreate: (String) -> Unit = {},
    onSearch: () -> Unit = {}, onNewChat: () -> Unit = {}, onNewBot: () -> Unit = {},
    pendingApprovals: Map<String, Int> = emptyMap(),
    onApprovals: (String, String) -> Unit = { _, _ -> },
) {
    val state by vm.state.collectAsState()
    var pendingDelete by remember { mutableStateOf<Connection?>(null) }

    BotsListContent(
        state, onAdd, onEdit, onDelete = { pendingDelete = it }, onOpen = onOpen,
        onLanguage = { AppCompatDelegate.setApplicationLocales(it.toLocales()) },
        bridges = bridges, onBridgeOpen = onBridgeOpen, onBridgeCreate = onBridgeCreate,
        onSearch = onSearch, onNewChat = onNewChat, onNewBot = onNewBot,
        pendingApprovals = pendingApprovals, onApprovals = onApprovals,
    )

    val deleteError by vm.deleteError.collectAsState()
    deleteError?.let { detail ->
        NoticeDialog(stringResource(R.string.delete_error, detail), stringResource(R.string.ok), vm::clearDeleteError)
    }
    pendingDelete?.let { c ->
        ConfirmDialog(
            title = stringResource(R.string.delete_confirm_title),
            body = stringResource(R.string.delete_confirm_body, c.name),
            cancelText = stringResource(R.string.cancel),
            confirmText = stringResource(R.string.delete),
            onCancel = { pendingDelete = null },
            onConfirm = { vm.delete(c.id); pendingDelete = null },
            destructive = true,
        )
    }
}

@Composable
fun BotsListContent(
    state: ConnectionsState,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (Connection) -> Unit,
    onLanguage: (LanguageOption) -> Unit,
    onOpen: (String) -> Unit = {},
    bridges: BridgeBotsState = BridgeBotsState(),
    onBridgeOpen: (String, String) -> Unit = { _, _ -> },
    onBridgeCreate: (String) -> Unit = {},
    onSearch: () -> Unit = {},
    onNewChat: () -> Unit = {},
    onNewBot: () -> Unit = {},
    pendingApprovals: Map<String, Int> = emptyMap(),
    onApprovals: (String, String) -> Unit = { _, _ -> },
) {
    val c = Rasul.colors
    val empty = state is ConnectionsState.Loaded && state.items.isEmpty() && bridges.groups.isEmpty() && !bridges.storageError
    Column(Modifier.fillMaxSize().background(c.bg)) {
        ScreenHeader(stringResource(R.string.tab_bots), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = if (empty) 6.dp else 0.dp), actionHorizontalPadding = 7.dp, actionInset = 4.dp, rightInset = 9.dp) {
            PrimaryScreenActions(onSearch, onNewChat, onNewBot)
        }
        if (empty) {
            val isArabic = LocalConfiguration.current.locales[0].language == "ar"
            Box(Modifier.fillMaxSize()) {
                EmptyState(
                    title = stringResource(R.string.bots_empty_title),
                    body = stringResource(R.string.bots_empty_body),
                    modifier = Modifier.align(Alignment.Center),
                    avatar = AvatarStyle(AvatarShape.Square, c.avatarViolet),
                    action = { PrimaryButton(stringResource(R.string.add_your_pc), onAdd, modifier = Modifier.widthIn(min = 214.dp), icon = RasulIcons.Plus) },
                    footnote = stringResource(R.string.bots_empty_note),
                )
                Box(Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 20.dp)) {
                    SegmentedSwitch(
                        listOf(LanguageOption.English to stringResource(R.string.language_english), LanguageOption.Arabic to stringResource(R.string.language_arabic)),
                        if (isArabic) LanguageOption.Arabic else LanguageOption.English,
                        onLanguage,
                    )
                }
            }
            return@Column
        }
        if (state !is ConnectionsState.Loaded && (bridges.groups.isNotEmpty() || bridges.storageError)) {
            LazyColumn(Modifier.weight(1f)) { bridgeBotItems(bridges, onBridgeOpen, onBridgeCreate, pendingApprovals, onApprovals) }
        }
        when (state) {
            ConnectionsState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            is ConnectionsState.Failed -> Text(
                stringResource(R.string.connections_load_error, state.detail),
                color = c.danger, style = RasulType.body, modifier = Modifier.padding(24.dp),
            )
            is ConnectionsState.Loaded -> {
                LazyColumn(Modifier.fillMaxSize()) {
                    bridgeBotItems(bridges, onBridgeOpen, onBridgeCreate, pendingApprovals, onApprovals)
                    if (bridges.groups.isNotEmpty() && state.items.isNotEmpty()) item {
                        Text(stringResource(R.string.bridge_direct_connections), style = RasulType.meta, color = c.muted, modifier = Modifier.padding(20.dp))
                    }
                    itemsIndexed(state.items, key = { _, conn -> conn.id }) { index, conn ->
                        var menu by remember { mutableStateOf(false) }
                        RasulRow(
                            onClick = { onOpen(conn.id) }, hairlineStart = 82.dp,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                start = 20.dp, end = 14.dp, top = if (index == 0) 14.dp else 12.dp,
                                bottom = if (index == 0 || LocalConfiguration.current.locales[0].language == "ar") 16.dp else 28.dp,
                            ),
                            leading = { BotAvatar(avatarStyleFor(conn.id)) },
                            trailing = {
                                Box {
                                    Icon(
                                        RasulIcons.Dots, stringResource(R.string.delete), tint = c.muted,
                                        modifier = Modifier.minimumInteractiveComponentSize().size(22.dp).clickable(role = Role.Button) { menu = true },
                                    )
                                    DropdownMenu(menu, { menu = false }) {
                                        app.rasul.WindowHapticFeedbackProvider {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.edit)) },
                                                onClick = { menu = false; onEdit(conn.id) },
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.delete), color = c.danger) },
                                                onClick = { menu = false; onDelete(conn) },
                                            )
                                        }
                                    }
                                }
                            },
                        ) {
                            RowTitle(conn.name)
                            // The last message and the working state are not known to the app; show the address.
                            RowSub(conn.baseUrl + (conn.profile?.let { "  /p/$it" } ?: ""))
                        }
                    }
                }
            }
        }
    }
}

private val sample = ConnectionsState.Loaded(
    listOf(
        Connection("a", "Home server", "http://192.0.2.7:8642"),
        Connection("b", "Research", "http://192.0.2.7:8642", "research"),
        Connection("c", "Deploys", "http://192.0.2.9:8642", "deploys"),
    ),
)

@Preview(showBackground = true, widthDp = 390, heightDp = 700)
@Composable
private fun BotsEn() = RasulTheme(false) { BotsListContent(sample, {}, {}, {}, {}) }

@Preview(showBackground = true, widthDp = 390, heightDp = 700, locale = "ar")
@Composable
private fun BotsAr() = RasulTheme(false) { BotsListContent(sample, {}, {}, {}, {}) }

@Preview(showBackground = true, widthDp = 390, heightDp = 700)
@Composable
private fun BotsEmptyEn() = RasulTheme(false) { BotsListContent(ConnectionsState.Loaded(emptyList()), {}, {}, {}, {}) }

@Preview(showBackground = true, widthDp = 390, heightDp = 700, locale = "ar")
@Composable
private fun BotsEmptyAr() = RasulTheme(false) { BotsListContent(ConnectionsState.Loaded(emptyList()), {}, {}, {}, {}) }
