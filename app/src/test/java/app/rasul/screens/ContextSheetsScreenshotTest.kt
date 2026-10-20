package app.rasul.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.rasul.R
import app.rasul.chat.ActiveRun
import app.rasul.chat.AttachCloseButton
import app.rasul.chat.AttachSheetBody
import app.rasul.chat.BackgroundTasks
import app.rasul.chat.BgItem
import app.rasul.chat.BgKind
import app.rasul.chat.BgStatus
import app.rasul.chat.ChatLoad
import app.rasul.chat.ChatMessage
import app.rasul.chat.ChatScreenContent
import app.rasul.chat.ChatState
import app.rasul.chat.MessageKind
import app.rasul.chat.StatusLabel
import app.rasul.chat.TasksSheetBody
import app.rasul.data.BridgeProcess
import app.rasul.data.HermesError
import app.rasul.data.HermesResult
import app.rasul.ui.components.RasulSheetBody
import app.rasul.ui.theme.Rasul
import app.rasul.ui.theme.RasulShapes
import app.rasul.ui.theme.RasulTheme
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Goldens for the attach sheet and the background tasks line and sheet, numbered from 190: the attach sheet on a bridge
 * chat (190) and a direct one (191), the pill while the run works (192) and after it (193), and the tasks sheet with the
 * run live (194), ended (195), empty (196) and fourteen rows at font scale 1.3 with Stop in view (197). The sheets are drawn in place over the chat (the real ones are windows).
 */
@OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ContextSheetsScreenshotTest(private val language: String, private val dark: Boolean, private val kind: String) {
    @get:Rule val compose = createComposeRule()

    private val now = 1_792_100_000_000L

    @Test fun capture() {
        compose.mainClock.autoAdvance = false
        RuntimeEnvironment.setQualifiers("+$language-w390dp-h844dp${if (dark) "-night" else "-notnight"}")
        if (kind == "tasksmany") RuntimeEnvironment.setFontScale(1.3f)
        val ar = language == "ar"
        /** A background start the way Hermes reports it (ids are "proc_" and 12 hex characters). */
        fun started(tasks: BackgroundTasks, id: String, command: String, minutes: Long) =
            tasks.toolStarted("terminal", command, now - minutes * 60_000)
                .toolCompleted(
                    "terminal", false,
                    """{"output": "Background process started", "session_id": "$id", "pid": 4242, "exit_code": 0, "error": null}""",
                    now - minutes * 60_000,
                )

        fun process(id: String, command: String, minutes: Long) = started(BackgroundTasks(), id, command, minutes)

        /** The agent waited on [id] and saw it exit with [code]: the row is Finished, or Failed for a non-zero code. */
        fun exited(tasks: BackgroundTasks, id: String, code: Int) =
            tasks.toolStarted("process_manage", "wait ${id.take(16)}", now)
                .toolCompleted(
                    "process_manage", false,
                    """{"status": "exited", "command": "x", "exit_code": $code, "completion_reason": "exit", "termination_source": null, "output": ""}""",
                    now,
                )
        val build = if (ar) "npm run build" else "npm run build"
        val live = process("proc_a1b2c3d4e5f6", build, 3)
            .let { it.copy(items = it.items + BgItem("h1", BgKind.Helper, if (ar) "مساعد البحث" else "Research helper", now - 2 * 60_000, BgStatus.Running)) }
            .let {
                it.copy(items = it.items + BgItem(
                    "h2", BgKind.Helper,
                    if (ar) "لخّص سجلات النشر لليلة الماضية وأبلغ عن أي شيء غير معتاد بالتفصيل" else "Summarize the deploy logs for last night and flag anything unusual in detail",
                    now - 9 * 60_000, BgStatus.Finished,
                ))
            }
            .let { started(it, "proc_0123456789ab", "pytest -q tests/", 21) }
            .let { exited(it, "proc_0123456789ab", 0) }
        // The ended state comes through the same events: one still running, one that exited with code 1, one that exited cleanly.
        val ended = process("proc_a1b2c3d4e5f6", build, 3)
            .let { started(it, "proc_b2c3d4e5f6a1", "docker compose -f deploy/compose.yml up --build --force-recreate --remove-orphans api worker", 14) }
            .let { exited(it, "proc_b2c3d4e5f6a1", 1) }
            .let { started(it, "proc_0123456789ab", "pytest -q tests/", 21) }
            .let { exited(it, "proc_0123456789ab", 0) }
        val many = BackgroundTasks(
            (1..14).map {
                val helper = it % 4 == 0
                BgItem(
                    "t$it", if (helper) BgKind.Helper else BgKind.Process,
                    if (helper) "Research helper $it" else "npm run build -- --target $it",
                    now - it * 4 * 60_000L,
                    when { it % 5 == 0 -> BgStatus.Failed; it % 3 == 0 -> BgStatus.Finished; else -> BgStatus.Running },
                    if (helper) null else "proc_$it",
                )
            },
        )
        val chat = listOf(
            ChatMessage(1, MessageKind.User, if (ar) "لخّص سجلات النشر لليلة الماضية" else "Summarize last night's deploy logs and flag anything odd."),
            ChatMessage(2, MessageKind.Assistant, if (ar) "حاضر، سأقرأ السجلات على جهازك وأعود إليك." else "On it. I'll read the logs on your PC and report back."),
        )
        val base = ChatState(load = ChatLoad.Ready, sessionId = "sample-session", messages = chat)
        val twoRemote = listOf(
            BridgeProcess("proc_a1b2c3d4e5f6", build, now - 3 * 60_000, "sample-session"),
            BridgeProcess("proc_c3", "tail -f app.log", now - 40 * 60_000, "sample-session"),
        )
        val running = ActiveRun("run-1", System.currentTimeMillis(), StatusLabel.Thinking)
        val twoRunning = BackgroundTasks(
            listOf(
                BgItem("proc_a1b2c3d4e5f6", BgKind.Process, build, now - 3 * 60_000, BgStatus.Running, "proc_a1b2c3d4e5f6"),
                BgItem("h1", BgKind.Helper, "Research helper", now - 2 * 60_000, BgStatus.Running),
            ),
        )
        val state = when (kind) {
            "pillworking" -> base.copy(run = running, background = twoRunning)
            "pillstill" -> base.copy(remoteProcesses = twoRemote, background = ended)
            else -> base
        }
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides if (ar) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                RasulTheme(dark) {
                    Box(Modifier.fillMaxSize()) {
                        ChatScreenContent(
                            state = state,
                            title = if (ar) "بحث" else "Research",
                            onBack = {},
                            onDraft = {},
                            onSend = {},
                            onStop = {},
                            onReconnect = {},
                            onRefresh = {},
                            onAnswerApproval = { _, _ -> },
                            onRetryHistory = {},
                            filesViaBridge = kind != "attachdirect",
                            modelOptionsLoader = { HermesResult.Err(HermesError.NotFound) },
                            selectedModel = null,
                            tokenUsage = null,
                            onSelectModel = {},
                        )
                        when (kind) {
                            "attach", "attachdirect" -> SheetOver(androidx.compose.ui.res.stringResource(R.string.chat_attach_sheet_title), { AttachCloseButton {} }) {
                                AttachSheetBody(kind == "attach", {}, {}, {}, {})
                            }
                            "taskslive" -> SheetOver(androidx.compose.ui.res.stringResource(R.string.bg_title), null) {
                                TasksSheetBody(live.items, runLive = true, stopEnabled = true, onStop = {}, nowMs = now)
                            }
                            "tasksended" -> SheetOver(androidx.compose.ui.res.stringResource(R.string.bg_title), null) {
                                TasksSheetBody(ended.items, runLive = false, stopEnabled = false, onStop = {}, nowMs = now)
                            }
                            "tasksempty" -> SheetOver(androidx.compose.ui.res.stringResource(R.string.bg_title), null) {
                                TasksSheetBody(emptyList(), runLive = false, stopEnabled = false, onStop = {}, nowMs = now)
                            }
                            "tasksmany" -> SheetOver(androidx.compose.ui.res.stringResource(R.string.bg_title), null) {
                                TasksSheetBody(many.items, runLive = true, stopEnabled = true, onStop = {}, nowMs = now)
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val number = mapOf("attach" to 190, "attachdirect" to 191, "pillworking" to 192, "pillstill" to 193, "taskslive" to 194, "tasksended" to 195, "tasksempty" to 196, "tasksmany" to 197)
        captureScreenRoboImage(
            "src/test/screenshots/${number.getValue(kind)}-context-$kind-$language${if (dark) "-dark" else ""}.png",
        )
    }

    /** A scrim over the whole screen and the sheet at the bottom, drawn like the real one (grabber, title, content). */
    @androidx.compose.runtime.Composable
    private fun androidx.compose.foundation.layout.BoxScope.SheetOver(
        title: String,
        titleAction: (@androidx.compose.runtime.Composable () -> Unit)?,
        content: @androidx.compose.runtime.Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
    ) {
        val colors = Rasul.colors
        Box(Modifier.fillMaxSize().background(colors.scrim))
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(colors.bg, RasulShapes.sheet)) {
            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.width(36.dp).height(5.dp).background(colors.hairline, RoundedCornerShape(3.dp)))
            }
            RasulSheetBody(title, titleAction, content)
        }
    }

    companion object {
        @JvmStatic @Parameters(name = "{0}, dark={1}, {2}")
        fun cases(): List<Array<Any>> = listOf("en", "ar").flatMap { language ->
            listOf(false, true).flatMap { dark ->
                listOf("attach", "attachdirect", "pillworking", "pillstill", "taskslive", "tasksended", "tasksempty", "tasksmany").map { arrayOf(language, dark, it) }
            }
        }
    }
}
