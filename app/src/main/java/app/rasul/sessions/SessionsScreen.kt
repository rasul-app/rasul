package app.rasul.sessions

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.rasul.R
import app.rasul.data.ActiveTarget
import app.rasul.data.BusyWhere
import app.rasul.data.PinStore
import app.rasul.data.pinnedFirst
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import app.rasul.ui.components.RasulIcons
import app.rasul.data.HermesError
import app.rasul.data.Load
import app.rasul.data.timeText
import app.rasul.data.RelativeTimeLabels
import app.rasul.data.formatRelativeTime
import app.rasul.ui.LoadView
import app.rasul.ui.text
import app.rasul.ui.rateLimitMessage
import app.rasul.data.Connection
import app.rasul.data.SentAttachment
import app.rasul.ui.PcPicker
import app.rasul.ui.PcSwitcherHost
import app.rasul.ui.components.BotAvatar
import app.rasul.ui.components.FilterPill
import app.rasul.ui.components.GlassText
import app.rasul.ui.components.ConfirmDialog
import app.rasul.ui.components.NoticeDialog
import app.rasul.ui.components.SmallOutlineButton
import app.rasul.ui.components.RasulNavBar
import app.rasul.ui.components.RasulRow
import app.rasul.ui.components.RowSub
import app.rasul.ui.components.RowTitle
import app.rasul.ui.components.ScreenHeader
import app.rasul.ui.components.avatarStyleFor
import app.rasul.ui.PrimaryScreenActions
import app.rasul.ui.theme.Rasul
import app.rasul.ui.theme.RasulTheme
import app.rasul.ui.theme.RasulType
import app.rasul.ui.theme.RasulShapes
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.JsonNull
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private enum class SessionFilter { All, Open, Ended }
private data class PendingSession(val source: ActiveTarget.Ready, val session: SessionDto)

/** Sessions list with refresh action, connection switcher, filters, and status rows. */
@Composable
fun SessionsScreen(
    vm: SessionsViewModel,
    onOpen: (session: SessionDto, source: ActiveTarget.Ready) -> Unit,
    onPairPc: () -> Unit = {},
    onSearch: () -> Unit = {},
    onNewChat: () -> Unit = {},
    onNewBot: () -> Unit = {},
) {
    val state by vm.state.collectAsState()
    val source = (state as? Load.Ready)?.source
    val picker by vm.picker.collectAsState()
    val busy by vm.busy.collectAsState()
    val renameError by vm.renameError.collectAsState()
    val deleteError by vm.deleteError.collectAsState()
    val pinned by vm.pinned.collectAsState()
    val draftLines by vm.draftLines.collectAsState()
    val busyChats by vm.busyChats.collectAsState()
    val pinLimit by vm.pinLimit.collectAsState()
    val botChat by vm.botChat.collectAsState()
    // The list refreshes itself every few seconds only while this screen is visible.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, vm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) vm.onVisible()
            if (event == Lifecycle.Event.ON_STOP) vm.onHidden()
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) vm.onVisible()
        onDispose { lifecycle.removeObserver(observer); vm.onHidden() }
    }
    var rename by remember(picker.target) { mutableStateOf<PendingSession?>(null) }
    var confirmDelete by remember(picker.target) { mutableStateOf<PendingSession?>(null) }
    Column(Modifier.fillMaxSize().background(Rasul.colors.bg)) {
        ScreenHeader(stringResource(R.string.tab_sessions), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 0.dp), actionHorizontalPadding = 7.dp, actionInset = 4.dp, rightInset = 9.dp) {
            PrimaryScreenActions(onSearch, onNewChat, onNewBot)
            GlassText(stringResource(R.string.refresh), vm::refresh)
        }
        SessionPcSwitcherHost(picker, vm::selectPc, vm::clearWriteError)
        LoadView(
            state, onRetry = vm::retry,
            onPairPc = onPairPc.takeIf { picker.pcs.isEmpty() && picker.bridgeGroups.isEmpty() },
        ) { list ->
            SessionList(
                list, source?.id.orEmpty(), System.currentTimeMillis(),
                onOpen = { session -> source?.let { onOpen(session, it) } },
                busy = busy != null,
                onRename = { session -> source?.let { vm.clearRenameError(); rename = PendingSession(it, session) } },
                onDelete = { session -> source?.let { confirmDelete = PendingSession(it, session) } },
                pinned = pinned,
                draftLines = draftLines,
                busyChats = busyChats,
                onTogglePin = { session -> source?.let { vm.togglePin(it, session) } },
                botChat = botChat,
            )
        }
    }
    rename?.let { pending ->
        SessionRenameDialog(
            initialTitle = pending.session.title.orEmpty(),
            saving = busy != null,
            serverError = renameError?.sessionValidationMessage(),
            onCancel = { if (busy == null) { rename = null; vm.clearRenameError() } },
            onSave = { title ->
                vm.rename(pending.source, pending.session, title) {
                    rename = null
                    vm.clearRenameError()
                }
            },
        )
    }
    confirmDelete?.let { pending ->
        SessionDeleteConfirmDialog(
            title = pending.session.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.session_untitled),
            onCancel = { confirmDelete = null },
            onConfirm = {
                vm.delete(pending.source, pending.session)
                confirmDelete = null
            },
        )
    }
    if (pinLimit) NoticeDialog(stringResource(R.string.chat_pin_limit, PinStore.MAX_PER_BOT), stringResource(R.string.ok), vm::clearPinLimit)
    deleteError?.let { NoticeDialog(it.sessionMessage(), stringResource(R.string.ok), vm::clearDeleteError) }
}

@Composable
private fun SessionPcSwitcherHost(picker: PcPicker, onSelect: (String) -> Unit, onClearError: () -> Unit = {}) {
    PcSwitcherHost(
        picker, onSelect, onClearError, capsuleMinWidth = 130.dp, capsulePadding = 6.dp, capsuleIconSize = 14.dp,
        capsuleTextStyle = RasulType.pill.copy(fontSize = 13.sp, lineHeight = 18.sp,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)),
    )
}

@Composable
internal fun SessionList(
    list: List<SessionDto>,
    avatarKey: String,
    nowMillis: Long,
    onOpen: (SessionDto) -> Unit,
    busy: Boolean = false,
    onRename: (SessionDto) -> Unit = {},
    onDelete: (SessionDto) -> Unit = {},
    pinned: List<String> = emptyList(),
    draftLines: Map<String, String> = emptyMap(),
    busyChats: Map<String, BusyWhere> = emptyMap(),
    onTogglePin: (SessionDto) -> Unit = {},
    /** The bot's hidden forever chat, pinned as the first row; it is never filtered and never has a menu. */
    botChat: SessionDto? = null,
) {
    var filter by remember { mutableStateOf(SessionFilter.All) }
    if (list.isEmpty() && botChat == null) {
        Text(
            stringResource(R.string.sessions_empty), style = RasulType.body, color = Rasul.colors.muted,
            modifier = Modifier.padding(24.dp),
        )
        return
    }
    if (list.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterPill(stringResource(R.string.filter_all), filter == SessionFilter.All, width = 46.dp) { filter = SessionFilter.All }
            FilterPill(stringResource(R.string.filter_open), filter == SessionFilter.Open, width = 60.dp) { filter = SessionFilter.Open }
            FilterPill(stringResource(R.string.filter_ended), filter == SessionFilter.Ended, width = 60.dp) { filter = SessionFilter.Ended }
        }
    }
    val shown = pinnedFirst(list.filter {
        it.id != botChat?.id && when (filter) {
            SessionFilter.All -> true
            SessionFilter.Open -> it.isActive
            SessionFilter.Ended -> !it.isActive
        }
    }, pinned) { it.id }
    LazyColumn(Modifier.fillMaxSize()) {
        botChat?.let { bot ->
            item(key = bot.id) {
                BotChatRow(bot, avatarKey, nowMillis, busyChats[bot.id]) { onOpen(bot) }
            }
        }
        itemsIndexed(shown, key = { _, s -> s.id }) { index, s ->
            val isArabic = LocalConfiguration.current.locales[0].language == "ar"
            val lastActive = s.lastActive?.takeUnless { it is JsonNull }
            val time = relativeSessionTime(lastActive ?: s.startedAt, nowMillis)
            var menuOpen by remember(s.id) { mutableStateOf(false) }
            Box {
                RasulRow(
                    onClick = { onOpen(s) },
                    onLongClick = { menuOpen = true },
                    onLongClickLabel = stringResource(R.string.ux3c_chat_actions),
                    hairlineStart = if (isArabic) 78.dp else 82.dp, hairlineEnd = if (isArabic) 20.dp else 16.dp,
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = if (index == 0 && botChat == null) 10.dp else 8.dp, bottom = 16.dp),
                    leading = { BotAvatar(avatarStyleFor(avatarKey), size = 40.dp) },
                    trailing = {
                        time?.let { Text(it, style = RasulType.meta, color = Rasul.colors.muted, modifier = Modifier.widthIn(min = 32.dp), textAlign = TextAlign.End) }
                    },
                ) {
                    val isPinned = s.id in pinned
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (isPinned) Icon(
                            RasulIcons.Pin, stringResource(R.string.chat_pinned),
                            tint = Rasul.colors.accent, modifier = Modifier.size(14.dp),
                        )
                        Box(Modifier.weight(1f, fill = false)) { RowTitle(s.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.session_untitled)) }
                    }
                    val draft = draftLines[s.id]
                    if (draft != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.chat_draft_label), style = RasulType.rowSub, color = Rasul.colors.accent)
                            Box(Modifier.weight(1f, fill = false)) { RowSub(draft, maxLines = 1) }
                        }
                    } else s.preview?.takeIf { it.isNotBlank() }?.let { RowSub(it, maxLines = 1) }
                    val busyWhere = busyChats[s.id]
                    if (busyWhere != null || s.isCronRun) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (busyWhere != null) BusyChip(busyWhere)
                            if (s.isCronRun) {
                                Text(
                                    stringResource(R.string.session_job_run),
                                    modifier = Modifier.padding(top = 3.dp).background(Rasul.colors.selected, RasulShapes.filter)
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                    color = Rasul.colors.accent,
                                    style = RasulType.chip,
                                )
                            }
                        }
                    }
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    SessionMenuAction(stringResource(if (s.id in pinned) R.string.chat_unpin else R.string.chat_pin), enabled = true) {
                        menuOpen = false
                        onTogglePin(s)
                    }
                    SessionMenuAction(stringResource(R.string.ux3c_chat_rename), enabled = !busy) {
                        menuOpen = false
                        onRename(s)
                    }
                    SessionMenuAction(stringResource(R.string.ux3c_chat_delete), enabled = !busy) {
                        menuOpen = false
                        onDelete(s)
                    }
                }
            }
        }
    }
}

/**
 * The "Laptop chat" row: the bot's hidden forever chat, styled like the other rows but titled and subtitled
 * from strings (the server title is the internal "Bot Chat"), with no long-press menu — pin, rename (a PATCH)
 * and delete must not reach it. [busyWhere] reuses the same map as normal rows when the id is busy.
 */
@Composable
private fun BotChatRow(
    session: SessionDto,
    avatarKey: String,
    nowMillis: Long,
    busyWhere: BusyWhere?,
    onOpen: () -> Unit,
) {
    val isArabic = LocalConfiguration.current.locales[0].language == "ar"
    val time = relativeSessionTime(session.lastActive?.takeUnless { it is JsonNull } ?: session.startedAt, nowMillis)
    RasulRow(
        onClick = onOpen,
        hairlineStart = if (isArabic) 78.dp else 82.dp, hairlineEnd = if (isArabic) 20.dp else 16.dp,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 16.dp),
        leading = { BotAvatar(avatarStyleFor(avatarKey), size = 40.dp) },
        trailing = {
            time?.let { Text(it, style = RasulType.meta, color = Rasul.colors.muted, modifier = Modifier.widthIn(min = 32.dp), textAlign = TextAlign.End) }
        },
    ) {
        RowTitle(stringResource(R.string.bot_chat_title))
        RowSub(stringResource(R.string.bot_chat_subtitle), maxLines = 1)
        if (busyWhere != null) BusyChip(busyWhere)
    }
}

@Composable
private fun BusyChip(where: BusyWhere) {
    Text(
        stringResource(if (where == BusyWhere.Desktop) R.string.chat_busy_pc else R.string.chat_busy_now),
        modifier = Modifier.padding(top = 3.dp).background(Rasul.colors.selected, RasulShapes.filter)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        color = Rasul.colors.accent,
        style = RasulType.chip,
    )
}

@Composable
private fun SessionMenuAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { role = Role.Button }.padding(horizontal = 16.dp, vertical = 12.dp),
        color = if (enabled) Rasul.colors.ink else Rasul.colors.muted,
        style = RasulType.body,
    )
}

@Composable
internal fun SessionRenameDialog(
    initialTitle: String,
    saving: Boolean,
    serverError: String?,
    onCancel: () -> Unit,
    onSave: (title: String) -> Unit,
) {
    Dialog(onDismissRequest = onCancel) {
        app.rasul.ui.components.DialogScrim()
        SessionRenameContent(initialTitle, saving, serverError, onCancel, onSave)
    }
}

@Composable
internal fun SessionRenameContent(
    initialTitle: String,
    saving: Boolean,
    serverError: String?,
    onCancel: () -> Unit,
    onSave: (title: String) -> Unit,
) {
    val c = Rasul.colors
    var title by remember(initialTitle) { mutableStateOf(initialTitle) }
    var attempted by remember(initialTitle) { mutableStateOf(false) }
    val missingTitle = attempted && title.isBlank()
    Column(
        Modifier.fillMaxWidth().background(c.bg, RasulShapes.dialog).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
            Text(stringResource(R.string.ux3c_chat_rename_title), style = RasulType.dialogTitle, color = c.ink)
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                enabled = !saving,
                label = { Text(stringResource(R.string.ux3c_chat_title)) },
                isError = missingTitle,
                supportingText = if (missingTitle) { { Text(stringResource(R.string.ux3c_chat_title_required)) } } else null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            serverError?.takeIf { it.isNotBlank() }?.let { Text(it, style = RasulType.meta, color = c.danger) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                SmallOutlineButton(stringResource(R.string.cancel), onCancel, enabled = !saving, width = if (androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "ar") 58.dp else 74.dp)
                SmallOutlineButton(
                    stringResource(R.string.save),
                    {
                        attempted = true
                        val capturedTitle = title.trim()
                        if (capturedTitle.isNotEmpty()) onSave(capturedTitle)
                    },
                    enabled = !saving, width = if (androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "ar") 58.dp else 60.dp,
                )
            }
    }
}

@Composable
internal fun SessionDeleteConfirmDialog(title: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.ux3c_chat_delete_title),
        body = stringResource(R.string.ux3c_chat_delete_body, title),
        cancelText = stringResource(R.string.cancel),
        confirmText = stringResource(R.string.ux3c_chat_delete),
        onCancel = onCancel,
        onConfirm = onConfirm,
        destructive = true,
    )
}

internal fun sessionValidationText(error: HermesError.Server, context: Context): String =
    rateLimitMessage(error, context) ?: if (error.serverCode == "invalid_title") {
        context.getString(R.string.ux3c_session_validation_invalid_title)
    } else {
        context.getString(R.string.ux3c_session_validation_fallback, error.message.orEmpty())
    }

internal fun sessionMessageText(error: HermesError.Server, context: Context): String =
    rateLimitMessage(error, context) ?: error.message?.takeIf { it.isNotBlank() }
    ?: context.getString(R.string.err_server, error.code)

@Composable
private fun HermesError.sessionValidationMessage(): String = when (this) {
    is HermesError.Server -> sessionValidationText(this, LocalContext.current)
    else -> text()
}

@Composable
private fun HermesError.sessionMessage(): String = when (this) {
    is HermesError.Server -> sessionMessageText(this, LocalContext.current)
    else -> text()
}

@Composable
private fun relativeSessionTime(value: kotlinx.serialization.json.JsonElement?, nowMillis: Long): String? {
    val primitive = value as? JsonPrimitive ?: return timeText(value)
    val millis = primitive.doubleOrNull?.let { (it * 1000.0).toLong() } ?: try {
        java.time.OffsetDateTime.parse(primitive.content).toInstant().toEpochMilli()
    } catch (_: java.time.DateTimeException) {
        return timeText(value)
    }
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val zone = TimeZone.getDefault()
    return formatRelativeTime(
        millis, nowMillis, locale,
        RelativeTimeLabels(
            now = context.getString(R.string.relative_now),
            minutes = { count, localized -> context.resources.getQuantityString(R.plurals.relative_minutes, count, localized) },
            hours = { count, localized -> context.resources.getQuantityString(R.plurals.relative_hours, count, localized) },
            yesterday = context.getString(R.string.relative_yesterday),
            date = { millis, dateLocale, timeZone ->
                DateFormat.getDateInstance(DateFormat.MEDIUM, dateLocale).apply { this.timeZone = timeZone }.format(Date(millis))
            },
        ),
        zone,
    )
}

@Composable
fun TranscriptScreen(
    vm: TranscriptViewModel,
    onBack: () -> Unit,
    connectionUnchanged: Boolean = true,
    onContinue: () -> Unit = {},
) {
    val state by vm.state.collectAsState()
    val continuing by vm.continuing.collectAsState()
    Column(Modifier.fillMaxSize().background(Rasul.colors.bg).padding(top = 16.dp)) {
        RasulNavBar(stringResource(R.string.transcript_title), stringResource(R.string.back), onBack, horizontalPadding = 40.dp, leadingAlignment = Alignment.Center)
        if (vm.canContinue && connectionUnchanged && state is Load.Ready) ContinueInChatButton(enabled = !continuing, onClick = onContinue)
        LoadView(state, onRetry = vm::refresh) { msgs -> TranscriptMessages(msgs, vm.isCronSession) }
    }
}

@Composable
internal fun ContinueInChatButton(enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text(stringResource(R.string.transcript_continue))
    }
}

@Composable
internal fun TranscriptMessages(msgs: List<SessionMessageDto>, isCronRun: Boolean = false) {
    if (msgs.isEmpty()) {
        Text(
            stringResource(R.string.transcript_empty), style = RasulType.body, color = Rasul.colors.muted,
            modifier = Modifier.padding(24.dp),
        )
    } else {
        LazyColumn(
            Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(msgs) { index, m ->
                Column(Modifier.fillMaxWidth()) {
                    if (isCronRun && index == 0) {
                        var expanded by remember(m.id) { mutableStateOf(false) }
                        val stateDescription = stringResource(
                            if (expanded) R.string.transcript_instructions_expanded else R.string.transcript_instructions_collapsed,
                        )
                        Text(
                            stringResource(R.string.transcript_job_instructions),
                            style = RasulType.chip.copy(lineHeight = 15.sp),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .clickable(role = Role.Button) { expanded = !expanded }
                                .semantics { role = Role.Button; this.stateDescription = stateDescription }.padding(vertical = 8.dp),
                            color = Rasul.colors.accent,
                        )
                        if (expanded) {
                            Text(m.displayText(), style = RasulType.body, color = Rasul.colors.ink, modifier = Modifier.heightIn(min = 21.dp))
                        }
                    } else {
                        Text(
                            listOfNotNull(m.role?.let { roleLabel(it) }, m.toolName).joinToString(" · "),
                            style = RasulType.chip.copy(lineHeight = 15.sp),
                            modifier = Modifier.heightIn(min = 18.dp),
                            color = Rasul.colors.accent,
                        )
                        val text = m.displayText()
                        if (text.isNotEmpty() || m.attachments().isEmpty()) {
                            Text(text, style = RasulType.body, color = Rasul.colors.ink, modifier = Modifier.heightIn(min = 21.dp))
                        }
                        m.attachments().forEach { a ->
                            Text(
                                when (a) {
                                    is SentAttachment.NamedFile -> a.name
                                    is SentAttachment.SentFile -> a.name
                                    else -> stringResource(R.string.chat_photo_label)
                                },
                                style = RasulType.body, color = Rasul.colors.muted, modifier = Modifier.heightIn(min = 21.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Known protocol roles in the app language; an unknown role is shown as received. */
@Composable
private fun roleLabel(role: String): String = when (role) {
    "user" -> stringResource(R.string.role_user)
    "assistant" -> stringResource(R.string.role_assistant)
    "tool" -> stringResource(R.string.role_tool)
    "system" -> stringResource(R.string.role_system)
    else -> role
}

private val sampleSessions = listOf(
    SessionDto("1", "Nightly deploy summary", source = "cron", preview = "The report is ready to review.", startedAt = JsonPrimitive(1_759_999_700), messageCount = 12),
    SessionDto(
        "2", "Fix flaky login test", preview = "I found the timing issue.", startedAt = JsonPrimitive(1_759_992_800),
        endedAt = JsonPrimitive(1_759_999_000), messageCount = 4,
    ),
)

private val samplePcs = listOf(Connection("a", "Home PC", "http://192.0.2.7:8642", "default"))

private val sampleMessages = listOf(
    SessionMessageDto(role = "user", content = JsonPrimitive("Summarize last night's deploy.")),
    SessionMessageDto(role = "assistant", content = JsonPrimitive("The deploy finished at 03:12 with no errors.")),
    SessionMessageDto(role = "tool", toolName = "shell", content = JsonPrimitive("exit 0")),
)

@Composable
private fun TranscriptPreviewBody() = RasulTheme(false) {
    Column(Modifier.fillMaxSize().background(Rasul.colors.bg).padding(top = 16.dp)) {
        RasulNavBar(stringResource(R.string.transcript_title), stringResource(R.string.back), {}, horizontalPadding = 40.dp, leadingAlignment = Alignment.Center)
        ContinueInChatButton {}
        TranscriptMessages(sampleMessages)
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 700)
@Composable
private fun TranscriptEn() = TranscriptPreviewBody()

@Preview(showBackground = true, widthDp = 390, heightDp = 700, locale = "ar")
@Composable
private fun TranscriptAr() = TranscriptPreviewBody()

@Composable
private fun SessionsPreviewBody() = RasulTheme(false) {
    Column(Modifier.fillMaxSize().background(Rasul.colors.bg)) {
        ScreenHeader(stringResource(R.string.tab_sessions), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 0.dp), actionHorizontalPadding = 7.dp, actionInset = 4.dp, rightInset = 9.dp) {
            PrimaryScreenActions({}, {}, {})
            GlassText(stringResource(R.string.refresh), {})
        }
        SessionPcSwitcherHost(PcPicker(samplePcs, "a"), {})
        SessionList(sampleSessions, "a", 1_760_000_000_000L, onOpen = {})
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 700)
@Composable
private fun SessionsEn() = SessionsPreviewBody()

@Preview(showBackground = true, widthDp = 390, heightDp = 700, locale = "ar")
@Composable
private fun SessionsAr() = SessionsPreviewBody()

@Composable
private fun SessionsBusyPreviewBody() = RasulTheme(false) {
    Column(Modifier.fillMaxSize().background(Rasul.colors.bg)) {
        ScreenHeader(stringResource(R.string.tab_sessions), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 0.dp), actionHorizontalPadding = 7.dp, actionInset = 4.dp, rightInset = 9.dp) {
            PrimaryScreenActions({}, {}, {})
            GlassText(stringResource(R.string.refresh), {})
        }
        SessionPcSwitcherHost(PcPicker(samplePcs, "a"), {})
        SessionList(
            sampleSessions, "a", 1_760_000_000_000L, onOpen = {},
            busyChats = mapOf("1" to BusyWhere.Desktop, "2" to BusyWhere.App),
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 700)
@Composable
private fun SessionsBusyEn() = SessionsBusyPreviewBody()

@Preview(showBackground = true, widthDp = 390, heightDp = 700, locale = "ar")
@Composable
private fun SessionsBusyAr() = SessionsBusyPreviewBody()

@Preview(showBackground = true, widthDp = 390, heightDp = 700)
@Composable
private fun SessionRenameEn() = RasulTheme(false) {
    SessionRenameDialog("Nightly summary", saving = false, serverError = null, onCancel = {}, onSave = {})
}

@Preview(showBackground = true, widthDp = 390, heightDp = 700, locale = "ar")
@Composable
private fun SessionRenameAr() = RasulTheme(false) {
    SessionRenameDialog("Nightly summary", saving = false, serverError = null, onCancel = {}, onSave = {})
}

@Composable
private fun BridgePcPreviewBody(state: Load<Nothing>) = RasulTheme(false) {
    val pcId = "bridge:sample:default"
    val pc = Connection(pcId, "Studio PC · Default", "", "default")
    Column(Modifier.fillMaxSize().background(Rasul.colors.bg)) {
        ScreenHeader(stringResource(R.string.tab_sessions), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 0.dp), actionHorizontalPadding = 7.dp, actionInset = 4.dp, rightInset = 9.dp) { GlassText(stringResource(R.string.refresh), {}) }
        SessionPcSwitcherHost(
            PcPicker(
                pcs = listOf(pc), activeId = pcId, target = ActiveTarget.Failed(app.rasul.data.HermesError.BridgeUnreachable),
                loaded = true, targetLoaded = true,
            ),
            {},
        )
        LoadView(state, onRetry = {}) {}
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 700)
@Composable
private fun BridgePcUnreachableEn() = BridgePcPreviewBody(Load.BridgeUnreachable("Studio PC"))

@Preview(showBackground = true, widthDp = 390, heightDp = 700, locale = "ar")
@Composable
private fun BridgePcUnreachableAr() = BridgePcPreviewBody(Load.BridgeUnreachable("Studio PC"))

@Preview(showBackground = true, widthDp = 390, heightDp = 700)
@Composable
private fun BridgePcNoReadyBotsEn() = BridgePcPreviewBody(Load.BridgeNoReadyBots("Studio PC"))

@Preview(showBackground = true, widthDp = 390, heightDp = 700, locale = "ar")
@Composable
private fun BridgePcNoReadyBotsAr() = BridgePcPreviewBody(Load.BridgeNoReadyBots("Studio PC"))
