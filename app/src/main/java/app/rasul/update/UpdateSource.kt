package app.rasul.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** What the update flow needs from the network; faked in tests. */
interface UpdateSource {
    suspend fun fetchReleases(): List<GithubRelease>
    suspend fun fetchChecksum(url: String): String

    /** Streams [url] into [dest] and returns the lowercase SHA-256 of the bytes written. */
    suspend fun download(url: String, dest: File, onProgress: (read: Long, total: Long) -> Unit): String
}

/** Hosts the update flow may reach: the API, the release download address and its two storage redirects. */
fun isAllowedUpdateHost(url: HttpUrl): Boolean = url.isHttps && url.host in setOf(
    "api.github.com", "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com",
)

private const val MAX_REDIRECTS = 5

class HttpUpdateSource(
    base: OkHttpClient = OkHttpClient(),
    private val releasesUrl: String = RELEASES_URL,
    private val versionName: String = "",
    private val hostAllowed: (HttpUrl) -> Boolean = ::isAllowedUpdateHost,
    private val assetAllowed: (String) -> Boolean = ::isAllowedAssetUrl,
    private val maxApkBytes: Long = MAX_APK_BYTES,
) : UpdateSource {
    // Redirects are followed by hand so every hop is checked before any connection to it is opened.
    private val plain = base.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val checkClient = plain.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
    private val downloadClient = plain.newBuilder().callTimeout(0, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    private fun request(url: HttpUrl) = Request.Builder().url(url)
        .header("User-Agent", "Rasul/$versionName").header("Accept", "application/vnd.github+json").build()

    private fun fail(error: UpdateError): Nothing = throw UpdateException(error)

    /** Opens [url], following at most [MAX_REDIRECTS] redirects, each to an allowed host. [track] receives every call. */
    private fun open(client: OkHttpClient, url: String, track: (Call) -> Unit): Response {
        var current = url.toHttpUrlOrNull() ?: fail(UpdateError.BadResponse)
        repeat(MAX_REDIRECTS + 1) {
            if (!hostAllowed(current)) fail(UpdateError.BadResponse)
            val call = client.newCall(request(current))
            track(call)
            val response = try {
                call.execute()
            } catch (e: IOException) {
                fail(UpdateError.Offline)
            }
            if (!response.isRedirect) return response
            val location = response.header("Location")
            response.close()
            current = location?.let { current.resolve(it) } ?: fail(UpdateError.BadResponse)
        }
        fail(UpdateError.BadResponse)
    }

    private fun checkStatus(response: Response) {
        val code = response.code
        if (code == 403 || code == 429) fail(UpdateError.RateLimited)
        if (!response.isSuccessful) fail(UpdateError.Http(code))
    }

    private inline fun <T> reading(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        fail(UpdateError.Offline)
    }

    private fun readCapped(body: ResponseBody?, cap: Long): String {
        val source = body?.source() ?: fail(UpdateError.BadResponse)
        if (body.contentLength() > cap) fail(UpdateError.BadResponse)
        val buffer = Buffer()
        while (buffer.size <= cap) if (source.read(buffer, 8192) == -1L) break
        if (buffer.size > cap) fail(UpdateError.BadResponse)
        return buffer.readUtf8()
    }

    override suspend fun fetchReleases(): List<GithubRelease> = withContext(Dispatchers.IO) {
        var current: Call? = null
        coroutineContext.job.invokeOnCompletion { current?.cancel() }
        open(checkClient, releasesUrl) { current = it }.use { response ->
            checkStatus(response)
            reading { parseReleases(readCapped(response.body, MAX_RELEASES_BODY_BYTES)) } ?: fail(UpdateError.BadResponse)
        }
    }

    override suspend fun fetchChecksum(url: String): String = withContext(Dispatchers.IO) {
        if (!assetAllowed(url)) fail(UpdateError.BadResponse)
        var current: Call? = null
        coroutineContext.job.invokeOnCompletion { current?.cancel() }
        open(checkClient, url) { current = it }.use { response ->
            checkStatus(response)
            reading { readCapped(response.body, MAX_CHECKSUM_BYTES) }
        }
    }

    override suspend fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit): String = withContext(Dispatchers.IO) {
        if (!assetAllowed(url)) fail(UpdateError.BadResponse)
        var current: Call? = null
        coroutineContext.job.invokeOnCompletion { current?.cancel() }
        open(downloadClient, url) { current = it }.use { response ->
            checkStatus(response)
            val body = response.body ?: fail(UpdateError.BadResponse)
            val total = body.contentLength()
            if (total > maxApkBytes) fail(UpdateError.TooLarge)
            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L
            dest.parentFile?.mkdirs()
            reading {
                dest.outputStream().use { out ->
                    val input = body.byteStream()
                    val chunk = ByteArray(16 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(chunk)
                        if (n < 0) break
                        read += n
                        if (read > maxApkBytes) fail(UpdateError.TooLarge)
                        digest.update(chunk, 0, n)
                        out.write(chunk, 0, n)
                        onProgress(read, total)
                    }
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
