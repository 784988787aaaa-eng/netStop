package com.example.feature.rules

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.common.UnitConverter
import com.example.core.i18n.LocalAppLanguage
import com.example.core.i18n.tr
import com.example.core.model.ActiveNetworkTransport
import com.example.core.model.AppNetworkProfile
import com.example.core.model.BandwidthUnit
import com.example.core.model.NetworkTypeCondition
import com.example.core.model.PolicyPreset
import com.example.core.model.RuleDurationMode
import com.example.core.model.RuleEnforcementState
import com.example.core.model.TrafficRule
import com.example.feature.common.AppPackageIcon
import com.example.feature.common.EngineInactiveWarningBanner
import com.example.feature.common.PolicyBadgeChip
import com.example.feature.common.RuleEditorDialog
import com.example.ui.theme.NetBlockCoral
import com.example.ui.theme.NetPrimaryBlue
import com.example.ui.theme.NetSuccessEmerald
import com.example.ui.theme.NetWarningAmber

/**
 * الشاشة الرابعة: قواعد التحكم والأولويات وحسم التعارض (Rules Screen).
 */
@Composable
fun RulesScreen(
    rules: List<TrafficRule>,
    apps: List<AppNetworkProfile>,
    bandwidthUnit: BandwidthUnit,
    onSaveRule: (
        app: AppNetworkProfile,
        preset: PolicyPreset,
        customDownBps: Long,
        customUpBps: Long,
        networkCondition: NetworkTypeCondition,
        durationMode: RuleDurationMode,
        priority: Int,
        notes: String,
        existingRuleId: Long
    ) -> Unit,
    onToggleRule: (TrafficRule, Boolean) -> Unit,
    onDeleteRule: (TrafficRule) -> Unit,
    isVpnRunning: Boolean = false,
    activeTransport: ActiveNetworkTransport = ActiveNetworkTransport.WIFI,
    failedRulePackages: Set<String> = emptySet(),
    onStartEngineClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val lang = LocalAppLanguage.current
    var showAppPickerForNewRule by remember { mutableStateOf(false) }
    var selectedAppForRule by remember { mutableStateOf<AppNetworkProfile?>(null) }
    var editingRule by remember { mutableStateOf<TrafficRule?>(null) }

    val conflictsByUid = remember(rules) {
        rules.filter { it.isEnabled }.groupBy { it.uid }.filterValues { it.size > 1 }
    }

    Scaffold(
        modifier = modifier.fillMaxSize().testTag("rules_screen"),
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAppPickerForNewRule = true },
                containerColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("add_rule_fab")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Add, contentDescription = tr(lang, "إضافة قاعدة", "Add Rule"))
                    Spacer(Modifier.width(6.dp))
                    Text(tr(lang, "إضافة قاعدة", "Add Rule"), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!isVpnRunning) {
                item {
                    EngineInactiveWarningBanner(
                        isVpnRunning = false,
                        enabledRulesCount = rules.count { it.isEnabled },
                        onStartEngineClick = onStartEngineClick
                    )
                }
            }

            // بطاقة مختصرة لشرح التمييز بين القاعدة المحفوظة والقاعدة النافذة
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = NetPrimaryBlue,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = tr(
                                lang,
                                "تُرتب القواعد حسب الأولوية (100–1) ونوع الشبكة. القواعد المحفوظة تصبح نافذة فور تشغيل المحرك.",
                                "Rules are prioritized (100–1) by network scope. Saved rules take effect when the engine is active."
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (conflictsByUid.isNotEmpty()) {
                item {
                    Surface(
                        color = NetWarningAmber.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = NetWarningAmber)
                            Text(
                                text = tr(
                                    lang,
                                    "${conflictsByUid.size} تطبيق لديه قواعد متعددة؛ تُطبق القاعدة الأعلى أولوية تلقائياً.",
                                    "${conflictsByUid.size} app(s) have multiple rules; highest priority rule wins."
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = NetWarningAmber
                            )
                        }
                    }
                }
            }

            if (rules.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = tr(lang, "لا توجد قواعد مخصصة بعد", "No Custom Rules Yet"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = tr(
                                    lang,
                                    "أنشئ قاعدة لتحديد السرعة أو حظر تطبيق على Wi-Fi أو بيانات الهاتف.",
                                    "Create a rule to limit speed or block an app on Wi-Fi or Cellular."
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            Button(onClick = { showAppPickerForNewRule = true }) {
                                Text(tr(lang, "إضافة قاعدة", "Create Rule"))
                            }
                        }
                    }
                }
            } else {
                items(rules, key = { it.id }) { rule ->
                    val hasConflict = conflictsByUid.containsKey(rule.uid)
                    val isWinningInConflict = conflictsByUid[rule.uid]?.maxByOrNull { it.priority }?.id == rule.id
                    val enforcementState = rule.evaluateEnforcementState(
                        isVpnRunning = isVpnRunning,
                        activeTransport = activeTransport,
                        hasApplyError = failedRulePackages.contains(rule.packageName)
                    )
                    RuleCardItem(
                        rule = rule,
                        enforcementState = enforcementState,
                        bandwidthUnit = bandwidthUnit,
                        hasConflict = hasConflict,
                        isWinningInConflict = isWinningInConflict,
                        onToggle = { enabled -> onToggleRule(rule, enabled) },
                        onEdit = {
                            val matchingApp = apps.firstOrNull { it.uid == rule.uid } ?: AppNetworkProfile(
                                uid = rule.uid,
                                packageName = rule.packageName,
                                appName = rule.appName,
                                isSystemApp = false
                            )
                            selectedAppForRule = matchingApp
                            editingRule = rule
                        },
                        onDelete = { onDeleteRule(rule) }
                    )
                }
            }
        }
    }

    if (showAppPickerForNewRule) {
        AlertDialog(
            onDismissRequest = { showAppPickerForNewRule = false },
            title = { Text(tr(lang, "اختر تطبيقاً", "Select Application")) },
            text = {
                LazyColumn(
                    modifier = Modifier.height(340.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(apps, key = { it.uid }) { app ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showAppPickerForNewRule = false
                                    editingRule = null
                                    selectedAppForRule = app
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                AppPackageIcon(packageName = app.packageName, appName = app.appName, size = 34.dp)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(app.appName, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = "UID ${app.uid} • ${app.packageName}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                OutlinedButton(onClick = { showAppPickerForNewRule = false }) {
                    Text(tr(lang, "إغلاق", "Close"))
                }
            }
        )
    }

    selectedAppForRule?.let { targetApp ->
        RuleEditorDialog(
            app = targetApp,
            existingRule = editingRule,
            onDismiss = {
                selectedAppForRule = null
                editingRule = null
            },
            onSave = { preset, downBps, upBps, netCond, durMode, prio, notes ->
                onSaveRule(
                    targetApp,
                    preset,
                    downBps,
                    upBps,
                    netCond,
                    durMode,
                    prio,
                    notes,
                    editingRule?.id ?: 0L
                )
                selectedAppForRule = null
                editingRule = null
            }
        )
    }
}

@Composable
private fun RuleCardItem(
    rule: TrafficRule,
    enforcementState: RuleEnforcementState,
    bandwidthUnit: BandwidthUnit,
    hasConflict: Boolean,
    isWinningInConflict: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val lang = LocalAppLanguage.current
    val enforcementColor = when (enforcementState) {
        RuleEnforcementState.ENFORCED_IN_KERNEL_VPN,
        RuleEnforcementState.ACTIVE_MONITORED_RATE -> NetSuccessEmerald
        RuleEnforcementState.SAVED_ONLY_VPN_OFF,
        RuleEnforcementState.INACTIVE_NETWORK_MISMATCH -> NetWarningAmber
        RuleEnforcementState.FAILED_TO_APPLY -> NetBlockCoral
        RuleEnforcementState.DISABLED_BY_USER,
        RuleEnforcementState.EXPIRED_TIME_LIMIT -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AppPackageIcon(packageName = rule.packageName, appName = rule.appName, size = 38.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = rule.appName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${tr(lang, "أولوية", "Priority")} ${rule.priority} • ${rule.networkCondition.title(lang)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                PolicyBadgeChip(preset = rule.preset, compact = true)
                Switch(
                    checked = rule.isEnabled,
                    onCheckedChange = onToggle
                )
            }

            Surface(
                color = enforcementColor.copy(alpha = 0.14f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (rule.isEnabled) {
                            tr(lang, "الحالة: مفعلة", "Saved: Enabled")
                        } else {
                            tr(lang, "الحالة: معطلة", "Saved: Disabled")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = enforcementState.badge(lang),
                        style = MaterialTheme.typography.labelSmall,
                        color = enforcementColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            val downText = when {
                rule.maxDownloadBytesPerSec < 0 -> tr(lang, "حظر", "Blocked")
                rule.maxDownloadBytesPerSec == 0L -> tr(lang, "غير محدود", "Unlimited")
                else -> UnitConverter.formatSpeed(rule.maxDownloadBytesPerSec, bandwidthUnit)
            }
            val upText = when {
                rule.maxUploadBytesPerSec < 0 -> tr(lang, "حظر", "Blocked")
                rule.maxUploadBytesPerSec == 0L -> tr(lang, "غير محدود", "Unlimited")
                else -> UnitConverter.formatSpeed(rule.maxUploadBytesPerSec, bandwidthUnit)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "↓ $downText • ↑ $upText • ${rule.durationMode.title(lang)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )

                Row {
                    IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = tr(lang, "تعديل", "Edit"),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = tr(lang, "حذف", "Delete"),
                            tint = NetBlockCoral,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            if (hasConflict) {
                Text(
                    text = if (isWinningInConflict) {
                        tr(lang, "القاعدة المطبقة (الأعلى أولوية)", "Active rule (highest priority)")
                    } else {
                        tr(lang, "احتياطية (توجد قاعدة أعلى أولوية)", "Standby (overridden by higher priority rule)")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isWinningInConflict) NetPrimaryBlue else NetWarningAmber
                )
            }
        }
    }
}
