package app.rasul.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.rasul.R
import app.rasul.ui.components.RasulIcons
import app.rasul.ui.components.RasulSheet
import app.rasul.ui.theme.Rasul
import app.rasul.ui.theme.RasulShapes
import app.rasul.ui.theme.RasulType
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.util.Locale

/** The line above the composer: "Working… · N running tasks" while a run is live, "N tasks still running" after it. */
@Composable
internal fun TasksPillRow(pill: TasksPill, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Rasul.colors
    val label = when (pill) {
        is TasksPill.Working -> pluralStringResource(R.plurals.bg_running_tasks, pill.count, pill.count)
        is TasksPill.StillRunning -> pluralStringResource(R.plurals.bg_still_running, pill.count, pill.count)
    }
    val prefix = if (pill is TasksPill.Working) stringResource(R.string.bg_working) else null
    // The pill is 28 dp tall as drawn; the touch target is 48 dp and overflows the row evenly.
    Box(modifier.height(28.dp).requiredHeight(48.dp), contentAlignment = Alignment.CenterStart) {
        Row(
            Modifier
                .background(colors.fill, RasulShapes.filter)
                .clickable(onClickLabel = stringResource(R.string.bg_open_cd), role = Role.Button, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .semantics(mergeDescendants = true) {}
                .testTag("chat_tasks_pill"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (prefix != null) {
                Box(Modifier.size(8.dp).background(colors.accent, CircleShape))
                Text(prefix, style = RasulType.sectionLabel, color = colors.ink, maxLines = 1)
            }
            Text(label, style = RasulType.sectionLabel, color = colors.accent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(RasulIcons.Chev, contentDescription = null, tint = colors.accent, modifier = Modifier.size(14.dp))
        }
    }
}

/** The Background tasks sheet. [onStop] is the run's own stop and is offered only while the run is live. */
@Composable
internal fun TasksSheet(
    items: List<BgItem>,
    runLive: Boolean,
    stopEnabled: Boolean,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
    clock: () -> Long = System::currentTimeMillis,
) {
    var now by remember { mutableLongStateOf(clock()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = clock()
        }
    }
    RasulSheet(title = stringResource(R.string.bg_title), onDismiss = onDismiss) {
        TasksSheetBody(items, runLive, stopEnabled, onStop, now)
    }
}

/**
 * The sheet's content under the title; the goldens draw it without the window the real sheet lives in. The rows scroll
 * when they do not fit; the Stop button is outside the scrolled part, so it is always in view.
 */
@Composable
internal fun TasksSheetBody(items: List<BgItem>, runLive: Boolean, stopEnabled: Boolean, onStop: () -> Unit, nowMs: Long) {
    val colors = Rasul.colors
    Column(Modifier.fillMaxWidth().testTag("chat_tasks_sheet")) {
        if (items.isEmpty()) {
            Text(
                stringResource(R.string.bg_empty),
                style = RasulType.body,
                color = colors.muted,
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp).testTag("chat_tasks_empty"),
            )
        } else {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 6.dp).testTag("chat_tasks_rows")) {
                items.forEachIndexed { index, item ->
                    if (index > 0) Box(Modifier.fillMaxWidth().padding(start = 52.dp).height(1.dp).background(colors.hairline))
                    TaskRow(item, nowMs)
                }
            }
        }
        if (runLive) {
            Box(
                Modifier.fillMaxWidth().padding(top = 14.dp).heightIn(min = 48.dp)
                    .border(1.dp, colors.dangerSoft, RasulShapes.button)
                    .clickable(enabled = stopEnabled, role = Role.Button, onClick = onStop)
                    .padding(horizontal = 28.dp, vertical = 14.dp)
                    .testTag("chat_tasks_stop"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.chat_stop),
                    style = RasulType.button,
                    color = if (stopEnabled) colors.danger else colors.danger.copy(alpha = 0.4f),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun TaskRow(item: BgItem, nowMs: Long) {
    val colors = Rasul.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("chat_task_row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).background(colors.fill, CircleShape), contentAlignment = Alignment.Center) {
            Icon(
                if (item.kind == BgKind.Process) RasulIcons.Terminal else RasulIcons.Helper,
                contentDescription = stringResource(if (item.kind == BgKind.Process) R.string.bg_kind_process else R.string.bg_kind_helper),
                tint = colors.ink,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            // A command or a goal reads in its own direction (a Latin command inside Arabic text must not be reordered).
            Text(
                item.name,
                style = RasulType.rowTitle.copy(textDirection = TextDirection.Content),
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
                textAlign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) TextAlign.Right else TextAlign.Left,
            )
            Text(startedAgo(item.startedAt, nowMs), style = RasulType.meta, color = colors.muted)
        }
        StatusChip(item.status)
    }
}

@Composable
private fun StatusChip(status: BgStatus) {
    val colors = Rasul.colors
    val (fill: Color, ink: Color, text) = when (status) {
        BgStatus.Running -> Triple(colors.accentSoft, colors.accent, stringResource(R.string.bg_status_running))
        BgStatus.Finished -> Triple(colors.successSoft, colors.success, stringResource(R.string.bg_status_finished))
        BgStatus.Failed -> Triple(colors.dangerSoft, colors.danger, stringResource(R.string.bg_status_failed))
    }
    Text(
        text,
        style = RasulType.chip,
        color = ink,
        maxLines = 1,
        modifier = Modifier.background(fill, RasulShapes.chip).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

@Composable
private fun startedAgo(startedAt: Long?, nowMs: Long): String {
    if (startedAt == null) return stringResource(R.string.bg_started_unknown)
    val minutes = ((nowMs - startedAt).coerceAtLeast(0) / 60_000).toInt()
    // The count is a number in a sentence, so it takes the phone's digits, the way the relative times in the chat list do.
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    fun shown(count: Int) = NumberFormat.getIntegerInstance(locale).format(count)
    return when {
        minutes < 1 -> stringResource(R.string.bg_started_now)
        minutes < 60 -> pluralStringResource(R.plurals.bg_started_minutes, minutes, shown(minutes))
        else -> (minutes / 60).let { pluralStringResource(R.plurals.bg_started_hours, it, shown(it)) }
    }
}
