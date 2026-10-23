package com.example.feature.app_details

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddAlert
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.common.UnitConverter
import com.example.core.model.AppNetworkProfile
import com.example.core.model.AuditLogItem
import com.example.core.model.BandwidthUnit
import com.example.core.model.FlowRecord
import com.example.core.model.NetworkTypeCondition
import com.example.core.model.PolicyPreset
import com.example.core.model.QuotaLimitAction
import com.example.core.model.QuotaPeriod
import com.example.core.model.QuotaRule
import com.example.core.model.RuleDurationMode
import com.example.core.model.SpeedSamplePoint
import com.example.feature.common.AppPackageIcon
import com.example.feature.common.CapabilityStatusBadge
import com.example.feature.common.LiveTrafficAreaChart
import com.example.feature.common.PolicyBadgeChip
import com.example.feature.common.QuotaEditorDialog
import com.example.feature.common.RuleEditorDialog
import com.example.feature.common.SpeedMetricPill
import com.example.ui.theme.NetBlockCoral
import com.example.ui.theme.NetDownloadTeal
import com.example.ui.theme.NetPrimaryBlue
import com.example.ui.theme.NetUploadViolet
import com.example.ui.theme.NetWarningAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * الشاشة الثالثة: تفاصيل التطبيق الشاملة (App Details Screen - القسم الثامن).
 */
@Composable
fun AppDetailsScreen(
    app: AppNetworkProfile?,
    speedHistory: List<SpeedSamplePoint>,
    appFlows: List<FlowRecord>,
    appEvents: List<AuditLogItem>,
    bandwidthUnit: BandwidthUnit,
    onBack: () -> Unit,
    onSaveRule: (
        preset: PolicyPreset,
        customDownBps: Long,
        customUpBps: Long,
        networkCondition: NetworkTypeCondition,
        durationMode: RuleDurationMode,
        priority: Int,
        notes: String
    ) -> Unit,
    onSaveQuota: (
        period: QuotaPeriod,
        limitBytes: Long,
        warningPercent: Int,
        actionOnLimit: QuotaLimitAction,
        networkCondition: NetworkTypeCondition,
        customDays: Int
    ) -> Unit,
    onResetQuota: (QuotaRule) -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }

    if (app == null) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("لم يتم اختيار تطبيق لعرض تفاصيله.", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onBack) { Text("العودة") }
        }
        return
    }

    var showRuleDialog by remember { mutableStateOf(false) }
    var showQuotaDialog by remember { mutableStateOf(false) }
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("app_details_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. شريط العنوان والعودة
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("details_back_button")) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "رجوع"
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "تفاصيل التطبيق والتحكم الدقيق",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // 2. بطاقة هوية التطبيق وإمكانية التحكم
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        AppPackageIcon(packageName = app.packageName, appName = app.appName, size = 56.dp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = app.appName,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = app.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "UID: ${app.uid} • ${if (app.isSystemApp) "تطبيق نظام" else "تطبيق مستخدم"} • ${if (app.sharedUidCount > 1) "${app.sharedUidCount} حزم مشتركة" else "UID مستقل"}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        PolicyBadgeChip(preset = app.activeRule?.preset ?: PolicyPreset.ALLOW)
                    }

                    CapabilityStatusBadge(status = app.capabilityStatus)
                    Text(
                        text = app.capabilityStatus.explanationAr,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 3. بطاقة السرعة اللحظية والرسم البياني للتطبيق
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("سرعات النقل اللحظية", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SpeedMetricPill(
                                isDownload = true,
                                formattedSpeed = UnitConverter.formatSpeed(app.downloadBytesPerSec, bandwidthUnit)
                            )
                            SpeedMetricPill(
                                isDownload = false,
                                formattedSpeed = UnitConverter.formatSpeed(app.uploadBytesPerSec, bandwidthUnit)
                            )
                        }
                    }

                    LiveTrafficAreaChart(
                        samples = speedHistory,
                        bandwidthUnit = bandwidthUnit
                    )
                }
            }
        }

        // 4. الاستهلاك التراكمي (يومي / أسبوعي / شهري) + نوع الشبكة (Wi-Fi مقابل الهاتف)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("إحصاءات الاستهلاك ونوع الشبكة", style = MaterialTheme.typography.titleMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StatMiniBox(
                            title = "اليوم",
                            value = UnitConverter.formatVolume(app.totalTodayBytes),
                            subtitle = "↓ ${UnitConverter.formatVolume(app.todayDownloadBytes)} • ↑ ${UnitConverter.formatVolume(app.todayUploadBytes)}",
                            modifier = Modifier.weight(1f)
                        )
                        StatMiniBox(
                            title = "هذا الأسبوع",
                            value = UnitConverter.formatVolume(app.weekTotalBytes),
                            subtitle = "تراكمي 7 أيام",
                            modifier = Modifier.weight(1f)
                        )
                        StatMiniBox(
                            title = "هذا الشهر",
                            value = UnitConverter.formatVolume(app.monthTotalBytes),
                            subtitle = "تراكمي 30 يومًا",
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StatMiniBox(
                            title = "استهلاك Wi-Fi",
                            value = UnitConverter.formatVolume(app.wifiBytes),
                            subtitle = "الشبكات اللاسلكية",
                            modifier = Modifier.weight(1f)
                        )
                        StatMiniBox(
                            title = "بيانات الهاتف",
                            value = UnitConverter.formatVolume(app.cellularBytes),
                            subtitle = "شريحة الجوال",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // 5. إعدادات السرعة وقاعدة التحكم
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("إعدادات السرعة وقاعدة التحكم", style = MaterialTheme.typography.titleMedium)
                        Button(onClick = { showRuleDialog = true }) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (app.activeRule == null) "تعيين قاعدة" else "تعديل القاعدة")
                        }
                    }

                    val rule = app.activeRule
                    if (rule == null) {
                        Text(
                            text = "لا توجد قيود سرعة أو حظر مفعلة لهذا التطبيق حاليًا (سماح كامل).",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "السياسة الحالية: ${rule.preset.titleAr} • الأولوية: ${rule.priority}/100",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "حد التنزيل: ${if (rule.maxDownloadBytesPerSec == 0L) "غير محدود" else UnitConverter.formatSpeed(rule.maxDownloadBytesPerSec, bandwidthUnit)} | حد الرفع: ${if (rule.maxUploadBytesPerSec == 0L) "غير محدود" else UnitConverter.formatSpeed(rule.maxUploadBytesPerSec, bandwidthUnit)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "شرط الشبكة: ${rule.networkCondition.titleAr} • المدة: ${rule.durationMode.titleAr}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 6. الحصة المخصصة للتطبيق
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("حصة استهلاك البيانات المخصصة", style = MaterialTheme.typography.titleMedium)
                        OutlinedButton(onClick = { showQuotaDialog = true }) {
                            Icon(Icons.Default.AddAlert, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (app.activeQuota == null) "إضافة حصة" else "تعديل الحصة")
                        }
                    }

                    val quota = app.activeQuota
                    if (quota == null) {
                        Text(
                            text = "لم يتم تحديد سقف حصة بيانات خاص بهذا التطبيق بعد.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${quota.period.titleAr}: ${UnitConverter.formatVolume(quota.usedBytes)} من ${UnitConverter.formatVolume(quota.limitBytes)} (${quota.usagePercent}%)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            IconButton(onClick = { onResetQuota(quota) }) {
                                Icon(Icons.Default.Refresh, contentDescription = "إعادة تعيين عداد الحصة")
                            }
                        }
                        LinearProgressIndicator(
                            progress = { quota.usageRatio.coerceIn(0f, 1f) },
                            color = when {
                                quota.isLimitReached -> NetBlockCoral
                                quota.isWarningReached -> NetWarningAmber
                                else -> NetDownloadTeal
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                        Text(
                            text = "التنبيه عند ${quota.warningPercent}% • الإجراء عند النفاد: ${quota.actionOnLimit.titleAr}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 7. التدفقات النشطة وسجل الأحداث الخاص بالتطبيق
        item {
            Text(
                text = "التدفقات الشبكية المرصودة (${appFlows.size}) وسجل الأحداث (${appEvents.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        if (appFlows.isNotEmpty()) {
            items(appFlows.take(10), key = { it.flowKey }) { flow ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "${flow.protocol.label} (${flow.ipVersion.label}) • ${flow.state.labelAr}",
                            style = MaterialTheme.typography.labelLarge,
                            color = NetPrimaryBlue
                        )
                        Text(
                            text = "${flow.sourceIp}:${flow.sourcePort} → ${flow.destinationIp}:${flow.destinationPort}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (flow.dnsQueryHost != null) {
                            Text(
                                text = "نطاق DNS: ${flow.dnsQueryHost}",
                                style = MaterialTheme.typography.bodySmall,
                                color = NetUploadViolet
                            )
                        }
                    }
                }
            }
        }

        items(appEvents.take(15), key = { "ev_${it.id}" }) { ev ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(ev.eventType.titleAr, style = MaterialTheme.typography.labelLarge, color = NetPrimaryBlue)
                        Text(ev.summaryAr, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        text = timeFormatter.format(Date(ev.timestampMillis)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showRuleDialog) {
        RuleEditorDialog(
            app = app,
            existingRule = app.activeRule,
            onDismiss = { showRuleDialog = false },
            onSave = { preset, downBps, upBps, netCond, durMode, prio, notes ->
                onSaveRule(preset, downBps, upBps, netCond, durMode, prio, notes)
                showRuleDialog = false
            }
        )
    }

    if (showQuotaDialog) {
        QuotaEditorDialog(
            targetApp = app,
            existingQuota = app.activeQuota,
            onDismiss = { showQuotaDialog = false },
            onSave = { period, limitBytes, warnPct, action, netCond, customDays ->
                onSaveQuota(period, limitBytes, warnPct, action, netCond, customDays)
                showQuotaDialog = false
            }
        )
    }
}

@Composable
private fun StatMiniBox(
    title: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
