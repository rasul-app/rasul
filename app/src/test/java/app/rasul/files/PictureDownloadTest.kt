package app.rasul.files

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

class PictureDownloadTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun link(name: String, size: String?, token: Char = 'p') = FileLink(token.toString().repeat(43), name, size)

    @Test fun imageWithKnownSizeUnderTheCapIsFetchedByItself() {
        assertTrue(autoDownloadsPicture(link("photo.png", "1.2 MB")))
        assertTrue(autoDownloadsPicture(link("PHOTO.JPG", "300 KB")))
        for (ext in listOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif")) assertTrue(ext, autoDownloadsPicture(link("a.$ext", "10 KB")))
        assertTrue(autoDownloadsPicture(link("edge.png", "8.0 MB")))
    }

    @Test fun biggerUnknownOrNonImageStaysATapToDownloadCard() {
        assertFalse(autoDownloadsPicture(link("big.png", "8.1 MB")))
        assertFalse(autoDownloadsPicture(link("big.png", "20 MB")))
        assertFalse(autoDownloadsPicture(link("unknown.png", null)))
        assertFalse(autoDownloadsPicture(link("report.pdf", "10 KB")))
        assertFalse(autoDownloadsPicture(link("drawing.svg", "10 KB")))
        assertFalse(autoDownloadsPicture(link("noext", "10 KB")))
    }

    @Test fun sizeTextConvertsToBytes() {
        assertEquals(512L, FileLinks.sizeBytes("512 B"))
        assertEquals(2048L, FileLinks.sizeBytes("2 KB"))
        assertEquals(1_258_291L, FileLinks.sizeBytes("1.2 MB"))
        assertNull(FileLinks.sizeBytes(null))
        assertNull(FileLinks.sizeBytes("a lot"))
    }

    private class Counting(val calls: AtomicInteger, val respond: () -> FileResponse) : FileSource {
        override suspend fun <T> fetch(token: String, read: suspend (FileResponse) -> T): T { calls.incrementAndGet(); return read(respond()) }
    }

    @Test fun autoStartRunsOncePerTokenAndNeverRestartsAFailedOne() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO)
        val calls = AtomicInteger()
        val ok = link("a.png", "1 KB", 'a')
        val bad = link("b.png", "1 KB", 'b')
        val downloads = FileDownloads(File(tmp.root, "r"), scope, { Counting(calls) { FileResponse(500) } })
        val good = FileDownloads(File(tmp.root, "g"), scope, {
            Counting(calls) { FileResponse(200, "image/png", 4, null, ByteArrayInputStream(ByteArray(4))) }
        })
        assertTrue(good.startAuto("bridge", ok))
        withTimeout(10_000) { good.states.first { it[ok.token] is FileState.Done } }
        assertFalse(good.startAuto("bridge", ok))
        assertEquals(1, calls.get())
        // failing picture: one automatic attempt, then it stays failed until the user taps Try again
        assertTrue(downloads.startAuto("bridge", bad))
        withTimeout(10_000) { downloads.states.first { it[bad.token] is FileState.Failed } }
        assertFalse(downloads.startAuto("bridge", bad))
        downloads.cancel(bad.token)
        assertFalse(downloads.startAuto("bridge", bad))
        assertNull(downloads.states.value[bad.token])
        assertEquals(2, calls.get())
        scope.cancel()
    }

    @Test fun autoStartSkipsIneligibleLinksAndFilesAlreadyOnDisk() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO)
        val calls = AtomicInteger()
        val root = File(tmp.root, "r2")
        val source = { _: String -> Counting(calls) { FileResponse(200, "image/png", 1, null, ByteArrayInputStream(ByteArray(1))) } }
        val downloads = FileDownloads(root, scope, { source(it) })
        assertFalse(downloads.startAuto("bridge", link("big.png", "9 MB", 'c')))
        assertFalse(downloads.startAuto("bridge", link("x.pdf", "1 KB", 'd')))
        assertFalse(downloads.startAuto("bridge", link("u.png", null, 'e')))
        assertEquals(0, calls.get())
        val have = link("have.png", "1 KB", 'f')
        val dir = FileDownloader.dirFor(root, have.token).also { it.mkdirs() }
        File(dir, "have.png").writeBytes(ByteArray(3))
        downloads.probe(have.token)
        assertFalse(downloads.startAuto("bridge", have))
        assertEquals(0, calls.get())
        scope.cancel()
    }

    /** A body of [left] zero bytes, read in chunks. */
    private class Zeros(var left: Long) : java.io.InputStream() {
        override fun read(): Int = if (left-- > 0) 0 else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = minOf(len.toLong(), left).toInt()
            left -= n
            return n
        }
    }

    @Test fun automaticPictureStopsAtTheThumbnailCapWhenTheLinkLiedAboutItsSize() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO)
        val liar = link("lie.png", "1 KB", 'l')
        val root = File(tmp.root, "lie")
        val big = { _: String -> Counting(AtomicInteger()) { FileResponse(200, "image/png", null, null, Zeros(9L * 1024 * 1024)) } }
        val downloads = FileDownloads(root, scope, { big(it) })
        assertTrue(downloads.startAuto("bridge", liar))
        // the automatic download ends with no state at all: the card is Idle again and offers Download
        withTimeout(10_000) { downloads.states.first { liar.token !in it } }
        assertNull(downloads.states.value[liar.token])
        assertNull(FileDownloader.existing(root, liar.token))
        assertFalse(downloads.startAuto("bridge", liar)) // one automatic attempt only
        scope.cancel()
    }

    @Test fun automaticCapAlsoStopsOnAnHonestBigLengthAndATapUsesTheGeneralLimit() {
        val nine = 9L * 1024 * 1024
        val auto = runBlocking {
            FileDownloader.download("a".repeat(43), "x.png", object : FileSource {
                override suspend fun <T> fetch(token: String, read: suspend (FileResponse) -> T): T =
                    read(FileResponse(200, "image/png", nine, null, Zeros(nine)))
            }, File(tmp.root, "auto"), maxBytes = THUMB_MAX_BYTES)
        }
        assertEquals(FileFailure.TooLarge, (auto as FileState.Failed).failure)
        val tap = runBlocking {
            FileDownloader.download("a".repeat(43), "x.png", object : FileSource {
                override suspend fun <T> fetch(token: String, read: suspend (FileResponse) -> T): T =
                    read(FileResponse(200, "image/png", nine, null, Zeros(nine)))
            }, File(tmp.root, "tap"))
        }
        assertEquals(nine, (tap as FileState.Done).size)
    }

    @Test fun saveCopyWritesTheBytes() {
        val file = tmp.newFile("a.bin").also { it.writeBytes(ByteArray(200_000) { i -> (i % 253).toByte() }) }
        val out = ByteArrayOutputStream()
        assertEquals(SaveResult.Saved, saveCopy(file) { out })
        assertTrue(file.readBytes().contentEquals(out.toByteArray()))
    }

    @Test fun saveCopyNamesTheFailure() {
        val file = tmp.newFile("b.bin").also { it.writeBytes(ByteArray(10)) }
        assertEquals(SaveResult.SourceMissing, saveCopy(File(tmp.root, "gone.bin")) { ByteArrayOutputStream() })
        assertEquals(SaveResult.TargetUnavailable, saveCopy(file) { null })
        assertEquals(SaveResult.TargetUnavailable, saveCopy(file) { throw java.io.FileNotFoundException() })
        assertEquals(SaveResult.TargetUnavailable, saveCopy(file) { throw SecurityException() })
        val failing = object : OutputStream() {
            override fun write(b: Int) = throw IOException("no space")
        }
        assertEquals(SaveResult.WriteFailed, saveCopy(file) { failing })
    }

    @Test fun savesThatNeverStartedLeaveThePickedDocumentAlone() {
        val file = tmp.newFile("d.bin").also { it.writeBytes(ByteArray(10)) }
        val discarded = AtomicInteger()
        val discard = { discarded.incrementAndGet(); Unit }
        assertEquals(SaveResult.SourceMissing, saveCopy(File(tmp.root, "gone2.bin"), discard) { ByteArrayOutputStream() })
        assertEquals(SaveResult.TargetUnavailable, saveCopy(file, discard) { null })
        assertEquals(SaveResult.TargetUnavailable, saveCopy(file, discard) { throw java.io.FileNotFoundException() })
        assertEquals(0, discarded.get())
    }

    @Test fun failedSaveRemovesThePickedDocumentAndKeepsTheSpecificError() {
        val file = tmp.newFile("c.bin").also { it.writeBytes(ByteArray(10)) }
        val failing = object : OutputStream() {
            override fun write(b: Int) = throw IOException("no space")
        }
        val discarded = AtomicInteger()
        assertEquals(SaveResult.WriteFailed, saveCopy(file, discard = { discarded.incrementAndGet() }) { failing })
        assertEquals(1, discarded.get())
        // a failing clean-up changes nothing about the answer
        assertEquals(SaveResult.WriteFailed, saveCopy(file, discard = { throw SecurityException() }) { failing })
        // a good copy keeps its document
        assertEquals(SaveResult.Saved, saveCopy(file, discard = { discarded.incrementAndGet() }) { ByteArrayOutputStream() })
        assertEquals(1, discarded.get())
    }
}
