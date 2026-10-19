package app.rasul.files

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
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

    private fun bitmap() = android.graphics.Bitmap.createBitmap(120, 80, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()

    @Test fun pictureTapOpensAndOverflowOffersSaveAndShare() {
        val calls = mutableListOf<String>()
        compose.setContent {
            RasulTheme(dark = false) { PictureCard("sunset.png", bitmap(), { calls += "open" }, { calls += "save" }, { calls += "share" }) }
        }
        compose.onNode(hasContentDescription("Preview of sunset.png")).performClick()
        compose.onNode(hasContentDescription("More actions for sunset.png")).performClick()
        compose.onNodeWithText("Save").performClick()
        compose.onNode(hasContentDescription("More actions for sunset.png")).performClick()
        compose.onNodeWithText("Share").performClick()
        assertEquals(listOf("open", "save", "share"), calls)
    }

    @Test fun pictureLongPressOpensTheMenu() {
        compose.setContent { RasulTheme(dark = false) { PictureCard("sunset.png", bitmap(), {}, {}, {}) } }
        compose.onNodeWithText("Save").assertDoesNotExist()
        compose.onNode(hasContentDescription("Preview of sunset.png")).performTouchInput { longClick() }
        compose.onNodeWithText("Save").assertExists()
        compose.onNodeWithText("Share").assertExists()
    }

    private fun thin(w: Int, h: Int) = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()

    @Test fun veryThinPicturesKeepABoxOfAtLeast48dpEachWay() {
        compose.setContent {
            RasulTheme(dark = false) {
                androidx.compose.foundation.layout.Column {
                    PictureCard("tall-line.png", thin(1, 30000), {}, {}, {})
                    PictureCard("wide-line.png", thin(30000, 1), {}, {}, {})
                }
            }
        }
        for (name in listOf("tall-line.png", "wide-line.png")) {
            compose.onNode(hasContentDescription("Preview of $name")).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
    }

    @Test fun tallPictureStaysWithinTheMaximumHeight() {
        compose.setContent { RasulTheme(dark = false) { PictureCard("tall.png", thin(100, 5000), {}, {}, {}) } }
        compose.onNode(hasContentDescription("Preview of tall.png")).assertHeightIsAtLeast(48.dp)
        assertTrue(compose.onNode(hasContentDescription("Preview of tall.png")).getBoundsInRoot().height <= 360.dp)
    }

    @Test fun decodedPictureIsNullWhileTheNextFileDecodesNeverTheOldDoneValue() {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val file = File("/nonexistent/sunset.png")
        val state = androidx.compose.runtime.mutableStateOf<FileState.Done?>(null)
        val seen = mutableListOf<String>()
        compose.setContent {
            val decoded by rememberDecoded(state.value, picture = true) { _, _ -> gate.await(); thin(2, 2) }
            seen += when {
                state.value == null -> "downloading"
                decoded == null -> "placeholder"
                else -> "picture"
            }
        }
        compose.waitForIdle()
        state.value = FileState.Done(file, "sunset.png", "image/png", 1234)
        compose.waitForIdle()
        gate.complete(Unit)
        compose.waitUntil(10_000) { seen.lastOrNull() == "picture" }
        assertEquals(listOf("downloading", "placeholder", "picture"), seen.distinct())
    }

    @Test fun aNewFileReplacingAFinishedOneShowsThePlaceholderFirst() {
        val gates = mapOf("a.png" to kotlinx.coroutines.CompletableDeferred<Unit>(), "b.png" to kotlinx.coroutines.CompletableDeferred<Unit>())
        val state = androidx.compose.runtime.mutableStateOf<FileState.Done?>(null)
        val seen = mutableListOf<String>()
        compose.setContent {
            val decoded by rememberDecoded(state.value, picture = true) { file, _ -> gates.getValue(file.name).await(); thin(2, 2) }
            seen += when {
                state.value == null -> "none"
                decoded == null -> "placeholder-" + state.value!!.name
                else -> "picture-" + state.value!!.name
            }
        }
        state.value = FileState.Done(File("/nonexistent/a.png"), "a.png", "image/png", 10)
        compose.waitForIdle()
        gates.getValue("a.png").complete(Unit)
        compose.waitUntil(10_000) { seen.lastOrNull() == "picture-a.png" }
        state.value = FileState.Done(File("/nonexistent/b.png"), "b.png", "image/png", 10)
        compose.waitForIdle()
        // b is still decoding: the card must show the placeholder, not a's picture (needs the value = null reset)
        assertEquals("placeholder-b.png", seen.last())
        gates.getValue("b.png").complete(Unit)
        compose.waitUntil(10_000) { seen.lastOrNull() == "picture-b.png" }
    }

    @Test fun placeholderIsLabelledWithTheFileName() {
        compose.setContent { RasulTheme(dark = false) { PicturePlaceholder("sunset.png") } }
        compose.onNode(hasContentDescription("Loading sunset.png")).assertExists()
    }

    @Test fun pictureInABridgeChatDownloadsByItselfAndShowsAsAPicture() {
        val scope = CoroutineScope(Dispatchers.IO)
        val png = java.io.ByteArrayOutputStream().also { bitmap().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val source = object : FileSource {
            override suspend fun <T> fetch(token: String, read: suspend (FileResponse) -> T): T =
                read(FileResponse(200, "image/png", png.size.toLong(), null, java.io.ByteArrayInputStream(png)))
        }
        val downloads = FileDownloads(File(System.getProperty("java.io.tmpdir"), "rasul-pic-ui"), scope, { source })
        val message = "Here:\n[sunset.png (1 KB)](rasul-file://$token)"
        compose.setContent {
            RasulTheme(dark = false) {
                CompositionLocalProvider(LocalFileBridge provides "bridge-1", LocalFileDownloads provides downloads) {
                    AssistantText(message, RasulType.chatBody, androidx.compose.ui.graphics.Color.Black)
                }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasContentDescription("Preview of sunset.png")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Download").assertDoesNotExist()
        scope.cancel()
    }
}
