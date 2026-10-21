package app.rasul.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.rasul.R
import app.rasul.ui.components.PrimaryButton
import app.rasul.ui.components.RasulIcons
import app.rasul.ui.theme.Rasul
import app.rasul.ui.theme.RasulMonoFontFamily
import app.rasul.ui.theme.RasulTheme
import app.rasul.ui.theme.RasulType
import kotlinx.coroutines.delay
import java.util.Locale

private val CardShape = RoundedCornerShape(18.dp)
private val ButtonShape = RoundedCornerShape(18.dp)
private val ListMaxHeight = 240.dp

/** A preview is a command, a path or a query: it reads in the direction of its first letter, whatever the app language. */
private fun lineDirection(text: String): TextDirection {
    val first = text.firstOrNull { Character.isLetter(it) } ?: return TextDirection.Ltr
    return when (Character.getDirectionality(first)) {
        Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> TextDirection.Rtl
        else -> TextDirection.Ltr
    }
}

/**
 * One line of preview text. A command or path (first letter not Arabic) lays out left-to-right even in the Arabic UI,
 * like [CodeText], so `./gradlew test` is not reordered; an Arabic preview keeps the surrounding direction.
 */
@Composable
private fun PreviewLine(text: String, color: Color, modifier: Modifier = Modifier) {
    val direction = lineDirection(text)
    val line = @Composable {
        Text(
            text,
            modifier = modifier,
            style = RasulType.bodySmall.copy(textDirection = direction, textAlign = TextAlign.Start),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (direction == TextDirection.Ltr) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { line() }
    } else {
        line()
    }
}

/** "1:24" */
internal fun clockText(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

@Composable
internal fun toolLabel(id: String): String = toolLabelRes(id)?.let { stringResource(it) } ?: id

/** The time source of the card: a 1 s tick that lives and dies with the card, so only the card recomposes. */
@Composable
private fun rememberNow(clock: () -> Long): Long {
    var now by remember { mutableLongStateOf(clock()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = clock()
        }
    }
    return now
}

/**
 * The live card of a running task: what it is doing now, how long it ran, how many steps are done.
 * Built only from [ActiveRun.progress]. After the first reply text it shrinks to one line.
 * A mirrored PC run passes [onStop] = null (nothing to stop on the phone) and [statusLine] =
 * the backend's own status text, which then stands in for the phase-derived headline.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RunProgressCard(
    run: ActiveRun,
    onStop: (() -> Unit)?,
    onReconnect: () -> Unit,
    onRefresh: () -> Unit,
    onReview: (() -> Unit)?,
    statusLine: String? = null,
    clock: () -> Long = System::currentTimeMillis,
) {
    val colors = Rasul.colors
    val now = rememberNow(clock)
    val progress = run.progress
    var showSteps by rememberSaveable(run.runId) { mutableStateOf(false) }
    val phase = progress.phase
    val lost = run.status == StatusLabel.Lost
    val unavailable = run.status == StatusLabel.LiveUpdatesUnavailable
    val collapsed = phase == RunPhase.Writing && !run.stopRequested && !lost && !unavailable
    val waiting = phase == RunPhase.WaitingApproval && !run.stopRequested && !lost
    val headline = statusLine ?: headlineText(run)
    val headlineColor = when {
        waiting -> colors.warn
        lost -> colors.muted
        else -> colors.accent
    }
    val elapsed = if (progress.restored) null else clockText(now - run.startedAtMs)
    val steps = progress.stepCount.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.progress_steps, it, it) }
    val canList = progress.steps.isNotEmpty()
    val watch = run.status != StatusLabel.Lost && run.status != StatusLabel.LiveUpdatesUnavailable
    val notes = progress.notes(now, watchStall = watch)

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = CardShape,
        color = colors.bg,
        contentColor = colors.ink,
        border = BorderStroke(1.dp, colors.cardBorder),
        shadowElevation = 4.dp,
    ) {
        Column(
            Modifier.padding(if (collapsed) 10.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(if (collapsed) 4.dp else 8.dp),
        ) {
            if (lost) ConnectionLostBanner(onReconnect)
            if (collapsed) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FlowRow(
                        Modifier.weight(1f).heightIn(min = 48.dp).testTag("run_progress_collapsed")
                            .then(
                                if (canList) Modifier.clickable(role = Role.Button) { showSteps = !showSteps } else Modifier,
                            ),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            headline,
                            modifier = Modifier.testTag("run_progress_headline").semantics { liveRegion = LiveRegionMode.Polite },
                            style = RasulType.rowTitle,
                            color = headlineColor,
                        )
                        // The row is one button: its ticking timer must not be read with the headline; the step count stays readable.
                        if (elapsed != null) {
                            Text(
                                "· $elapsed",
                                modifier = Modifier.testTag("run_progress_meta").then(if (canList) Modifier.clearAndSetSemantics {} else Modifier),
                                style = RasulType.meta,
                                color = colors.muted,
                            )
                        }
                        if (steps != null) {
                            Text(
                                "· $steps",
                                modifier = Modifier.testTag("run_progress_steps_meta"),
                                style = RasulType.meta,
                                color = colors.muted,
                            )
                        }
                    }
                    if (onStop != null) StopButton(run, onStop)
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().then(
                        if (waiting && onReview != null) Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onReview) else Modifier,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier.size(28.dp).background(
                            when {
                                waiting -> colors.warn.copy(alpha = 0.12f)
                                lost -> colors.fill
                                else -> colors.accentSoft
                            },
                            RoundedCornerShape(14.dp),
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (waiting) RasulIcons.Pause else RasulIcons.Clock,
                            contentDescription = null,
                            tint = headlineColor,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    // Only the headline is a live region: a phase change is announced, the timer's ticks are not.
                    Text(
                        headline,
                        modifier = Modifier.weight(1f).testTag("run_progress_headline").semantics { liveRegion = LiveRegionMode.Polite },
                        style = RasulType.rowTitle,
                        color = headlineColor,
                    )
                    if (elapsed != null) {
                        CodeText(
                            elapsed,
                            style = RasulType.meta.copy(fontFamily = RasulMonoFontFamily, fontWeight = FontWeight.Medium),
                            color = colors.muted,
                            modifier = Modifier.testTag("run_progress_timer")
                                .then(if (waiting && onReview != null) Modifier.clearAndSetSemantics {} else Modifier),
                        )
                    }
                }
                val current = (phase as? RunPhase.Tool)?.takeIf { !run.stopRequested && !lost }
                current?.preview?.let {
                    PreviewLine(it, colors.muted, Modifier.testTag("run_progress_preview"))
                }
                progress.lastDone?.takeIf { progress.stepCount > 0 }?.let {
                    Text(
                        stringResource(R.string.progress_steps_done, progress.stepCount, toolLabel(it.tool)),
                        modifier = Modifier.testTag("run_progress_steps_done"),
                        style = RasulType.bodySmall,
                        color = colors.muted,
                    )
                }
            }
            if (progress.steerNoticeAt(now)) {
                Text(stringResource(R.string.chat_steered), style = RasulType.bodySmall, color = colors.accent)
            }
            notes.forEach { note ->
                Text(
                    when (note) {
                        is RunNote.LongTool -> stringResource(R.string.progress_note_long_tool, note.minutes)
                        is RunNote.Stalled -> stringResource(R.string.progress_note_stalled, note.minutes)
                    },
                    modifier = Modifier.testTag(if (note is RunNote.Stalled) "run_progress_note_stalled" else "run_progress_note_long"),
                    style = RasulType.bodySmall,
                    color = colors.warnText,
                )
            }
            if (canList && !collapsed) {
                StepsToggle(showSteps) { showSteps = !showSteps }
            }
            if (canList && showSteps) StepList(progress.steps, now)
            if (!collapsed && (onStop != null || run.status == StatusLabel.LiveUpdatesUnavailable || onReview != null)) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onStop != null) StopButton(run, onStop)
                    if (run.status == StatusLabel.LiveUpdatesUnavailable) {
                        TextButton(onClick = onRefresh) {
                            Text(stringResource(R.string.chat_refresh), style = RasulType.buttonSmall)
                        }
                    }
                    if (onReview != null) {
                        PrimaryButton(
                            text = stringResource(R.string.approval_review),
                            onClick = onReview,
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun headlineText(run: ActiveRun): String = when {
    run.stopRequested -> statusText(StatusLabel.Stopping)
    run.status == StatusLabel.Lost -> statusText(StatusLabel.Lost)
    run.status == StatusLabel.LiveUpdatesUnavailable -> statusText(run.status)
    else -> when (val phase = run.progress.phase) {
        RunPhase.Received -> stringResource(R.string.progress_received)
        RunPhase.Thinking -> stringResource(R.string.progress_thinking)
        is RunPhase.Tool -> stringResource(R.string.progress_running, toolLabel(phase.name))
        RunPhase.WaitingApproval -> stringResource(R.string.progress_waiting_approval)
        RunPhase.Writing -> stringResource(R.string.progress_writing)
        is RunPhase.Reconnecting -> stringResource(R.string.progress_reconnecting, phase.attempt, phase.max)
    }
}

@Composable
private fun StopButton(run: ActiveRun, onStop: () -> Unit) {
    val colors = Rasul.colors
    OutlinedButton(
        onClick = onStop,
        enabled = run.runId != null && !run.stopRequested,
        modifier = Modifier.heightIn(min = 48.dp).testTag("run_progress_stop"),
        shape = ButtonShape,
        border = BorderStroke(1.dp, colors.danger.copy(alpha = 0.35f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.danger),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).background(colors.danger, RoundedCornerShape(2.dp)))
            Text(stringResource(R.string.chat_stop), style = RasulType.buttonSmall)
        }
    }
}

@Composable
private fun StepsToggle(open: Boolean, onToggle: () -> Unit) {
    TextButton(
        onClick = onToggle,
        modifier = Modifier.heightIn(min = 48.dp).testTag("run_progress_steps_toggle"),
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp),
    ) {
        Text(
            stringResource(if (open) R.string.progress_hide_steps else R.string.progress_show_steps),
            style = RasulType.buttonSmall,
        )
    }
}

/** The kept tool calls, oldest first: tick or cross, label, the one-line preview and how long each took. */
@Composable
private fun StepList(steps: List<RunStep>, now: Long?) {
    val colors = Rasul.colors
    Column(
        Modifier.fillMaxWidth().heightIn(max = ListMaxHeight).verticalScroll(rememberScrollState()).testTag("run_progress_step_list"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        steps.forEach { step ->
            val icon: ImageVector
            val tint = when {
                step.failed -> colors.danger
                step.running -> colors.accent
                else -> colors.success
            }
            icon = when {
                step.failed -> RasulIcons.X
                step.running -> RasulIcons.Clock
                else -> RasulIcons.Check
            }
            val state = stringResource(
                when {
                    step.failed -> R.string.progress_failed
                    step.running -> R.string.progress_step_running
                    else -> R.string.progress_done
                },
            )
            Row(
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(icon, contentDescription = state, tint = tint, modifier = Modifier.size(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(toolLabel(step.tool), style = RasulType.sectionLabel, color = colors.ink)
                    step.preview?.let {
                        PreviewLine(it, colors.muted, Modifier.testTag("run_progress_step_preview"))
                    }
                }
                val ms = step.durationMs ?: if (step.running && now != null) now - step.startedAt else null
                if (ms != null) {
                    CodeText(
                        clockText(ms),
                        style = RasulType.meta.copy(fontFamily = RasulMonoFontFamily),
                        color = colors.muted,
                    )
                }
            }
        }
    }
}

/** The compact line left under the reply once a run ended: "Done in 1:24 · 5 steps"; a tap opens the step list. */
@Composable
internal fun RunSummaryLine(summary: RunSummary) {
    val colors = Rasul.colors
    var open by rememberSaveable(summary.runId) { mutableStateOf(false) }
    val time = summary.elapsedMs?.let { clockText(it) }
    val text = when (summary.outcome) {
        RunOutcome.Done -> time?.let { stringResource(R.string.progress_done_in, it) } ?: stringResource(R.string.progress_done)
        RunOutcome.Stopped -> time?.let { stringResource(R.string.progress_stopped_after, it) } ?: stringResource(R.string.progress_stopped)
        RunOutcome.Failed -> time?.let { stringResource(R.string.progress_failed_after, it) } ?: stringResource(R.string.progress_failed)
    }
    val line = listOfNotNull(
        text,
        summary.stepCount.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.progress_steps, it, it) },
    ).joinToString(" · ")
    val tint = when (summary.outcome) {
        RunOutcome.Done -> colors.success
        RunOutcome.Stopped -> colors.muted
        RunOutcome.Failed -> colors.danger
    }
    val icon = if (summary.outcome == RunOutcome.Done) RasulIcons.Check else RasulIcons.X
    val canOpen = summary.steps.isNotEmpty()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("run_summary_line")
                .then(if (canOpen) Modifier.clickable(role = Role.Button) { open = !open } else Modifier),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Text(line, modifier = Modifier.weight(1f), style = RasulType.bodySmall, color = colors.muted)
        }
        if (open) {
            Box(Modifier.padding(bottom = 8.dp)) { StepList(summary.steps, now = null) }
        }
    }
}

private const val PREVIEW_NOW = 1_000_000_000L

private fun previewSteps(count: Int): List<RunStep> {
    val tools = listOf("web_search" to "gradle release notes", "read_file" to "app/build.gradle.kts", "patch" to null, "terminal" to "./gradlew assembleDebug", "web_extract" to null)
    return tools.take(count).mapIndexed { i, (tool, preview) ->
        val start = PREVIEW_NOW - 80_000L + i * 12_000L
        RunStep(tool, preview, start, start + 5_000L, durationMs = 5_000L + i * 1_000L, failed = i == 3)
    }
}

private enum class ProgressPreviewKind { Received, Tool, Approval, Stalled, Writing, Done }

@Composable
private fun ProgressPreviewBody(kind: ProgressPreviewKind) = RasulTheme(dark = isSystemInDarkTheme()) {
    val colors = Rasul.colors
    val started = PREVIEW_NOW - 72_000L
    Box(Modifier.fillMaxWidth().background(colors.bg).padding(vertical = 8.dp)) {
        when (kind) {
            ProgressPreviewKind.Done -> RunSummaryLine(RunSummary("p", RunOutcome.Done, 84_000L, 5, previewSteps(5)))
            else -> {
                val run = when (kind) {
                    ProgressPreviewKind.Received -> ActiveRun("p", PREVIEW_NOW - 2_000L, StatusLabel.Starting)
                    ProgressPreviewKind.Tool -> ActiveRun(
                        "p", started, StatusLabel.Tool("terminal"),
                        progress = RunProgress(
                            started, RunPhase.Tool("terminal", "./gradlew testDebugUnitTest --tests app.rasul.chat.*"),
                            previewSteps(3) + RunStep("terminal", "./gradlew testDebugUnitTest --tests app.rasul.chat.*", PREVIEW_NOW - 20_000L),
                            stepCount = 3, lastEventAt = PREVIEW_NOW - 20_000L,
                        ),
                    )
                    ProgressPreviewKind.Approval -> ActiveRun(
                        "p", started, StatusLabel.WaitingApproval,
                        progress = RunProgress(started, RunPhase.WaitingApproval, previewSteps(2), 2, PREVIEW_NOW - 9_000L),
                    )
                    ProgressPreviewKind.Stalled -> ActiveRun(
                        "p", PREVIEW_NOW - 300_000L, StatusLabel.Thinking,
                        progress = RunProgress(PREVIEW_NOW - 300_000L, RunPhase.Thinking, previewSteps(2), 2, PREVIEW_NOW - 200_000L),
                    )
                    else -> ActiveRun(
                        "p", started, StatusLabel.Writing,
                        progress = RunProgress(started, RunPhase.Writing, previewSteps(5), 5, PREVIEW_NOW - 1_000L),
                    )
                }
                RunProgressCard(run, onStop = {}, onReconnect = {}, onRefresh = {}, onReview = null, clock = { PREVIEW_NOW })
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 260, name = "Progress received EN")
@Composable
private fun ProgressReceivedEn() = ProgressPreviewBody(ProgressPreviewKind.Received)

@Preview(showBackground = true, widthDp = 390, heightDp = 260, locale = "ar", name = "Progress received AR")
@Composable
private fun ProgressReceivedAr() = ProgressPreviewBody(ProgressPreviewKind.Received)

@Preview(showBackground = true, widthDp = 390, heightDp = 260, name = "Progress tool EN")
@Composable
private fun ProgressToolEn() = ProgressPreviewBody(ProgressPreviewKind.Tool)

@Preview(showBackground = true, widthDp = 390, heightDp = 260, locale = "ar", name = "Progress tool AR")
@Composable
private fun ProgressToolAr() = ProgressPreviewBody(ProgressPreviewKind.Tool)

@Preview(showBackground = true, widthDp = 390, heightDp = 260, name = "Progress approval EN")
@Composable
private fun ProgressApprovalEn() = ProgressPreviewBody(ProgressPreviewKind.Approval)

@Preview(showBackground = true, widthDp = 390, heightDp = 260, locale = "ar", name = "Progress approval AR")
@Composable
private fun ProgressApprovalAr() = ProgressPreviewBody(ProgressPreviewKind.Approval)

@Preview(showBackground = true, widthDp = 390, heightDp = 260, name = "Progress stalled EN")
@Composable
private fun ProgressStalledEn() = ProgressPreviewBody(ProgressPreviewKind.Stalled)

@Preview(showBackground = true, widthDp = 390, heightDp = 260, locale = "ar", name = "Progress stalled AR")
@Composable
private fun ProgressStalledAr() = ProgressPreviewBody(ProgressPreviewKind.Stalled)

@Preview(showBackground = true, widthDp = 390, heightDp = 120, name = "Progress writing EN")
@Composable
private fun ProgressWritingEn() = ProgressPreviewBody(ProgressPreviewKind.Writing)

@Preview(showBackground = true, widthDp = 390, heightDp = 120, locale = "ar", name = "Progress writing AR")
@Composable
private fun ProgressWritingAr() = ProgressPreviewBody(ProgressPreviewKind.Writing)

@Preview(showBackground = true, widthDp = 390, heightDp = 80, name = "Progress done EN")
@Composable
private fun ProgressDoneEn() = ProgressPreviewBody(ProgressPreviewKind.Done)

@Preview(showBackground = true, widthDp = 390, heightDp = 80, locale = "ar", name = "Progress done AR")
@Composable
private fun ProgressDoneAr() = ProgressPreviewBody(ProgressPreviewKind.Done)
