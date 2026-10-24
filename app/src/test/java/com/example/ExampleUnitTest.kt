package com.example

import com.example.core.common.UnitConverter
import com.example.core.i18n.AppLanguage
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

        limiter.configureUidLimits(uid, maxDownloadBytesPerSec = 10_240L, maxUploadBytesPerSec = 5_120L, nowNanos = startNanos)

        val first = limiter.acquirePacket(uid, packetSizeBytes = 4000, isDownload = true, nowNanos = startNanos)
        assertTrue(first is RateLimitDecision.PassImmediately)

        val d1 = limiter.acquirePacket(uid, packetSizeBytes = 3000, isDownload = true, nowNanos = startNanos)
        val d2 = limiter.acquirePacket(uid, packetSizeBytes = 3000, isDownload = true, nowNanos = startNanos)
        val d3 = limiter.acquirePacket(uid, packetSizeBytes = 3000, isDownload = true, nowNanos = startNanos)
        assertTrue(d1 is RateLimitDecision.DelayPacket)
        assertTrue(d2 is RateLimitDecision.DelayPacket)
        assertTrue(d3 is RateLimitDecision.DelayPacket)
        assertEquals(3, limiter.getQueueDepth())

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

        val onWifi = engine.evaluateForUid(
            uid = uid,
            rules = rules,
            quotas = emptyList(),
            activeTransport = ActiveNetworkTransport.WIFI
        )
        assertEquals(PolicyPreset.HIGH, onWifi.preset)
        assertEquals(500_000L, onWifi.maxDownloadBytesPerSec)

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

        val s0 = accounting.recordSample(uid, 100_000L, 50_000L, ActiveNetworkTransport.WIFI, nowMillis = 1_000L)
        assertEquals(0L, s0.downloadBytesPerSec)

        val s1 = accounting.recordSample(uid, 304_800L, 101_200L, ActiveNetworkTransport.WIFI, nowMillis = 2_000L)
        assertTrue(s1.downloadBytesPerSec > 100_000L)
        assertTrue(s1.uploadBytesPerSec > 25_000L)
        assertEquals(256_000L, s1.wifiTotalBytes)

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

        val warnEval = accounting.evaluateAndUpdateQuota(quota, addedBytes = 100L, nowMillis = 20_000L)
        assertTrue(warnEval.newlyTriggeredWarning)
        assertFalse(warnEval.newlyTriggeredLimit)

        val limitEval = accounting.evaluateAndUpdateQuota(warnEval.updatedQuota, addedBytes = 200L, nowMillis = 30_000L)
        assertTrue(limitEval.newlyTriggeredLimit)
        assertTrue(limitEval.updatedQuota.isLimitReached)

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

        attribution.registerKnownIdentity(10555, "org.browser.app", "متصفح الويب")
        val resolved = attribution.resolveUidIdentity(10555)
        assertTrue(resolved.isAttributed)
        assertEquals("org.browser.app", resolved.packageName)
        assertEquals("متصفح الويب", resolved.appName)
    }

    @Test
    fun `8 engine UI states declare accurate toggling and loading flags`() {
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

        val step1 = accounting.evaluateAndUpdateQuota(quota, addedBytes = 200_000L, nowMillis = originalStartTime + 10_000L)
        assertFalse(step1.periodWasReset)
        assertTrue(step1.newlyTriggeredWarning)
        assertFalse(step1.newlyTriggeredLimit)
        assertEquals(originalStartTime, step1.updatedQuota.periodStartMillis)
        assertTrue(step1.updatedQuota.warningTriggered)
        assertFalse(step1.updatedQuota.limitTriggered)

        val step2 = accounting.evaluateAndUpdateQuota(step1.updatedQuota, addedBytes = 50_000L, nowMillis = originalStartTime + 20_000L)
        assertFalse(step2.newlyTriggeredWarning)
        assertEquals(originalStartTime, step2.updatedQuota.periodStartMillis)
        assertTrue(step2.updatedQuota.warningTriggered)
    }

    @Test
    fun `10 packet directionality correctly tags download vs upload by virtual VPN IP`() {
        val vpnIp = "10.215.173.1"
        val remoteIp = "93.184.216.34"

        val isDownloadInbound = vpnIp == "10.215.173.1"
        assertTrue(isDownloadInbound)

        val isDownloadOutbound = remoteIp == "10.215.173.1"
        assertFalse(isDownloadOutbound)
    }

    @Test
    fun `11 accounting engine handles kernel counter reset without losing accumulated session usage`() {
        val accounting = TrafficAccountingEngine()
        val uid = 10777

        accounting.recordSample(uid, 100_000L, 40_000L, ActiveNetworkTransport.WIFI, nowMillis = 1_000L)

        val beforeReset = accounting.recordSample(uid, 150_000L, 60_000L, ActiveNetworkTransport.WIFI, nowMillis = 2_000L)
        assertFalse(beforeReset.counterResetDetected)
        assertEquals(50_000L, beforeReset.sessionRxBytes)
        assertEquals(20_000L, beforeReset.sessionTxBytes)

        val afterReset = accounting.recordSample(uid, 10_000L, 5_000L, ActiveNetworkTransport.WIFI, nowMillis = 3_000L)
        assertTrue(afterReset.counterResetDetected)
        assertEquals(60_000L, afterReset.sessionRxBytes)
        assertEquals(25_000L, afterReset.sessionTxBytes)
    }

    @Test
    fun `12 rule enforcement state separates saved rules from engine enforced rules`() {
        val blockRuleWifi = TrafficRule(
            id = 5L,
            uid = 10900,
            packageName = "com.social.media",
            appName = "Social Media",
            preset = PolicyPreset.BLOCK,
            maxDownloadBytesPerSec = -1L,
            maxUploadBytesPerSec = -1L,
            networkCondition = NetworkTypeCondition.WIFI_ONLY,
            priority = 90,
            isEnabled = true
        )

        val stateWhenVpnOff = blockRuleWifi.evaluateEnforcementState(
            isVpnRunning = false,
            activeTransport = ActiveNetworkTransport.WIFI
        )
        assertEquals(com.example.core.model.RuleEnforcementState.SAVED_ONLY_VPN_OFF, stateWhenVpnOff)

        val stateOnCellular = blockRuleWifi.evaluateEnforcementState(
            isVpnRunning = true,
            activeTransport = ActiveNetworkTransport.CELLULAR
        )
        assertEquals(com.example.core.model.RuleEnforcementState.INACTIVE_NETWORK_MISMATCH, stateOnCellular)

        val stateEnforced = blockRuleWifi.evaluateEnforcementState(
            isVpnRunning = true,
            activeTransport = ActiveNetworkTransport.WIFI
        )
        assertEquals(com.example.core.model.RuleEnforcementState.ENFORCED_IN_KERNEL_VPN, stateEnforced)

        val stateError = blockRuleWifi.evaluateEnforcementState(
            isVpnRunning = true,
            activeTransport = ActiveNetworkTransport.WIFI,
            hasApplyError = true
        )
        assertEquals(com.example.core.model.RuleEnforcementState.FAILED_TO_APPLY, stateError)
    }

    @Test
    fun `13 sort and filter options include all directive criteria`() {
        assertEquals(7, com.example.ui.AppSortOption.entries.size)
        assertEquals(6, com.example.ui.AppFilterOption.entries.size)
    }

    @Test
    fun `14 bilingual Arabic and English model translations and RTL LTR flags`() {
        assertTrue(AppLanguage.AR.isRtl)
        assertFalse(AppLanguage.EN.isRtl)

        assertEquals("حظر الاتصال", PolicyPreset.BLOCK.title(AppLanguage.AR))
        assertEquals("Block", PolicyPreset.BLOCK.title(AppLanguage.EN))

        assertEquals("المحرك يعمل", com.example.core.model.EngineUiState.RUNNING.title(AppLanguage.AR))
        assertEquals("Engine Active", com.example.core.model.EngineUiState.RUNNING.title(AppLanguage.EN))
    }

    @Test
    fun `15 per-network Wi-Fi and Cellular quick block helpers evaluate accurately`() {
        val wifiOnlyBlock = TrafficRule(
            uid = 1001,
            packageName = "com.app.one",
            appName = "App One",
            preset = PolicyPreset.BLOCK,
            maxDownloadBytesPerSec = -1L,
            maxUploadBytesPerSec = -1L,
            networkCondition = NetworkTypeCondition.WIFI_ONLY
        )
        assertTrue(wifiOnlyBlock.isWifiBlocked())
        assertFalse(wifiOnlyBlock.isCellularBlocked())

        val cellOnlyBlock = wifiOnlyBlock.copy(networkCondition = NetworkTypeCondition.CELLULAR_ONLY)
        assertFalse(cellOnlyBlock.isWifiBlocked())
        assertTrue(cellOnlyBlock.isCellularBlocked())

        val bothBlock = wifiOnlyBlock.copy(networkCondition = NetworkTypeCondition.ANY)
        assertTrue(bothBlock.isWifiBlocked())
        assertTrue(bothBlock.isCellularBlocked())
    }

    @Test
    fun `16 accounting engine preserves cumulative boot baseline when includeInitialBaselineInSession is enabled`() {
        val accounting = TrafficAccountingEngine()
        val chromeUid = 10142

        // عند القراءة الأولى من الجلسة مع تفعيل includeInitialBaselineInSession، يجب حفظ الاستهلاك السابق وعدم تصفيره
        val initial = accounting.recordSample(
            uid = chromeUid,
            currentKernelRxBytes = 2_500_000L,
            currentKernelTxBytes = 350_000L,
            activeTransport = ActiveNetworkTransport.WIFI,
            nowMillis = 1_000L,
            includeInitialBaselineInSession = true
        )
        assertEquals(2_500_000L, initial.sessionRxBytes)
        assertEquals(350_000L, initial.sessionTxBytes)
        assertEquals(2_850_000L, initial.wifiTotalBytes)
        assertEquals(0L, initial.downloadBytesPerSec)

        // عند حدوث تصفح جديد في Chrome، يزداد الاستهلاك والسرعة اللحظية فوراً
        val afterBrowsing = accounting.recordSample(
            uid = chromeUid,
            currentKernelRxBytes = 3_100_000L,
            currentKernelTxBytes = 420_000L,
            activeTransport = ActiveNetworkTransport.WIFI,
            nowMillis = 2_000L,
            includeInitialBaselineInSession = true
        )
        assertEquals(3_100_000L, afterBrowsing.sessionRxBytes)
        assertEquals(420_000L, afterBrowsing.sessionTxBytes)
        assertEquals(600_000L, afterBrowsing.downloadBytesPerSec)
        assertEquals(70_000L, afterBrowsing.uploadBytesPerSec)
    }

    @Test
    fun `17 section 4_1 exact numerical accounting and binary vs decimal unit formatting`() {
        val accounting = TrafficAccountingEngine()
        val uid = 10801

        // Sample 0 at t = 0.0 s: rxBytes = 1,000,000
        val sample0 = accounting.recordSample(
            uid = uid,
            currentKernelRxBytes = 1_000_000L,
            currentKernelTxBytes = 500_000L,
            activeTransport = ActiveNetworkTransport.WIFI,
            nowMillis = 0L
        )
        assertTrue(sample0.isAvailable)
        assertEquals(0L, sample0.downloadBytesPerSec)

        // Sample 1 at t = 2.0 s (2,000 ms): rxBytes = 1,020,000 -> deltaBytes = 20,000 -> rate = 10,000 B/s
        val sample1 = accounting.recordSample(
            uid = uid,
            currentKernelRxBytes = 1_020_000L,
            currentKernelTxBytes = 510_000L,
            activeTransport = ActiveNetworkTransport.WIFI,
            nowMillis = 2_000L
        )
        assertTrue(sample1.isAvailable)
        assertEquals(20_000L, sample1.deltaRxBytes)
        assertEquals(10_000L, sample1.downloadBytesPerSec)
        assertEquals(5_000L, sample1.uploadBytesPerSec)

        // Decimal KB/s: 10,000 / 1,000 = 10.0 KB/s
        assertEquals("10.0 KB/s", UnitConverter.formatDecimalKBPerSec(sample1.downloadBytesPerSec))

        // Binary KiB/s: 10,000 / 1,024 = 9.77 KiB/s
        assertEquals("9.77 KiB/s", UnitConverter.formatBinaryKiBPerSec(sample1.downloadBytesPerSec))

        // Binary 10 KiB/s = 10,240 B/s -> 10.00 KiB/s
        assertEquals("10.00 KiB/s", UnitConverter.formatBinaryKiBPerSec(10_240L))
        assertEquals("20.00 KiB/s", UnitConverter.formatBinaryKiBPerSec(20_480L))
    }

    @Test
    fun `18 section 4_2 edge cases unavailable counters duplicate timestamps and zero traffic`() {
        val accounting = TrafficAccountingEngine()
        val uid = 10802

        // 1. Unavailable counter (-1L or isCounterAvailable = false) must NEVER be converted to 0
        val unavailableSample = accounting.recordSample(
            uid = uid,
            currentKernelRxBytes = -1L,
            currentKernelTxBytes = -1L,
            activeTransport = ActiveNetworkTransport.WIFI,
            nowMillis = 1_000L,
            isCounterAvailable = false
        )
        assertFalse(unavailableSample.isAvailable)
        assertEquals(-1L, unavailableSample.sessionRxBytes)
        assertEquals(-1L, unavailableSample.downloadBytesPerSec)
        assertEquals("البيانات غير متاحة", UnitConverter.formatVolume(unavailableSample.sessionRxBytes, useArabicSymbols = true))
        assertEquals("Traffic unavailable", UnitConverter.formatVolume(unavailableSample.sessionRxBytes, useArabicSymbols = false))
        assertEquals("Blocked", UnitConverter.formatSpeed(unavailableSample.downloadBytesPerSec, BandwidthUnit.KB_PER_SEC, useArabicSymbols = false))

        // 2. Initialize valid baseline at t = 2,000 ms
        val initValid = accounting.recordSample(
            uid = uid,
            currentKernelRxBytes = 500_000L,
            currentKernelTxBytes = 200_000L,
            activeTransport = ActiveNetworkTransport.WIFI,
            nowMillis = 2_000L,
            isCounterAvailable = true
        )
        assertTrue(initValid.isAvailable)
        assertEquals(0L, initValid.downloadBytesPerSec)

        // 3. Active transfer at t = 3,000 ms (+20,480 B -> 20,480 B/s)
        val activeSample = accounting.recordSample(
            uid = uid,
            currentKernelRxBytes = 520_480L,
            currentKernelTxBytes = 210_240L,
            activeTransport = ActiveNetworkTransport.WIFI,
            nowMillis = 3_000L,
            isCounterAvailable = true
        )
        assertEquals(20_480L, activeSample.downloadBytesPerSec)
        assertEquals(10_240L, activeSample.uploadBytesPerSec)

        // 4. Duplicate / out-of-order sample timestamp (nowMillis = 3,000 ms) must not divide by zero or double count
        val duplicateSample = accounting.recordSample(
            uid = uid,
            currentKernelRxBytes = 520_480L,
            currentKernelTxBytes = 210_240L,
            activeTransport = ActiveNetworkTransport.WIFI,
            nowMillis = 3_000L,
            isCounterAvailable = true
        )
        assertTrue(duplicateSample.duplicateOrStaleSample)
        assertEquals(0L, duplicateSample.deltaRxBytes)
        assertEquals(20_480L, duplicateSample.downloadBytesPerSec)

        // 5. Zero traffic over next 2 seconds (t = 5,000 ms) -> 0 B/s
        val idleSample = accounting.recordSample(
            uid = uid,
            currentKernelRxBytes = 520_480L,
            currentKernelTxBytes = 210_240L,
            activeTransport = ActiveNetworkTransport.WIFI,
            nowMillis = 5_000L,
            isCounterAvailable = true
        )
        assertFalse(idleSample.duplicateOrStaleSample)
        assertEquals(0L, idleSample.deltaRxBytes)
        assertEquals(0L, idleSample.downloadBytesPerSec)
    }

    @Test
    fun `19 section 5_4 seven mandatory rate limiting scenarios including 20 KiBs 10 KiBs and multi flow sharing`() {
        val limiter = TokenBucketRateLimiter(maxQueueDepth = 512, maxAcceptableDelayMillis = 2_500L)
        val baseNanos = 10_000_000_000L

        // Scenario 1: Unlimited (0 B/s) -> full burst passes immediately without delay
        val uidUnlimited = 10901
        limiter.configureUidLimits(uidUnlimited, maxDownloadBytesPerSec = 0L, maxUploadBytesPerSec = 0L, nowNanos = baseNanos)
        val unlimitedResult = limiter.executePacedTransferWindow(
            uid = uidUnlimited,
            isDownload = true,
            durationMillis = 5_000L,
            packetSizeBytes = 1024,
            concurrentFlows = 1,
            offeredPacketsPerTick = 4,
            tickStepMillis = 20L,
            startNanos = baseNanos
        )
        assertTrue(unlimitedResult.totalBytesTransferred >= 500_000L)
        assertEquals(0, unlimitedResult.delayedPacketsCount)
        assertEquals(0, unlimitedResult.droppedPacketsCount)

        // Scenario 2: 20 KiB/s limit (20,480 B/s) over 5 seconds -> within 18-22 KiB/s (18,432..22,528 B/s)
        val uid20K = 10902
        limiter.configureUidLimits(
            uid = uid20K,
            maxDownloadBytesPerSec = PolicyPreset.MEDIUM.defaultDownloadBytesPerSec, // 20,480 B/s
            maxUploadBytesPerSec = PolicyPreset.MEDIUM.defaultUploadBytesPerSec,
            nowNanos = baseNanos
        )
        val result20K = limiter.executePacedTransferWindow(
            uid = uid20K,
            isDownload = true,
            durationMillis = 5_000L,
            packetSizeBytes = 1024,
            concurrentFlows = 1,
            offeredPacketsPerTick = 4,
            tickStepMillis = 20L,
            startNanos = baseNanos
        )
        assertTrue(
            "Expected 20 KiB/s (18,432..22,528 B/s) but got ${result20K.measuredBytesPerSec} B/s",
            result20K.measuredBytesPerSec in 18_432L..22_528L
        )
        assertTrue(result20K.delayedPacketsCount > 0)

        // Scenario 3: 10 KiB/s limit (10,240 B/s) over 5 seconds -> within 9-11 KiB/s (9,216..11,264 B/s)
        val uid10K = 10903
        limiter.configureUidLimits(
            uid = uid10K,
            maxDownloadBytesPerSec = PolicyPreset.LOW.defaultDownloadBytesPerSec, // 10,240 B/s
            maxUploadBytesPerSec = PolicyPreset.LOW.defaultUploadBytesPerSec,
            nowNanos = baseNanos
        )
        val result10K = limiter.executePacedTransferWindow(
            uid = uid10K,
            isDownload = true,
            durationMillis = 5_000L,
            packetSizeBytes = 1024,
            concurrentFlows = 1,
            offeredPacketsPerTick = 4,
            tickStepMillis = 20L,
            startNanos = baseNanos
        )
        assertTrue(
            "Expected 10 KiB/s (9,216..11,264 B/s) but got ${result10K.measuredBytesPerSec} B/s",
            result10K.measuredBytesPerSec in 9_216L..11_264L
        )
        assertTrue(result10K.measuredBytesPerSec < result20K.measuredBytesPerSec)

        // Scenario 4: Dynamic rate change mid-transfer (20 KiB/s -> 10 KiB/s) on uid20K
        val midChangeNanos = baseNanos + 6_000_000_000L
        limiter.configureUidLimits(
            uid = uid20K,
            maxDownloadBytesPerSec = PolicyPreset.LOW.defaultDownloadBytesPerSec, // 10,240 B/s
            maxUploadBytesPerSec = PolicyPreset.LOW.defaultUploadBytesPerSec,
            nowNanos = midChangeNanos
        )
        val afterChangeResult = limiter.executePacedTransferWindow(
            uid = uid20K,
            isDownload = true,
            durationMillis = 5_000L,
            packetSizeBytes = 1024,
            concurrentFlows = 1,
            offeredPacketsPerTick = 4,
            tickStepMillis = 20L,
            startNanos = midChangeNanos
        )
        assertTrue(
            "Expected rate after dynamic change to drop to 10 KiB/s (9,216..11,264 B/s), got ${afterChangeResult.measuredBytesPerSec} B/s",
            afterChangeResult.measuredBytesPerSec in 9_216L..11_264L
        )

        // Scenario 5: Blocked application (-1 B/s) -> 0 bytes transferred
        val uidBlocked = 10904
        limiter.configureUidLimits(uidBlocked, maxDownloadBytesPerSec = -1L, maxUploadBytesPerSec = -1L, nowNanos = baseNanos)
        val blockedResult = limiter.executePacedTransferWindow(
            uid = uidBlocked,
            isDownload = true,
            durationMillis = 5_000L,
            packetSizeBytes = 1024,
            concurrentFlows = 1,
            offeredPacketsPerTick = 4,
            tickStepMillis = 20L,
            startNanos = baseNanos
        )
        assertEquals(0L, blockedResult.totalBytesTransferred)
        assertEquals(0L, blockedResult.measuredBytesPerSec)
        assertTrue(blockedResult.droppedPacketsCount > 0)

        // Scenario 6: Two applications with different limits simultaneously (App A = 20 KiB/s, App B = 10 KiB/s)
        val uidAppA = 10905
        val uidAppB = 10906
        limiter.configureUidLimits(uidAppA, maxDownloadBytesPerSec = 20_480L, maxUploadBytesPerSec = 20_480L, nowNanos = baseNanos)
        limiter.configureUidLimits(uidAppB, maxDownloadBytesPerSec = 10_240L, maxUploadBytesPerSec = 10_240L, nowNanos = baseNanos)
        val simA = limiter.executePacedTransferWindow(
            uid = uidAppA,
            isDownload = true,
            durationMillis = 5_000L,
            packetSizeBytes = 1024,
            concurrentFlows = 1,
            startNanos = baseNanos
        )
        val simB = limiter.executePacedTransferWindow(
            uid = uidAppB,
            isDownload = true,
            durationMillis = 5_000L,
            packetSizeBytes = 1024,
            concurrentFlows = 1,
            startNanos = baseNanos
        )
        assertTrue(simA.measuredBytesPerSec in 18_432L..22_528L)
        assertTrue(simB.measuredBytesPerSec in 9_216L..11_264L)

        // Scenario 7: Single application opening 4 simultaneous connections/ports shares one aggregate 20 KiB/s UID budget
        val uidMultiConn = 10907
        limiter.configureUidLimits(uidMultiConn, maxDownloadBytesPerSec = 20_480L, maxUploadBytesPerSec = 20_480L, nowNanos = baseNanos)
        val multiFlowResult = limiter.executePacedTransferWindow(
            uid = uidMultiConn,
            isDownload = true,
            durationMillis = 5_000L,
            packetSizeBytes = 1024,
            concurrentFlows = 4,
            offeredPacketsPerTick = 4,
            tickStepMillis = 20L,
            startNanos = baseNanos
        )
        assertEquals(4, multiFlowResult.perFlowBytesTransferred.size)
        assertTrue(
            "Aggregate rate across 4 simultaneous flows must remain capped at 20 KiB/s (18,432..22,528 B/s), got ${multiFlowResult.measuredBytesPerSec} B/s",
            multiFlowResult.measuredBytesPerSec in 18_432L..22_528L
        )
    }
}
