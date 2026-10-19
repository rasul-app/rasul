package app.rasul.desktop

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rasul.R
import app.rasul.bridge.DesktopError
import app.rasul.bridge.LogFile
import app.rasul.bridge.LogLevel
import app.rasul.ui.components.FilterPill
import app.rasul.ui.components.SegmentedSwitch
import app.rasul.ui.theme.Rasul
import app.rasul.ui.theme.RasulMonoFontFamily
import app.rasul.ui.theme.RasulTheme
import app.rasul.ui.theme.RasulType
import kotlinx.coroutines.delay

private const val COPIED_MS = 2_000L

/** Hermes log screen of one PC: three log files, a level filter, the newest lines at the bottom. */
@Composable
fun LogsScreen(vm: LogsViewModel, targetName: String, onBack: () -> Unit) {
    val state by vm.state.collectAsState()
    LogsContent(state, targetName, onBack, vm::refresh, vm::selectFile, vm::selectLevel)
}

@Composable
internal fun LogsContent(
    state: LogsState,
    targetName: String,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onFile: (LogFile) -> Unit,
    onLevel: (LogLevel) -> Unit,
) {
    val c = Rasul.colors
    Column(Modifier.fillMaxSize().background(c.bg)) {
        DesktopHeader(R.string.logs_title, targetName, onBack, onRetry.takeIf { !state.direct && !state.loading })
        if (state.direct) {
            DesktopMessage(stringResource(R.string.logs_needs_bridge))
            return@Column
        }
        Controls(state, onFile, onLevel)
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = c.accent)
                    Text(stringResource(R.string.logs_loading), style = RasulType.body, color = c.muted)
                }
            }
            state.error != null -> DesktopMessage(stringResource(state.error.message), onRetry)
            state.lines.isEmpty() -> DesktopMessage(stringResource(R.string.logs_empty), onRetry)
            else -> LogLines(state)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Controls(state: LogsState, onFile: (LogFile) -> Unit, onLevel: (LogLevel) -> Unit) {
    val files = listOf(
        LogFile.Errors to stringResource(R.string.logs_file_errors),
        LogFile.Agent to stringResource(R.string.logs_file_agent),
        LogFile.Gateway to stringResource(R.string.logs_file_gateway),
    )
    val levels = listOf(
        LogLevel.All to stringResource(R.string.logs_level_all),
        LogLevel.Error to stringResource(R.string.logs_level_error),
        LogLevel.Warning to stringResource(R.string.logs_level_warning),
        LogLevel.Info to stringResource(R.string.logs_level_info),
    )
    val fileLabel = stringResource(R.string.logs_file_label)
    val levelLabel = stringResource(R.string.logs_level_label)
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.fillMaxWidth().semantics { contentDescription = fileLabel }, contentAlignment = Alignment.Center) {
            SegmentedSwitch(files, state.file, onFile)
        }
        FlowRow(
            Modifier.fillMaxWidth().semantics { contentDescription = levelLabel },
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            levels.forEach { (level, label) -> FilterPill(label, level == state.level) { onLevel(level) } }
        }
    }
}

@Composable
private fun LogLines(state: LogsState) {
    val c = Rasul.colors
    val scroll = rememberScrollState()
    // The content is measured after this runs; wait one frame so the end is known.
    LaunchedEffect(state.reads) {
        withFrameNanos { }
        scroll.scrollTo(scroll.maxValue)
    }
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) { delay(COPIED_MS); copied = false }
    }
    val copiedText = stringResource(R.string.logs_copied)
    CopyRow(
        copyLabel = if (copied) copiedText else stringResource(R.string.logs_copy),
        copied = copied,
        onCopy = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("", state.lines.joinToString("\n")))
            copied = true
        },
    )
    val description = stringResource(R.string.logs_lines_desc)
    // The log text is code-like and always reads left to right, also on an Arabic screen.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        SelectionContainer {
            Column(
                Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 8.dp)
                    .semantics { contentDescription = description },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                state.lines.forEach { line ->
                    Text(
                        line,
                        style = RasulType.bodySmall.copy(
                            fontFamily = RasulMonoFontFamily, fontSize = 12.sp, lineHeight = 16.sp, textDirection = TextDirection.Ltr,
                        ),
                        color = when (logLineKind(line)) {
                            LogLineKind.Error -> c.dangerText
                            LogLineKind.Warning -> c.warnText
                            LogLineKind.Plain -> c.ink
                        },
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun CopyRow(copyLabel: String, copied: Boolean, onCopy: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp), contentAlignment = Alignment.CenterEnd) {
        TextButton(
            onClick = onCopy,
            modifier = Modifier.semantics { if (copied) liveRegion = LiveRegionMode.Polite },
        ) { Text(copyLabel) }
    }
}

private val previewLines = listOf(
    "2026-10-18 09:12:03,118 INFO gateway.run: Gateway started",
    "2026-10-18 09:12:04,402 WARNING gateway.platforms.telegram: Poll retry in 5s",
    "2026-10-18 09:14:51,977 ERROR agent.tools: Tool call failed: file not found (~/notes/todo.md)",
    "2026-10-18 09:15:02,310 INFO agent.run: Session resumed",
    "2026-10-18 09:20:17,005 ERROR gateway.platforms.telegram: Connection reset by peer",
)

@Composable
private fun LogsPreview(state: LogsState) = LogsContent(state, "My PC", {}, {}, {}, {})

@Preview(showBackground = true, widthDp = 390, heightDp = 780, name = "LogsLines")
@Composable
private fun LogsLinesEn() = RasulTheme(false) { LogsPreview(LogsState(lines = previewLines, loaded = true)) }

@Preview(showBackground = true, widthDp = 390, heightDp = 780, locale = "ar", name = "LogsLines")
@Composable
private fun LogsLinesAr() = RasulTheme(false) { LogsPreview(LogsState(lines = previewLines, loaded = true)) }

@Preview(showBackground = true, widthDp = 390, heightDp = 780, name = "LogsEmpty")
@Composable
private fun LogsEmptyEn() = RasulTheme(false) { LogsPreview(LogsState(loaded = true)) }

@Preview(showBackground = true, widthDp = 390, heightDp = 780, locale = "ar", name = "LogsEmpty")
@Composable
private fun LogsEmptyAr() = RasulTheme(false) { LogsPreview(LogsState(loaded = true)) }

@Preview(showBackground = true, widthDp = 390, heightDp = 780, name = "LogsError")
@Composable
private fun LogsErrorEn() = RasulTheme(false) { LogsPreview(LogsState(error = DesktopError.LogsNotFound)) }

@Preview(showBackground = true, widthDp = 390, heightDp = 780, locale = "ar", name = "LogsError")
@Composable
private fun LogsErrorAr() = RasulTheme(false) { LogsPreview(LogsState(error = DesktopError.LogsNotFound)) }
