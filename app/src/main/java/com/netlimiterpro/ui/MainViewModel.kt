package com.netlimiterpro.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.netlimiterpro.data.repository.RuleRepository
import com.netlimiterpro.data.source.InstalledApp
import com.netlimiterpro.data.source.InstalledAppsProvider
import com.netlimiterpro.domain.model.AppRule
import com.netlimiterpro.domain.model.NetworkPolicy
import com.netlimiterpro.service.TrafficStatsTracker
import com.netlimiterpro.domain.network.TokenBucketThrottler
import com.netlimiterpro.service.NetLimiterVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = RuleRepository(application.applicationContext)
    private val appsProvider = InstalledAppsProvider(application.applicationContext)

    private val _uiState = MutableStateFlow(
        MainUiState(defaultLimitBytesPerSecond = repository.getDefaultLimitBytesPerSecond())
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        refreshApps()
        viewModelScope.launch {
            while (true) {
                val label = NetLimiterVpnService.activeModeLabel
                val running = NetLimiterVpnService.isRunning
                val uids = _uiState.value.allApps.asSequence().map { it.app.uid }.distinct().toList()
                withContext(Dispatchers.IO) { uids.forEach { uid -> TrafficStatsTracker.sampleSystemUid(uid) } }
                val rates = TrafficStatsTracker.snapshots().associate { it.uid to it.bytesPerSecond }
                _uiState.update { state ->
                    state.copy(
                        activeModeLabel = if (running) label else "",
                        vpnRunning = running,
                        liveRatesByUid = rates
                    )
                }
                delay(1_000L)
            }
        }
    }

    fun refreshApps() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, message = null) }
            try {
                val apps = appsProvider.loadInstalledApps()
                val rules = withContext(Dispatchers.IO) { repository.getAllRules() }
                    .associateBy { key(it.uid, it.packageName) }
                val rows = apps.map { app ->
                    AppRowState(
                        app = app,
                        rule = rules[key(app.uid, app.packageName)] ?: defaultRule(app),
                    )
                }
                _uiState.update { old ->
                    val oldRows = old.allApps.associateBy { key(it.app.uid, it.app.packageName) }
                    val mergedRows = rows.map { fresh ->
                        val prior = oldRows[key(fresh.app.uid, fresh.app.packageName)]
                        fresh.copy(
                            expanded = prior?.expanded ?: false,
                            customSpeedInput = prior?.customSpeedInput.orEmpty()
                        )
                    }
                    old.copy(allApps = mergedRows, isLoading = false).recomputeVisible()
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(isLoading = false, message = "load_error") }
            }
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query).recomputeVisible() }
    }

    fun setFilter(filter: AppFilter) {
        _uiState.update { it.copy(filter = filter).recomputeVisible() }
    }

    fun setVpnRunning(running: Boolean) {
        _uiState.update { it.copy(vpnRunning = running) }
    }

    fun toggleExpanded(uid: Int, packageName: String) {
        updateRow(uid, packageName) { it.copy(expanded = !it.expanded) }
    }

    fun setCustomSpeedInput(uid: Int, packageName: String, input: String) {
        if (input.any { !it.isDigit() && it != '.' && it != ',' }) return
        updateRow(uid, packageName) { it.copy(customSpeedInput = input) }
    }

    fun setWifiEnabled(uid: Int, packageName: String, enabled: Boolean) {
        setPolicy(uid, packageName, RuleNetworkTarget.WIFI, if (enabled) NetworkPolicy.THROTTLE else NetworkPolicy.ALLOW)
    }

    fun setMobileDataEnabled(uid: Int, packageName: String, enabled: Boolean) {
        setPolicy(uid, packageName, RuleNetworkTarget.MOBILE, if (enabled) NetworkPolicy.THROTTLE else NetworkPolicy.ALLOW)
    }

    fun setTargetNetwork(uid: Int, packageName: String, target: RuleNetworkTarget) {
        updateRow(uid, packageName) { it.copy(targetNetwork = target) }
    }

    fun setPolicy(uid: Int, packageName: String, target: RuleNetworkTarget, policy: NetworkPolicy) {
        val defaultRate = _uiState.value.defaultLimitBytesPerSecond
        mutateRule(uid, packageName) { rule ->
            when (target) {
                RuleNetworkTarget.BOTH -> rule.copy(
                    wifiPolicy = policy, mobilePolicy = policy,
                    wifiEnabled = policy != NetworkPolicy.ALLOW, mobileDataEnabled = policy != NetworkPolicy.ALLOW,
                    wifiLimitBytesPerSecond = if (policy == NetworkPolicy.THROTTLE && rule.wifiLimitBytesPerSecond == 0L) defaultRate else rule.wifiLimitBytesPerSecond,
                    mobileDataLimitBytesPerSecond = if (policy == NetworkPolicy.THROTTLE && rule.mobileDataLimitBytesPerSecond == 0L) defaultRate else rule.mobileDataLimitBytesPerSecond
                )
                RuleNetworkTarget.WIFI -> rule.copy(
                    wifiPolicy = policy, wifiEnabled = policy != NetworkPolicy.ALLOW,
                    wifiLimitBytesPerSecond = if (policy == NetworkPolicy.THROTTLE && rule.wifiLimitBytesPerSecond == 0L) defaultRate else rule.wifiLimitBytesPerSecond
                )
                RuleNetworkTarget.MOBILE -> rule.copy(
                    mobilePolicy = policy, mobileDataEnabled = policy != NetworkPolicy.ALLOW,
                    mobileDataLimitBytesPerSecond = if (policy == NetworkPolicy.THROTTLE && rule.mobileDataLimitBytesPerSecond == 0L) defaultRate else rule.mobileDataLimitBytesPerSecond
                )
            }
        }
    }

    fun blockAllWifi() = applyBulkPolicy(RuleNetworkTarget.WIFI, NetworkPolicy.BLOCK)
    fun allowAllWifi() = applyBulkPolicy(RuleNetworkTarget.WIFI, NetworkPolicy.ALLOW)
    fun blockAllMobile() = applyBulkPolicy(RuleNetworkTarget.MOBILE, NetworkPolicy.BLOCK)
    fun allowAllMobile() = applyBulkPolicy(RuleNetworkTarget.MOBILE, NetworkPolicy.ALLOW)
    fun throttleAllToDefault() {
        val default = _uiState.value.defaultLimitBytesPerSecond
        applyBulkTransform { rule -> rule.copy(
            wifiPolicy = NetworkPolicy.THROTTLE, mobilePolicy = NetworkPolicy.THROTTLE,
            wifiEnabled = true, mobileDataEnabled = true,
            wifiLimitBytesPerSecond = default, mobileDataLimitBytesPerSecond = default
        ) }
    }

    private fun applyBulkPolicy(target: RuleNetworkTarget, policy: NetworkPolicy) = applyBulkTransform { rule ->
        when (target) {
            RuleNetworkTarget.BOTH -> rule.copy(wifiPolicy = policy, mobilePolicy = policy,
                wifiEnabled = policy != NetworkPolicy.ALLOW, mobileDataEnabled = policy != NetworkPolicy.ALLOW)
            RuleNetworkTarget.WIFI -> rule.copy(wifiPolicy = policy, wifiEnabled = policy != NetworkPolicy.ALLOW)
            RuleNetworkTarget.MOBILE -> rule.copy(mobilePolicy = policy, mobileDataEnabled = policy != NetworkPolicy.ALLOW)
        }
    }

    private fun applyBulkTransform(transform: (AppRule) -> AppRule) {
        val state = _uiState.value
        val targets = state.visibleApps.map { row -> transform(row.rule) }
        _uiState.update { current -> current.copy(allApps = current.allApps.map { row ->
            val replacement = targets.firstOrNull { it.uid == row.app.uid && it.packageName == row.app.packageName }
            if (replacement != null) row.copy(rule = replacement) else row
        }).recomputeVisible() }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.saveRules(targets)
                requestServiceReload()
            } catch (_: Exception) { _uiState.update { it.copy(message = "save_error") } }
        }
    }

    private fun requestServiceReload() {
        if (NetLimiterVpnService.isRunning) getApplication<Application>().sendBroadcast(
            Intent(NetLimiterVpnService.ACTION_RELOAD_RULES).setPackage(getApplication<Application>().packageName)
        )
    }

    fun chooseQuickSpeed(uid: Int, packageName: String, kilobytesPerSecond: Long) {
        require(kilobytesPerSecond > 0L)
        val bytes = TokenBucketThrottler.kilobytesPerSecondToBytes(kilobytesPerSecond.toDouble())
        mutateRule(uid, packageName) {
            when (findRow(uid, packageName)?.targetNetwork ?: RuleNetworkTarget.BOTH) {
                RuleNetworkTarget.BOTH -> it.copy(wifiLimitBytesPerSecond = bytes, mobileDataLimitBytesPerSecond = bytes,
                    wifiPolicy = NetworkPolicy.THROTTLE, mobilePolicy = NetworkPolicy.THROTTLE, wifiEnabled = true, mobileDataEnabled = true)
                RuleNetworkTarget.WIFI -> it.copy(wifiLimitBytesPerSecond = bytes, wifiPolicy = NetworkPolicy.THROTTLE, wifiEnabled = true)
                RuleNetworkTarget.MOBILE -> it.copy(mobileDataLimitBytesPerSecond = bytes, mobilePolicy = NetworkPolicy.THROTTLE, mobileDataEnabled = true)
            }
        }
        updateRow(uid, packageName) { it.copy(customSpeedInput = "") }
    }

    fun applyCustomSpeed(uid: Int, packageName: String) {
        val row = findRow(uid, packageName) ?: return
        val parsed = row.customSpeedInput.trim().replace(',', '.').toDoubleOrNull() ?: return
        if (!parsed.isFinite() || parsed < 0.0) return
        val bytes = runCatching {
            TokenBucketThrottler.kilobytesPerSecondToBytes(parsed)
        }.getOrNull() ?: return
        val targetPolicy = if (parsed == 0.0) NetworkPolicy.BLOCK else NetworkPolicy.THROTTLE
        mutateRule(uid, packageName) {
            when (findRow(uid, packageName)?.targetNetwork ?: RuleNetworkTarget.BOTH) {
                RuleNetworkTarget.BOTH -> it.copy(wifiLimitBytesPerSecond = bytes, mobileDataLimitBytesPerSecond = bytes,
                    wifiPolicy = targetPolicy, mobilePolicy = targetPolicy, wifiEnabled = true, mobileDataEnabled = true)
                RuleNetworkTarget.WIFI -> it.copy(wifiLimitBytesPerSecond = bytes, wifiPolicy = targetPolicy, wifiEnabled = true)
                RuleNetworkTarget.MOBILE -> it.copy(mobileDataLimitBytesPerSecond = bytes, mobilePolicy = targetPolicy, mobileDataEnabled = true)
            }
        }
        updateRow(uid, packageName) { it.copy(customSpeedInput = "") }
    }

    fun setDefaultSpeed(kilobytesPerSecond: Double) {
        if (!kilobytesPerSecond.isFinite() || kilobytesPerSecond < 0.0) return
        val bytes = runCatching {
            TokenBucketThrottler.kilobytesPerSecondToBytes(kilobytesPerSecond)
        }.getOrNull() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.setDefaultLimitBytesPerSecond(bytes)
                if (NetLimiterVpnService.isRunning) {
                    getApplication<Application>().sendBroadcast(
                        Intent(NetLimiterVpnService.ACTION_RELOAD_RULES).setPackage(getApplication<Application>().packageName)
                    )
                }
                _uiState.update { it.copy(defaultLimitBytesPerSecond = bytes, message = null) }
            } catch (_: Exception) {
                _uiState.update { it.copy(message = "save_error") }
            }
        }
    }

    private fun mutateRule(uid: Int, packageName: String, transform: (AppRule) -> AppRule) {
        val current = findRow(uid, packageName) ?: return
        val updatedRule = transform(current.rule)
        updateRow(uid, packageName) { it.copy(rule = updatedRule) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.saveRule(updatedRule)
                requestServiceReload()
            } catch (_: Exception) {
                _uiState.update { it.copy(message = "save_error") }
            }
        }
    }

    private fun updateRow(uid: Int, packageName: String, transform: (AppRowState) -> AppRowState) {
        _uiState.update { state ->
            state.copy(
                allApps = state.allApps.map { row ->
                    if (row.app.uid == uid && row.app.packageName == packageName) transform(row) else row
                }
            ).recomputeVisible()
        }
    }

    private fun findRow(uid: Int, packageName: String): AppRowState? =
        _uiState.value.allApps.firstOrNull { it.app.uid == uid && it.app.packageName == packageName }

    private fun defaultRule(app: InstalledApp) = AppRule(
        uid = app.uid,
        packageName = app.packageName,
        wifiLimitBytesPerSecond = _uiState.value.defaultLimitBytesPerSecond,
        mobileDataLimitBytesPerSecond = _uiState.value.defaultLimitBytesPerSecond
    )

    private fun MainUiState.recomputeVisible(): MainUiState {
        val query = searchQuery.trim().lowercase()
        val filtered = allApps.asSequence()
            .filter { MainUiState.matchesFilter(it.app, filter) }
            .filter { row ->
                query.isEmpty() ||
                    row.app.label.lowercase().contains(query) ||
                    row.app.packageName.lowercase().contains(query) ||
                    row.app.uid.toString().contains(query)
            }
            .sortedWith(
                compareByDescending<AppRowState> { it.isRestricted }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.app.label }
                    .thenBy { it.app.packageName }
            )
            .toList()
        return copy(visibleApps = filtered)
    }

    private fun key(uid: Int, packageName: String) = "$uid:$packageName"
}
