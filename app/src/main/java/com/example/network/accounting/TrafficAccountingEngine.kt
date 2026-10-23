package com.example.network.accounting

import com.example.core.model.ActiveNetworkTransport
import com.example.core.model.QuotaPeriod
import com.example.core.model.QuotaRule
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/**
 * محرك حساب معدلات النقل والاستهلاك التراكمي وإدارة الحصص (Traffic Accounting & Quota Manager - القسم الرابع والخامس).
 *
 * ملاحظة هندسية حول دقة الحصص (Section 5 Quota Overage Documentation):
 * عند بلوغ التطبيق للحد الأقصى للحصة، قد يظهر تجاوز طفيف (In-Flight Margin) يتراوح عادة بين 16 KB إلى 64 KB
 * نتيجة الحزم الموجودة بالفعل داخل مخازن النواة (Kernel Socket Buffers) قبل تطبيق سياسة القطع أو التقييد في دورة القياس التالية (500ms-1000ms).
 */
class TrafficAccountingEngine {

    companion object {
        const val DOCUMENTED_IN_FLIGHT_MARGIN_BYTES = 64L * 1024L // 64 KB هامش الحزم العابرة الموثق
    }

    data class UidAccountingSnapshot(
        val uid: Int,
        val downloadBytesPerSec: Long,
        val uploadBytesPerSec: Long,
        val deltaRxBytes: Long,
        val deltaTxBytes: Long,
        val sessionRxBytes: Long,
        val sessionTxBytes: Long,
        val wifiTotalBytes: Long,
        val cellularTotalBytes: Long,
        val lastTimestampMillis: Long
    )

    private data class InternalState(
        var baselineRxBytes: Long,
        var baselineTxBytes: Long,
        var lastRxBytes: Long,
        var lastTxBytes: Long,
        var lastSampleMillis: Long,
        var smoothedDownBps: Long = 0L,
        var smoothedUpBps: Long = 0L,
        var wifiAccumulatedBytes: Long = 0L,
        var cellularAccumulatedBytes: Long = 0L
    )

    private val uidStates = ConcurrentHashMap<Int, InternalState>()

    /**
     * يحسب السرعة اللحظية (بايت/ثانية) والاستهلاك التراكمي لـ UID بناءً على قراءات النواة أو محرك VPN.
     */
    fun recordSample(
        uid: Int,
        currentKernelRxBytes: Long,
        currentKernelTxBytes: Long,
        activeTransport: ActiveNetworkTransport,
        nowMillis: Long = System.currentTimeMillis()
    ): UidAccountingSnapshot {
        val safeRx = currentKernelRxBytes.coerceAtLeast(0L)
        val safeTx = currentKernelTxBytes.coerceAtLeast(0L)

        val state = uidStates.getOrPut(uid) {
            InternalState(
                baselineRxBytes = safeRx,
                baselineTxBytes = safeTx,
                lastRxBytes = safeRx,
                lastTxBytes = safeTx,
                lastSampleMillis = nowMillis
            )
        }

        synchronized(state) {
            val elapsedMillis = (nowMillis - state.lastSampleMillis).coerceAtLeast(1L)
            val deltaRx = (safeRx - state.lastRxBytes).coerceAtLeast(0L)
            val deltaTx = (safeTx - state.lastTxBytes).coerceAtLeast(0L)

            val instantDownBps = if (elapsedMillis >= 100L) {
                (deltaRx * 1000L) / elapsedMillis
            } else {
                state.smoothedDownBps
            }
            val instantUpBps = if (elapsedMillis >= 100L) {
                (deltaTx * 1000L) / elapsedMillis
            } else {
                state.smoothedUpBps
            }

            // تنعيم أسي خفيف (70% قراءة لحظية + 30% قراءة سابقة) لتجنب التذبذب الحاد مع الحفاظ على الاستجابة الفورية
            val downBps = if (deltaRx == 0L) 0L else ((instantDownBps * 7L + state.smoothedDownBps * 3L) / 10L).coerceAtLeast(1L)
            val upBps = if (deltaTx == 0L) 0L else ((instantUpBps * 7L + state.smoothedUpBps * 3L) / 10L).coerceAtLeast(1L)

            state.smoothedDownBps = downBps
            state.smoothedUpBps = upBps
            state.lastRxBytes = safeRx
            state.lastTxBytes = safeTx
            state.lastSampleMillis = nowMillis

            val totalDelta = deltaRx + deltaTx
            when (activeTransport) {
                ActiveNetworkTransport.WIFI, ActiveNetworkTransport.ETHERNET -> {
                    state.wifiAccumulatedBytes += totalDelta
                }
                ActiveNetworkTransport.CELLULAR -> {
                    state.cellularAccumulatedBytes += totalDelta
                }
                else -> {
                    state.wifiAccumulatedBytes += totalDelta
                }
            }

            val sessionRx = (safeRx - state.baselineRxBytes).coerceAtLeast(0L)
            val sessionTx = (safeTx - state.baselineTxBytes).coerceAtLeast(0L)

            return UidAccountingSnapshot(
                uid = uid,
                downloadBytesPerSec = downBps,
                uploadBytesPerSec = upBps,
                deltaRxBytes = deltaRx,
                deltaTxBytes = deltaTx,
                sessionRxBytes = sessionRx,
                sessionTxBytes = sessionTx,
                wifiTotalBytes = state.wifiAccumulatedBytes,
                cellularTotalBytes = state.cellularAccumulatedBytes,
                lastTimestampMillis = nowMillis
            )
        }
    }

    /**
     * يتحقق مما إذا كانت دورة الحصة (يومية/أسبوعية/شهرية/مخصصة) قد انتهت وتستوجب إعادة التعيين التلقائي.
     */
    fun shouldResetQuotaPeriod(quota: QuotaRule, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (nowMillis <= quota.periodStartMillis) return false
        val elapsedMillis = nowMillis - quota.periodStartMillis
        val dayMillis = 24L * 3_600_000L

        return when (quota.period) {
            QuotaPeriod.DAILY -> elapsedMillis >= dayMillis || !isSameCalendarDay(quota.periodStartMillis, nowMillis)
            QuotaPeriod.WEEKLY -> elapsedMillis >= 7L * dayMillis
            QuotaPeriod.MONTHLY -> elapsedMillis >= 30L * dayMillis || !isSameCalendarMonth(quota.periodStartMillis, nowMillis)
            QuotaPeriod.CUSTOM -> elapsedMillis >= quota.customDurationDays.coerceAtLeast(1) * dayMillis
        }
    }

    /**
     * يحدّث استهلاك الحصة ويفحص حالة التحذير وتجاوز الحد، ويعيد تعيين الحصة تلقائيًا إذا دخلت فترة جديدة.
     */
    fun evaluateAndUpdateQuota(
        quota: QuotaRule,
        addedBytes: Long,
        nowMillis: Long = System.currentTimeMillis()
    ): QuotaEvaluationResult {
        if (shouldResetQuotaPeriod(quota, nowMillis)) {
            val resetQuota = quota.copy(
                usedBytes = addedBytes.coerceAtLeast(0L),
                periodStartMillis = nowMillis,
                warningTriggered = false,
                limitTriggered = false
            )
            return QuotaEvaluationResult(
                updatedQuota = resetQuota,
                periodWasReset = true,
                newlyTriggeredWarning = resetQuota.isWarningReached,
                newlyTriggeredLimit = resetQuota.isLimitReached
            )
        }

        val newUsed = (quota.usedBytes + addedBytes.coerceAtLeast(0L))
        val updated = quota.copy(usedBytes = newUsed)

        val newlyWarned = !quota.warningTriggered && updated.isWarningReached
        val newlyLimited = !quota.limitTriggered && updated.isLimitReached

        return QuotaEvaluationResult(
            updatedQuota = updated.copy(
                warningTriggered = quota.warningTriggered || newlyWarned,
                limitTriggered = quota.limitTriggered || newlyLimited
            ),
            periodWasReset = false,
            newlyTriggeredWarning = newlyWarned,
            newlyTriggeredLimit = newlyLimited
        )
    }

    fun resetAllSessionCounters() {
        uidStates.clear()
    }

    private fun isSameCalendarDay(t1: Long, t2: Long): Boolean {
        val c1 = Calendar.getInstance().apply { timeInMillis = t1 }
        val c2 = Calendar.getInstance().apply { timeInMillis = t2 }
        return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) &&
            c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR)
    }

    private fun isSameCalendarMonth(t1: Long, t2: Long): Boolean {
        val c1 = Calendar.getInstance().apply { timeInMillis = t1 }
        val c2 = Calendar.getInstance().apply { timeInMillis = t2 }
        return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) &&
            c1.get(Calendar.MONTH) == c2.get(Calendar.MONTH)
    }
}

data class QuotaEvaluationResult(
    val updatedQuota: QuotaRule,
    val periodWasReset: Boolean,
    val newlyTriggeredWarning: Boolean,
    val newlyTriggeredLimit: Boolean
)
