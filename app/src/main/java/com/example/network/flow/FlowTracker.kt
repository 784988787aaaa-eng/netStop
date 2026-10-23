package com.example.network.flow

import com.example.core.model.FlowRecord
import com.example.core.model.FlowState
import com.example.network.packet.ParsedPacketHeader
import java.util.concurrent.ConcurrentHashMap

/**
 * متتبع التدفقات الشبكية (Flow Tracker - القسم الرابع).
 * يدير جدول التدفقات بذاكرة محدودة السعة (Bounded Capacity) لمنع استنزاف الذاكرة تحت الأحمال العالية.
 */
class FlowTracker(
    val maxCapacity: Int = 2048,
    private val tcpIdleTimeoutMillis: Long = 120_000L,
    private val udpIdleTimeoutMillis: Long = 30_000L
) {
    private val flowMap = ConcurrentHashMap<String, FlowRecord>()

    fun recordPacket(
        header: ParsedPacketHeader,
        uid: Int,
        packageName: String,
        appName: String,
        isOutbound: Boolean,
        targetState: FlowState? = null,
        nowMillis: Long = System.currentTimeMillis()
    ): FlowRecord {
        val key = header.toFiveTupleKey()
        val existing = flowMap[key]

        if (existing == null && flowMap.size >= maxCapacity) {
            evictExpiredOrOldest(nowMillis)
        }

        val resolvedState = when {
            targetState != null -> targetState
            header.tcpFin || header.tcpRst -> FlowState.CLOSING
            header.tcpSyn && !header.tcpAck -> FlowState.NEW
            else -> FlowState.ESTABLISHED
        }

        val updated = if (existing == null) {
            FlowRecord(
                flowKey = key,
                uid = uid,
                packageName = packageName,
                appName = appName,
                ipVersion = header.ipVersion,
                protocol = header.protocol,
                sourceIp = header.sourceIp,
                sourcePort = header.sourcePort,
                destinationIp = header.destinationIp,
                destinationPort = header.destinationPort,
                dnsQueryHost = header.dnsQueryDomain,
                bytesDown = if (!isOutbound) header.totalLengthBytes.toLong() else 0L,
                bytesUp = if (isOutbound) header.totalLengthBytes.toLong() else 0L,
                packetsDown = if (!isOutbound) 1 else 0,
                packetsUp = if (isOutbound) 1 else 0,
                state = resolvedState,
                createdAtMillis = nowMillis,
                lastSeenMillis = nowMillis
            )
        } else {
            existing.copy(
                dnsQueryHost = header.dnsQueryDomain ?: existing.dnsQueryHost,
                bytesDown = existing.bytesDown + if (!isOutbound) header.totalLengthBytes else 0,
                bytesUp = existing.bytesUp + if (isOutbound) header.totalLengthBytes else 0,
                packetsDown = existing.packetsDown + if (!isOutbound) 1 else 0,
                packetsUp = existing.packetsUp + if (isOutbound) 1 else 0,
                state = resolvedState,
                lastSeenMillis = nowMillis
            )
        }

        flowMap[key] = updated
        return updated
    }

    fun getActiveFlows(): List<FlowRecord> {
        return flowMap.values.sortedByDescending { it.lastSeenMillis }
    }

    fun getFlowsForUid(uid: Int): List<FlowRecord> {
        return flowMap.values.filter { it.uid == uid }.sortedByDescending { it.lastSeenMillis }
    }

    fun activeFlowCount(): Int = flowMap.size

    /**
     * تنظيف التدفقات المنتهية أو الخاملة وإخلاء أقدم التدفقات عند امتلاء الجدول.
     */
    fun evictExpiredOrOldest(nowMillis: Long = System.currentTimeMillis()): Int {
        var removedCount = 0
        val iterator = flowMap.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            val timeout = if (entry.protocol.number == 6) tcpIdleTimeoutMillis else udpIdleTimeoutMillis
            if (entry.state == FlowState.CLOSING || entry.state == FlowState.EXPIRED || (nowMillis - entry.lastSeenMillis) > timeout) {
                iterator.remove()
                removedCount++
            }
        }

        if (flowMap.size >= maxCapacity) {
            val oldestKeys = flowMap.values
                .sortedBy { it.lastSeenMillis }
                .take((maxCapacity / 8).coerceAtLeast(1))
                .map { it.flowKey }
            oldestKeys.forEach { key ->
                if (flowMap.remove(key) != null) removedCount++
            }
        }
        return removedCount
    }

    fun clearAll() {
        flowMap.clear()
    }
}
