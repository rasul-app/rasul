package app.rasul.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.rasul.R
import app.rasul.bridge.JobRunDto
import app.rasul.data.timeText
import app.rasul.ui.components.ChipKind
import app.rasul.ui.components.RasulRow
import app.rasul.ui.components.RowSub
import app.rasul.ui.components.RowTitle
import app.rasul.ui.components.StatusChip
import app.rasul.ui.theme.Rasul
import app.rasul.ui.theme.RasulTheme
import app.rasul.ui.theme.RasulType
import kotlinx.serialization.json.JsonPrimitive

/** One job's past and running runs, read over Rasul Bridge. */
@Composable
fun RunHistoryScreen(vm: RunHistoryViewModel, jobName: String, onBack: () -> Unit) {
    val state by vm.state.collectAsState()
    RunHistoryContent(state, jobName, onBack, vm::refresh)
}

@Composable
internal fun RunHistoryContent(
    state: RunHistoryState,
    jobName: String,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    val c = Rasul.colors
    Column(Modifier.fillMaxSize().background(c.bg)) {
        DesktopHeader(R.string.run_history_title, jobName, onBack, onRetry.takeIf { !state.direct && !state.removed })
        when {
            state.direct -> DesktopMessage(stringResource(R.string.run_history_needs_bridge))
            state.removed -> DesktopMessage(stringResource(R.string.run_history_pc_removed))
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = c.accent)
                    Text(stringResource(R.string.run_history_loading), style = RasulType.body, color = c.muted)
                }
            }
            state.error != null -> DesktopMessage(stringResource(state.error.message), onRetry)
            state.runs.isEmpty() -> DesktopMessage(stringResource(R.string.run_history_empty), onRetry)
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(state.runs, key = { it.id }) { run ->
                    RunRow(run, last = run.id == state.runs.last().id)
                }
            }
        }
    }
}

@Composable
private fun RunRow(run: JobRunDto, last: Boolean, nowSeconds: Long = System.currentTimeMillis() / 1000) {
    val c = Rasul.colors
    RasulRow(
        last = last, hairlineStart = 20.dp, hairlineEnd = 20.dp, textSpacing = 4.dp,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
        trailing = {
            if (run.isActive) StatusChip(stringResource(R.string.run_history_running), ChipKind.Running)
        },
    ) {
        RowTitle(run.title?.takeIf { it.isNotBlank() } ?: run.id)
        run.preview?.takeIf { it.isNotBlank() }?.let { RowSub(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            timeText(run.startedAt)?.let { Text(it, style = RasulType.meta, color = c.muted, maxLines = 1) }
            runDuration(run, nowSeconds)?.let { (res, a, b) ->
                Text("· " + stringResource(res, a, b), style = RasulType.meta, color = c.accent, maxLines = 1)
            }
        }
    }
}

private val previewRuns = listOf(
    JobRunDto("r1", "Daily log summary", JsonPrimitive(1_900_000_000), JsonPrimitive(1_900_000_252), false, "Summarized 14 notes; no errors."),
    JobRunDto("r2", "Daily log summary", JsonPrimitive(1_899_990_000), null, true, "Reading notes…"),
    JobRunDto("r3", null, JsonPrimitive(1_899_980_000), JsonPrimitive(1_899_980_045), false, null),
)

@Composable
private fun RunHistoryPreviewBody() = RunHistoryContent(
    RunHistoryState(runs = previewRuns, loaded = true), "Daily log summary", {}, {},
)

@Preview(showBackground = true, widthDp = 390, heightDp = 700, name = "RunHistory")
@Composable
private fun RunHistoryEn() = RasulTheme(false) { RunHistoryPreviewBody() }

@Preview(showBackground = true, widthDp = 390, heightDp = 700, locale = "ar", name = "RunHistory")
@Composable
private fun RunHistoryAr() = RasulTheme(false) { RunHistoryPreviewBody() }
