package com.netlimiterpro.service

import android.net.ConnectivityManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.Build
import com.netlimiterpro.domain.model.AppRule
import com.netlimiterpro.domain.model.NetworkPolicy
import com.netlimiterpro.domain.network.ActiveNetworkKind
import com.netlimiterpro.domain.network.TokenBucketThrottler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class ParsedIpPacket(
    val ipVersion: Int,
    val protocolNumber: Int,
    val sourceAddress: InetAddress,
    val destinationAddress: InetAddress,
    val sourcePort: Int?,
    val destinationPort: Int?,
    val packetSize: Int
) {
    val isTcp: Boolean get() = protocolNumber == 6
    val isUdp: Boolean get() = protocolNumber == 17
}

/**
 * TUN packet reader/parser and rate pacing component.
 *
 * Important: Android VpnService provides IP packets, not an automatic
 * forwarding path. This component deliberately does not pretend that writing
 * a packet back to the TUN forwards it to the Internet. A production data
 * plane must relay TCP/UDP through a compatible userspace IP stack/proxy.
 * Until that relay is installed, the service uses a fail-closed policy and
 * will not establish a default-route tunnel that would black-hole traffic.
 */
class PacketShaperEngine(
    private val service: VpnService,
    private val connectivityManager: ConnectivityManager,
    private val onPacketObserved: (ParsedIpPacket, Int?) -> Unit = { _, _ -> }
) {
    private val stopped = AtomicBoolean(true)
    private val throttlers = ConcurrentHashMap<String, TokenBucketThrottler>()
    @Volatile private var activeRules: List<AppRule> = emptyList()
    @Volatile private var activeNetwork: ActiveNetworkKind = ActiveNetworkKind.OFFLINE
    @Volatile private var defaultLimitBytesPerSecond: Long = 0L
    private var readerJob: Job? = null

    /** Atomically refresh policy inputs used by subsequent packets. */
    fun updateRules(
        rules: List<AppRule>,
        networkKind: ActiveNetworkKind,
        defaultLimit: Long
    ) {
        activeRules = rules.toList()
        activeNetwork = networkKind
        defaultLimitBytesPerSecond = defaultLimit.coerceAtLeast(0L)
        val validUids = rules.asSequence().map { it.uid.toString() }.toSet()
        throttlers.keys.removeAll { key ->
            val parts = key.split(':')
            parts.size != 2 || parts[0] !in validUids || parts[1] != networkKind.name
        }
    }

    fun start(
        scope: CoroutineScope,
        tun: ParcelFileDescriptor,
        rules: List<AppRule>,
        networkKind: ActiveNetworkKind
    ) {
        stop()
        updateRules(rules, networkKind, defaultLimitBytesPerSecond)
        stopped.set(false)
        readerJob = scope.launch(Dispatchers.IO) {
            val inputStream = runCatching { FileInputStream(tun.fileDescriptor) }.getOrNull() ?: return@launch
            val outputStream = runCatching { java.io.FileOutputStream(tun.fileDescriptor) }.getOrNull()
            val buffer = ByteArray(32768)
            val isWifi = networkKind == ActiveNetworkKind.WIFI

            while (isActive && !stopped.get()) {
                val count = runCatching { inputStream.read(buffer) }.getOrDefault(-1)
                if (count <= 0) {
                    delay(5L)
                    continue
                }
                val packet = parsePacket(buffer, count) ?: continue
                val uid = findOwnerUid(packet)
                onPacketObserved(packet, uid)

                // Check policy: drop packets for blocked applications
                val isBlocked = if (uid != null) {
                    activeRules.any { it.uid == uid && it.policyFor(isWifi) == NetworkPolicy.BLOCK }
                } else {
                    activeRules.isNotEmpty() && activeRules.all { it.policyFor(isWifi) == NetworkPolicy.BLOCK }
                }
                if (isBlocked) {
                    // Packet dropped silently (Sinkhole firewall cut)
                    continue
                }

                // For non-blocked UDP DNS traffic (port 53), relay via protected socket so resolution works
                if (outputStream != null && packet.isUdp && packet.destinationPort == 53 &&
                    packet.sourceAddress is Inet4Address && packet.destinationAddress is Inet4Address &&
                    count >= 28
                ) {
                    relayDns(packet, buffer, count, outputStream)
                }
            }
            runCatching { inputStream.close() }
            runCatching { outputStream?.close() }
        }
    }

    private fun relayDns(
        packet: ParsedIpPacket,
        buffer: ByteArray,
        packetLength: Int,
        outputStream: java.io.FileOutputStream
    ) {
        val ipHeaderLen = (buffer[0].toInt() and 0x0F) * 4
        val udpHeaderOffset = ipHeaderLen
        val payloadOffset = udpHeaderOffset + 8
        if (payloadOffset > packetLength) return
        val payloadLength = packetLength - payloadOffset
        val queryData = buffer.copyOfRange(payloadOffset, payloadOffset + payloadLength)

        runCatching {
            val socket = java.net.DatagramSocket()
            try {
                if (!service.protect(socket)) return@runCatching
                socket.soTimeout = 2000
                val sendPacket = java.net.DatagramPacket(
                    queryData, queryData.size,
                    packet.destinationAddress, 53
                )
                socket.send(sendPacket)
                val responseBuf = ByteArray(4096)
                val responsePacket = java.net.DatagramPacket(responseBuf, responseBuf.size)
                socket.receive(responsePacket)

                val replyPayload = responsePacket.data.copyOf(responsePacket.length)
                val replyIpPacket = buildIpv4UdpPacket(
                    source = packet.destinationAddress as Inet4Address,
                    destination = packet.sourceAddress as Inet4Address,
                    sourcePort = 53,
                    destinationPort = packet.sourcePort ?: 53,
                    payload = replyPayload
                )
                synchronized(outputStream) {
                    outputStream.write(replyIpPacket)
                    outputStream.flush()
                }
            } finally {
                socket.close()
            }
        }
    }

    suspend fun stopAndJoin() {
        stopped.set(true)
        readerJob?.cancelAndJoin()
        readerJob = null
    }

    fun stop() {
        stopped.set(true)
        readerJob?.cancel()
        readerJob = null
    }

    fun parsePacket(bytes: ByteArray, length: Int = bytes.size): ParsedIpPacket? {
        if (length < 1 || length > bytes.size) return null
        val version = (bytes[0].toInt() ushr 4) and 0x0F
        return when (version) {
            4 -> parseIpv4(bytes, length)
            6 -> parseIpv6(bytes, length)
            else -> null
        }
    }

    fun findOwnerUid(packet: ParsedIpPacket): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (!packet.isTcp && !packet.isUdp) return null
        val sourcePort = packet.sourcePort ?: return null
        val destinationPort = packet.destinationPort ?: return null
        return runCatching {
            connectivityManager.getConnectionOwnerUid(
                packet.protocolNumber,
                InetSocketAddress(packet.sourceAddress, sourcePort),
                InetSocketAddress(packet.destinationAddress, destinationPort)
            )
        }.getOrNull()?.takeIf { it >= 0 }
    }

    fun throttlerFor(uid: Int, networkKind: ActiveNetworkKind, rules: List<AppRule>): TokenBucketThrottler? {
        val rule = rules.firstOrNull { it.uid == uid } ?: return null
        val policy = when (networkKind) {
            ActiveNetworkKind.WIFI -> rule.wifiPolicy
            ActiveNetworkKind.CELLULAR -> rule.mobilePolicy
            ActiveNetworkKind.OTHER, ActiveNetworkKind.OFFLINE -> NetworkPolicy.ALLOW
        }
        if (policy != NetworkPolicy.THROTTLE) return null
        val rate = when (networkKind) {
            ActiveNetworkKind.WIFI -> rule.wifiLimitBytesPerSecond
            ActiveNetworkKind.CELLULAR -> rule.mobileDataLimitBytesPerSecond
            else -> 0L
        }
        if (rate <= 0L) return null
        val key = "$uid:${networkKind.name}"
        return throttlers.compute(key) { _, existing ->
            if (existing == null) TokenBucketThrottler(rate)
            else {
                existing.bytesPerSecond = rate
                existing
            }
        }
    }

    private fun parseIpv4(bytes: ByteArray, length: Int): ParsedIpPacket? {
        if (length < 20) return null
        val headerLength = (bytes[0].toInt() and 0x0F) * 4
        if (headerLength < 20 || length < headerLength) return null
        val totalLength = u16(bytes, 2).coerceAtMost(length)
        if (totalLength < headerLength) return null
        val protocol = bytes[9].toInt() and 0xFF
        val source = InetAddress.getByAddress(bytes.copyOfRange(12, 16))
        val destination = InetAddress.getByAddress(bytes.copyOfRange(16, 20))
        val ports = parsePorts(bytes, totalLength, headerLength, protocol)
        return ParsedIpPacket(4, protocol, source, destination, ports.first, ports.second, totalLength)
    }

    private fun parseIpv6(bytes: ByteArray, length: Int): ParsedIpPacket? {
        if (length < 40) return null
        val payloadLength = u16(bytes, 4)
        val totalLength = (40 + payloadLength).coerceAtMost(length)
        var nextHeader = bytes[6].toInt() and 0xFF
        var offset = 40
        // Walk common IPv6 extension headers with strict bounds.
        var hops = 0
        while (nextHeader in IPV6_EXTENSION_HEADERS && hops < 8) {
            if (offset + 2 > totalLength) return null
            val current = nextHeader
            nextHeader = bytes[offset].toInt() and 0xFF
            val extLength = when (current) {
                44 -> 8
                51 -> ((bytes[offset + 1].toInt() and 0xFF) + 2) * 4
                else -> ((bytes[offset + 1].toInt() and 0xFF) + 1) * 8
            }
            if (extLength <= 0 || offset + extLength > totalLength) return null
            offset += extLength
            hops++
        }
        val source = Inet6Address.getByAddress(null, bytes.copyOfRange(8, 24), -1)
        val destination = Inet6Address.getByAddress(null, bytes.copyOfRange(24, 40), -1)
        val ports = parsePorts(bytes, totalLength, offset, nextHeader)
        return ParsedIpPacket(6, nextHeader, source, destination, ports.first, ports.second, totalLength)
    }

    private fun parsePorts(
        bytes: ByteArray,
        totalLength: Int,
        offset: Int,
        protocol: Int
    ): Pair<Int?, Int?> {
        if ((protocol != 6 && protocol != 17) || offset + 4 > totalLength) return null to null
        return u16(bytes, offset) to u16(bytes, offset + 2)
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    companion object {
        private val IPV6_EXTENSION_HEADERS = setOf(0, 43, 44, 60, 51)

        /** Builds a valid IPv4/UDP datagram, including IPv4 and UDP checksums. */
        fun buildIpv4UdpPacket(
            source: Inet4Address,
            destination: Inet4Address,
            sourcePort: Int,
            destinationPort: Int,
            payload: ByteArray,
            identification: Int = 0
        ): ByteArray {
            require(sourcePort in 1..65535 && destinationPort in 1..65535)
            require(payload.size <= 65507) { "IPv4 UDP payload exceeds maximum datagram size" }
            val udpLength = 8 + payload.size
            val packet = ByteArray(20 + udpLength)
            packet[0] = 0x45
            packet[1] = 0
            putU16(packet, 2, packet.size)
            putU16(packet, 4, identification and 0xFFFF)
            putU16(packet, 6, 0x4000) // Don't fragment.
            packet[8] = 64
            packet[9] = 17
            putU16(packet, 10, 0)
            System.arraycopy(source.address, 0, packet, 12, 4)
            System.arraycopy(destination.address, 0, packet, 16, 4)
            putU16(packet, 10, internetChecksum(packet, 0, 20))

            val udpOffset = 20
            putU16(packet, udpOffset, sourcePort)
            putU16(packet, udpOffset + 2, destinationPort)
            putU16(packet, udpOffset + 4, udpLength)
            putU16(packet, udpOffset + 6, 0)
            System.arraycopy(payload, 0, packet, udpOffset + 8, payload.size)
            val pseudoHeader = ByteArray(12 + udpLength)
            System.arraycopy(source.address, 0, pseudoHeader, 0, 4)
            System.arraycopy(destination.address, 0, pseudoHeader, 4, 4)
            pseudoHeader[9] = 17
            putU16(pseudoHeader, 10, udpLength)
            System.arraycopy(packet, udpOffset, pseudoHeader, 12, udpLength)
            val udpChecksum = internetChecksum(pseudoHeader, 0, pseudoHeader.size).let { if (it == 0) 0xFFFF else it }
            putU16(packet, udpOffset + 6, udpChecksum)
            return packet
        }

        /** RFC 1071 one's-complement checksum over a byte range. */
        fun internetChecksum(bytes: ByteArray, offset: Int, length: Int): Int {
            require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
            var sum = 0L
            var index = offset
            val end = offset + length
            while (index + 1 < end) {
                sum += (((bytes[index].toInt() and 0xFF) shl 8) or (bytes[index + 1].toInt() and 0xFF)).toLong()
                index += 2
            }
            if (index < end) sum += ((bytes[index].toInt() and 0xFF) shl 8).toLong()
            while ((sum ushr 16) != 0L) sum = (sum and 0xFFFFL) + (sum ushr 16)
            return sum.inv().toInt() and 0xFFFF
        }

        private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = (value ushr 8).toByte()
            bytes[offset + 1] = value.toByte()
        }
    }
}
