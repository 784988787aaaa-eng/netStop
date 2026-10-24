package com.example.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.common.UnitConverter
import com.example.core.i18n.LocalAppLanguage
import com.example.core.i18n.tr
import com.example.core.model.ActiveNetworkTransport
import com.example.core.model.AppNetworkProfile
import com.example.core.model.AuditLogItem
import com.example.core.model.BandwidthUnit
import com.example.core.model.DataAvailabilityStatus
import com.example.core.model.EngineDiagnostics
import com.example.core.model.EngineUiState
import com.example.core.model.PolicyPreset
import com.example.core.model.QuotaRule
import com.example.core.model.RuleEnforcementState
import com.example.core.model.SpeedSamplePoint
import com.example.core.model.TrafficRule
import com.example.feature.common.AppPackageIcon
import com.example.feature.common.LiveTrafficAreaChart
import com.example.feature.common.PolicyBadgeChip
import com.example.feature.common.SpeedMetricPill
import com.example.ui.NetBalancerDestination
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
 * الشاشة الأولى: لوحة التحكم الرئيسية (Control Center - القسم 4).
 */
@Composable
fun DashboardScreen(
    diagnostics: EngineDiagnostics,
    engineUiState: EngineUiState = EngineUiState.STOPPED,
    totalDownloadBps: Long,
    totalUploadBps: Long,
    todayTotalBytes: Long,
    todayWifiBytes: Long,
    todayCellularBytes: Long,
    activeTransport: ActiveNetworkTransport,
    speedHistory: List<SpeedSamplePoint>,
    apps: List<AppNetworkProfile>,
    bandwidthUnit: BandwidthUnit,
    onToggleVpnClick: () -> Unit,
    onRunSelfTestClick: () -> Unit,
    onNavigate: (NetBalancerDestination) -> Unit,
    onOpenAppDetails: (Int) -> Unit,
    unattributedDownloadBps: Long = 0L,
    unattributedUploadBps: Long = 0L,
    lastUpdatedMillis: Long = System.currentTimeMillis(),
    isKernelTelemetryAvailable: Boolean = true,
    rules: List<TrafficRule> = emptyList(),
    quotas: List<QuotaRule> = emptyList(),
    auditLogs: List<AuditLogItem> = emptyList(),
    onRequestUsageAccess: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val lang = LocalAppLanguage.current
    val activeApps = remember(apps) { apps.filter { it.isCurrentlyActive }.take(5) }
    val topConsumers = remember(apps) {
        apps.filter { it.dataAvailability == DataAvailabilityStatus.AVAILABLE }
            .sortedByDescending { it.totalTodayBytes }
            .take(5)
    }
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("dashboard_screen"),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. بطاقة قيادة محرك الشبكة و VPN
        item {
            EngineHeroCard(
                diagnostics = diagnostics,
                engineUiState = engineUiState,
                activeTransport = activeTransport,
                lastUpdatedText = timeFormatter.format(Date(lastUpdatedMillis)),
                onToggleVpnClick = onToggleVpnClick,
                onRunSelfTestClick = onRunSelfTestClick
            )
        }

        // تنبيه منح صلاحية PACKAGE_USAGE_STATS لقراءة عدادات التطبيقات الخارجية على Android 10+
        if (!diagnostics.isUsageAccessGranted) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = NetWarningAmber.copy(alpha = 0.14f)
                    ),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, NetWarningAmber.copy(alpha = 0.45f), MaterialTheme.shapes.medium)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = NetWarningAmber,
                            modifier = Modifier.size(20.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = tr(
                                    lang,
                                    "صلاحية إحصاءات الاستخدام غير مفعّلة",
                                    "Usage Access Permission Required"
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = tr(
                                    lang,
                                    "تتطلب قراءة استهلاك التطبيقات الخارجية (مثل Chrome) على أندرويد 10+ منح صلاحية إحصاءات الاستخدام أو توجيه التطبيق عبر VPN.",
                                    "Reading per-app traffic (e.g. Chrome) on Android 10+ requires Usage Access or routing the app through the VPN."
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FilledTonalButton(
                            onClick = onRequestUsageAccess,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.testTag("dashboard_grant_usage_access_btn")
                        ) {
                            Text(
                                text = tr(lang, "منح الصلاحية", "Grant Access"),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // 2. بطاقات السرعة اللحظية واستهلاك اليوم
        item {
            TelemetrySummaryRow(
                totalDownloadBps = totalDownloadBps,
                totalUploadBps = totalUploadBps,
                unattributedDownloadBps = unattributedDownloadBps,
                unattributedUploadBps = unattributedUploadBps,
                todayTotalBytes = todayTotalBytes,
                todayWifiBytes = todayWifiBytes,
                todayCellularBytes = todayCellularBytes,
                isKernelTelemetryAvailable = isKernelTelemetryAvailable,
                bandwidthUnit = bandwidthUnit
            )
        }

        // 3. ملخص القواعد الفعالة وحالة الحصص
        item {
            RulesAndQuotasSummaryRow(
                rules = rules,
                quotas = quotas,
                isVpnRunning = diagnostics.isVpnRunning,
                activeTransport = activeTransport,
                failedRulePackages = diagnostics.failedRulePackages,
                onNavigateRules = { onNavigate(NetBalancerDestination.RULES) },
                onNavigateQuotas = { onNavigate(NetBalancerDestination.QUOTAS) }
            )
        }

        // 4. مخطط تدفق البيانات اللحظي
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = tr(lang, "حركة الشبكة المباشرة", "Live Network Traffic"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${timeFormatter.format(Date(lastUpdatedMillis))} • ${diagnostics.activeFlowsCount} ${tr(lang, "تدفق", "flows")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    LiveTrafficAreaChart(
                        samples = speedHistory,
                        bandwidthUnit = bandwidthUnit
                    )
                }
            }
        }

        // 5. أزرار الوصول السريع
        item {
            QuickActionsRow(onNavigate = onNavigate, onRunSelfTestClick = onRunSelfTestClick)
        }

        // 6. التطبيقات النشطة الآن (تظهر فقط التطبيقات ذات النشاط المؤكد)
        item {
            SectionHeader(
                title = tr(lang, "نشطة الآن (${activeApps.size})", "Active Now (${activeApps.size})"),
                actionLabel = tr(lang, "كل التطبيقات", "All Apps"),
                onActionClick = { onNavigate(NetBalancerDestination.APPLICATIONS) }
            )
        }

        if (activeApps.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isKernelTelemetryAvailable) {
                            tr(lang, "لا توجد تطبيقات تنقل بيانات في هذه اللحظة.", "No active app transfers right now.")
                        } else {
                            tr(lang, "بيانات الاستهلاك غير متاحة.", "Usage data unavailable.")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        } else {
            items(activeApps, key = { "active_${it.uid}" }) { app ->
                CompactAppTrafficCard(
                    app = app,
                    bandwidthUnit = bandwidthUnit,
                    maxBytesReference = topConsumers.firstOrNull()?.totalTodayBytes ?: 1L,
                    onClick = { onOpenAppDetails(app.uid) }
                )
            }
        }

        // 7. التطبيقات الأكثر استهلاكًا اليوم
        item {
            SectionHeader(
                title = tr(lang, "الأعلى استهلاكًا اليوم", "Top Usage Today"),
                actionLabel = tr(lang, "إدارة", "Manage"),
                onActionClick = { onNavigate(NetBalancerDestination.APPLICATIONS) }
            )
        }

        items(topConsumers, key = { "top_${it.uid}" }) { app ->
            CompactAppTrafficCard(
                app = app,
                bandwidthUnit = bandwidthUnit,
                maxBytesReference = topConsumers.firstOrNull()?.totalTodayBytes ?: 1L,
                onClick = { onOpenAppDetails(app.uid) }
            )
        }

        // 8. أحدث أحداث المحرك
        if (auditLogs.isNotEmpty()) {
            item {
                SectionHeader(
                    title = tr(lang, "أحدث الأحداث (${auditLogs.size})", "Recent Events (${auditLogs.size})"),
                    actionLabel = tr(lang, "السجل الكامل", "Full Log"),
                    onActionClick = { onNavigate(NetBalancerDestination.HISTORY) }
                )
            }

            items(auditLogs.take(3), key = { "dash_alert_${it.id}" }) { log ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigate(NetBalancerDestination.HISTORY) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = log.eventType.title(lang),
                                style = MaterialTheme.typography.labelLarge,
                                color = NetPrimaryBlue,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = log.summaryAr,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = timeFormatter.format(Date(log.timestampMillis)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RulesAndQuotasSummaryRow(
    rules: List<TrafficRule>,
    quotas: List<QuotaRule>,
    isVpnRunning: Boolean,
    activeTransport: ActiveNetworkTransport,
    failedRulePackages: Set<String>,
    onNavigateRules: () -> Unit,
    onNavigateQuotas: () -> Unit
) {
    val lang = LocalAppLanguage.current
    val enabledRules = rules.filter { it.isEnabled }
    val enforcedCount = enabledRules.count { rule ->
        val state = rule.evaluateEnforcementState(
            isVpnRunning = isVpnRunning,
            activeTransport = activeTransport,
            hasApplyError = failedRulePackages.contains(rule.packageName)
        )
        state == RuleEnforcementState.ENFORCED_IN_KERNEL_VPN || state == RuleEnforcementState.ACTIVE_MONITORED_RATE
    }
    val exceededQuotas = quotas.count { it.isLimitReached }
    val warningQuotas = quotas.count { it.isWarningReached && !it.isLimitReached }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .weight(1f)
                .clickable { onNavigateRules() }
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = tr(lang, "القواعد", "Rules"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = tr(
                        lang,
                        "$enforcedCount فعالة / ${enabledRules.size} محفوظة",
                        "$enforcedCount active / ${enabledRules.size} saved"
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (enforcedCount > 0) NetSuccessEmerald else NetPrimaryBlue
                )
                Text(
                    text = if (isVpnRunning) {
                        tr(lang, "تُطبق عبر نفق VPN", "Enforced via local VPN")
                    } else {
                        tr(lang, "شغّل المحرك لتنفيذها", "Start engine to enforce")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .weight(1f)
                .clickable { onNavigateQuotas() }
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = tr(lang, "الحصص", "Data Quotas"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = when {
                        exceededQuotas > 0 -> tr(lang, "$exceededQuotas متجاوزة", "$exceededQuotas exceeded")
                        warningQuotas > 0 -> tr(lang, "$warningQuotas قريبة من الحد", "$warningQuotas near limit")
                        quotas.isNotEmpty() -> tr(lang, "${quotas.size} ضمن الحد", "${quotas.size} within limit")
                        else -> tr(lang, "لا توجد حصص", "No quotas set")
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        exceededQuotas > 0 -> NetBlockCoral
                        warningQuotas > 0 -> NetWarningAmber
                        else -> NetDownloadTeal
                    }
                )
                Text(
                    text = tr(lang, "إجمالي الحصص: ${quotas.size}", "Monitored quotas: ${quotas.size}"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun EngineHeroCard(
    diagnostics: EngineDiagnostics,
    engineUiState: EngineUiState,
    activeTransport: ActiveNetworkTransport,
    lastUpdatedText: String,
    onToggleVpnClick: () -> Unit,
    onRunSelfTestClick: () -> Unit
) {
    val lang = LocalAppLanguage.current
    val statusColor = when (engineUiState) {
        EngineUiState.RUNNING -> NetSuccessEmerald
        EngineUiState.STOPPED -> NetPrimaryBlue
        EngineUiState.STARTING, EngineUiState.AWAITING_CONSENT -> NetUploadViolet
        EngineUiState.STOPPING -> NetBlockCoral
        EngineUiState.FAILED, EngineUiState.REVOKED_OR_LOST -> NetBlockCoral
    }

    val buttonContainerColor = when (engineUiState) {
        EngineUiState.RUNNING -> NetBlockCoral
        EngineUiState.STOPPED -> NetPrimaryBlue
        EngineUiState.STARTING, EngineUiState.AWAITING_CONSENT -> NetUploadViolet
        EngineUiState.STOPPING -> NetBlockCoral.copy(alpha = 0.8f)
        EngineUiState.FAILED, EngineUiState.REVOKED_OR_LOST -> MaterialTheme.colorScheme.error
    }

    val gradient = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.surface,
            statusColor.copy(alpha = 0.14f)
        )
    )

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, statusColor.copy(alpha = 0.45f), MaterialTheme.shapes.large)
    ) {
        Column(
            modifier = Modifier
                .background(gradient)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(statusColor.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (engineUiState) {
                            EngineUiState.RUNNING -> Icons.Default.VerifiedUser
                            EngineUiState.FAILED, EngineUiState.REVOKED_OR_LOST -> Icons.Default.Warning
                            else -> Icons.Default.Shield
                        },
                        contentDescription = engineUiState.title(lang),
                        tint = statusColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = engineUiState.title(lang),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = engineUiState.description(lang),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Button(
                onClick = onToggleVpnClick,
                enabled = engineUiState.canToggle,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonContainerColor,
                    contentColor = Color.White,
                    disabledContainerColor = buttonContainerColor.copy(alpha = 0.7f),
                    disabledContentColor = Color.White.copy(alpha = 0.9f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .testTag("toggle_vpn_button")
            ) {
                if (engineUiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = Color.White,
                        strokeWidth = 2.5.dp
                    )
                    Spacer(Modifier.width(10.dp))
                } else {
                    Icon(
                        imageVector = when (engineUiState) {
                            EngineUiState.RUNNING -> Icons.Default.Stop
                            EngineUiState.FAILED -> Icons.Default.Warning
                            EngineUiState.REVOKED_OR_LOST -> Icons.Default.Shield
                            else -> Icons.Default.PlayArrow
                        },
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = when (engineUiState) {
                        EngineUiState.RUNNING -> tr(lang, "إيقاف المحرك", "Stop Engine")
                        EngineUiState.STOPPED -> tr(lang, "تشغيل المحرك", "Start Engine")
                        EngineUiState.AWAITING_CONSENT -> tr(lang, "بانتظار إذن VPN...", "Awaiting VPN Permission...")
                        EngineUiState.STARTING -> tr(lang, "جاري التشغيل...", "Starting Engine...")
                        EngineUiState.STOPPING -> tr(lang, "جاري الإيقاف...", "Stopping Engine...")
                        EngineUiState.FAILED -> tr(lang, "إعادة المحاولة", "Retry Engine")
                        EngineUiState.REVOKED_OR_LOST -> tr(lang, "منح إذن VPN", "Grant VPN Permission")
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = if (activeTransport == ActiveNetworkTransport.CELLULAR) Icons.Default.CellTower else Icons.Default.Wifi,
                        contentDescription = activeTransport.title(lang),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "${activeTransport.title(lang)} • ${tr(lang, "تحديث", "Updated")}: $lastUpdatedText",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                FilledTonalButton(
                    onClick = onRunSelfTestClick,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier
                        .height(30.dp)
                        .testTag("self_test_button")
                ) {
                    Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(tr(lang, "فحص المسار", "Self-Test"), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun TelemetrySummaryRow(
    totalDownloadBps: Long,
    totalUploadBps: Long,
    unattributedDownloadBps: Long,
    unattributedUploadBps: Long,
    todayTotalBytes: Long,
    todayWifiBytes: Long,
    todayCellularBytes: Long,
    isKernelTelemetryAvailable: Boolean,
    bandwidthUnit: BandwidthUnit
) {
    val lang = LocalAppLanguage.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.weight(1f)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.ArrowDownward,
                            contentDescription = tr(lang, "تنزيل", "Download"),
                            tint = NetDownloadTeal,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = tr(lang, "سرعة التنزيل", "Download"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (isKernelTelemetryAvailable) {
                            UnitConverter.formatSpeed(totalDownloadBps, bandwidthUnit)
                        } else {
                            tr(lang, "البيانات غير متاحة", "Unavailable")
                        },
                        style = MaterialTheme.typography.titleLarge,
                        color = NetDownloadTeal,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.weight(1f)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.ArrowUpward,
                            contentDescription = tr(lang, "رفع", "Upload"),
                            tint = NetUploadViolet,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = tr(lang, "سرعة الرفع", "Upload"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (isKernelTelemetryAvailable) {
                            UnitConverter.formatSpeed(totalUploadBps, bandwidthUnit)
                        } else {
                            tr(lang, "البيانات غير متاحة", "Unavailable")
                        },
                        style = MaterialTheme.typography.titleLarge,
                        color = NetUploadViolet,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = tr(lang, "استهلاك اليوم", "Today's Usage"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = UnitConverter.formatVolume(todayTotalBytes),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Wi-Fi", style = MaterialTheme.typography.labelMedium, color = NetPrimaryBlue)
                            Text(
                                text = UnitConverter.formatVolume(todayWifiBytes),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = tr(lang, "الهاتف", "Cellular"),
                                style = MaterialTheme.typography.labelMedium,
                                color = NetUploadViolet
                            )
                            Text(
                                text = UnitConverter.formatVolume(todayCellularBytes),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
                if (unattributedDownloadBps > 0L || unattributedUploadBps > 0L) {
                    Text(
                        text = tr(
                            lang,
                            "حركة النظام: ↓ ${UnitConverter.formatSpeed(unattributedDownloadBps, bandwidthUnit)} • ↑ ${UnitConverter.formatSpeed(unattributedUploadBps, bandwidthUnit)}",
                            "OS/System traffic: ↓ ${UnitConverter.formatSpeed(unattributedDownloadBps, bandwidthUnit)} • ↑ ${UnitConverter.formatSpeed(unattributedUploadBps, bandwidthUnit)}"
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickActionsRow(
    onNavigate: (NetBalancerDestination) -> Unit,
    onRunSelfTestClick: () -> Unit
) {
    val lang = LocalAppLanguage.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        QuickActionChip(
            icon = Icons.Default.Apps,
            label = tr(lang, "التطبيقات", "Apps"),
            onClick = { onNavigate(NetBalancerDestination.APPLICATIONS) },
            modifier = Modifier.weight(1f)
        )
        QuickActionChip(
            icon = Icons.Default.Rule,
            label = tr(lang, "القواعد", "Rules"),
            onClick = { onNavigate(NetBalancerDestination.RULES) },
            modifier = Modifier.weight(1f)
        )
        QuickActionChip(
            icon = Icons.Default.DataUsage,
            label = tr(lang, "الحصص", "Quotas"),
            onClick = { onNavigate(NetBalancerDestination.QUOTAS) },
            modifier = Modifier.weight(1f)
        )
        QuickActionChip(
            icon = Icons.Default.NetworkCheck,
            label = tr(lang, "فحص", "Verify"),
            onClick = onRunSelfTestClick,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun QuickActionChip(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.clickable { onClick() }
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    actionLabel: String,
    onActionClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = actionLabel,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { onActionClick() }
        )
    }
}

@Composable
private fun CompactAppTrafficCard(
    app: AppNetworkProfile,
    bandwidthUnit: BandwidthUnit,
    maxBytesReference: Long,
    onClick: () -> Unit
) {
    val lang = LocalAppLanguage.current
    val preset = app.activeRule?.preset ?: PolicyPreset.ALLOW
    val ratio = (app.totalTodayBytes.toFloat() / maxBytesReference.coerceAtLeast(1L).toFloat()).coerceIn(0.02f, 1f)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AppPackageIcon(packageName = app.packageName, appName = app.appName, size = 38.dp)

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.appName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (app.dataAvailability == DataAvailabilityStatus.UNAVAILABLE) {
                            app.dataAvailability.label(lang)
                        } else {
                            UnitConverter.formatVolume(app.totalTodayBytes)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (app.dataAvailability == DataAvailabilityStatus.AVAILABLE) {
                    Column(horizontalAlignment = Alignment.End) {
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

                PolicyBadgeChip(preset = preset, compact = true)
            }

            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { ratio },
                color = if (preset == PolicyPreset.BLOCK) NetBlockCoral else NetPrimaryBlue,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
            )
        }
    }
}
