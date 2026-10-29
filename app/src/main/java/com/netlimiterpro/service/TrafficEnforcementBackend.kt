package com.netlimiterpro.service

import android.net.VpnService
import com.netlimiterpro.domain.model.AppRule
import com.netlimiterpro.domain.network.ActiveNetworkKind
import java.net.Socket

/** Selects root kernel guard when it is actually installed, otherwise local HTTP proxy. */
class TrafficEnforcementBackend(private val vpnService: VpnService) {
    enum class Mode { VPN_RELAY, ROOT_TC, UNAVAILABLE }
    data class Capability(val mode: Mode, val available: Boolean, val explanation: String)

    private val rootShaper = RootTrafficShaper()
    @Volatile private var activeMode = Mode.UNAVAILABLE

    fun detect(): Capability = if (rootShaper.hasRoot()) {
        Capability(Mode.ROOT_TC, true, "Root shell available; hashlimit/drop guard can be attempted. This is lossy egress limiting, not smooth shaping.")
    } else {
        Capability(Mode.VPN_RELAY, true, "Local HTTP/HTTPS CONNECT proxy is available for proxy-aware applications only; it does not capture arbitrary TCP/UDP or QUIC.")
    }

    fun activateRootIfAvailable(rules: List<AppRule>, network: ActiveNetworkKind): Capability? {
        if (!rootShaper.hasRoot()) return null
        val result = rootShaper.apply(rules, network)
        if (result.success) {
            activeMode = Mode.ROOT_TC
            return Capability(Mode.ROOT_TC, true, result.detail)
        }
        rootShaper.clear()
        return null
    }

    fun clearRoot() { rootShaper.clear(); if (activeMode == Mode.ROOT_TC) activeMode = Mode.UNAVAILABLE }

    fun mode(): Mode = activeMode

    fun protect(socket: Socket): Boolean = vpnService.protect(socket)

    fun rootShaper(): RootTrafficShaper = rootShaper
}
