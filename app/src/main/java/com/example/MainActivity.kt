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
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.core.i18n.AppLanguage
import com.example.core.i18n.LocalAppLanguage
import com.example.core.i18n.tr
import com.example.core.model.EngineUiState
import com.example.core.permissions.PermissionAndCapabilityHelper
import com.example.feature.app_details.AppDetailsScreen
import com.example.feature.applications.ApplicationsScreen
import com.example.feature.common.VpnConsentExplanationDialog
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
                appLanguage = settings.appLanguage
            ) {
                NetBalancerAppRoot(viewModel = viewModel)
            }
        }
    }
}

private data class NavTabItem(
    val destination: NetBalancerDestination,
    val labelAr: String,
    val labelEn: String,
    val icon: ImageVector,
    val testTag: String
) {
    fun label(lang: AppLanguage): String = if (lang == AppLanguage.AR) labelAr else labelEn
}

/**
 * 5 تبويبات رئيسية واضحة وغير مزدحمة (القسم 8).
 */
private val primaryNavTabs = listOf(
    NavTabItem(NetBalancerDestination.DASHBOARD, "الرئيسية", "Dashboard", Icons.Default.Dashboard, "nav_tab_dashboard"),
    NavTabItem(NetBalancerDestination.APPLICATIONS, "التطبيقات", "Apps", Icons.Default.Apps, "nav_tab_applications"),
    NavTabItem(NetBalancerDestination.HISTORY, "الإحصاءات", "Stats", Icons.Default.DataUsage, "nav_tab_history"),
    NavTabItem(NetBalancerDestination.RULES, "القواعد", "Rules", Icons.Default.Rule, "nav_tab_rules"),
    NavTabItem(NetBalancerDestination.SETTINGS, "الإعدادات", "Settings", Icons.Default.Settings, "nav_tab_settings")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetBalancerAppRoot(
    viewModel: NetBalancerViewModel
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val lang = LocalAppLanguage.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val engineUiState by viewModel.engineUiState.collectAsStateWithLifecycle()
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    val rules by viewModel.rulesState.collectAsStateWithLifecycle()
    val quotas by viewModel.quotasState.collectAsStateWithLifecycle()
    val auditLogs by viewModel.auditLogsState.collectAsStateWithLifecycle()
    val diagnostics by viewModel.engineDiagnostics.collectAsStateWithLifecycle()
    val activeFlows by viewModel.activeFlows.collectAsStateWithLifecycle()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> viewModel.onAppPaused()
                Lifecycle.Event.ON_RESUME -> viewModel.onAppResumed()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var showVpnExplanationDialog by remember { mutableStateOf(false) }
    var launchSystemVpnConsent: (() -> Unit)? = null

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

    launchSystemVpnConsent = {
        val prepareIntent = viewModel.prepareVpnServiceIntent()
        if (prepareIntent != null) {
            viewModel.setAwaitingConsent()
            vpnConsentLauncher.launch(prepareIntent)
        } else {
            viewModel.startVpnEngine()
        }
    }

    val proceedToVpnConsent: () -> Unit = {
        val prepareIntent = viewModel.prepareVpnServiceIntent()
        if (prepareIntent != null && !settings.hasSeenVpnIntroDialog) {
            showVpnExplanationDialog = true
        } else {
            launchSystemVpnConsent?.invoke()
        }
    }

    // مشغل طلب صلاحية الإشعارات على أندرويد 13+
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        viewModel.refreshPermissionsAndCapabilities()
        proceedToVpnConsent()
    }

    val handleToggleVpn: () -> Unit = {
        if (engineUiState.canToggle) {
            if (diagnostics.isVpnRunning || engineUiState == EngineUiState.RUNNING) {
                viewModel.stopVpnEngine()
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !PermissionAndCapabilityHelper.hasNotificationPermission(context)
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    proceedToVpnConsent()
                }
            }
        }
    }

    if (showVpnExplanationDialog) {
        VpnConsentExplanationDialog(
            onConfirm = {
                showVpnExplanationDialog = false
                viewModel.markVpnIntroSeen()
                launchSystemVpnConsent?.invoke()
            },
            onDismiss = {
                showVpnExplanationDialog = false
            }
        )
    }

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
                                    text = "NetBalancer",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = uiState.currentDestination.title(lang),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    actions = {
                        // زر تبديل اللغة الفوري (العربية RTL / English LTR)
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { viewModel.toggleAppLanguage() }
                                .testTag("top_bar_language_toggle")
                        ) {
                            Text(
                                text = if (lang == AppLanguage.AR) "EN" else "عربي",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }

                        // شارة حالة المحرك التفاعلية في الشريط العلوي
                        val pillColor = when (engineUiState) {
                            EngineUiState.RUNNING -> NetSuccessEmerald
                            EngineUiState.FAILED, EngineUiState.REVOKED_OR_LOST -> MaterialTheme.colorScheme.error
                            else -> NetPrimaryBlue
                        }
                        Surface(
                            color = pillColor.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier
                                .padding(end = 12.dp, start = 4.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .clickable { handleToggleVpn() }
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
                                    text = engineUiState.title(lang),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = pillColor,
                                    fontWeight = FontWeight.SemiBold
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
                                    tab.destination == NetBalancerDestination.APPLICATIONS) ||
                                (uiState.currentDestination == NetBalancerDestination.QUOTAS &&
                                    tab.destination == NetBalancerDestination.HISTORY)
                            NavigationBarItem(
                                selected = selected,
                                onClick = { viewModel.navigateTo(tab.destination) },
                                icon = {
                                    Icon(
                                        imageVector = tab.icon,
                                        contentDescription = tab.label(lang)
                                    )
                                },
                                label = {
                                    Text(
                                        text = tab.label(lang),
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
                                    tab.destination == NetBalancerDestination.APPLICATIONS) ||
                                (uiState.currentDestination == NetBalancerDestination.QUOTAS &&
                                    tab.destination == NetBalancerDestination.HISTORY)
                            NavigationRailItem(
                                selected = selected,
                                onClick = { viewModel.navigateTo(tab.destination) },
                                icon = { Icon(tab.icon, contentDescription = tab.label(lang)) },
                                label = { Text(tab.label(lang), style = MaterialTheme.typography.labelMedium) },
                                modifier = Modifier.testTag(tab.testTag)
                            )
                        }
                    }
                }

                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    // شريط رسائل التأكيد الفوري المختصر
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
                                            contentDescription = tr(lang, "إغلاق", "Dismiss"),
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
                                onOpenAppDetails = { uid -> viewModel.openAppDetails(uid) },
                                unattributedDownloadBps = uiState.unattributedDownloadBps,
                                unattributedUploadBps = uiState.unattributedUploadBps,
                                lastUpdatedMillis = uiState.lastSampleTimestampMillis,
                                isKernelTelemetryAvailable = uiState.isKernelTelemetryAvailable,
                                rules = rules,
                                quotas = quotas,
                                auditLogs = auditLogs,
                                onRequestUsageAccess = {
                                    runCatching {
                                        context.startActivity(PermissionAndCapabilityHelper.createUsageAccessSettingsIntent())
                                    }
                                }
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
                                },
                                onToggleWifiQuickBlock = { app -> viewModel.toggleWifiQuickBlock(app) },
                                onToggleCellularQuickBlock = { app -> viewModel.toggleCellularQuickBlock(app) },
                                isVpnRunning = diagnostics.isVpnRunning,
                                enabledRulesCount = rules.count { it.isEnabled },
                                onStartEngineClick = handleToggleVpn,
                                isUsageAccessGranted = diagnostics.isUsageAccessGranted,
                                onRequestUsageAccess = {
                                    runCatching {
                                        context.startActivity(PermissionAndCapabilityHelper.createUsageAccessSettingsIntent())
                                    }
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
                                onResetQuota = { quota -> viewModel.resetQuota(quota) },
                                onToggleWifiQuickBlock = { app -> viewModel.toggleWifiQuickBlock(app) },
                                onToggleCellularQuickBlock = { app -> viewModel.toggleCellularQuickBlock(app) }
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
                                onDeleteRule = { rule -> viewModel.deleteRule(rule) },
                                isVpnRunning = diagnostics.isVpnRunning,
                                activeTransport = uiState.activeTransport,
                                failedRulePackages = diagnostics.failedRulePackages,
                                onStartEngineClick = handleToggleVpn
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
                                onDeleteQuota = { quota -> viewModel.deleteQuota(quota) },
                                onSwitchToHistory = { viewModel.navigateTo(NetBalancerDestination.HISTORY) }
                            )
                        }

                        NetBalancerDestination.HISTORY -> {
                            HistoryScreen(
                                apps = uiState.apps,
                                auditLogs = auditLogs,
                                selectedFilter = uiState.selectedEventFilter,
                                onSelectFilter = viewModel::updateHistoryFilter,
                                quotasCount = quotas.size,
                                onSwitchToQuotas = { viewModel.navigateTo(NetBalancerDestination.QUOTAS) }
                            )
                        }

                        NetBalancerDestination.SETTINGS -> {
                            SettingsScreen(
                                settings = settings,
                                diagnostics = diagnostics,
                                onDarkThemeChange = viewModel::setDarkTheme,
                                onAppLanguageChange = viewModel::setAppLanguage,
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
                                onClearAllData = { viewModel.clearAllStatistics() },
                                onExportBackupJson = { viewModel.exportConfigurationJson() },
                                onImportBackupJson = { json -> viewModel.importConfigurationJson(json) }
                            )
                        }
                    }
                }
            }
        }
    }
}
