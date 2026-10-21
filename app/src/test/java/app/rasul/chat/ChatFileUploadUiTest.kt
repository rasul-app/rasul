package app.rasul.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.rasul.data.HermesError
import app.rasul.data.HermesResult
import app.rasul.data.PendingAttachment
import app.rasul.data.SIZE_UNKNOWN
import app.rasul.data.SentAttachment
import app.rasul.data.UploadFailure
import app.rasul.data.UploadState
import app.rasul.ui.theme.RasulTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h700dp")
class ChatFileUploadUiTest {
    @get:Rule val compose = createComposeRule()

    private fun show(
        state: ChatState,
        filesViaBridge: Boolean = true,
        onPickAnyFile: () -> Unit = {},
        onPickFile: () -> Unit = {},
        onRetry: (Long) -> Unit = {},
        onRemove: (Long) -> Unit = {},
        onSend: () -> Unit = {},
    ) = compose.setContent {
        RasulTheme(false) { Screen(state, filesViaBridge, onPickAnyFile, onPickFile, onRetry, onRemove, onSend) }
    }

    @Composable
    private fun Screen(
        state: ChatState, filesViaBridge: Boolean, onPickAnyFile: () -> Unit, onPickFile: () -> Unit,
        onRetry: (Long) -> Unit, onRemove: (Long) -> Unit, onSend: () -> Unit,
    ) = ChatScreenContent(
        state = state,
        title = "Chat",
        onBack = {},
        onDraft = {},
        onSend = onSend,
        onStop = {},
        onReconnect = {},
        onRefresh = {},
        onAnswerApproval = { _, _ -> },
        onRetryHistory = {},
        onPickFile = onPickFile,
        onPickAnyFile = onPickAnyFile,
        onRetryUpload = onRetry,
        filesViaBridge = filesViaBridge,
        onRemoveAttachment = onRemove,
        modelOptionsLoader = { HermesResult.Err(HermesError.NotFound) },
        selectedModel = null,
        tokenUsage = null,
        onSelectModel = {},
    )

    private fun ready(vararg files: PendingAttachment.UploadFile, messages: List<ChatMessage> = emptyList()) =
        ChatState(load = ChatLoad.Ready, sessionId = "s", attachments = files.toList(), messages = messages)

    private fun file(key: Long, name: String, state: UploadState, size: Long = 2_000_000) =
        PendingAttachment.UploadFile(key, name, size, state)

    @Test fun bridgeChatOffersFileAndRunsThePicker() {
        var picked = 0
        show(ready(), filesViaBridge = true, onPickAnyFile = { picked++ })
        compose.onNodeWithTag("chat_attach_button").performClick()
        compose.onNodeWithText("Text file").assertDoesNotExist()
        compose.onNodeWithTag("chat_attach_any_file").assertIsEnabled().performClick()
        assertEquals(1, picked)
    }

    @Test fun directChatShowsFileDisabledWithTheReasonAndKeepsTheTextFileOption() {
        var picked = 0
        var textPicked = 0
        show(ready(), filesViaBridge = false, onPickAnyFile = { picked++ }, onPickFile = { textPicked++ })
        compose.onNodeWithTag("chat_attach_button").performClick()
        compose.onNodeWithTag("chat_attach_any_file").assertIsNotEnabled().performClick()
        compose.onNodeWithText("Sending files needs Rasul on your PC").assertExists()
        assertEquals(0, picked)
        compose.onNodeWithText("Text file").assertIsEnabled().performClick()
        assertEquals(1, textPicked)
    }

    @Test fun uploadingChipShowsProgressAndSpokenState() {
        show(ready(file(1, "report.pdf", UploadState.Uploading(800_000))))
        compose.onNodeWithTag("chat_upload_progress", useUnmergedTree = true).assertExists()
        compose.onNode(hasContentDescription("report.pdf", substring = true) and hasContentDescription("uploading 40%", substring = true)).assertExists()
        compose.onNodeWithTag("chat_uploading_note").assertExists()
    }

    @Test fun sendIsOffWhileUploadingAndSaysWhy() {
        var sent = 0
        show(ready(file(1, "report.pdf", UploadState.Uploading(10))), onSend = { sent++ })
        compose.onNodeWithContentDescription("Uploading…").assertIsNotEnabled().performClick()
        assertEquals(0, sent)
    }

    @Test fun readyChipShowsACheckAndEnablesSend() {
        var sent = 0
        show(ready(file(1, "report.pdf", UploadState.Ready("f"))), onSend = { sent++ })
        compose.onNodeWithTag("chat_upload_done", useUnmergedTree = true).assertExists()
        compose.onNode(hasContentDescription("ready", substring = true) and hasContentDescription("report.pdf", substring = true)).assertExists()
        assertTrue(compose.onAllNodesWithTag("chat_upload_progress", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithContentDescription("Send").assertIsEnabled().performClick()
        assertEquals(1, sent)
    }

    @Test fun failedChipOffersRetryAndKeepsSendOff() {
        val retried = mutableListOf<Long>()
        show(ready(file(7, "report.pdf", UploadState.Failed(UploadFailure.Rejected(null)))), onRetry = { retried += it })
        compose.onNodeWithText("Upload failed. Try again").assertExists()
        compose.onNode(hasContentDescription("failed", substring = true) and hasContentDescription("report.pdf", substring = true)).assertExists()
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Try again: report.pdf").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf(7L), retried)
    }

    @Test fun removeButtonIsNamedAndBigEnough() {
        val removed = mutableListOf<Long>()
        show(ready(file(3, "report.pdf", UploadState.Ready("f"))), onRemove = { removed += it })
        compose.onNodeWithContentDescription("Remove attachment report.pdf").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf(3L), removed)
    }

    @Test fun sentFileShowsAsACardWithNameAndSize() {
        val message = ChatMessage(1, MessageKind.User, "", attachments = listOf(SentAttachment.SentFile("report.pdf", 2_000_000)))
        show(ready(messages = listOf(message)))
        compose.onNodeWithTag("chat_sent_file").assertExists()
        compose.onNodeWithText("report.pdf").assertExists()
    }

    private fun sentCard(size: Long) {
        val message = ChatMessage(1, MessageKind.User, "", attachments = listOf(SentAttachment.SentFile("history.pdf", size)))
        show(ready(messages = listOf(message)))
    }

    @Test fun sentFileFromHistoryShowsOnlyItsName() {
        sentCard(SIZE_UNKNOWN)
        compose.onNodeWithTag("chat_sent_file").assertContentDescriptionEquals("history.pdf")
        compose.onNodeWithText("history.pdf").assertExists()
        compose.onNodeWithText("0 B", substring = true).assertDoesNotExist()
        compose.onNodeWithText("-1", substring = true).assertDoesNotExist()
    }

    @Test fun sentFileWithASizeSpeaksNameAndSize() {
        sentCard(2_000_000)
        compose.onNodeWithTag("chat_sent_file").assertContentDescriptionContains("history.pdf", substring = true)
        compose.onNodeWithTag("chat_sent_file").assertContentDescriptionContains("MB", substring = true)
    }

    @Config(qualifiers = "ar-w390dp-h700dp")
    @Test fun sentFileFromHistoryShowsOnlyItsNameInArabic() {
        sentCard(SIZE_UNKNOWN)
        compose.onNodeWithTag("chat_sent_file").assertContentDescriptionEquals("history.pdf")
        compose.onNodeWithText("history.pdf").assertExists()
        compose.onNodeWithText("-1", substring = true).assertDoesNotExist()
    }

    @Config(qualifiers = "ar-w390dp-h700dp")
    @Test fun sentFileWithASizeSpeaksTheArabicSentenceInArabic() {
        sentCard(2_000_000)
        compose.onNodeWithTag("chat_sent_file").assertContentDescriptionContains("الملف history.pdf", substring = true)
    }

    @Test fun errorTextsNameTheFile() {
        val messages = listOf(
            ChatMessage(1, MessageKind.Error, "", error = ChatFailure.UploadTooLarge("huge.iso")),
            ChatMessage(2, MessageKind.Error, "", error = ChatFailure.BridgeNeedsUpdate),
            ChatMessage(3, MessageKind.Error, "", error = ChatFailure.FilesGone(listOf("a.pdf"))),
        )
        show(ready(messages = messages))
        compose.onNodeWithText("huge.iso is larger than 50 MB").assertExists()
        compose.onNodeWithText("Update Rasul on your PC to send files").assertExists()
        compose.onNodeWithText("a.pdf is no longer on your PC. Attach it again").assertExists()
    }

    @Test fun middleEllipsisKeepsTheStartAndTheExtension() {
        assertEquals("short.pdf", middleEllipsize("short.pdf"))
        val long = middleEllipsize("quarterly-financial-report-final-v2.pdf")
        assertEquals(18, long.length)
        assertTrue(long.startsWith("quarterly"))
        assertTrue(long.endsWith("-v2.pdf"))
        assertTrue(long.contains("…"))
    }

    // The emoji is two UTF-16 units; an 18-unit name keeps 9 at the start and 8 at the end. The first cut falls inside
    // the first emoji when it starts at unit 8, the second cut inside the last emoji for every start.
    @Test fun middleEllipsisNeverSplitsAnEmoji() {
        val face = "🙂"
        for (start in 5..12) {
            val name = "a".repeat(start) + face + "b".repeat(30) + face + "c".repeat(3) + ".pdf"
            val shown = middleEllipsize(name)
            assertTrue(shown, shown.hasWholePairs())
            assertTrue(shown, shown.length <= 18)
        }
        assertEquals("x".repeat(9) + "…" + "abcdefg", middleEllipsize("x".repeat(30) + face + "abcdefg"))
        assertEquals("12345678" + "…" + "x".repeat(8), middleEllipsize("12345678" + face + "x".repeat(30)))
    }

    private fun String.hasWholePairs(): Boolean {
        var i = 0
        while (i < length) {
            val c = this[i]
            if (c.isHighSurrogate()) {
                if (i + 1 >= length || !this[i + 1].isLowSurrogate()) return false
                i += 2
            } else if (c.isLowSurrogate()) {
                return false
            } else {
                i++
            }
        }
        return true
    }

    @Test fun percentIsClampedAndSafeForZeroSize() {
        assertEquals(0, uploadPercent(5, 0))
        assertEquals(40, uploadPercent(800, 2000))
        assertEquals(100, uploadPercent(9999, 2000))
    }
}
