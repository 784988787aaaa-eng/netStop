package com.netlimiterpro

import com.netlimiterpro.domain.model.AppRule
import com.netlimiterpro.domain.model.NetworkPolicy
import com.netlimiterpro.domain.network.TokenBucketThrottler
import com.netlimiterpro.service.PacketShaperEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class NetLimiterTests {

    @Test
    fun testTokenBucketThrottlerAcquisition() {
        val throttler = TokenBucketThrottler(bytesPerSecond = 100_000L)
        val acquired = throttler.acquireUpTo(50_000)
        assertTrue(acquired in 1..50_000)
    }

    @Test
    fun testSpeedConversions() {
        val bytes = TokenBucketThrottler.kilobytesPerSecondToBytes(250.0)
        assertEquals(250_000L, bytes)
        val kb = TokenBucketThrottler.bytesPerSecondToKilobytes(250_000L)
        assertEquals(250.0, kb, 0.001)
    }

    @Test
    fun testAppRulePolicyDefaults() {
        val rule = AppRule(uid = 10123, packageName = "com.android.chrome")
        assertEquals(NetworkPolicy.ALLOW, rule.wifiPolicy)
        assertEquals(NetworkPolicy.ALLOW, rule.mobilePolicy)

        val blockedRule = rule.copy(wifiPolicy = NetworkPolicy.BLOCK, wifiEnabled = true)
        assertEquals(NetworkPolicy.BLOCK, blockedRule.policyFor(isWifi = true))
        assertEquals(NetworkPolicy.ALLOW, blockedRule.policyFor(isWifi = false))
        assertTrue(blockedRule.isEnabledFor(isWifi = true))
    }

    @Test
    fun testBuildIpv4UdpPacketValid() {
        val src = InetAddress.getByName("10.111.0.1") as Inet4Address
        val dst = InetAddress.getByName("8.8.8.8") as Inet4Address
        val payload = "DNS_TEST_QUERY".toByteArray()

        val packetBytes = PacketShaperEngine.buildIpv4UdpPacket(
            source = src,
            destination = dst,
            sourcePort = 54321,
            destinationPort = 53,
            payload = payload
        )

        assertNotNull(packetBytes)
        // 20 bytes IPv4 header + 8 bytes UDP header + payload size
        assertEquals(28 + payload.size, packetBytes.size)

        // Verify IPv4 version and header length
        assertEquals(0x45.toByte(), packetBytes[0])
        // Protocol 17 is UDP
        assertEquals(17.toByte(), packetBytes[9])
    }
}
