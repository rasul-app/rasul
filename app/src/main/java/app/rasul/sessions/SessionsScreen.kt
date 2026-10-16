package app.rasul.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.rasul.R
import app.rasul.data.ActiveTarget
import app.rasul.data.Load
import app.rasul.data.timeText
import app.rasul.ui.LoadView
import app.rasul.data.Connection
import app.rasul.ui.PcPicker
import app.rasul.ui.PcSwitcherHost
import app.rasul.ui.components.BotAvatar
import app.rasul.ui.components.ChipKind
import app.rasul.ui.components.FilterPill
import app.rasul.ui.components.GlassText
import app.rasul.ui.components.RasulNavBar
import app.rasul.ui.components.RasulRow
import app.rasul.ui.components.RowSub
import app.rasul.ui.components.RowTitle
import app.rasul.ui.components.ScreenHeader
import app.rasul.ui.components.StatusChip
import app.rasul.ui.components.avatarStyleFor
import app.rasul.ui.theme.Rasul
import app.rasul.ui.theme.RasulTheme
import app.rasul.ui.theme.RasulType
import kotlinx.serialization.json.JsonPrimitive

private enum class SessionFilter { All, Open, Ended }

/** Sessions list with refresh action, connection switcher, filters, and status rows. */
@Composable
fun SessionsScreen(vm: SessionsViewModel, onOpen: (id: String, source: ActiveTarget.Ready) -> Unit) {
    val state by vm.state.collectAsState()
    val source = (state as? Load.Ready)?.source
    Column(Modifier.fillMaxSize().background(Rasul.colors.bg)) {
        ScreenHeader(stringResource(R.string.tab_sessions)) { GlassText(stringResource(R.string.refresh), vm::refresh) }
        val picker by vm.picker.collectAsState()
        PcSwitcherHost(picker, vm::selectPc, vm::clearWriteError)
        LoadView(state, onRetry = vm::retry) { list -> SessionList(list) { id -> source?.let { onOpen(id, it) } } }
    }
}

@Composable
internal fun SessionList(list: List<SessionDto>, onOpen: (String) -> Unit) {
    var filter by remember { mutableStateOf(SessionFilter.All) }
    if (list.isEmpty()) {
        Text(
            stringResource(R.string.sessions_empty), style = RasulType.body, color = Rasul.colors.muted,
            modifier = Modifier.padding(24.dp),
        )
        return
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterPill(stringResource(R.string.filter_all), filter == SessionFilter.All) { filter = SessionFilter.All }
        FilterPill(stringResource(R.string.filter_open), filter == SessionFilter.Open) { filter = SessionFilter.Open }
        FilterPill(stringResource(R.string.filter_ended), filter == SessionFilter.Ended) { filter = SessionFilter.Ended }
    }
    val shown = list.filter {
        when (filter) {
            SessionFilter.All -> true
            SessionFilter.Open -> it.isActive
            SessionFilter.Ended -> !it.isActive
        }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(shown, key = { it.id }) { s ->
            val time = timeText(s.lastActive ?: s.startedAt)
            RasulRow(
                onClick = { onOpen(s.id) }, hairlineStart = 74.dp,
                leading = { BotAvatar(avatarStyleFor(s.id), size = 40.dp) },
                trailing = {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        time?.let { Text(it, style = RasulType.meta, color = Rasul.colors.muted) }
                        if (s.isActive) {
                            StatusChip(stringResource(R.string.filter_open), ChipKind.Running)
                        } else {
                            StatusChip(stringResource(R.string.filter_ended), ChipKind.Neutral)
                        }
                    }
                },
            ) {
                RowTitle(s.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.session_untitled))
                RowSub(
                    s.preview?.takeIf { it.isNotBlank() }
                        ?: s.messageCount?.let { stringResource(R.string.session_messages, it) }.orEmpty(),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun TranscriptScreen(vm: TranscriptViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsState()
    Column(Modifier.fillMaxSize().background(Rasul.colors.bg)) {
        RasulNavBar(stringResource(R.string.transcript_title), stringResource(R.string.back), onBack)
        LoadView(state, onRetry = vm::refresh) { msgs -> TranscriptMessages(msgs) }
    }
}

@Composable
internal fun TranscriptMessages(msgs: List<SessionMessageDto>) {
    if (msgs.isEmpty()) {
        Text(
            stringResource(R.string.transcript_empty), style = RasulType.body, color = Rasul.colors.muted,
            modifier = Modifier.padding(24.dp),
        )
    } else {
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(msgs) { m ->
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        listOfNotNull(m.role?.let { roleLabel(it) }, m.toolName).joinToString(" · "),
                        style = RasulType.chip,
                        color = Rasul.colors.accent,
                    )
                    Text(m.text(), style = RasulType.body, color = Rasul.colors.ink)
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
    SessionDto("1", "Nightly deploy summary", preview = "Research", startedAt = JsonPrimitive(1_700_000_000), messageCount = 12),
    SessionDto(
        "2", "Fix flaky login test", preview = "Hermes", startedAt = JsonPrimitive(1_699_990_000),
        endedAt = JsonPrimitive(1_699_999_000), messageCount = 4,
    ),
)

private val samplePcs = listOf(Connection("a", "Home PC", "http://100.64.0.7:8642", "default"))

private val sampleMessages = listOf(
    SessionMessageDto(role = "user", content = JsonPrimitive("Summarize last night's deploy.")),
    SessionMessageDto(role = "assistant", content = JsonPrimitive("The deploy finished at 03:12 with no errors.")),
    SessionMessageDto(role = "tool", toolName = "shell", content = JsonPrimitive("exit 0")),
)

@Composable
private fun TranscriptPreviewBody() = RasulTheme(false) {
    Column(Modifier.fillMaxSize().background(Rasul.colors.bg)) {
        RasulNavBar(stringResource(R.string.transcript_title), stringResource(R.string.back), {})
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
        ScreenHeader(stringResource(R.string.tab_sessions)) { GlassText(stringResource(R.string.refresh), {}) }
        PcSwitcherHost(PcPicker(samplePcs, "a"), {})
        SessionList(sampleSessions) {}
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 700)
@Composable
private fun SessionsEn() = SessionsPreviewBody()

@Preview(showBackground = true, widthDp = 390, heightDp = 700, locale = "ar")
@Composable
private fun SessionsAr() = SessionsPreviewBody()
