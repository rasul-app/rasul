package app.rasul.bridge

import app.rasul.R
import app.rasul.data.HermesError
import app.rasul.data.HermesTarget
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

// Shapes follow docs/bridge-desktop.md "Messaging channels": every route sits under
// /v1/profiles/{id}/desktop/channels and errors come back as {"error": "<token>"}.

@Serializable
data class ChannelFieldDto(
    val key: String,
    val required: Boolean = false,
    @SerialName("is_set") val isSet: Boolean = false,
    @SerialName("is_list") val isList: Boolean = false,
    val prompt: String? = null,
)

/** One messaging channel. [state] is open text: unknown values are shown as sent. */
@Serializable
data class ChannelDto(
    val id: String,
    val name: String,
    val description: String? = null,
    val enabled: Boolean = false,
    val configured: Boolean = false,
    @SerialName("gateway_running") val gatewayRunning: Boolean = false,
    val state: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
    @SerialName("error_message") val errorMessage: String? = null,
    @SerialName("home_channel_set") val homeChannelSet: Boolean = false,
    val fields: List<ChannelFieldDto> = emptyList(),
    /** Present only on the answer to a switch change. */
    @SerialName("needs_restart") val needsRestart: Boolean = false,
) {
    val pendingRestart: Boolean get() = state == ChannelState.PENDING_RESTART
}

object ChannelState {
    const val CONNECTED = "connected"
    const val DISABLED = "disabled"
    const val NOT_CONFIGURED = "not_configured"
    const val PENDING_RESTART = "pending_restart"
    const val GATEWAY_STOPPED = "gateway_stopped"
    const val STARTUP_FAILED = "startup_failed"
}

@Serializable
private data class ChannelListDto(val channels: List<ChannelDto> = emptyList())

@Serializable
data class ChannelTestDto(
    val ok: Boolean,
    val state: String? = null,
    val message: String? = null,
    @SerialName("auth_posture") val authPosture: String? = null,
)

@Serializable
data class TelegramSetupStartDto(
    @SerialName("pairing_id") val pairingId: String,
    @SerialName("deep_link") val deepLink: String,
    @SerialName("suggested_username") val suggestedUsername: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
data class TelegramSetupStatusDto(
    val status: String,
    @SerialName("bot_username") val botUsername: String? = null,
    @SerialName("owner_user_id") val ownerUserId: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
) {
    val ready: Boolean get() = status == "ready"
}

@Serializable
data class TelegramApplyDto(
    val ok: Boolean = false,
    @SerialName("bot_username") val botUsername: String? = null,
    @SerialName("restart_started") val restartStarted: Boolean = false,
    @SerialName("needs_restart") val needsRestart: Boolean = false,
)

/** The WhatsApp setup states the bridge may report. */
object WhatsappStatus {
    const val INSTALLING = "installing"
    const val STARTING = "starting"
    const val WAITING = "waiting"
    const val CONNECTED = "connected"
    const val ERROR = "error"
    const val CANCELLED = "cancelled"
    val all = setOf(INSTALLING, STARTING, WAITING, CONNECTED, ERROR, CANCELLED)
}

/** Start and status share this shape. [qrPayload] is only ever set while [status] is `waiting`. */
@Serializable
data class WhatsappSetupDto(
    @SerialName("pairing_id") val pairingId: String,
    val status: String,
    @SerialName("qr_payload") val qrPayload: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("account_name") val accountName: String? = null,
    @SerialName("account_phone_last4") val accountPhoneLast4: String? = null,
    /** One fixed code; see [whatsappError]. */
    val error: String? = null,
) {
    val terminal: Boolean get() = status == WhatsappStatus.CONNECTED || status == WhatsappStatus.ERROR || status == WhatsappStatus.CANCELLED
}

@Serializable
data class WhatsappApplyDto(
    val ok: Boolean = false,
    @SerialName("restart_started") val restartStarted: Boolean = false,
    @SerialName("needs_restart") val needsRestart: Boolean = false,
)

private val QR_TEXT = Regex("[\\x20-\\x7e]{1,$MAX_QR_PAYLOAD}")
private val LAST4 = Regex("[0-9]{4}")

/** Drops what the contract does not allow: a QR outside `waiting` or with odd characters, a last-4 that is not 4 digits. */
internal fun WhatsappSetupDto.checked(): WhatsappSetupDto = copy(
    qrPayload = qrPayload?.takeIf { status == WhatsappStatus.WAITING && QR_TEXT.matches(it) },
    accountPhoneLast4 = accountPhoneLast4?.takeIf { LAST4.matches(it) },
    accountName = accountName?.trim()?.take(100)?.takeIf { it.isNotEmpty() },
)

/** The error a setup in `error` state stands for; an unknown code is the generic failure. */
fun whatsappError(code: String?): ChannelError = when (code) {
    "whatsapp_bridge_missing" -> ChannelError.WhatsappBridgeMissing
    "whatsapp_install_failed" -> ChannelError.WhatsappInstallFailed
    "whatsapp_timeout" -> ChannelError.WhatsappTimeout
    "whatsapp_logged_out" -> ChannelError.WhatsappLoggedOut
    else -> ChannelError.WhatsappSetupFailed
}

@Serializable
private data class OkDto(val ok: Boolean = false)

enum class ChannelError(val message: Int) {
    OlderBridge(R.string.chan_err_older_bridge),
    ChannelNotFound(R.string.chan_err_not_found),
    /** The text Hermes gave for the refusal rides along as the detail. */
    Conflict(R.string.chan_err_conflict),
    Busy(R.string.chan_err_busy),
    SetupNotFound(R.string.chan_err_setup_not_found),
    SetupCancelled(R.string.chan_wa_err_cancelled),
    SetupNotReady(R.string.chan_err_setup_not_ready),
    SetupExpired(R.string.chan_setup_expired),
    BadLink(R.string.chan_err_bad_link),
    WhatsappBridgeMissing(R.string.chan_wa_err_bridge_missing),
    WhatsappInstallFailed(R.string.chan_wa_err_install_failed),
    WhatsappTimeout(R.string.chan_wa_err_timeout),
    WhatsappLoggedOut(R.string.chan_wa_err_logged_out),
    WhatsappSetupFailed(R.string.chan_wa_err_failed),
    SettingUnknown(R.string.chan_set_err_unknown),
    SettingInvalid(R.string.chan_set_err_invalid),
    SettingRedacted(R.string.chan_set_err_redacted),
    RateLimited(R.string.chan_err_rate_limited),
    Forbidden(R.string.bridge_revoked),
    Invalid(R.string.desktop_invalid),
    Unavailable(R.string.desktop_unavailable),
    DesktopFailed(R.string.desktop_error),
    ProfileNotReady(R.string.bridge_profile_not_ready),
    InvalidResponse(R.string.desktop_invalid_response),
    DeviceKeyRepair(R.string.bridge_key_repair),
    Timeout(R.string.bridge_timeout),
    Unreachable(R.string.bridge_unreachable),
}

/** [detail] is the cleaned sentence Hermes gave (conflict, not ready); [activeAgents] is only set for [ChannelError.Busy]. */
class ChannelException(
    val error: ChannelError,
    val detail: String? = null,
    val activeAgents: Int? = null,
) : Exception()

/** What a screen keeps after a failed call. */
data class ChannelFailure(val error: ChannelError, val detail: String? = null)

internal fun channelFailure(error: Throwable): ChannelFailure = when (error) {
    is ChannelException -> ChannelFailure(error.error, error.detail)
    is IOException -> ChannelFailure(channelTransportError(error))
    else -> ChannelFailure(ChannelError.DesktopFailed)
}

internal fun channelTransportError(error: Exception): ChannelError = when (bridgeError(error)) {
    HermesError.DeviceRevoked -> ChannelError.Forbidden
    HermesError.DeviceKeyRepair -> ChannelError.DeviceKeyRepair
    HermesError.BridgeTimeout -> ChannelError.Timeout
    else -> ChannelError.Unreachable
}

/** Maps a non-2xx answer: the token wins, the status is the fallback. A bare 404 means the route is missing. */
internal fun channelException(status: Int, body: String): ChannelException {
    val obj = try { profileJson.parseToJsonElement(body) as? JsonObject } catch (_: Exception) { null }
    val token = (obj?.get("error") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    val detail = (obj?.get("message") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        ?.trim()?.take(200)?.takeIf { it.isNotEmpty() }
    val agents = (obj?.get("active_agents") as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
    return when (token) {
        "gateway_busy" -> ChannelException(ChannelError.Busy, activeAgents = agents)
        "channel_conflict" -> ChannelException(ChannelError.Conflict, detail)
        "channel_not_found" -> ChannelException(ChannelError.ChannelNotFound)
        "channel_setup_not_found" -> ChannelException(ChannelError.SetupNotFound)
        "channel_setup_not_ready" -> ChannelException(ChannelError.SetupNotReady, detail)
        "channel_setup_expired" -> ChannelException(ChannelError.SetupExpired)
        "channel_setup_bad_link" -> ChannelException(ChannelError.BadLink)
        "channel_unknown_setting" -> ChannelException(ChannelError.SettingUnknown)
        "channel_setting_invalid" -> ChannelException(ChannelError.SettingInvalid)
        "channel_setting_redacted" -> ChannelException(ChannelError.SettingRedacted)
        "whatsapp_bridge_missing", "whatsapp_install_failed", "whatsapp_timeout", "whatsapp_logged_out",
        "whatsapp_setup_failed" -> ChannelException(whatsappError(token))
        "desktop_unavailable" -> ChannelException(ChannelError.Unavailable)
        "desktop_error" -> ChannelException(ChannelError.DesktopFailed)
        "desktop_invalid" -> ChannelException(ChannelError.Invalid)
        "desktop_rate_limited" -> ChannelException(ChannelError.RateLimited)
        else -> ChannelException(
            when {
                status == 400 -> ChannelError.Invalid
                status == 401 || status == 403 -> ChannelError.Forbidden
                status == 404 -> ChannelError.OlderBridge
                status == 410 -> ChannelError.SetupExpired
                status == 429 -> ChannelError.RateLimited
                status == 502 -> ChannelError.DesktopFailed
                status == 503 -> ChannelError.ProfileNotReady
                else -> ChannelError.InvalidResponse
            },
        )
    }
}

private val TELEGRAM_LINK = Regex("https://(t\\.me|telegram\\.me)/[A-Za-z0-9_/]{1,64}(\\?[A-Za-z0-9_=&-]{0,256})?")

/**
 * The address to open for the Telegram setup, or null. Checked again in the app on purpose: only an https link to
 * Telegram with no user information, port or fragment may ever reach an external viewer.
 */
fun telegramOpenLink(link: String?): String? = link?.takeIf { TELEGRAM_LINK.matches(it) }

interface ChannelsApi {
    suspend fun channels(): List<ChannelDto>

    /** The row as the bridge read it back after the change, with [ChannelDto.needsRestart]. */
    suspend fun setEnabled(id: String, enabled: Boolean): ChannelDto
    suspend fun test(id: String): ChannelTestDto

    /** Throws [ChannelError.Busy] while agents work, unless [force]. */
    suspend fun restart(force: Boolean)
    suspend fun startTelegramSetup(botName: String?): TelegramSetupStartDto
    suspend fun telegramSetupStatus(pairingId: String): TelegramSetupStatusDto
    suspend fun applyTelegramSetup(pairingId: String, allowedUserIds: List<String>, force: Boolean): TelegramApplyDto
    suspend fun cancelTelegramSetup(pairingId: String)

    /** [allowedUsers] is already checked by [parseWhatsappNumbers]; empty means none. */
    suspend fun startWhatsappSetup(mode: WhatsappMode, allowedUsers: List<String>): WhatsappSetupDto
    suspend fun whatsappSetupStatus(pairingId: String): WhatsappSetupDto

    /** Throws [ChannelError.Busy] while agents work, unless [force]. */
    suspend fun applyWhatsappSetup(pairingId: String, force: Boolean): WhatsappApplyDto
    suspend fun cancelWhatsappSetup(pairingId: String)

    /** Writes [values] and removes the [clear] keys; the row comes back with [ChannelDto.needsRestart]. Values are never logged. */
    suspend fun saveSettings(id: String, values: Map<String, String>, clear: List<String>): ChannelDto
}

/** The channels api for one bridge target, or null when the bot is a direct connection. */
fun HermesTarget.channelsApi(): ChannelsApi? = bridge?.let(::BridgeChannelsApi)

class BridgeChannelsApi(private val endpoint: BridgeEndpoint) : ChannelsApi {
    private fun url(path: String) = BridgeTransport.origin.newBuilder()
        .addPathSegments("v1/profiles").addPathSegment(endpoint.profileId)
        .addPathSegment("desktop").addPathSegments(path).build()

    private suspend fun <T> send(path: String, method: String = "GET", body: String? = null, decode: (String) -> T): T {
        val request = Request.Builder()
            .url(url(path))
            .header("Accept", "application/json")
            .method(method, body?.toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            endpoint.client.bridgeResponse(request) { response ->
                val text = response.body?.string().orEmpty()
                if (response.code !in 200..299) throw channelException(response.code, text)
                try {
                    decode(text)
                } catch (e: ChannelException) {
                    throw e
                } catch (_: Exception) {
                    throw ChannelException(ChannelError.InvalidResponse)
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: ChannelException) { throw e }
        catch (e: IOException) { throw ChannelException(channelTransportError(e)) }
    }

    private fun checkedId(id: String) {
        if (!CHANNEL_ID.matches(id)) throw ChannelException(ChannelError.Invalid)
    }

    private fun checkedPairing(id: String) {
        if (!PAIRING_ID.matches(id)) throw ChannelException(ChannelError.Invalid)
    }

    override suspend fun channels(): List<ChannelDto> =
        send("channels") { json.decodeFromString<ChannelListDto>(it).channels.filter { c -> CHANNEL_ID.matches(c.id) } }

    override suspend fun setEnabled(id: String, enabled: Boolean): ChannelDto {
        checkedId(id)
        return send(
            "channels/$id/enabled", method = "PUT",
            body = buildJsonObject { put("enabled", enabled) }.toString(),
        ) {
            json.decodeFromString<ChannelDto>(it).also { row -> if (row.id != id) throw ChannelException(ChannelError.InvalidResponse) }
        }
    }

    override suspend fun test(id: String): ChannelTestDto {
        checkedId(id)
        return send("channels/$id/test", method = "POST", body = "") { json.decodeFromString<ChannelTestDto>(it) }
    }

    override suspend fun restart(force: Boolean) {
        send("channels/restart", method = "POST", body = buildJsonObject { put("force", force) }.toString()) {
            if (!json.decodeFromString<OkDto>(it).ok) throw ChannelException(ChannelError.InvalidResponse)
        }
    }

    override suspend fun startTelegramSetup(botName: String?): TelegramSetupStartDto {
        val name = botName?.trim()?.takeIf { it.isNotEmpty() }
        if (name != null && name.length > MAX_BOT_NAME) throw ChannelException(ChannelError.Invalid)
        val body = buildJsonObject { if (name != null) put("bot_name", name) }.toString()
        return send("channels/telegram/setup", method = "POST", body = body) {
            json.decodeFromString<TelegramSetupStartDto>(it).also { r ->
                if (!PAIRING_ID.matches(r.pairingId)) throw ChannelException(ChannelError.InvalidResponse)
            }
        }
    }

    override suspend fun telegramSetupStatus(pairingId: String): TelegramSetupStatusDto {
        checkedPairing(pairingId)
        return send("channels/telegram/setup/$pairingId") {
            json.decodeFromString<TelegramSetupStatusDto>(it).also { r ->
                if (r.status != "waiting" && r.status != "ready") throw ChannelException(ChannelError.InvalidResponse)
            }
        }
    }

    override suspend fun applyTelegramSetup(pairingId: String, allowedUserIds: List<String>, force: Boolean): TelegramApplyDto {
        checkedPairing(pairingId)
        if (allowedUserIds.size !in 1..MAX_USER_IDS || !allowedUserIds.all { USER_ID.matches(it) }) {
            throw ChannelException(ChannelError.Invalid)
        }
        val body = buildJsonObject {
            put("allowed_user_ids", JsonArray(allowedUserIds.map(::JsonPrimitive)))
            put("force", force)
        }.toString()
        return send("channels/telegram/setup/$pairingId/apply", method = "POST", body = body) {
            json.decodeFromString<TelegramApplyDto>(it).also { r -> if (!r.ok) throw ChannelException(ChannelError.InvalidResponse) }
        }
    }

    override suspend fun cancelTelegramSetup(pairingId: String) {
        checkedPairing(pairingId)
        send("channels/telegram/setup/$pairingId", method = "DELETE") { }
    }

    override suspend fun startWhatsappSetup(mode: WhatsappMode, allowedUsers: List<String>): WhatsappSetupDto {
        val users = allowedUsers.distinct()
        if (users.size > MAX_WHATSAPP_NUMBERS || !users.all { PHONE_NUMBER.matches(it) }) throw ChannelException(ChannelError.Invalid)
        val body = buildJsonObject {
            put("mode", mode.wire)
            if (users.isNotEmpty()) put("allowed_users", users.joinToString(","))
        }.toString()
        return send("channels/whatsapp/setup", method = "POST", body = body) { whatsappState(it, null) }
    }

    override suspend fun whatsappSetupStatus(pairingId: String): WhatsappSetupDto {
        checkedPairing(pairingId)
        return send("channels/whatsapp/setup/$pairingId") { whatsappState(it, pairingId) }
    }

    private fun whatsappState(text: String, wanted: String?): WhatsappSetupDto {
        val state = json.decodeFromString<WhatsappSetupDto>(text).checked()
        if (!PAIRING_ID.matches(state.pairingId) || (wanted != null && state.pairingId != wanted) || state.status !in WhatsappStatus.all) {
            throw ChannelException(ChannelError.InvalidResponse)
        }
        return state
    }

    override suspend fun applyWhatsappSetup(pairingId: String, force: Boolean): WhatsappApplyDto {
        checkedPairing(pairingId)
        val body = buildJsonObject { put("force", force) }.toString()
        return send("channels/whatsapp/setup/$pairingId/apply", method = "POST", body = body) {
            json.decodeFromString<WhatsappApplyDto>(it).also { r -> if (!r.ok) throw ChannelException(ChannelError.InvalidResponse) }
        }
    }

    override suspend fun cancelWhatsappSetup(pairingId: String) {
        checkedPairing(pairingId)
        send("channels/whatsapp/setup/$pairingId", method = "DELETE") { }
    }

    override suspend fun saveSettings(id: String, values: Map<String, String>, clear: List<String>): ChannelDto {
        checkedId(id)
        if (values.isEmpty() && clear.isEmpty()) throw ChannelException(ChannelError.Invalid)
        if (values.size > MAX_SETTING_CHANGES || clear.size > MAX_SETTING_CHANGES || values.keys.any { it in clear }) {
            throw ChannelException(ChannelError.Invalid)
        }
        for ((key, value) in values) {
            when (settingValueProblem(key, value)) {
                null -> {}
                SettingProblem.Masked -> throw ChannelException(ChannelError.SettingRedacted)
                SettingProblem.BadKey -> throw ChannelException(ChannelError.SettingUnknown)
                else -> throw ChannelException(ChannelError.SettingInvalid)
            }
        }
        if (!clear.all(::isSettingKey)) throw ChannelException(ChannelError.SettingUnknown)
        val body = buildJsonObject {
            if (values.isNotEmpty()) put("values", buildJsonObject { values.forEach { (k, v) -> put(k, v) } })
            if (clear.isNotEmpty()) put("clear", JsonArray(clear.map(::JsonPrimitive)))
        }.toString()
        return send("channels/$id/settings", method = "PUT", body = body) {
            json.decodeFromString<ChannelDto>(it).also { row -> if (row.id != id) throw ChannelException(ChannelError.InvalidResponse) }
        }
    }

    private companion object {
        val PHONE_NUMBER = Regex("\\+?[0-9]{1,20}")
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val CHANNEL_ID = Regex("[a-z0-9_]{1,32}")
        val PAIRING_ID = Regex("[A-Za-z0-9_-]{8,128}")
        val USER_ID = Regex("[0-9]{1,20}")
        const val MAX_USER_IDS = 20
        const val MAX_BOT_NAME = 64
    }
}
