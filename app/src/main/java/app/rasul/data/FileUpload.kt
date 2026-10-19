package app.rasul.data

import app.rasul.bridge.BridgeEndpoint
import app.rasul.bridge.BridgeTransport
import app.rasul.bridge.bridgeError
import app.rasul.bridge.bridgeResponse
import app.rasul.bridge.bridgeStreamClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Limits the bridge enforces for files sent to the agent; the app checks them first to name the file at fault. */
object FileUploadLimits {
    const val MAX_FILES = 4
    const val MAX_FILE_BYTES = 50L * 1024 * 1024

    /** Size of every chunk but the last; the bridge answers it in `chunk_size` and rejects any other size. */
    const val CHUNK_BYTES = 786_432

    /** A failed chunk is sent again this many times (with a growing pause) before the upload is called failed. */
    const val CHUNK_RETRIES = 3
}

/** Why one upload stopped. Each case has its own text; [Network] carries the transport cause. */
sealed interface UploadFailure {
    /** The bridge has no upload route: it is older than this feature. */
    data object OldBridge : UploadFailure

    /** The bridge does not have this bot (its folder was removed on the PC). */
    data object ProfileMissing : UploadFailure

    /** The bridge could not write the file on the PC (`upload_failed`); sending it again may work. */
    data object SaveFailed : UploadFailure
    data object TooLarge : UploadFailure
    data object Empty : UploadFailure
    data object TooManyUploads : UploadFailure
    data object DiskFull : UploadFailure

    /** The file could not be read from the phone, or changed while it was read. */
    data object Unreadable : UploadFailure

    /** The bridge refused the upload for another reason (unknown upload, bad chunk, checksum); a new upload may work. */
    data class Rejected(val code: String?) : UploadFailure
    data class Network(val error: HermesError) : UploadFailure
}

class UploadException(val failure: UploadFailure, val transient: Boolean = false) : Exception(failure.toString())

data class UploadSession(val upload: String, val chunkSize: Int)
data class UploadedFile(val file: String, val name: String, val size: Long)

/** The bridge's upload routes. Every method throws [UploadException]. */
interface UploadApi {
    suspend fun create(name: String, size: Long, type: String): UploadSession
    suspend fun putChunk(upload: String, index: Int, bytes: ByteArray, length: Int = bytes.size)
    suspend fun complete(upload: String, sha256: String): UploadedFile
    suspend fun cancel(upload: String)
}

/** The upload routes of the bridge a chat target belongs to, or null for a direct connection. */
fun HermesTarget.uploadApi(): UploadApi? = bridge?.let { HttpUploadApi(it) }

@Serializable
private data class CreatedDto(val upload: String, @SerialName("chunk_size") val chunkSize: Int)

@Serializable
private data class CompletedDto(val file: String, val name: String = "", val size: Long = 0)

@Serializable
private data class ErrorDto(val error: String? = null)

private val uploadJson = Json { ignoreUnknownKeys = true }
private val hexId = Regex("[0-9a-f]{32}")

private fun errorCode(body: String): String? = try {
    uploadJson.decodeFromString<ErrorDto>(body).error
} catch (_: Exception) {
    null
}

/**
 * Maps a failed reply to one failure. On the create route a 404 is an older bridge when it answers "Route not found"
 * (the route does not exist), and a missing bot when it answers "Profile not found" (the route exists, the bot does not).
 */
internal fun uploadFailure(status: Int, body: String, creating: Boolean): UploadException {
    val code = errorCode(body)
    return when {
        status == 403 -> UploadException(UploadFailure.Network(HermesError.DeviceRevoked))
        status == 503 -> UploadException(UploadFailure.Network(HermesError.ProfileNotReady))
        creating && status == 404 && code == "Route not found" -> UploadException(UploadFailure.OldBridge)
        creating && status == 404 && code == "Profile not found" -> UploadException(UploadFailure.ProfileMissing)
        status == 500 && code == "upload_failed" -> UploadException(UploadFailure.SaveFailed)
        status == 413 -> UploadException(UploadFailure.TooLarge)
        status == 429 -> UploadException(UploadFailure.TooManyUploads)
        status == 507 -> UploadException(UploadFailure.DiskFull)
        status >= 500 -> UploadException(UploadFailure.Network(HermesError.BridgeUnreachable), transient = true)
        else -> UploadException(UploadFailure.Rejected(code))
    }
}

class HttpUploadApi(private val endpoint: BridgeEndpoint) : UploadApi {
    private val jsonType = "application/json".toMediaType()
    private val binaryType = "application/octet-stream".toMediaType()
    private val client: OkHttpClient get() = endpoint.client

    private fun base() = BridgeTransport.origin.newBuilder().addPathSegments("v1")

    private suspend fun <T> call(
        client: OkHttpClient,
        request: Request,
        creating: Boolean,
        ok: Set<Int>,
        read: (String) -> T,
    ): T {
        try {
            return client.bridgeResponse(request) { response ->
                val text = response.body?.string().orEmpty()
                if (response.code !in ok) throw uploadFailure(response.code, text, creating)
                try {
                    read(text)
                } catch (_: Exception) {
                    throw UploadException(UploadFailure.Network(HermesError.BridgeResponseInvalid))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: UploadException) {
            throw e
        } catch (e: IOException) {
            throw UploadException(UploadFailure.Network(bridgeError(e)), transient = true)
        }
    }

    override suspend fun create(name: String, size: Long, type: String): UploadSession {
        val url = base().addPathSegment("profiles").addPathSegment(endpoint.profileId).addPathSegment("uploads").build()
        val body = buildJsonObject {
            put("name", name)
            put("size", size)
            put("type", type)
        }.toString()
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .post(body.toRequestBody(jsonType)).build()
        return call(client, request, creating = true, ok = setOf(201)) { text ->
            val dto = uploadJson.decodeFromString<CreatedDto>(text)
            require(hexId.matches(dto.upload) && dto.chunkSize > 0)
            UploadSession(dto.upload, dto.chunkSize)
        }
    }

    override suspend fun putChunk(upload: String, index: Int, bytes: ByteArray, length: Int) {
        val url = base().addPathSegment("uploads").addPathSegment(upload).addPathSegment("chunks")
            .addPathSegment(index.toString()).build()
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .put(bytes.toRequestBody(binaryType, 0, length)).build()
        // No whole-call deadline: a 768 KiB chunk on a slow link may take longer than the 30 s the small JSON calls get.
        call(client.bridgeStreamClient(), request, creating = false, ok = setOf(204)) { }
    }

    override suspend fun complete(upload: String, sha256: String): UploadedFile {
        val url = base().addPathSegment("uploads").addPathSegment(upload).addPathSegment("complete").build()
        val body = buildJsonObject { put("sha256", sha256) }.toString()
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .post(body.toRequestBody(jsonType)).build()
        return call(client, request, creating = false, ok = setOf(200)) { text ->
            val dto = uploadJson.decodeFromString<CompletedDto>(text)
            require(hexId.matches(dto.file))
            UploadedFile(dto.file, dto.name, dto.size)
        }
    }

    override suspend fun cancel(upload: String) {
        val url = base().addPathSegment("uploads").addPathSegment(upload).build()
        val request = Request.Builder().url(url).header("Accept", "application/json").delete().build()
        call(client, request, creating = false, ok = setOf(204)) { }
    }
}

/** The sizes of the chunks a file of [size] bytes is sent in: all [chunk] bytes but a shorter last one. */
fun chunkSizes(size: Long, chunk: Int = FileUploadLimits.CHUNK_BYTES): List<Int> {
    require(size >= 0 && chunk > 0)
    val full = (size / chunk).toInt()
    val rest = (size % chunk).toInt()
    return List(full) { chunk } + listOfNotNull(rest.takeIf { it > 0 })
}

/**
 * Sends one file through [api]: create, the chunks in order (SHA-256 computed over the same bytes as they are read,
 * one chunk in memory at a time), complete. A chunk that fails for a transient reason is sent again up to
 * [FileUploadLimits.CHUNK_RETRIES] times, pausing 1 s, 2 s, 4 s in between ([pause] is replaceable for tests).
 */
class FileUploader(
    private val api: UploadApi,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    /**
     * [open] gives a fresh stream of the file. [onCreated] reports the upload id as soon as the bridge has made it,
     * so a cancel can delete it. [onProgress] gets the bytes accepted so far.
     */
    suspend fun upload(
        name: String,
        size: Long,
        type: String,
        open: () -> InputStream?,
        onCreated: (String) -> Unit = {},
        onProgress: (Long) -> Unit = {},
    ): UploadedFile {
        if (size <= 0) throw UploadException(UploadFailure.Empty)
        if (size > FileUploadLimits.MAX_FILE_BYTES) throw UploadException(UploadFailure.TooLarge)
        val session = api.create(name, size, type)
        onCreated(session.upload)
        val digest = MessageDigest.getInstance("SHA-256")
        val stream = try {
            open()
        } catch (e: IOException) {
            null
        } ?: throw UploadException(UploadFailure.Unreadable)
        stream.use { input ->
            val buffer = ByteArray(session.chunkSize)
            var sent = 0L
            var index = 0
            for (expected in chunkSizes(size, session.chunkSize)) {
                val read = try {
                    readFully(input, buffer, expected)
                } catch (e: IOException) {
                    throw UploadException(UploadFailure.Unreadable)
                }
                if (read != expected) throw UploadException(UploadFailure.Unreadable)
                digest.update(buffer, 0, read)
                putWithRetry(session.upload, index, buffer, read)
                sent += read
                index++
                onProgress(sent)
            }
            // A file that grew while it was read no longer matches the size the bridge was told.
            if (input.read() >= 0) throw UploadException(UploadFailure.Unreadable)
        }
        return api.complete(session.upload, digest.digest().joinToString("") { "%02x".format(it) })
    }

    private suspend fun putWithRetry(upload: String, index: Int, bytes: ByteArray, length: Int) {
        var attempt = 0
        while (true) {
            try {
                api.putChunk(upload, index, bytes, length)
                return
            } catch (e: UploadException) {
                if (!e.transient || attempt >= FileUploadLimits.CHUNK_RETRIES) throw e
                pause(1000L shl attempt)
                attempt++
            }
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray, wanted: Int): Int {
        var total = 0
        while (total < wanted) {
            val n = input.read(buffer, total, wanted - total)
            if (n < 0) break
            total += n
        }
        return total
    }
}
