package com.example.monitoring

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Process
import androidx.core.app.NotificationCompat
import com.example.R
import com.example.core.common.UnitConverter
import com.example.core.model.ActiveNetworkTransport
import com.example.core.model.AppNetworkProfile
import com.example.core.model.AuditEventType
import com.example.core.model.AuditLogItem
import com.example.core.model.DataAvailabilityStatus
import com.example.core.model.QuotaRule
import com.example.core.model.SpeedSamplePoint
import com.example.core.model.TrafficRule
import com.example.core.permissions.PermissionAndCapabilityHelper
import com.example.data.database.AppUsageTotalEntity
import com.example.data.repository.NetBalancerRepository
import com.example.network.vpn.VpnEngineController
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/**
 * منسق المراقبة الدورية وتجميع الإحصاءات وفحص الحصص (Monitoring & Aggregation Layer - القسم 14 و 15).
 * يعمل بالكامل خارج خيط الواجهة (Background Coroutine) ويحدّث القراءات كل 500-1000 مللي ثانية.
 *
 * يعالج قيد Android 10+ (API 29+) الذي يحجب `TrafficStats.getUidRxBytes(otherUid)` عبر:
 * 1. قراءة `NetworkStatsManager` لكل UID عند توفر الصلاحية.
 * 2. قراءة `TrafficStats.getTotalRxBytes()` / `getTotalTxBytes()` وعدادات واجهات النواة التي تعمل لحظيًا
 *    في جميع إصدارات أندرويد، وإسناد حركة الشبكة الخارجية الفعلية إلى المتصفحات والتطبيقات النشطة غير المحظورة.
 * 3. منع مضاعفة الحساب عند الحفظ الدوري في قاعدة بيانات Room.
 */
class TrafficMonitorCoordinator(
    private val context: Context,
    private val repository: NetBalancerRepository
) {
    companion object {
        private const val QUOTA_ALERT_CHANNEL_ID = "netbalancer_quota_alerts"
        private const val MAX_CHART_SAMPLES = 40
    }

    private var lastGlobalRxBytes: Long = -1L
    private var lastGlobalTxBytes: Long = -1L
    private var lastGlobalSampleMillis: Long = 0L
    private var lastTransport: ActiveNetworkTransport? = null

    private val sessionBaselinePersisted = ConcurrentHashMap<Int, AppUsageTotalEntity>()
    private var sessionDayOfYear: Int = -1

    private val globalSpeedHistory = ArrayDeque<SpeedSamplePoint>(MAX_CHART_SAMPLES)
    private val perAppSpeedHistory = HashMap<Int, ArrayDeque<SpeedSamplePoint>>()

    init {
        createQuotaAlertChannel()
    }

    data class MonitoringCycleOutput(
        val updatedApps: List<AppNetworkProfile>,
        val totalDownloadBps: Long,
        val totalUploadBps: Long,
        val attributedAppsDownloadBps: Long,
        val attributedAppsUploadBps: Long,
        val unattributedDownloadBps: Long,
        val unattributedUploadBps: Long,
        val todayTotalBytes: Long,
        val todayWifiBytes: Long,
        val todayCellularBytes: Long,
        val activeTransport: ActiveNetworkTransport,
        val speedHistory: List<SpeedSamplePoint>,
        val sampleTimestampMillis: Long,
        val isKernelTelemetryAvailable: Boolean
    )

    fun resetCoordinatorBaselines() {
        val global = repository.attributionEngine.readKernelTotalTelemetry()
        lastGlobalRxBytes = global.rxBytes
        lastGlobalTxBytes = global.txBytes
        sessionBaselinePersisted.clear()
        VpnEngineController.clearVpnUidCounters()
    }

    suspend fun sampleCycle(
        baseApps: List<AppNetworkProfile>,
        rules: List<TrafficRule>,
        quotas: List<QuotaRule>,
        persistedTotals: Map<Int, AppUsageTotalEntity>,
        notificationsEnabled: Boolean,
        nowMillis: Long = System.currentTimeMillis()
    ): MonitoringCycleOutput {
        val currentDay = Calendar.getInstance().apply { timeInMillis = nowMillis }.get(Calendar.DAY_OF_YEAR)
        if (sessionDayOfYear != currentDay) {
            sessionDayOfYear = currentDay
            sessionBaselinePersisted.clear()
        }
        for ((uid, entity) in persistedTotals) {
            if (isSameCalendarDay(entity.lastUpdatedMillis, nowMillis)) {
                sessionBaselinePersisted.putIfAbsent(uid, entity)
            }
        }

        val transport = PermissionAndCapabilityHelper.detectActiveNetworkTransport(context)
        if (lastTransport != null && lastTransport != transport) {
            repository.logAuditEvent(
                AuditLogItem(
                    eventType = AuditEventType.NETWORK_CHANGED,
                    summaryAr = "تغير اتصال الشبكة من «${lastTransport?.titleAr}» إلى «${transport.titleAr}»",
                    networkTransport = transport
                )
            )
            repository.syncRulesWithEngine()
        }
        lastTransport = transport

        val diag = VpnEngineController.diagnostics.value
        val isVpnRunning = diag.isVpnRunning
        val failedPkgs = diag.failedRulePackages

        // 1. قراءة عدادات النواة الكلية للجهاز (تعمل لحظيًا لإجمالي حركة الجهاز دون خلطها بـ UID عشوائي)
        val globalTelemetry = repository.attributionEngine.readKernelTotalTelemetry(nowMillis)
        val globalRx = globalTelemetry.rxBytes
        val globalTx = globalTelemetry.txBytes
        var globalDownBps = 0L
        var globalUpBps = 0L

        if (globalTelemetry.isAvailable && lastGlobalRxBytes >= 0L && nowMillis > lastGlobalSampleMillis) {
            val dtMillis = (nowMillis - lastGlobalSampleMillis).coerceAtLeast(1L)
            val dGlobalRx = if (globalRx < lastGlobalRxBytes) globalRx else (globalRx - lastGlobalRxBytes)
            val dGlobalTx = if (globalTx < lastGlobalTxBytes) globalTx else (globalTx - lastGlobalTxBytes)
            globalDownBps = (dGlobalRx * 1000L) / dtMillis
            globalUpBps = (dGlobalTx * 1000L) / dtMillis
        }
        if (globalTelemetry.isAvailable) {
            lastGlobalRxBytes = globalRx
            lastGlobalTxBytes = globalTx
            lastGlobalSampleMillis = nowMillis
        }

        // 2. قراءة العدادات المباشرة الموثقة لكل تطبيق بصورة مستقلة تماماً (دون أي نبضات اصطناعية أو تخمين)
        var sumAppsDownBps = 0L
        var sumAppsUpBps = 0L
        var totalDeltaBytesAllApps = 0L
        val allRulesByUid = rules.groupBy { it.uid }
        val activeRulesByUid = rules.filter { it.isEnabled && !it.isExpired(nowMillis) }.groupBy { it.uid }
        val quotasByUid = quotas.filter { it.isEnabled }.groupBy { it.uid }

        val updatedApps = baseApps.map { app ->
            val vpnPair = VpnEngineController.getVpnUidBytes(app.uid)
            val direct = repository.attributionEngine.readKernelUidTelemetry(
                uid = app.uid,
                nowMillis = nowMillis,
                vpnUidBytes = vpnPair
            )

            val snap = repository.accountingEngine.recordSample(
                uid = app.uid,
                currentKernelRxBytes = direct.rxBytes,
                currentKernelTxBytes = direct.txBytes,
                activeTransport = transport,
                nowMillis = nowMillis,
                includeInitialBaselineInSession = true,
                isCounterAvailable = direct.isAvailable
            )

            val effectivePolicy = VpnEngineController.policyEngine.evaluateForUid(
                uid = app.uid,
                rules = rules,
                quotas = quotas,
                activeTransport = transport,
                nowMillis = nowMillis
            )

            val isBlockedNow = isVpnRunning && effectivePolicy.isBlocked
            val isCounterAvailable = snap.isAvailable && direct.isAvailable

            val effectiveDownBps = when {
                !isCounterAvailable -> 0L
                isBlockedNow -> 0L
                else -> snap.downloadBytesPerSec.coerceAtLeast(0L)
            }
            val effectiveUpBps = when {
                !isCounterAvailable -> 0L
                isBlockedNow -> 0L
                else -> snap.uploadBytesPerSec.coerceAtLeast(0L)
            }

            if (isCounterAvailable) {
                sumAppsDownBps += effectiveDownBps
                sumAppsUpBps += effectiveUpBps
            }

            val appDeltaBytes = if (isCounterAvailable) (snap.deltaRxBytes + snap.deltaTxBytes).coerceAtLeast(0L) else 0L
            totalDeltaBytesAllApps += appDeltaBytes

            // تحديث منحنى السرعة الخاص بالتطبيق
            val appHistory = perAppSpeedHistory.getOrPut(app.uid) { ArrayDeque(24) }
            if (appHistory.size >= 24) appHistory.removeFirst()
            appHistory.addLast(SpeedSamplePoint(nowMillis, effectiveDownBps, effectiveUpBps))

            // فحص جميع الحصص المطابقة للتطبيق عند وجود استهلاك إضافي فعلي
            val appQuotas = quotasByUid[app.uid].orEmpty()
            for (appQuota in appQuotas) {
                if (appDeltaBytes > 0L && isQuotaMatchingTransport(appQuota, transport)) {
                    evaluateQuotaAlerts(appQuota, appDeltaBytes, notificationsEnabled, nowMillis)
                }
            }

            val initialSaved = sessionBaselinePersisted[app.uid]
            val latestSaved = persistedTotals[app.uid]
            val isSameDay = initialSaved != null && isSameCalendarDay(initialSaved.lastUpdatedMillis, nowMillis)
            val isSameWeek = initialSaved != null && isWithinLastDays(initialSaved.lastUpdatedMillis, nowMillis, 7)
            val isSameMonth = initialSaved != null && isSameCalendarMonth(initialSaved.lastUpdatedMillis, nowMillis)

            val baseTodayDown = if (isSameDay) initialSaved!!.todayDownloadBytes else 0L
            val baseTodayUp = if (isSameDay) initialSaved!!.todayUploadBytes else 0L
            val baseWeek = if (isSameWeek) initialSaved!!.weekTotalBytes else 0L
            val baseMonth = if (isSameMonth) initialSaved!!.monthTotalBytes else 0L
            val baseWifi = if (isSameDay) initialSaved!!.wifiBytes else 0L
            val baseCell = if (isSameDay) initialSaved!!.cellularBytes else 0L

            val sessionRxSafe = if (isCounterAvailable) snap.sessionRxBytes.coerceAtLeast(0L) else 0L
            val sessionTxSafe = if (isCounterAvailable) snap.sessionTxBytes.coerceAtLeast(0L) else 0L
            val wifiSafe = if (isCounterAvailable) snap.wifiTotalBytes.coerceAtLeast(0L) else 0L
            val cellSafe = if (isCounterAvailable) snap.cellularTotalBytes.coerceAtLeast(0L) else 0L

            val todayDown = maxOf(latestSaved?.todayDownloadBytes ?: 0L, sessionRxSafe, baseTodayDown)
            val todayUp = maxOf(latestSaved?.todayUploadBytes ?: 0L, sessionTxSafe, baseTodayUp)
            val sessionTotal = todayDown + todayUp
            val weekTotal = maxOf(latestSaved?.weekTotalBytes ?: 0L, baseWeek, sessionTotal)
            val monthTotal = maxOf(latestSaved?.monthTotalBytes ?: 0L, baseMonth, sessionTotal)
            val wifiBytes = maxOf(latestSaved?.wifiBytes ?: 0L, wifiSafe, if (transport != ActiveNetworkTransport.CELLULAR) sessionTotal else baseWifi)
            val cellBytes = maxOf(latestSaved?.cellularBytes ?: 0L, cellSafe, if (transport == ActiveNetworkTransport.CELLULAR) sessionTotal else baseCell)

            val capability = PermissionAndCapabilityHelper.evaluateAppCapability(
                uid = app.uid,
                isSystemApp = app.isSystemApp,
                sharedUidPackageCount = app.sharedUidCount,
                isVpnActive = isVpnRunning
            )

            val uidFlows = VpnEngineController.activeFlows.value.filter { it.uid == app.uid }
            val primaryRule = VpnEngineController.policyEngine.sortRulesByPriority(activeRulesByUid[app.uid].orEmpty()).firstOrNull()
                ?: VpnEngineController.policyEngine.sortRulesByPriority(allRulesByUid[app.uid].orEmpty()).firstOrNull()

            val enforcementState = primaryRule?.evaluateEnforcementState(
                isVpnRunning = isVpnRunning,
                activeTransport = transport,
                failedPackages = failedPkgs,
                nowMillis = nowMillis
            )

            val availability = if (isCounterAvailable || (latestSaved != null && (latestSaved.todayDownloadBytes + latestSaved.todayUploadBytes) > 0L)) {
                DataAvailabilityStatus.AVAILABLE
            } else {
                DataAvailabilityStatus.UNAVAILABLE
            }

            app.copy(
                downloadBytesPerSec = effectiveDownBps,
                uploadBytesPerSec = effectiveUpBps,
                sessionDownloadBytes = sessionRxSafe,
                sessionUploadBytes = sessionTxSafe,
                todayDownloadBytes = todayDown,
                todayUploadBytes = todayUp,
                weekTotalBytes = weekTotal,
                monthTotalBytes = monthTotal,
                wifiBytes = wifiBytes,
                cellularBytes = cellBytes,
                activeFlowsCount = uidFlows.size,
                blockedAttemptsCount = uidFlows.count { it.state.name == "BLOCKED" } + (latestSaved?.blockedAttemptsCount ?: 0),
                capabilityStatus = capability,
                dataAvailability = availability,
                activeRule = primaryRule,
                activeRuleEnforcementState = enforcementState,
                activeQuota = appQuotas.firstOrNull(),
                lastActiveTimestamp = if (isCounterAvailable && (effectiveDownBps > 0 || effectiveUpBps > 0)) nowMillis else app.lastActiveTimestamp,
                lastSampleTimestampMillis = nowMillis,
                measurementSourceAr = direct.sourceDescriptionAr
            )
        }

        // فحص الحصة العامة للجهاز (uid == -1)
        val globalQuota = quotasByUid[-1]?.firstOrNull()
        if (globalQuota != null && totalDeltaBytesAllApps > 0L && isQuotaMatchingTransport(globalQuota, transport)) {
            evaluateQuotaAlerts(globalQuota, totalDeltaBytesAllApps, notificationsEnabled, nowMillis)
        }

        val finalDownBps = maxOf(globalDownBps, sumAppsDownBps)
        val finalUpBps = maxOf(globalUpBps, sumAppsUpBps)
        val unattributedDownBps = if (globalDownBps > sumAppsDownBps) globalDownBps - sumAppsDownBps else 0L
        val unattributedUpBps = if (globalUpBps > sumAppsUpBps) globalUpBps - sumAppsUpBps else 0L

        if (globalSpeedHistory.size >= MAX_CHART_SAMPLES) {
            globalSpeedHistory.removeFirst()
        }
        globalSpeedHistory.addLast(SpeedSamplePoint(nowMillis, finalDownBps, finalUpBps))

        val sumAppsToday = updatedApps.filter { it.dataAvailability == DataAvailabilityStatus.AVAILABLE }.sumOf { it.totalTodayBytes }
        val todayWifi = updatedApps.filter { it.dataAvailability == DataAvailabilityStatus.AVAILABLE }.sumOf { it.wifiBytes }
        val todayCell = updatedApps.filter { it.dataAvailability == DataAvailabilityStatus.AVAILABLE }.sumOf { it.cellularBytes }

        return MonitoringCycleOutput(
            updatedApps = updatedApps,
            totalDownloadBps = finalDownBps,
            totalUploadBps = finalUpBps,
            attributedAppsDownloadBps = sumAppsDownBps,
            attributedAppsUploadBps = sumAppsUpBps,
            unattributedDownloadBps = unattributedDownBps,
            unattributedUploadBps = unattributedUpBps,
            todayTotalBytes = sumAppsToday,
            todayWifiBytes = todayWifi,
            todayCellularBytes = todayCell,
            activeTransport = transport,
            speedHistory = globalSpeedHistory.toList(),
            sampleTimestampMillis = nowMillis,
            isKernelTelemetryAvailable = globalTelemetry.isAvailable || updatedApps.any { it.dataAvailability == DataAvailabilityStatus.AVAILABLE }
        )
    }

    private fun isQuotaMatchingTransport(quota: QuotaRule, transport: ActiveNetworkTransport): Boolean {
        return when (quota.networkCondition) {
            com.example.core.model.NetworkTypeCondition.ANY -> true
            com.example.core.model.NetworkTypeCondition.WIFI_ONLY -> transport == ActiveNetworkTransport.WIFI || transport == ActiveNetworkTransport.ETHERNET
            com.example.core.model.NetworkTypeCondition.CELLULAR_ONLY -> transport == ActiveNetworkTransport.CELLULAR
        }
    }

    private fun isSameCalendarDay(t1: Long, t2: Long): Boolean {
        if (t1 <= 0L || t2 <= 0L) return false
        val c1 = Calendar.getInstance().apply { timeInMillis = t1 }
        val c2 = Calendar.getInstance().apply { timeInMillis = t2 }
        return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) &&
            c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR)
    }

    private fun isWithinLastDays(t1: Long, t2: Long, days: Int): Boolean {
        if (t1 <= 0L || t2 <= 0L || t2 < t1) return false
        return (t2 - t1) < days * 24L * 3_600_000L
    }

    private fun isSameCalendarMonth(t1: Long, t2: Long): Boolean {
        if (t1 <= 0L || t2 <= 0L) return false
        val c1 = Calendar.getInstance().apply { timeInMillis = t1 }
        val c2 = Calendar.getInstance().apply { timeInMillis = t2 }
        return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) &&
            c1.get(Calendar.MONTH) == c2.get(Calendar.MONTH)
    }

    fun getAppSpeedHistory(uid: Int): List<SpeedSamplePoint> {
        return perAppSpeedHistory[uid]?.toList().orEmpty()
    }

    private suspend fun evaluateQuotaAlerts(
        quota: QuotaRule,
        addedBytes: Long,
        notificationsEnabled: Boolean,
        nowMillis: Long
    ) {
        val eval = repository.accountingEngine.evaluateAndUpdateQuota(quota, addedBytes, nowMillis)
        if (eval.newlyTriggeredWarning || eval.newlyTriggeredLimit || eval.periodWasReset || addedBytes >= 32 * 1024L) {
            repository.updateQuotaProgress(eval.updatedQuota)
        }

        if (eval.newlyTriggeredWarning) {
            val msg = "بلغ استهلاك ${quota.appName} نسبة ${eval.updatedQuota.usagePercent}% من ${quota.period.titleAr} (${UnitConverter.formatVolume(eval.updatedQuota.usedBytes)} / ${UnitConverter.formatVolume(quota.limitBytes)})"
            repository.logAuditEvent(
                AuditLogItem(
                    eventType = AuditEventType.QUOTA_WARNING,
                    uid = quota.uid,
                    packageName = quota.packageName,
                    appName = quota.appName,
                    summaryAr = msg
                )
            )
            if (notificationsEnabled) {
                sendQuotaNotification("تحذير اقتراب من الحصة — ${quota.appName}", msg, quota.id.toInt() + 5000)
            }
        }

        if (eval.newlyTriggeredLimit) {
            val msg = "تجاوز ${quota.appName} الحد الأقصى لـ ${quota.period.titleAr} (${UnitConverter.formatVolume(quota.limitBytes)}) — تم تفعيل: ${quota.actionOnLimit.titleAr}"
            repository.logAuditEvent(
                AuditLogItem(
                    eventType = AuditEventType.QUOTA_EXCEEDED,
                    uid = quota.uid,
                    packageName = quota.packageName,
                    appName = quota.appName,
                    summaryAr = msg
                )
            )
            repository.syncRulesWithEngine()
            if (notificationsEnabled) {
                sendQuotaNotification("تم بلوغ الحد الأقصى للحصة — ${quota.appName}", msg, quota.id.toInt() + 6000)
            }
        }
    }

    private fun createQuotaAlertChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                QUOTA_ALERT_CHANNEL_ID,
                "تنبيهات حصص البيانات",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "إشعارات اقتراب أو بلوغ الحد الأقصى لحصص استهلاك الإنترنت"
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun sendQuotaNotification(title: String, body: String, notificationId: Int) {
        if (!PermissionAndCapabilityHelper.hasNotificationPermission(context)) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val notification = NotificationCompat.Builder(context, QUOTA_ALERT_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(notificationId, notification) }
    }
}
