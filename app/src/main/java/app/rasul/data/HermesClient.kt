package app.rasul.data

import app.rasul.bridge.send
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class HermesClient(
    base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
) {
    // Redirects are never followed, whatever client was passed in: one could leave the address policy.
    private val http = base.newBuilder().followRedirects(false).followSslRedirects(false).build()

    private val json = Json { ignoreUnknownKeys = true }

    /** GET /v1/capabilities. Err(Incompatible) when the body is not Hermes or lacks a required feature. */
    suspend fun capabilities(target: HermesTarget): HermesResult<CapabilitiesDto> {
        val body = when (val r = get(target, "v1/capabilities")) {
            is HermesResult.Err -> return r
            is HermesResult.Ok -> r.value
        }
        val caps = try {
            json.decodeFromString<CapabilitiesDto>(body)
        } catch (e: Exception) {
            return HermesResult.Err(HermesError.Incompatible(listOf("capabilities")))
        }
        if (caps.objectType != CapabilitiesDto.OBJECT_TYPE) {
            return HermesResult.Err(HermesError.Incompatible(listOf("capabilities")))
        }
        val missing = CapabilitiesDto.REQUIRED_FEATURES - caps.enabledFeatures()
        return if (missing.isEmpty()) HermesResult.Ok(caps) else HermesResult.Err(HermesError.Incompatible(missing))
    }

    /** The server's error text: flat `{"error": "..."}` (jobs) or nested `{"error": {"message": "..."}}` (sessions, api_server.py:908). */
    private fun errorMessage(body: String): String? = try {
        when (val error = (json.parseToJsonElement(body) as? JsonObject)?.get("error")) {
            is JsonPrimitive -> error.contentOrNull
            is JsonObject -> (error["message"] as? JsonPrimitive)?.contentOrNull
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val PROFILE_UNKNOWN_MESSAGE = "Unknown or unconfigured profile"
    }

    /** GET /health. Ok when the server answers 2xx. */
    suspend fun health(target: HermesTarget): HermesResult<Unit> =
        when (val r = get(target, "health")) {
            is HermesResult.Err -> r
            is HermesResult.Ok -> HermesResult.Ok(Unit)
        }

    private suspend fun get(target: HermesTarget, path: String): HermesResult<String> = send(target, path)

    /**
     * The single gate every request passes (address policy, key check, no redirects, specific errors).
     * [post] true sends an empty-or-[body] JSON POST; [query] is appended as URL query parameters.
     */
    internal suspend fun send(
        target: HermesTarget,
        path: String,
        post: Boolean = false,
        body: String = "{}",
        query: Map<String, String> = emptyMap(),
    ): HermesResult<String> = withContext(Dispatchers.IO) {
        target.bridge?.let { return@withContext it.send(path, post, body, query) }
        val base = target.baseUrl.trim().toHttpUrlOrNull()
            ?: return@withContext HermesResult.Err(HermesError.InvalidAddress(target.baseUrl))
        if (AddressPolicy.check(target.baseUrl) is AddressVerdict.Rejected) {
            return@withContext HermesResult.Err(HermesError.AddressRejected)
        }
        // Printable ASCII (0x20..0x7E) only: CR, LF, controls and non-ASCII cannot be a header value. Internal spaces
        // are kept. The server strips the presented token (api_server.py:1575-1578), so a leading or trailing space
        // could never match: reject those too.
        if (target.apiKey.isEmpty() || target.apiKey.startsWith(' ') || target.apiKey.endsWith(' ') ||
            !target.apiKey.all { it in ' '..'~' }
        ) {
            return@withContext HermesResult.Err(HermesError.InvalidKey)
        }
        val profile = target.profile?.trim()?.takeIf { it.isNotEmpty() }
        val url = base.newBuilder().apply {
            if (profile != null) addPathSegment("p").addPathSegment(profile)
            addPathSegments(path)
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        try {
            val request = Request.Builder().url(url)
                .header("Authorization", "Bearer ${target.apiKey}")
                .header("Accept", "application/json")
                .apply { if (post) post(body.toRequestBody("application/json".toMediaType())) }
                .build()
            http.newCall(request).execute().use { resp ->
                when {
                    resp.isRedirect -> HermesResult.Err(HermesError.Redirected(resp.code))
                    resp.isSuccessful -> HermesResult.Ok(resp.body?.string().orEmpty())
                    resp.code == 401 || resp.code == 403 -> HermesResult.Err(HermesError.Unauthorized)
                    resp.code == 404 -> {
                        // api_server.py:1700 answers an unrouted /p/<x>/ with this exact message; a missing job
                        // answers {"error":"Job not found"} (api_server.py:3852). Anything else: route unknown.
                        val msg = errorMessage(resp.body?.string().orEmpty())
                        HermesResult.Err(
                            when {
                                msg == PROFILE_UNKNOWN_MESSAGE -> HermesError.ProfileUnknown(profile.orEmpty())
                                msg != null -> HermesError.ResourceNotFound
                                else -> HermesError.NotFound
                            },
                        )
                    }
                    else -> HermesResult.Err(HermesError.Server(resp.code))
                }
            }
        } catch (e: IllegalArgumentException) {
            HermesResult.Err(HermesError.InvalidKey)
        } catch (e: IOException) {
            HermesResult.Err(HermesError.Unreachable(e.message ?: e.javaClass.simpleName))
        }
    }
}
