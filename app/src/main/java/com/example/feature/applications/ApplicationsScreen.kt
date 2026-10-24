package com.example.feature.applications

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.common.UnitConverter
import com.example.core.i18n.LocalAppLanguage
import com.example.core.i18n.tr
import com.example.core.model.AppNetworkProfile
import com.example.core.model.BandwidthUnit
import com.example.core.model.DataAvailabilityStatus
import com.example.core.model.NetworkTypeCondition
import com.example.core.model.PolicyPreset
import com.example.core.model.RuleDurationMode
import com.example.core.model.RuleEnforcementState
import com.example.feature.common.AppPackageIcon
import com.example.feature.common.EngineInactiveWarningBanner
import com.example.feature.common.PolicyBadgeChip
import com.example.feature.common.QuickNetworkToggleIconButton
import com.example.feature.common.RuleEditorDialog
import com.example.feature.common.SpeedMetricPill
import com.example.ui.AppFilterOption
import com.example.ui.AppSortOption
import com.example.ui.theme.NetPrimaryBlue
import com.example.ui.theme.NetSuccessEmerald
import com.example.ui.theme.NetWarningAmber

/**
 * الشاشة الثانية: قائمة التطبيقات مع التحكم المباشر بنقرة واحدة (Wi-Fi / Cellular) والدرج القابل للطي لتحديد السرعة (القسم 5 و 6).
 */
@Composable
fun ApplicationsScreen(
    apps: List<AppNetworkProfile>,
    searchQuery: String,
    sortOption: AppSortOption,
    filterOption: AppFilterOption,
    showSystemAppsDefault: Boolean,
    bandwidthUnit: BandwidthUnit,
    onSearchQueryChange: (String) -> Unit,
    onSortOptionChange: (AppSortOption) -> Unit,
    onFilterOptionChange: (AppFilterOption) -> Unit,
    onOpenAppDetails: (Int) -> Unit,
    onSaveAppRule: (
        app: AppNetworkProfile,
        preset: PolicyPreset,
        customDownBps: Long,
        customUpBps: Long,
        networkCondition: NetworkTypeCondition,
        durationMode: RuleDurationMode,
        priority: Int,
        notes: String
    ) -> Unit,
    onToggleWifiQuickBlock: (AppNetworkProfile) -> Unit = {},
    onToggleCellularQuickBlock: (AppNetworkProfile) -> Unit = {},
    isVpnRunning: Boolean = false,
    enabledRulesCount: Int = 0,
    onStartEngineClick: () -> Unit = {},
    isUsageAccessGranted: Boolean = true,
    onRequestUsageAccess: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val lang = LocalAppLanguage.current
    var editingAppForRule by remember { mutableStateOf<AppNetworkProfile?>(null) }
    var expandedAppUid by remember { mutableStateOf<Int?>(null) }

    val filteredAndSortedApps by remember(apps, searchQuery, sortOption, filterOption, showSystemAppsDefault) {
        derivedStateOf {
            apps.filter { app ->
                val matchesSearch = searchQuery.isBlank() ||
                    app.appName.contains(searchQuery, ignoreCase = true) ||
                    app.packageName.contains(searchQuery, ignoreCase = true) ||
                    app.uid.toString().contains(searchQuery)

                val matchesFilter = when (filterOption) {
                    AppFilterOption.ALL -> if (showSystemAppsDefault) true else !app.isSystemApp
                    AppFilterOption.USER_APPS -> !app.isSystemApp
                    AppFilterOption.SYSTEM_APPS -> app.isSystemApp
                    AppFilterOption.ACTIVE_NOW -> app.isCurrentlyActive
                    AppFilterOption.BLOCKED_ONLY -> app.activeRule != null && app.activeRule.isEnabled && app.activeRule.isFullBlock()
                    AppFilterOption.RATE_LIMITED_ONLY -> app.activeRule != null && app.activeRule.isEnabled && app.activeRule.isRateLimited()
                }
                matchesSearch && matchesFilter
            }.let { list ->
                when (sortOption) {
                    AppSortOption.TOTAL_USAGE -> list.sortedByDescending {
                        if (it.dataAvailability == DataAvailabilityStatus.AVAILABLE) it.totalTodayBytes else -1L
                    }
                    AppSortOption.DOWNLOAD_VOLUME -> list.sortedByDescending {
                        if (it.dataAvailability == DataAvailabilityStatus.AVAILABLE) it.todayDownloadBytes else -1L
                    }
                    AppSortOption.UPLOAD_VOLUME -> list.sortedByDescending {
                        if (it.dataAvailability == DataAvailabilityStatus.AVAILABLE) it.todayUploadBytes else -1L
                    }
                    AppSortOption.DOWNLOAD_SPEED -> list.sortedByDescending {
                        if (it.dataAvailability == DataAvailabilityStatus.AVAILABLE) it.downloadBytesPerSec else -1L
                    }
                    AppSortOption.UPLOAD_SPEED -> list.sortedByDescending {
                        if (it.dataAvailability == DataAvailabilityStatus.AVAILABLE) it.uploadBytesPerSec else -1L
                    }
                    AppSortOption.RECENT_ACTIVITY -> list.sortedWith(
                        compareByDescending<AppNetworkProfile> { it.isCurrentlyActive }
                            .thenByDescending {
                                if (it.dataAvailability == DataAvailabilityStatus.AVAILABLE) {
                                    it.downloadBytesPerSec + it.uploadBytesPerSec
                                } else -1L
                            }
                            .thenByDescending {
                                if (it.dataAvailability == DataAvailabilityStatus.AVAILABLE) it.totalTodayBytes else -1L
                            }
                    )
                    AppSortOption.APP_NAME -> list.sortedBy { it.appName.lowercase() }
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("applications_screen")
    ) {
        // شريط البحث والتصفية والفرز المدمج
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            EngineInactiveWarningBanner(
                isVpnRunning = isVpnRunning,
                enabledRulesCount = enabledRulesCount,
                onStartEngineClick = onStartEngineClick
            )

            if (!isUsageAccessGranted) {
                Surface(
                    color = NetWarningAmber.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = tr(
                                    lang,
                                    "البيانات غير متاحة للتطبيقات غير الموجهة عبر VPN بدون صلاحية إحصاءات الاستخدام",
                                    "Traffic unavailable for non-VPN apps without Usage Access permission"
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        FilledTonalButton(
                            onClick = onRequestUsageAccess,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier
                                .height(28.dp)
                                .testTag("apps_grant_usage_access_btn")
                        ) {
                            Text(
                                text = tr(lang, "منح الصلاحية", "Grant Access"),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = {
                    Text(
                        tr(
                            lang,
                            "ابحث باسم التطبيق أو الحزمة أو UID...",
                            "Search by app name, package, or UID..."
                        )
                    )
                },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = tr(lang, "بحث", "Search")) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = tr(lang, "مسح", "Clear"))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("app_search_input")
            )

            // شرائح التصفية
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FilterList,
                    contentDescription = tr(lang, "تصفية", "Filter"),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                AppFilterOption.entries.forEach { option ->
                    FilterChip(
                        selected = filterOption == option,
                        onClick = { onFilterOptionChange(option) },
                        label = { Text(option.label(lang)) }
                    )
                }
            }

            // شرائح الفرز
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Sort,
                    contentDescription = tr(lang, "فرز", "Sort"),
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(18.dp)
                )
                AppSortOption.entries.forEach { option ->
                    FilterChip(
                        selected = sortOption == option,
                        onClick = { onSortOptionChange(option) },
                        label = { Text(option.label(lang)) }
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = tr(
                            lang,
                            "التطبيقات (${filteredAndSortedApps.size})",
                            "Apps (${filteredAndSortedApps.size})"
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = tr(
                            lang,
                            "نقرة لحظر Wi-Fi/الهاتف • سهم لتحديد السرعة",
                            "Tap Wi-Fi/Cell to block • Expand for speed limit"
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            items(filteredAndSortedApps, key = { it.uid }) { app ->
                val isExpanded = expandedAppUid == app.uid
                ApplicationControlItemCard(
                    app = app,
                    bandwidthUnit = bandwidthUnit,
                    isExpanded = isExpanded,
                    onToggleExpand = {
                        expandedAppUid = if (isExpanded) null else app.uid
                    },
                    onCardClick = { onOpenAppDetails(app.uid) },
                    onToggleWifi = { onToggleWifiQuickBlock(app) },
                    onToggleCellular = { onToggleCellularQuickBlock(app) },
                    onQuickPresetSelect = { preset ->
                        if (preset == PolicyPreset.CUSTOM) {
                            editingAppForRule = app
                        } else {
                            onSaveAppRule(
                                app,
                                preset,
                                preset.defaultDownloadBytesPerSec.coerceAtLeast(0L),
                                preset.defaultUploadBytesPerSec.coerceAtLeast(0L),
                                NetworkTypeCondition.ANY,
                                RuleDurationMode.PERMANENT,
                                60,
                                "Quick preset"
                            )
                        }
                    },
                    onConfigureRuleClick = { editingAppForRule = app }
                )
            }
        }
    }

    editingAppForRule?.let { targetApp ->
        RuleEditorDialog(
            app = targetApp,
            existingRule = targetApp.activeRule,
            onDismiss = { editingAppForRule = null },
            onSave = { preset, downBps, upBps, netCond, durMode, prio, notes ->
                onSaveAppRule(targetApp, preset, downBps, upBps, netCond, durMode, prio, notes)
                editingAppForRule = null
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ApplicationControlItemCard(
    app: AppNetworkProfile,
    bandwidthUnit: BandwidthUnit,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onCardClick: () -> Unit,
    onToggleWifi: () -> Unit,
    onToggleCellular: () -> Unit,
    onQuickPresetSelect: (PolicyPreset) -> Unit,
    onConfigureRuleClick: () -> Unit
) {
    val lang = LocalAppLanguage.current
    val activeRule = app.activeRule
    val activePreset = activeRule?.preset ?: PolicyPreset.ALLOW
    val isWifiBlocked = activeRule?.isWifiBlocked() == true
    val isCellularBlocked = activeRule?.isCellularBlocked() == true
    val enforcementState = app.activeRuleEnforcementState

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCardClick() }
            .testTag("app_item_${app.uid}")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // الصف الرئيسي الرشيق (أيقونة + اسم + سرعة واستهلاك + أزرار Wi-Fi / هاتف بنقرة + سهم توسيع)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box {
                    AppPackageIcon(packageName = app.packageName, appName = app.appName, size = 42.dp)
                    if (app.isCurrentlyActive) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .align(Alignment.BottomEnd)
                                .clip(CircleShape)
                                .background(NetSuccessEmerald)
                        )
                    }
                }

                // معلومات التطبيق الأساسية (مختصرة وواضحة)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = app.appName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (app.isSystemApp) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = tr(lang, "نظام", "System"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }

                    // حالة السياسة + الاستهلاك أو عدم التوفر الصادق
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PolicyBadgeChip(preset = activePreset, compact = true)

                        if (activeRule != null && enforcementState != null) {
                            val enfColor = if (enforcementState.isActivelyEnforced) NetSuccessEmerald else NetWarningAmber
                            Text(
                                text = enforcementState.badge(lang),
                                style = MaterialTheme.typography.labelSmall,
                                color = enfColor,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Text(
                            text = if (app.dataAvailability == DataAvailabilityStatus.UNAVAILABLE) {
                                app.dataAvailability.label(lang)
                            } else {
                                UnitConverter.formatVolume(app.totalTodayBytes)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }

                    // السرعة الحالية أو سقف السرعة المخصص
                    if (app.dataAvailability == DataAvailabilityStatus.AVAILABLE) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SpeedMetricPill(
                                isDownload = true,
                                formattedSpeed = UnitConverter.formatSpeed(app.downloadBytesPerSec, bandwidthUnit)
                            )
                            SpeedMetricPill(
                                isDownload = false,
                                formattedSpeed = UnitConverter.formatSpeed(app.uploadBytesPerSec, bandwidthUnit)
                            )
                            if (activeRule != null && activeRule.isRateLimited()) {
                                Text(
                                    text = "≤ ${UnitConverter.formatSpeed(activeRule.maxDownloadBytesPerSec, bandwidthUnit)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NetPrimaryBlue
                                )
                            }
                        }
                    }
                }

                // أزرار التحكم المباشر (Wi-Fi + Cellular + Expand) على طراز NetGuard
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    QuickNetworkToggleIconButton(
                        isWifi = true,
                        isBlocked = isWifiBlocked,
                        onClick = onToggleWifi,
                        modifier = Modifier.testTag("quick_wifi_toggle_${app.uid}")
                    )
                    QuickNetworkToggleIconButton(
                        isWifi = false,
                        isBlocked = isCellularBlocked,
                        onClick = onToggleCellular,
                        modifier = Modifier.testTag("quick_cell_toggle_${app.uid}")
                    )
                    IconButton(
                        onClick = onToggleExpand,
                        modifier = Modifier
                            .size(34.dp)
                            .testTag("expand_app_row_${app.uid}")
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = tr(lang, "توسيع الخيارات", "Expand options"),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // الدرج المدمج القابل للطي (يظهر فقط عند رغبة المستخدم لتحديد السرعة أو فتح التفاصيل)
            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = tr(lang, "تحديد السرعة والسياسة:", "Speed Limit & Policy:"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${app.packageName} • UID ${app.uid}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PolicyPreset.entries.forEach { preset ->
                            FilterChip(
                                selected = activePreset == preset,
                                onClick = { onQuickPresetSelect(preset) },
                                label = {
                                    Text(
                                        text = preset.shortLabel(lang),
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onConfigureRuleClick,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(tr(lang, "سرعة مخصصة وشرط شبكة", "Custom Speed & Scope"), style = MaterialTheme.typography.labelMedium)
                        }
                        FilledTonalButton(
                            onClick = onCardClick,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(tr(lang, "التفاصيل والإحصاءات", "Details & Stats"), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}
