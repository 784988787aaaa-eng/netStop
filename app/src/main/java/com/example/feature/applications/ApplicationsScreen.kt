package com.example.feature.applications

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.common.UnitConverter
import com.example.core.model.AppNetworkProfile
import com.example.core.model.BandwidthUnit
import com.example.core.model.NetworkTypeCondition
import com.example.core.model.PolicyPreset
import com.example.core.model.RuleDurationMode
import com.example.feature.common.AppPackageIcon
import com.example.feature.common.CapabilityStatusBadge
import com.example.feature.common.PolicyBadgeChip
import com.example.feature.common.RuleEditorDialog
import com.example.feature.common.SpeedMetricPill
import com.example.ui.AppFilterOption
import com.example.ui.AppSortOption

/**
 * الشاشة الثانية: قائمة التطبيقات وإدارة السياسات والبحث والفرز والتصفية (القسم السادس والثامن).
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
    modifier: Modifier = Modifier
) {
    var editingAppForRule by remember { mutableStateOf<AppNetworkProfile?>(null) }

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
                    AppFilterOption.RESTRICTED_OR_BLOCKED -> app.activeRule != null && app.activeRule.preset != PolicyPreset.ALLOW
                }
                matchesSearch && matchesFilter
            }.let { list ->
                when (sortOption) {
                    AppSortOption.TOTAL_USAGE -> list.sortedByDescending { it.totalTodayBytes }
                    AppSortOption.DOWNLOAD_SPEED -> list.sortedByDescending { it.downloadBytesPerSec }
                    AppSortOption.UPLOAD_SPEED -> list.sortedByDescending { it.uploadBytesPerSec }
                    AppSortOption.APP_NAME -> list.sortedBy { it.appName }
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("applications_screen")
    ) {
        // شريط البحث والتصفية والفرز
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text("ابحث باسم التطبيق أو الحزمة أو UID...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "بحث") },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "مسح البحث")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("app_search_input")
            )

            // شرائح التصفية بحسب الحالة والنوع
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FilterList,
                    contentDescription = "تصفية",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                AppFilterOption.entries.forEach { option ->
                    FilterChip(
                        selected = filterOption == option,
                        onClick = { onFilterOptionChange(option) },
                        label = { Text(option.labelAr) }
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
                    contentDescription = "فرز",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(18.dp)
                )
                AppSortOption.entries.forEach { option ->
                    FilterChip(
                        selected = sortOption == option,
                        onClick = { onSortOptionChange(option) },
                        label = { Text(option.labelAr) }
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    text = "عدد التطبيقات المعروضة: ${filteredAndSortedApps.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            items(filteredAndSortedApps, key = { it.uid }) { app ->
                ApplicationControlItemCard(
                    app = app,
                    bandwidthUnit = bandwidthUnit,
                    onCardClick = { onOpenAppDetails(app.uid) },
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
                                "تعيين سريع من شاشة التطبيقات"
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

@Composable
private fun ApplicationControlItemCard(
    app: AppNetworkProfile,
    bandwidthUnit: BandwidthUnit,
    onCardClick: () -> Unit,
    onQuickPresetSelect: (PolicyPreset) -> Unit,
    onConfigureRuleClick: () -> Unit
) {
    val activePreset = app.activeRule?.preset ?: PolicyPreset.ALLOW

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCardClick() }
            .testTag("app_item_${app.uid}")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AppPackageIcon(packageName = app.packageName, appName = app.appName, size = 44.dp)

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = app.appName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (app.isSystemApp) {
                            Text(
                                text = "نظام",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(
                        text = "${app.packageName} • UID ${app.uid}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CapabilityStatusBadge(status = app.capabilityStatus)
                        Text(
                            text = "المجموع: ${UnitConverter.formatVolume(app.totalTodayBytes)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    SpeedMetricPill(
                        isDownload = true,
                        formattedSpeed = UnitConverter.formatSpeed(app.downloadBytesPerSec, bandwidthUnit)
                    )
                    Spacer(Modifier.height(4.dp))
                    SpeedMetricPill(
                        isDownload = false,
                        formattedSpeed = UnitConverter.formatSpeed(app.uploadBytesPerSec, bandwidthUnit)
                    )
                }
            }

            // أزرار التحكم السريع بالسياسة لكل تطبيق
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PolicyPreset.entries.forEach { preset ->
                    val isSelected = activePreset == preset
                    if (isSelected) {
                        PolicyBadgeChip(preset = preset, onClick = { onConfigureRuleClick() })
                    } else {
                        FilterChip(
                            selected = false,
                            onClick = { onQuickPresetSelect(preset) },
                            label = { Text(preset.titleAr, style = MaterialTheme.typography.labelMedium) }
                        )
                    }
                }
                IconButton(
                    onClick = onConfigureRuleClick,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "إعدادات متقدمة للتطبيق",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
