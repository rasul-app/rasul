package app.rasul.share

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.rasul.R
import app.rasul.chat.failureText
import app.rasul.data.PendingAttachment
import app.rasul.desktop.DesktopHeader
import app.rasul.sessions.SessionDto
import app.rasul.ui.ChatBotChoice
import app.rasul.ui.components.BotAvatar
import app.rasul.ui.components.EmptyState
import app.rasul.ui.components.PrimaryButton
import app.rasul.ui.components.RasulIcons
import app.rasul.ui.components.RasulRow
import app.rasul.ui.components.RowSub
import app.rasul.ui.components.RowTitle
import app.rasul.ui.components.SectionLabel
import app.rasul.ui.components.SecondaryButton
import app.rasul.ui.components.avatarStyleFor
import app.rasul.ui.text
import app.rasul.ui.theme.Rasul
import app.rasul.ui.theme.RasulShapes
import app.rasul.ui.theme.RasulType

/** The Send-to screen: what is being shared, then the bot, then New chat or one of its latest chats. */
@Composable
fun SendToContent(
    share: PendingShare,
    bots: List<ChatBotChoice>,
    botsLoading: Boolean,
    state: SendToState,
    onBack: () -> Unit,
    onPickBot: (ChatBotChoice) -> Unit,
    onNewChat: () -> Unit,
    onPickChat: (SessionDto) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Rasul.colors.bg)) {
        DesktopHeader(R.string.share_title, if (share.canSend) state.bot?.name.orEmpty() else "", onBack)
        when {
            share.loading -> Centered {
                CircularProgressIndicator(color = Rasul.colors.accent)
                Text(
                    stringResource(R.string.share_reading), style = RasulType.body, color = Rasul.colors.muted,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            !share.canSend -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                EmptyState(
                    title = stringResource(R.string.share_nothing_title),
                    body = share.failure?.let { failureText(it) } ?: stringResource(R.string.share_nothing_body),
                    action = { PrimaryButton(stringResource(R.string.share_close), onClose, modifier = Modifier.widthIn(min = 160.dp)) },
                )
            }
            else -> {
                ShareSummary(share)
                if (state.bot == null) BotList(bots, botsLoading, onPickBot, onClose)
                else ChatList(state.recents, onNewChat, onPickChat, onRetry)
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
        }
    }
}

/** The text, the item count and any left-out note, read out as one sentence by TalkBack. */
@Composable
private fun ShareSummary(share: PendingShare) {
    val photos = share.items.count { it is PendingAttachment.Photo }
    val file = share.items.filterIsInstance<PendingAttachment.TextFile>().firstOrNull()
    val items = when {
        photos > 0 -> pluralStringResource(R.plurals.share_photos, photos, photos)
        file != null -> file.name
        else -> null
    }
    val dropped = if (share.dropped > 0) pluralStringResource(R.plurals.share_dropped, share.dropped, share.dropped) else null
    val failure = share.failure?.let { failureText(it) }
    val notice = if (share.openFailed) stringResource(R.string.share_open_failed) else null
    val spoken = stringResource(
        R.string.share_summary_cd,
        listOfNotNull(share.text.take(SPOKEN_TEXT_CHARS).takeIf { it.isNotEmpty() }, items, dropped, failure, notice).joinToString(". "),
    )
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
            .background(Rasul.colors.selected, RasulShapes.filter)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (share.text.isNotEmpty()) Text(share.text, style = RasulType.body, color = Rasul.colors.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
        items?.let { Text(it, style = RasulType.meta, color = Rasul.colors.accent) }
        dropped?.let { Text(it, style = RasulType.meta, color = Rasul.colors.dangerText) }
        failure?.let { Text(it, style = RasulType.meta, color = Rasul.colors.dangerText) }
        notice?.let { Text(it, style = RasulType.meta, color = Rasul.colors.dangerText) }
    }
}

private const val SPOKEN_TEXT_CHARS = 120

@Composable
private fun BotList(bots: List<ChatBotChoice>, loading: Boolean, onPick: (ChatBotChoice) -> Unit, onClose: () -> Unit) {
    when {
        bots.isEmpty() && loading -> Centered {
            CircularProgressIndicator(color = Rasul.colors.accent)
            Text(stringResource(R.string.share_loading_bots), style = RasulType.body, color = Rasul.colors.muted)
        }
        bots.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            EmptyState(
                title = stringResource(R.string.share_no_bots_title),
                body = stringResource(R.string.share_no_bots_body),
                action = { PrimaryButton(stringResource(R.string.share_close), onClose, modifier = Modifier.widthIn(min = 160.dp)) },
            )
        }
        else -> {
            Box(Modifier.semantics { heading() }) { SectionLabel(stringResource(R.string.share_choose_bot)) }
            LazyColumn(Modifier.fillMaxSize()) {
                items(bots.size, key = { bots[it].id }) { index ->
                    val bot = bots[index]
                    RasulRow(
                        onClick = { onPick(bot) },
                        hairlineStart = 74.dp,
                        last = index == bots.lastIndex,
                        leading = { BotAvatar(avatarStyleFor(bot.id), size = 40.dp) },
                    ) {
                        RowTitle(bot.name)
                        bot.pcName?.let { RowSub(it, maxLines = 1) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatList(recents: RecentChats, onNewChat: () -> Unit, onPick: (SessionDto) -> Unit, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        RasulRow(
            onClick = onNewChat,
            hairlineStart = 74.dp,
            leading = { Icon(RasulIcons.Plus, null, tint = Rasul.colors.accent, modifier = Modifier.padding(9.dp)) },
        ) {
            RowTitle(stringResource(R.string.share_new_chat), color = Rasul.colors.accent)
            RowSub(stringResource(R.string.share_new_chat_sub), maxLines = 1)
        }
        Box(Modifier.semantics { heading() }) { SectionLabel(stringResource(R.string.share_recent_chats)) }
        when (recents) {
            RecentChats.Loading -> Centered {
                CircularProgressIndicator(color = Rasul.colors.accent)
                Text(
                    stringResource(R.string.share_loading_chats), style = RasulType.body, color = Rasul.colors.muted,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            is RecentChats.Failed -> Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(recents.error.text(), style = RasulType.body, color = Rasul.colors.dangerText, textAlign = TextAlign.Center)
                SecondaryButton(stringResource(R.string.retry), onRetry, modifier = Modifier.widthIn(min = 160.dp))
            }
            is RecentChats.Ready -> if (recents.chats.isEmpty()) {
                Text(stringResource(R.string.share_no_chats), style = RasulType.body, color = Rasul.colors.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(recents.chats, key = { it.id }) { chat ->
                        RasulRow(
                            onClick = { onPick(chat) },
                            hairlineStart = 20.dp,
                            last = chat === recents.chats.last(),
                        ) {
                            RowTitle(chat.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.session_untitled))
                            chat.preview?.takeIf { it.isNotBlank() }?.let { RowSub(it, maxLines = 1) }
                        }
                    }
                }
            }
        }
    }
}
