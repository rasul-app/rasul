package app.rasul.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class RejectReason { InvalidUrl, CleartextPublicHost }

sealed interface AddressVerdict {
    data object Allowed : AddressVerdict

    /** https to a public, non-Tailscale name: allowed but the UI warns. */
    data object AllowedPublicHttps : AddressVerdict
    data class Rejected(val reason: RejectReason) : AddressVerdict
}

/** Plain HTTP is restricted to the phone loopback interface. Other addresses require HTTPS. */
object AddressPolicy {
    fun check(rawUrl: String): AddressVerdict {
        val url = rawUrl.trim().toHttpUrlOrNull() ?: return AddressVerdict.Rejected(RejectReason.InvalidUrl)
        val host = url.host.lowercase().trimEnd('.')
        val local = isLocalHost(host)
        return when {
            !url.isHttps && host != "127.0.0.1" && host != "localhost" ->
                AddressVerdict.Rejected(RejectReason.CleartextPublicHost)
            local -> AddressVerdict.Allowed
            url.isHttps -> AddressVerdict.AllowedPublicHttps
            else -> AddressVerdict.Rejected(RejectReason.CleartextPublicHost)
        }
    }

    private fun isLocalHost(host: String): Boolean {
        if (host == "localhost" || host == "::1" || host.endsWith(".ts.net")) return true
        val o = ipv4(host) ?: return false
        return o[0] == 127 || o[0] == 10 ||
            (o[0] == 172 && o[1] in 16..31) ||
            (o[0] == 192 && o[1] == 168) ||
            (o[0] == 100 && o[1] in 64..127)
    }

    private fun ipv4(host: String): List<Int>? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val nums = parts.map { p -> if (p.isNotEmpty() && p.all { it in '0'..'9' }) p.toIntOrNull() else null }
        return if (nums.all { it != null && it in 0..255 }) nums.map { it!! } else null
    }
}
