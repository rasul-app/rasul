package app.rasul.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.rasul.R
import app.rasul.bridge.JobRunDto
import app.rasul.ui.components.RasulIcons
import app.rasul.ui.theme.Rasul
import app.rasul.ui.theme.RasulType

/** Back + title + target name header, matching the Skills & tools screen. */
@Composable
internal fun DesktopHeader(title: Int, subtitle: String, onBack: () -> Unit, onRefresh: (() -> Unit)? = null, refreshLabel: Int = R.string.refresh) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(RasulIcons.Back, contentDescription = stringResource(R.string.back), tint = Rasul.colors.accent)
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = RasulType.rowTitle, color = Rasul.colors.ink)
            Text(subtitle, style = RasulType.meta, color = Rasul.colors.muted, maxLines = 1)
        }
        if (onRefresh != null) TextButton(onClick = onRefresh) { Text(stringResource(refreshLabel)) }
    }
}

/** Centered message with an optional 48dp retry action. */
@Composable
internal fun DesktopMessage(text: String, onRetry: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(
            Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text, style = RasulType.body, color = Rasul.colors.ink, textAlign = TextAlign.Center)
            if (onRetry != null) {
                TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.retry))
                }
            }
        }
    }
}

/** Duration between started_at and ended_at (or now while running) as (string res, arg1, arg2). */
internal fun runDuration(run: JobRunDto, nowSeconds: Long): Triple<Int, Long, Long>? {
    val started = run.startedEpoch() ?: return null
    val ended = run.endedEpoch() ?: if (run.isActive) nowSeconds else return null
    val secs = (ended - started).coerceAtLeast(0)
    return when {
        secs >= 3600 -> Triple(R.string.run_duration_hm, secs / 3600, secs % 3600 / 60)
        secs >= 60 -> Triple(R.string.run_duration_ms, secs / 60, secs % 60)
        else -> Triple(R.string.run_duration_s, secs, 0L)
    }
}
