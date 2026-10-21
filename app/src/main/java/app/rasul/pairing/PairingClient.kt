package app.rasul.pairing

import app.rasul.BuildConfig
import app.rasul.R
import app.rasul.bridge.BridgeTransport
import app.rasul.bridge.DeviceIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
data class PairingRequest(
    val inv: String,
    val sec: String,
    @SerialName("device_name") val deviceName: String,
    @SerialName("device_cert_pem") val deviceCertPem: String,
)
@Serializable
data class PairingResponse(@SerialName("device_id") val deviceId: String)
@Serializable
data class PairingRequestV2(
    val inv: String,
    val sec: String,
    @SerialName("device_name") val deviceName: String,
    @SerialName("device_cert_pem") val deviceCertPem: String,
    val platform: String,
    @SerialName("app_version") val appVersion: String,
)
@Serializable
private data class PairingResponseV2(@SerialName("device_id") val deviceId: String, val role: String? = null)
data class PairingResultV2(val deviceId: String, val role: String)
@Serializable
private data class PairingErrorBody(val error: String)

enum class PairingFailure(val message: Int) {
    Denied(R.string.pair_denied), Expired(R.string.pair_qr_expired),
    Timeout(R.string.pair_timeout), RateLimit(R.string.pair_rate_limit),
    Invalid(R.string.pair_request_invalid), Connection(R.string.pair_connection),
    Response(R.string.pair_response_invalid), Storage(R.string.pair_storage),
    Identity(R.string.pair_identity), Unavailable(R.string.pair_unavailable),
    HeaderSize(R.string.pair_header_size), Header(R.string.pair_header),
    DuplicateHeader(R.string.pair_duplicate_header), Request(R.string.pair_bad_request),
    Framing(R.string.pair_framing), Length(R.string.pair_length), BodySize(R.string.pair_body_size),
    DeviceRequired(R.string.pair_device_required), Route(R.string.pair_route),
    V2Refused(R.string.pair_v2_refused), V2Invalid(R.string.pair_v2_invalid), V2RateLimit(R.string.pair_v2_rate_limit);

    /** The text for a pairing of [protocol]; protocol 2 points at the Rasul window on the PC instead of a command. */
    fun messageFor(protocol: Int): Int = if (protocol != 2) message else when (this) {
        Expired -> R.string.pair_v2_qr_expired
        Timeout -> R.string.pair_v2_timeout
        Storage -> R.string.pair_v2_storage
        Unavailable -> R.string.pair_v2_unavailable
        else -> message
    }
}
class PairingException(val failure: PairingFailure) : IOException()

data class PairingEnrollment(val deviceId: String, val lastWorkingUrl: String?, val role: String? = null)

interface PairingService {
    suspend fun enroll(invitation: PairingInvitation, identity: DeviceIdentity, onCode: (String) -> Unit): PairingEnrollment
}

class PairingClient(
    private val transport: (PairingInvitation, (ByteArray) -> Unit) -> BridgeTransport = { invitation, verified ->
        BridgeTransport(invitation.urls, invitation.pin, null, onServerSpki = verified)
    },
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val appVersion: String = BuildConfig.VERSION_NAME,
) : PairingService {
    // Closing the transport closes TLS sockets, which Android forbids on the main thread.
    override suspend fun enroll(invitation: PairingInvitation, identity: DeviceIdentity, onCode: (String) -> Unit): PairingEnrollment =
        withContext(Dispatchers.IO) { enrollOffMain(invitation, identity, onCode) }

    private suspend fun enrollOffMain(invitation: PairingInvitation, identity: DeviceIdentity, onCode: (String) -> Unit): PairingEnrollment {
        if (now() > invitation.expiresAt + CLOCK_SKEW_SECONDS) throw PairingException(PairingFailure.Expired)
        val cert = identity.certificateChain.first()
        val pem = "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(cert.encoded) +
            "\n-----END CERTIFICATE-----\n"
        transport(invitation) { spki ->
            onCode(PairingConfirmation.code(spki, cert.publicKey.encoded, invitation.invitation))
        }.use { link ->
            val http = link.client.newBuilder().callTimeout(130, TimeUnit.SECONDS).readTimeout(130, TimeUnit.SECONDS).build()
            return try {
                if (invitation.protocol == 2) {
                    val result = postV2(http, pairingRequestV2(invitation, pem, appVersion))
                    PairingEnrollment(result.deviceId, link.lastWorkingUrl, result.role)
                } else PairingEnrollment(post(http, PairingRequest(invitation.invitation, invitation.secret, "Phone", pem),
                    expired = { now() >= invitation.expiresAt }), link.lastWorkingUrl)
            } catch (e: SocketTimeoutException) {
                throw PairingException(PairingFailure.Timeout)
            }
        }
    }

    companion object {
        private val json = Json
        private val lenient = Json { ignoreUnknownKeys = true }
        suspend fun post(http: OkHttpClient, body: PairingRequest, expired: () -> Boolean = { false }): String =
            send(http, "v1/pair", json.encodeToString(body), expired) { text ->
                val result = try { json.decodeFromString<PairingResponse>(text) }
                    catch (_: Exception) { throw PairingException(PairingFailure.Response) }
                if (!Regex("[a-f0-9]{32}").matches(result.deviceId)) throw PairingException(PairingFailure.Response)
                result.deviceId
            }

        /** The host reads at most 64, 32 and 32 characters of these fields. */
        internal fun pairingRequestV2(invitation: PairingInvitation, pem: String, appVersion: String) = PairingRequestV2(
            invitation.invitation, invitation.secret, "Phone".take(64), pem, "android", appVersion.take(32),
        )

        /** Protocol 2: unknown response fields are ignored and an unknown role counts as a viewer. */
        suspend fun postV2(http: OkHttpClient, body: PairingRequestV2): PairingResultV2 =
            send(http, "v2/pair", json.encodeToString(body), { false }) { text ->
                val result = try { lenient.decodeFromString<PairingResponseV2>(text) }
                    catch (_: Exception) { throw PairingException(PairingFailure.Response) }
                if (!Regex("[a-f0-9]{32}").matches(result.deviceId)) throw PairingException(PairingFailure.Response)
                PairingResultV2(result.deviceId, if (result.role == "owner") "owner" else "viewer")
            }

        private suspend fun <T> send(http: OkHttpClient, path: String, payload: String, expired: () -> Boolean, parse: (String) -> T): T {
            val request = Request.Builder().url("https://example.invalid/$path")
                .post(payload.toRequestBody("application/json".toMediaType())).build()
            return http.newCall(request).awaitBody { response -> response.use {
                val text = it.body?.source()?.apply { request(16_385) }?.let { source ->
                    if (source.buffer.size > 16_384) throw PairingException(PairingFailure.Response)
                    source.readUtf8()
                } ?: throw PairingException(PairingFailure.Response)
                if (it.code != 200) {
                    if (path == "v2/pair") when (it.code) {
                        403 -> throw PairingException(PairingFailure.V2Refused)
                        400 -> throw PairingException(PairingFailure.V2Invalid)
                        429 -> throw PairingException(PairingFailure.V2RateLimit)
                    }
                    val token = runCatching { json.decodeFromString<PairingErrorBody>(text).error }.getOrNull()
                    throw PairingException(when {
                        it.code == 403 && token == "Pairing denied" && expired() -> PairingFailure.Expired
                        it.code == 403 && token == "Pairing denied" -> PairingFailure.Denied
                        it.code == 429 && token == "Pairing rate limit" -> PairingFailure.RateLimit
                        it.code == 400 && token == "Invalid pairing request" -> PairingFailure.Invalid
                        token == "Headers too large" -> PairingFailure.HeaderSize
                        token == "Invalid header" -> PairingFailure.Header
                        token == "Duplicate header" -> PairingFailure.DuplicateHeader
                        token == "Invalid request" -> PairingFailure.Request
                        token == "Unsupported framing" -> PairingFailure.Framing
                        token == "Invalid content length" -> PairingFailure.Length
                        token == "Request body too large" -> PairingFailure.BodySize
                        token == "Registered device certificate required" -> PairingFailure.DeviceRequired
                        token == "Route not found" -> PairingFailure.Route
                        token == "Upstream unavailable" -> PairingFailure.Unavailable
                        else -> PairingFailure.Response
                    })
                }
                parse(text)
            } }
        }
    }
}

internal suspend fun <T> Call.awaitBody(read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                val value = response.use(read)
                if (continuation.isActive) continuation.resume(value)
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }
    })
}
