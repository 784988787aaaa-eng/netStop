package com.example.network.packet

import com.example.core.model.IpVersion
import com.example.core.model.PacketProtocol
import java.net.InetAddress

/**
 * بيانات رأس الحزمة الشبكية المستخلصة دون قراءة محتوى المستخدم (حفاظًا على الخصوصية - القسم التاسع).
 */
data class ParsedPacketHeader(
    val ipVersion: IpVersion,
    val protocol: PacketProtocol,
    val rawProtocolNumber: Int,
    val sourceIp: String,
    val destinationIp: String,
    val sourcePort: Int,
    val destinationPort: Int,
    val totalLengthBytes: Int,
    val headerLengthBytes: Int,
    val payloadLengthBytes: Int,
    val tcpSyn: Boolean = false,
    val tcpAck: Boolean = false,
    val tcpFin: Boolean = false,
    val tcpRst: Boolean = false,
    val dnsQueryDomain: String? = null,
    val exceedsMtu: Boolean = false
) {
    fun toFiveTupleKey(): String {
        return "${ipVersion.label}:${protocol.label}:$sourceIp:$sourcePort->$destinationIp:$destinationPort"
    }
}

/**
 * محلل رؤوس حزم IPv4 و IPv6 و TCP و UDP و DNS (القسم الرابع).
 * يعمل بدون تخصيص كائنات ثقيلة ويتحقق من سلامة أطوال الرؤوس وحدود MTU.
 */
object IpPacketParser {

    const val DEFAULT_VPN_MTU = 1400

    fun parse(buffer: ByteArray, length: Int, mtu: Int = DEFAULT_VPN_MTU): ParsedPacketHeader? {
        if (length < 20 || buffer.size < length) return null

        val version = (buffer[0].toInt() ushr 4) and 0x0F
        return when (version) {
            4 -> parseIpv4(buffer, length, mtu)
            6 -> parseIpv6(buffer, length, mtu)
            else -> null
        }
    }

    private fun parseIpv4(buffer: ByteArray, length: Int, mtu: Int): ParsedPacketHeader? {
        val ihlWords = buffer[0].toInt() and 0x0F
        val ipHeaderLen = ihlWords * 4
        if (ipHeaderLen < 20 || length < ipHeaderLen) return null

        val totalLen = ((buffer[2].toInt() and 0xFF) shl 8) or (buffer[3].toInt() and 0xFF)
        val effectiveLen = if (totalLen in ipHeaderLen..length) totalLen else length
        val protoNum = buffer[9].toInt() and 0xFF

        val srcIp = formatIpv4(buffer, 12)
        val dstIp = formatIpv4(buffer, 16)

        return parseTransportLayer(
            buffer = buffer,
            totalLength = effectiveLen,
            transportOffset = ipHeaderLen,
            ipVersion = IpVersion.IPV4,
            protoNum = protoNum,
            srcIp = srcIp,
            dstIp = dstIp,
            mtu = mtu
        )
    }

    private fun parseIpv6(buffer: ByteArray, length: Int, mtu: Int): ParsedPacketHeader? {
        val ipHeaderLen = 40
        if (length < ipHeaderLen) return null

        val payloadLen = ((buffer[4].toInt() and 0xFF) shl 8) or (buffer[5].toInt() and 0xFF)
        val effectiveLen = (ipHeaderLen + payloadLen).coerceAtMost(length)
        val nextHeader = buffer[6].toInt() and 0xFF

        val srcBytes = buffer.copyOfRange(8, 24)
        val dstBytes = buffer.copyOfRange(24, 40)
        val srcIp = try {
            InetAddress.getByAddress(srcBytes).hostAddress ?: "::"
        } catch (e: Exception) {
            "::"
        }
        val dstIp = try {
            InetAddress.getByAddress(dstBytes).hostAddress ?: "::"
        } catch (e: Exception) {
            "::"
        }

        return parseTransportLayer(
            buffer = buffer,
            totalLength = effectiveLen,
            transportOffset = ipHeaderLen,
            ipVersion = IpVersion.IPV6,
            protoNum = nextHeader,
            srcIp = srcIp,
            dstIp = dstIp,
            mtu = mtu
        )
    }

    private fun parseTransportLayer(
        buffer: ByteArray,
        totalLength: Int,
        transportOffset: Int,
        ipVersion: IpVersion,
        protoNum: Int,
        srcIp: String,
        dstIp: String,
        mtu: Int
    ): ParsedPacketHeader {
        var srcPort = 0
        var dstPort = 0
        var tcpSyn = false
        var tcpAck = false
        var tcpFin = false
        var tcpRst = false
        var dnsDomain: String? = null
        var transportHeaderLen = 0

        when (protoNum) {
            6 -> { // TCP
                if (totalLength >= transportOffset + 20) {
                    srcPort = readUint16(buffer, transportOffset)
                    dstPort = readUint16(buffer, transportOffset + 2)
                    val dataOffsetWords = (buffer[transportOffset + 12].toInt() ushr 4) and 0x0F
                    transportHeaderLen = (dataOffsetWords * 4).coerceAtLeast(20)
                    val flags = buffer[transportOffset + 13].toInt() and 0xFF
                    tcpFin = (flags and 0x01) != 0
                    tcpSyn = (flags and 0x02) != 0
                    tcpRst = (flags and 0x04) != 0
                    tcpAck = (flags and 0x10) != 0
                }
            }
            17 -> { // UDP
                if (totalLength >= transportOffset + 8) {
                    srcPort = readUint16(buffer, transportOffset)
                    dstPort = readUint16(buffer, transportOffset + 2)
                    transportHeaderLen = 8
                    if (dstPort == 53 || srcPort == 53) {
                        dnsDomain = parseDnsQName(buffer, transportOffset + 8, totalLength)
                    }
                }
            }
            1, 58 -> { // ICMP / ICMPv6
                transportHeaderLen = 8
            }
        }

        val protocol = when {
            protoNum == 17 && (dstPort == 53 || srcPort == 53) -> PacketProtocol.DNS
            protoNum == 6 -> PacketProtocol.TCP
            protoNum == 17 -> PacketProtocol.UDP
            protoNum == 1 -> PacketProtocol.ICMP
            protoNum == 58 -> PacketProtocol.ICMPV6
            else -> PacketProtocol.OTHER
        }

        val totalHeaders = (transportOffset + transportHeaderLen).coerceAtMost(totalLength)
        val payloadBytes = (totalLength - totalHeaders).coerceAtLeast(0)

        return ParsedPacketHeader(
            ipVersion = ipVersion,
            protocol = protocol,
            rawProtocolNumber = protoNum,
            sourceIp = srcIp,
            destinationIp = dstIp,
            sourcePort = srcPort,
            destinationPort = dstPort,
            totalLengthBytes = totalLength,
            headerLengthBytes = totalHeaders,
            payloadLengthBytes = payloadBytes,
            tcpSyn = tcpSyn,
            tcpAck = tcpAck,
            tcpFin = tcpFin,
            tcpRst = tcpRst,
            dnsQueryDomain = dnsDomain,
            exceedsMtu = totalLength > mtu
        )
    }

    private fun parseDnsQName(buffer: ByteArray, dnsOffset: Int, totalLength: Int): String? {
        // رأس DNS الثابت = 12 بايت
        var pos = dnsOffset + 12
        if (pos >= totalLength) return null

        val parts = mutableListOf<String>()
        var jumps = 0
        while (pos < totalLength && jumps < 12) {
            val labelLen = buffer[pos].toInt() and 0xFF
            if (labelLen == 0) break
            // تجاهل مؤشرات الضغط في استعلام QNAME الأولي
            if ((labelLen and 0xC0) == 0xC0) break
            pos++
            if (pos + labelLen > totalLength) return null
            val sb = StringBuilder(labelLen)
            for (i in 0 until labelLen) {
                val c = (buffer[pos + i].toInt() and 0xFF).toChar()
                if (c.isLetterOrDigit() || c == '-' || c == '_') {
                    sb.append(c)
                }
            }
            if (sb.isNotEmpty()) parts.add(sb.toString())
            pos += labelLen
            jumps++
        }
        return if (parts.isNotEmpty()) parts.joinToString(".") else null
    }

    private fun formatIpv4(b: ByteArray, offset: Int): String {
        return "${b[offset].toInt() and 0xFF}.${b[offset + 1].toInt() and 0xFF}.${b[offset + 2].toInt() and 0xFF}.${b[offset + 3].toInt() and 0xFF}"
    }

    private fun readUint16(b: ByteArray, offset: Int): Int {
        return ((b[offset].toInt() and 0xFF) shl 8) or (b[offset + 1].toInt() and 0xFF)
    }
}
