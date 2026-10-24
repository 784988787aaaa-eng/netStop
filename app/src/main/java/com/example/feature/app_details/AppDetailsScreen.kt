package com.example.feature.app_details

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddAlert
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.core.common.UnitConverter
import com.example.core.i18n.LocalAppLanguage
import com.example.core.i18n.tr
import com.example.core.model.AppNetworkProfile
import com.example.core.model.AuditLogItem
import com.example.core.model.BandwidthUnit
import com.example.core.model.DataAvailabilityStatus
import com.example.core.model.FlowRecord
import com.example.core.model.NetworkTypeCondition
import com.example.core.model.PolicyPreset
import com.example.core.model.QuotaLimitAction
import com.example.core.model.QuotaPeriod
import com.example.core.model.QuotaRule
import com.example.core.model.RuleDurationMode
import com.example.core.model.RuleEnforcementState
import com.example.core.model.SpeedSamplePoint
import com.example.feature.common.AppPackageIcon
import com.example.feature.common.CapabilityStatusBadge
import com.example.feature.common.LiveTrafficAreaChart
import com.example.feature.common.PolicyBadgeChip
import com.example.feature.common.QuickNetworkToggleIconButton
import com.example.feature.common.QuotaEditorDialog
import com.example.feature.common.RuleEditorDialog
import com.example.feature.common.SpeedMetricPill
import com.example.ui.theme.NetBlockCoral
import com.example.ui.theme.NetDownloadTeal
import com.example.ui.theme.NetPrimaryBlue
import com.example.ui.theme.NetSuccessEmerald
import com.example.ui.theme.NetUploadViolet
import com.example.ui.theme.NetWarningAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * الشاشة الثالثة: تفاصيل التطبيق والتحكم المتقدم (Section 7).
 */
@OptIn(ExperimentalLayoutApi::class)
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
    onToggleWifiQuickBlock: (AppNetworkProfile) -> Unit = {},
    onToggleCellularQuickBlock: (AppNetworkProfile) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val lang = LocalAppLanguage.current
    BackHandler { onBack() }

    if (app == null) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(tr(lang, "لم يتم اختيار تطبيق.", "No app selected."), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onBack) { Text(tr(lang, "العودة", "Back")) }
        }
        return
    }

    var showRuleDialog by remember { mutableStateOf(false) }
    var showQuotaDialog by remember { mutableStateOf(false) }
    var customDownKbInput by remember(app.activeRule) {
        val currentDown = app.activeRule?.maxDownloadBytesPerSec?.takeIf { it > 0 } ?: (256L * 1024L)
        mutableStateOf((currentDown / 1024L).toString())
    }
    var customUpKbInput by remember(app.activeRule) {
        val currentUp = app.activeRule?.maxUploadBytesPerSec?.takeIf { it > 0 } ?: (128L * 1024L)
        mutableStateOf((currentUp / 1024L).toString())
    }
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("app_details_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. شريط العنوان والرجوع
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("details_back_button")) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = tr(lang, "رجوع", "Back")
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = tr(lang, "تفاصيل التطبيق والتحكم", "App Details & Control"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // 2. بطاقة هوية التطبيق والتحكم السريع بالشبكة
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
                        AppPackageIcon(packageName = app.packageName, appName = app.appName, size = 54.dp)
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
                                text = "UID ${app.uid} • ${if (app.isSystemApp) tr(lang, "نظام", "System") else tr(lang, "مستخدم", "User")}${if (app.sharedUidCount > 1) " • ${app.sharedUidCount} shared" else ""}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            QuickNetworkToggleIconButton(
                                isWifi = true,
                                isBlocked = app.activeRule?.isWifiBlocked() == true,
                                onClick = { onToggleWifiQuickBlock(app) }
                            )
                            QuickNetworkToggleIconButton(
                                isWifi = false,
                                isBlocked = app.activeRule?.isCellularBlocked() == true,
                                onClick = { onToggleCellularQuickBlock(app) }
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CapabilityStatusBadge(status = app.capabilityStatus)
                        PolicyBadgeChip(preset = app.activeRule?.preset ?: PolicyPreset.ALLOW)
                        app.activeRuleEnforcementState?.let { enf ->
                            Text(
                                text = enf.badge(lang),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (enf.isActivelyEnforced) NetSuccessEmerald else NetWarningAmber,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Text(
                        text = app.capabilityStatus.explanation(lang),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 3. بطاقة التحكم المباشر بالسياسة وسقف السرعة المخصص
        item {
            val activePreset = app.activeRule?.preset ?: PolicyPreset.ALLOW
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
                        Text(
                            text = tr(lang, "التحكم في الاتصال وتحديد السرعة", "Connection & Speed Control"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        OutlinedButton(
                            onClick = { showRuleDialog = true },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(tr(lang, "متقدم", "Advanced"), style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PolicyPreset.entries.forEach { preset ->
                            FilterChip(
                                selected = activePreset == preset,
                                onClick = {
                                    if (preset == PolicyPreset.CUSTOM) {
                                        val downBps = (customDownKbInput.toLongOrNull() ?: 256L) * 1024L
                                        val upBps = (customUpKbInput.toLongOrNull() ?: 128L) * 1024L
                                        onSaveRule(
                                            PolicyPreset.CUSTOM,
                                            downBps,
                                            upBps,
                                            app.activeRule?.networkCondition ?: NetworkTypeCondition.ANY,
                                            app.activeRule?.durationMode ?: RuleDurationMode.PERMANENT,
                                            app.activeRule?.priority ?: 60,
                                            ""
                                        )
                                    } else {
                                        onSaveRule(
                                            preset,
                                            preset.defaultDownloadBytesPerSec.coerceAtLeast(0L),
                                            preset.defaultUploadBytesPerSec.coerceAtLeast(0L),
                                            app.activeRule?.networkCondition ?: NetworkTypeCondition.ANY,
                                            app.activeRule?.durationMode ?: RuleDurationMode.PERMANENT,
                                            app.activeRule?.priority ?: 60,
                                            ""
                                        )
                                    }
                                },
                                label = { Text(preset.shortLabel(lang)) }
                            )
                        }
                    }

                    // حقول إدخال السرعة المخصصة المستقلة للتنزيل والرفع بوحدة واضحة (KB/s)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = customDownKbInput,
                            onValueChange = { customDownKbInput = it.filter { ch -> ch.isDigit() } },
                            label = { Text(tr(lang, "تنزيل (KB/s)", "Down (KB/s)")) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = customUpKbInput,
                            onValueChange = { customUpKbInput = it.filter { ch -> ch.isDigit() } },
                            label = { Text(tr(lang, "رفع (KB/s)", "Up (KB/s)")) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                val downBps = (customDownKbInput.toLongOrNull()?.coerceAtLeast(1L) ?: 256L) * 1024L
                                val upBps = (customUpKbInput.toLongOrNull()?.coerceAtLeast(1L) ?: 128L) * 1024L
                                onSaveRule(
                                    PolicyPreset.CUSTOM,
                                    downBps,
                                    upBps,
                                    app.activeRule?.networkCondition ?: NetworkTypeCondition.ANY,
                                    app.activeRule?.durationMode ?: RuleDurationMode.PERMANENT,
                                    app.activeRule?.priority ?: 60,
                                    "Custom KB/s"
                                )
                            },
                            modifier = Modifier.padding(top = 6.dp)
                        ) {
                            Text(tr(lang, "تطبيق", "Apply"))
                        }
                    }

                    app.activeRule?.let { rule ->
                        val enfState = app.activeRuleEnforcementState ?: RuleEnforcementState.SAVED_ONLY_VPN_OFF
                        Text(
                            text = "${rule.networkCondition.title(lang)} • ${rule.durationMode.title(lang)} • ${enfState.badge(lang)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 4. السرعة اللحظية والرسم البياني للتطبيق
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
                        Text(
                            text = tr(lang, "السرعة المباشرة", "Live Speed"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (app.dataAvailability == DataAvailabilityStatus.AVAILABLE) {
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
                        } else {
                            Text(
                                text = app.dataAvailability.label(lang),
                                style = MaterialTheme.typography.labelMedium,
                                color = NetWarningAmber
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

        // 5. الاستهلاك التراكمي حسب الفترة ونوع الشبكة
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = tr(lang, "استهلاك البيانات", "Data Usage"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (app.dataAvailability == DataAvailabilityStatus.UNAVAILABLE) {
                        Text(
                            text = tr(lang, "بيانات الاستهلاك غير متاحة لهذا التطبيق على هذا الجهاز.", "Usage data unavailable for this app on this device."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            StatMiniBox(
                                title = tr(lang, "اليوم", "Today"),
                                value = UnitConverter.formatVolume(app.totalTodayBytes),
                                subtitle = "↓ ${UnitConverter.formatVolume(app.todayDownloadBytes)} • ↑ ${UnitConverter.formatVolume(app.todayUploadBytes)}",
                                modifier = Modifier.weight(1f)
                            )
                            StatMiniBox(
                                title = tr(lang, "7 أيام", "7 Days"),
                                value = UnitConverter.formatVolume(app.weekTotalBytes),
                                subtitle = tr(lang, "أسبوعي", "Weekly"),
                                modifier = Modifier.weight(1f)
                            )
                            StatMiniBox(
                                title = tr(lang, "30 يومًا", "30 Days"),
                                value = UnitConverter.formatVolume(app.monthTotalBytes),
                                subtitle = tr(lang, "شهري", "Monthly"),
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            StatMiniBox(
                                title = "Wi-Fi",
                                value = UnitConverter.formatVolume(app.wifiBytes),
                                subtitle = tr(lang, "شبكة لاسلكية", "Wireless"),
                                modifier = Modifier.weight(1f)
                            )
                            StatMiniBox(
                                title = tr(lang, "بيانات الهاتف", "Cellular"),
                                value = UnitConverter.formatVolume(app.cellularBytes),
                                subtitle = tr(lang, "شريحة الجوال", "Mobile Data"),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // 6. حصة البيانات المخصصة للتطبيق
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
                        Text(
                            text = tr(lang, "حصة البيانات", "Data Quota"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        OutlinedButton(onClick = { showQuotaDialog = true }) {
                            Icon(Icons.Default.AddAlert, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (app.activeQuota == null) tr(lang, "إضافة حصة", "Add Quota") else tr(lang, "تعديل", "Edit"))
                        }
                    }

                    val quota = app.activeQuota
                    if (quota == null) {
                        Text(
                            text = tr(lang, "لا توجد حصة مخصصة لهذا التطبيق.", "No quota set for this app."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${quota.period.title(lang)}: ${UnitConverter.formatVolume(quota.usedBytes)} / ${UnitConverter.formatVolume(quota.limitBytes)} (${quota.usagePercent}%)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            IconButton(onClick = { onResetQuota(quota) }) {
                                Icon(Icons.Default.Refresh, contentDescription = tr(lang, "تصفير", "Reset"))
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
                            text = "${tr(lang, "تنبيه عند", "Alert at")} ${quota.warningPercent}% • ${quota.actionOnLimit.title(lang)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 7. التدفقات النشطة وسجل الأحداث الخاص بالتطبيق
        if (appFlows.isNotEmpty() || appEvents.isNotEmpty()) {
            item {
                Text(
                    text = tr(
                        lang,
                        "التدفقات النشطة (${appFlows.size}) والأحداث (${appEvents.size})",
                        "Active Flows (${appFlows.size}) & Events (${appEvents.size})"
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (appFlows.isNotEmpty()) {
            items(appFlows.take(10), key = { it.flowKey }) { flow ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "${flow.protocol.label} (${flow.ipVersion.label}) • ${flow.state.label(lang)}",
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
                                text = "DNS: ${flow.dnsQueryHost}",
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
                        Text(ev.eventType.title(lang), style = MaterialTheme.typography.labelLarge, color = NetPrimaryBlue)
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
