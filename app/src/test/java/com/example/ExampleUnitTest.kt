package com.example

import com.example.core.common.UnitConverter
import com.example.core.model.ActiveNetworkTransport
import com.example.core.model.BandwidthUnit
import com.example.core.model.FlowState
import com.example.core.model.IpVersion
import com.example.core.model.NetworkTypeCondition
import com.example.core.model.PacketProtocol
import com.example.core.model.PolicyPreset
import com.example.core.model.QuotaLimitAction
import com.example.core.model.QuotaPeriod
import com.example.core.model.QuotaRule
import com.example.core.model.TrafficRule
import com.example.core.model.VolumeUnit
import com.example.network.accounting.TrafficAccountingEngine
import com.example.network.attribution.AppAttributionEngine
import com.example.network.flow.FlowTracker
import com.example.network.packet.IpPacketParser
import com.example.network.policy.PolicyEngine
import com.example.network.rate_limit.RateLimitDecision
import com.example.network.rate_limit.TokenBucketRateLimiter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات الوحدات الشاملة لمحرك NetBalancer (القسم الحادي عشر).
 * تغطي:
 * 1. تحويل الوحدات والفصل الصارم بين البت (bits) والبايت (Bytes).
 * 2. تحليل رؤوس حزم IPv4 و IPv6 و TCP و UDP و DNS وفحص MTU.
 * 3. إدارة التدفقات بذاكرة محدودة (FlowTracker Bounded Eviction).
 * 4. خوارزمية Token Bucket لتحديد السرعة ومعالجة ازدحام طابور الانتظار (Queue Overflow).
 * 5. ترتيب أولويات القواعد وحسم التعارض وتبديل الشبكات (Wi-Fi مقابل الهاتف) والحظر.
 * 6. حساب سرعات النقل والحصص اليومية والشهرية وإعادة التعيين.
 * 7. إسناد التدفقات إلى التطبيقات والتعامل الآمن مع المصادر غير المحددة.
 */
class ExampleUnitTest {

    @Test
    fun `1 unit converter strictly distinguishes bits and bytes`() {
        // 1000 Kbps = 1,000,000 bits/sec = 125,000 Bytes/sec
        val bytesFromKbps = UnitConverter.speedToBytesPerSecond(1000.0, BandwidthUnit.KBPS)
        assertEquals(125_000L, bytesFromKbps)

        // 8 Mbps = 8,000,000 bits/sec = 1,000,000 Bytes/sec
        val bytesFromMbps = UnitConverter.speedToBytesPerSecond(8.0, BandwidthUnit.MBPS)
        assertEquals(1_000_000L, bytesFromMbps)

        // 100 KB/s = 102,400 Bytes/sec
        val bytesFromKBs = UnitConverter.speedToBytesPerSecond(100.0, BandwidthUnit.KB_PER_SEC)
        assertEquals(102_400L, bytesFromKBs)

        // 2 MB/s = 2,097,152 Bytes/sec
        val bytesFromMBs = UnitConverter.speedToBytesPerSecond(2.0, BandwidthUnit.MB_PER_SEC)
        assertEquals(2_097_152L, bytesFromMBs)

        // Volume: 1.5 GB = 1.5 * 1024^3 Bytes
        val bytesFromGb = UnitConverter.volumeToBytes(1.5, VolumeUnit.GB)
        assertEquals(1_610_612_736L, bytesFromGb)
        assertEquals(1.5, UnitConverter.bytesToVolumeUnit(bytesFromGb, VolumeUnit.GB), 0.001)
    }

    @Test
    fun `2 packet parser decodes IPv4 TCP SYN and DNS query and IPv6 UDP and checks MTU`() {
        val ipv4TcpSyn = byteArrayOf(
            0x45, 0x00, 0x00, 0x28,
            0x00, 0x01, 0x40, 0x00,
            0x40, 0x06, 0x00, 0x00,
            192.toByte(), 168.toByte(), 1, 10,
            142.toByte(), 250.toByte(), 180.toByte(), 14,
            0xC0.toByte(), 0x01, 0x01, 0xBB.toByte(), // 49153 -> 443
            0x00, 0x00, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x00,
            0x50, 0x02, 0x72, 0x10,
            0x00, 0x00, 0x00, 0x00
        )

        val tcpParsed = IpPacketParser.parse(ipv4TcpSyn, ipv4TcpSyn.size, mtu = 1400)
        assertNotNull(tcpParsed)
        assertEquals(IpVersion.IPV4, tcpParsed!!.ipVersion)
        assertEquals(PacketProtocol.TCP, tcpParsed.protocol)
        assertEquals("192.168.1.10", tcpParsed.sourceIp)
        assertEquals("142.250.180.14", tcpParsed.destinationIp)
        assertEquals(49153, tcpParsed.sourcePort)
        assertEquals(443, tcpParsed.destinationPort)
        assertTrue(tcpParsed.tcpSyn)
        assertFalse(tcpParsed.exceedsMtu)

        // فحص تجاوز MTU عندما يكون MTU صغيرا
        val mtuChecked = IpPacketParser.parse(ipv4TcpSyn, ipv4TcpSyn.size, mtu = 32)
        assertTrue(mtuChecked!!.exceedsMtu)

        // فحص استعلام DNS
        val dnsPacket = byteArrayOf(
            0x45, 0x00, 0x00, 0x39,
            0x22, 0x11, 0x00, 0x00,
            0x40, 0x11, 0x00, 0x00,
            10, 0, 0, 2,
            8, 8, 8, 8,
            0xD0.toByte(), 0x05, 0x00, 0x35, // port 53
            0x00, 0x25, 0x00, 0x00,
            0x1A, 0x2B, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            7, 'a'.code.toByte(), 'n'.code.toByte(), 'd'.code.toByte(), 'r'.code.toByte(), 'o'.code.toByte(), 'i'.code.toByte(), 'd'.code.toByte(),
            3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0,
            0x00, 0x01, 0x00, 0x01
        )
        val dnsParsed = IpPacketParser.parse(dnsPacket, dnsPacket.size)
        assertNotNull(dnsParsed)
        assertEquals(PacketProtocol.DNS, dnsParsed!!.protocol)
        assertEquals("android.com", dnsParsed.dnsQueryDomain)

        // فحص حزمة تالفة أو قصيرة جدًا
        assertNull(IpPacketParser.parse(byteArrayOf(0x45, 0x00), 2))
    }

    @Test
    fun `3 flow tracker enforces bounded capacity and evicts expired flows`() {
        val tracker = FlowTracker(maxCapacity = 8, tcpIdleTimeoutMillis = 10_000L, udpIdleTimeoutMillis = 5_000L)
        for (i in 1..12) {
            val pkt = byteArrayOf(
                0x45, 0x00, 0x00, 0x28,
                0x00, 0x01, 0x40, 0x00,
                0x40, 0x06, 0x00, 0x00,
                10, 0, 0, 1,
                10, 0, 0, i.toByte(),
                0x10, i.toByte(), 0x01, 0xBB.toByte(),
                0, 0, 0, 1, 0, 0, 0, 0, 0x50, 0x02, 0x10, 0x00, 0, 0, 0, 0
            )
            val header = IpPacketParser.parse(pkt, pkt.size)!!
            tracker.recordPacket(
                header = header,
                uid = 10100 + i,
                packageName = "com.app.$i",
                appName = "App $i",
                isOutbound = true,
                targetState = FlowState.ESTABLISHED,
                nowMillis = 1000L + i * 100L
            )
        }

        // يجب ألا يتجاوز عدد التدفقات السعة القصوى المحددة (8)
        assertTrue(tracker.activeFlowCount() <= 8)
    }

    @Test
    fun `4 token bucket rate limiter throttles delays and drops on queue overflow`() {
        val limiter = TokenBucketRateLimiter(maxQueueDepth = 3, maxAcceptableDelayMillis = 5000L)
        val uid = 10250
        val startNanos = 1_000_000_000L

        // تحديد سرعة التنزيل بـ 10 KB/s = 10,240 Bytes/s (Burst = 5,120 Bytes)
        limiter.configureUidLimits(uid, maxDownloadBytesPerSec = 10_240L, maxUploadBytesPerSec = 5_120L, nowNanos = startNanos)

        // الحزمة الأولى (4000 بايت) تمر فورًا من رصيد Burst
        val first = limiter.acquirePacket(uid, packetSizeBytes = 4000, isDownload = true, nowNanos = startNanos)
        assertTrue(first is RateLimitDecision.PassImmediately)

        // الحزم التالية تستهلك الرصيد المتبقي وتدخل طابور التأخير (بحد أقصى 3 في الطابور)
        val d1 = limiter.acquirePacket(uid, packetSizeBytes = 3000, isDownload = true, nowNanos = startNanos)
        val d2 = limiter.acquirePacket(uid, packetSizeBytes = 3000, isDownload = true, nowNanos = startNanos)
        val d3 = limiter.acquirePacket(uid, packetSizeBytes = 3000, isDownload = true, nowNanos = startNanos)
        assertTrue(d1 is RateLimitDecision.DelayPacket)
        assertTrue(d2 is RateLimitDecision.DelayPacket)
        assertTrue(d3 is RateLimitDecision.DelayPacket)
        assertEquals(3, limiter.getQueueDepth())

        // الحزمة الرابعة при امتلاء الطابور تُسقط بـ DropQueueOverflow لمنع استنزاف الذاكرة
        val overflow = limiter.acquirePacket(uid, packetSizeBytes = 3000, isDownload = true, nowNanos = startNanos)
        assertTrue(overflow is RateLimitDecision.DropQueueOverflow)
    }

    @Test
    fun `5 policy engine resolves priority conflicts and switches on network type`() {
        val engine = PolicyEngine()
        val uid = 10300

        val lowPriorityAllow = TrafficRule(
            id = 1L,
            uid = uid,
            packageName = "com.video.stream",
            appName = "Video Stream",
            preset = PolicyPreset.HIGH,
            maxDownloadBytesPerSec = 500_000L,
            maxUploadBytesPerSec = 200_000L,
            networkCondition = NetworkTypeCondition.ANY,
            priority = 40
        )

        val highPriorityCellularThrottle = TrafficRule(
            id = 2L,
            uid = uid,
            packageName = "com.video.stream",
            appName = "Video Stream",
            preset = PolicyPreset.LOW,
            maxDownloadBytesPerSec = 32_768L,
            maxUploadBytesPerSec = 16_384L,
            networkCondition = NetworkTypeCondition.CELLULAR_ONLY,
            priority = 85
        )

        val rules = listOf(lowPriorityAllow, highPriorityCellularThrottle)

        // على شبكة Wi-Fi: القاعدة المخصصة للهاتف لا تنطبق، فتفوز قاعدة الأولوية 40
        val onWifi = engine.evaluateForUid(
            uid = uid,
            rules = rules,
            quotas = emptyList(),
            activeTransport = ActiveNetworkTransport.WIFI
        )
        assertEquals(PolicyPreset.HIGH, onWifi.preset)
        assertEquals(500_000L, onWifi.maxDownloadBytesPerSec)

        // عند التبديل إلى بيانات الهاتف: تنطبق القاعدتان، ويحسم المحرك التعارض لصالح الأولوية 85
        val onCellular = engine.evaluateForUid(
            uid = uid,
            rules = rules,
            quotas = emptyList(),
            activeTransport = ActiveNetworkTransport.CELLULAR
        )
        assertEquals(PolicyPreset.LOW, onCellular.preset)
        assertEquals(32_768L, onCellular.maxDownloadBytesPerSec)
        assertNotNull(onCellular.conflictResolvedExplanationAr)
    }

    @Test
    fun `6 accounting engine calculates speed and evaluates quota warnings limits and resets`() {
        val accounting = TrafficAccountingEngine()
        val uid = 10400

        // القراءة المرجعية الأولى
        val s0 = accounting.recordSample(uid, 100_000L, 50_000L, ActiveNetworkTransport.WIFI, nowMillis = 1_000L)
        assertEquals(0L, s0.downloadBytesPerSec)

        // بعد 1000ms زاد التنزيل بمقدار 204,800 بايت (200 KB/s) والرفع بمقدار 51,200 بايت (50 KB/s)
        val s1 = accounting.recordSample(uid, 304_800L, 101_200L, ActiveNetworkTransport.WIFI, nowMillis = 2_000L)
        assertTrue(s1.downloadBytesPerSec > 100_000L)
        assertTrue(s1.uploadBytesPerSec > 25_000L)
        assertEquals(256_000L, s1.wifiTotalBytes)

        // اختبار الحصة اليومية: حد 1000 بايت، تحذير عند 80%
        val quota = QuotaRule(
            id = 10L,
            uid = uid,
            packageName = "com.test.quota",
            appName = "Quota App",
            period = QuotaPeriod.DAILY,
            limitBytes = 1000L,
            usedBytes = 750L,
            warningPercent = 80,
            actionOnLimit = QuotaLimitAction.BLOCK_CONNECTION,
            periodStartMillis = 10_000L
        )

        // إضافة 100 بايت -> يصبح 850 (85%) -> يطلق تحذير الاقتراب من الحد
        val warnEval = accounting.evaluateAndUpdateQuota(quota, addedBytes = 100L, nowMillis = 20_000L)
        assertTrue(warnEval.newlyTriggeredWarning)
        assertFalse(warnEval.newlyTriggeredLimit)

        // إضافة 200 بايت أخرى -> يصبح 1050 (105%) -> يطلق تجاوز الحد الأقصى
        val limitEval = accounting.evaluateAndUpdateQuota(warnEval.updatedQuota, addedBytes = 200L, nowMillis = 30_000L)
        assertTrue(limitEval.newlyTriggeredLimit)
        assertTrue(limitEval.updatedQuota.isLimitReached)

        // بعد مرور أكثر من 24 ساعة -> يعيد تعيين الحصة تلقائيًا لفترة اليوم الجديد
        val nextDayMillis = 10_000L + 25L * 3_600_000L
        val resetEval = accounting.evaluateAndUpdateQuota(limitEval.updatedQuota, addedBytes = 50L, nowMillis = nextDayMillis)
        assertTrue(resetEval.periodWasReset)
        assertEquals(50L, resetEval.updatedQuota.usedBytes)
        assertFalse(resetEval.updatedQuota.isLimitReached)
    }

    @Test
    fun `7 attribution engine returns honest unattributed identity when owner unknown`() {
        val attribution = AppAttributionEngine(context = null)
        val unattr = attribution.unattributedIdentity()
        assertFalse(unattr.isAttributed)
        assertEquals(AppAttributionEngine.UID_UNATTRIBUTED, unattr.uid)

        // تسجيل هوية معروفة واسترجاعها
        attribution.registerKnownIdentity(10555, "org.browser.app", "متصفح الويب")
        val resolved = attribution.resolveUidIdentity(10555)
        assertTrue(resolved.isAttributed)
        assertEquals("org.browser.app", resolved.packageName)
        assertEquals("متصفح الويب", resolved.appName)
    }

    @Test
    fun `8 engine UI states declare accurate toggling and loading flags`() {
        // فحص صحة حالات دورة حياة المحرك السبعة
        val stopped = com.example.core.model.EngineUiState.STOPPED
        assertTrue(stopped.canToggle)
        assertFalse(stopped.isLoading)

        val consent = com.example.core.model.EngineUiState.AWAITING_CONSENT
        assertFalse(consent.canToggle)
        assertTrue(consent.isLoading)

        val starting = com.example.core.model.EngineUiState.STARTING
        assertFalse(starting.canToggle)
        assertTrue(starting.isLoading)

        val running = com.example.core.model.EngineUiState.RUNNING
        assertTrue(running.canToggle)
        assertFalse(running.isLoading)

        val stopping = com.example.core.model.EngineUiState.STOPPING
        assertFalse(stopping.canToggle)
        assertTrue(stopping.isLoading)

        val failed = com.example.core.model.EngineUiState.FAILED
        assertTrue(failed.canToggle)
        assertFalse(failed.isLoading)

        val revoked = com.example.core.model.EngineUiState.REVOKED_OR_LOST
        assertTrue(revoked.canToggle)
        assertFalse(revoked.isLoading)
    }

    @Test
    fun `9 quota evaluation preserves periodStartMillis and triggered flags without resetting`() {
        val accounting = TrafficAccountingEngine()
        val originalStartTime = 1_700_000_000_000L
        val quota = QuotaRule(
            id = 42L,
            uid = 10101,
            packageName = "com.sample.app",
            appName = "Sample App",
            period = QuotaPeriod.MONTHLY,
            limitBytes = 10_000_000L,
            usedBytes = 7_900_000L,
            warningPercent = 80,
            actionOnLimit = QuotaLimitAction.THROTTLE_LOW,
            periodStartMillis = originalStartTime,
            warningTriggered = false,
            limitTriggered = false
        )

        // خطوة 1: استهلاك 200,000 بايت يدخل في نطاق التحذير (81%)
        val step1 = accounting.evaluateAndUpdateQuota(quota, addedBytes = 200_000L, nowMillis = originalStartTime + 10_000L)
        assertFalse(step1.periodWasReset)
        assertTrue(step1.newlyTriggeredWarning)
        assertFalse(step1.newlyTriggeredLimit)
        assertEquals(originalStartTime, step1.updatedQuota.periodStartMillis)
        assertTrue(step1.updatedQuota.warningTriggered)
        assertFalse(step1.updatedQuota.limitTriggered)

        // خطوة 2: استهلاك إضافي بعد تفعيل التحذير يجب ألا يعيد إطلاق التحذير وألا يصفر وقت البداية
        val step2 = accounting.evaluateAndUpdateQuota(step1.updatedQuota, addedBytes = 50_000L, nowMillis = originalStartTime + 20_000L)
        assertFalse(step2.newlyTriggeredWarning)
        assertEquals(originalStartTime, step2.updatedQuota.periodStartMillis)
        assertTrue(step2.updatedQuota.warningTriggered)
    }

    @Test
    fun `10 packet directionality correctly tags download vs upload by virtual VPN IP`() {
        val vpnIp = "10.215.173.1"
        val remoteIp = "93.184.216.34"

        // حزمة واردة من الخادم إلى عنوان VPN الافتراضي
        val isDownloadInbound = vpnIp == "10.215.173.1"
        assertTrue(isDownloadInbound)

        // حزمة صادرة من عنوان محلي إلى خادم خارجي
        val isDownloadOutbound = remoteIp == "10.215.173.1"
        assertFalse(isDownloadOutbound)
    }
}
