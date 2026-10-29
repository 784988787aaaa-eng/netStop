package com.netlimiterpro.ui

import com.netlimiterpro.data.source.InstalledApp
import com.netlimiterpro.data.source.InstalledAppKind
import com.netlimiterpro.domain.model.AppRule
import com.netlimiterpro.domain.model.NetworkPolicy

enum class AppFilter {
    ALL,
    USER,
    SYSTEM
}

enum class RuleNetworkTarget { BOTH, WIFI, MOBILE }

data class AppRowState(
    val app: InstalledApp,
    val rule: AppRule,
    val expanded: Boolean = false,
    val customSpeedInput: String = "",
    val targetNetwork: RuleNetworkTarget = RuleNetworkTarget.BOTH
) {
    val isRestricted: Boolean
        get() = rule.wifiPolicy != NetworkPolicy.ALLOW || rule.mobilePolicy != NetworkPolicy.ALLOW
}

data class MainUiState(
    val allApps: List<AppRowState> = emptyList(),
    val visibleApps: List<AppRowState> = emptyList(),
    val searchQuery: String = "",
    val filter: AppFilter = AppFilter.ALL,
    val defaultLimitBytesPerSecond: Long = 250_000L,
    val vpnRunning: Boolean = false,
    val activeModeLabel: String = "",
    val liveRatesByUid: Map<Int, Long> = emptyMap(),
    val isLoading: Boolean = true,
    val message: String? = null
) {
    companion object {
        fun matchesFilter(app: InstalledApp, filter: AppFilter): Boolean = when (filter) {
            AppFilter.ALL -> true
            AppFilter.USER -> app.kind == InstalledAppKind.USER
            AppFilter.SYSTEM -> app.kind == InstalledAppKind.SYSTEM
        }
    }
}
