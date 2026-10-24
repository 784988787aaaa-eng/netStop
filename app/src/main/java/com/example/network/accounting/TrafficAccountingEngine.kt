package com.example.network.accounting

import com.example.core.model.ActiveNetworkTransport
import com.example.core.model.QuotaPeriod
import com.example.core.model.QuotaRule
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/**
 * محرك حساب معدلات النقل والاستهلاك التراكمي وإدارة الحصص (Traffic Accounting & Quota Manager - القسم 14 و 15).
 *
 * المبادئ الهندسية المنفذة:
 * 1. حساب السرعة من فرق العدادات وفاصل زمني معلوم.
 * 2. التحقق الصريح من إعادة تعيين العدادات (Counter Reset Detection) عند انخفاض عداد النواة عن القراءة السابقة،
 *    بحيث يحتفظ المحرك بالبايتات المتراكمة السابقة ولا يفقدها أو ينتج قيماً سالبة.
 * 3. الحفاظ على الاستهلاك التراكمي منذ إقلاع الجهاز (`includeInitialBaselineInSession = true`) مع حساب السرعة اللحظية من التغير الفعلي فقط.
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
        val counterResetDetected: Boolean = false,
        val isAvailable: Boolean = true,
        val duplicateOrStaleSample: Boolean = false,
        val lastTimestampMillis: Long
    )

    private data class InternalState(
        var baselineRxBytes: Long,
        var baselineTxBytes: Long,
        var priorAccumulatedRxBytes: Long = 0L,
        var priorAccumulatedTxBytes: Long = 0L,
        var lastRxBytes: Long,
        var lastTxBytes: Long,
        var lastSampleMillis: Long,
        var lastDownBps: Long = 0L,
        var lastUpBps: Long = 0L,
        var wifiAccumulatedBytes: Long = 0L,
        var cellularAccumulatedBytes: Long = 0L
    )

    private val uidStates = ConcurrentHashMap<Int, InternalState>()

    /**
     * يحسب السرعة اللحظية الدقيقة (بايت/ثانية) والاستهلاك التراكمي لـ UID بناءً على قراءات النواة أو محرك الإسناد:
     * rateBytesPerSecond = (currentBytes - previousBytes) / elapsedSeconds
     * - يرفض تحويل القراءات غير المتاحة (UNSUPPORTED < 0 أو isCounterAvailable == false) إلى صفر (القسم 1.7 و 3.2).
     * - يحمي من العينات المكررة أو المتأخرة زمنياً (nowMillis <= lastSampleMillis).
     * - يعالج إعادة تعيين العدادات (Counter Reset) دون قيم سالبة أو ضياع التراكم السابق.
     */
    fun recordSample(
        uid: Int,
        currentKernelRxBytes: Long,
        currentKernelTxBytes: Long,
        activeTransport: ActiveNetworkTransport,
        nowMillis: Long = System.currentTimeMillis(),
        includeInitialBaselineInSession: Boolean = false,
        isCounterAvailable: Boolean = true
    ): UidAccountingSnapshot {
        if (!isCounterAvailable || currentKernelRxBytes < 0L || currentKernelTxBytes < 0L) {
            val existing = uidStates[uid]
            return if (existing != null) {
                synchronized(existing) {
                    val sessionRx = existing.priorAccumulatedRxBytes + (existing.lastRxBytes - existing.baselineRxBytes).coerceAtLeast(0L)
                    val sessionTx = existing.priorAccumulatedTxBytes + (existing.lastTxBytes - existing.baselineTxBytes).coerceAtLeast(0L)
                    UidAccountingSnapshot(
                        uid = uid,
                        downloadBytesPerSec = -1L,
                        uploadBytesPerSec = -1L,
                        deltaRxBytes = 0L,
                        deltaTxBytes = 0L,
                        sessionRxBytes = sessionRx,
                        sessionTxBytes = sessionTx,
                        wifiTotalBytes = existing.wifiAccumulatedBytes,
                        cellularTotalBytes = existing.cellularAccumulatedBytes,
                        counterResetDetected = false,
                        isAvailable = false,
                        lastTimestampMillis = nowMillis
                    )
                }
            } else {
                UidAccountingSnapshot(
                    uid = uid,
                    downloadBytesPerSec = -1L,
                    uploadBytesPerSec = -1L,
                    deltaRxBytes = 0L,
                    deltaTxBytes = 0L,
                    sessionRxBytes = -1L,
                    sessionTxBytes = -1L,
                    wifiTotalBytes = -1L,
                    cellularTotalBytes = -1L,
                    counterResetDetected = false,
                    isAvailable = false,
                    lastTimestampMillis = nowMillis
                )
            }
        }

        val safeRx = currentKernelRxBytes
        val safeTx = currentKernelTxBytes

        var isFirstSampleForUid = false
        val state = uidStates.getOrPut(uid) {
            isFirstSampleForUid = true
            val initBaselineRx = if (includeInitialBaselineInSession) 0L else safeRx
            val initBaselineTx = if (includeInitialBaselineInSession) 0L else safeTx
            val initTotal = if (includeInitialBaselineInSession) (safeRx + safeTx) else 0L
            val initWifi = if (activeTransport != ActiveNetworkTransport.CELLULAR) initTotal else 0L
            val initCell = if (activeTransport == ActiveNetworkTransport.CELLULAR) initTotal else 0L
            InternalState(
                baselineRxBytes = initBaselineRx,
                baselineTxBytes = initBaselineTx,
                lastRxBytes = safeRx,
                lastTxBytes = safeTx,
                lastSampleMillis = nowMillis,
                wifiAccumulatedBytes = initWifi,
                cellularAccumulatedBytes = initCell
            )
        }

        synchronized(state) {
            if (isFirstSampleForUid) {
                val sessionRx = state.priorAccumulatedRxBytes + (safeRx - state.baselineRxBytes).coerceAtLeast(0L)
                val sessionTx = state.priorAccumulatedTxBytes + (safeTx - state.baselineTxBytes).coerceAtLeast(0L)
                return UidAccountingSnapshot(
                    uid = uid,
                    downloadBytesPerSec = 0L,
                    uploadBytesPerSec = 0L,
                    deltaRxBytes = 0L,
                    deltaTxBytes = 0L,
                    sessionRxBytes = sessionRx,
                    sessionTxBytes = sessionTx,
                    wifiTotalBytes = state.wifiAccumulatedBytes,
                    cellularTotalBytes = state.cellularAccumulatedBytes,
                    counterResetDetected = false,
                    isAvailable = true,
                    lastTimestampMillis = nowMillis
                )
            }

            // حماية من العينات المكررة أو غير المتقدمة زمنياً (Duplicate / Out-of-order Sample Protection)
            if (nowMillis <= state.lastSampleMillis) {
                val sessionRx = state.priorAccumulatedRxBytes + (state.lastRxBytes - state.baselineRxBytes).coerceAtLeast(0L)
                val sessionTx = state.priorAccumulatedTxBytes + (state.lastTxBytes - state.baselineTxBytes).coerceAtLeast(0L)
                return UidAccountingSnapshot(
                    uid = uid,
                    downloadBytesPerSec = state.lastDownBps,
                    uploadBytesPerSec = state.lastUpBps,
                    deltaRxBytes = 0L,
                    deltaTxBytes = 0L,
                    sessionRxBytes = sessionRx,
                    sessionTxBytes = sessionTx,
                    wifiTotalBytes = state.wifiAccumulatedBytes,
                    cellularTotalBytes = state.cellularAccumulatedBytes,
                    counterResetDetected = false,
                    isAvailable = true,
                    duplicateOrStaleSample = true,
                    lastTimestampMillis = state.lastSampleMillis
                )
            }

            val elapsedMillis = nowMillis - state.lastSampleMillis

            // التحقق من إعادة تعيين العدادات (Counter Reset Detection - Section 3.3 & 4.1)
            var resetDetected = false
            val deltaRx = if (safeRx < state.lastRxBytes) {
                resetDetected = true
                state.priorAccumulatedRxBytes += (state.lastRxBytes - state.baselineRxBytes).coerceAtLeast(0L)
                state.baselineRxBytes = 0L
                safeRx
            } else {
                safeRx - state.lastRxBytes
            }

            val deltaTx = if (safeTx < state.lastTxBytes) {
                resetDetected = true
                state.priorAccumulatedTxBytes += (state.lastTxBytes - state.baselineTxBytes).coerceAtLeast(0L)
                state.baselineTxBytes = 0L
                safeTx
            } else {
                safeTx - state.lastTxBytes
            }

            val downBps = if (deltaRx == 0L) 0L else (deltaRx * 1000L) / elapsedMillis
            val upBps = if (deltaTx == 0L) 0L else (deltaTx * 1000L) / elapsedMillis

            state.lastDownBps = downBps
            state.lastUpBps = upBps
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

            val sessionRx = state.priorAccumulatedRxBytes + (safeRx - state.baselineRxBytes).coerceAtLeast(0L)
            val sessionTx = state.priorAccumulatedTxBytes + (safeTx - state.baselineTxBytes).coerceAtLeast(0L)

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
                counterResetDetected = resetDetected,
                isAvailable = true,
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
