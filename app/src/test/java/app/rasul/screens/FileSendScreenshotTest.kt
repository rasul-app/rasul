package app.rasul.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.LayoutDirection
import app.rasul.chat.ChatFailure
import app.rasul.chat.ChatLoad
import app.rasul.chat.ChatMessage
import app.rasul.chat.ChatScreenContent
import app.rasul.chat.ChatState
import app.rasul.chat.MessageKind
import app.rasul.data.HermesError
import app.rasul.data.HermesResult
import app.rasul.data.PendingAttachment
import app.rasul.data.SentAttachment
import app.rasul.data.UploadFailure
import app.rasul.data.UploadState
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
import java.util.TimeZone

/**
 * Chat goldens for sending files through the bridge, numbered from 182: chips uploading (182), ready (183) and failed (184),
 * the old-bridge error (185) and sent file cards (186). The attach sheet (190, 191) is in ContextSheetsScreenshotTest.
 */
@OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class FileSendScreenshotTest(private val language: String, private val dark: Boolean, private val kind: String) {
    @get:Rule val compose = createComposeRule()

    @Test fun capture() {
        val saved = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            render()
        } finally {
            TimeZone.setDefault(saved)
        }
    }

    private fun render() {
        RuntimeEnvironment.setQualifiers("+$language-w390dp-h844dp${if (dark) "-night" else "-notnight"}")
        val ar = language == "ar"
        val report = if (ar) "تقرير الربع الأول المفصل والنهائي.pdf" else "quarterly-financial-report-final-v2.pdf"
        val archive = if (ar) "صور الرحلة.zip" else "trip-photos.zip"
        val notes = if (ar) "ملاحظات.docx" else "meeting-notes.docx"
        fun file(key: Long, name: String, size: Long, state: UploadState) = PendingAttachment.UploadFile(key, name, size, state)
        val base = ChatState(load = ChatLoad.Ready, sessionId = "sample-session")
        val state = when (kind) {
            "uploading" -> base.copy(attachments = listOf(file(1, report, 4_200_000, UploadState.Uploading(1_680_000))))
            "ready" -> base.copy(
                draft = if (ar) "لخّص لي هذه الملفات من فضلك" else "Please summarize these files",
                attachments = listOf(
                    file(1, report, 4_200_000, UploadState.Ready("a".repeat(32))),
                    file(2, archive, 12_500_000, UploadState.Ready("b".repeat(32))),
                ),
            )
            "failed" -> base.copy(attachments = listOf(
                file(1, report, 4_200_000, UploadState.Failed(UploadFailure.Rejected(null))),
                file(2, archive, 12_500_000, UploadState.Ready("a".repeat(32))),
            ))
            "oldbridge" -> base.copy(
                messages = listOf(ChatMessage(1, MessageKind.Error, "", error = ChatFailure.BridgeNeedsUpdate)),
                attachments = listOf(file(1, report, 4_200_000, UploadState.Failed(UploadFailure.OldBridge))),
            )
            "sent" -> base.copy(messages = listOf(
                ChatMessage(
                    1, MessageKind.User, if (ar) "هذه هي الملفات" else "Here are the files",
                    attachments = listOf(SentAttachment.SentFile(report, 4_200_000), SentAttachment.SentFile(notes, 38_000)),
                    atMillis = 1_792_097_160_000L,
                ),
            ))
            else -> base
        }
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides if (ar) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                RasulTheme(dark) {
                    Box(Modifier.fillMaxSize()) {
                    ChatScreenContent(
                        state = state,
                        title = if (ar) "مساعدي" else "My assistant",
                        onBack = {},
                        onDraft = {},
                        onSend = {},
                        onStop = {},
                        onReconnect = {},
                        onRefresh = {},
                        onAnswerApproval = { _, _ -> },
                        onRetryHistory = {},
                        filesViaBridge = true,
                        modelOptionsLoader = { HermesResult.Err(HermesError.NotFound) },
                        selectedModel = null,
                        tokenUsage = null,
                        onSelectModel = {},
                    )
                  }
                }
            }
        }
        compose.waitForIdle()
        val number = mapOf("uploading" to 182, "ready" to 183, "failed" to 184, "oldbridge" to 185, "sent" to 186)
        captureScreenRoboImage(
            "src/test/screenshots/${number.getValue(kind)}-sendfile${kind}-$language${if (dark) "-dark" else ""}.png",
        )
    }

    companion object {
        @JvmStatic @Parameters(name = "{0}, dark={1}, {2}")
        fun cases(): List<Array<Any>> = listOf("en", "ar").flatMap { language ->
            listOf(false, true).flatMap { dark ->
                listOf("uploading", "ready", "failed", "oldbridge", "sent").map { arrayOf(language, dark, it) }
            }
        }
    }
}
