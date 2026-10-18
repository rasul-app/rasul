package app.rasul.files

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.rasul.data.HermesError
import app.rasul.ui.theme.RasulTheme
import app.rasul.ui.theme.RasulType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp")
class FileCardUiTest {
    @get:Rule val compose = createComposeRule()

    private val token = "k".repeat(43)

    private fun show(link: FileLink, state: FileState, calls: MutableList<String> = mutableListOf()) {
        compose.setContent {
            RasulTheme(dark = false) {
                FileCard(link, state, null,
                    onDownload = { calls += "download" }, onCancel = { calls += "cancel" },
                    onOpen = { calls += "open" }, onSave = { calls += "save" }, onShare = { calls += "share" })
            }
        }
    }

    private fun done(name: String, mime: String) = FileState.Done(File("/nonexistent/$name"), name, mime, 1234)

    @Test fun idleCardShowsNameSizeAndDownloadOnly() {
        val calls = mutableListOf<String>()
        show(FileLink(token, "report.pdf", "1.2 MB"), FileState.Idle, calls)
        compose.onNodeWithText("report.pdf").assertExists()
        compose.onNodeWithText("1.2 MB").assertExists()
        compose.onNodeWithText("Open").assertDoesNotExist()
        compose.onNodeWithText("Download").performClick()
        assertEquals(listOf("download"), calls)
    }

    @Test fun actionsCarryTheFileNameForScreenReaders() {
        show(FileLink(token, "report.pdf", null), done("report.pdf", "application/pdf"))
        compose.onNode(hasContentDescription("Open report.pdf")).assertExists()
        compose.onNode(hasContentDescription("Save report.pdf")).assertExists()
        compose.onNode(hasContentDescription("Share report.pdf")).assertExists()
    }

    @Test fun finishedPdfOffersOpenSaveShare() {
        val calls = mutableListOf<String>()
        show(FileLink(token, "report.pdf", null), done("report.pdf", "application/pdf"), calls)
        compose.onNodeWithText("Open").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Share").performClick()
        assertEquals(listOf("open", "save", "share"), calls)
    }

    @Test fun finishedHtmlCardHasSaveAndShareButNoOpen() {
        show(FileLink(token, "page.html", null), done("page.html", "application/octet-stream"))
        compose.onNodeWithText("Open").assertDoesNotExist()
        compose.onNodeWithText("Save").assertExists()
        compose.onNodeWithText("Share").assertExists()
    }

    @Test fun downloadingShowsProgressAndCancel() {
        val calls = mutableListOf<String>()
        show(FileLink(token, "a.zip", "3 MB"), FileState.Downloading(1_000_000, 3_000_000), calls)
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("cancel"), calls)
        compose.onNodeWithText("Download").assertDoesNotExist()
    }

    @Test fun expiredAndChangedCannotBeRetriedButTransportCan() {
        assertFalse(canRetry(FileFailure.Expired))
        assertFalse(canRetry(FileFailure.Changed))
        assertFalse(canRetry(FileFailure.NotFound))
        assertTrue(canRetry(FileFailure.RateLimited))
        assertTrue(canRetry(FileFailure.ShortBody))
        assertTrue(canRetry(FileFailure.Transport(HermesError.BridgeLinkDropped)))
        show(FileLink(token, "a.pdf", null), FileState.Failed(FileFailure.Expired))
        compose.onNodeWithText("This file has expired. Ask the agent to send it again.").assertExists()
        compose.onNodeWithText("Try again").assertDoesNotExist()
    }

    @Test fun failedRateLimitOffersRetry() {
        val calls = mutableListOf<String>()
        show(FileLink(token, "a.pdf", null), FileState.Failed(FileFailure.RateLimited), calls)
        compose.onNodeWithText("Try again").performClick()
        assertEquals(listOf("download"), calls)
    }

    private val text = "Here you go:\n[report.pdf (1.2 MB)](rasul-file://$token)\nand [file not available: gone.zip]"

    @Test fun withoutABridgeChatTheTextIsShownExactlyAsBefore() {
        compose.setContent { RasulTheme(dark = false) { AssistantText(text, RasulType.chatBody, androidx.compose.ui.graphics.Color.Black) } }
        compose.onNodeWithText(text).assertExists()
        compose.onNodeWithText("Download").assertDoesNotExist()
    }

    @Test fun inABridgeChatTheLinkBecomesACardAndNothingDownloadsByItself() {
        val scope = CoroutineScope(Dispatchers.IO)
        var asked = 0
        val downloads = FileDownloads(File(System.getProperty("java.io.tmpdir"), "rasul-card-ui"), scope, { asked++; null })
        compose.setContent {
            RasulTheme(dark = false) {
                CompositionLocalProvider(LocalFileBridge provides "bridge-1", LocalFileDownloads provides downloads) {
                    AssistantText(text, RasulType.chatBody, androidx.compose.ui.graphics.Color.Black)
                }
            }
        }
        compose.onNodeWithText("report.pdf").assertExists()
        compose.onNodeWithText("Download").assertExists()
        compose.onNodeWithText("File not available: gone.zip").assertExists()
        compose.onNodeWithText(text).assertDoesNotExist()
        assertEquals(0, asked)
        scope.cancel()
    }
}
