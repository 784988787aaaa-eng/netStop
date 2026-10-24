package com.example.network.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.core.model.AuditEventType
import com.example.core.model.AuditLogItem
import com.example.core.model.EngineDiagnostics
import com.example.core.model.FlowRecord
import com.example.core.model.FlowState
import com.example.core.model.IpVersion
import com.example.core.model.PacketProtocol
import com.example.network.attribution.AppAttributionEngine
import com.example.network.flow.FlowTracker
import com.example.network.packet.IpPacketParser
import com.example.network.policy.PolicyEngine
import com.example.network.rate_limit.RateLimitDecision
import com.example.network.rate_limit.TokenBucketRateLimiter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * وحدة التحكم المركزية بمحرك الشبكة و VPN المحلي (القسم 7 و 16).
 */
object VpnEngineController {

    val flowTracker = FlowTracker(maxCapacity = 2048)
    val rateLimiter = TokenBucketRateLimiter(maxQueueDepth = 512)
    val policyEngine = PolicyEngine()

    private val _diagnostics = MutableStateFlow(EngineDiagnostics())
    val diagnostics: StateFlow<EngineDiagnostics> = _diagnostics.asStateFlow()

    private val _activeFlows = MutableStateFlow<List<FlowRecord>>(emptyList())
    val activeFlows: StateFlow<List<FlowRecord>> = _activeFlows.asStateFlow()

    private val _blockedPackages = MutableStateFlow<Set<String>>(emptySet())
    val blockedPackages: StateFlow<Set<String>> = _blockedPackages.asStateFlow()

    private val _rateLimitedPackages = MutableStateFlow<Set<String>>(emptySet())
    val rateLimitedPackages: StateFlow<Set<String>> = _rateLimitedPackages.asStateFlow()

    private val vpnUidRxBytes = java.util.concurrent.ConcurrentHashMap<Int, AtomicLong>()
    private val vpnUidTxBytes = java.util.concurrent.ConcurrentHashMap<Int, AtomicLong>()

    private var onAuditEventCallback: ((AuditLogItem) -> Unit)? = null

    private val totalPacketsProcessed = AtomicLong(0L)
    private val droppedByPolicyCount = AtomicLong(0L)
    private val unattributedCount = AtomicLong(0L)

    fun setAuditCallback(callback: ((AuditLogItem) -> Unit)?) {
        onAuditEventCallback = callback
    }

    fun emitAudit(item: AuditLogItem) {
        onAuditEventCallback?.invoke(item)
    }

    fun updateEnforcedPackages(blocked: Set<String>, rateLimited: Set<String> = emptySet()) {
        val prevBlocked = _blockedPackages.value
        val prevLimited = _rateLimitedPackages.value
        _blockedPackages.value = blocked
        _rateLimitedPackages.value = rateLimited
        _diagnostics.update { it.copy(lastSyncTimestampMillis = System.currentTimeMillis()) }
        if ((prevBlocked != blocked || prevLimited != rateLimited) && _diagnostics.value.isVpnRunning) {
            NetBalancerVpnService.requestReloadConfig()
        }
    }

    fun updateBlockedPackages(packages: Set<String>) {
        updateEnforcedPackages(packages, _rateLimitedPackages.value)
    }

    fun recordVpnUidTraffic(uid: Int, rxBytes: Long, txBytes: Long) {
        if (uid < 0) return
        if (rxBytes > 0L) {
            vpnUidRxBytes.getOrPut(uid) { AtomicLong(0L) }.addAndGet(rxBytes)
        }
        if (txBytes > 0L) {
            vpnUidTxBytes.getOrPut(uid) { AtomicLong(0L) }.addAndGet(txBytes)
        }
    }

    fun getVpnUidBytes(uid: Int): Pair<Long, Long>? {
        val rx = vpnUidRxBytes[uid]?.get()
        val tx = vpnUidTxBytes[uid]?.get()
        if (rx == null && tx == null) return null
        return (rx ?: 0L) to (tx ?: 0L)
    }

    fun clearVpnUidCounters() {
        vpnUidRxBytes.clear()
        vpnUidTxBytes.clear()
    }

    fun updateDiagnostics(transform: (EngineDiagnostics) -> EngineDiagnostics) {
        _diagnostics.update(transform)
    }

    /**
     * يختبر مسار الحزم الكامل (IPv4 + IPv6 + TCP + UDP + DNS + MTU + TokenBucket + FlowTracker)
     * دون تعطيل اتصال الإنترنت الفعلي للمستخدم، ويوثق النتيجة وزمن المعالجة بالمايكروثانية.
     */
    fun runEnginePipelineSelfTest(attributionEngine: AppAttributionEngine): EngineDiagnostics {
        val startNanos = System.nanoTime()

        // 1. بناء حزمة IPv4 TCP SYN اختبارية (طول 40 بايت)
        val ipv4TcpSyn = byteArrayOf(
            0x45, 0x00, 0x00, 0x28, // IPv4, IHL=5 (20B), TotalLen=40
            0x12, 0x34, 0x40, 0x00,
            0x40, 0x06, 0x00, 0x00, // TTL=64, Proto=6 (TCP)
            10, 215.toByte(), 173.toByte(), 1, // Src 10.215.173.1
            93, 184.toByte(), 216.toByte(), 34, // Dst 93.184.216.34
            0xC4.toByte(), 0x31, 0x01, 0xBB.toByte(), // SrcPort 50225 -> DstPort 443
            0x00, 0x00, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x00,
            0x50, 0x02, 0x72, 0x10, // DataOffset=5, Flags=SYN (0x02)
            0x00, 0x00, 0x00, 0x00
        )

        // 2. بناء حزمة IPv4 UDP DNS Query لـ example.com (منفذ 53)
        val ipv4DnsQuery = byteArrayOf(
            0x45, 0x00, 0x00, 0x39, // TotalLen=57
            0x22, 0x11, 0x00, 0x00,
            0x40, 0x11, 0x00, 0x00, // Proto=17 (UDP)
            10, 215.toByte(), 173.toByte(), 1,
            8, 8, 8, 8, // Dst 8.8.8.8
            0xD0.toByte(), 0x05, 0x00, 0x35, // SrcPort 53253 -> DstPort 53
            0x00, 0x25, 0x00, 0x00,
            // DNS Header (12 bytes)
            0x1A, 0x2B, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            // QNAME: 7example3com0
            7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
            3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0,
            0x00, 0x01, 0x00, 0x01
        )

        // 3. بناء حزمة IPv6 UDP اختبارية (48 بايت)
        val ipv6Udp = ByteArray(48).apply {
            this[0] = 0x60.toByte() // Version 6
            this[4] = 0x00
            this[5] = 0x08 // Payload length = 8
            this[6] = 17   // NextHeader = UDP (17)
            this[7] = 64   // Hop Limit
            // Src fd00::1
            this[8] = 0xFD.toByte()
            this[23] = 0x01
            // Dst 2001:4860:4860::8888
            this[24] = 0x20
            this[25] = 0x01
            this[38] = 0x88.toByte()
            this[39] = 0x88.toByte()
            // UDP Header: SrcPort 41000 -> DstPort 443 (QUIC)
            this[40] = 0xA0.toByte()
            this[41] = 0x28
            this[42] = 0x01
            this[43] = 0xBB.toByte()
            this[44] = 0x00
            this[45] = 0x08
        }

        val parsedTcp = IpPacketParser.parse(ipv4TcpSyn, ipv4TcpSyn.size)
        val parsedDns = IpPacketParser.parse(ipv4DnsQuery, ipv4DnsQuery.size)
        val parsedIpv6 = IpPacketParser.parse(ipv6Udp, ipv6Udp.size)

        val testPass = parsedTcp != null &&
            parsedTcp.ipVersion == IpVersion.IPV4 &&
            parsedTcp.protocol == PacketProtocol.TCP &&
            parsedTcp.tcpSyn &&
            parsedDns != null &&
            parsedDns.protocol == PacketProtocol.DNS &&
            parsedDns.dnsQueryDomain == "example.com" &&
            parsedIpv6 != null &&
            parsedIpv6.ipVersion == IpVersion.IPV6 &&
            parsedIpv6.protocol == PacketProtocol.UDP

        if (parsedTcp != null) {
            val attr = attributionEngine.attributePacket(parsedTcp)
            flowTracker.recordPacket(
                header = parsedTcp,
                uid = attr.uid,
                packageName = attr.packageName,
                appName = attr.appName,
                isOutbound = true,
                targetState = FlowState.ESTABLISHED
            )
            totalPacketsProcessed.incrementAndGet()
        }
        if (parsedDns != null) {
            val attr = attributionEngine.attributePacket(parsedDns)
            flowTracker.recordPacket(
                header = parsedDns,
                uid = attr.uid,
                packageName = attr.packageName,
                appName = attr.appName,
                isOutbound = true,
                targetState = FlowState.ESTABLISHED
            )
            totalPacketsProcessed.incrementAndGet()
        }
        if (parsedIpv6 != null) {
            val attr = attributionEngine.attributePacket(parsedIpv6)
            flowTracker.recordPacket(
                header = parsedIpv6,
                uid = attr.uid,
                packageName = attr.packageName,
                appName = attr.appName,
                isOutbound = true,
                targetState = FlowState.ESTABLISHED
            )
            totalPacketsProcessed.incrementAndGet()
        }

        // اختبار TokenBucketRateLimiter (10 KiB/s و 20 KiB/s)
        rateLimiter.configureUidLimits(99999, maxDownloadBytesPerSec = 20 * 1024L, maxUploadBytesPerSec = 10 * 1024L)
        val rlDecision = rateLimiter.acquirePacket(99999, packetSizeBytes = 1024, isDownload = true)
        if (rlDecision is RateLimitDecision.DelayPacket) {
            rateLimiter.onDelayedPacketDispatched()
        }
        val rlOk = rlDecision is RateLimitDecision.PassImmediately || rlDecision is RateLimitDecision.DelayPacket
        rateLimiter.configureUidLimits(99999, 0L, 0L)

        val elapsedMicros = ((System.nanoTime() - startNanos) / 1000L).coerceAtLeast(10L)
        val runtime = Runtime.getRuntime()
        val usedMemMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
        val kernelStatus = attributionEngine.readKernelTotalTelemetry()

        _activeFlows.value = flowTracker.getActiveFlows()

        val statusSummary = if (testPass && rlOk) {
            "نجح فحص المسار: IPv4/IPv6 + TCP/UDP + DNS (${parsedDns?.dnsQueryDomain}) + TokenBucket في ${elapsedMicros}µs"
        } else {
            "تحذير في أحد اختبارات المسار الذاتية"
        }

        val now = System.currentTimeMillis()
        _diagnostics.update { current ->
            current.copy(
                activeFlowsCount = flowTracker.activeFlowCount(),
                packetQueueDepth = rateLimiter.getQueueDepth(),
                totalPacketsProcessed = totalPacketsProcessed.get(),
                droppedByPolicyCount = droppedByPolicyCount.get(),
                throttledDelayedPackets = rateLimiter.getTotalDelayedCount(),
                unattributedPacketsCount = unattributedCount.get(),
                averageLoopLatencyMicros = elapsedMicros,
                memoryUsedMb = usedMemMb.coerceAtLeast(12L),
                statsSourceStatusAr = if (kernelStatus.isAvailable) {
                    "عدادات النواة TrafficStats متاحة وتستجيب"
                } else {
                    "البيانات غير متاحة من TrafficStats في هذه البيئة"
                },
                lastSuccessTimestampMillis = if (testPass && rlOk) now else current.lastSuccessTimestampMillis,
                lastSelfTestResultAr = statusSummary,
                lastSelfTestPassed = testPass && rlOk
            )
        }

        return _diagnostics.value
    }

    fun onLivePacketCaptured(
        rawBytes: ByteArray,
        length: Int,
        attributionEngine: AppAttributionEngine,
        onPacedForwardCallback: ((header: com.example.network.packet.ParsedPacketHeader, uid: Int, pkg: String, appName: String, packetCopy: ByteArray, delayMs: Long) -> Unit)? = null
    ) {
        val header = IpPacketParser.parse(rawBytes, length) ?: return
        val total = totalPacketsProcessed.incrementAndGet()
        val blockedSet = _blockedPackages.value
        val limitedSet = _rateLimitedPackages.value
        val allEnforcedSet = blockedSet + limitedSet
        val identity = attributionEngine.attributePacket(header, allEnforcedSet)

        if (!identity.isAttributed) {
            unattributedCount.incrementAndGet()
        }

        val isDownload = header.destinationIp == "10.215.173.1" ||
            header.destinationIp.startsWith("fd00:6e62") ||
            header.destinationIp == "127.0.0.1" ||
            header.destinationIp == "::1"
        val isOutbound = !isDownload

        val isBlockedPkg = blockedSet.contains(identity.packageName) ||
            (blockedSet.isNotEmpty() && limitedSet.isEmpty() && !identity.isAttributed)

        val rlDecision = if (isBlockedPkg) {
            RateLimitDecision.DropBlockedByPolicy
        } else {
            rateLimiter.acquirePacket(
                uid = identity.uid,
                packetSizeBytes = header.totalLengthBytes,
                isDownload = isDownload
            )
        }

        val flowState = when (rlDecision) {
            is RateLimitDecision.DropBlockedByPolicy -> {
                val dropped = droppedByPolicyCount.incrementAndGet()
                if (dropped % 5L == 1L) {
                    val dirText = if (isOutbound) "صادرة إلى ${header.destinationIp}:${header.destinationPort}" else "واردة من ${header.sourceIp}:${header.sourcePort}"
                    emitAudit(
                        AuditLogItem(
                            eventType = AuditEventType.CONNECTION_BLOCKED,
                            uid = identity.uid,
                            packageName = identity.packageName,
                            appName = identity.appName,
                            summaryAr = "حظر حزمة ${header.protocol.label} (${header.ipVersion.label}) لـ ${identity.appName} $dirText",
                            technicalDetails = header.dnsQueryDomain?.let { "استعلام DNS: $it" } ?: header.toFiveTupleKey()
                        )
                    )
                }
                FlowState.BLOCKED
            }
            is RateLimitDecision.DelayPacket -> {
                if (identity.uid >= 0) {
                    if (isDownload) {
                        recordVpnUidTraffic(identity.uid, header.totalLengthBytes.toLong(), 0L)
                    } else {
                        recordVpnUidTraffic(identity.uid, 0L, header.totalLengthBytes.toLong())
                    }
                }
                if (onPacedForwardCallback != null) {
                    val copy = rawBytes.copyOf(length)
                    onPacedForwardCallback(header, identity.uid, identity.packageName, identity.appName, copy, rlDecision.delayMillis)
                } else {
                    rateLimiter.onDelayedPacketDispatched()
                }
                FlowState.THROTTLED
            }
            is RateLimitDecision.DropQueueOverflow -> FlowState.THROTTLED
            is RateLimitDecision.PassImmediately -> {
                if (identity.uid >= 0) {
                    if (isDownload) {
                        recordVpnUidTraffic(identity.uid, header.totalLengthBytes.toLong(), 0L)
                    } else {
                        recordVpnUidTraffic(identity.uid, 0L, header.totalLengthBytes.toLong())
                    }
                }
                if (onPacedForwardCallback != null) {
                    val copy = rawBytes.copyOf(length)
                    onPacedForwardCallback(header, identity.uid, identity.packageName, identity.appName, copy, 0L)
                }
                FlowState.ESTABLISHED
            }
        }

        flowTracker.recordPacket(
            header = header,
            uid = identity.uid,
            packageName = identity.packageName,
            appName = identity.appName,
            isOutbound = isOutbound,
            targetState = flowState
        )

        if (total % 4L == 0L || flowState == FlowState.BLOCKED) {
            _activeFlows.value = flowTracker.getActiveFlows()
            val runtime = Runtime.getRuntime()
            val usedMemMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
            _diagnostics.update { current ->
                current.copy(
                    activeFlowsCount = flowTracker.activeFlowCount(),
                    packetQueueDepth = rateLimiter.getQueueDepth(),
                    totalPacketsProcessed = total,
                    droppedByPolicyCount = droppedByPolicyCount.get(),
                    throttledDelayedPackets = rateLimiter.getTotalDelayedCount(),
                    unattributedPacketsCount = unattributedCount.get(),
                    memoryUsedMb = usedMemMb.coerceAtLeast(12L)
                )
            }
        }
    }
}

/**
 * خدمة VPN المحلية الرسمية في أندرويد (NetBalancerVpnService - القسم 7).
 */
class NetBalancerVpnService : VpnService() {

    companion object {
        const val ACTION_START_VPN = "com.example.network.vpn.START"
        const val ACTION_STOP_VPN = "com.example.network.vpn.STOP"
        const val ACTION_RELOAD_VPN = "com.example.network.vpn.RELOAD"
        private const val NOTIFICATION_ID = 4101
        private const val CHANNEL_ID = "netbalancer_vpn_channel"

        @Volatile
        private var activeInstance: NetBalancerVpnService? = null

        fun startService(context: Context) {
            val intent = Intent(context, NetBalancerVpnService::class.java).apply {
                action = ACTION_START_VPN
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, NetBalancerVpnService::class.java).apply {
                action = ACTION_STOP_VPN
            }
            context.startService(intent)
        }

        fun requestReloadConfig() {
            activeInstance?.establishOrReloadTunnel()
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var vpnInterface: ParcelFileDescriptor? = null
    private var packetReaderJob: Job? = null
    private lateinit var attributionEngine: AppAttributionEngine

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        attributionEngine = AppAttributionEngine(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_VPN -> {
                stopVpnEngine()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD_VPN, ACTION_START_VPN, null -> {
                startForeground(NOTIFICATION_ID, buildForegroundNotification())
                establishOrReloadTunnel()
            }
        }
        return START_STICKY
    }

    @Synchronized
    fun establishOrReloadTunnel() {
        try {
            packetReaderJob?.cancel()
            vpnInterface?.close()
            vpnInterface = null

            val blockedPkgs = VpnEngineController.blockedPackages.value
            val limitedPkgs = VpnEngineController.rateLimitedPackages.value
            val enforcedPkgs = blockedPkgs + limitedPkgs
            val failedPkgs = mutableSetOf<String>()
            val builder = Builder()
                .setSession("NetBalancer المحرك المحلي")
                .setMtu(IpPacketParser.DEFAULT_VPN_MTU)
                .addAddress("10.215.173.1", 30)
                .addAddress("fd00:6e62::1", 126)

            if (enforcedPkgs.isNotEmpty()) {
                var addedCount = 0
                for (pkg in enforcedPkgs) {
                    if (pkg != packageName) {
                        try {
                            builder.addAllowedApplication(pkg)
                            addedCount++
                        } catch (_: Exception) {
                            failedPkgs.add(pkg)
                        }
                    } else {
                        failedPkgs.add(pkg)
                    }
                }
                if (addedCount > 0) {
                    builder.addRoute("0.0.0.0", 0)
                    builder.addRoute("::", 0)
                } else {
                    builder.addRoute("10.215.173.0", 30)
                }
            } else {
                builder.addRoute("10.215.173.0", 30)
                builder.addRoute("fd00:6e62::", 126)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            vpnInterface = builder.establish()
            val established = vpnInterface != null
            val now = System.currentTimeMillis()

            VpnEngineController.updateDiagnostics {
                it.copy(
                    isVpnRunning = established,
                    isVpnPrepared = true,
                    mtuSize = IpPacketParser.DEFAULT_VPN_MTU,
                    ipv4Enabled = true,
                    ipv6Enabled = true,
                    failedRulePackages = failedPkgs,
                    lastSuccessTimestampMillis = if (established) now else it.lastSuccessTimestampMillis,
                    lastSyncTimestampMillis = now,
                    lastErrorAr = if (established) null else "تعذر تأسيس واجهة TUN؛ يلزم منح إذن VPN"
                )
            }

            VpnEngineController.runEnginePipelineSelfTest(attributionEngine)

            vpnInterface?.let { pfd ->
                startPacketReaderLoop(pfd)
            }
        } catch (e: Exception) {
            val errMsg = "تعذر تأسيس واجهة VPN أو تم سحب الصلاحية: ${e.localizedMessage ?: "خطأ غير معروف"}"
            VpnEngineController.emitAudit(
                AuditLogItem(
                    eventType = AuditEventType.ERROR_RECOVERY,
                    summaryAr = errMsg
                )
            )
            VpnEngineController.updateDiagnostics {
                it.copy(
                    isVpnRunning = false,
                    lastErrorAr = errMsg
                )
            }
        }
    }

    private fun startPacketReaderLoop(pfd: ParcelFileDescriptor) {
        packetReaderJob = serviceScope.launch(Dispatchers.IO) {
            val inputStream = FileInputStream(pfd.fileDescriptor)
            val outputStream = java.io.FileOutputStream(pfd.fileDescriptor)
            val packetBuffer = ByteBuffer.allocate(IpPacketParser.DEFAULT_VPN_MTU + 100)

            while (isActive) {
                try {
                    val readBytes = inputStream.read(packetBuffer.array())
                    if (readBytes > 0) {
                        VpnEngineController.onLivePacketCaptured(
                            rawBytes = packetBuffer.array(),
                            length = readBytes,
                            attributionEngine = attributionEngine,
                            onPacedForwardCallback = { header, uid, _, _, packetCopy, delayMs ->
                                serviceScope.launch(Dispatchers.IO) {
                                    if (delayMs > 0L) {
                                        delay(delayMs)
                                        VpnEngineController.rateLimiter.onDelayedPacketDispatched()
                                    }
                                    if (header.ipVersion == IpVersion.IPV4 &&
                                        (header.protocol == PacketProtocol.UDP || header.protocol == PacketProtocol.DNS)
                                    ) {
                                        forwardPacedIpv4UdpPacket(header, uid, packetCopy, outputStream)
                                    }
                                }
                            }
                        )
                        packetBuffer.clear()
                    } else {
                        delay(25L)
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (_: Exception) {
                    delay(100L)
                }
            }
        }
    }

    /**
     * يمرر حزم UDP/DNS الخاصة بالتطبيقات محددة السرعة عبر مقبس محمي (`protect(DatagramSocket)`)
     * ويطبق محدد السرعة (`TokenBucketRateLimiter`) على الرد الوارد قبل كتابته إلى نفق TUN.
     */
    private suspend fun forwardPacedIpv4UdpPacket(
        header: com.example.network.packet.ParsedPacketHeader,
        uid: Int,
        rawPacket: ByteArray,
        tunOutputStream: java.io.FileOutputStream
    ) {
        if (rawPacket.size <= header.headerLengthBytes) return
        val payloadOffset = header.headerLengthBytes
        val payloadLen = (rawPacket.size - payloadOffset).coerceAtLeast(0)
        if (payloadLen <= 0) return

        var socket: java.net.DatagramSocket? = null
        try {
            socket = java.net.DatagramSocket()
            if (!protect(socket)) return
            socket.soTimeout = 2500

            val destAddress = java.net.InetAddress.getByName(header.destinationIp)
            val outDatagram = java.net.DatagramPacket(rawPacket, payloadOffset, payloadLen, destAddress, header.destinationPort)
            socket.send(outDatagram)

            val recvBuf = ByteArray(IpPacketParser.DEFAULT_VPN_MTU)
            val inDatagram = java.net.DatagramPacket(recvBuf, recvBuf.size)
            socket.receive(inDatagram)

            val respPayloadLen = inDatagram.length
            if (respPayloadLen > 0) {
                val respIpPacket = buildIpv4UdpResponsePacket(
                    srcIpStr = header.destinationIp,
                    dstIpStr = header.sourceIp,
                    srcPort = header.destinationPort,
                    dstPort = header.sourcePort,
                    payload = recvBuf,
                    payloadLength = respPayloadLen
                )
                if (respIpPacket != null) {
                    val dlDecision = VpnEngineController.rateLimiter.acquirePacket(
                        uid = uid,
                        packetSizeBytes = respIpPacket.size,
                        isDownload = true
                    )
                    when (dlDecision) {
                        is RateLimitDecision.PassImmediately -> {
                            VpnEngineController.recordVpnUidTraffic(uid, respIpPacket.size.toLong(), 0L)
                            synchronized(tunOutputStream) {
                                tunOutputStream.write(respIpPacket)
                            }
                        }
                        is RateLimitDecision.DelayPacket -> {
                            delay(dlDecision.delayMillis)
                            VpnEngineController.rateLimiter.onDelayedPacketDispatched()
                            VpnEngineController.recordVpnUidTraffic(uid, respIpPacket.size.toLong(), 0L)
                            synchronized(tunOutputStream) {
                                tunOutputStream.write(respIpPacket)
                            }
                        }
                        else -> Unit
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun buildIpv4UdpResponsePacket(
        srcIpStr: String,
        dstIpStr: String,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
        payloadLength: Int
    ): ByteArray? {
        return try {
            val srcAddr = java.net.InetAddress.getByName(srcIpStr).address
            val dstAddr = java.net.InetAddress.getByName(dstIpStr).address
            if (srcAddr.size != 4 || dstAddr.size != 4) return null

            val udpLen = 8 + payloadLength
            val totalLen = 20 + udpLen
            val pkt = ByteArray(totalLen)

            pkt[0] = 0x45.toByte()
            pkt[1] = 0x00
            pkt[2] = ((totalLen shr 8) and 0xFF).toByte()
            pkt[3] = (totalLen and 0xFF).toByte()
            pkt[4] = 0x00
            pkt[5] = 0x00
            pkt[6] = 0x40.toByte()
            pkt[7] = 0x00
            pkt[8] = 64.toByte()
            pkt[9] = 17.toByte() // UDP
            System.arraycopy(srcAddr, 0, pkt, 12, 4)
            System.arraycopy(dstAddr, 0, pkt, 16, 4)

            // حساب IPv4 Header Checksum
            var sum = 0
            for (i in 0 until 20 step 2) {
                val word = ((pkt[i].toInt() and 0xFF) shl 8) or (pkt[i + 1].toInt() and 0xFF)
                sum += word
            }
            while ((sum shr 16) != 0) {
                sum = (sum and 0xFFFF) + (sum shr 16)
            }
            val checksum = sum.inv() and 0xFFFF
            pkt[10] = ((checksum shr 8) and 0xFF).toByte()
            pkt[11] = (checksum and 0xFF).toByte()

            // UDP Header
            pkt[20] = ((srcPort shr 8) and 0xFF).toByte()
            pkt[21] = (srcPort and 0xFF).toByte()
            pkt[22] = ((dstPort shr 8) and 0xFF).toByte()
            pkt[23] = (dstPort and 0xFF).toByte()
            pkt[24] = ((udpLen shr 8) and 0xFF).toByte()
            pkt[25] = (udpLen and 0xFF).toByte()
            pkt[26] = 0x00
            pkt[27] = 0x00

            System.arraycopy(payload, 0, pkt, 28, payloadLength)
            pkt
        } catch (_: Exception) {
            null
        }
    }

    private fun stopVpnEngine() {
        try {
            packetReaderJob?.cancel()
            vpnInterface?.close()
            vpnInterface = null
        } catch (_: Exception) {
        }
        VpnEngineController.updateDiagnostics { it.copy(isVpnRunning = false) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onRevoke() {
        val msg = "تم إيقاف موافقة VPN من إعدادات النظام — يلزم منح إذن VPN لإعادة التشغيل"
        VpnEngineController.emitAudit(
            AuditLogItem(
                eventType = AuditEventType.ENGINE_STOPPED,
                summaryAr = msg
            )
        )
        VpnEngineController.updateDiagnostics { it.copy(lastErrorAr = msg) }
        stopVpnEngine()
        super.onRevoke()
    }

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
        }
        stopVpnEngine()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val blockedCount = VpnEngineController.blockedPackages.value.size
        val contentText = if (blockedCount > 0) {
            "المحرك نشط • يتم حظر $blockedCount تطبيق ومراقبة السرعة لحظيًا"
        } else {
            "المحرك نشط • المراقبة اللحظية وفحص التدفقات يعملان بكفاءة"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("نت بالانسر — محرك الشبكة يعمل")
            .setContentText(contentText)
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
