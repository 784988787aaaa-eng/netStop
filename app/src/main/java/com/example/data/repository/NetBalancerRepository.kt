package com.example.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import com.example.core.model.ActiveNetworkTransport
import com.example.core.model.AppNetworkProfile
import com.example.core.model.AuditEventType
import com.example.core.model.AuditLogItem
import com.example.core.model.NetworkTypeCondition
import com.example.core.model.PolicyPreset
import com.example.core.model.QuotaLimitAction
import com.example.core.model.QuotaPeriod
import com.example.core.model.QuotaRule
import com.example.core.model.RuleDurationMode
import com.example.core.model.TrafficRule
import com.example.core.permissions.PermissionAndCapabilityHelper
import com.example.data.database.AppUsageTotalEntity
import com.example.data.database.AuditEventEntity
import com.example.data.database.NetBalancerDao
import com.example.data.database.QuotaEntity
import com.example.data.database.RuleEntity
import com.example.network.accounting.TrafficAccountingEngine
import com.example.network.attribution.AppAttributionEngine
import com.example.network.vpn.VpnEngineController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * المستودع الرئيسي لإدارة التطبيقات، القواعد، الحصص، الإحصاءات، وسجل الأحداث (Section 3 & 6).
 */
class NetBalancerRepository(
    private val context: Context,
    private val dao: NetBalancerDao,
    val attributionEngine: AppAttributionEngine = AppAttributionEngine(context),
    val accountingEngine: TrafficAccountingEngine = TrafficAccountingEngine()
) {
    val rulesFlow: Flow<List<TrafficRule>> = dao.observeAllRules().map { list ->
        list.map { it.toDomain() }
    }

    val quotasFlow: Flow<List<QuotaRule>> = dao.observeAllQuotas().map { list ->
        list.map { it.toDomain() }
    }

    val auditLogsFlow: Flow<List<AuditLogItem>> = dao.observeRecentAuditEvents().map { list ->
        list.map { it.toDomain() }
    }

    val usageTotalsFlow: Flow<Map<Int, AppUsageTotalEntity>> = dao.observeAllUsageTotals().map { list ->
        list.associateBy { it.uid }
    }

    /**
     * يكتشف جميع تطبيقات المستخدم وتطبيقات النظام المتاحة عبر PackageManager مع احترام قيود رؤية الحزم في أندرويد.
     */
    suspend fun discoverInstalledApps(): List<AppNetworkProfile> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val discoveredMap = LinkedHashMap<String, ApplicationInfo>()

        // 1. التطبيقات المثبتة المرئية عبر getInstalledApplications
        runCatching {
            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (info in installed) {
                discoveredMap[info.packageName] = info
            }
        }

        // 2. التطبيقات القابلة للتشغيل عبر Launcher Intent لضمان شمول جميع تطبيقات المستخدم
        runCatching {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
            for (ri in resolveInfos) {
                val appInfo = ri.activityInfo?.applicationInfo
                if (appInfo != null) {
                    discoveredMap[appInfo.packageName] = appInfo
                }
            }
        }

        // حساب عدد الحزم المشتركة في كل UID
        val uidPackageCounts = discoveredMap.values.groupingBy { it.uid }.eachCount()
        val isVpnActive = VpnEngineController.diagnostics.value.isVpnRunning

        val profiles = mutableListOf<AppNetworkProfile>()
        val seenUids = HashSet<Int>()

        for (appInfo in discoveredMap.values) {
            val uid = appInfo.uid
            // إذا كان هناك أكثر من حزمة تشترك في نفس UID، ندمجها تحت الحزمة الرئيسية مع توضيح عدد الحزم المشتركة
            if (!seenUids.add(uid)) continue

            val label = runCatching { pm.getApplicationLabel(appInfo).toString() }
                .getOrDefault(appInfo.packageName)
            val isSystem = PermissionAndCapabilityHelper.isSystemApplication(appInfo)
            val sharedCount = uidPackageCounts[uid] ?: 1

            attributionEngine.registerKnownIdentity(uid, appInfo.packageName, label)

            val (kernelRx, kernelTx) = attributionEngine.readKernelUidBytes(uid)
            val capability = PermissionAndCapabilityHelper.evaluateAppCapability(
                uid = uid,
                isSystemApp = isSystem,
                sharedUidPackageCount = sharedCount,
                isVpnActive = isVpnActive
            )

            profiles.add(
                AppNetworkProfile(
                    uid = uid,
                    packageName = appInfo.packageName,
                    appName = label,
                    isSystemApp = isSystem,
                    sharedUidCount = sharedCount,
                    todayDownloadBytes = kernelRx,
                    todayUploadBytes = kernelTx,
                    weekTotalBytes = kernelRx + kernelTx,
                    monthTotalBytes = kernelRx + kernelTx,
                    wifiBytes = kernelRx + kernelTx,
                    cellularBytes = 0L,
                    capabilityStatus = capability
                )
            )
        }

        // التأكد من وجود تطبيقنا نفسه وحزم النظام الشبكية الأساسية للشفافية الكاملة
        if (profiles.none { it.uid == Process.myUid() }) {
            val myUid = Process.myUid()
            val (rx, tx) = attributionEngine.readKernelUidBytes(myUid)
            profiles.add(
                0,
                AppNetworkProfile(
                    uid = myUid,
                    packageName = context.packageName,
                    appName = "NetBalancer (هذا التطبيق)",
                    isSystemApp = false,
                    todayDownloadBytes = rx,
                    todayUploadBytes = tx,
                    weekTotalBytes = rx + tx,
                    monthTotalBytes = rx + tx,
                    wifiBytes = rx + tx
                )
            )
        }

        profiles.sortedWith(
            compareBy<AppNetworkProfile> { it.isSystemApp }
                .thenByDescending { it.totalTodayBytes }
                .thenBy { it.appName }
        )
    }

    /**
     * يضيف أو يعدل قاعدة تحكم لتطبيق، ويطبقها فورًا في محرك VPN و TokenBucketRateLimiter، ويسجل الحدث في السجل.
     */
    suspend fun saveRule(
        uid: Int,
        packageName: String,
        appName: String,
        preset: PolicyPreset,
        customDownBps: Long? = null,
        customUpBps: Long? = null,
        networkCondition: NetworkTypeCondition = NetworkTypeCondition.ANY,
        durationMode: RuleDurationMode = RuleDurationMode.PERMANENT,
        priority: Int = 60,
        notes: String = "",
        existingRuleId: Long = 0L
    ): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val downLimit = when (preset) {
            PolicyPreset.CUSTOM -> (customDownBps ?: preset.defaultDownloadBytesPerSec).coerceAtLeast(0L)
            PolicyPreset.BLOCK -> -1L
            else -> preset.defaultDownloadBytesPerSec
        }
        val upLimit = when (preset) {
            PolicyPreset.CUSTOM -> (customUpBps ?: preset.defaultUploadBytesPerSec).coerceAtLeast(0L)
            PolicyPreset.BLOCK -> -1L
            else -> preset.defaultUploadBytesPerSec
        }
        val expiresAt = durationMode.durationMillis?.let { now + it }

        val rule = TrafficRule(
            id = existingRuleId,
            uid = uid,
            packageName = packageName,
            appName = appName,
            preset = preset,
            maxDownloadBytesPerSec = downLimit,
            maxUploadBytesPerSec = upLimit,
            networkCondition = networkCondition,
            durationMode = durationMode,
            expiresAtEpochMillis = expiresAt,
            priority = priority.coerceIn(1, 100),
            isEnabled = true,
            notes = notes,
            updatedAtMillis = now
        )

        val savedId = dao.upsertRule(RuleEntity.fromDomain(rule))
        syncRulesWithEngine()

        logAuditEvent(
            AuditLogItem(
                eventType = AuditEventType.RULE_APPLIED,
                uid = uid,
                packageName = packageName,
                appName = appName,
                summaryAr = "تطبيق سياسة «${preset.titleAr}» على $appName (${networkCondition.titleAr} • أولوية $priority)",
                technicalDetails = "تنزيل=${downLimit}B/s, رفع=${upLimit}B/s, المدة=${durationMode.titleAr}"
            )
        )
        savedId
    }

    suspend fun toggleRuleEnabled(rule: TrafficRule, enabled: Boolean) = withContext(Dispatchers.IO) {
        dao.upsertRule(RuleEntity.fromDomain(rule.copy(isEnabled = enabled, updatedAtMillis = System.currentTimeMillis())))
        syncRulesWithEngine()
        logAuditEvent(
            AuditLogItem(
                eventType = AuditEventType.RULE_APPLIED,
                uid = rule.uid,
                packageName = rule.packageName,
                appName = rule.appName,
                summaryAr = if (enabled) "تفعيل قاعدة ${rule.preset.titleAr} لتطبيق ${rule.appName}" else "تعطيل قاعدة ${rule.preset.titleAr} لتطبيق ${rule.appName}"
            )
        )
    }

    suspend fun deleteRule(rule: TrafficRule) = withContext(Dispatchers.IO) {
        dao.deleteRuleById(rule.id)
        syncRulesWithEngine()
        logAuditEvent(
            AuditLogItem(
                eventType = AuditEventType.RULE_APPLIED,
                uid = rule.uid,
                packageName = rule.packageName,
                appName = rule.appName,
                summaryAr = "حذف قاعدة التحكم (${rule.preset.titleAr}) الخاصة بـ ${rule.appName}"
            )
        )
    }

    suspend fun saveQuota(
        uid: Int,
        packageName: String,
        appName: String,
        period: QuotaPeriod,
        limitBytes: Long,
        warningPercent: Int = 80,
        actionOnLimit: QuotaLimitAction = QuotaLimitAction.THROTTLE_LOW,
        networkCondition: NetworkTypeCondition = NetworkTypeCondition.ANY,
        customDurationDays: Int = 14,
        existingId: Long = 0L,
        currentUsedBytes: Long = 0L
    ): Long = withContext(Dispatchers.IO) {
        val quota = QuotaRule(
            id = existingId,
            uid = uid,
            packageName = packageName,
            appName = appName,
            period = period,
            limitBytes = limitBytes.coerceAtLeast(1024L),
            usedBytes = currentUsedBytes.coerceAtLeast(0L),
            warningPercent = warningPercent.coerceIn(50, 99),
            actionOnLimit = actionOnLimit,
            networkCondition = networkCondition,
            periodStartMillis = System.currentTimeMillis(),
            customDurationDays = customDurationDays.coerceIn(1, 365),
            isEnabled = true
        )
        val id = dao.upsertQuota(QuotaEntity.fromDomain(quota))
        syncRulesWithEngine()
        logAuditEvent(
            AuditLogItem(
                eventType = AuditEventType.RULE_APPLIED,
                uid = uid,
                packageName = packageName,
                appName = appName,
                summaryAr = "حفظ ${period.titleAr} لتطبيق $appName مع إجراء: ${actionOnLimit.titleAr}"
            )
        )
        id
    }

    suspend fun resetQuotaCounter(quota: QuotaRule) = withContext(Dispatchers.IO) {
        val reset = quota.copy(
            usedBytes = 0L,
            periodStartMillis = System.currentTimeMillis(),
            warningTriggered = false,
            limitTriggered = false
        )
        dao.upsertQuota(QuotaEntity.fromDomain(reset))
        syncRulesWithEngine()
        logAuditEvent(
            AuditLogItem(
                eventType = AuditEventType.RULE_APPLIED,
                uid = quota.uid,
                packageName = quota.packageName,
                appName = quota.appName,
                summaryAr = "إعادة تعيين عداد ${quota.period.titleAr} لتطبيق ${quota.appName} إلى الصفر"
            )
        )
    }

    suspend fun deleteQuota(quota: QuotaRule) = withContext(Dispatchers.IO) {
        dao.deleteQuotaById(quota.id)
        syncRulesWithEngine()
    }

    /**
     * يزامن القواعد والحصص الفعالة مع محرك VPN و TokenBucketRateLimiter في الخلفية.
     */
    suspend fun syncRulesWithEngine() = withContext(Dispatchers.IO) {
        val allRules = dao.getAllRulesOnce().map { it.toDomain() }
        val allQuotas = dao.getAllQuotasOnce().map { it.toDomain() }
        val transport = PermissionAndCapabilityHelper.detectActiveNetworkTransport(context)
        val affectedUids = (allRules.map { it.uid } + allQuotas.map { it.uid }).filter { it >= 0 }.toSet()

        val blockedPackages = mutableSetOf<String>()
        for (uid in affectedUids) {
            val effective = VpnEngineController.policyEngine.evaluateForUid(
                uid = uid,
                rules = allRules,
                quotas = allQuotas,
                activeTransport = transport
            )
            VpnEngineController.rateLimiter.configureUidLimits(
                uid = uid,
                maxDownloadBytesPerSec = effective.maxDownloadBytesPerSec,
                maxUploadBytesPerSec = effective.maxUploadBytesPerSec
            )
            if (effective.isBlocked) {
                val pkg = allRules.firstOrNull { it.uid == uid }?.packageName
                    ?: allQuotas.firstOrNull { it.uid == uid }?.packageName
                if (!pkg.isNullOrBlank()) {
                    blockedPackages.add(pkg)
                }
            }
            if (effective.conflictResolvedExplanationAr != null) {
                val appName = allRules.firstOrNull { it.uid == uid }?.appName ?: "UID $uid"
                logAuditEvent(
                    AuditLogItem(
                        eventType = AuditEventType.CONFLICT_RESOLVED,
                        uid = uid,
                        appName = appName,
                        summaryAr = effective.conflictResolvedExplanationAr
                    )
                )
            }
        }
        VpnEngineController.updateBlockedPackages(blockedPackages)
    }

    suspend fun saveUsageSnapshots(profiles: List<AppNetworkProfile>) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val entities = profiles.map { p ->
            AppUsageTotalEntity(
                uid = p.uid,
                packageName = p.packageName,
                appName = p.appName,
                todayDownloadBytes = p.todayDownloadBytes,
                todayUploadBytes = p.todayUploadBytes,
                weekTotalBytes = p.weekTotalBytes,
                monthTotalBytes = p.monthTotalBytes,
                wifiBytes = p.wifiBytes,
                cellularBytes = p.cellularBytes,
                blockedAttemptsCount = p.blockedAttemptsCount,
                lastUpdatedMillis = now
            )
        }
        dao.upsertUsageTotals(entities)
    }

    suspend fun logAuditEvent(item: AuditLogItem) = withContext(Dispatchers.IO) {
        val transport = PermissionAndCapabilityHelper.detectActiveNetworkTransport(context)
        dao.insertAuditEvent(AuditEventEntity.fromDomain(item.copy(networkTransport = transport)))
    }

    suspend fun clearAllStatisticsAndLogs() = withContext(Dispatchers.IO) {
        dao.clearAllUsageTotals()
        dao.clearAllAuditEvents()
        accountingEngine.resetAllSessionCounters()
        VpnEngineController.flowTracker.clearAll()
        logAuditEvent(
            AuditLogItem(
                eventType = AuditEventType.ENGINE_STARTED,
                summaryAr = "تم مسح جميع الإحصاءات التراكمية وسجل الأحداث بناءً على طلب المستخدم"
            )
        )
    }

    suspend fun pruneOldLogs(retentionDays: Int) = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - (retentionDays.coerceAtLeast(1) * 24L * 3_600_000L)
        dao.pruneAuditEventsOlderThan(cutoff)
    }
}
