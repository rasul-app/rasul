package app.rasul.files

import app.rasul.data.HermesError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException

class FileDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var root: File
    private val token = "T".repeat(43)

    @Before fun setUp() { root = File(tmp.root, "received") }

    private class Fake(val respond: suspend () -> FileResponse) : FileSource {
        override suspend fun <T> fetch(token: String, read: suspend (FileResponse) -> T): T = read(respond())
    }

    private fun ok(bytes: ByteArray, disposition: String? = null, type: String? = "application/pdf", length: Long? = bytes.size.toLong()) =
        FileResponse(200, type, length, disposition, ByteArrayInputStream(bytes))

    private fun run(source: FileSource, hint: String = "hint.pdf", progress: (Long, Long?) -> Unit = { _, _ -> }): FileState =
        runBlocking { FileDownloader.download(token, hint, source, root, progress) }

    private fun failure(state: FileState) = (state as FileState.Failed).failure

    @Test fun successStreamsToDiskWithHeaderNameAndProgress() {
        val bytes = ByteArray(150_000) { (it % 251).toByte() }
        val seen = mutableListOf<Long>()
        val header = "attachment; filename=\"ascii.pdf\"; filename*=UTF-8''r%C3%A9sum%C3%A9.pdf"
        val state = run(Fake { ok(bytes, header) }) { d, _ -> seen += d }
        val done = state as FileState.Done
        assertEquals("résumé.pdf", done.name)
        assertEquals("application/pdf", done.mime)
        assertEquals(150_000L, done.size)
        assertTrue(bytes.contentEquals(done.file.readBytes()))
        assertEquals(150_000L, seen.last())
        assertTrue(seen.size >= 3) // 64 KB chunks
        assertEquals(listOf(done.file.name), done.file.parentFile!!.list()!!.toList()) // no .part left
        assertEquals(FileDownloader.dirFor(root, token), done.file.parentFile)
        assertFalse(done.file.parentFile!!.name.contains(token))
    }

    @Test fun nameFallsBackToTheLinkNameAndIsSanitised() {
        val a = run(Fake { ok(ByteArray(3), disposition = null) }, hint = "../x/doc.pdf") as FileState.Done
        assertEquals("_x_doc.pdf", a.name)
        val b = run(Fake { ok(ByteArray(3), disposition = "attachment; filename=\"..\\\\..\\\\evil.pdf\"") }) as FileState.Done
        assertFalse(b.name.contains('\\') || b.name.contains('/'))
        assertEquals(b.file.parentFile, FileDownloader.dirFor(root, token))
    }

    @Test fun everyErrorCodeMapsToItsOwnFailure() {
        val table = listOf(
            Triple(404, "file_not_found", FileFailure.NotFound),
            Triple(410, "file_expired", FileFailure.Expired),
            Triple(410, "file_changed", FileFailure.Changed),
            Triple(410, "file_unavailable", FileFailure.Unavailable),
            Triple(429, "file_rate_limited", FileFailure.RateLimited),
        )
        for ((status, code, expected) in table) {
            val state = run(Fake { FileResponse(status, errorBody = "{\"error\": \"$code\"}") })
            assertEquals(code, expected, failure(state))
        }
        assertEquals(FileFailure.Forbidden, failure(run(Fake { FileResponse(403, errorBody = "{\"error\": \"Registered device certificate required\"}") })))
        assertEquals(FileFailure.NotFound, failure(run(Fake { FileResponse(404, errorBody = null) })))
        assertEquals(FileFailure.Unavailable, failure(run(Fake { FileResponse(410, errorBody = "x") })))
        assertEquals(FileFailure.RateLimited, failure(run(Fake { FileResponse(429) })))
        assertEquals(FileFailure.Server(500), failure(run(Fake { FileResponse(500, errorBody = "<html>") })))
        assertEquals(FileFailure.Server(204), failure(run(Fake { FileResponse(204) })))
        assertNull(FileDownloader.existing(root, token))
    }

    @Test fun shortBodyIsAFailureAndTheFolderIsGone() {
        val state = run(Fake { ok(ByteArray(50), length = 100) })
        assertEquals(FileFailure.ShortBody, failure(state))
        assertFalse(FileDownloader.dirFor(root, token).exists())
    }

    @Test fun longerThanContentLengthIsAFailureToo() {
        assertEquals(FileFailure.ShortBody, failure(run(Fake { ok(ByteArray(200), length = 100) })))
        assertFalse(FileDownloader.dirFor(root, token).exists())
    }

    private class Zeros(var left: Long) : InputStream() {
        var reads = 0
        override fun read(): Int = if (left-- > 0) 0 else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            reads++
            if (left <= 0) return -1
            val n = minOf(len.toLong(), left).toInt()
            left -= n
            return n
        }
    }

    @Test fun contentLengthOverTheCapIsRefusedWithoutReading() {
        val body = Zeros(10)
        val state = run(Fake { FileResponse(200, "application/zip", FileLinks.MAX_BYTES + 1, null, body) }, hint = "big.zip")
        assertEquals(FileFailure.TooLarge, failure(state))
        assertEquals(0, body.reads)
        assertFalse(FileDownloader.dirFor(root, token).exists())
    }

    @Test fun unknownLengthStopsAtTheCap() {
        val body = Zeros(FileLinks.MAX_BYTES + 5)
        val state = run(Fake { FileResponse(200, "application/zip", null, null, body) }, hint = "big.zip")
        assertEquals(FileFailure.TooLarge, failure(state))
        assertFalse(FileDownloader.dirFor(root, token).exists())
    }

    @Test fun exactlyTheCapIsAccepted() {
        val state = run(Fake { FileResponse(200, "application/zip", FileLinks.MAX_BYTES, null, Zeros(FileLinks.MAX_BYTES)) }, hint = "big.zip")
        assertEquals(FileLinks.MAX_BYTES, (state as FileState.Done).size)
    }

    @Test fun cancelDeletesThePartialFileAndPropagates() {
        var cancelled = false
        var self: Job? = null
        val stream = object : InputStream() {
            var n = 0
            override fun read(): Int = -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (n++ == 0) self!!.cancel()
                return if (n < 5) 100 else -1
            }
        }
        runBlocking {
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                self = kotlin.coroutines.coroutineContext[Job]
                try {
                    FileDownloader.download(token, "a.pdf", Fake { FileResponse(200, "application/pdf", 400, null, stream) }, root)
                } catch (e: CancellationException) {
                    cancelled = true
                    throw e
                }
            }
            job.join()
        }
        assertTrue(cancelled)
        assertFalse(FileDownloader.dirFor(root, token).exists())
    }

    @Test fun transportErrorsKeepTheirSpecificCause() {
        val refused = run(Fake { throw ConnectException("refused") })
        assertEquals(FileFailure.Transport(HermesError.BridgeRefused), failure(refused))
        val dropped = run(Fake {
            FileResponse(200, "application/pdf", 10, null, object : InputStream() {
                override fun read(): Int = throw java.io.EOFException()
            })
        })
        assertEquals(FileFailure.Transport(HermesError.BridgeLinkDropped), failure(dropped))
        assertFalse(FileDownloader.dirFor(root, token).exists())
        assertEquals(FileFailure.Transport(HermesError.BridgeUnreachable), failure(run(Fake { throw IOException("x") })))
    }

    @Test fun unwritableFolderIsAStorageFailure() {
        root.writeText("a file where the folder should be")
        assertEquals(FileFailure.Storage, failure(run(Fake { ok(ByteArray(3)) })))
    }

    @Test fun finishedFileIsFoundAgainAndPartialIsNot() {
        val done = run(Fake { ok(ByteArray(5), type = "application/pdf") }, hint = "a.pdf") as FileState.Done
        val again = FileDownloader.existing(root, token)!!
        assertEquals(done.file, again.file)
        assertEquals("application/pdf", again.mime)
        val other = "o".repeat(43)
        File(FileDownloader.dirFor(root, other).also { it.mkdirs() }, "download.part").writeText("half")
        assertNull(FileDownloader.existing(root, other))
    }

    @Test fun cleanupRemovesOnlyFoldersOlderThanTheLimit() {
        val old = File(root, "old").also { it.mkdirs(); File(it, "a.pdf").writeText("x") }
        val fresh = File(root, "fresh").also { it.mkdirs(); File(it, "b.pdf").writeText("x") }
        val kept = File(root, "kept").also { it.mkdirs(); File(it, "c.pdf").writeText("x") }
        val now = 1_000_000_000_000L
        val day = 24L * 3600 * 1000
        for (d in listOf(old, kept)) { d.listFiles()!!.forEach { it.setLastModified(now - day - 1000) }; d.setLastModified(now - day - 1000) }
        fresh.listFiles()!!.forEach { it.setLastModified(now - day + 60_000) }
        fresh.setLastModified(now - day + 60_000)
        assertEquals(1, FileDownloader.cleanup(root, now, keep = setOf(kept)))
        assertFalse(old.exists())
        assertTrue(fresh.exists())
        assertTrue(kept.exists())
        assertEquals(0, FileDownloader.cleanup(File(root, "missing"), now))
    }

    @Test fun managerRunsADownloadToDoneAndRehydrates() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO)
        val downloads = FileDownloads(root, scope, { Fake { ok(ByteArray(7), type = "application/pdf") } })
        downloads.start("bridge", FileLink(token, "a.pdf", "7 B"))
        val done = withTimeout(10_000) { downloads.states.first { it[token] is FileState.Done } }[token] as FileState.Done
        assertEquals(7L, done.size)
        val fresh = FileDownloads(root, scope, { null })
        fresh.probe(token)
        assertEquals(done.file, (fresh.states.value[token] as FileState.Done).file)
        scope.cancel()
    }

    @Test fun managerWithoutASourceFailsWithConnectionChangedAndCancelClearsState() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO)
        val downloads = FileDownloads(root, scope, { null })
        downloads.start("gone", FileLink(token, "a.pdf", null))
        val failed = withTimeout(10_000) { downloads.states.first { it[token] is FileState.Failed } }[token] as FileState.Failed
        assertEquals(FileFailure.Transport(HermesError.ConnectionChanged), failed.failure)
        downloads.cancel(token)
        assertNull(downloads.states.value[token])
        scope.cancel()
    }

    @Test fun cancelledJobCleanupLeavesANewerDownloadAlone() {
        val endless = object : InputStream() {
            override fun read(): Int = -1
            override fun read(b: ByteArray, off: Int, len: Int): Int { Thread.sleep(2); return 100 }
        }
        runBlocking {
            val old = launch(Dispatchers.IO) {
                FileDownloader.download(token, "a.pdf", Fake { FileResponse(200, "application/pdf", null, null, endless) }, root)
            }
            while (FileDownloader.dirFor(root, token).listFiles().isNullOrEmpty()) kotlinx.coroutines.yield()
            val newer = FileDownloader.download(token, "a.pdf", Fake { ok(ByteArray(10)) }, root)
            old.cancelAndJoin()
            val done = newer as FileState.Done
            assertTrue(done.file.exists())
            assertEquals(10L, done.file.length())
        }
    }
}
