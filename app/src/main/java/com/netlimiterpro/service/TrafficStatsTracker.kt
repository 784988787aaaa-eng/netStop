package com.netlimiterpro.service

import android.net.TrafficStats
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe per-UID telemetry. Proxy-observed bytes and kernel UID counters are
 * kept in separate windows; snapshot() reports the larger rate rather than adding
 * two measurements of the same traffic together.
 */
object TrafficStatsTracker {
    data class Snapshot(
        val uid: Int,
        val bytesPerSecond: Long,
        val totalBytes: Long,
        val sampledAtMillis: Long
    )

    private data class Window(
        val windowStartMillis: AtomicLong = AtomicLong(System.currentTimeMillis()),
        val windowBytes: AtomicLong = AtomicLong(0L),
        val totalBytes: AtomicLong = AtomicLong(0L),
        val currentRate: AtomicLong = AtomicLong(0L)
    )

    private val proxyWindows = ConcurrentHashMap<Int, Window>()
    private val systemWindows = ConcurrentHashMap<Int, Window>()
    private val totalProxy = Window()
    private val lastSystemTotals = ConcurrentHashMap<Int, Long>()

    /** Records bytes forwarded by LocalThrottledProxyServer only. */
    fun recordProxyBytes(uid: Int?, byteCount: Long) {
        if (byteCount <= 0L) return
        val now = System.currentTimeMillis()
        recordInto(proxyWindows.computeIfAbsent(uid ?: UNKNOWN_UID) { Window() }, byteCount, now)
        recordInto(totalProxy, byteCount, now)
    }

    /** Backwards-compatible alias; new proxy code should call recordProxyBytes. */
    fun record(uid: Int?, byteCount: Long) = recordProxyBytes(uid, byteCount)

    /** Samples kernel per-UID byte counters into a separate, non-additive window. */
    fun sampleSystemUid(uid: Int): Snapshot {
        if (uid < 0) return snapshot(uid)
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        if (rx < 0L || tx < 0L) return snapshot(uid)
        val current = rx + tx
        val previous = lastSystemTotals.put(uid, current)
        if (previous != null && current >= previous) {
            val delta = current - previous
            if (delta > 0L) {
                recordInto(systemWindows.computeIfAbsent(uid) { Window() }, delta, System.currentTimeMillis())
            }
        }
        return snapshot(uid)
    }

    fun snapshot(uid: Int): Snapshot {
        val now = System.currentTimeMillis()
        val proxy = snapshotOf(uid, proxyWindows[uid], now)
        val system = snapshotOf(uid, systemWindows[uid], now)
        return Snapshot(
            uid = uid,
            bytesPerSecond = maxOf(proxy.bytesPerSecond, system.bytesPerSecond),
            totalBytes = maxOf(proxy.totalBytes, system.totalBytes),
            sampledAtMillis = now
        )
    }

    /** Aggregate reflects bytes actually observed by the local proxy, not duplicated system samples. */
    fun totalSnapshot(): Snapshot = snapshotOf(ALL_UIDS, totalProxy, System.currentTimeMillis())

    fun snapshots(): List<Snapshot> {
        val now = System.currentTimeMillis()
        return (proxyWindows.keys + systemWindows.keys).toSet()
            .map { uid ->
                val proxy = snapshotOf(uid, proxyWindows[uid], now)
                val system = snapshotOf(uid, systemWindows[uid], now)
                Snapshot(uid, maxOf(proxy.bytesPerSecond, system.bytesPerSecond), maxOf(proxy.totalBytes, system.totalBytes), now)
            }
            .sortedByDescending { it.bytesPerSecond }
    }

    private fun recordInto(window: Window, bytes: Long, now: Long) {
        window.totalBytes.addAndGet(bytes)
        val start = window.windowStartMillis.get()
        if (now - start >= WINDOW_MILLIS && window.windowStartMillis.compareAndSet(start, now)) {
            val elapsed = (now - start).coerceAtLeast(1L)
            val previous = window.windowBytes.getAndSet(0L)
            window.currentRate.set(previous * 1000L / elapsed)
        }
        window.windowBytes.addAndGet(bytes)
    }

    private fun snapshotOf(uid: Int, window: Window?, now: Long): Snapshot {
        if (window == null) return Snapshot(uid, 0L, 0L, now)
        val start = window.windowStartMillis.get()
        val elapsed = (now - start).coerceAtLeast(1L)
        val current = window.windowBytes.get()
        val rate = if (elapsed >= WINDOW_MILLIS) current * 1000L / elapsed else window.currentRate.get()
        return Snapshot(uid, rate, window.totalBytes.get(), now)
    }

    private const val WINDOW_MILLIS = 1_000L
    const val UNKNOWN_UID = -1
    const val ALL_UIDS = -2
}
