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
import com.example.core.model.AppNetworkProfile
import com.example.core.model.BandwidthUnit
import com.example.core.model.NetworkTypeCondition
import com.example.core.model.PolicyPreset
import com.example.core.model.RuleDurationMode
import com.example.core.model.TrafficRule
import com.example.feature.common.AppPackageIcon
import com.example.feature.common.PolicyBadgeChip
import com.example.feature.common.RuleEditorDialog
import com.example.ui.theme.NetBlockCoral
import com.example.ui.theme.NetPrimaryBlue
import com.example.ui.theme.NetWarningAmber

/**
 * الشاشة الرابعة: قواعد التحكم والأولويات وحسم التعارض (Rules Screen - القسم الخامس والثامن).
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
    modifier: Modifier = Modifier
) {
    var showAppPickerForNewRule by remember { mutableStateOf(false) }
    var selectedAppForRule by remember { mutableStateOf<AppNetworkProfile?>(null) }
    var editingRule by remember { mutableStateOf<TrafficRule?>(null) }

    // اكتشاف التطبيقات التي لديها أكثر من قاعدة مفعلة لشرح آلية حسم التعارض
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
                    Icon(Icons.Default.Add, contentDescription = "إضافة قاعدة")
                    Spacer(Modifier.width(6.dp))
                    Text("إضافة قاعدة", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // بطاقة شرح نظام الأولويات وحسم التعارض
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = NetPrimaryBlue,
                            modifier = Modifier.size(22.dp)
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "محرك الأولويات ومعالجة تعارض القواعد",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "عند وجود أكثر من قاعدة لنفس التطبيق، يطبق المحرك القاعدة الأعلى أولوية (100 إلى 1)، ثم الأكثر تخصيصًا لنوع الشبكة (Wi-Fi أو بيانات الهاتف قبل جميع الشبكات)، ثم الأكثر تقييدًا.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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
                                text = "يوجد ${conflictsByUid.size} تطبيق لديه قواعد متعددة؛ تم حسم التعارض تلقائيًا لصالح القاعدة ذات الأولوية الأعلى.",
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
                                text = "لا توجد قواعد تحكم مخصصة بعد",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "اضغط على زر «إضافة قاعدة» لتحديد سرعة التنزيل والرفع أو حظر اتصال تطبيق معين على شبكة Wi-Fi أو بيانات الهاتف.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(6.dp))
                            Button(onClick = { showAppPickerForNewRule = true }) {
                                Text("إنشاء أول قاعدة تحكم")
                            }
                        }
                    }
                }
            } else {
                items(rules, key = { it.id }) { rule ->
                    val hasConflict = conflictsByUid.containsKey(rule.uid)
                    val isWinningInConflict = conflictsByUid[rule.uid]?.maxByOrNull { it.priority }?.id == rule.id
                    RuleCardItem(
                        rule = rule,
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
            title = { Text("اختر التطبيق لإضافة قاعدة تحكم") },
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
                    Text("إغلاق")
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
    bandwidthUnit: BandwidthUnit,
    hasConflict: Boolean,
    isWinningInConflict: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
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
                AppPackageIcon(packageName = rule.packageName, appName = rule.appName, size = 40.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = rule.appName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "الأولوية: ${rule.priority}/100 • ${rule.networkCondition.titleAr}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                PolicyBadgeChip(preset = rule.preset)
                Switch(
                    checked = rule.isEnabled,
                    onCheckedChange = onToggle
                )
            }

            val downText = when {
                rule.maxDownloadBytesPerSec < 0 -> "محظور"
                rule.maxDownloadBytesPerSec == 0L -> "غير محدود"
                else -> UnitConverter.formatSpeed(rule.maxDownloadBytesPerSec, bandwidthUnit)
            }
            val upText = when {
                rule.maxUploadBytesPerSec < 0 -> "محظور"
                rule.maxUploadBytesPerSec == 0L -> "غير محدود"
                else -> UnitConverter.formatSpeed(rule.maxUploadBytesPerSec, bandwidthUnit)
            }

            Text(
                text = "سقف التنزيل: $downText • سقف الرفع: $upText • المدة: ${rule.durationMode.titleAr}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (hasConflict) {
                Text(
                    text = if (isWinningInConflict) {
                        "حالة التعارض: هذه القاعدة هي المطبقة فعليًا لأنها الأعلى أولوية."
                    } else {
                        "حالة التعارض: قاعدة احتياطية (توجد قاعدة أخرى لنفس التطبيق بأولوية أعلى)."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isWinningInConflict) NetPrimaryBlue else NetWarningAmber
                )
            }

            if (rule.notes.isNotBlank()) {
                Text(
                    text = "ملاحظة: ${rule.notes}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "تعديل القاعدة", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "حذف القاعدة", tint = NetBlockCoral)
                }
            }
        }
    }
}
