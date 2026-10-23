package com.example.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

/**
 * الوجهات الرئيسية والفرعية للتطبيق (القسم الثامن).
 */
enum class NetBalancerDestination(val titleAr: String) {
    DASHBOARD("لوحة التحكم"),
    APPLICATIONS("التطبيقات"),
    APP_DETAILS("تفاصيل التطبيق"),
    RULES("قواعد التحكم"),
    QUOTAS("حصص البيانات"),
    HISTORY("سجل الاستهلاك"),
    SETTINGS("الإعدادات")
}

/**
 * خيارات فرز وتصفية قائمة التطبيقات (القسم السادس والثامن).
 */
enum class AppSortOption(val labelAr: String) {
    TOTAL_USAGE("الأكثر استهلاكًا"),
    DOWNLOAD_SPEED("سرعة التنزيل"),
    UPLOAD_SPEED("سرعة الرفع"),
    APP_NAME("الاسم أبجديًا")
}

enum class AppFilterOption(val labelAr: String) {
    ALL("جميع التطبيقات"),
    USER_APPS("تطبيقات المستخدم"),
    SYSTEM_APPS("تطبيقات النظام"),
    ACTIVE_NOW("النشطة شبكيًا"),
    RESTRICTED_OR_BLOCKED("المحظورة والمقيدة")
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
    val todayTotalBytes: Long = 0L,
    val todayWifiBytes: Long = 0L,
    val todayCellularBytes: Long = 0L,
    val activeTransport: ActiveNetworkTransport = ActiveNetworkTransport.WIFI,
    val globalSpeedHistory: List<SpeedSamplePoint> = emptyList(),
    val selectedEventFilter: AuditEventType? = null,
    val statusBannerMessageAr: String? = null
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

    init {
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
            // تشغيل فحص المسار الذاتي الأولي لإثبات جاهزية المحرك (Section 4)
            VpnEngineController.runEnginePipelineSelfTest(repository.attributionEngine)
            startPeriodicSamplingLoop()
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
                            todayTotalBytes = cycle.todayTotalBytes,
                            todayWifiBytes = cycle.todayWifiBytes,
                            todayCellularBytes = cycle.todayCellularBytes,
                            activeTransport = cycle.activeTransport,
                            globalSpeedHistory = cycle.speedHistory
                        )
                    }

                    snapshotTickCounter++
                    if (snapshotTickCounter >= 15) {
                        snapshotTickCounter = 0
                        repository.saveUsageSnapshots(cycle.updatedApps)
                    }
                }
                delay(settings.samplingIntervalMs.coerceIn(500L, 5000L))
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
        _engineUiState.value = EngineUiState.STOPPED
        _uiState.update { it.copy(statusBannerMessageAr = "تم إلغاء موافقة خدمة VPN من قبل المستخدم") }
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
        _engineUiState.value = EngineUiState.REVOKED_OR_LOST
        _uiState.update { it.copy(statusBannerMessageAr = "تم سحب إذن VPN من إعدادات النظام") }
    }

    fun startVpnEngine() {
        val current = _engineUiState.value
        if (current == EngineUiState.RUNNING) return
        _engineUiState.value = EngineUiState.STARTING
        try {
            NetBalancerVpnService.startService(appContext)
            viewModelScope.launch {
                repository.syncRulesWithEngine()
                repository.logAuditEvent(
                    AuditLogItem(
                        eventType = AuditEventType.ENGINE_STARTED,
                        summaryAr = "تم تشغيل محرك VPN المحلي وتفعيل سياسات الحظر وتحديد السرعة"
                    )
                )
                _uiState.update {
                    it.copy(statusBannerMessageAr = "تم تشغيل محرك الشبكة المحلي بنجاح")
                }
            }
        } catch (e: Exception) {
            setEngineFailed("تعذر إطلاق خدمة VPN: ${e.localizedMessage ?: "خطأ غير معروف"}")
        }
    }

    fun stopVpnEngine() {
        val current = _engineUiState.value
        if (current == EngineUiState.STOPPED || current == EngineUiState.STOPPING) return
        _engineUiState.value = EngineUiState.STOPPING
        try {
            NetBalancerVpnService.stopService(appContext)
            viewModelScope.launch {
                repository.logAuditEvent(
                    AuditLogItem(
                        eventType = AuditEventType.ENGINE_STOPPED,
                        summaryAr = "تم إيقاف محرك VPN المحلي"
                    )
                )
                _uiState.update {
                    it.copy(statusBannerMessageAr = "تم إيقاف محرك الشبكة؛ تستمر المراقبة المحلية للإحصاءات")
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
            if (preset == PolicyPreset.ALLOW && existingRuleId == 0L) {
                // إذا اختار المستخدم "سماح كامل" من القائمة السريعة، نزيل القيود السابقة للتطبيق أو نحفظ قاعدة سماح
                val existingRules = rulesState.value.filter { it.uid == app.uid }
                existingRules.forEach { repository.deleteRule(it) }
                _uiState.update {
                    it.copy(statusBannerMessageAr = "تمت استعادة السماح الكامل لتطبيق ${app.appName}")
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
                    it.copy(statusBannerMessageAr = "تم تطبيق سياسة «${preset.titleAr}» على ${app.appName}")
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
            repository.deleteRule(rule)
            _uiState.update {
                it.copy(statusBannerMessageAr = "تم حذف القاعدة الخاصة بـ ${rule.appName}")
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
                it.copy(statusBannerMessageAr = "تم حفظ ${period.titleAr} لتطبيق $appName")
            }
        }
    }

    fun resetQuota(quota: QuotaRule) {
        viewModelScope.launch {
            repository.resetQuotaCounter(quota)
            _uiState.update {
                it.copy(statusBannerMessageAr = "تمت إعادة تعيين عداد الحصة لـ ${quota.appName}")
            }
        }
    }

    fun deleteQuota(quota: QuotaRule) {
        viewModelScope.launch {
            repository.deleteQuota(quota)
            _uiState.update {
                it.copy(statusBannerMessageAr = "تم حذف الحصة الخاصة بـ ${quota.appName}")
            }
        }
    }

    fun clearAllStatistics() {
        viewModelScope.launch {
            repository.clearAllStatisticsAndLogs()
            _uiState.update {
                it.copy(statusBannerMessageAr = "تم مسح السجل والإحصاءات المحلية بنجاح")
            }
        }
    }

    // تحديث الإعدادات
    fun setDarkTheme(dark: Boolean) = viewModelScope.launch { preferencesRepository.setDarkTheme(dark) }
    fun setBandwidthUnit(unit: BandwidthUnit) = viewModelScope.launch { preferencesRepository.setBandwidthUnit(unit) }
    fun setSamplingInterval(ms: Long) = viewModelScope.launch { preferencesRepository.setSamplingIntervalMs(ms) }
    fun setNotificationsEnabled(enabled: Boolean) = viewModelScope.launch { preferencesRepository.setNotificationsEnabled(enabled) }
    fun setShowSystemApps(show: Boolean) = viewModelScope.launch { preferencesRepository.setShowSystemApps(show) }
    fun setLogRetentionDays(days: Int) = viewModelScope.launch {
        preferencesRepository.setLogRetentionDays(days)
        repository.pruneOldLogs(days)
    }
    fun setAutoRestoreRules(enabled: Boolean) = viewModelScope.launch { preferencesRepository.setAutoRestoreRules(enabled) }
}
