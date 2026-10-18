package app.rasul.files

import app.rasul.bridge.BridgeTransport
import app.rasul.bridge.bridgeError
import app.rasul.bridge.bridgeResponse
import app.rasul.bridge.bridgeStreamClient
import app.rasul.data.HermesError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.security.MessageDigest

/** What the bridge answered to `GET /v1/files/<token>` (docs/bridge-files.md). [errorBody] is set for non-200 answers. */
class FileResponse(
    val status: Int,
    val contentType: String? = null,
    val contentLength: Long? = null,
    val disposition: String? = null,
    val body: InputStream = InputStream.nullInputStream(),
    val errorBody: String? = null,
)

/** One download request. The response is closed when [read] returns. */
interface FileSource {
    suspend fun <T> fetch(token: String, read: suspend (FileResponse) -> T): T
}

class BridgeFileSource(private val client: OkHttpClient) : FileSource {
    override suspend fun <T> fetch(token: String, read: suspend (FileResponse) -> T): T {
        val url = BridgeTransport.origin.newBuilder().addPathSegments("v1/files").addPathSegment(token).build()
        // identity: a compressed body would hide the real Content-Length that the short-body check needs.
        val request = Request.Builder().url(url).header("Accept", "*/*").header("Accept-Encoding", "identity").build()
        // A file can take longer than the 30 s call timeout; the 45 s read timeout still ends a stalled link.
        return client.bridgeStreamClient().bridgeResponse(request) { response ->
            val body = response.body
            if (response.code == 200 && body != null) {
                read(FileResponse(200, response.header("Content-Type"), body.contentLength().takeIf { it >= 0 },
                    response.header("Content-Disposition"), body.byteStream()))
            } else {
                read(FileResponse(response.code, errorBody = body?.source()?.let { runCatching { it.readUtf8(MAX_ERROR_BODY) }.getOrNull() }))
            }
        }
    }

    private companion object { const val MAX_ERROR_BODY = 4096L }
}

sealed interface FileFailure {
    data object NotFound : FileFailure
    data object Expired : FileFailure
    data object Changed : FileFailure
    data object Unavailable : FileFailure
    data object RateLimited : FileFailure
    data object Forbidden : FileFailure
    data object TooLarge : FileFailure

    /** Fewer (or more) bytes arrived than Content-Length promised; the partial file is deleted. */
    data object ShortBody : FileFailure
    data object Storage : FileFailure
    data class Server(val code: Int) : FileFailure
    data class Transport(val error: HermesError) : FileFailure
}

sealed interface FileState {
    data object Idle : FileState
    data class Downloading(val done: Long, val total: Long?) : FileState
    data class Done(val file: File, val name: String, val mime: String, val size: Long) : FileState
    data class Failed(val failure: FileFailure) : FileState
}

object FileDownloader {
    private const val CHUNK = 64 * 1024
    private const val PART = "download.part"

    /** Folder of one token under [root]: a hash, never the token or the (attacker-chosen) name. */
    fun dirFor(root: File, token: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(root, hash.take(32))
    }

    internal fun failureFor(status: Int, body: String?): FileFailure {
        val code = body?.let { Regex(""""error"\s*:\s*"([^"]{1,64})"""").find(it)?.groupValues?.get(1) }
        return when (code) {
            "file_not_found" -> FileFailure.NotFound
            "file_expired" -> FileFailure.Expired
            "file_changed" -> FileFailure.Changed
            "file_unavailable" -> FileFailure.Unavailable
            "file_rate_limited" -> FileFailure.RateLimited
            else -> when (status) {
                403 -> FileFailure.Forbidden
                404 -> FileFailure.NotFound
                410 -> FileFailure.Unavailable
                429 -> FileFailure.RateLimited
                else -> FileFailure.Server(status)
            }
        }
    }

    /** The file name from Content-Disposition (`filename*` first), or null. Not yet sanitised. */
    internal fun dispositionName(header: String?): String? {
        if (header == null) return null
        Regex("""filename\*\s*=\s*UTF-8''([^;]+)""", RegexOption.IGNORE_CASE).find(header)?.let {
            runCatching { URLDecoder.decode(it.groupValues[1].trim().replace("+", "%2B"), "UTF-8") }.getOrNull()?.let { n -> return n }
        }
        return Regex("""filename\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)
    }

    /**
     * Downloads [token] into its folder under [root] and returns Done or Failed. Streams to `download.part` and
     * renames only when the whole body arrived, so a final file always means a complete one. Cancellation
     * (CancellationException) deletes the partial file and propagates.
     */
    suspend fun download(
        token: String,
        nameHint: String,
        source: FileSource,
        root: File,
        onProgress: (done: Long, total: Long?) -> Unit = { _, _ -> },
    ): FileState {
        val dir = dirFor(root, token)
        return try {
            source.fetch(token) { response -> receive(response, nameHint, dir, onProgress) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            FileState.Failed(FileFailure.Transport(bridgeError(e)))
        }
    }

    private suspend fun receive(response: FileResponse, nameHint: String, dir: File, onProgress: (Long, Long?) -> Unit): FileState {
        if (response.status != 200) return FileState.Failed(failureFor(response.status, response.errorBody))
        val total = response.contentLength?.takeIf { it >= 0 }
        if (total != null && total > FileLinks.MAX_BYTES) return FileState.Failed(FileFailure.TooLarge)
        val name = FileLinks.sanitiseName(dispositionName(response.disposition)?.takeIf { it.isNotBlank() } ?: nameHint)
        // Per-attempt folder: a cancelled job cleans up only its own, never a newer job's files in [dir].
        if (!dir.mkdirs() && !dir.isDirectory) return FileState.Failed(FileFailure.Storage)
        dir.listFiles()?.forEach { if (it.isFile) it.delete() }
        val attempt = File(dir, ".a" + System.nanoTime())
        if (!attempt.mkdirs()) return FileState.Failed(FileFailure.Storage)
        val part = File(attempt, PART)
        var finished = false
        try {
            var count = 0L
            val buffer = ByteArray(CHUNK)
            var writing = true // opening the file counts as a storage step
            try {
                part.outputStream().use { out ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        writing = false
                        val n = response.body.read(buffer)
                        if (n < 0) break
                        count += n
                        if (count > FileLinks.MAX_BYTES) return FileState.Failed(FileFailure.TooLarge)
                        if (total != null && count > total) return FileState.Failed(FileFailure.ShortBody)
                        writing = true
                        out.write(buffer, 0, n)
                        onProgress(count, total)
                    }
                }
            } catch (e: IOException) {
                return FileState.Failed(if (writing) FileFailure.Storage else FileFailure.Transport(bridgeError(e)))
            }
            if (total != null && count != total) return FileState.Failed(FileFailure.ShortBody)
            val final = File(dir, name)
            if (!part.renameTo(final)) return FileState.Failed(FileFailure.Storage)
            finished = true
            attempt.deleteRecursively()
            return FileState.Done(final, name, FileLinks.mimeFor(response.contentType, name), count)
        } finally {
            if (!finished) {
                attempt.deleteRecursively()
                dir.delete() // only succeeds when empty
            }
        }
    }

    /** A completed download left on disk by an earlier run, or null. */
    fun existing(root: File, token: String): FileState.Done? {
        val dir = dirFor(root, token)
        val file = dir.listFiles()?.firstOrNull { it.isFile && it.name != PART } ?: return null
        return FileState.Done(file, file.name, FileLinks.mimeFor(null, file.name), file.length())
    }

    /** Deletes received folders not touched for [maxAgeMs], except those named in [keep]. Returns how many went. */
    fun cleanup(root: File, nowMs: Long, maxAgeMs: Long = 24L * 3600 * 1000, keep: Set<File> = emptySet()): Int {
        var removed = 0
        root.listFiles()?.forEach { dir ->
            if (dir in keep) return@forEach
            val newest = (dir.listFiles()?.maxOfOrNull { it.lastModified() } ?: 0L).coerceAtLeast(dir.lastModified())
            if (nowMs - newest > maxAgeMs && dir.deleteRecursively()) removed++
        }
        return removed
    }
}

/** App-level owner of running downloads. State survives screen changes; a card only observes [states]. */
class FileDownloads(
    private val root: File,
    private val scope: CoroutineScope,
    private val sourceFor: suspend (bridgeId: String) -> FileSource?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    val states = MutableStateFlow<Map<String, FileState>>(emptyMap())
    private val jobs = mutableMapOf<String, Job>()

    fun start(bridgeId: String, link: FileLink) {
        synchronized(this) {
            if (jobs[link.token]?.isActive == true) return
            states.update { it + (link.token to FileState.Downloading(0, null)) }
            jobs[link.token] = scope.launch {
                val source = sourceFor(bridgeId)
                val result = if (source == null) FileState.Failed(FileFailure.Transport(HermesError.ConnectionChanged))
                else FileDownloader.download(link.token, link.name, source, root) { done, total ->
                    states.update { it + (link.token to FileState.Downloading(done, total)) }
                }
                states.update { it + (link.token to result) }
            }
        }
    }

    fun cancel(token: String) {
        synchronized(this) { jobs.remove(token)?.cancel() }
        states.update { it - token }
    }

    /** Fills in a finished file from an earlier run so a reloaded chat shows Open/Save/Share at once. */
    fun probe(token: String) {
        if (states.value.containsKey(token)) return
        FileDownloader.existing(root, token)?.let { done -> states.update { if (token in it) it else it + (token to done) } }
    }

    fun cleanup(): Int {
        val active = synchronized(this) { jobs.filterValues { it.isActive }.keys }
        return FileDownloader.cleanup(root, now(), keep = active.map { FileDownloader.dirFor(root, it) }.toSet())
    }
}
