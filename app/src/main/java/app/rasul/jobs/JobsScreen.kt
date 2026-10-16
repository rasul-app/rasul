package app.rasul.jobs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import app.rasul.ui.components.ChipKind
import app.rasul.ui.components.ConfirmDialog
import app.rasul.ui.components.GlassText
import app.rasul.ui.components.NoticeDialog
import app.rasul.ui.components.RasulIcons
import app.rasul.ui.components.RasulRow
import app.rasul.ui.components.RasulToggle
import app.rasul.ui.components.RowSub
import app.rasul.ui.components.RowTitle
import app.rasul.ui.components.ScreenHeader
import app.rasul.ui.components.SmallOutlineButton
import app.rasul.ui.components.StatusChip
import app.rasul.ui.text
import app.rasul.ui.theme.Rasul
import app.rasul.ui.theme.RasulTheme
import app.rasul.ui.theme.RasulType
import kotlinx.serialization.json.JsonPrimitive

private data class Pending(val source: ActiveTarget.Ready, val job: JobDto)

/** Jobs list and resume confirmation dialog. */
@Composable
fun JobsScreen(vm: JobsViewModel) {
    val state by vm.state.collectAsState()
    val busy by vm.busy.collectAsState()
    val actionError by vm.actionError.collectAsState()
    val picker by vm.picker.collectAsState()
    // Dialogs hold the source they were opened from and reset when the active PC changes.
    var confirmRun by remember(picker.target) { mutableStateOf<Pending?>(null) }
    var confirmResume by remember(picker.target) { mutableStateOf<Pending?>(null) }
    val source = (state as? Load.Ready)?.source

    Column(Modifier.fillMaxSize().background(Rasul.colors.bg)) {
        ScreenHeader(stringResource(R.string.tab_jobs)) { GlassText(stringResource(R.string.refresh), vm::refresh) }
        PcSwitcherHost(picker, vm::selectPc, vm::clearWriteError)
        LoadView(state, onRetry = vm::retry) { list ->
            JobList(
                list, busy = busy != null,
                // Pause is safe: one tap. Resume can run an overdue job at once: confirm.
                onToggle = { j -> source?.let { s -> if (j.isPaused) confirmResume = Pending(s, j) else vm.togglePause(s, j) } },
                onRunNow = { j -> source?.let { confirmRun = Pending(it, j) } },
            )
        }
    }

    confirmResume?.let { p ->
        ConfirmDialog(
            title = stringResource(R.string.job_resume_confirm_title),
            body = stringResource(R.string.job_resume_confirm_body, p.job.name ?: p.job.id),
            cancelText = stringResource(R.string.cancel),
            confirmText = stringResource(R.string.job_resume),
            onCancel = { confirmResume = null },
            onConfirm = { vm.togglePause(p.source, p.job); confirmResume = null },
        )
    }
    confirmRun?.let { p ->
        ConfirmDialog(
            title = stringResource(R.string.job_run_confirm_title),
            body = stringResource(R.string.job_run_confirm_body, p.job.name ?: p.job.id),
            cancelText = stringResource(R.string.cancel),
            confirmText = stringResource(R.string.job_run_now),
            onCancel = { confirmRun = null },
            onConfirm = { vm.runNow(p.source, p.job); confirmRun = null },
        )
    }
    actionError?.let { e -> NoticeDialog(e.text(), stringResource(R.string.ok), vm::clearActionError) }
}

@Composable
internal fun JobList(list: List<JobDto>, busy: Boolean, onToggle: (JobDto) -> Unit, onRunNow: (JobDto) -> Unit) {
    val c = Rasul.colors
    if (list.isEmpty()) {
        Text(stringResource(R.string.jobs_empty), style = RasulType.body, color = c.muted, modifier = Modifier.padding(24.dp))
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(list, key = { it.id }) { j ->
            val (badgeBg, badgeIcon, badgeTint) = when (j.jobState) {
                JobState.Scheduled -> Triple(c.accentSoft, RasulIcons.Clock, c.accent)
                JobState.Paused -> Triple(c.fill, RasulIcons.Pause, c.muted)
                JobState.Completed -> Triple(c.successSoft, RasulIcons.Check, c.success)
                JobState.Error -> Triple(c.dangerSoft, RasulIcons.X, c.danger)
            }
            RasulRow(
                hairlineStart = 74.dp,
                leading = { IconBadge(badgeBg, badgeIcon, badgeTint) },
                trailing = {
                    when (j.jobState) {
                        JobState.Scheduled -> RasulToggle(
                            true, stringResource(R.string.job_pause_named, j.name?.takeIf { it.isNotBlank() } ?: j.id),
                        ) { if (!busy) onToggle(j) }
                        JobState.Paused -> SmallOutlineButton(stringResource(R.string.job_resume), { onToggle(j) }, enabled = !busy)
                        JobState.Completed -> StatusChip(stringResource(R.string.job_completed), ChipKind.Done)
                        JobState.Error -> StatusChip(stringResource(R.string.job_failed), ChipKind.Failed)
                    }
                },
            ) {
                RowTitle(j.name?.takeIf { it.isNotBlank() } ?: j.id, if (j.jobState == JobState.Paused) c.muted else c.ink)
                j.scheduleDisplay?.let { RowSub(stringResource(R.string.job_schedule, it)) }
                when (j.jobState) {
                    JobState.Scheduled -> RowSub(
                        stringResource(R.string.job_next, timeText(j.nextRunAt) ?: stringResource(R.string.job_none)), c.accent,
                    )
                    JobState.Paused -> RowSub(stringResource(R.string.job_paused), c.warnText)
                    JobState.Completed -> RowSub(stringResource(R.string.job_completed), c.successText)
                    JobState.Error -> RowSub(stringResource(R.string.job_failed), c.danger)
                }
                // Keep the run action visible for every nonterminal job.
                if (!j.jobState.isTerminal) {
                    Box(Modifier.padding(top = 4.dp)) { SmallOutlineButton(stringResource(R.string.job_run_now), { onRunNow(j) }, enabled = !busy) }
                }
            }
        }
    }
}

@Composable
private fun IconBadge(bg: Color, icon: ImageVector, tint: Color) {
    Box(Modifier.size(40.dp).background(bg, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

private val sampleJobs = listOf(
    JobDto("1", "Daily log summary", "Every day at 03:00", enabled = true, nextRunAt = JsonPrimitive(1_900_000_000)),
    JobDto("2", "Backup check", "Every 6 hours", enabled = false),
    JobDto("3", "Migration dry run", "One time", state = "completed"),
    JobDto("4", "Clean old builds", "Every day at 02:00", state = "error"),
)

private val samplePcs = listOf(Connection("a", "Home PC", "http://100.64.0.7:8642", "default"))

@Composable
private fun JobsPreviewBody(dialog: Boolean = false) = RasulTheme(false) {
    Column(Modifier.fillMaxSize().background(Rasul.colors.bg)) {
        ScreenHeader(stringResource(R.string.tab_jobs)) { GlassText(stringResource(R.string.refresh), {}) }
        PcSwitcherHost(PcPicker(samplePcs, "a"), {})
        JobList(sampleJobs, busy = false, onToggle = {}, onRunNow = {})
    }
    if (dialog) {
        ConfirmDialog(
            stringResource(R.string.job_resume_confirm_title), stringResource(R.string.job_resume_confirm_body, "Backup check"),
            stringResource(R.string.cancel), stringResource(R.string.job_resume), {}, {},
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 700)
@Composable
private fun JobsEn() = JobsPreviewBody()

@Preview(showBackground = true, widthDp = 390, heightDp = 700, locale = "ar")
@Composable
private fun JobsAr() = JobsPreviewBody()

@Preview(showBackground = true, widthDp = 390, heightDp = 700)
@Composable
private fun ResumeDialogEn() = JobsPreviewBody(dialog = true)

@Preview(showBackground = true, widthDp = 390, heightDp = 700, locale = "ar")
@Composable
private fun ResumeDialogAr() = JobsPreviewBody(dialog = true)
