package app.rasul.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import app.rasul.R
import app.rasul.bridge.BridgeGroup
import app.rasul.ui.components.GlassIcon
import app.rasul.ui.components.BotAvatar
import app.rasul.ui.components.RasulRow
import app.rasul.ui.components.RasulIcons
import app.rasul.ui.components.RasulSheet
import app.rasul.ui.components.RowSub
import app.rasul.ui.components.RowTitle
import app.rasul.ui.components.avatarStyleFor
import app.rasul.ui.components.ScreenHeader
import app.rasul.ui.theme.RasulTheme
import app.rasul.ui.theme.Rasul
import androidx.compose.ui.res.stringResource

@Composable
fun PrimaryScreenActions(
    onSearch: () -> Unit,
    onNewChat: (() -> Unit)? = null,
    onNewBot: (() -> Unit)? = null,
    menuInitiallyExpanded: Boolean = false,
) {
    GlassIcon(RasulIcons.Search, stringResource(R.string.search_title), onSearch)
    if (onNewChat != null && onNewBot != null) {
        var expanded by rememberSaveable { mutableStateOf(menuInitiallyExpanded) }
        androidx.compose.foundation.layout.Box {
            GlassIcon(RasulIcons.Plus, stringResource(R.string.add_actions), { expanded = true })
            DropdownMenu(
                expanded = expanded, onDismissRequest = { expanded = false },
                modifier = Modifier.width(240.dp),
                offset = androidx.compose.ui.unit.DpOffset(20.dp, if (LocalConfiguration.current.locales[0].language == "ar") 4.dp else 6.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                containerColor = Rasul.colors.bg,
                border = BorderStroke(1.dp, Rasul.colors.cardBorder),
            ) {
                app.rasul.WindowHapticFeedbackProvider {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.new_chat)) },
                            onClick = { expanded = false; onNewChat() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.new_bot)) },
                            onClick = { expanded = false; onNewBot() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlusMenuPreview() = RasulTheme(false) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.tab_bots),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 0.dp),
            actionHorizontalPadding = 7.dp, actionInset = 4.dp, rightInset = 9.dp,
        ) {
            PrimaryScreenActions({}, {}, {}, menuInitiallyExpanded = true)
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 700)
@Composable
private fun PlusMenuEn() = PlusMenuPreview()

@Preview(showBackground = true, widthDp = 390, heightDp = 700, locale = "ar")
@Composable
private fun PlusMenuAr() = PlusMenuPreview()

@Composable
fun BotPickerSheet(bots: List<ChatBotChoice>, onDismiss: () -> Unit, onPick: (ChatBotChoice) -> Unit) {
    RasulSheet(stringResource(R.string.bot_picker_title), onDismiss) {
        LazyColumn(Modifier.heightIn(max = 480.dp)) {
            itemsIndexed(bots) { index, bot ->
                RasulRow(
                    onClick = { onPick(bot) },
                    hairlineStart = 54.dp,
                    last = index == bots.lastIndex,
                    leading = { BotAvatar(avatarStyleFor(bot.id), size = 40.dp) },
                ) {
                    RowTitle(bot.name)
                    bot.pcName?.let { RowSub(it) }
                }
            }
        }
    }
}

@Composable
fun BridgePickerSheet(groups: List<BridgeGroup>, onDismiss: () -> Unit, onPick: (BridgeGroup) -> Unit) {
    RasulSheet(stringResource(R.string.bridge_picker_title), onDismiss) {
        LazyColumn(Modifier.heightIn(max = 480.dp)) {
            itemsIndexed(groups) { index, group ->
                RasulRow(
                    onClick = { onPick(group) },
                    hairlineStart = 54.dp,
                    last = index == groups.lastIndex,
                    leading = { androidx.compose.material3.Icon(RasulIcons.Pc, null, tint = Rasul.colors.ink) },
                ) { RowTitle(group.name) }
            }
        }
    }
}

@Composable
private fun BotPickerPreview() = RasulTheme(false) {
    BotPickerSheet(listOf(
        ChatBotChoice("bridge:study:default", "Default", "study", "default", "Study PC"),
        ChatBotChoice("bridge:studio:default", "Default", "studio", "default", "Studio PC"),
    ), {}, {})
}

@Preview(showBackground = true, widthDp = 390, heightDp = 700)
@Composable
private fun BotPickerEn() = BotPickerPreview()

@Preview(showBackground = true, widthDp = 390, heightDp = 700, locale = "ar")
@Composable
private fun BotPickerAr() = BotPickerPreview()

@Composable
private fun BridgePickerPreview() = RasulTheme(false) {
    BridgePickerSheet(listOf(BridgeGroup("study", "Study PC"), BridgeGroup("studio", "Studio PC")), {}, {})
}

@Preview(showBackground = true, widthDp = 390, heightDp = 700)
@Composable
private fun BridgePickerEn() = BridgePickerPreview()

@Preview(showBackground = true, widthDp = 390, heightDp = 700, locale = "ar")
@Composable
private fun BridgePickerAr() = BridgePickerPreview()
