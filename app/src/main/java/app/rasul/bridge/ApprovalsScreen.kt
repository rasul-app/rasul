package app.rasul.bridge

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Spacer
import app.rasul.R
import app.rasul.ui.components.ChipKind
import app.rasul.ui.components.ConfirmDialog
import app.rasul.ui.components.PrimaryButton
import app.rasul.ui.components.RasulIcons
import app.rasul.ui.components.SecondaryButton
import app.rasul.ui.components.StatusChip
import app.rasul.ui.theme.Rasul
import app.rasul.ui.theme.RasulMonoFontFamily
import app.rasul.ui.theme.RasulShapes
import app.rasul.ui.theme.RasulType
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun ApprovalsScreen(vm: ApprovalsViewModel, botName: String, onBack: () -> Unit) {
    val state by vm.state.collectAsState()
    ApprovalsContent(state, botName, onAnswer = vm::answer, onReload = vm::reload, onBack = onBack)
}

@Composable
fun ApprovalsContent(
    state: ApprovalsScreenState,
    botName: String,
    nowEpochSec: Long? = null,
    onAnswer: (String, String) -> Unit = { _, _ -> },
    onReload: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    val colors = Rasul.colors
    var tick by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    if (nowEpochSec == null) {
        LaunchedEffect(Unit) { while (true) { delay(1000); tick = System.currentTimeMillis() / 1000 } }
    }
    val now = nowEpochSec ?: tick
    var confirmAlwaysFor by rememberSaveable { mutableStateOf<String?>(null) }
    val backName = stringResource(R.string.back)
    // Expired entries stay hidden; the next authoritative snapshot clears them for good
    // (docs/bridge-approvals.md:75).
    val cards = state.cards.filter { it.item.expiresAt > now }

    Column(Modifier.fillMaxSize().background(colors.bg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp).semantics { contentDescription = backName }) {
                Icon(RasulIcons.Back, contentDescription = null, tint = colors.accent)
            }
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text(stringResource(R.string.approvals_title), style = RasulType.sheetTitle, color = colors.ink)
                Text(botName, style = RasulType.meta, color = colors.muted)
            }
        }
        when {
            state.direct -> ApprovalsMessage(stringResource(R.string.approvals_bridge_only))
            state.unsupported -> ApprovalsMessage(stringResource(R.string.approvals_unsupported), onReload)
            state.loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val loadingName = stringResource(R.string.approvals_loading)
                CircularProgressIndicator(color = colors.accent, modifier = Modifier.semantics { contentDescription = loadingName })
            }
            cards.isEmpty() && state.feedError != null ->
                ApprovalsMessage(stringResource(state.feedError!!.message), onReload, isError = true)
            else -> LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.feedError?.let { error ->
                    item(key = "feed-error") {
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                stringResource(error.message), Modifier.weight(1f),
                                color = colors.dangerText, style = RasulType.bodySmall,
                            )
                            TextButton(onClick = onReload) { Text(stringResource(R.string.retry)) }
                        }
                    }
                }
                if (cards.isEmpty()) {
                    item(key = "empty") {
                        Column(
                            Modifier.fillMaxWidth().padding(top = 96.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(stringResource(R.string.approvals_empty_title), style = RasulType.emptyTitle, color = colors.ink, textAlign = TextAlign.Center)
                            Text(stringResource(R.string.approvals_empty_body), style = RasulType.body, color = colors.muted, textAlign = TextAlign.Center)
                        }
                    }
                }
                items(cards, key = { it.item.requestId }) { card ->
                    ApprovalCard(card, now, onAnswer, onAlways = { confirmAlwaysFor = it })
                }
            }
        }
    }

    confirmAlwaysFor?.let { id ->
        // The expiry-filtered list, not state.cards: a hidden expired request cannot be confirmed.
        val item = cards.firstOrNull { it.item.requestId == id }?.item
        if (item == null) LaunchedEffect(id) { confirmAlwaysFor = null }
        else ConfirmDialog(
            title = stringResource(R.string.approvals_always_confirm_title),
            body = stringResource(R.string.approvals_always_confirm_body),
            cancelText = stringResource(R.string.cancel),
            confirmText = stringResource(R.string.approvals_always_confirm_yes),
            onCancel = { confirmAlwaysFor = null },
            onConfirm = { confirmAlwaysFor = null; onAnswer(id, "always") },
        ) {
            // The permanent approval covers exactly this request's tool and command.
            StatusChip(item.surface, ChipKind.Neutral)
            if (item.description.isNotBlank()) Text(item.description, style = RasulType.body, color = colors.ink)
            AlwaysConfirmCommand(item.command)
        }
    }
}

@Composable
private fun ApprovalsMessage(text: String, onReload: (() -> Unit)? = null, isError: Boolean = false) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text, style = RasulType.body, color = if (isError) Rasul.colors.dangerText else Rasul.colors.muted, textAlign = TextAlign.Center)
            if (onReload != null) TextButton(onClick = onReload) { Text(stringResource(R.string.retry)) }
        }
    }
}

@Composable
private fun ApprovalCard(
    card: ApprovalCardState,
    now: Long,
    onAnswer: (String, String) -> Unit,
    onAlways: (String) -> Unit,
) {
    val colors = Rasul.colors
    val item = card.item
    Column(
        Modifier.fillMaxWidth().clip(RasulShapes.card).border(1.dp, colors.cardBorder, RasulShapes.card).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip(item.surface, ChipKind.Neutral)
            Spacer(Modifier.weight(1f))
            ApprovalsCountdown(item.expiresAt, now)
        }
        if (item.description.isNotBlank()) Text(item.description, style = RasulType.body, color = colors.ink)
        ApprovalCommand(item.command)
        card.error?.let { error ->
            Text(
                stringResource(error.message), style = RasulType.body, color = colors.dangerText,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        ApprovalButtons(item, card.sending, onAnswer, onAlways)
    }
}

/** The Always confirmation's copy of the card's command box: scrollable, never ellipsized. */
@Composable
private fun AlwaysConfirmCommand(command: String) {
    val colors = Rasul.colors
    Column(
        Modifier.fillMaxWidth().heightIn(max = 160.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.ink)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        // Command text is always laid out left-to-right, matching the card.
        androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(
                command,
                style = RasulType.body.copy(fontFamily = RasulMonoFontFamily, fontSize = 13.sp),
                color = colors.bg,
            )
        }
    }
}

/** The display text the bridge already redacted; never executable (docs/bridge-approvals.md:70-72). */
@Composable
private fun ApprovalCommand(command: String) {
    val colors = Rasul.colors
    var expanded by remember { mutableStateOf(false) }
    var overflowed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        SelectionContainer {
            Column(
                Modifier.fillMaxWidth().background(colors.ink, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                // Command text is always laid out left-to-right, matching the chat approval sheet.
                androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(
                        command,
                        style = RasulType.body.copy(fontFamily = RasulMonoFontFamily, fontSize = 13.sp),
                        color = colors.bg,
                        maxLines = if (expanded) Int.MAX_VALUE else 6,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { if (!expanded) overflowed = it.hasVisualOverflow },
                    )
                }
            }
        }
        if (overflowed || expanded) {
            Text(
                stringResource(if (expanded) R.string.approvals_show_less else R.string.approvals_show_more),
                style = RasulType.bodySmall, color = colors.accent,
                modifier = Modifier.align(Alignment.End).padding(top = 2.dp)
                    .minimumInteractiveComponentSize()
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ApprovalsCountdown(expiresAt: Double, now: Long) {
    val remaining = (expiresAt - now).coerceAtLeast(0.0).toLong()
    // The accessibility text changes once a minute at most; the ticking digits are display-only.
    val announce = if (remaining < 60) stringResource(R.string.approvals_expires_soon)
    else pluralStringResource(R.plurals.approvals_expires_announce, ((remaining + 59) / 60).toInt(), ((remaining + 59) / 60).toInt())
    Text(
        stringResource(R.string.approvals_expires_in, String.format(Locale.US, "%d:%02d", remaining / 60, remaining % 60)),
        style = RasulType.meta.copy(fontFamily = RasulMonoFontFamily),
        color = if (remaining < 60) Rasul.colors.warnText else Rasul.colors.muted,
        modifier = Modifier.semantics { contentDescription = announce },
    )
}

@Composable
private fun ApprovalButtons(
    item: ApprovalItemDto,
    busy: Boolean,
    onAnswer: (String, String) -> Unit,
    onAlways: (String) -> Unit,
) {
    val colors = Rasul.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryButton(
            text = stringResource(R.string.approval_once),
            onClick = { onAnswer(item.requestId, "once") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
        )
        if (item.allowSession) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(
                    text = stringResource(R.string.approvals_session),
                    onClick = { onAnswer(item.requestId, "session") },
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                )
                if (item.allowPermanent) SecondaryButton(
                    text = stringResource(R.string.approvals_always),
                    onClick = { onAlways(item.requestId) },
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                )
            }
        }
        OutlinedButton(
            onClick = { onAnswer(item.requestId, "deny") },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = RasulShapes.button,
            border = BorderStroke(1.dp, colors.danger.copy(alpha = 0.35f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.danger),
        ) {
            Text(stringResource(R.string.approval_deny), style = RasulType.button)
        }
    }
}

// ---------- banner, badge ----------

/** In-app banner while any bridge bot has a pending approval; opens that bot's approvals screen. */
@Composable
fun ApprovalsBanner(text: String, onOpen: () -> Unit) {
    val c = Rasul.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp)
            .clip(RasulShapes.card)
            .background(c.warn.copy(alpha = 0.12f))
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = text }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(RasulIcons.Info, contentDescription = null, tint = c.warn, modifier = Modifier.size(20.dp))
        Text(text, Modifier.weight(1f), style = RasulType.body, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(RasulIcons.Chev, contentDescription = null, tint = c.muted, modifier = Modifier.size(14.dp))
    }
}

/** Count pill on a bots-list row; opens that bot's approvals screen. */
@Composable
internal fun ApprovalsCountBadge(count: Int, onClick: () -> Unit) {
    val c = Rasul.colors
    val label = pluralStringResource(R.plurals.approvals_waiting_cd, count, count)
    Box(
        Modifier.minimumInteractiveComponentSize()
            .semantics { contentDescription = label }
            .clip(RasulShapes.chip)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "99+" else count.toString(),
            style = RasulType.chip.copy(fontWeight = FontWeight.SemiBold),
            color = Color.White,
            modifier = Modifier.background(c.badge, RoundedCornerShape(10.dp)).padding(horizontal = 7.dp, vertical = 1.dp),
        )
    }
}

/** The bot whose screen the banner opens: the first one with unexpired pending approvals. */
data class ApprovalsBannerData(val bridgeId: String, val profileId: String, val botName: String, val count: Int)

/**
 * Delay until the soonest pending approval's expiry — the banner/badge re-tick — or null when
 * nothing is pending expiry (the ticker rests until the feeds change).
 */
internal fun nextApprovalExpiryDelayMs(expiresAtSec: List<Double>, nowSec: Long): Long? {
    val next = expiresAtSec.filter { it > nowSec }.minOrNull() ?: return null
    return (((next - nowSec) * 1000).toLong() + 100).coerceAtLeast(300)
}

internal fun approvalsBannerData(
    feeds: Map<String, ApprovalsRepository.Feed>,
    bots: BridgeBotsState,
    nowEpochSec: Long,
): ApprovalsBannerData? {
    for (group in bots.groups) for (profile in group.profiles) {
        val feed = feeds[bridgeBotId(group.id, profile.id)] ?: continue
        if (feed.unsupported) continue
        val count = feed.items.count { it.expiresAt > nowEpochSec }
        if (count > 0) return ApprovalsBannerData(group.id, profile.id, profile.displayName, count)
    }
    return null
}

// Screenshots live in ApprovalsScreenshotTest (approvals-*.png): the only reference set for
// these screens — the preview scanner duplicates the same three states, so no previews here.
