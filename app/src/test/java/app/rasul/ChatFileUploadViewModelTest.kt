package app.rasul

import android.net.Uri
import app.rasul.bridge.BridgeEndpoint
import app.rasul.chat.AttachLoad
import app.rasul.chat.AttachmentLoader
import app.rasul.chat.ChatFailure
import app.rasul.chat.ChatViewModel
import app.rasul.chat.MessageKind
import app.rasul.chat.OrderedWriter
import app.rasul.chat.PickedFile
import app.rasul.chat.TargetLoader
import app.rasul.data.FileUploadLimits
import app.rasul.data.HermesError
import app.rasul.data.HermesResult
import app.rasul.data.HermesTarget
import app.rasul.data.PendingAttachment
import app.rasul.data.RunUpdate
import app.rasul.data.SentAttachment
import app.rasul.data.UploadApi
import app.rasul.data.UploadException
import app.rasul.data.UploadFailure
import app.rasul.data.UploadSession
import app.rasul.data.UploadState
import app.rasul.data.UploadedFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.InputStream

private val MAX_BYTES = FileUploadLimits.MAX_FILE_BYTES

private class ZeroStream(private val length: Long) : InputStream() {
    private var pos = 0L
    override fun read(): Int = if (pos >= length) -1 else { pos++; 0 }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (pos >= length) return -1
        val n = minOf(len.toLong(), length - pos).toInt()
        java.util.Arrays.fill(b, off, off + n, 0)
        pos += n
        return n
    }
}

/** Picked documents by uri; nothing is read until the uploader asks for the stream. */
private class FileLoader : AttachmentLoader {
    val picked = mutableMapOf<String, PickedFile>()
    override suspend fun loadPhotos(uris: List<Uri>, photoBudget: Int): AttachLoad = AttachLoad.Ok(emptyList())
    override suspend fun loadFile(uri: Uri): AttachLoad = AttachLoad.Ok(emptyList())
    override suspend fun fit(photo: PendingAttachment.Photo, budget: Int): PendingAttachment.Photo? = photo
    override suspend fun describeFile(uri: Uri): PickedFile? = picked[uri.toString()]
    override fun openFile(uri: Uri): InputStream? = picked[uri.toString()]?.let { ZeroStream(it.size) }
}

private class FakeUploads : UploadApi {
    var gate: CompletableDeferred<Unit>? = null
    var createGate: CompletableDeferred<Unit>? = null
    var chunkPuts = 0
    var createError: UploadException? = null
    var chunkErrors = ArrayDeque<UploadException>()
    val created = mutableListOf<String>()
    val cancelled = mutableListOf<String>()
    private var n = 0

    override suspend fun create(name: String, size: Long, type: String): UploadSession {
        createGate?.await()
        createError?.let { throw it }
        created += name
        return UploadSession("up${++n}", FileUploadLimits.CHUNK_BYTES)
    }

    override suspend fun putChunk(upload: String, index: Int, bytes: ByteArray, length: Int) {
        chunkPuts++
        gate?.await()
        chunkErrors.removeFirstOrNull()?.let { throw it }
    }

    override suspend fun complete(upload: String, sha256: String) = UploadedFile("file-$upload", "n", 0)

    override suspend fun cancel(upload: String) {
        cancelled += upload
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatFileUploadViewModelTest {
    private val sched = TestCoroutineScheduler()
    private val runs = FakeRuns()
    private val sessions = FakeSessions()
    private val memory = FakeMemory()
    private val loader = FileLoader()
    private val uploads = FakeUploads()
    private val writer = OrderedWriter(CoroutineScope(StandardTestDispatcher(sched)))
    private var keyN = 0

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher(sched))

    @After fun tearDown() = Dispatchers.resetMain()

    private fun idle() = sched.advanceUntilIdle()

    private val bridgeTarget = HermesTarget("", null, "", BridgeEndpoint(OkHttpClient(), "default"))

    private fun vm(direct: Boolean = false): ChatViewModel {
        val target = if (direct) HermesTarget("http://example.invalid/", null, "k") else bridgeTarget
        return ChatViewModel(
            TargetLoader { HermesResult.Ok(target) }, runs, sessions, memory, writer, now = { 0L },
            follow = { _, _, _ -> MutableSharedFlow<RunUpdate>(replay = 64) },
            newKey = { "key${++keyN}" },
            workDispatcher = StandardTestDispatcher(sched),
            attachLoader = loader,
            uploadApiFor = { if (it.bridge != null) uploads else null },
            uploadPause = {},
        ).also { idle() }
    }

    private fun pick(v: ChatViewModel, vararg files: Pair<String, Long>) {
        val uris = files.mapIndexed { i, (name, size) ->
            Uri.parse("content://files/${v.state.value.attachments.size}-$i-$name").also { loader.picked[it.toString()] = PickedFile(name, size, "application/octet-stream") }
        }
        v.addFiles(uris)
        idle()
    }

    private fun ChatViewModel.files() = state.value.attachments.filterIsInstance<PendingAttachment.UploadFile>()
    private fun ChatViewModel.notices() = state.value.messages.filter { it.kind == MessageKind.Error }.map { it.error }

    @Test fun pickedFilesUploadAtOnceAndBecomeReady() {
        val v = vm()
        pick(v, "report.pdf" to 100L, "data.zip" to 2_000_000L)
        assertEquals(listOf("report.pdf", "data.zip"), v.files().map { it.name })
        assertEquals(listOf(100L, 2_000_000L), v.files().map { it.size })
        assertTrue(v.files().all { it.state is UploadState.Ready })
        assertEquals(listOf("report.pdf", "data.zip"), uploads.created)
        assertFalse(v.state.value.uploading)
    }

    @Test fun sendWaitsForEveryUploadThenPassesTheFileIds() {
        val v = vm()
        uploads.gate = CompletableDeferred()
        pick(v, "report.pdf" to 100L)
        assertTrue(v.state.value.uploading)
        assertTrue(v.files().single().state is UploadState.Uploading)
        v.onDraft("see this")
        v.send()
        idle()
        assertEquals(0, runs.startN)

        uploads.gate!!.complete(Unit)
        idle()
        assertFalse(v.state.value.uploading)
        v.send()
        idle()
        assertEquals(1, runs.startN)
        assertEquals(listOf(listOf("file-up1")), runs.startFiles)
        assertEquals(JsonPrimitive("see this"), runs.startInputs.single())
        assertTrue(v.state.value.attachments.isEmpty())
        val sent = v.state.value.messages.last { it.kind == MessageKind.User }
        assertEquals(listOf(SentAttachment.SentFile("report.pdf", 100)), sent.attachments)
    }

    @Test fun twoFilesAndNoTextSendTheirNamesAsInputAndBothIds() {
        val v = vm()
        pick(v, "a.pdf" to 10L, "b.zip" to 20L)
        v.send()
        idle()
        assertEquals(listOf(listOf("file-up1", "file-up2")), runs.startFiles)
        assertEquals(JsonPrimitive("a.pdf, b.zip"), runs.startInputs.single())
    }

    @Test fun anEarlierFileKeepsItsIdWhileALaterOneUploads() {
        val v = vm()
        pick(v, "a.pdf" to 10L)
        uploads.gate = CompletableDeferred()
        pick(v, "b.pdf" to 10L)
        assertTrue(v.files()[0].state is UploadState.Ready)
        assertTrue(v.files()[1].state is UploadState.Uploading)
    }

    @Test fun moreThanFourFilesKeepsFourAndSaysSo() {
        val v = vm()
        pick(v, *(1..5).map { "f$it.txt" to 10L }.toTypedArray())
        assertEquals(4, v.files().size)
        assertEquals(listOf<ChatFailure?>(ChatFailure.TooManyFiles), v.notices())
        pick(v, "g.txt" to 10L)
        assertEquals(4, v.files().size)
        assertEquals(2, v.notices().size)
    }

    @Test fun anOversizedFileIsRefusedByNameAndTheOthersStay() {
        val v = vm()
        pick(v, "huge.iso" to MAX_BYTES + 1, "exact.bin" to MAX_BYTES, "empty.txt" to 0L)
        assertEquals(listOf("exact.bin"), v.files().map { it.name })
        assertEquals(
            listOf<ChatFailure?>(ChatFailure.UploadTooLarge("huge.iso"), ChatFailure.UploadEmpty("empty.txt")),
            v.notices(),
        )
    }

    @Test fun removingAChipWhileItUploadsDeletesTheUpload() {
        val v = vm()
        uploads.gate = CompletableDeferred()
        pick(v, "report.pdf" to FileUploadLimits.CHUNK_BYTES * 2L)
        val key = v.files().single().key
        v.removeAttachment(key)
        idle()
        assertTrue(v.state.value.attachments.isEmpty())
        assertEquals(listOf("up1"), uploads.cancelled)
    }

    @Test fun removingAFinishedFileDoesNotCallTheBridge() {
        val v = vm()
        pick(v, "report.pdf" to 10L)
        v.removeAttachment(v.files().single().key)
        idle()
        assertTrue(uploads.cancelled.isEmpty())
    }

    @Test fun eachFailureHasItsOwnMessageAndSendStaysOff() {
        val cases = listOf(
            UploadFailure.OldBridge to ChatFailure.BridgeNeedsUpdate,
            UploadFailure.TooLarge to ChatFailure.UploadTooLarge("a.bin"),
            UploadFailure.TooManyUploads to ChatFailure.TooManyOpenUploads,
            UploadFailure.DiskFull to ChatFailure.PcDiskFull,
            UploadFailure.Network(HermesError.BridgeLinkDropped) to ChatFailure.Api(HermesError.BridgeLinkDropped),
            UploadFailure.Unreadable to ChatFailure.FileUnreadable,
            UploadFailure.ProfileMissing to ChatFailure.UploadBotMissing,
            UploadFailure.SaveFailed to ChatFailure.PcSaveFailed,
        )
        for ((failure, notice) in cases) {
            uploads.createError = UploadException(failure)
            val v = vm()
            pick(v, "a.bin" to 10L)
            assertEquals(UploadState.Failed(failure), v.files().single().state)
            assertEquals(listOf<ChatFailure?>(notice), v.notices())
            assertTrue(v.state.value.filesNotReady)
            v.send()
            idle()
            assertEquals(0, runs.startN)
        }
    }

    @Test fun aRefusedUploadOnlyMarksTheChipFailed() {
        uploads.createError = UploadException(UploadFailure.Rejected("checksum_mismatch"))
        val v = vm()
        pick(v, "a.bin" to 10L)
        assertEquals(UploadState.Failed(UploadFailure.Rejected("checksum_mismatch")), v.files().single().state)
        assertTrue(v.notices().isEmpty())
    }

    @Test fun aChunkThatKeepsFailingFailsTheChipAndRetryUploadsAgain() {
        uploads.chunkErrors = ArrayDeque(List(4) { UploadException(UploadFailure.Network(HermesError.BridgeLinkDropped), transient = true) })
        val v = vm()
        pick(v, "a.bin" to 10L)
        assertEquals(UploadState.Failed(UploadFailure.Network(HermesError.BridgeLinkDropped)), v.files().single().state)
        v.retryUpload(v.files().single().key)
        idle()
        assertTrue(v.files().single().state is UploadState.Ready)
        assertEquals(listOf("a.bin", "a.bin"), uploads.created)
        assertEquals(listOf("up1"), uploads.cancelled)
    }

    @Test fun aDirectConnectionRefusesFilesAndStagesNothing() {
        val v = vm(direct = true)
        pick(v, "a.bin" to 10L)
        assertTrue(v.state.value.attachments.isEmpty())
        assertEquals(listOf<ChatFailure?>(ChatFailure.FilesNeedBridge), v.notices())
        assertTrue(uploads.created.isEmpty())
    }

    @Test fun photosAndFilesAreNeverMixed() {
        val v = vm()
        v.addAttachments(listOf(PendingAttachment.Photo(PendingAttachment.newKey(), ByteArray(4), ByteArray(4))))
        pick(v, "a.bin" to 10L)
        assertTrue(v.files().isEmpty())
        assertEquals(listOf<ChatFailure?>(ChatFailure.FilesMixed), v.notices())

        val w = vm()
        pick(w, "a.bin" to 10L)
        w.addAttachments(listOf(PendingAttachment.Photo(PendingAttachment.newKey(), ByteArray(4), ByteArray(4))))
        assertEquals(1, w.state.value.attachments.size)
        assertEquals(listOf<ChatFailure?>(ChatFailure.FilesMixed), w.notices())
    }

    @Test fun closingTheChatDeletesItsOpenUploads() {
        val v = vm()
        uploads.gate = CompletableDeferred()
        pick(v, "a.pdf" to 100L, "b.pdf" to 100L)
        assertEquals(2, v.files().count { it.state is UploadState.Uploading })
        v.close()
        idle()
        assertEquals(listOf("up1", "up2"), uploads.cancelled.sorted())
    }

    @Test fun closingTheChatKeepsFinishedFilesAndFailedUploadsAreDeleted() {
        val v = vm()
        pick(v, "done.pdf" to 10L)
        uploads.createError = null
        uploads.chunkErrors = ArrayDeque(List(4) { UploadException(UploadFailure.Network(HermesError.BridgeLinkDropped), transient = true) })
        pick(v, "broken.bin" to 10L)
        assertTrue(v.files()[0].state is UploadState.Ready && v.files()[1].state is UploadState.Failed)
        v.close()
        idle()
        assertEquals(listOf("up2"), uploads.cancelled)
    }

    @Test fun removingAChipWhileTheBridgeIsMakingItsUploadDeletesItAsSoonAsItAnswers() {
        val v = vm()
        uploads.createGate = CompletableDeferred()
        pick(v, "report.pdf" to 100L)
        v.removeAttachment(v.files().single().key)
        idle()
        assertTrue(uploads.cancelled.isEmpty())
        uploads.createGate!!.complete(Unit)
        idle()
        assertEquals(listOf("up1"), uploads.cancelled)
        assertEquals(0, uploads.chunkPuts)
        assertTrue(v.notices().isEmpty())
        assertTrue(v.state.value.attachments.isEmpty())
    }

    @Test fun aFileTheRunTookIsNoLongerTracked() {
        val v = vm()
        pick(v, "a.pdf" to 10L, "b.pdf" to 10L)
        assertEquals(2, v.trackedUploads)
        v.send()
        idle()
        assertEquals(1, runs.startN)
        assertEquals(0, v.trackedUploads)
    }

    @Test fun filesKeptAfterAFailedStartAreStillTracked() {
        val v = vm()
        pick(v, "a.pdf" to 10L)
        runs.onStart = { HermesResult.Err(HermesError.Server(500, null, null)) }
        v.send()
        idle()
        assertEquals(1, v.trackedUploads)
        assertEquals(1, v.files().size)
    }

    @Test fun aRefusedMessageBodyHasItsOwnNoticeAndKeepsTheFiles() {
        val v = vm()
        pick(v, "a.pdf" to 10L)
        runs.onStart = { HermesResult.Err(HermesError.Server(400, null, "invalid_run_input")) }
        v.onDraft("caption")
        v.send()
        idle()
        assertTrue(v.notices().contains(ChatFailure.RunInputRefused))
        assertFalse(v.notices().any { it is ChatFailure.Api })
        assertEquals(ChatFailure.RunInputRefused, v.state.value.terminalFailure)
        assertEquals(1, v.files().size)
        assertEquals("caption", v.state.value.draft)
    }

    @Test fun filesTheBridgeNoLongerHasAreNamedAndTheirChipsGoWhileTheTextStays() {
        val v = vm()
        pick(v, "a.pdf" to 10L)
        runs.onStart = { HermesResult.Err(HermesError.Server(400, null, "file_unavailable")) }
        v.onDraft("caption")
        v.send()
        idle()
        assertTrue(v.state.value.attachments.isEmpty())
        assertTrue(v.notices().contains(ChatFailure.FilesGone(listOf("a.pdf"))))
        assertEquals("caption", v.state.value.draft)
        assertEquals(listOf(listOf("file-up1")), runs.startFiles)
    }
}
