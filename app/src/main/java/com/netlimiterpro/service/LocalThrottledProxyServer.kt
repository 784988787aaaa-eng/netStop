package com.netlimiterpro.service

import android.net.ConnectivityManager
import android.os.Build
import android.system.OsConstants
import com.netlimiterpro.domain.model.AppRule
import com.netlimiterpro.domain.model.NetworkPolicy
import com.netlimiterpro.domain.network.ActiveNetworkKind
import com.netlimiterpro.domain.network.TokenBucketThrottler
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loopback HTTP proxy for apps which honor Android's VPN HTTP proxy setting.
 * Supports HTTP absolute-form requests and HTTPS CONNECT tunnels. It is not a
 * transparent TCP/UDP relay: direct sockets, QUIC/UDP and apps ignoring the
 * proxy are outside this backend's coverage.
 */
class LocalThrottledProxyServer(
    private val connectivityManager: ConnectivityManager,
    private val protectSocket: (Socket) -> Boolean,
    private val rulesProvider: () -> List<AppRule>,
    private val networkProvider: () -> ActiveNetworkKind,
    private val defaultLimitProvider: () -> Long
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val workers: ExecutorService = Executors.newCachedThreadPool()
    private val buckets = ConcurrentHashMap<String, TokenBucketThrottler>()
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile var port: Int = -1
        private set

    @Synchronized
    fun start(): Int {
        if (serverSocket?.isClosed == false) return port
        check(!closed.get()) { "Proxy server is closed" }
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 64)
        serverSocket = socket
        port = socket.localPort
        workers.execute {
            while (!closed.get()) {
                try {
                    val client = socket.accept()
                    workers.execute { handleClient(client) }
                } catch (_: IOException) {
                    if (!closed.get()) Thread.yield()
                    break
                }
            }
        }
        return port
    }

    fun updatePolicy() {
        // Rates are read dynamically for each connection. Buckets are retained
        // to avoid resetting accumulated tokens on every UI rule edit.
        buckets.keys.retainAll(rulesProvider().map { "${it.uid}:${networkProvider()}" }.toSet())
    }

    private fun handleClient(client: Socket) {
        client.use { incoming ->
            incoming.soTimeout = 30_000
            val input = BufferedInputStream(incoming.getInputStream())
            val output = BufferedOutputStream(incoming.getOutputStream())
            input.mark(4)
            val protocolVersion = input.read()
            if (protocolVersion < 0) return
            if (protocolVersion == SOCKS5_VERSION) {
                handleSocks5(incoming, input, output)
                return
            }
            input.reset()
            val header = readHeader(input) ?: return
            val firstLine = header.substringBefore("\r\n")
            val parts = firstLine.split(' ', limit = 3)
            if (parts.size < 2) {
                writeError(output, "400 Bad Request")
                return
            }
            val isConnect = parts[0].equals("CONNECT", ignoreCase = true)
            val target = try {
                if (isConnect) parseConnectTarget(parts[1]) else parseHttpTarget(parts[1], header)
            } catch (_: Exception) {
                writeError(output, "400 Bad Request")
                return
            }
            if (target.first.isBlank() || target.second !in 1..65535) {
                writeError(output, "400 Bad Request")
                return
            }
            val uid = findOwnerUid(incoming)
            if (isBlocked(uid)) {
                writeError(output, "403 Forbidden")
                return
            }
            val throttler = throttlerFor(uid)
            val outbound = Socket()
            try {
                if (!protectSocket(outbound)) throw IOException("VpnService.protect failed")
                outbound.connect(InetSocketAddress(target.first, target.second), 15_000)
                outbound.soTimeout = 0
                val remoteIn = BufferedInputStream(outbound.getInputStream())
                val remoteOut = BufferedOutputStream(outbound.getOutputStream())
                if (isConnect) {
                    output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charset.forName("ISO-8859-1")))
                    output.flush()
                } else {
                    val rewritten = rewriteHttpRequest(firstLine, target.first, target.second, header)
                    remoteOut.write(rewritten.toByteArray(Charset.forName("ISO-8859-1")))
                    remoteOut.flush()
                }
                if (isConnect) {
                    val up = workers.submit { pumpThrottled(input, remoteOut, throttler, uid) }
                    pumpThrottled(remoteIn, output, throttler, uid)
                    up.cancel(true)
                } else {
                    forwardHttpRequestBody(input, remoteOut, header, throttler)
                    remoteOut.flush()
                    runCatching { outbound.shutdownOutput() }
                    pumpThrottled(remoteIn, output, throttler, uid)
                }
            } catch (_: Exception) {
                runCatching { writeError(output, "502 Bad Gateway") }
            } finally {
                runCatching { outbound.close() }
            }
        }
    }

    private fun findOwnerUid(client: Socket): Int? {
        val network = networkProvider()
        val activeRules = when (network) {
            ActiveNetworkKind.WIFI -> rulesProvider().filter { it.isEnabledFor(true) }
            ActiveNetworkKind.CELLULAR -> rulesProvider().filter { it.isEnabledFor(false) }
            else -> emptyList()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val clientAddress = client.remoteSocketAddress as? InetSocketAddress
            val serverAddress = client.localSocketAddress as? InetSocketAddress
            if (clientAddress != null && serverAddress != null) {
                // First try the client-facing tuple as requested, and only accept
                // a UID when it corresponds to an enabled rule on this network.
                val primary = queryOwnerUid(clientAddress, serverAddress)
                if (primary != null && activeRules.any { it.uid == primary }) return primary

                // Android/device implementations may interpret the tuple in the
                // opposite direction for an accepted socket; try that as fallback.
                val reverse = queryOwnerUid(serverAddress, clientAddress)
                if (reverse != null && activeRules.any { it.uid == reverse }) return reverse
            }
        }
        // When attribution is unavailable/ambiguous, a single active app rule is
        // a safe deterministic fallback; multiple rules must not be guessed.
        return activeRules.singleOrNull()?.uid
    }

    private fun queryOwnerUid(local: InetSocketAddress, remote: InetSocketAddress): Int? =
        runCatching {
            connectivityManager.getConnectionOwnerUid(
                OsConstants.IPPROTO_TCP,
                local,
                remote
            ).takeIf { it >= 0 }
        }.getOrNull()

    private fun isBlocked(uid: Int?): Boolean {
        val network = networkProvider()
        val isWifi = network == ActiveNetworkKind.WIFI
        val activeRules = when (network) {
            ActiveNetworkKind.WIFI -> rulesProvider().filter { it.isEnabledFor(true) }
            ActiveNetworkKind.CELLULAR -> rulesProvider().filter { it.isEnabledFor(false) }
            else -> emptyList()
        }
        if (uid == null) {
            return activeRules.isNotEmpty() && activeRules.all { it.policyFor(isWifi) == NetworkPolicy.BLOCK }
        }
        return activeRules.any { it.uid == uid && it.policyFor(isWifi) == NetworkPolicy.BLOCK }
    }

    private fun throttlerFor(uid: Int?): TokenBucketThrottler {
        val network = networkProvider()
        val activeRules = when (network) {
            ActiveNetworkKind.WIFI -> rulesProvider().filter { it.isEnabledFor(true) }
            ActiveNetworkKind.CELLULAR -> rulesProvider().filter { it.isEnabledFor(false) }
            else -> emptyList()
        }
        val throttledRules = activeRules.filter { it.policyFor(network == ActiveNetworkKind.WIFI) == NetworkPolicy.THROTTLE && ruleRate(it, network) > 0L }
        val matchedRule = uid?.let { id -> throttledRules.firstOrNull { it.uid == id } }
        val fallbackRule = if (matchedRule == null) throttledRules.minByOrNull { ruleRate(it, network) } else null
        val rule = matchedRule ?: fallbackRule
        val rate = rule?.let { ruleRate(it, network) } ?: defaultLimitProvider()
        val effectiveUid = matchedRule?.uid ?: fallbackRule?.uid ?: uid ?: -1
        val key = "$effectiveUid:$network"
        val bucket = buckets.getOrPut(key) { TokenBucketThrottler(rate.coerceAtLeast(0L)) }
        bucket.bytesPerSecond = rate.coerceAtLeast(0L)
        return bucket
    }

    private fun ruleRate(rule: AppRule, network: ActiveNetworkKind): Long = when (network) {
        ActiveNetworkKind.WIFI -> rule.wifiLimitBytesPerSecond
        ActiveNetworkKind.CELLULAR -> rule.mobileDataLimitBytesPerSecond
        else -> Long.MAX_VALUE
    }

    /** RFC 1928 SOCKS5 CONNECT support on the same loopback port as HTTP proxy. */
    private fun handleSocks5(
        client: Socket,
        input: BufferedInputStream,
        output: BufferedOutputStream
    ) {
        val methodCount = input.read()
        if (methodCount < 0) return
        val methods = ByteArray(methodCount)
        readExact(input, methods, methods.size)
        if (methods.none { (it.toInt() and 0xFF) == SOCKS5_NO_AUTH }) {
            output.write(byteArrayOf(SOCKS5_VERSION.toByte(), 0xFF.toByte()))
            output.flush()
            return
        }
        output.write(byteArrayOf(SOCKS5_VERSION.toByte(), SOCKS5_NO_AUTH.toByte()))
        output.flush()

        val requestHeader = ByteArray(4)
        readExact(input, requestHeader, 4)
        val version = requestHeader[0].toInt() and 0xFF
        val command = requestHeader[1].toInt() and 0xFF
        val addressType = requestHeader[3].toInt() and 0xFF
        if (version != SOCKS5_VERSION || command != SOCKS5_CONNECT) {
            writeSocks5Reply(output, SOCKS5_COMMAND_UNSUPPORTED)
            return
        }
        val host = when (addressType) {
            SOCKS5_IPV4 -> InetAddress.getByAddress(ByteArray(4).also { readExact(input, it, it.size) }).hostAddress
            SOCKS5_DOMAIN -> {
                val length = input.read()
                if (length <= 0) throw IOException("Invalid SOCKS5 domain length")
                val domain = ByteArray(length)
                readExact(input, domain, domain.size)
                String(domain, Charset.forName("UTF-8"))
            }
            SOCKS5_IPV6 -> InetAddress.getByAddress(ByteArray(16).also { readExact(input, it, it.size) }).hostAddress
            else -> {
                writeSocks5Reply(output, SOCKS5_ADDRESS_UNSUPPORTED)
                return
            }
        }
        val portBytes = ByteArray(2)
        readExact(input, portBytes, 2)
        val targetPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)
        if (targetPort !in 1..65535) {
            writeSocks5Reply(output, SOCKS5_GENERAL_FAILURE)
            return
        }

        val uid = findOwnerUid(client)
        if (isBlocked(uid)) {
            writeSocks5Reply(output, SOCKS5_CONNECTION_NOT_ALLOWED)
            return
        }
        val throttler = throttlerFor(uid)
        val outbound = Socket()
        try {
            if (!protectSocket(outbound)) throw IOException("VpnService.protect failed")
            outbound.connect(InetSocketAddress(host, targetPort), 15_000)
            outbound.soTimeout = 0
            writeSocks5Reply(output, SOCKS5_SUCCEEDED)
            val remoteInput = BufferedInputStream(outbound.getInputStream())
            val remoteOutput = BufferedOutputStream(outbound.getOutputStream())
            val uplink = workers.submit { pumpThrottled(input, remoteOutput, throttler, uid) }
            pumpThrottled(remoteInput, output, throttler, uid)
            uplink.cancel(true)
        } catch (_: Exception) {
            runCatching { writeSocks5Reply(output, SOCKS5_GENERAL_FAILURE) }
        } finally {
            runCatching { outbound.close() }
        }
    }

    private fun writeSocks5Reply(output: BufferedOutputStream, status: Int) {
        output.write(byteArrayOf(
            SOCKS5_VERSION.toByte(), status.toByte(), 0x00, SOCKS5_IPV4.toByte(),
            0, 0, 0, 0, 0, 0
        ))
        output.flush()
    }

    /** Copies bytes without dropping them, applying token-bucket pacing before each write. */
    private fun pumpThrottled(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        throttler: TokenBucketThrottler,
        uid: Int?
    ) {
        val buffer = ByteArray(8 * 1024)
        while (!Thread.currentThread().isInterrupted) {
            val count = input.read(buffer)
            if (count < 0) return
            var offset = 0
            while (offset < count && !Thread.currentThread().isInterrupted) {
                val allowed = throttler.acquireUpTo(count - offset)
                if (allowed <= 0) {
                    val waitNanos = throttler.nanosUntilAvailable(1).coerceAtLeast(100_000L)
                    val millis = waitNanos / 1_000_000L
                    val nanos = (waitNanos % 1_000_000L).toInt()
                    Thread.sleep(millis, nanos)
                    continue
                }
                output.write(buffer, offset, allowed)
                TrafficStatsTracker.recordProxyBytes(uid, allowed.toLong())
                offset += allowed
            }
            output.flush()
        }
    }

    private fun forwardHttpRequestBody(
        input: BufferedInputStream,
        output: java.io.OutputStream,
        header: String,
        throttler: TokenBucketThrottler
    ) {
        val transferChunked = header.lineSequence().any {
            it.startsWith("Transfer-Encoding:", true) && it.substringAfter(':').contains("chunked", true)
        }
        if (transferChunked) {
            while (true) {
                val line = readCrlfLine(input, 8192) ?: throw IOException("Unexpected end of chunked request")
                output.writeThrottled(line.toByteArray(Charset.forName("ISO-8859-1")), throttler)
                val size = line.trim().substringBefore(';').toLongOrNull(16)
                    ?: throw IOException("Invalid HTTP chunk size")
                if (size == 0L) {
                    while (true) {
                        val trailer = readCrlfLine(input, 8192) ?: throw IOException("Unexpected end of trailers")
                        output.writeThrottled(trailer.toByteArray(Charset.forName("ISO-8859-1")), throttler)
                        if (trailer == "\r\n") return
                    }
                }
                copyExactThrottled(input, output, size, throttler)
                val ending = ByteArray(2)
                readExact(input, ending, 2)
                output.writeThrottled(ending, throttler)
            }
        }
        val contentLength = header.lineSequence().firstOrNull { it.startsWith("Content-Length:", true) }
            ?.substringAfter(':')?.trim()?.toLongOrNull() ?: 0L
        if (contentLength > 0L) copyExactThrottled(input, output, contentLength, throttler)
    }

    private fun readCrlfLine(input: BufferedInputStream, maxBytes: Int): String? {
        val bytes = ByteArrayOutputStream()
        var previous = -1
        while (bytes.size() <= maxBytes) {
            val value = input.read()
            if (value < 0) return if (bytes.size() == 0) null else throw IOException("Incomplete line")
            bytes.write(value)
            if (previous == 13 && value == 10) return bytes.toString("ISO-8859-1")
            previous = value
        }
        throw IOException("HTTP line too long")
    }

    private fun copyExactThrottled(input: BufferedInputStream, output: java.io.OutputStream, amount: Long, throttler: TokenBucketThrottler) {
        var remaining = amount
        val buffer = ByteArray(8 * 1024)
        while (remaining > 0L) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) throw IOException("Unexpected end of HTTP body")
            output.writeThrottled(buffer.copyOf(count), throttler)
            remaining -= count.toLong()
        }
    }

    private fun readExact(input: BufferedInputStream, target: ByteArray, amount: Int) {
        var offset = 0
        while (offset < amount) {
            val count = input.read(target, offset, amount - offset)
            if (count < 0) throw IOException("Unexpected end of HTTP chunk")
            offset += count
        }
    }

    private fun java.io.OutputStream.writeThrottled(bytes: ByteArray, throttler: TokenBucketThrottler) {
        var offset = 0
        while (offset < bytes.size) {
            val allowed = throttler.acquireUpTo(bytes.size - offset)
            if (allowed <= 0) {
                val waitNanos = throttler.nanosUntilAvailable(1).coerceAtLeast(100_000L)
                Thread.sleep(waitNanos / 1_000_000L, (waitNanos % 1_000_000L).toInt())
            } else {
                write(bytes, offset, allowed)
                offset += allowed
            }
        }
    }

    private fun readHeader(input: BufferedInputStream): String? {
        val out = ByteArrayOutputStream()
        var matched = 0
        while (out.size() < MAX_HEADER_BYTES) {
            val value = input.read()
            if (value < 0) return if (out.size() == 0) null else throw IOException("Incomplete HTTP header")
            out.write(value)
            matched = when {
                matched == 0 && value == 13 -> 1
                matched == 1 && value == 10 -> 2
                matched == 2 && value == 13 -> 3
                matched == 3 && value == 10 -> 4
                value == 13 -> 1
                else -> 0
            }
            if (matched == 4) return out.toString("ISO-8859-1")
        }
        throw IOException("HTTP header too large")
    }

    private fun parseConnectTarget(value: String): Pair<String, Int> {
        val uri = URI("tcp://$value")
        return (uri.host ?: throw IllegalArgumentException("Missing host")) to (uri.port.takeIf { it > 0 } ?: 443)
    }

    private fun parseHttpTarget(value: String, header: String): Pair<String, Int> {
        val uri = URI(value)
        if (uri.scheme != null && !uri.scheme.equals("http", true)) throw IllegalArgumentException("Only HTTP supported")
        val host = uri.host ?: header.lineSequence().firstOrNull { it.startsWith("Host:", true) }
            ?.substringAfter(':')?.trim()?.substringBefore(':')
            ?: throw IllegalArgumentException("Missing host")
        return host to (uri.port.takeIf { it > 0 } ?: 80)
    }

    private fun rewriteHttpRequest(firstLine: String, host: String, port: Int, header: String): String {
        val parts = firstLine.split(' ', limit = 3)
        val uri = URI(parts[1])
        val path = buildString {
            append(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
            uri.rawQuery?.let { append('?').append(it) }
        }
        val rewrittenLine = "${parts[0]} $path ${parts.getOrElse(2) { "HTTP/1.1" }}"
        val headers = header.substringAfter("\r\n", "").split("\r\n")
            .filterNot { it.isBlank() || it.startsWith("Proxy-Connection:", true) }
            .toMutableList()
        if (headers.none { it.startsWith("Connection:", true) }) headers += "Connection: close"
        if (headers.none { it.startsWith("Host:", true) }) headers += "Host: $host${if (port != 80) ":$port" else ""}"
        return rewrittenLine + "\r\n" + headers.joinToString("\r\n") + "\r\n\r\n"
    }

    private fun writeError(output: java.io.OutputStream, status: String) {
        val body = "NetLimiterPro proxy: $status\n"
        output.write("HTTP/1.1 $status\r\nConnection: close\r\nContent-Type: text/plain\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body".toByteArray(Charset.forName("ISO-8859-1")))
        output.flush()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        workers.shutdownNow()
        buckets.clear()
        port = -1
    }

    companion object {
        private const val MAX_HEADER_BYTES = 32 * 1024
        private const val SOCKS5_VERSION = 0x05
        private const val SOCKS5_NO_AUTH = 0x00
        private const val SOCKS5_CONNECT = 0x01
        private const val SOCKS5_SUCCEEDED = 0x00
        private const val SOCKS5_GENERAL_FAILURE = 0x01
        private const val SOCKS5_CONNECTION_NOT_ALLOWED = 0x02
        private const val SOCKS5_COMMAND_UNSUPPORTED = 0x07
        private const val SOCKS5_ADDRESS_UNSUPPORTED = 0x08
        private const val SOCKS5_IPV4 = 0x01
        private const val SOCKS5_DOMAIN = 0x03
        private const val SOCKS5_IPV6 = 0x04
    }
}
