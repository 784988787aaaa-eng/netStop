package com.netlimiterpro.service

import android.content.Context
import java.io.File

/** Optional JNI boundary. The bundled native implementation is a safe stub, not a tun2socks engine. */
class Tun2SocksBridge(context: Context) {
    data class ProbeResult(
        val hevLibraryPresent: Boolean,
        val tun2SocksLibraryPresent: Boolean,
        val jniLibraryPresent: Boolean,
        val jniLibraryLoaded: Boolean,
        val fullTunnelReady: Boolean
    )

    private val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    private val jniLoaded: Boolean = runCatching { System.loadLibrary("netlimiter_tun2socks"); true }.getOrDefault(false)

    /** Returns -1 unless a real compatible native forwarding engine is linked. */
    fun start(tunFd: Int, socksHost: String, socksPort: Int, mtu: Int): Int = runCatching {
        if (!jniLoaded || tunFd < 0 || socksHost.isBlank() || socksPort !in 1..65535 || mtu !in 576..9000) -1
        else nativeStartTun2Socks(tunFd, socksHost, socksPort, mtu)
    }.getOrDefault(-1)

    fun stop() { runCatching { if (jniLoaded) nativeStopTun2Socks() } }

    /** Reads and discards at most one packet; only safe for BLOCK-only sinkhole mode. */
    fun drainSinkholePacket(tunFd: Int, timeoutMs: Int = 100): Int = runCatching {
        if (!jniLoaded || tunFd < 0) -1 else nativeDrainSinkholePacket(tunFd, timeoutMs.coerceIn(0, 1_000))
    }.getOrDefault(-1)

    private external fun nativeDrainSinkholePacket(tunFd: Int, timeoutMs: Int): Int
    private external fun nativeStartTun2Socks(tunFd: Int, socksHost: String, socksPort: Int, mtu: Int): Int
    private external fun nativeStopTun2Socks()

    fun probe(): ProbeResult {
        val hev = File(nativeDir, "libhev-socks5-tunnel.so").isFile
        val tun = File(nativeDir, "libtun2socks.so").isFile
        val jni = File(nativeDir, "libnetlimiter_tun2socks.so").isFile
        // The bundled JNI exports a deliberate -1 stub; its presence is not a working data plane.
        return ProbeResult(hev, tun, jni, jniLoaded, fullTunnelReady = false)
    }
}
