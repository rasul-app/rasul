package app.rasul.bridge

import org.conscrypt.Conscrypt
import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

class SpkiTrustManager(pin: ByteArray, private val onVerifiedSpki: (ByteArray) -> Unit = {}) : X509TrustManager {
    private val expected = pin.copyOf().also { require(it.size == 32) }
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("Missing server certificate")
        val actual = MessageDigest.getInstance("SHA-256").digest(leaf.publicKey.encoded)
        if (!MessageDigest.isEqual(expected, actual)) throw CertificateException("Server identity mismatch")
        leaf.checkValidity()
        onVerifiedSpki(leaf.publicKey.encoded)
    }
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client trust is not supported")
    }
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** A fresh context per connection prevents cached TLS sessions from being resumed. */
class BridgeTlsSocketFactory(
    private val trust: SpkiTrustManager,
    private val identity: DeviceIdentity? = null,
    private val newContext: () -> SSLContext = { SSLContext.getInstance("TLSv1.3", Conscrypt.newProvider()) },
    private val rejections: BridgeRejectionTracker = BridgeRejectionTracker(identity != null),
) : SSLSocketFactory() {
    private fun factory(): SSLSocketFactory = newContext().apply {
        val keys: Array<KeyManager> = identity?.let { arrayOf(DeviceKeyManager(it)) } ?: emptyArray()
        init(keys, arrayOf(trust), null)
    }.socketFactory

    private fun tls(socket: Socket): Socket = (socket as SSLSocket).apply {
        enabledProtocols = arrayOf("TLSv1.3")
    }
    override fun getDefaultCipherSuites(): Array<String> = factory().defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = factory().supportedCipherSuites
    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket {
        fun secure(raw: Socket): Socket {
            val socket = tls(factory().createSocket(raw, host, port, autoClose)) as SSLSocket
            val readTimeout = raw.soTimeout
            try {
                socket.soTimeout = 10_000
                socket.startHandshake()
                socket.soTimeout = readTimeout
                return socket
            } catch (failure: Exception) {
                runCatching { socket.close() }
                throw if (raw is WebSocketSocket) BridgeHandshakeFailure(rejections.rejected(failure)) else failure
            }
        }
        return if (s is RoutingSocket) s.tls(::secure) else secure(s)
    }
    override fun createSocket(): Socket = tls(factory().createSocket())
    override fun createSocket(host: String, port: Int): Socket = tls(factory().createSocket(host, port))
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        tls(factory().createSocket(host, port, localHost, localPort))
    override fun createSocket(host: InetAddress, port: Int): Socket = tls(factory().createSocket(host, port))
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        tls(factory().createSocket(address, port, localAddress, localPort))
}
