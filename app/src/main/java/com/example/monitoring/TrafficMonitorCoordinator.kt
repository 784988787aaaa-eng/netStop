package com.example.monitoring

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.R
import com.example.core.common.UnitConverter
import com.example.core.model.ActiveNetworkTransport
import com.example.core.model.AppNetworkProfile
import com.example.core.model.AuditEventType
import com.example.core.model.AuditLogItem
import com.example.core.model.QuotaRule
import com.example.core.model.SpeedSamplePoint
import com.example.core.model.TrafficRule
import com.example.core.permissions.PermissionAndCapabilityHelper
import com.example.data.database.AppUsageTotalEntity
import com.example.data.repository.NetBalancerRepository
import com.example.network.vpn.VpnEngineController

/**
 * منسق المراقبة الدورية وتجميع الإحصاءات وفحص الحصص (Monitoring & Aggregation Layer - Section 3 & 10).
 * يعمل بالكامل خارج خيط الواجهة (Background Coroutine) ويحدّث القراءات كل 500-1000 مللي ثانية.
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

    private val globalSpeedHistory = ArrayDeque<SpeedSamplePoint>(MAX_CHART_SAMPLES)
    private val perAppSpeedHistory = HashMap<Int, ArrayDeque<SpeedSamplePoint>>()

    init {
        createQuotaAlertChannel()
    }

    data class MonitoringCycleOutput(
        val updatedApps: List<AppNetworkProfile>,
        val totalDownloadBps: Long,
        val totalUploadBps: Long,
        val todayTotalBytes: Long,
        val todayWifiBytes: Long,
        val todayCellularBytes: Long,
        val activeTransport: ActiveNetworkTransport,
        val speedHistory: List<SpeedSamplePoint>
    )

    suspend fun sampleCycle(
        baseApps: List<AppNetworkProfile>,
        rules: List<TrafficRule>,
        quotas: List<QuotaRule>,
        persistedTotals: Map<Int, AppUsageTotalEntity>,
        notificationsEnabled: Boolean,
        nowMillis: Long = System.currentTimeMillis()
    ): MonitoringCycleOutput {
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

        val isVpnRunning = VpnEngineController.diagnostics.value.isVpnRunning
        val activeFlowsByUid = VpnEngineController.activeFlows.value.groupBy { it.uid }

        // 1. قراءة سرعة الجهاز الكلية من النواة
        val (globalRx, globalTx) = repository.attributionEngine.readKernelTotalBytes()
        var globalDownBps = 0L
        var globalUpBps = 0L
        if (lastGlobalRxBytes >= 0L && nowMillis > lastGlobalSampleMillis) {
            val dtMillis = (nowMillis - lastGlobalSampleMillis).coerceAtLeast(100L)
            val dRx = (globalRx - lastGlobalRxBytes).coerceAtLeast(0L)
            val dTx = (globalTx - lastGlobalTxBytes).coerceAtLeast(0L)
            globalDownBps = (dRx * 1000L) / dtMillis
            globalUpBps = (dTx * 1000L) / dtMillis
        }
        lastGlobalRxBytes = globalRx
        lastGlobalTxBytes = globalTx
        lastGlobalSampleMillis = nowMillis

        // 2. قراءة وتحديث كل تطبيق
        var sumAppsDownBps = 0L
        var sumAppsUpBps = 0L
        var totalDeltaBytesAllApps = 0L
        val rulesByUid = rules.filter { it.isEnabled && !it.isExpired(nowMillis) }.groupBy { it.uid }
        val quotasByUid = quotas.filter { it.isEnabled }.groupBy { it.uid }

        val updatedApps = baseApps.map { app ->
            val (uidRx, uidTx) = repository.attributionEngine.readKernelUidBytes(app.uid)
            val snap = repository.accountingEngine.recordSample(
                uid = app.uid,
                currentKernelRxBytes = uidRx,
                currentKernelTxBytes = uidTx,
                activeTransport = transport,
                nowMillis = nowMillis
            )

            val effectivePolicy = VpnEngineController.policyEngine.evaluateForUid(
                uid = app.uid,
                rules = rules,
                quotas = quotas,
                activeTransport = transport,
                nowMillis = nowMillis
            )

            // تطبيق السقف الفعلي إذا كان المحرك نشطًا والقاعدة تقيد السرعة أو تحظرها
            val effectiveDownBps = when {
                isVpnRunning && effectivePolicy.isBlocked -> 0L
                isVpnRunning && effectivePolicy.maxDownloadBytesPerSec > 0L ->
                    snap.downloadBytesPerSec.coerceAtMost(effectivePolicy.maxDownloadBytesPerSec)
                else -> snap.downloadBytesPerSec
            }
            val effectiveUpBps = when {
                isVpnRunning && effectivePolicy.isBlocked -> 0L
                isVpnRunning && effectivePolicy.maxUploadBytesPerSec > 0L ->
                    snap.uploadBytesPerSec.coerceAtMost(effectivePolicy.maxUploadBytesPerSec)
                else -> snap.uploadBytesPerSec
            }

            sumAppsDownBps += effectiveDownBps
            sumAppsUpBps += effectiveUpBps
            val appDeltaBytes = snap.deltaRxBytes + snap.deltaTxBytes
            totalDeltaBytesAllApps += appDeltaBytes

            // تحديث منحنى السرعة الخاص بالتطبيق
            val appHistory = perAppSpeedHistory.getOrPut(app.uid) { ArrayDeque(24) }
            if (appHistory.size >= 24) appHistory.removeFirst()
            appHistory.addLast(SpeedSamplePoint(nowMillis, effectiveDownBps, effectiveUpBps))

            // فحص الحصة الخاصة بالتطبيق عند وجود استهلاك إضافي
            val appQuota = quotasByUid[app.uid]?.firstOrNull()
            if (appQuota != null && appDeltaBytes > 0L) {
                evaluateQuotaAlerts(appQuota, appDeltaBytes, notificationsEnabled, nowMillis)
            }

            val saved = persistedTotals[app.uid]
            val todayDown = maxOf(uidRx, (saved?.todayDownloadBytes ?: 0L) + snap.sessionRxBytes)
            val todayUp = maxOf(uidTx, (saved?.todayUploadBytes ?: 0L) + snap.sessionTxBytes)
            val totalToday = todayDown + todayUp
            val wifiBytes = if (transport != ActiveNetworkTransport.CELLULAR) {
                maxOf(totalToday - (saved?.cellularBytes ?: 0L), snap.wifiTotalBytes)
            } else {
                saved?.wifiBytes ?: snap.wifiTotalBytes
            }
            val cellBytes = if (transport == ActiveNetworkTransport.CELLULAR) {
                (saved?.cellularBytes ?: 0L) + snap.cellularTotalBytes
            } else {
                saved?.cellularBytes ?: 0L
            }

            val capability = PermissionAndCapabilityHelper.evaluateAppCapability(
                uid = app.uid,
                isSystemApp = app.isSystemApp,
                sharedUidPackageCount = app.sharedUidCount,
                isVpnActive = isVpnRunning
            )

            val uidFlows = activeFlowsByUid[app.uid].orEmpty()
            val primaryRule = VpnEngineController.policyEngine.sortRulesByPriority(rulesByUid[app.uid].orEmpty()).firstOrNull()

            app.copy(
                downloadBytesPerSec = effectiveDownBps,
                uploadBytesPerSec = effectiveUpBps,
                sessionDownloadBytes = snap.sessionRxBytes,
                sessionUploadBytes = snap.sessionTxBytes,
                todayDownloadBytes = todayDown,
                todayUploadBytes = todayUp,
                weekTotalBytes = maxOf(totalToday, saved?.weekTotalBytes ?: totalToday),
                monthTotalBytes = maxOf(totalToday, saved?.monthTotalBytes ?: totalToday),
                wifiBytes = wifiBytes,
                cellularBytes = cellBytes,
                activeFlowsCount = uidFlows.size,
                blockedAttemptsCount = uidFlows.count { it.state.name == "BLOCKED" } + (saved?.blockedAttemptsCount ?: 0),
                capabilityStatus = capability,
                activeRule = primaryRule,
                activeQuota = appQuota,
                lastActiveTimestamp = if (effectiveDownBps > 0 || effectiveUpBps > 0) nowMillis else app.lastActiveTimestamp
            )
        }

        // فحص الحصة العامة للجهاز (uid == -1)
        val globalQuota = quotasByUid[-1]?.firstOrNull()
        if (globalQuota != null && totalDeltaBytesAllApps > 0L) {
            evaluateQuotaAlerts(globalQuota, totalDeltaBytesAllApps, notificationsEnabled, nowMillis)
        }

        val finalDownBps = maxOf(globalDownBps, sumAppsDownBps)
        val finalUpBps = maxOf(globalUpBps, sumAppsUpBps)

        if (globalSpeedHistory.size >= MAX_CHART_SAMPLES) {
            globalSpeedHistory.removeFirst()
        }
        globalSpeedHistory.addLast(SpeedSamplePoint(nowMillis, finalDownBps, finalUpBps))

        val todayTotal = updatedApps.sumOf { it.totalTodayBytes }
        val todayWifi = updatedApps.sumOf { it.wifiBytes }
        val todayCell = updatedApps.sumOf { it.cellularBytes }

        return MonitoringCycleOutput(
            updatedApps = updatedApps,
            totalDownloadBps = finalDownBps,
            totalUploadBps = finalUpBps,
            todayTotalBytes = todayTotal,
            todayWifiBytes = todayWifi,
            todayCellularBytes = todayCell,
            activeTransport = transport,
            speedHistory = globalSpeedHistory.toList()
        )
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
            repository.saveQuota(
                uid = eval.updatedQuota.uid,
                packageName = eval.updatedQuota.packageName,
                appName = eval.updatedQuota.appName,
                period = eval.updatedQuota.period,
                limitBytes = eval.updatedQuota.limitBytes,
                warningPercent = eval.updatedQuota.warningPercent,
                actionOnLimit = eval.updatedQuota.actionOnLimit,
                networkCondition = eval.updatedQuota.networkCondition,
                customDurationDays = eval.updatedQuota.customDurationDays,
                existingId = eval.updatedQuota.id,
                currentUsedBytes = eval.updatedQuota.usedBytes
            )
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
