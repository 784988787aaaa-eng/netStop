package com.example.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.i18n.AppLanguage
import com.example.core.i18n.tr
import com.example.core.model.ActiveNetworkTransport
import com.example.core.model.AppNetworkProfile
import com.example.core.model.AuditEventType
import com.example.core.model.AuditLogItem
import com.example.core.model.BandwidthUnit
import com.example.core.model.EngineDiagnostics
import com.example.core.model.EngineUiState
import com.example.core.model.FlowRecord
import com.example.core.model.NetworkTypeCondition
import com.example.core.model.PolicyPreset
import com.example.core.model.QuotaLimitAction
import com.example.core.model.QuotaPeriod
import com.example.core.model.QuotaRule
import com.example.core.model.RuleDurationMode
import com.example.core.model.SpeedSamplePoint
import com.example.core.model.TrafficRule
import com.example.core.permissions.PermissionAndCapabilityHelper
import com.example.data.database.AppUsageTotalEntity
import com.example.data.database.NetBalancerDatabase
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.preferences.UserSettingsState
import com.example.data.repository.NetBalancerRepository
import com.example.monitoring.TrafficMonitorCoordinator
import com.example.network.vpn.NetBalancerVpnService
import com.example.network.vpn.VpnEngineController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * الوجهات الرئيسية والفرعية للتطبيق (القسم 8 و 10).
 */
enum class NetBalancerDestination(
    val titleAr: String,
    val titleEn: String
) {
    DASHBOARD("الرئيسية", "Dashboard"),
    APPLICATIONS("التطبيقات", "Apps"),
    APP_DETAILS("تفاصيل التطبيق", "App Details"),
    RULES("القواعد", "Rules"),
    QUOTAS("الحصص", "Quotas"),
    HISTORY("الإحصاءات والسجل", "Stats & Log"),
    SETTINGS("الإعدادات", "Settings");

    fun title(lang: AppLanguage): String = if (lang == AppLanguage.AR) titleAr else titleEn
}

/**
 * خيارات فرز وتصفية قائمة التطبيقات (القسم 5 و 6).
 */
enum class AppSortOption(
    val labelAr: String,
    val labelEn: String
) {
    TOTAL_USAGE("الأكثر استهلاكًا", "Top Usage"),
    DOWNLOAD_VOLUME("الأكثر تنزيلاً", "Most Download"),
    UPLOAD_VOLUME("الأكثر رفعًا", "Most Upload"),
    DOWNLOAD_SPEED("سرعة التنزيل", "Download Speed"),
    UPLOAD_SPEED("سرعة الرفع", "Upload Speed"),
    RECENT_ACTIVITY("النشاط الحالي", "Active Now"),
    APP_NAME("الاسم أبجديًا", "Name (A–Z)");

    fun label(lang: AppLanguage): String = if (lang == AppLanguage.AR) labelAr else labelEn
}

enum class AppFilterOption(
    val labelAr: String,
    val labelEn: String
) {
    ALL("الكل", "All"),
    USER_APPS("المستخدم", "User Apps"),
    SYSTEM_APPS("النظام", "System"),
    ACTIVE_NOW("نشطة الآن", "Active"),
    BLOCKED_ONLY("محظورة", "Blocked"),
    RATE_LIMITED_ONLY("محددة السرعة", "Limited");

    fun label(lang: AppLanguage): String = if (lang == AppLanguage.AR) labelAr else labelEn
}

data class NetBalancerUiState(
    val currentDestination: NetBalancerDestination = NetBalancerDestination.DASHBOARD,
    val selectedAppUid: Int? = null,
    val isLoadingApps: Boolean = true,
    val apps: List<AppNetworkProfile> = emptyList(),
    val searchQuery: String = "",
    val sortOption: AppSortOption = AppSortOption.TOTAL_USAGE,
    val filterOption: AppFilterOption = AppFilterOption.ALL,
    val totalDownloadBps: Long = 0L,
    val totalUploadBps: Long = 0L,
    val unattributedDownloadBps: Long = 0L,
    val unattributedUploadBps: Long = 0L,
    val todayTotalBytes: Long = 0L,
    val todayWifiBytes: Long = 0L,
    val todayCellularBytes: Long = 0L,
    val activeTransport: ActiveNetworkTransport = ActiveNetworkTransport.WIFI,
    val globalSpeedHistory: List<SpeedSamplePoint> = emptyList(),
    val selectedEventFilter: AuditEventType? = null,
    val statusBannerMessageAr: String? = null,
    val lastSampleTimestampMillis: Long = System.currentTimeMillis(),
    val isKernelTelemetryAvailable: Boolean = true
)

class NetBalancerViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val database = NetBalancerDatabase.getInstance(appContext)
    val repository = NetBalancerRepository(appContext, database.dao())
    val preferencesRepository = AppPreferencesRepository(appContext)
    private val monitorCoordinator = TrafficMonitorCoordinator(appContext, repository)

    private val _uiState = MutableStateFlow(NetBalancerUiState())
    val uiState: StateFlow<NetBalancerUiState> = _uiState.asStateFlow()

    private val _engineUiState = MutableStateFlow(EngineUiState.STOPPED)
    val engineUiState: StateFlow<EngineUiState> = _engineUiState.asStateFlow()

    private val navigationBackStack = ArrayDeque<NetBalancerDestination>()

    val settingsState: StateFlow<UserSettingsState> = preferencesRepository.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = UserSettingsState()
    )

    val rulesState: StateFlow<List<TrafficRule>> = repository.rulesFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val quotasState: StateFlow<List<QuotaRule>> = repository.quotasFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val auditLogsState: StateFlow<List<AuditLogItem>> = repository.auditLogsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val engineDiagnostics: StateFlow<EngineDiagnostics> = VpnEngineController.diagnostics
    val activeFlows: StateFlow<List<FlowRecord>> = VpnEngineController.activeFlows

    private var baseDiscoveredApps: List<AppNetworkProfile> = emptyList()
    private var latestPersistedTotals: Map<Int, AppUsageTotalEntity> = emptyMap()
    private var snapshotTickCounter = 0
    private var lastPausedTimestampMillis: Long = 0L

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == Intent.ACTION_PACKAGE_ADDED ||
                action == Intent.ACTION_PACKAGE_REMOVED ||
                action == Intent.ACTION_PACKAGE_REPLACED
            ) {
                refreshInstalledApps()
            }
        }
    }
    private var isPackageReceiverRegistered = false

    init {
        registerPackageChangeReceiver()

        VpnEngineController.setAuditCallback { item ->
            viewModelScope.launch {
                repository.logAuditEvent(item)
            }
        }

        viewModelScope.launch {
            engineDiagnostics.collect { diag ->
                val current = _engineUiState.value
                if (diag.isVpnRunning) {
                    _engineUiState.value = EngineUiState.RUNNING
                } else if (current == EngineUiState.RUNNING || current == EngineUiState.STOPPING) {
                    _engineUiState.value = EngineUiState.STOPPED
                }
            }
        }

        viewModelScope.launch {
            repository.usageTotalsFlow.collect { map ->
                latestPersistedTotals = map
            }
        }

        viewModelScope.launch {
            refreshPermissionsAndCapabilities()
            baseDiscoveredApps = repository.discoverInstalledApps()
            _uiState.update {
                it.copy(
                    isLoadingApps = false,
                    apps = baseDiscoveredApps
                )
            }
            repository.syncRulesWithEngine()
            VpnEngineController.runEnginePipelineSelfTest(repository.attributionEngine)
            startPeriodicSamplingLoop()
        }
    }

    private fun registerPackageChangeReceiver() {
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(packageChangeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(packageChangeReceiver, filter)
            }
            isPackageReceiverRegistered = true
        }
    }

    fun refreshInstalledApps() {
        viewModelScope.launch {
            val refreshed = repository.discoverInstalledApps()
            baseDiscoveredApps = refreshed
            _uiState.update { it.copy(apps = refreshed) }
            repository.syncRulesWithEngine()
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (isPackageReceiverRegistered) {
            runCatching { appContext.unregisterReceiver(packageChangeReceiver) }
            isPackageReceiverRegistered = false
        }
    }

    private fun startPeriodicSamplingLoop() {
        viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                val settings = settingsState.value
                if (baseDiscoveredApps.isNotEmpty()) {
                    val cycle = monitorCoordinator.sampleCycle(
                        baseApps = baseDiscoveredApps,
                        rules = rulesState.value,
                        quotas = quotasState.value,
                        persistedTotals = latestPersistedTotals,
                        notificationsEnabled = settings.notificationsEnabled
                    )
                    baseDiscoveredApps = cycle.updatedApps

                    _uiState.update { current ->
                        current.copy(
                            apps = cycle.updatedApps,
                            totalDownloadBps = cycle.totalDownloadBps,
                            totalUploadBps = cycle.totalUploadBps,
                            unattributedDownloadBps = cycle.unattributedDownloadBps,
                            unattributedUploadBps = cycle.unattributedUploadBps,
                            todayTotalBytes = cycle.todayTotalBytes,
                            todayWifiBytes = cycle.todayWifiBytes,
                            todayCellularBytes = cycle.todayCellularBytes,
                            activeTransport = cycle.activeTransport,
                            globalSpeedHistory = cycle.speedHistory,
                            lastSampleTimestampMillis = cycle.sampleTimestampMillis,
                            isKernelTelemetryAvailable = cycle.isKernelTelemetryAvailable
                        )
                    }

                    snapshotTickCounter++
                    if (snapshotTickCounter >= 10) {
                        snapshotTickCounter = 0
                        repository.saveUsageSnapshots(cycle.updatedApps)
                    }
                }
                delay(settings.samplingIntervalMs.coerceIn(500L, 5000L))
            }
        }
    }

    fun onAppPaused() {
        lastPausedTimestampMillis = System.currentTimeMillis()
        viewModelScope.launch(Dispatchers.IO) {
            if (baseDiscoveredApps.isNotEmpty()) {
                repository.saveUsageSnapshots(baseDiscoveredApps)
            }
        }
    }

    fun onAppResumed() {
        val now = System.currentTimeMillis()
        lastPausedTimestampMillis = 0L

        viewModelScope.launch(Dispatchers.Default) {
            refreshPermissionsAndCapabilities()
            if (baseDiscoveredApps.isNotEmpty()) {
                val cycle = monitorCoordinator.sampleCycle(
                    baseApps = baseDiscoveredApps,
                    rules = rulesState.value,
                    quotas = quotasState.value,
                    persistedTotals = latestPersistedTotals,
                    notificationsEnabled = settingsState.value.notificationsEnabled,
                    nowMillis = now
                )
                baseDiscoveredApps = cycle.updatedApps
                _uiState.update { current ->
                    current.copy(
                        apps = cycle.updatedApps,
                        totalDownloadBps = cycle.totalDownloadBps,
                        totalUploadBps = cycle.totalUploadBps,
                        unattributedDownloadBps = cycle.unattributedDownloadBps,
                        unattributedUploadBps = cycle.unattributedUploadBps,
                        todayTotalBytes = cycle.todayTotalBytes,
                        todayWifiBytes = cycle.todayWifiBytes,
                        todayCellularBytes = cycle.todayCellularBytes,
                        activeTransport = cycle.activeTransport,
                        globalSpeedHistory = cycle.speedHistory,
                        lastSampleTimestampMillis = cycle.sampleTimestampMillis,
                        isKernelTelemetryAvailable = cycle.isKernelTelemetryAvailable
                    )
                }
                repository.saveUsageSnapshots(cycle.updatedApps)
            }
        }
    }

    fun navigateTo(destination: NetBalancerDestination, selectedUid: Int? = null) {
        val current = _uiState.value.currentDestination
        if (current != destination) {
            navigationBackStack.addLast(current)
        }
        _uiState.update {
            it.copy(
                currentDestination = destination,
                selectedAppUid = selectedUid ?: it.selectedAppUid
            )
        }
    }

    fun openAppDetails(uid: Int) {
        navigateTo(NetBalancerDestination.APP_DETAILS, selectedUid = uid)
    }

    fun navigateBack(): Boolean {
        val prev = navigationBackStack.removeLastOrNull()
        return if (prev != null) {
            _uiState.update { it.copy(currentDestination = prev) }
            true
        } else if (_uiState.value.currentDestination != NetBalancerDestination.DASHBOARD) {
            _uiState.update { it.copy(currentDestination = NetBalancerDestination.DASHBOARD) }
            true
        } else {
            false
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun updateSortOption(option: AppSortOption) {
        _uiState.update { it.copy(sortOption = option) }
    }

    fun updateFilterOption(option: AppFilterOption) {
        _uiState.update { it.copy(filterOption = option) }
    }

    fun updateHistoryFilter(filter: AuditEventType?) {
        _uiState.update { it.copy(selectedEventFilter = filter) }
    }

    fun clearBannerMessage() {
        _uiState.update { it.copy(statusBannerMessageAr = null) }
    }

    fun getAppSpeedHistory(uid: Int): List<SpeedSamplePoint> {
        return monitorCoordinator.getAppSpeedHistory(uid)
    }

    fun refreshPermissionsAndCapabilities() {
        val isVpnPrepared = PermissionAndCapabilityHelper.isVpnPermissionGranted(appContext)
        val hasUsageStats = PermissionAndCapabilityHelper.hasUsageStatsPermission(appContext)
        val transport = PermissionAndCapabilityHelper.detectActiveNetworkTransport(appContext)
        VpnEngineController.updateDiagnostics {
            it.copy(
                isVpnPrepared = isVpnPrepared,
                isUsageAccessGranted = hasUsageStats,
                activeTransport = transport
            )
        }
    }

    fun prepareVpnServiceIntent(): Intent? {
        return PermissionAndCapabilityHelper.prepareVpnIntent(appContext)
    }

    fun setAwaitingConsent() {
        _engineUiState.value = EngineUiState.AWAITING_CONSENT
    }

    fun setConsentRejected() {
        val lang = settingsState.value.appLanguage
        _engineUiState.value = EngineUiState.STOPPED
        _uiState.update {
            it.copy(
                statusBannerMessageAr = tr(
                    lang,
                    "تم إلغاء إذن VPN.",
                    "VPN permission cancelled."
                )
            )
        }
    }

    fun setEngineStarting() {
        _engineUiState.value = EngineUiState.STARTING
    }

    fun setEngineStopping() {
        _engineUiState.value = EngineUiState.STOPPING
    }

    fun setEngineFailed(reason: String) {
        _engineUiState.value = EngineUiState.FAILED
        _uiState.update { it.copy(statusBannerMessageAr = reason) }
    }

    fun setEngineRevoked() {
        val lang = settingsState.value.appLanguage
        _engineUiState.value = EngineUiState.REVOKED_OR_LOST
        _uiState.update {
            it.copy(
                statusBannerMessageAr = tr(
                    lang,
                    "سُحب إذن VPN من النظام.",
                    "VPN permission was revoked."
                )
            )
        }
    }

    fun startVpnEngine() {
        val current = _engineUiState.value
        if (current == EngineUiState.RUNNING) return
        val lang = settingsState.value.appLanguage
        _engineUiState.value = EngineUiState.STARTING
        try {
            NetBalancerVpnService.startService(appContext)
            viewModelScope.launch {
                repository.syncRulesWithEngine()
                repository.logAuditEvent(
                    AuditLogItem(
                        eventType = AuditEventType.ENGINE_STARTED,
                        summaryAr = tr(
                            lang,
                            "تم تشغيل محرك VPN المحلي وتفعيل القواعد.",
                            "Local VPN engine started and rules activated."
                        )
                    )
                )
                _uiState.update {
                    it.copy(
                        statusBannerMessageAr = tr(
                            lang,
                            "تم تشغيل المحرك.",
                            "Engine started."
                        )
                    )
                }
            }
        } catch (e: Exception) {
            setEngineFailed(
                tr(
                    lang,
                    "تعذّر تشغيل المحرك. حاول مجدداً.",
                    "Engine failed to start. Try again."
                )
            )
        }
    }

    fun stopVpnEngine() {
        val current = _engineUiState.value
        if (current == EngineUiState.STOPPED || current == EngineUiState.STOPPING) return
        val lang = settingsState.value.appLanguage
        _engineUiState.value = EngineUiState.STOPPING
        try {
            NetBalancerVpnService.stopService(appContext)
            viewModelScope.launch {
                repository.logAuditEvent(
                    AuditLogItem(
                        eventType = AuditEventType.ENGINE_STOPPED,
                        summaryAr = tr(lang, "تم إيقاف محرك VPN المحلي.", "Local VPN engine stopped.")
                    )
                )
                _uiState.update {
                    it.copy(
                        statusBannerMessageAr = tr(
                            lang,
                            "تم إيقاف المحرك. تستمر المراقبة المحلية.",
                            "Engine stopped. Passive monitoring continues."
                        )
                    )
                }
            }
        } catch (e: Exception) {
            _engineUiState.value = EngineUiState.STOPPED
        }
    }

    fun runSelfTestDiagnostics() {
        viewModelScope.launch(Dispatchers.Default) {
            val diag = VpnEngineController.runEnginePipelineSelfTest(repository.attributionEngine)
            repository.logAuditEvent(
                AuditLogItem(
                    eventType = AuditEventType.ENGINE_STARTED,
                    summaryAr = diag.lastSelfTestResultAr,
                    technicalDetails = "Flows=${diag.activeFlowsCount}/${diag.maxFlowTableCapacity}, Queue=${diag.packetQueueDepth}/${diag.maxPacketQueueCapacity}, Memory=${diag.memoryUsedMb}MB"
                )
            )
            _uiState.update {
                it.copy(statusBannerMessageAr = diag.lastSelfTestResultAr)
            }
        }
    }

    /**
     * تبديل سريع بنقرة واحدة لحظر/سماح Wi-Fi للتطبيق (مستلهم من سرعة تفاعل NetGuard - القسم 5 و 6).
     */
    fun toggleWifiQuickBlock(app: AppNetworkProfile) {
        val currentRule = app.activeRule
        val wifiBlocked = currentRule?.isWifiBlocked() == true
        val cellBlocked = currentRule?.isCellularBlocked() == true

        val newNetCondition: NetworkTypeCondition? = when {
            !wifiBlocked && !cellBlocked -> NetworkTypeCondition.WIFI_ONLY
            !wifiBlocked && cellBlocked -> NetworkTypeCondition.ANY
            wifiBlocked && cellBlocked -> NetworkTypeCondition.CELLULAR_ONLY
            else -> null // فك الحظر بالكامل
        }

        if (newNetCondition == null) {
            applyAppPolicyRule(app = app, preset = PolicyPreset.ALLOW)
        } else {
            applyAppPolicyRule(
                app = app,
                preset = PolicyPreset.BLOCK,
                networkCondition = newNetCondition,
                durationMode = RuleDurationMode.PERMANENT,
                priority = 75,
                notes = "Wi-Fi/Cell quick toggle",
                existingRuleId = currentRule?.id ?: 0L
            )
        }
    }

    /**
     * تبديل سريع بنقرة واحدة لحظر/سماح بيانات الهاتف للتطبيق (مستلهم من سرعة تفاعل NetGuard - القسم 5 و 6).
     */
    fun toggleCellularQuickBlock(app: AppNetworkProfile) {
        val currentRule = app.activeRule
        val wifiBlocked = currentRule?.isWifiBlocked() == true
        val cellBlocked = currentRule?.isCellularBlocked() == true

        val newNetCondition: NetworkTypeCondition? = when {
            !cellBlocked && !wifiBlocked -> NetworkTypeCondition.CELLULAR_ONLY
            !cellBlocked && wifiBlocked -> NetworkTypeCondition.ANY
            cellBlocked && wifiBlocked -> NetworkTypeCondition.WIFI_ONLY
            else -> null // فك الحظر بالكامل
        }

        if (newNetCondition == null) {
            applyAppPolicyRule(app = app, preset = PolicyPreset.ALLOW)
        } else {
            applyAppPolicyRule(
                app = app,
                preset = PolicyPreset.BLOCK,
                networkCondition = newNetCondition,
                durationMode = RuleDurationMode.PERMANENT,
                priority = 75,
                notes = "Wi-Fi/Cell quick toggle",
                existingRuleId = currentRule?.id ?: 0L
            )
        }
    }

    fun applyAppPolicyRule(
        app: AppNetworkProfile,
        preset: PolicyPreset,
        customDownBps: Long? = null,
        customUpBps: Long? = null,
        networkCondition: NetworkTypeCondition = NetworkTypeCondition.ANY,
        durationMode: RuleDurationMode = RuleDurationMode.PERMANENT,
        priority: Int = 60,
        notes: String = "",
        existingRuleId: Long = 0L
    ) {
        viewModelScope.launch {
            val lang = settingsState.value.appLanguage
            if (preset == PolicyPreset.ALLOW && existingRuleId == 0L) {
                val existingRules = rulesState.value.filter { it.uid == app.uid }
                existingRules.forEach { repository.deleteRule(it) }
                _uiState.update {
                    it.copy(
                        statusBannerMessageAr = tr(
                            lang,
                            "تم السماح بالاتصال لـ ${app.appName}.",
                            "Allowed connection for ${app.appName}."
                        )
                    )
                }
            } else {
                repository.saveRule(
                    uid = app.uid,
                    packageName = app.packageName,
                    appName = app.appName,
                    preset = preset,
                    customDownBps = customDownBps,
                    customUpBps = customUpBps,
                    networkCondition = networkCondition,
                    durationMode = durationMode,
                    priority = priority,
                    notes = notes,
                    existingRuleId = existingRuleId
                )
                _uiState.update {
                    it.copy(
                        statusBannerMessageAr = tr(
                            lang,
                            "تم تطبيق «${preset.title(lang)}» على ${app.appName}.",
                            "Applied ${preset.title(lang)} to ${app.appName}."
                        )
                    )
                }
            }
        }
    }

    fun toggleRule(rule: TrafficRule, enabled: Boolean) {
        viewModelScope.launch {
            repository.toggleRuleEnabled(rule, enabled)
        }
    }

    fun deleteRule(rule: TrafficRule) {
        viewModelScope.launch {
            val lang = settingsState.value.appLanguage
            repository.deleteRule(rule)
            _uiState.update {
                it.copy(
                    statusBannerMessageAr = tr(
                        lang,
                        "تم حذف قاعدة ${rule.appName}.",
                        "Deleted rule for ${rule.appName}."
                    )
                )
            }
        }
    }

    fun saveQuotaRule(
        uid: Int,
        packageName: String,
        appName: String,
        period: QuotaPeriod,
        limitBytes: Long,
        warningPercent: Int,
        actionOnLimit: QuotaLimitAction,
        networkCondition: NetworkTypeCondition,
        customDays: Int = 14,
        existingId: Long = 0L,
        currentUsedBytes: Long = 0L
    ) {
        viewModelScope.launch {
            val lang = settingsState.value.appLanguage
            repository.saveQuota(
                uid = uid,
                packageName = packageName,
                appName = appName,
                period = period,
                limitBytes = limitBytes,
                warningPercent = warningPercent,
                actionOnLimit = actionOnLimit,
                networkCondition = networkCondition,
                customDurationDays = customDays,
                existingId = existingId,
                currentUsedBytes = currentUsedBytes
            )
            _uiState.update {
                it.copy(
                    statusBannerMessageAr = tr(
                        lang,
                        "تم حفظ ${period.title(lang)} لـ $appName.",
                        "Saved ${period.title(lang)} for $appName."
                    )
                )
            }
        }
    }

    fun resetQuota(quota: QuotaRule) {
        viewModelScope.launch {
            val lang = settingsState.value.appLanguage
            repository.resetQuotaCounter(quota)
            _uiState.update {
                it.copy(
                    statusBannerMessageAr = tr(
                        lang,
                        "تم تصفير عداد الحصة لـ ${quota.appName}.",
                        "Reset quota counter for ${quota.appName}."
                    )
                )
            }
        }
    }

    fun deleteQuota(quota: QuotaRule) {
        viewModelScope.launch {
            val lang = settingsState.value.appLanguage
            repository.deleteQuota(quota)
            _uiState.update {
                it.copy(
                    statusBannerMessageAr = tr(
                        lang,
                        "تم حذف حصة ${quota.appName}.",
                        "Deleted quota for ${quota.appName}."
                    )
                )
            }
        }
    }

    fun clearAllStatistics() {
        viewModelScope.launch {
            val lang = settingsState.value.appLanguage
            repository.clearAllStatisticsAndLogs()
            monitorCoordinator.resetCoordinatorBaselines()
            baseDiscoveredApps = baseDiscoveredApps.map {
                it.copy(
                    downloadBytesPerSec = 0L,
                    uploadBytesPerSec = 0L,
                    sessionDownloadBytes = 0L,
                    sessionUploadBytes = 0L,
                    todayDownloadBytes = 0L,
                    todayUploadBytes = 0L,
                    weekTotalBytes = 0L,
                    monthTotalBytes = 0L,
                    wifiBytes = 0L,
                    cellularBytes = 0L,
                    blockedAttemptsCount = 0
                )
            }
            _uiState.update {
                it.copy(
                    apps = baseDiscoveredApps,
                    todayTotalBytes = 0L,
                    todayWifiBytes = 0L,
                    todayCellularBytes = 0L,
                    statusBannerMessageAr = tr(
                        lang,
                        "تم مسح السجل والإحصاءات المحلية.",
                        "Cleared local logs and statistics."
                    )
                )
            }
        }
    }

    /**
     * تصدير القواعد والحصص والإعدادات بصيغة JSON للنسخ الاحتياطي (مستلهم من NetGuard).
     */
    fun exportConfigurationJson(): String {
        val root = JSONObject()
        root.put("app", "NetBalancer")
        root.put("version", 1)
        root.put("exportedAtMillis", System.currentTimeMillis())

        val rulesArr = JSONArray()
        rulesState.value.forEach { r ->
            val obj = JSONObject()
            obj.put("uid", r.uid)
            obj.put("packageName", r.packageName)
            obj.put("appName", r.appName)
            obj.put("preset", r.preset.name)
            obj.put("maxDownloadBytesPerSec", r.maxDownloadBytesPerSec)
            obj.put("maxUploadBytesPerSec", r.maxUploadBytesPerSec)
            obj.put("networkCondition", r.networkCondition.name)
            obj.put("durationMode", r.durationMode.name)
            obj.put("priority", r.priority)
            obj.put("isEnabled", r.isEnabled)
            obj.put("notes", r.notes)
            rulesArr.put(obj)
        }
        root.put("rules", rulesArr)

        val quotasArr = JSONArray()
        quotasState.value.forEach { q ->
            val obj = JSONObject()
            obj.put("uid", q.uid)
            obj.put("packageName", q.packageName)
            obj.put("appName", q.appName)
            obj.put("period", q.period.name)
            obj.put("limitBytes", q.limitBytes)
            obj.put("warningPercent", q.warningPercent)
            obj.put("actionOnLimit", q.actionOnLimit.name)
            obj.put("networkCondition", q.networkCondition.name)
            obj.put("customDurationDays", q.customDurationDays)
            quotasArr.put(obj)
        }
        root.put("quotas", quotasArr)
        return root.toString(2)
    }

    /**
     * استيراد القواعد والحصص من نص JSON احتياطي.
     */
    fun importConfigurationJson(jsonText: String) {
        viewModelScope.launch {
            val lang = settingsState.value.appLanguage
            runCatching {
                val root = JSONObject(jsonText)
                val rulesArr = root.optJSONArray("rules")
                var importedCount = 0
                if (rulesArr != null) {
                    for (i in 0 until rulesArr.length()) {
                        val obj = rulesArr.getJSONObject(i)
                        val preset = runCatching { PolicyPreset.valueOf(obj.getString("preset")) }.getOrDefault(PolicyPreset.MEDIUM)
                        val netCond = runCatching { NetworkTypeCondition.valueOf(obj.optString("networkCondition", "ANY")) }.getOrDefault(NetworkTypeCondition.ANY)
                        val durMode = runCatching { RuleDurationMode.valueOf(obj.optString("durationMode", "PERMANENT")) }.getOrDefault(RuleDurationMode.PERMANENT)
                        repository.saveRule(
                            uid = obj.getInt("uid"),
                            packageName = obj.getString("packageName"),
                            appName = obj.optString("appName", obj.getString("packageName")),
                            preset = preset,
                            customDownBps = obj.optLong("maxDownloadBytesPerSec", preset.defaultDownloadBytesPerSec),
                            customUpBps = obj.optLong("maxUploadBytesPerSec", preset.defaultUploadBytesPerSec),
                            networkCondition = netCond,
                            durationMode = durMode,
                            priority = obj.optInt("priority", 60),
                            notes = obj.optString("notes", "")
                        )
                        importedCount++
                    }
                }
                _uiState.update {
                    it.copy(
                        statusBannerMessageAr = tr(
                            lang,
                            "تم استيراد $importedCount قاعدة بنجاح.",
                            "Imported $importedCount rules successfully."
                        )
                    )
                }
            }.onFailure {
                _uiState.update {
                    it.copy(
                        statusBannerMessageAr = tr(
                            lang,
                            "تعذّر قراءة بيانات النسخة الاحتياطية.",
                            "Invalid backup format."
                        )
                    )
                }
            }
        }
    }

    // تحديث الإعدادات
    fun setDarkTheme(dark: Boolean) = viewModelScope.launch { preferencesRepository.setDarkTheme(dark) }
    fun setAppLanguage(language: AppLanguage) = viewModelScope.launch { preferencesRepository.setAppLanguage(language) }
    fun toggleAppLanguage() = viewModelScope.launch {
        val next = if (settingsState.value.appLanguage == AppLanguage.AR) AppLanguage.EN else AppLanguage.AR
        preferencesRepository.setAppLanguage(next)
    }
    fun setBandwidthUnit(unit: BandwidthUnit) = viewModelScope.launch { preferencesRepository.setBandwidthUnit(unit) }
    fun setSamplingInterval(ms: Long) = viewModelScope.launch { preferencesRepository.setSamplingIntervalMs(ms) }
    fun setNotificationsEnabled(enabled: Boolean) = viewModelScope.launch { preferencesRepository.setNotificationsEnabled(enabled) }
    fun setShowSystemApps(show: Boolean) = viewModelScope.launch { preferencesRepository.setShowSystemApps(show) }
    fun setLogRetentionDays(days: Int) = viewModelScope.launch {
        preferencesRepository.setLogRetentionDays(days)
        repository.pruneOldLogs(days)
    }
    fun setAutoRestoreRules(enabled: Boolean) = viewModelScope.launch { preferencesRepository.setAutoRestoreRules(enabled) }
    fun markVpnIntroSeen() = viewModelScope.launch { preferencesRepository.setHasSeenVpnIntroDialog(true) }
}
