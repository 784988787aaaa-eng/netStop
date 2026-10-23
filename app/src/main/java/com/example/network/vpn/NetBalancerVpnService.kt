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
import com.example.core.model.ActiveNetworkTransport
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
 * وحدة التحكم المركزية بمحرك الشبكة و VPN المحلي (Section 4).
 * تربط بين:
 * - PacketReader (قراءة رؤوس IPv4/IPv6/TCP/UDP/DNS دون حجب الواجهة)
 * - FlowTracker (تتبع التدفقات بذاكرة محدودة 2048 تدفقًا)
 * - AppAttributionEngine (إسناد الاتصالات إلى UID)
 * - PolicyEngine (تحديد السياسة وحسم التعارض)
 * - TokenBucketRateLimiter (تحديد السرعة وإدارة طابور الانتظار)
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

    fun updateBlockedPackages(packages: Set<String>) {
        val previous = _blockedPackages.value
        _blockedPackages.value = packages
        if (previous != packages && _diagnostics.value.isVpnRunning) {
            NetBalancerVpnService.requestReloadConfig()
        }
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

        // اختبار TokenBucketRateLimiter
        rateLimiter.configureUidLimits(99999, maxDownloadBytesPerSec = 64 * 1024L, maxUploadBytesPerSec = 32 * 1024L)
        val rlDecision = rateLimiter.acquirePacket(99999, packetSizeBytes = 1200, isDownload = true)
        val rlOk = rlDecision is RateLimitDecision.PassImmediately || rlDecision is RateLimitDecision.DelayPacket
        rateLimiter.configureUidLimits(99999, 0L, 0L)

        val elapsedMicros = ((System.nanoTime() - startNanos) / 1000L).coerceAtLeast(10L)
        val runtime = Runtime.getRuntime()
        val usedMemMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)

        _activeFlows.value = flowTracker.getActiveFlows()

        val statusSummary = if (testPass && rlOk) {
            "نجح فحص المسار: IPv4/IPv6 + TCP/UDP + DNS (${parsedDns?.dnsQueryDomain}) + TokenBucket في ${elapsedMicros}µs"
        } else {
            "تحذير في أحد اختبارات المسار الذاتية"
        }

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
                lastSelfTestResultAr = statusSummary,
                lastSelfTestPassed = testPass && rlOk
            )
        }

        return _diagnostics.value
    }

    fun onLivePacketCaptured(
        rawBytes: ByteArray,
        length: Int,
        attributionEngine: AppAttributionEngine
    ) {
        val header = IpPacketParser.parse(rawBytes, length) ?: return
        val total = totalPacketsProcessed.incrementAndGet()
        val identity = attributionEngine.attributePacket(header)

        if (!identity.isAttributed) {
            unattributedCount.incrementAndGet()
        }

        val isDownload = header.destinationIp == "10.215.173.1" ||
            header.destinationIp.startsWith("fd00:6e62") ||
            header.destinationIp == "127.0.0.1" ||
            header.destinationIp == "::1"
        val isOutbound = !isDownload

        val isBlockedPkg = _blockedPackages.value.contains(identity.packageName)
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
                            summaryAr = "حظر حزمة ${header.protocol.label} (${header.ipVersion.label}) $dirText",
                            technicalDetails = header.dnsQueryDomain?.let { "استعلام DNS: $it" } ?: header.toFiveTupleKey()
                        )
                    )
                }
                FlowState.BLOCKED
            }
            is RateLimitDecision.DelayPacket -> FlowState.THROTTLED
            is RateLimitDecision.DropQueueOverflow -> FlowState.THROTTLED
            is RateLimitDecision.PassImmediately -> FlowState.ESTABLISHED
        }

        flowTracker.recordPacket(
            header = header,
            uid = identity.uid,
            packageName = identity.packageName,
            appName = identity.appName,
            isOutbound = isOutbound,
            targetState = flowState
        )

        if (total % 4L == 0L) {
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
 * خدمة VPN المحلية الرسمية في أندرويد (NetBalancerVpnService - القسم الرابع).
 *
 * التصميم الهندسي لضمان عدم قطع الإنترنت عن التطبيقات المسموحة:
 * - في أندرويد بدون صلاحية Root، توجيه جميع التطبيقات إلى واجهة TUN محلية دون مكدس TCP/IP كامل في فضاء المستخدم
 *   سيؤدي إلى ابتلاع الحزم وتعطيل الإنترنت.
 * - لذلك يطبق NetBalancerVpnService استراتيجية **التوجيه الانتقائي الآمن (Selective Split-Tunnel Sink + Live Packet Reader)**:
 *   1. التطبيقات المحظورة (عبر قاعدة حظر أو تجاوز حصة) تُضاف عبر `Builder.addAllowedApplication(pkg)` إلى نفق TUN المحلي المعزول،
 *      حيث يلتقط `PacketReader` محاولات اتصالها (TCP/UDP/DNS IPv4/IPv6) ويحلل رؤوسها ويسجلها في سجل الأحداث ثم يسقطها بأمان،
 *      بينما تستمر جميع التطبيقات المسموحة في الاتصال المباشر السريع بالإنترنت دون أي انقطاع!
 *   2. إذا لم يكن هناك أي تطبيق محظور حاليًا، ينشئ المحرك واجهة المراقبة المحلية المقيدة بحزمة الفحص الذاتي فقط،
 *      مما يحافظ على عمل الإنترنت بنسبة 100% لجميع التطبيقات مع استمرار مراقبة سرعات النواة اللحظية.
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
            val builder = Builder()
                .setSession("NetBalancer المحرك المحلي")
                .setMtu(IpPacketParser.DEFAULT_VPN_MTU)
                .addAddress("10.215.173.1", 30)
                .addAddress("fd00:6e62::1", 126)

            if (blockedPkgs.isNotEmpty()) {
                // توجيه التطبيقات المحظورة فقط إلى نفق العزل والفحص المحلي لضمان عدم تأثر باقي تطبيقات الجهاز
                var addedCount = 0
                for (pkg in blockedPkgs) {
                    if (pkg != packageName) {
                        try {
                            builder.addAllowedApplication(pkg)
                            addedCount++
                        } catch (_: Exception) {
                        }
                    }
                }
                if (addedCount > 0) {
                    builder.addRoute("0.0.0.0", 0)
                    builder.addRoute("::", 0)
                } else {
                    builder.addRoute("10.215.173.0", 30)
                }
            } else {
                // عند عدم وجود تطبيق محظور، نوجّه فقط شبكة الفحص الداخلية 10.215.173.0/30 حتى لا يتعطل الإنترنت أبدًا
                builder.addRoute("10.215.173.0", 30)
                builder.addRoute("fd00:6e62::", 126)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            vpnInterface = builder.establish()
            val established = vpnInterface != null

            VpnEngineController.updateDiagnostics {
                it.copy(
                    isVpnRunning = established,
                    isVpnPrepared = true,
                    mtuSize = IpPacketParser.DEFAULT_VPN_MTU,
                    ipv4Enabled = true,
                    ipv6Enabled = true
                )
            }

            VpnEngineController.runEnginePipelineSelfTest(attributionEngine)

            vpnInterface?.let { pfd ->
                startPacketReaderLoop(pfd)
            }
        } catch (e: Exception) {
            VpnEngineController.emitAudit(
                AuditLogItem(
                    eventType = AuditEventType.ERROR_RECOVERY,
                    summaryAr = "تعذر تأسيس واجهة VPN أو تم سحب الصلاحية: ${e.localizedMessage ?: "خطأ غير معروف"}"
                )
            )
            VpnEngineController.updateDiagnostics { it.copy(isVpnRunning = false) }
        }
    }

    private fun startPacketReaderLoop(pfd: ParcelFileDescriptor) {
        packetReaderJob = serviceScope.launch(Dispatchers.IO) {
            val inputStream = FileInputStream(pfd.fileDescriptor)
            val packetBuffer = ByteBuffer.allocate(IpPacketParser.DEFAULT_VPN_MTU + 100)

            while (isActive) {
                try {
                    val readBytes = inputStream.read(packetBuffer.array())
                    if (readBytes > 0) {
                        VpnEngineController.onLivePacketCaptured(
                            rawBytes = packetBuffer.array(),
                            length = readBytes,
                            attributionEngine = attributionEngine
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
        VpnEngineController.emitAudit(
            AuditLogItem(
                eventType = AuditEventType.ENGINE_STOPPED,
                summaryAr = "تم إيقاف موافقة VPN من إعدادات النظام"
            )
        )
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
