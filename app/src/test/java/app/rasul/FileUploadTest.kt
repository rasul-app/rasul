package app.rasul

import app.rasul.bridge.BridgeEndpoint
import app.rasul.data.FileUploadLimits
import app.rasul.data.FileUploader
import app.rasul.data.HermesError
import app.rasul.data.HermesResult
import app.rasul.data.HermesTarget
import app.rasul.data.HttpUploadApi
import app.rasul.data.RunsClient
import app.rasul.data.UploadApi
import app.rasul.data.UploadException
import app.rasul.data.UploadFailure
import app.rasul.data.UploadSession
import app.rasul.data.UploadedFile
import app.rasul.data.chunkSizes
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest

private const val CHUNK = FileUploadLimits.CHUNK_BYTES
private const val MAX = FileUploadLimits.MAX_FILE_BYTES
private val UPLOAD_ID = "a".repeat(32)
private val FILE_ID = "b".repeat(32)

/** Zeros-and-pattern bytes of a given length without holding them in memory. */
private class PatternStream(private val length: Long) : InputStream() {
    private var pos = 0L
    override fun read(): Int = if (pos >= length) -1 else ((pos++ * 31) and 0xff).toInt()
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (pos >= length) return -1
        val n = minOf(len.toLong(), length - pos).toInt()
        for (i in 0 until n) b[off + i] = ((pos + i) * 31 and 0xff).toByte()
        pos += n
        return n
    }
}

private fun patternSha(length: Long): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val stream = PatternStream(length)
    val buffer = ByteArray(65536)
    while (true) {
        val n = stream.read(buffer, 0, buffer.size)
        if (n < 0) break
        digest.update(buffer, 0, n)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Records every call; failures are scripted per call. */
private class RecordingApi : UploadApi {
    val sizes = mutableListOf<Int>()
    val indexes = mutableListOf<Int>()
    val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
    var completeSha: String? = null
    val cancelled = mutableListOf<String>()
    var createError: UploadException? = null
    var chunkErrors = ArrayDeque<UploadException?>()
    var putCalls = 0
    var created: Triple<String, Long, String>? = null

    override suspend fun create(name: String, size: Long, type: String): UploadSession {
        createError?.let { throw it }
        created = Triple(name, size, type)
        return UploadSession(UPLOAD_ID, CHUNK)
    }

    override suspend fun putChunk(upload: String, index: Int, bytes: ByteArray, length: Int) {
        putCalls++
        chunkErrors.removeFirstOrNull()?.let { throw it }
        sizes += length
        indexes += index
        digest.update(bytes, 0, length)
    }

    override suspend fun complete(upload: String, sha256: String): UploadedFile {
        completeSha = sha256
        return UploadedFile(FILE_ID, "n", sizes.sumOf { it.toLong() })
    }

    override suspend fun cancel(upload: String) {
        cancelled += upload
    }
}

class FileUploadTest {
    private val closers = mutableListOf<() -> Unit>()

    @After fun cleanUp() = closers.forEach { it() }

    // ---- chunk splitting ----

    @Test fun smallFileIsOneChunkOfItsOwnSize() {
        assertEquals(listOf(1), chunkSizes(1))
        assertEquals(listOf(CHUNK - 1), chunkSizes((CHUNK - 1).toLong()))
    }

    @Test fun fileOfExactlyOneChunkIsOneFullChunk() {
        assertEquals(listOf(CHUNK), chunkSizes(CHUNK.toLong()))
    }

    @Test fun oneByteOverAChunkMakesAOneByteLastChunk() {
        assertEquals(listOf(CHUNK, 1), chunkSizes(CHUNK.toLong() + 1))
    }

    @Test fun fiftyMiBSplitsIntoSixtySixFullChunksAndAShorterLast() {
        val sizes = chunkSizes(MAX)
        assertEquals(67, sizes.size)
        assertTrue(sizes.dropLast(1).all { it == CHUNK })
        assertEquals((MAX - 66L * CHUNK).toInt(), sizes.last())
        assertEquals(MAX, sizes.sumOf { it.toLong() })
    }

    // ---- uploader ----

    private fun uploader(api: UploadApi, pauses: MutableList<Long> = mutableListOf()) = FileUploader(api) { pauses += it }

    @Test fun chunksAreSentInOrderWithTheExactSizesAndTheShaIsOverTheirBytes() = runBlocking {
        val size = CHUNK * 2L + 12345
        val api = RecordingApi()
        val progress = mutableListOf<Long>()
        var createdId: String? = null
        val done = uploader(api).upload("a.bin", size, "application/zip", { PatternStream(size) }, { createdId = it }, { progress += it })
        assertEquals(listOf(CHUNK, CHUNK, 12345), api.sizes)
        assertEquals(listOf(0, 1, 2), api.indexes)
        assertEquals(Triple("a.bin", size, "application/zip"), api.created)
        assertEquals(UPLOAD_ID, createdId)
        assertEquals(listOf(CHUNK.toLong(), CHUNK * 2L, size), progress)
        assertEquals(patternSha(size), api.completeSha)
        assertEquals(api.digest.digest().joinToString("") { "%02x".format(it) }, api.completeSha)
        assertEquals(FILE_ID, done.file)
    }

    @Test fun aFileOfExactlyFiftyMiBUploadsInSixtySevenChunks() = runBlocking {
        val api = RecordingApi()
        uploader(api).upload("big.bin", MAX, "application/octet-stream", { PatternStream(MAX) })
        assertEquals(chunkSizes(MAX), api.sizes)
        assertEquals(patternSha(MAX), api.completeSha)
    }

    @Test fun shaOfRealBytesMatchesAnIndependentDigest() = runBlocking {
        val bytes = ByteArray(CHUNK + 7) { (it % 251).toByte() }
        val api = RecordingApi()
        uploader(api).upload("x", bytes.size.toLong(), "t", { ByteArrayInputStream(bytes) })
        val expected = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(expected, api.completeSha)
    }

    private fun transient() = UploadException(UploadFailure.Network(HermesError.BridgeLinkDropped), transient = true)

    @Test fun aFailedChunkIsRetriedWithGrowingPausesAndThenSucceeds() = runBlocking {
        val api = RecordingApi().apply { chunkErrors = ArrayDeque(listOf(transient(), transient())) }
        val pauses = mutableListOf<Long>()
        uploader(api, pauses).upload("a", 10, "t", { PatternStream(10) })
        assertEquals(3, api.putCalls)
        assertEquals(listOf(1000L, 2000L), pauses)
        assertEquals(listOf(10), api.sizes)
    }

    @Test fun aChunkThatKeepsFailingIsTriedFourTimesThenTheUploadFails() = runBlocking {
        val api = RecordingApi().apply { chunkErrors = ArrayDeque(List(10) { transient() }) }
        val pauses = mutableListOf<Long>()
        try {
            uploader(api, pauses).upload("a", 10, "t", { PatternStream(10) })
            fail("expected failure")
        } catch (e: UploadException) {
            assertEquals(UploadFailure.Network(HermesError.BridgeLinkDropped), e.failure)
        }
        assertEquals(1 + FileUploadLimits.CHUNK_RETRIES, api.putCalls)
        assertEquals(listOf(1000L, 2000L, 4000L), pauses)
        assertNull(api.completeSha)
    }

    @Test fun aRefusedChunkIsNotRetried() = runBlocking {
        val api = RecordingApi().apply { chunkErrors = ArrayDeque(listOf(UploadException(UploadFailure.Rejected("chunk_out_of_order")))) }
        try {
            uploader(api).upload("a", 10, "t", { PatternStream(10) })
            fail("expected failure")
        } catch (e: UploadException) {
            assertEquals(UploadFailure.Rejected("chunk_out_of_order"), e.failure)
        }
        assertEquals(1, api.putCalls)
    }

    @Test fun sizeLimitsAreCheckedBeforeAnythingIsSent() = runBlocking {
        val api = RecordingApi()
        for ((size, expected) in listOf(0L to UploadFailure.Empty, MAX + 1 to UploadFailure.TooLarge)) {
            try {
                uploader(api).upload("a", size, "t", { PatternStream(size) })
                fail("expected failure")
            } catch (e: UploadException) {
                assertEquals(expected, e.failure)
            }
        }
        assertNull(api.created)
    }

    @Test fun aFileThatShrinksOrGrowsWhileReadIsUnreadable() = runBlocking {
        for (actual in listOf(5L, 15L)) {
            val api = RecordingApi()
            try {
                uploader(api).upload("a", 10, "t", { PatternStream(actual) })
                fail("expected failure")
            } catch (e: UploadException) {
                assertEquals(UploadFailure.Unreadable, e.failure)
            }
            assertNull(api.completeSha)
        }
    }

    // ---- HTTP routes ----

    private class Seen(val request: Request, val body: ByteArray)

    private fun fakeBridge(vararg replies: Pair<Int, String>): Triple<HttpUploadApi, MutableList<Seen>, OkHttpClient> {
        val seen = mutableListOf<Seen>()
        val queue = ArrayDeque(replies.toList())
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val buffer = okio.Buffer()
            request.body?.writeTo(buffer)
            seen += Seen(request, buffer.readByteArray())
            val (code, body) = queue.removeFirst()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("")
                .body(body.toResponseBody()).build()
        }.build()
        closers += { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        return Triple(HttpUploadApi(BridgeEndpoint(client, "default")), seen, client)
    }

    private suspend fun failureOf(block: suspend () -> Unit): UploadException = try {
        block()
        fail("expected failure")
        throw AssertionError()
    } catch (e: UploadException) {
        e
    }

    @Test fun createPostsTheDescriptionToTheProfileRoute() = runBlocking {
        val (api, seen, _) = fakeBridge(201 to """{"upload":"$UPLOAD_ID","chunk_size":786432}""")
        val session = api.create("report.pdf", 1234, "application/pdf")
        assertEquals(UploadSession(UPLOAD_ID, 786432), session)
        val request = seen.single().request
        assertEquals("POST", request.method)
        assertEquals("/v1/profiles/default/uploads", request.url.encodedPath)
        assertEquals("""{"name":"report.pdf","size":1234,"type":"application/pdf"}""", String(seen.single().body))
    }

    @Test fun chunkIsPutAsRawBytesToItsIndex() = runBlocking {
        val (api, seen, _) = fakeBridge(204 to "")
        api.putChunk(UPLOAD_ID, 3, byteArrayOf(1, 2, 3, 9, 9), length = 3)
        val request = seen.single().request
        assertEquals("PUT", request.method)
        assertEquals("/v1/uploads/$UPLOAD_ID/chunks/3", request.url.encodedPath)
        assertEquals("application/octet-stream", request.body!!.contentType().toString())
        assertArrayEquals(byteArrayOf(1, 2, 3), seen.single().body)
    }

    @Test fun completeSendsTheChecksumAndReadsTheFileId() = runBlocking {
        val (api, seen, _) = fakeBridge(200 to """{"file":"$FILE_ID","name":"report.pdf","size":1234}""")
        val file = api.complete(UPLOAD_ID, "c".repeat(64))
        assertEquals(UploadedFile(FILE_ID, "report.pdf", 1234), file)
        assertEquals("/v1/uploads/$UPLOAD_ID/complete", seen.single().request.url.encodedPath)
        assertEquals("""{"sha256":"${"c".repeat(64)}"}""", String(seen.single().body))
    }

    @Test fun cancelSendsDelete() = runBlocking {
        val (api, seen, _) = fakeBridge(204 to "")
        api.cancel(UPLOAD_ID)
        assertEquals("DELETE", seen.single().request.method)
        assertEquals("/v1/uploads/$UPLOAD_ID", seen.single().request.url.encodedPath)
    }

    @Test fun createErrorsMapToTheirOwnFailure() = runBlocking {
        val cases = listOf(
            404 to """{"error":"Route not found"}""" to UploadFailure.OldBridge,
            404 to """{"error":"Profile not found"}""" to UploadFailure.ProfileMissing,
            404 to "" to UploadFailure.Rejected(null),
            500 to """{"error":"upload_failed"}""" to UploadFailure.SaveFailed,
            413 to """{"error":"file_too_large"}""" to UploadFailure.TooLarge,
            429 to """{"error":"too_many_uploads"}""" to UploadFailure.TooManyUploads,
            507 to """{"error":"disk_full"}""" to UploadFailure.DiskFull,
            400 to """{"error":"invalid_upload"}""" to UploadFailure.Rejected("invalid_upload"),
            403 to "{}" to UploadFailure.Network(HermesError.DeviceRevoked),
            503 to "{}" to UploadFailure.Network(HermesError.ProfileNotReady),
        )
        for ((reply, expected) in cases) {
            val (api, _, _) = fakeBridge(reply)
            assertEquals("${reply.first}", expected, failureOf { api.create("a", 1, "t") }.failure)
        }
    }

    @Test fun aSaveFailureIsNotRetriedAndOtherServerErrorsAre() = runBlocking {
        val (api, _, _) = fakeBridge(500 to """{"error":"upload_failed"}""", 500 to """{"error":"other"}""", 502 to "")
        val save = failureOf { api.putChunk(UPLOAD_ID, 0, ByteArray(1)) }
        assertEquals(UploadFailure.SaveFailed, save.failure)
        assertFalse(save.transient)
        assertTrue(failureOf { api.putChunk(UPLOAD_ID, 0, ByteArray(1)) }.transient)
        assertTrue(failureOf { api.putChunk(UPLOAD_ID, 0, ByteArray(1)) }.transient)
    }

    @Test fun chunkAndCompleteErrorsMapToRejectedWithTheirCode() = runBlocking {
        val (api, _, _) = fakeBridge(
            404 to """{"error":"upload_not_found"}""",
            409 to """{"error":"chunk_out_of_order"}""",
            400 to """{"error":"checksum_mismatch"}""",
            409 to """{"error":"upload_incomplete"}""",
        )
        assertEquals(UploadFailure.Rejected("upload_not_found"), failureOf { api.putChunk(UPLOAD_ID, 0, ByteArray(1)) }.failure)
        assertEquals(UploadFailure.Rejected("chunk_out_of_order"), failureOf { api.putChunk(UPLOAD_ID, 1, ByteArray(1)) }.failure)
        assertEquals(UploadFailure.Rejected("checksum_mismatch"), failureOf { api.complete(UPLOAD_ID, "d".repeat(64)) }.failure)
        assertEquals(UploadFailure.Rejected("upload_incomplete"), failureOf { api.complete(UPLOAD_ID, "d".repeat(64)) }.failure)
    }

    @Test fun serverErrorOnAChunkIsTransientButARefusalIsNot() = runBlocking {
        val (api, _, _) = fakeBridge(502 to "", 409 to """{"error":"chunk_out_of_order"}""")
        assertTrue(failureOf { api.putChunk(UPLOAD_ID, 0, ByteArray(1)) }.transient)
        assertFalse(failureOf { api.putChunk(UPLOAD_ID, 0, ByteArray(1)) }.transient)
    }

    @Test fun aDroppedConnectionIsATransientNetworkFailure() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { throw java.io.IOException("reset") }.build()
        closers += { client.dispatcher.executorService.shutdown() }
        val e = failureOf { HttpUploadApi(BridgeEndpoint(client, "default")).putChunk(UPLOAD_ID, 0, ByteArray(1)) }
        assertTrue(e.failure is UploadFailure.Network)
        assertTrue(e.transient)
    }

    // ---- run body ----

    private fun runClient(vararg replies: Pair<Int, String>): Triple<RunsClient, MutableList<Seen>, HermesTarget> {
        val (_, seen, client) = fakeBridge(*replies)
        return Triple(RunsClient(), seen, HermesTarget("", null, "", BridgeEndpoint(client, "default")))
    }

    @Test fun runBodyCarriesRasulFilesAndNothingElseChanges() = runBlocking {
        val (runs, seen, target) = runClient(202 to """{"run_id":"r1"}""", 202 to """{"run_id":"r2"}""")
        assertTrue(runs.startRun(target, JsonPrimitive("hi"), "s1", "k1") is HermesResult.Ok)
        assertTrue(runs.startRun(target, JsonPrimitive("hi"), "s1", "k2", listOf(FILE_ID, "c".repeat(32))) is HermesResult.Ok)
        val plain = String(seen[0].body)
        val withFiles = String(seen[1].body)
        assertEquals("""{"input":"hi","session_id":"s1"}""", plain)
        assertEquals("""{"input":"hi","session_id":"s1","rasul_files":["$FILE_ID","${"c".repeat(32)}"]}""", withFiles)
    }

    @Test fun aRefusedRunBodyIsReportedWithItsCode() = runBlocking {
        val (runs, _, target) = runClient(400 to """{"error":"invalid_run_input"}""", 400 to """{"error":"something_else"}""", 429 to "")
        assertEquals(HermesResult.Err(HermesError.Server(400, null, "invalid_run_input")), runs.startRun(target, JsonPrimitive("hi"), "s1", "k1", listOf(FILE_ID)))
        assertEquals(HermesResult.Err(HermesError.BridgeUnreachable), runs.startRun(target, JsonPrimitive("hi"), "s1", "k2", listOf(FILE_ID)))
        assertEquals(HermesResult.Err(HermesError.BridgeUnreachable), runs.startRun(target, JsonPrimitive("hi"), "s1", "k3"))
    }

    @Test fun missingFileAtSendIsReportedWithItsCode() = runBlocking {
        val (runs, _, target) = runClient(400 to """{"error":"file_unavailable"}""")
        val result = runs.startRun(target, JsonPrimitive("hi"), "s1", "k1", listOf(FILE_ID))
        assertEquals(HermesResult.Err(HermesError.Server(400, null, "file_unavailable")), result)
    }
}
