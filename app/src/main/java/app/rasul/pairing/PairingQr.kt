package app.rasul.pairing

import android.util.JsonReader
import android.util.JsonToken
import app.rasul.R
import app.rasul.bridge.validBridgeUrl
import java.io.StringReader
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64

/** Invitation secrets are transient and must never be persisted or logged. */
class PairingInvitation(
    val bridgeId: String,
    val name: String,
    val urls: List<String>,
    val pin: ByteArray,
    val invitation: String,
    val secret: String,
    val expiresAt: Long,
)

enum class PairingQrError(val message: Int) {
    Invalid(R.string.pair_qr_invalid),
    Expired(R.string.pair_qr_expired),
    Version(R.string.pair_qr_version),
    MissingPin(R.string.pair_qr_missing_pin),
    InsecureUrl(R.string.pair_qr_url),
}

sealed interface PairingQrResult {
    class Valid(val invitation: PairingInvitation) : PairingQrResult
    data class Invalid(val error: PairingQrError) : PairingQrResult
}

object PairingQrParser {
    fun parse(raw: String, nowSeconds: Long = System.currentTimeMillis() / 1000): PairingQrResult {
        fun invalid(error: PairingQrError = PairingQrError.Invalid) = PairingQrResult.Invalid(error)
        if (raw.length > 16_384 || !raw.startsWith("rasul-pair:")) return invalid()
        if (!raw.startsWith("rasul-pair:1:")) return invalid(PairingQrError.Version)
        return try {
            val payload = decode(raw.removePrefix("rasul-pair:1:"))
            val utf8 = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(payload)).toString()
            val strings = mutableMapOf<String, String>()
            val numbers = mutableMapOf<String, Long>()
            val urls = mutableListOf<String>()
            val seen = mutableSetOf<String>()
            JsonReader(StringReader(utf8)).use { reader ->
                reader.isLenient = false
                reader.beginObject()
                while (reader.hasNext()) {
                    val key = reader.nextName()
                    require(seen.add(key))
                    when (key) {
                        "v", "exp" -> {
                            require(reader.peek() == JsonToken.NUMBER)
                            val value = reader.nextString()
                            require(Regex("0|[1-9][0-9]{0,18}").matches(value))
                            numbers[key] = value.toLong()
                        }
                        "urls" -> {
                            reader.beginArray()
                            while (reader.hasNext()) {
                                require(urls.size < 8 && reader.peek() == JsonToken.STRING)
                                urls.add(reader.nextString())
                            }
                            reader.endArray()
                        }
                        "bridge_id", "name", "pin", "inv", "sec" -> {
                            require(reader.peek() == JsonToken.STRING)
                            strings[key] = reader.nextString()
                        }
                        else -> return invalid()
                    }
                }
                reader.endObject()
                require(reader.peek() == JsonToken.END_DOCUMENT)
            }
            if (numbers["v"] != 1L) return invalid(PairingQrError.Version)
            if ("pin" !in strings) return invalid(PairingQrError.MissingPin)
            require(seen == setOf("v", "bridge_id", "name", "urls", "pin", "inv", "sec", "exp"))
            val expires = numbers.getValue("exp")
            if (expires <= nowSeconds) return invalid(PairingQrError.Expired)
            require(expires - nowSeconds in 1..120)
            if (urls.isEmpty() || urls.any { !validBridgeUrl(it) }) return invalid(PairingQrError.InsecureUrl)
            require(urls.distinct().size == urls.size)
            val name = strings.getValue("name")
            val bridgeId = strings.getValue("bridge_id")
            val invitation = strings.getValue("inv")
            require(name.isNotBlank() && name.length <= 128 && name.none { it.isISOControl() })
            require(Regex("[A-Za-z0-9-]{1,128}").matches(bridgeId))
            require(Regex("[a-f0-9]{32}").matches(invitation))
            val pin = decode(strings.getValue("pin")).also { require(it.size == 32) }
            val secret = strings.getValue("sec").also { require(decode(it).size == 32) }
            PairingQrResult.Valid(PairingInvitation(bridgeId, name, urls.toList(), pin, invitation, secret, expires))
        } catch (_: Exception) { invalid() }
    }

    private fun decode(value: String): ByteArray {
        require(value.isNotEmpty() && Regex("[A-Za-z0-9_-]+").matches(value))
        return Base64.getUrlDecoder().decode(value).also {
            require(Base64.getUrlEncoder().withoutPadding().encodeToString(it) == value)
        }
    }
}

object PairingConfirmation {
    fun code(serverSpki: ByteArray, deviceSpki: ByteArray, invitation: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(serverSpki)
        digest.update(deviceSpki)
        digest.update(invitation.toByteArray(Charsets.UTF_8))
        return BigInteger(1, digest.digest()).toString(10).padStart(6, '0').take(6)
    }
}
