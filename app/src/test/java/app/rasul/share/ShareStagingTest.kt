package app.rasul.share

import android.net.Uri
import app.rasul.chat.AttachLoad
import app.rasul.chat.AttachmentLoader
import app.rasul.chat.ChatFailure
import app.rasul.data.PendingAttachment
import app.rasul.data.RunInputLimits
import app.rasul.security.AppLockState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class RecordingLoader : AttachmentLoader {
    val photoCalls = mutableListOf<Pair<List<Uri>, Int>>()
    val fileCalls = mutableListOf<Uri>()
    var gate: CompletableDeferred<Unit>? = null
    var photos: AttachLoad? = null
    var file: AttachLoad? = null

    override suspend fun loadPhotos(uris: List<Uri>, photoBudget: Int): AttachLoad {
        photoCalls += uris to photoBudget
        gate?.await()
        return photos ?: AttachLoad.Ok(uris.map { PendingAttachment.Photo(PendingAttachment.newKey(), ByteArray(1), ByteArray(1)) })
    }

    override suspend fun loadFile(uri: Uri): AttachLoad {
        fileCalls += uri
        gate?.await()
        return file ?: AttachLoad.Ok(listOf(PendingAttachment.TextFile(PendingAttachment.newKey(), "notes.txt", "hello")))
    }

    override suspend fun fit(photo: PendingAttachment.Photo, budget: Int): PendingAttachment.Photo? = photo
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShareStagingTest {
    private fun uri(n: Int) = Uri.parse("content://media/$n")
    private val image: (Uri) -> Boolean = { it.toString().contains("img") }
    private fun img(n: Int) = Uri.parse("content://media/img$n")
    private fun doc(n: Int) = Uri.parse("content://docs/doc$n")

    @Test fun planKeepsTheFirstFourPhotos() {
        val plan = planShare((1..6).map(::img), image)
        assertEquals((1..4).map(::img), plan.photos)
        assertNull(plan.file)
        assertEquals(2, plan.dropped)
        assertEquals(RunInputLimits.MAX_PHOTOS, plan.photos.size)
    }

    @Test fun planWithExactlyTheLimitDropsNothing() {
        val plan = planShare((1..4).map(::img), image)
        assertEquals(4, plan.photos.size)
        assertEquals(0, plan.dropped)
    }

    @Test fun planKeepsOneFileWhenTheFirstItemIsAFile() {
        val plan = planShare(listOf(doc(1), img(1), doc(2)), image)
        assertTrue(plan.photos.isEmpty())
        assertEquals(doc(1), plan.file)
        assertEquals(2, plan.dropped)
    }

    @Test fun planKeepsPhotosWhenTheFirstItemIsAPhotoAndDropsFiles() {
        val plan = planShare(listOf(img(1), doc(1), img(2)), image)
        assertEquals(listOf(img(1), img(2)), plan.photos)
        assertNull(plan.file)
        assertEquals(1, plan.dropped)
    }

    @Test fun planOfNothingIsEmpty() {
        assertEquals(SharePlan(emptyList(), null, 0), planShare(emptyList(), image))
    }

    @Test fun stagerLoadsPhotosThroughTheAttachLoaderWithTheSharedBudget() = runTest {
        val loader = RecordingLoader()
        val stager = ShareStager(loader) { image(it) }
        val staged = stager.stage(7, IncomingShare("hi", (1..6).map(::img), refused = 1))
        assertEquals(1, loader.photoCalls.size)
        assertEquals((1..4).map(::img), loader.photoCalls.single().first)
        assertEquals(RunInputLimits.photoBudget(4), loader.photoCalls.single().second)
        assertEquals(4, staged.items.size)
        assertEquals("hi", staged.text)
        assertEquals(3, staged.dropped) // two over the limit, one refused
        assertNull(staged.failure)
    }

    @Test fun aPhotoSharedAfterManyFilesIsStillKept() = runTest {
        val loader = RecordingLoader()
        val staged = ShareStager(loader) { image(it) }.stage(1, IncomingShare("", listOf(img(1), doc(1), doc(2), doc(3), doc(4), img(2)), 0))
        assertEquals(listOf(img(1), img(2)), loader.photoCalls.single().first)
        assertEquals(2, staged.items.size)
        assertEquals(4, staged.dropped)
    }

    @Test fun stagerStopsLookingUpTypesOnceItHasAllThePhotosItKeeps() = runTest {
        val loader = RecordingLoader()
        val looked = mutableListOf<Uri>()
        val staged = ShareStager(loader) { uri -> looked += uri; image(uri) }.stage(1, IncomingShare("", (1..30).map(::img), 0))
        assertEquals(RunInputLimits.MAX_PHOTOS, looked.size)
        assertEquals((1..4).map(::img), loader.photoCalls.single().first)
        assertEquals(26, staged.dropped)
    }

    @Test fun aFirstItemThatIsNotAPhotoNeedsNoFurtherTypeLookups() = runTest {
        val loader = RecordingLoader()
        val looked = mutableListOf<Uri>()
        val staged = ShareStager(loader) { uri -> looked += uri; image(uri) }.stage(1, IncomingShare("", listOf(doc(1), img(1), img(2)), 0))
        assertEquals(listOf(doc(1)), looked)
        assertEquals(doc(1), loader.fileCalls.single())
        assertEquals(2, staged.dropped)
    }

    @Test fun stagerLoadsAFileThroughTheFileLoader() = runTest {
        val loader = RecordingLoader()
        val staged = ShareStager(loader) { image(it) }.stage(1, IncomingShare("", listOf(doc(1), doc(2)), 0))
        assertEquals(listOf(doc(1)), loader.fileCalls)
        assertTrue(loader.photoCalls.isEmpty())
        assertEquals("notes.txt", (staged.items.single() as PendingAttachment.TextFile).name)
        assertEquals(1, staged.dropped)
    }

    @Test fun stagerReportsTheSameFailureTheAttachButtonWould() = runTest {
        val loader = RecordingLoader().apply { file = AttachLoad.Failed(ChatFailure.FileTooLarge) }
        val staged = ShareStager(loader) { image(it) }.stage(1, IncomingShare("t", listOf(doc(1)), 0))
        assertEquals(ChatFailure.FileTooLarge, staged.failure)
        assertTrue(staged.items.isEmpty())
        assertEquals("t", staged.text)
    }

    @Test fun textOnlyShareNeedsNoLoading() = runTest {
        val inbox = ShareInbox()
        inbox.receive(this, IncomingShare("just text", emptyList(), 0), ShareStager(RecordingLoader()) { false })
        val share = inbox.pending.value!!
        assertFalse(share.loading)
        assertEquals("just text", share.text)
    }

    @Test fun inboxShowsLoadingThenTheStagedShare() = runTest(StandardTestDispatcher()) {
        val loader = RecordingLoader().apply { gate = CompletableDeferred() }
        val inbox = ShareInbox()
        inbox.receive(this, IncomingShare("", listOf(img(1)), 0), ShareStager(loader) { image(it) })
        assertTrue(inbox.pending.value!!.loading)
        advanceUntilIdle()
        assertTrue(inbox.pending.value!!.loading)
        loader.gate!!.complete(Unit)
        advanceUntilIdle()
        assertFalse(inbox.pending.value!!.loading)
        assertEquals(1, inbox.pending.value!!.items.size)
    }

    @Test fun aNewerShareReplacesAnOlderOneAndTheStaleResultIsIgnored() = runTest(StandardTestDispatcher()) {
        val loader = RecordingLoader().apply { gate = CompletableDeferred() }
        val inbox = ShareInbox()
        val stager = ShareStager(loader) { image(it) }
        inbox.receive(this, IncomingShare("old", listOf(img(1)), 0), stager)
        advanceUntilIdle()
        inbox.receive(this, IncomingShare("new", emptyList(), 0), stager)
        loader.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals("new", inbox.pending.value!!.text)
        assertTrue(inbox.pending.value!!.items.isEmpty())
    }

    @Test fun cancelDropsTheShare() = runTest {
        val inbox = ShareInbox()
        inbox.receive(this, IncomingShare("x", emptyList(), 0), ShareStager(RecordingLoader()) { false })
        inbox.cancel()
        assertNull(inbox.pending.value)
    }

    @Test fun onlyTheChosenRouteClaimsTheShareAndOnlyOnce() = runTest {
        val inbox = ShareInbox()
        inbox.receive(this, IncomingShare("x", emptyList(), 0), ShareStager(RecordingLoader()) { false })
        assertNull(inbox.claim("chat/a"))
        inbox.choose("chat/a?newSession=s")
        assertNull(inbox.claim("chat/b"))
        assertNull(inbox.claim("chat/a"))
        assertEquals("x", inbox.claim("chat/a?newSession=s")!!.text)
        assertNull(inbox.claim("chat/a?newSession=s"))
        assertNull(inbox.pending.value)
    }

    @Test fun aShareWithOnlyAFailureHasNothingToSend() {
        val failed = PendingShare(1, "", failure = ChatFailure.FileTypeUnsupported)
        assertFalse(failed.canSend)
        assertTrue(PendingShare(1, "text", failure = ChatFailure.FileTypeUnsupported).canSend)
        assertTrue(PendingShare(1, "", items = listOf(PendingAttachment.TextFile(1, "a.txt", "x"))).canSend)
        assertFalse(PendingShare(1, "", dropped = 2).canSend)
    }

    @Test fun anUnsupportedFileStagesAsAFailureWithNothingToSend() = runTest {
        val loader = RecordingLoader().apply { file = AttachLoad.Failed(ChatFailure.FileTypeUnsupported) }
        val staged = ShareStager(loader) { image(it) }.stage(1, IncomingShare("", listOf(Uri.parse("content://docs/a.pdf")), 0))
        assertEquals(ChatFailure.FileTypeUnsupported, staged.failure)
        assertFalse(staged.canSend)
    }

    @Test fun aChatThatCannotBeOpenedReturnsTheShareToThePicker() = runTest {
        val inbox = ShareInbox()
        inbox.receive(this, IncomingShare("x", emptyList(), 0), ShareStager(RecordingLoader()) { false })
        inbox.choose("chat/a?newSession=s")
        assertFalse(inbox.release("chat/b"))
        assertEquals("chat/a?newSession=s", inbox.pending.value!!.route)
        assertTrue(inbox.release("chat/a?newSession=s"))
        val back = inbox.pending.value!!
        assertNull(back.route)
        assertTrue(back.openFailed)
        assertEquals("x", back.text)
        assertNull(inbox.claim("chat/a?newSession=s"))
        assertFalse(inbox.release("chat/a?newSession=s"))
        inbox.choose("chat/c")
        assertFalse(inbox.pending.value!!.openFailed)
        assertEquals("x", inbox.claim("chat/c")!!.text)
    }

    @Test fun lockOrderingTheScreenOpensOnlyAfterUnlock() = runTest {
        val inbox = ShareInbox()
        inbox.receive(this, IncomingShare("secret", emptyList(), 0), ShareStager(RecordingLoader()) { false })
        val share = inbox.pending.value
        val locked = AppLockState(loaded = true, enabled = true, locked = true)
        assertFalse(shareMayOpen(AppLockState(loaded = false), share))
        assertFalse(shareMayOpen(locked, share))
        assertEquals("secret", inbox.pending.value!!.text) // kept while locked
        assertTrue(shareMayOpen(locked.copy(locked = false), share))
        assertTrue(shareMayOpen(AppLockState(loaded = true, enabled = false, locked = false), share))
        assertFalse(shareMayOpen(locked.copy(locked = false), null))
    }
}
