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
    val maxAcceptableDelayMillis: Long = 2500L,
    private val burstWindowSeconds: Double = 0.20
) {
    private class BucketState(
        @Volatile var rateBytesPerSec: Long, // 0 = غير محدود, -1 = محظور
        @Volatile var capacityBytes: Double,
        @Volatile var availableTokens: Double,
        @Volatile var lastRefillNanos: Long,
        private val burstWindowSeconds: Double
    ) {
        @Synchronized
        fun updateRate(newRateBytesPerSec: Long, nowNanos: Long) {
            refill(nowNanos)
            rateBytesPerSec = newRateBytesPerSec
            if (newRateBytesPerSec > 0) {
                capacityBytes = (newRateBytesPerSec * burstWindowSeconds).coerceAtLeast(4096.0)
                availableTokens = availableTokens.coerceIn(0.0, capacityBytes)
            } else {
                capacityBytes = 0.0
                availableTokens = 0.0
            }
        }

        @Synchronized
        fun tryAcquire(
            packetBytes: Int,
            nowNanos: Long,
            currentQueueDepth: Int,
            maxQueueDepth: Int,
            maxAcceptableDelayMillis: Long
        ): RateLimitDecision {
            if (rateBytesPerSec < 0L) return RateLimitDecision.DropBlockedByPolicy
            if (rateBytesPerSec == 0L) return RateLimitDecision.PassImmediately(Long.MAX_VALUE)

            refill(nowNanos)
            val cost = packetBytes.coerceAtLeast(1).toDouble()

            if (availableTokens >= cost) {
                availableTokens -= cost
                return RateLimitDecision.PassImmediately(availableTokens.toLong())
            }

            val deficitBytes = cost - availableTokens
            val waitSeconds = deficitBytes / rateBytesPerSec.toDouble()
            val waitMillis = (waitSeconds * 1000.0).toLong().coerceAtLeast(1L)

            if (currentQueueDepth >= maxQueueDepth || waitMillis > maxAcceptableDelayMillis) {
                // لا نخصم أي رموز للحزم المسقطة بسبب امتلاء الطابور
                return RateLimitDecision.DropQueueOverflow(currentQueueDepth)
            }

            // حجز الرموز للحزمة المؤجلة التي تم قبولها في طابور الجدولة الزمنية
            availableTokens -= cost
            return RateLimitDecision.DelayPacket(waitMillis, currentQueueDepth + 1)
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

    data class PacedTransferMetrics(
        val uid: Int,
        val totalBytesTransferred: Long,
        val totalPacketsTransferred: Int,
        val delayedPacketsCount: Int,
        val droppedPacketsCount: Int,
        val elapsedMillis: Long,
        val measuredBytesPerSec: Long,
        val perFlowBytesTransferred: Map<Int, Long>
    )

    private val downloadBuckets = ConcurrentHashMap<Int, BucketState>()
    private val uploadBuckets = ConcurrentHashMap<Int, BucketState>()
    private val currentQueueCount = AtomicInteger(0)
    private val totalDelayedPackets = AtomicLong(0L)
    private val totalDroppedOverflowPackets = AtomicLong(0L)

    /**
     * يضبط حدود سرعة التنزيل والرفع لـ UID معين (بالبايت/ثانية).
     * 0 = غير محدود، -1 = حظر كامل.
     * تشترك جميع اتصالات ومنافذ هذا الـ UID في نفس الدلو الإجمالي.
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

    fun getConfiguredDownloadLimit(uid: Int): Long = downloadBuckets[uid]?.rateBytesPerSec ?: 0L
    fun getConfiguredUploadLimit(uid: Int): Long = uploadBuckets[uid]?.rateBytesPerSec ?: 0L

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
        val cap = if (rateBytesPerSec > 0) (rateBytesPerSec * burstWindowSeconds).coerceAtLeast(4096.0) else 0.0
        val existing = map[uid]
        if (existing != null) {
            existing.updateRate(rateBytesPerSec, nowNanos)
        } else {
            map[uid] = BucketState(
                rateBytesPerSec = rateBytesPerSec,
                capacityBytes = cap,
                availableTokens = cap,
                lastRefillNanos = nowNanos,
                burstWindowSeconds = burstWindowSeconds
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

        val decision = bucket.tryAcquire(
            packetBytes = packetSizeBytes,
            nowNanos = nowNanos,
            currentQueueDepth = currentQueueCount.get(),
            maxQueueDepth = maxQueueDepth,
            maxAcceptableDelayMillis = maxAcceptableDelayMillis
        )
        when (decision) {
            is RateLimitDecision.DelayPacket -> {
                val pos = currentQueueCount.incrementAndGet()
                totalDelayedPackets.incrementAndGet()
                return decision.copy(queuePosition = pos)
            }
            is RateLimitDecision.DropQueueOverflow -> {
                totalDroppedOverflowPackets.incrementAndGet()
                return decision
            }
            else -> return decision
        }
    }

    /**
     * يُستدعى بعد انتهاء تأخير الحزمة وإرسالها لتحرير مقعدها في طابور الانتظار.
     */
    fun onDelayedPacketDispatched() {
        currentQueueCount.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
    }

    /**
     * محرك جدولة وتمرير الحزم الموزونة زمنياً (Paced Packet Stream) لاختبار والتحقق من معدل النقل الفعلي
     * عبر تدفق واحد أو عدة تدفقات متزامنة تشترك في ميزانية UID واحد (القسم 5.2 و 5.4 و 7.2).
     */
    fun executePacedTransferWindow(
        uid: Int,
        isDownload: Boolean,
        durationMillis: Long,
        packetSizeBytes: Int = 1024,
        concurrentFlows: Int = 1,
        offeredPacketsPerTick: Int = 4,
        tickStepMillis: Long = 20L,
        startNanos: Long = 1_000_000_000L,
        onTickCallback: ((elapsedMillis: Long, currentNanos: Long) -> Unit)? = null
    ): PacedTransferMetrics {
        val flowsCount = concurrentFlows.coerceAtLeast(1)
        val perFlowBytes = HashMap<Int, Long>()
        for (f in 0 until flowsCount) {
            perFlowBytes[f] = 0L
        }

        var totalBytes = 0L
        var totalPackets = 0
        var delayedCount = 0
        var droppedCount = 0

        val scheduledReleasesMillis = ArrayList<Pair<Long, Pair<Int, Int>>>()
        var elapsed = 0L
        var flowCursor = 0

        while (elapsed < durationMillis) {
            val currentNanos = startNanos + elapsed * 1_000_000L
            onTickCallback?.invoke(elapsed, currentNanos)

            // تحرير الحزم المؤجلة التي حان وقت إرسالها
            val iterator = scheduledReleasesMillis.iterator()
            while (iterator.hasNext()) {
                val item = iterator.next()
                if (item.first <= elapsed) {
                    val (fId, pktBytes) = item.second
                    totalBytes += pktBytes
                    totalPackets++
                    perFlowBytes[fId] = (perFlowBytes[fId] ?: 0L) + pktBytes
                    onDelayedPacketDispatched()
                    iterator.remove()
                }
            }

            // محاولة إرسال حزم عبر التدفقات المتزامنة
            for (i in 0 until offeredPacketsPerTick) {
                val flowId = flowCursor % flowsCount
                flowCursor++

                when (val decision = acquirePacket(uid, packetSizeBytes, isDownload, currentNanos)) {
                    is RateLimitDecision.PassImmediately -> {
                        totalBytes += packetSizeBytes
                        totalPackets++
                        perFlowBytes[flowId] = (perFlowBytes[flowId] ?: 0L) + packetSizeBytes
                    }
                    is RateLimitDecision.DelayPacket -> {
                        delayedCount++
                        val releaseAtMillis = elapsed + decision.delayMillis
                        if (releaseAtMillis <= durationMillis) {
                            scheduledReleasesMillis.add(releaseAtMillis to (flowId to packetSizeBytes))
                        } else {
                            // تنتهي نافذة القياس قبل موعد إطلاق الحزمة، نحرر مقعدها عند انتهاء النافذة
                            onDelayedPacketDispatched()
                        }
                    }
                    is RateLimitDecision.DropQueueOverflow,
                    is RateLimitDecision.DropBlockedByPolicy -> {
                        droppedCount++
                    }
                }
            }

            elapsed += tickStepMillis
        }

        // تحرير أي حزم حان موعدها عند نهاية النافذة تماماً
        val iterator = scheduledReleasesMillis.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item.first <= durationMillis) {
                val (fId, pktBytes) = item.second
                totalBytes += pktBytes
                totalPackets++
                perFlowBytes[fId] = (perFlowBytes[fId] ?: 0L) + pktBytes
            }
            onDelayedPacketDispatched()
            iterator.remove()
        }

        val measuredBps = if (durationMillis > 0L) (totalBytes * 1000L) / durationMillis else 0L
        return PacedTransferMetrics(
            uid = uid,
            totalBytesTransferred = totalBytes,
            totalPacketsTransferred = totalPackets,
            delayedPacketsCount = delayedCount,
            droppedPacketsCount = droppedCount,
            elapsedMillis = durationMillis,
            measuredBytesPerSec = measuredBps,
            perFlowBytesTransferred = perFlowBytes
        )
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
