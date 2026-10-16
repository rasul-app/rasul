package app.rasul

import app.rasul.data.AddressPolicy
import app.rasul.data.AddressVerdict
import app.rasul.data.RejectReason
import org.junit.Assert.assertEquals
import org.junit.Test

class AddressPolicyTest {
    private val allowed = listOf(
        "http://${java.net.InetAddress.getLoopbackAddress().hostAddress}:8642",
        "https://${java.net.InetAddress.getLoopbackAddress().hostName}",
    )
    private val rejectedCleartext = listOf(
        "http://example.invalid", "http://192.0.2.7", "http://[2001:db8::1]",
        "http://192.0.2.20", "http://192.0.2.9",
    )

    @Test fun allowedHosts() {
        for (u in allowed) assertEquals(u, AddressVerdict.Allowed, AddressPolicy.check(u))
    }

    @Test fun cleartextToOtherHostsRejected() {
        for (u in rejectedCleartext) {
            assertEquals(u, AddressVerdict.Rejected(RejectReason.CleartextPublicHost), AddressPolicy.check(u))
        }
    }

    @Test fun httpsPublicAllowedWithWarning() {
        assertEquals(AddressVerdict.AllowedPublicHttps, AddressPolicy.check("https://example.com"))
        assertEquals(AddressVerdict.AllowedPublicHttps, AddressPolicy.check("https://example.com:8443"))
    }

    @Test fun httpsLoopbackNoWarning() {
        assertEquals(AddressVerdict.Allowed, AddressPolicy.check("https://${java.net.InetAddress.getLoopbackAddress().hostName}"))
    }

    @Test fun invalidUrlsRejected() {
        for (u in listOf("", "not a url", "ftp://192.0.2.1", "192.0.2.1:8642")) {
            assertEquals(u, AddressVerdict.Rejected(RejectReason.InvalidUrl), AddressPolicy.check(u))
        }
    }
}
