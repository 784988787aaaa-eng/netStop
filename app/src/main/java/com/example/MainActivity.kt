package com.example

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.core.permissions.PermissionAndCapabilityHelper
import com.example.feature.app_details.AppDetailsScreen
import com.example.feature.applications.ApplicationsScreen
import com.example.feature.dashboard.DashboardScreen
import com.example.feature.history.HistoryScreen
import com.example.feature.quotas.QuotasScreen
import com.example.feature.rules.RulesScreen
import com.example.feature.settings.SettingsScreen
import com.example.ui.NetBalancerDestination
import com.example.ui.NetBalancerViewModel
import com.example.ui.theme.NetBalancerTheme
import com.example.ui.theme.NetPrimaryBlue
import com.example.ui.theme.NetSuccessEmerald

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: NetBalancerViewModel = viewModel()
            val settings by viewModel.settingsState.collectAsStateWithLifecycle()

            NetBalancerTheme(
                darkTheme = settings.isDarkTheme,
                forceRtl = true
            ) {
                NetBalancerAppRoot(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
    }
}

private data class NavTabItem(
    val destination: NetBalancerDestination,
    val labelAr: String,
    val icon: ImageVector,
    val testTag: String
)

private val primaryNavTabs = listOf(
    NavTabItem(NetBalancerDestination.DASHBOARD, "الرئيسية", Icons.Default.Dashboard, "nav_tab_dashboard"),
    NavTabItem(NetBalancerDestination.APPLICATIONS, "التطبيقات", Icons.Default.Apps, "nav_tab_applications"),
    NavTabItem(NetBalancerDestination.RULES, "القواعد", Icons.Default.Rule, "nav_tab_rules"),
    NavTabItem(NetBalancerDestination.QUOTAS, "الحصص", Icons.Default.DataUsage, "nav_tab_quotas"),
    NavTabItem(NetBalancerDestination.HISTORY, "السجل", Icons.Default.History, "nav_tab_history"),
    NavTabItem(NetBalancerDestination.SETTINGS, "الإعدادات", Icons.Default.Settings, "nav_tab_settings")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetBalancerAppRoot(
    viewModel: NetBalancerViewModel
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val engineUiState by viewModel.engineUiState.collectAsStateWithLifecycle()
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    val rules by viewModel.rulesState.collectAsStateWithLifecycle()
    val quotas by viewModel.quotasState.collectAsStateWithLifecycle()
    val auditLogs by viewModel.auditLogsState.collectAsStateWithLifecycle()
    val diagnostics by viewModel.engineDiagnostics.collectAsStateWithLifecycle()
    val activeFlows by viewModel.activeFlows.collectAsStateWithLifecycle()

    var proceedToVpnConsent: (() -> Unit)? = null

    // مشغل طلب موافقة خدمة VPN الرسمية في أندرويد
    val vpnConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.startVpnEngine()
        } else {
            viewModel.setConsentRejected()
        }
        viewModel.refreshPermissionsAndCapabilities()
    }

    proceedToVpnConsent = {
        val prepareIntent = viewModel.prepareVpnServiceIntent()
        if (prepareIntent != null) {
            viewModel.setAwaitingConsent()
            vpnConsentLauncher.launch(prepareIntent)
        } else {
            viewModel.startVpnEngine()
        }
    }

    // مشغل طلب صلاحية الإشعارات على أندرويد 13+ مع تسلسل غير متداخل
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        viewModel.refreshPermissionsAndCapabilities()
        proceedToVpnConsent?.invoke()
    }

    val handleToggleVpn: () -> Unit = {
        if (engineUiState.canToggle) {
            if (diagnostics.isVpnRunning || engineUiState == com.example.core.model.EngineUiState.RUNNING) {
                viewModel.stopVpnEngine()
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !PermissionAndCapabilityHelper.hasNotificationPermission(context)
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    proceedToVpnConsent?.invoke()
                }
            }
        }
    }

    // دعم زر الرجوع في الشاشات الفرعية والثانوية
    BackHandler(enabled = uiState.currentDestination != NetBalancerDestination.DASHBOARD) {
        viewModel.navigateBack()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWideScreen = maxWidth >= 680.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    ),
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Shield,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "NetBalancer • نت بالانسر",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = uiState.currentDestination.titleAr,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    actions = {
                        val pillColor = when (engineUiState) {
                            com.example.core.model.EngineUiState.RUNNING -> NetSuccessEmerald
                            com.example.core.model.EngineUiState.FAILED, com.example.core.model.EngineUiState.REVOKED_OR_LOST -> MaterialTheme.colorScheme.error
                            com.example.core.model.EngineUiState.STARTING, com.example.core.model.EngineUiState.AWAITING_CONSENT -> NetPrimaryBlue
                            com.example.core.model.EngineUiState.STOPPING -> NetPrimaryBlue
                            com.example.core.model.EngineUiState.STOPPED -> NetPrimaryBlue
                        }
                        Surface(
                            color = pillColor.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.padding(end = 12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(pillColor)
                                )
                                Text(
                                    text = engineUiState.titleAr,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = pillColor
                                )
                            }
                        }
                    }
                )
            },
            bottomBar = {
                if (!isWideScreen) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.testTag("bottom_navigation_bar")
                    ) {
                        primaryNavTabs.forEach { tab ->
                            val selected = uiState.currentDestination == tab.destination ||
                                (uiState.currentDestination == NetBalancerDestination.APP_DETAILS &&
                                    tab.destination == NetBalancerDestination.APPLICATIONS)
                            NavigationBarItem(
                                selected = selected,
                                onClick = { viewModel.navigateTo(tab.destination) },
                                icon = {
                                    Icon(
                                        imageVector = tab.icon,
                                        contentDescription = tab.labelAr
                                    )
                                },
                                label = {
                                    Text(
                                        text = tab.labelAr,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1
                                    )
                                },
                                modifier = Modifier.testTag(tab.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (isWideScreen) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        primaryNavTabs.forEach { tab ->
                            val selected = uiState.currentDestination == tab.destination ||
                                (uiState.currentDestination == NetBalancerDestination.APP_DETAILS &&
                                    tab.destination == NetBalancerDestination.APPLICATIONS)
                            NavigationRailItem(
                                selected = selected,
                                onClick = { viewModel.navigateTo(tab.destination) },
                                icon = { Icon(tab.icon, contentDescription = tab.labelAr) },
                                label = { Text(tab.labelAr, style = MaterialTheme.typography.labelMedium) },
                                modifier = Modifier.testTag(tab.testTag)
                            )
                        }
                    }
                }

                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    // شريط الرسائل والتأكيد الفوري
                    AnimatedVisibility(visible = uiState.statusBannerMessageAr != null) {
                        uiState.statusBannerMessageAr?.let { message ->
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    IconButton(
                                        onClick = { viewModel.clearBannerMessage() },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "إغلاق الرسالة",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    when (uiState.currentDestination) {
                        NetBalancerDestination.DASHBOARD -> {
                            DashboardScreen(
                                diagnostics = diagnostics,
                                engineUiState = engineUiState,
                                totalDownloadBps = uiState.totalDownloadBps,
                                totalUploadBps = uiState.totalUploadBps,
                                todayTotalBytes = uiState.todayTotalBytes,
                                todayWifiBytes = uiState.todayWifiBytes,
                                todayCellularBytes = uiState.todayCellularBytes,
                                activeTransport = uiState.activeTransport,
                                speedHistory = uiState.globalSpeedHistory,
                                apps = uiState.apps,
                                bandwidthUnit = settings.bandwidthUnit,
                                onToggleVpnClick = handleToggleVpn,
                                onRunSelfTestClick = { viewModel.runSelfTestDiagnostics() },
                                onNavigate = { dest -> viewModel.navigateTo(dest) },
                                onOpenAppDetails = { uid -> viewModel.openAppDetails(uid) }
                            )
                        }

                        NetBalancerDestination.APPLICATIONS -> {
                            ApplicationsScreen(
                                apps = uiState.apps,
                                searchQuery = uiState.searchQuery,
                                sortOption = uiState.sortOption,
                                filterOption = uiState.filterOption,
                                showSystemAppsDefault = settings.showSystemApps,
                                bandwidthUnit = settings.bandwidthUnit,
                                onSearchQueryChange = viewModel::updateSearchQuery,
                                onSortOptionChange = viewModel::updateSortOption,
                                onFilterOptionChange = viewModel::updateFilterOption,
                                onOpenAppDetails = { uid -> viewModel.openAppDetails(uid) },
                                onSaveAppRule = { app, preset, downBps, upBps, netCond, durMode, prio, notes ->
                                    viewModel.applyAppPolicyRule(
                                        app = app,
                                        preset = preset,
                                        customDownBps = downBps,
                                        customUpBps = upBps,
                                        networkCondition = netCond,
                                        durationMode = durMode,
                                        priority = prio,
                                        notes = notes
                                    )
                                }
                            )
                        }

                        NetBalancerDestination.APP_DETAILS -> {
                            val selectedApp = uiState.apps.firstOrNull { it.uid == uiState.selectedAppUid }
                            val appFlows = activeFlows.filter { it.uid == uiState.selectedAppUid }
                            val appEvents = auditLogs.filter { it.uid == uiState.selectedAppUid }
                            val appChartHistory = uiState.selectedAppUid?.let { viewModel.getAppSpeedHistory(it) }.orEmpty()

                            AppDetailsScreen(
                                app = selectedApp,
                                speedHistory = appChartHistory,
                                appFlows = appFlows,
                                appEvents = appEvents,
                                bandwidthUnit = settings.bandwidthUnit,
                                onBack = { viewModel.navigateBack() },
                                onSaveRule = { preset, downBps, upBps, netCond, durMode, prio, notes ->
                                    if (selectedApp != null) {
                                        viewModel.applyAppPolicyRule(
                                            app = selectedApp,
                                            preset = preset,
                                            customDownBps = downBps,
                                            customUpBps = upBps,
                                            networkCondition = netCond,
                                            durationMode = durMode,
                                            priority = prio,
                                            notes = notes,
                                            existingRuleId = selectedApp.activeRule?.id ?: 0L
                                        )
                                    }
                                },
                                onSaveQuota = { period, limitBytes, warnPct, action, netCond, customDays ->
                                    if (selectedApp != null) {
                                        viewModel.saveQuotaRule(
                                            uid = selectedApp.uid,
                                            packageName = selectedApp.packageName,
                                            appName = selectedApp.appName,
                                            period = period,
                                            limitBytes = limitBytes,
                                            warningPercent = warnPct,
                                            actionOnLimit = action,
                                            networkCondition = netCond,
                                            customDays = customDays,
                                            existingId = selectedApp.activeQuota?.id ?: 0L,
                                            currentUsedBytes = selectedApp.totalTodayBytes
                                        )
                                    }
                                },
                                onResetQuota = { quota -> viewModel.resetQuota(quota) }
                            )
                        }

                        NetBalancerDestination.RULES -> {
                            RulesScreen(
                                rules = rules,
                                apps = uiState.apps,
                                bandwidthUnit = settings.bandwidthUnit,
                                onSaveRule = { app, preset, downBps, upBps, netCond, durMode, prio, notes, existingId ->
                                    viewModel.applyAppPolicyRule(
                                        app = app,
                                        preset = preset,
                                        customDownBps = downBps,
                                        customUpBps = upBps,
                                        networkCondition = netCond,
                                        durationMode = durMode,
                                        priority = prio,
                                        notes = notes,
                                        existingRuleId = existingId
                                    )
                                },
                                onToggleRule = { rule, enabled -> viewModel.toggleRule(rule, enabled) },
                                onDeleteRule = { rule -> viewModel.deleteRule(rule) }
                            )
                        }

                        NetBalancerDestination.QUOTAS -> {
                            QuotasScreen(
                                quotas = quotas,
                                apps = uiState.apps,
                                onSaveQuota = { uid, pkg, name, period, limitBytes, warnPct, action, netCond, customDays, existingId, currentUsed ->
                                    viewModel.saveQuotaRule(
                                        uid = uid,
                                        packageName = pkg,
                                        appName = name,
                                        period = period,
                                        limitBytes = limitBytes,
                                        warningPercent = warnPct,
                                        actionOnLimit = action,
                                        networkCondition = netCond,
                                        customDays = customDays,
                                        existingId = existingId,
                                        currentUsedBytes = currentUsed
                                    )
                                },
                                onResetQuota = { quota -> viewModel.resetQuota(quota) },
                                onDeleteQuota = { quota -> viewModel.deleteQuota(quota) }
                            )
                        }

                        NetBalancerDestination.HISTORY -> {
                            HistoryScreen(
                                apps = uiState.apps,
                                auditLogs = auditLogs,
                                selectedFilter = uiState.selectedEventFilter,
                                onSelectFilter = viewModel::updateHistoryFilter
                            )
                        }

                        NetBalancerDestination.SETTINGS -> {
                            SettingsScreen(
                                settings = settings,
                                diagnostics = diagnostics,
                                onDarkThemeChange = viewModel::setDarkTheme,
                                onBandwidthUnitChange = viewModel::setBandwidthUnit,
                                onSamplingIntervalChange = viewModel::setSamplingInterval,
                                onNotificationsChange = viewModel::setNotificationsEnabled,
                                onShowSystemAppsChange = viewModel::setShowSystemApps,
                                onRetentionDaysChange = viewModel::setLogRetentionDays,
                                onRequestUsageAccess = {
                                    runCatching {
                                        context.startActivity(PermissionAndCapabilityHelper.createUsageAccessSettingsIntent())
                                    }
                                },
                                onClearAllData = { viewModel.clearAllStatistics() }
                            )
                        }
                    }
                }
            }
        }
    }
}
