package com.netlimiterpro.domain.network

import kotlin.math.min

/**
 * Thread-safe token bucket rate limiter.
 *
 * The caller should request permission for each outgoing byte chunk before
 * forwarding it. A limit of zero represents unlimited throughput.
 *
 * This class is a traffic-shaping primitive; it does not itself intercept
 * sockets or associate packets with Android UIDs.
 */
class TokenBucketThrottler(
    bytesPerSecond: Long,
    private val burstCapacityBytes: Long = defaultBurst(bytesPerSecond),
    private val nanoTime: () -> Long = System::nanoTime
) {
    @Volatile
    var bytesPerSecond: Long = bytesPerSecond
        set(value) {
            require(value >= 0L) { "Rate must be non-negative" }
            synchronized(lock) {
                field = value
                capacity = effectiveCapacity(value, burstCapacityBytes)
                tokens = min(tokens, capacity.toDouble())
                lastRefillNanos = nanoTime()
            }
        }

    private val lock = Any()
    private var capacity: Long = effectiveCapacity(bytesPerSecond, burstCapacityBytes)
    private var tokens: Double = capacity.toDouble()
    private var lastRefillNanos: Long = nanoTime()

    init {
        require(bytesPerSecond >= 0L) { "Rate must be non-negative" }
        require(burstCapacityBytes > 0L) { "Burst capacity must be positive" }
    }

    /**
     * Returns the number of bytes that can be sent immediately, capped by
     * [requestedBytes]. For a positive rate, a zero result means the caller
     * should wait and retry. A zero configured rate is unlimited.
     */
    fun acquireUpTo(requestedBytes: Int): Int {
        require(requestedBytes >= 0) { "Requested byte count must be non-negative" }
        if (requestedBytes == 0) return 0
        val rate = bytesPerSecond
        if (rate == 0L) return requestedBytes

        synchronized(lock) {
            refill(nanoTime())
            val granted = min(requestedBytes.toDouble(), tokens).toInt()
            tokens -= granted.toDouble()
            return granted
        }
    }

    /**
     * Estimated nanoseconds until at least [requiredBytes] tokens are
     * available. Returns zero for unlimited rates or invalidly large requests.
     */
    fun nanosUntilAvailable(requiredBytes: Int): Long {
        require(requiredBytes >= 0) { "Required byte count must be non-negative" }
        if (requiredBytes == 0 || bytesPerSecond == 0L) return 0L
        synchronized(lock) {
            refill(nanoTime())
            if (requiredBytes.toDouble() <= tokens) return 0L
            val missing = requiredBytes.toDouble() - tokens
            val nanos = missing * NANOS_PER_SECOND.toDouble() / bytesPerSecond.toDouble()
            return nanos.coerceAtLeast(0.0)
                .coerceAtMost(Long.MAX_VALUE.toDouble()).toLong()
        }
    }

    /** Wait-free inspection of current approximate bucket state. */
    fun availableTokens(): Long = synchronized(lock) {
        refill(nanoTime())
        tokens.toLong().coerceAtLeast(0L)
    }

    private fun refill(now: Long) {
        val elapsed = (now - lastRefillNanos).coerceAtLeast(0L)
        if (elapsed > 0L && bytesPerSecond > 0L) {
            val added = elapsed.toDouble() * bytesPerSecond.toDouble() / NANOS_PER_SECOND.toDouble()
            tokens = min(capacity.toDouble(), tokens + added)
        }
        lastRefillNanos = now
    }

    companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val BYTES_PER_KILOBYTE = 1_000L
        private const val BYTES_PER_MEGABYTE = 1_000_000L

        fun kilobytesPerSecondToBytes(value: Double): Long {
            require(value.isFinite() && value >= 0.0) { "Speed must be finite and non-negative" }
            val bytes = value * BYTES_PER_KILOBYTE.toDouble()
            require(bytes <= Long.MAX_VALUE.toDouble()) { "Speed is too large" }
            return bytes.toLong()
        }

        fun megabytesPerSecondToBytes(value: Double): Long {
            require(value.isFinite() && value >= 0.0) { "Speed must be finite and non-negative" }
            val bytes = value * BYTES_PER_MEGABYTE.toDouble()
            require(bytes <= Long.MAX_VALUE.toDouble()) { "Speed is too large" }
            return bytes.toLong()
        }

        fun bytesPerSecondToKilobytes(value: Long): Double {
            require(value >= 0L) { "Speed must be non-negative" }
            return value.toDouble() / BYTES_PER_KILOBYTE.toDouble()
        }

        fun bytesPerSecondToMegabytes(value: Long): Double {
            require(value >= 0L) { "Speed must be non-negative" }
            return value.toDouble() / BYTES_PER_MEGABYTE.toDouble()
        }

        private fun defaultBurst(rate: Long): Long = if (rate > 0L) rate.coerceAtLeast(1L) else 64L * 1024L

        private fun effectiveCapacity(rate: Long, requested: Long): Long {
            require(rate >= 0L) { "Rate must be non-negative" }
            require(requested > 0L) { "Burst capacity must be positive" }
            return if (rate == 0L) requested else min(requested, rate.coerceAtLeast(1L))
        }
    }
}
