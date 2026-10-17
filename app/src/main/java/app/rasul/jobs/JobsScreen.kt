package app.rasul.jobs

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.rasul.R
import app.rasul.data.ActiveTarget
import app.rasul.data.HermesError
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
import app.rasul.ui.PrimaryScreenActions
import app.rasul.ui.text
import app.rasul.ui.theme.Rasul
import app.rasul.ui.theme.RasulShapes
import app.rasul.ui.theme.RasulTheme
import app.rasul.ui.theme.RasulType
import kotlinx.serialization.json.JsonPrimitive

private data class Pending(val source: ActiveTarget.Ready, val job: JobDto)
private data class PendingJob(val source: ActiveTarget.Ready, val job: JobDto?)

/** Jobs list and resume confirmation dialog. */
@Composable
fun JobsScreen(vm: JobsViewModel, onPairPc: () -> Unit = {}, onSearch: () -> Unit = {}) {
    val state by vm.state.collectAsState()
    val busy by vm.busy.collectAsState()
    val actionError by vm.actionError.collectAsState()
    val formError by vm.formError.collectAsState()
    val deleteError by vm.deleteError.collectAsState()
    val picker by vm.picker.collectAsState()
    // Dialogs hold the source they were opened from and reset when the active PC changes.
    var confirmRun by remember(picker.target) { mutableStateOf<Pending?>(null) }
    var confirmResume by remember(picker.target) { mutableStateOf<Pending?>(null) }
    var form by remember(picker.target) { mutableStateOf<PendingJob?>(null) }
    var confirmDelete by remember(picker.target) { mutableStateOf<Pending?>(null) }
    val source = (state as? Load.Ready)?.source

    Column(Modifier.fillMaxSize().background(Rasul.colors.bg)) {
        ScreenHeader(stringResource(R.string.tab_jobs)) {
            PrimaryScreenActions(onSearch)
            if (source != null) {
                GlassText(stringResource(R.string.ux3c_job_create)) {
                    vm.clearFormError()
                    form = PendingJob(source, null)
                }
            }
            GlassText(stringResource(R.string.refresh), vm::refresh)
        }
        PcSwitcherHost(picker, vm::selectPc, vm::clearWriteError)
        LoadView(
            state, onRetry = vm::retry,
            onPairPc = onPairPc.takeIf { picker.pcs.isEmpty() && picker.bridgeGroups.isEmpty() },
        ) { list ->
            JobList(
                list, busy = busy != null,
                // Pause is safe: one tap. Resume can run an overdue job at once: confirm.
                onToggle = { j -> source?.let { s -> if (j.isPaused) confirmResume = Pending(s, j) else vm.togglePause(s, j) } },
                onRunNow = { j -> source?.let { confirmRun = Pending(it, j) } },
                onEdit = { j -> source?.let { vm.clearFormError(); form = PendingJob(it, j) } },
                onDelete = { j -> source?.let { confirmDelete = Pending(it, j) } },
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
    form?.let { pending ->
        JobFormDialog(
            job = pending.job,
            saving = busy != null,
            serverError = formError?.jobValidationMessage(),
            onDismiss = { if (busy == null) { form = null; vm.clearFormError() } },
            onSave = { name, schedule, prompt ->
                vm.save(pending.source, pending.job, name, schedule, prompt) {
                    form = null
                    vm.clearFormError()
                }
            },
        )
    }
    confirmDelete?.let { p ->
        JobDeleteConfirmDialog(
            name = p.job.name?.takeIf { it.isNotBlank() } ?: p.job.id,
            onCancel = { confirmDelete = null },
            onConfirm = {
                vm.delete(p.source, p.job)
                confirmDelete = null
            },
        )
    }
    actionError?.let { e -> NoticeDialog(e.text(), stringResource(R.string.ok), vm::clearActionError) }
    deleteError?.let { e -> NoticeDialog(e.jobMessage(), stringResource(R.string.ok), vm::clearDeleteError) }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun JobList(
    list: List<JobDto>,
    busy: Boolean,
    onToggle: (JobDto) -> Unit,
    onRunNow: (JobDto) -> Unit,
    onEdit: (JobDto) -> Unit = {},
    onDelete: (JobDto) -> Unit = {},
) {
    val c = Rasul.colors
    if (list.isEmpty()) {
        Text(stringResource(R.string.jobs_empty), style = RasulType.body, color = c.muted, modifier = Modifier.padding(24.dp))
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(list, key = { it.id }) { j ->
            val jobName = j.name?.takeIf { it.isNotBlank() } ?: j.id
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
                            true, stringResource(R.string.job_pause_named, jobName), enabled = !busy,
                        ) { if (!busy) onToggle(j) }
                        JobState.Paused -> RasulToggle(
                            false, stringResource(R.string.job_resume_named, jobName), enabled = !busy,
                        ) { if (!busy) onToggle(j) }
                        JobState.Completed -> StatusChip(stringResource(R.string.job_completed), ChipKind.Done)
                        JobState.Error -> StatusChip(stringResource(R.string.job_failed), ChipKind.Failed)
                    }
                },
                bottom = {
                    FlowRow(
                        Modifier.fillMaxWidth().testTag("job_actions_line"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SmallOutlineButton(stringResource(R.string.ux3c_job_edit), { onEdit(j) }, enabled = !busy)
                        SmallOutlineButton(stringResource(R.string.ux3c_job_delete), { onDelete(j) }, enabled = !busy)
                        if (!j.jobState.isTerminal) {
                            SmallOutlineButton(stringResource(R.string.job_run_now), { onRunNow(j) }, enabled = !busy)
                        }
                    }
                },
            ) {
                RowTitle(jobName, if (j.jobState == JobState.Paused) c.muted else c.ink)
                j.scheduleDisplay?.let { RowSub(stringResource(R.string.job_schedule, it)) }
                when (j.jobState) {
                    JobState.Scheduled -> RowSub(
                        stringResource(R.string.job_next, timeText(j.nextRunAt) ?: stringResource(R.string.job_none)), c.accent,
                    )
                    JobState.Paused -> RowSub(stringResource(R.string.job_paused), c.warnText)
                    JobState.Completed -> RowSub(stringResource(R.string.job_completed), c.successText)
                    JobState.Error -> RowSub(stringResource(R.string.job_failed), c.danger)
                }
            }
        }
    }
}

@Composable
internal fun JobFormDialog(
    job: JobDto?,
    saving: Boolean,
    serverError: String?,
    onDismiss: () -> Unit,
    onSave: (name: String, schedule: String, prompt: String) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        app.rasul.ui.components.DialogScrim()
        JobFormContent(job, saving, serverError, onDismiss, onSave)
    }
}

@Composable
internal fun JobFormContent(
    job: JobDto?,
    saving: Boolean,
    serverError: String?,
    onDismiss: () -> Unit,
    onSave: (name: String, schedule: String, prompt: String) -> Unit,
) {
    val c = Rasul.colors
    var name by remember(job?.id) { mutableStateOf(job?.name.orEmpty()) }
    var schedule by remember(job?.id) { mutableStateOf(job?.scheduleInput.orEmpty()) }
    var prompt by remember(job?.id) { mutableStateOf(job?.prompt.orEmpty()) }
    var attempted by remember(job?.id) { mutableStateOf(false) }
    val nameMissing = attempted && name.isBlank()
    val scheduleMissing = attempted && schedule.isBlank()
    val promptMissing = attempted && prompt.isBlank() && job == null
    Column(
        Modifier.fillMaxWidth().heightIn(max = 560.dp).background(c.bg, RasulShapes.dialog)
            .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
            Text(
                stringResource(if (job == null) R.string.ux3c_job_create_title else R.string.ux3c_job_edit_title),
                style = RasulType.dialogTitle,
                color = c.ink,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                enabled = !saving,
                label = { Text(stringResource(R.string.ux3c_job_name)) },
                isError = nameMissing,
                supportingText = if (nameMissing) { { Text(stringResource(R.string.ux3c_job_name_required)) } } else null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = schedule,
                onValueChange = { schedule = it },
                enabled = !saving,
                label = { Text(stringResource(R.string.ux3c_job_schedule)) },
                textStyle = RasulType.body.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr),
                isError = scheduleMissing,
                supportingText = if (scheduleMissing) { { Text(stringResource(R.string.ux3c_job_schedule_required)) } } else null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                enabled = !saving,
                label = { Text(stringResource(R.string.ux3c_job_prompt)) },
                minLines = 2,
                maxLines = 4,
                isError = promptMissing,
                supportingText = if (promptMissing) { { Text(stringResource(R.string.ux3c_job_validation_prompt_required)) } } else null,
                modifier = Modifier.fillMaxWidth(),
            )
            serverError?.takeIf { it.isNotBlank() }?.let { Text(it, style = RasulType.meta, color = c.danger) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                SmallOutlineButton(stringResource(R.string.cancel), onDismiss, enabled = !saving, width = if (androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "ar") 58.dp else 74.dp)
                SmallOutlineButton(
                    stringResource(R.string.save),
                    {
                        attempted = true
                        val capturedName = name.trim()
                        val capturedSchedule = schedule.trim()
                        val capturedPrompt = prompt
                        if (capturedName.isNotEmpty() && capturedSchedule.isNotEmpty() && (capturedPrompt.isNotBlank() || job != null)) {
                            onSave(capturedName, capturedSchedule, capturedPrompt)
                        }
                    },
                    enabled = !saving, width = if (androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "ar") 58.dp else 60.dp,
                )
            }
    }
}

@Composable
internal fun JobDeleteConfirmDialog(name: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.ux3c_job_delete_title),
        body = stringResource(R.string.ux3c_job_delete_body, name),
        cancelText = stringResource(R.string.cancel),
        confirmText = stringResource(R.string.ux3c_job_delete),
        onCancel = onCancel,
        onConfirm = onConfirm,
        destructive = true,
        contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 18.dp),
        spacing = 6.dp, actionPadding = 16.dp, confirmTextStyle = RasulType.buttonSmall,
        decoration = { app.rasul.ui.components.DialogScrim() },
    )
}

internal fun jobValidationText(error: HermesError.Server, context: Context): String {
    val message = error.message.orEmpty()
    val resource = when (message) {
        "Name is required" -> R.string.ux3c_job_validation_name_required
        "Name must be ≤ 200 characters" -> R.string.ux3c_job_validation_name_too_long
        "Schedule is required" -> R.string.ux3c_job_validation_schedule_required
        "Cron job has nothing to run: the prompt is blank and no script or skill(s) are set. Provide a prompt, a script, or at least one skill." -> R.string.ux3c_job_validation_prompt_required
        "Prompt must be ≤ 5000 characters" -> R.string.ux3c_job_validation_prompt_too_long
        "Repeat must be a positive integer" -> R.string.ux3c_job_validation_repeat_positive
        "No valid fields to update" -> R.string.ux3c_job_validation_no_fields
        else -> jobScheduleValidationResource(message)
    }
    return resource?.let(context::getString)
        ?: context.getString(R.string.ux3c_job_validation_fallback, error.message.orEmpty())
}

private fun jobScheduleValidationResource(message: String): Int? = when {
    message.startsWith("Invalid schedule '") && message.contains("'. Use:\n") &&
        message.endsWith("(one-shot at time)") -> R.string.ux3c_job_validation_schedule_invalid
    message.startsWith("Invalid duration: '") && message.contains("'. Use format like ") &&
        message.endsWith("(defaults to 1).") -> R.string.ux3c_job_validation_schedule_duration_invalid
    message.startsWith("Invalid duration '") && message.contains("' after 'in '. Use e.g. ") &&
        message.endsWith("'in 2h'.") -> R.string.ux3c_job_validation_schedule_delay_invalid
    message.startsWith("Invalid timestamp '") && message.contains("': ") ->
        R.string.ux3c_job_validation_schedule_timestamp_invalid
    message.startsWith("Invalid schedule '") && message.contains("': ") ->
        R.string.ux3c_job_validation_weekday_schedule_invalid
    message.startsWith("Invalid cron expression '") && message.contains("': ") ->
        R.string.ux3c_job_validation_cron_invalid
    message.startsWith("Weekday/time schedules like '") &&
        message.contains("' require the 'croniter' package. Install with: pip install croniter") ->
        R.string.ux3c_job_validation_weekday_schedule_unavailable
    message == "Cron expressions require 'croniter' package. Install with: pip install croniter" ->
        R.string.ux3c_job_validation_cron_unavailable
    message.startsWith("Requested one-shot time ") && message.contains(" is more than ") &&
        message.endsWith(" in the past and cannot be scheduled.") -> R.string.ux3c_job_validation_oneshot_past
    else -> null
}

@Composable
private fun HermesError.jobValidationMessage(): String = when (this) {
    is HermesError.Server -> jobValidationText(this, androidx.compose.ui.platform.LocalContext.current)
    else -> text()
}

@Composable
private fun HermesError.jobMessage(): String =
    (this as? HermesError.Server)?.message?.takeIf { it.isNotBlank() } ?: text()

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

private val samplePcs = listOf(Connection("a", "My PC", "http://192.0.2.7:8642", "default"))

@Composable
private fun JobsPreviewBody(dialog: Boolean = false) = RasulTheme(false) {
    Column(Modifier.fillMaxSize().background(Rasul.colors.bg)) {
        ScreenHeader(stringResource(R.string.tab_jobs)) {
            GlassText(stringResource(R.string.ux3c_job_create), {})
            GlassText(stringResource(R.string.refresh), {})
        }
        PcSwitcherHost(PcPicker(samplePcs, "a"), {})
        JobList(sampleJobs, busy = false, onToggle = {}, onRunNow = {}, onEdit = {}, onDelete = {})
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

@Preview(showBackground = true, widthDp = 390, heightDp = 800, locale = "ar")
@Composable
private fun JobsAr() = JobsPreviewBody()

@Preview(showBackground = true, widthDp = 390, heightDp = 700)
@Composable
private fun ResumeDialogEn() = JobsPreviewBody(dialog = true)

@Preview(showBackground = true, widthDp = 390, heightDp = 800, locale = "ar")
@Composable
private fun ResumeDialogAr() = JobsPreviewBody(dialog = true)

@Composable
private fun JobFormPreviewBody() = RasulTheme(false) {
    JobFormDialog(
        job = JobDto(
            id = "preview-job",
            name = "Morning summary",
            scheduleDisplay = "0 9 * * *",
            schedule = JsonPrimitive("0 9 * * *"),
            prompt = "Summarize recent activity.",
        ),
        saving = false,
        serverError = null,
        onDismiss = {},
        onSave = { _, _, _ -> },
    )
}

@Preview(showBackground = true, widthDp = 390, heightDp = 700)
@Composable
private fun JobFormEn() = JobFormPreviewBody()

@Preview(showBackground = true, widthDp = 390, heightDp = 700, locale = "ar")
@Composable
private fun JobFormAr() = JobFormPreviewBody()

@Preview(showBackground = true, widthDp = 390, heightDp = 700)
@Composable
private fun JobDeleteEn() = RasulTheme(false) {
    JobDeleteConfirmDialog("Morning summary", {}, {})
}

@Preview(showBackground = true, widthDp = 390, heightDp = 700, locale = "ar")
@Composable
private fun JobDeleteAr() = RasulTheme(false) {
    JobDeleteConfirmDialog("Morning summary", {}, {})
}
