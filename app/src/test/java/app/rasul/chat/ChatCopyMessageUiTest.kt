package app.rasul.chat

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import app.rasul.data.HermesError
import app.rasul.data.HermesResult
import app.rasul.files.FileDownloads
import app.rasul.files.LocalFileBridge
import app.rasul.files.LocalFileDownloads
import app.rasul.ui.theme.RasulTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], qualifiers = "w390dp-h700dp")
class ChatCopyMessageUiTest {
    @get:Rule val compose = createComposeRule()

    private val token = "k".repeat(43)

    private fun show(vararg messages: ChatMessage) {
        compose.setContent {
            RasulTheme(false) {
                ChatScreenContent(
                    state = ChatState(load = ChatLoad.Ready, sessionId = "s", messages = messages.toList()),
                    title = "Chat",
                    onBack = {},
                    onDraft = {},
                    onSend = {},
                    onStop = {},
                    onReconnect = {},
                    onRefresh = {},
                    onAnswerApproval = { _, _ -> },
                    onRetryHistory = {},
                    modelOptionsLoader = { HermesResult.Err(HermesError.NotFound) },
                    selectedModel = null,
                    tokenUsage = null,
                    onSelectModel = {},
                )
            }
        }
    }

    private fun clip(): String? {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return clipboard.primaryClip?.getItemAt(0)?.text?.toString()
    }

    private val hasCopyAction = SemanticsMatcher("has a Copy action") { node ->
        node.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == "Copy" } == true
    }

    @Test fun longPressOnAnAssistantBubbleCopiesItAndSaysCopied() {
        show(ChatMessage(1, MessageKind.Assistant, "The answer is 42."))
        compose.onNodeWithText("The answer is 42.").performTouchInput { longClick() }
        assertEquals("The answer is 42.", clip())
        assertEquals("Copied", ShadowToast.getTextOfLatestToast())
        assertEquals(1, ShadowToast.shownToastCount())
    }

    @Test fun longPressOnAUserBubbleCopiesIt() {
        show(ChatMessage(1, MessageKind.User, "Please fix the build"))
        compose.onNodeWithText("Please fix the build").performTouchInput { longClick() }
        assertEquals("Please fix the build", clip())
        assertEquals("Copied", ShadowToast.getTextOfLatestToast())
    }

    @Test fun theCopyAccessibilityActionCopiesWithoutALongPress() {
        show(ChatMessage(1, MessageKind.Assistant, "Read this aloud"))
        val node = compose.onNode(hasCopyAction)
        node.assertExists()
        val action = node.fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == "Copy" }
        compose.runOnUiThread { action.action() }
        assertEquals("Read this aloud", clip())
        assertEquals("Copied", ShadowToast.getTextOfLatestToast())
    }

    @Test @Config(sdk = [33]) fun onAndroid13TheSystemChipIsTheOnlyConfirmation() {
        show(ChatMessage(1, MessageKind.Assistant, "Only the system chip"))
        compose.onNodeWithText("Only the system chip").performTouchInput { longClick() }
        assertEquals("Only the system chip", clip())
        assertNull(ShadowToast.getLatestToast())
    }

    @Test fun aShortTapCopiesNothing() {
        show(ChatMessage(1, MessageKind.Assistant, "Just a tap"))
        compose.onNodeWithText("Just a tap").performClick()
        assertNull(clip())
        assertNull(ShadowToast.getLatestToast())
    }

    private fun showWithFiles(text: String, downloads: FileDownloads) {
        compose.setContent {
            RasulTheme(false) {
                CompositionLocalProvider(LocalFileBridge provides "bridge-1", LocalFileDownloads provides downloads) {
                    ChatScreenContent(
                        state = ChatState(
                            load = ChatLoad.Ready,
                            sessionId = "s",
                            messages = listOf(ChatMessage(1, MessageKind.Assistant, text)),
                        ),
                        title = "Chat",
                        onBack = {},
                        onDraft = {},
                        onSend = {},
                        onStop = {},
                        onReconnect = {},
                        onRefresh = {},
                        onAnswerApproval = { _, _ -> },
                        onRetryHistory = {},
                        modelOptionsLoader = { HermesResult.Err(HermesError.NotFound) },
                        selectedModel = null,
                        tokenUsage = null,
                        onSelectModel = {},
                    )
                }
            }
        }
    }

    @Test fun theDownloadButtonOfAFileLinkStillWorksOnTap() {
        val scope = CoroutineScope(Dispatchers.IO)
        var asked = 0
        val downloads = FileDownloads(File(System.getProperty("java.io.tmpdir"), "rasul-copy-ui"), scope, { asked++; null })
        showWithFiles("Here:\n[report.pdf (1.2 MB)](rasul-file://$token)", downloads)
        compose.onNodeWithText("Download").performClick()
        compose.waitUntil(5_000) { asked > 0 }
        assertNull(clip())
        scope.cancel()
    }

    @Test fun longPressOnAMessageWithAFileLinkCopiesNoDownloadToken() {
        val scope = CoroutineScope(Dispatchers.IO)
        val downloads = FileDownloads(File(System.getProperty("java.io.tmpdir"), "rasul-copy-ui"), scope, { null })
        showWithFiles("Here:\n[report.pdf (1.2 MB)](rasul-file://$token)", downloads)
        compose.onNodeWithText("Here:").performTouchInput { longClick() }
        val copied = clip()
        assertEquals("Here:\nreport.pdf (1.2 MB)", copied)
        assertFalse(copied!!.contains("rasul-file://"))
        scope.cancel()
    }

    @Test fun aTextCardTextMessageHasExactlyOneCopyAction() {
        val scope = CoroutineScope(Dispatchers.IO)
        val downloads = FileDownloads(File(System.getProperty("java.io.tmpdir"), "rasul-copy-ui"), scope, { null })
        showWithFiles("Before\n[report.pdf (1.2 MB)](rasul-file://$token)\nAfter", downloads)
        compose.onAllNodes(hasCopyAction).assertCountEquals(1)
        scope.cancel()
    }

    @Test fun theCopyActionNodeOfATextCardTextMessageCarriesBothTextPartsAndTheCardStaysItsOwnButton() {
        val scope = CoroutineScope(Dispatchers.IO)
        val downloads = FileDownloads(File(System.getProperty("java.io.tmpdir"), "rasul-copy-ui"), scope, { null })
        showWithFiles("Before\n[report.pdf (1.2 MB)](rasul-file://$token)\nAfter", downloads)
        val texts = compose.onNode(hasCopyAction).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        assertEquals(listOf("Before", "After"), texts)
        val button = compose.onNodeWithText("Download").fetchSemanticsNode().config
        assertTrue(button.contains(SemanticsActions.OnClick))
        assertFalse(button.contains(SemanticsActions.CustomActions))
        scope.cancel()
    }
}
