package com.example.network.rate_limit

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * قرار محدد السرعة (Token Bucket Rate Limiter Decision - القسم الخامس).
 */
sealed class RateLimitDecision {
    /** تتوفر رموز كافية؛ تمرر الحزمة فورًا دون تأخير */
    data class PassImmediately(val remainingTokensBytes: Long) : RateLimitDecision()

    /** لا تتوفر رموز كافية الآن، لكن الطابور يسمح بجدولة الحزمة بعد انتظار محدد بالمللي ثانية */
    data class DelayPacket(val delayMillis: Long, val queuePosition: Int) : RateLimitDecision()

    /** طابور الانتظار ممتلئ؛ تُسقط الحزمة لمنع استنزاف الذاكرة (Tail-Drop Congestion Control) */
    data class DropQueueOverflow(val currentQueueDepth: Int) : RateLimitDecision()

    /** الاتصال محظور بموجب السياسة */
    data object DropBlockedByPolicy : RateLimitDecision()
}

/**
 * خوارزمية Token Bucket لتحديد معدل التنزيل والرفع بصورة مستقلة لكل UID (القسم الرابع والخامس).
 *
 * التصميم الهندسي:
 * - لكل UID دلوان مستقلان: دلو للتنزيل (Download Bucket) ودلو للرفع (Upload Bucket).
 * - يدعم Burst Capacity مضبوطة (افتراضيًا تعادل 500ms من المعدل المحدد وبحد أدنى حزمة MTU واحدة 1500 بايت)
 *   لضمان عدم انكسار مصافحات TCP و DNS الصغيرة.
 * - يحدد حدًا أقصى لطابور الانتظار (maxQueueDepth) ويعالج الازدحام بإسقاط الحزم الزائدة (Tail Drop)
 *   حتى لا ينهار المحرك أو يستهلك ذاكرة غير محدودة.
 */
class TokenBucketRateLimiter(
    val maxQueueDepth: Int = 512,
    val maxAcceptableDelayMillis: Long = 1500L
) {
    private class BucketState(
        @Volatile var rateBytesPerSec: Long, // 0 = غير محدود, -1 = محظور
        @Volatile var capacityBytes: Double,
        @Volatile var availableTokens: Double,
        @Volatile var lastRefillNanos: Long
    ) {
        @Synchronized
        fun updateRate(newRateBytesPerSec: Long, nowNanos: Long) {
            refill(nowNanos)
            rateBytesPerSec = newRateBytesPerSec
            if (newRateBytesPerSec > 0) {
                // سعة الاندفاع (Burst) = نصف ثانية من المعدل أو 2048 بايت أيهما أكبر
                capacityBytes = (newRateBytesPerSec * 0.5).coerceAtLeast(2048.0)
                availableTokens = min(availableTokens, capacityBytes)
            }
        }

        @Synchronized
        fun consumeOrCalculateDelay(packetBytes: Int, nowNanos: Long): Pair<Boolean, Long> {
            if (rateBytesPerSec < 0L) return false to -1L // محظور
            if (rateBytesPerSec == 0L) return true to 0L   // غير محدود

            refill(nowNanos)
            val cost = packetBytes.coerceAtLeast(1).toDouble()

            if (availableTokens >= cost) {
                availableTokens -= cost
                return true to 0L
            }

            val deficitBytes = cost - availableTokens
            val waitSeconds = deficitBytes / rateBytesPerSec.toDouble()
            val waitMillis = (waitSeconds * 1000.0).toLong().coerceAtLeast(1L)

            // نحجز الرموز بالسالب ضمن السقف المسموح للانتظار
            availableTokens -= cost
            return false to waitMillis
        }

        @Synchronized
        fun currentTokens(nowNanos: Long): Long {
            refill(nowNanos)
            return availableTokens.toLong()
        }

        private fun refill(nowNanos: Long) {
            if (rateBytesPerSec <= 0L) {
                lastRefillNanos = nowNanos
                return
            }
            val elapsedNanos = (nowNanos - lastRefillNanos).coerceAtLeast(0L)
            if (elapsedNanos > 0L) {
                val addedTokens = (elapsedNanos.toDouble() / 1_000_000_000.0) * rateBytesPerSec.toDouble()
                availableTokens = min(capacityBytes, availableTokens + addedTokens)
                lastRefillNanos = nowNanos
            }
        }
    }

    private val downloadBuckets = ConcurrentHashMap<Int, BucketState>()
    private val uploadBuckets = ConcurrentHashMap<Int, BucketState>()
    private val currentQueueCount = AtomicInteger(0)
    private val totalDelayedPackets = AtomicLong(0L)
    private val totalDroppedOverflowPackets = AtomicLong(0L)

    /**
     * يضبط حدود سرعة التنزيل والرفع لـ UID معين (بالبايت/ثانية).
     * 0 = غير محدود، -1 = حظر كامل.
     */
    fun configureUidLimits(
        uid: Int,
        maxDownloadBytesPerSec: Long,
        maxUploadBytesPerSec: Long,
        nowNanos: Long = System.nanoTime()
    ) {
        updateBucketInMap(downloadBuckets, uid, maxDownloadBytesPerSec, nowNanos)
        updateBucketInMap(uploadBuckets, uid, maxUploadBytesPerSec, nowNanos)
    }

    private fun updateBucketInMap(
        map: ConcurrentHashMap<Int, BucketState>,
        uid: Int,
        rateBytesPerSec: Long,
        nowNanos: Long
    ) {
        if (rateBytesPerSec == 0L) {
            map.remove(uid)
            return
        }
        val cap = if (rateBytesPerSec > 0) (rateBytesPerSec * 0.5).coerceAtLeast(2048.0) else 0.0
        val existing = map[uid]
        if (existing != null) {
            existing.updateRate(rateBytesPerSec, nowNanos)
        } else {
            map[uid] = BucketState(
                rateBytesPerSec = rateBytesPerSec,
                capacityBytes = cap,
                availableTokens = cap,
                lastRefillNanos = nowNanos
            )
        }
    }

    /**
     * يقيّم حزمة واردة (تنزيل) أو صادرة (رفع) ويحدد ما إذا كانت ستمر فورًا أو تؤخر أو تسقط.
     */
    fun acquirePacket(
        uid: Int,
        packetSizeBytes: Int,
        isDownload: Boolean,
        nowNanos: Long = System.nanoTime()
    ): RateLimitDecision {
        val map = if (isDownload) downloadBuckets else uploadBuckets
        val bucket = map[uid] ?: return RateLimitDecision.PassImmediately(Long.MAX_VALUE)

        val (passedImmediately, waitMillis) = bucket.consumeOrCalculateDelay(packetSizeBytes, nowNanos)
        if (waitMillis < 0L) {
            return RateLimitDecision.DropBlockedByPolicy
        }
        if (passedImmediately) {
            return RateLimitDecision.PassImmediately(bucket.currentTokens(nowNanos))
        }

        // الحزمة تحتاج إلى تأخير: نتحقق من امتلاء طابور الانتظار أو تجاوز زمن التأخير الأقصى
        val qDepth = currentQueueCount.get()
        if (qDepth >= maxQueueDepth || waitMillis > maxAcceptableDelayMillis) {
            totalDroppedOverflowPackets.incrementAndGet()
            return RateLimitDecision.DropQueueOverflow(qDepth)
        }

        val pos = currentQueueCount.incrementAndGet()
        totalDelayedPackets.incrementAndGet()
        return RateLimitDecision.DelayPacket(waitMillis, pos)
    }

    /**
     * يُستدعى بعد انتهاء تأخير الحزمة وإرسالها لتحرير مقعدها في طابور الانتظار.
     */
    fun onDelayedPacketDispatched() {
        currentQueueCount.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
    }

    fun getQueueDepth(): Int = currentQueueCount.get()
    fun getTotalDelayedCount(): Long = totalDelayedPackets.get()
    fun getTotalDroppedOverflowCount(): Long = totalDroppedOverflowPackets.get()

    fun clearAll() {
        downloadBuckets.clear()
        uploadBuckets.clear()
        currentQueueCount.set(0)
    }
}
