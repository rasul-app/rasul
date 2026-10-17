package app.rasul.bridge

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/** Routes only the fixed inner authority; the outer client must use ordinary TCP sockets. */
class WebSocketSocketFactory(
    urls: List<String>,
    private val outer: OkHttpClient,
    private val onWorkingUrl: (String) -> Unit = {},
    private val confirmOnConnect: Boolean = true,
    private val onConnectFailure: () -> Unit = {},
    private val onOuterConnected: () -> Unit = {},
    private val staleAfterMs: Long = BridgeTimeouts.LINK_STALE_MS,
) : SocketFactory() {
    private val urls = urls.toList().also {
        require(it.isNotEmpty() && it.all(::validBridgeUrl))
        require(!outer.followRedirects && !outer.followSslRedirects)
    }
    @Volatile private var lastWorking: String? = null

    internal fun confirm(socket: WebSocketSocket) {
        lastWorking = socket.bridgeUrl
        onWorkingUrl(socket.bridgeUrl)
    }

    internal fun connect(timeoutMs: Int, excluded: Set<String> = emptySet(), onAttempt: (WebSocketSocket) -> Unit = {}): WebSocketSocket {
        var failure: IOException? = null
        val preferred = lastWorking
        val ordered = if (preferred == null) urls else listOf(preferred) + urls.filter { it != preferred }
        for (url in ordered.filterNot { it in excluded }) {
            val socket = WebSocketSocket(outer, url, if (URI(url).path == "/v1/link") url else url.trimEnd('/') + "/v1/link", staleAfterMs)
            try {
                onAttempt(socket)
                socket.open(timeoutMs)
                onOuterConnected()
                if (confirmOnConnect) confirm(socket)
                return socket
            } catch (e: IOException) {
                onConnectFailure()
                socket.close()
                failure = e
            }
        }
        throw IOException("No bridge transport available", failure)
    }

    override fun createSocket(): Socket = RoutingSocket(this)
    override fun createSocket(host: String, port: Int): Socket = createSocket().apply {
        require(host == "example.invalid" && port == 443)
        connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port))
    }
    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
        throw SocketException("Local binding is not supported")
    override fun createSocket(host: InetAddress, port: Int): Socket =
        throw SocketException("An inner authority is required")
    override fun createSocket(address: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
        throw SocketException("Local binding is not supported")
}

internal fun validBridgeUrl(raw: String): Boolean = try {
    val uri = URI(raw)
    raw.length <= 2048 && uri.scheme in setOf("ws", "wss") && !uri.host.isNullOrEmpty() &&
        uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
        (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/" || uri.rawPath == "/v1/link") &&
        (uri.port == -1 || uri.port in 1..65535)
} catch (_: Exception) { false }

internal class RoutingSocket(private val factory: WebSocketSocketFactory) : Socket() {
    @Volatile private var delegate: WebSocketSocket? = null
    private var timeout = 0
    private var connectTimeout = 10_000
    @Volatile private var closed = false
    private val excluded = mutableSetOf<String>()
    override fun connect(endpoint: SocketAddress) = connect(endpoint, 10_000)
    override fun connect(endpoint: SocketAddress, timeout: Int) {
        if (closed || delegate != null) throw SocketException("Socket is closed or connected")
        require(endpoint is InetSocketAddress && endpoint.port == 443 && endpoint.address.isLoopbackAddress)
        connectTimeout = if (timeout == 0) 10_000 else timeout
        delegate = factory.connect(connectTimeout, onAttempt = { delegate = it; if (closed) it.close() })
            .apply { soTimeout = this@RoutingSocket.timeout }
    }
    fun tls(create: (Socket) -> Socket): Socket {
        while (true) {
            val raw = delegate ?: throw SocketException("Not connected")
            try {
                val secured = create(raw)
                factory.confirm(raw)
                return secured
            } catch (failure: IOException) {
                raw.close()
                excluded.add(raw.bridgeUrl)
                if (closed) throw failure
                delegate = try {
                    factory.connect(connectTimeout, excluded, onAttempt = { delegate = it; if (closed) it.close() }).apply { soTimeout = timeout }
                } catch (unavailable: IOException) {
                    unavailable.addSuppressed(failure)
                    throw unavailable
                }
            }
        }
    }
    override fun getInputStream(): InputStream = delegate?.getInputStream() ?: throw SocketException("Not connected")
    override fun getOutputStream(): OutputStream = delegate?.getOutputStream() ?: throw SocketException("Not connected")
    override fun setSoTimeout(timeout: Int) { require(timeout >= 0); this.timeout = timeout; delegate?.soTimeout = timeout }
    override fun getSoTimeout() = timeout
    override fun setTcpNoDelay(on: Boolean) = Unit
    override fun getTcpNoDelay() = true
    override fun setKeepAlive(on: Boolean) = Unit
    override fun getKeepAlive() = true
    override fun getInetAddress(): InetAddress = InetAddress.getLoopbackAddress()
    override fun getLocalAddress(): InetAddress = InetAddress.getLoopbackAddress()
    override fun getPort() = 443
    override fun getLocalPort() = 0
    override fun isConnected() = delegate?.isConnected == true
    override fun isBound() = isConnected
    override fun isClosed() = closed
    override fun isInputShutdown() = closed || delegate?.isInputShutdown == true
    override fun isOutputShutdown() = closed || delegate?.isOutputShutdown == true
    override fun shutdownInput() { close() }
    override fun shutdownOutput() { close() }
    override fun close() { closed = true; delegate?.close() }
}

/** Binary frames form one byte stream. Callback threads never wait for a reader. */
internal class WebSocketSocket(private val outer: OkHttpClient, val bridgeUrl: String, private val url: String, private val staleAfterMs: Long = BridgeTimeouts.LINK_STALE_MS) : Socket() {
    private val monitor = Object()
    private val received = Buffer()
    private val opened = CountDownLatch(1)
    private var webSocket: WebSocket? = null
    @Volatile private var failure: IOException? = null
    @Volatile private var eof = false
    @Volatile private var eofDelivered = false
    @Volatile private var closed = false
    @Volatile private var connected = false
    @Volatile private var timeout = 0
    @Volatile private var lastActivityNanos = 0L

    fun open(timeoutMs: Int) {
        if (closed) throw SocketException("Socket closed")
        lastActivityNanos = System.nanoTime()
        webSocket = outer.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                synchronized(monitor) { connected = true; opened.countDown() }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                synchronized(monitor) {
                    if (closed || eof) return
                    // OkHttp has already assembled this message; this bounds only our retained queue.
                    if (bytes.size > MAX_BUFFER || received.size + bytes.size > MAX_BUFFER) {
                        fail(ProtocolException("Bridge receive buffer exceeded"))
                        webSocket.cancel()
                    } else {
                        received.write(bytes)
                        lastActivityNanos = System.nanoTime()
                        monitor.notifyAll()
                    }
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                fail(ProtocolException("Expected binary transport"))
                webSocket.cancel()
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(monitor) { eof = true; monitor.notifyAll() }
                webSocket.close(code, null)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(monitor) { eof = true; monitor.notifyAll() }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Only a link that had opened "dropped"; a refused upgrade is a connect failure.
                fail(if (connected) BridgeLinkDroppedException("Bridge transport failed", t) else IOException("Bridge transport failed", t))
            }
        })
        try {
            if (!opened.await(timeoutMs.coerceAtMost(10_000).toLong(), TimeUnit.MILLISECONDS)) {
                throw SocketTimeoutException("Bridge connect timed out")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Bridge connect interrupted", e)
        }
        synchronized(monitor) { failure?.let { throw it }; if (!connected || closed) throw SocketException("Bridge did not open") }
    }

    private fun fail(error: IOException) = synchronized(monitor) {
        if (failure == null) failure = error
        connected = false
        opened.countDown()
        monitor.notifyAll()
    }

    /**
     * No adapter bytes for [staleAfterMs]: the tunnel closes idle links (~100 s), and a suspended app cannot ping —
     * an idle link this old must not be trusted. A dead or stale link reports its input and output as shut down, so a
     * pooled connection is never mistaken for usable — OkHttp opens a fresh link instead of writing to a dead one.
     */
    private fun dead() = closed || eof || failure != null ||
        System.nanoTime() - lastActivityNanos > TimeUnit.MILLISECONDS.toNanos(staleAfterMs)
    override fun isInputShutdown() = dead()
    override fun isOutputShutdown() = dead()

    private val input = object : InputStream() {
        override fun read(): Int {
            val byte = ByteArray(1)
            return if (read(byte, 0, 1) == -1) -1 else byte[0].toInt() and 255
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
            if (length == 0) return 0
            val deadline = if (timeout == 0) Long.MAX_VALUE else System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout.toLong())
            synchronized(monitor) {
                while (received.size == 0L) {
                    failure?.let { throw it }
                    if (closed) throw SocketException("Socket closed")
                    if (eof) { eofDelivered = true; return -1 }
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) throw SocketTimeoutException("Bridge read timed out")
                    try {
                        if (timeout == 0) monitor.wait() else TimeUnit.NANOSECONDS.timedWait(monitor, remaining)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("Bridge read interrupted", e)
                    }
                }
                if (closed) throw SocketException("Socket closed")
                return received.read(bytes, offset, length)
            }
        }
        override fun available(): Int = synchronized(monitor) { received.size.toInt() }
        override fun close() = this@WebSocketSocket.close()
    }
    private val output = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
            synchronized(monitor) {
                failure?.let { throw it }
                if (eofDelivered) throw BridgeLinkDroppedException("Bridge link closed")
                if (!connected || closed) throw SocketException("Socket is not open")
                // A peer close must not hide queued TLS records behind a write failure.
                if (eof) return
                // A link idle past the tunnel limit is dropped even when it still looks open — never write to it.
                if (System.nanoTime() - lastActivityNanos > TimeUnit.MILLISECONDS.toNanos(staleAfterMs)) {
                    throw BridgeLinkDroppedException("Bridge link went stale").also { fail(it) }
                }
                val ws = webSocket ?: throw SocketException("Socket is not open")
                if (length > MAX_BUFFER || ws.queueSize() + length > MAX_BUFFER) throw IOException("Bridge send buffer exceeded")
                var cursor = offset
                while (cursor < offset + length) {
                    val count = minOf(64 * 1024, offset + length - cursor)
                    if (!ws.send(bytes.toByteString(cursor, count))) throw IOException("Bridge send failed")
                    cursor += count
                }
                lastActivityNanos = System.nanoTime()
            }
        }
        override fun close() = this@WebSocketSocket.close()
    }
    override fun getInputStream(): InputStream = input
    override fun getOutputStream(): OutputStream = output
    override fun setSoTimeout(timeout: Int) { require(timeout >= 0); this.timeout = timeout }
    override fun getSoTimeout() = timeout
    override fun getInetAddress(): InetAddress = InetAddress.getLoopbackAddress()
    override fun getLocalAddress(): InetAddress = InetAddress.getLoopbackAddress()
    override fun getPort() = 443
    override fun getLocalPort() = 0
    override fun isConnected() = connected
    override fun isBound() = connected
    override fun isClosed() = closed
    override fun close() {
        synchronized(monitor) { closed = true; received.clear(); monitor.notifyAll(); opened.countDown() }
        webSocket?.cancel()
    }
    private companion object { const val MAX_BUFFER = 1024L * 1024 }
}
