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
import com.example.core.model.EngineUiState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.common.UnitConverter
import com.example.core.model.ActiveNetworkTransport
import com.example.core.model.AppNetworkProfile
import com.example.core.model.BandwidthUnit
import com.example.core.model.EngineDiagnostics
import com.example.core.model.PolicyPreset
import com.example.core.model.SpeedSamplePoint
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

/**
 * الشاشة الأولى: لوحة التحكم الرئيسية (Dashboard Screen - القسم الثامن).
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
    modifier: Modifier = Modifier
) {
    val activeApps = apps.filter { it.isCurrentlyActive }.take(5)
    val topConsumers = apps.sortedByDescending { it.totalTodayBytes }.take(5)

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("dashboard_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. بطاقة حالة محرك الشبكة و VPN
        item {
            EngineHeroCard(
                diagnostics = diagnostics,
                engineUiState = engineUiState,
                activeTransport = activeTransport,
                onToggleVpnClick = onToggleVpnClick,
                onRunSelfTestClick = onRunSelfTestClick
            )
        }

        // 2. بطاقات سرعة التنزيل والرفع الإجمالية واستهلاك اليوم
        item {
            TelemetrySummaryRow(
                totalDownloadBps = totalDownloadBps,
                totalUploadBps = totalUploadBps,
                todayTotalBytes = todayTotalBytes,
                todayWifiBytes = todayWifiBytes,
                todayCellularBytes = todayCellularBytes,
                bandwidthUnit = bandwidthUnit
            )
        }

        // 3. الرسم البياني اللحظي لحركة الشبكة
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
                            text = "مخطط تدفق البيانات اللحظي",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "تحديث مباشر • ${diagnostics.activeFlowsCount} تدفق",
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

        // 4. أزرار الوصول السريع
        item {
            QuickActionsRow(onNavigate = onNavigate, onRunSelfTestClick = onRunSelfTestClick)
        }

        // 5. التطبيقات النشطة شبكيًا الآن
        item {
            SectionHeader(
                title = "التطبيقات النشطة في اللحظة الحالية (${activeApps.size})",
                actionLabel = "عرض الكل",
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
                        text = "لا توجد تدفقات عالية السرعة في هذه الثانية — يتم رصد أي نشاط فور حدوثه.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
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

        // 6. التطبيقات الأكثر استهلاكًا اليوم
        item {
            SectionHeader(
                title = "التطبيقات الأكثر استهلاكًا للبيانات",
                actionLabel = "إدارة التطبيقات",
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
    }
}

@Composable
private fun EngineHeroCard(
    diagnostics: EngineDiagnostics,
    engineUiState: EngineUiState,
    activeTransport: ActiveNetworkTransport,
    onToggleVpnClick: () -> Unit,
    onRunSelfTestClick: () -> Unit
) {
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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // شريط العنوان والحالة والوصف
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
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
                        contentDescription = "حالة محرك VPN",
                        tint = statusColor,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = engineUiState.titleAr,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = engineUiState.descriptionAr,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // الزر الرئيسي البارز بارتفاع 56dp+ وعرض كامل
            Button(
                onClick = onToggleVpnClick,
                enabled = engineUiState.canToggle,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonContainerColor,
                    contentColor = androidx.compose.ui.graphics.Color.White,
                    disabledContainerColor = buttonContainerColor.copy(alpha = 0.7f),
                    disabledContentColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.9f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .testTag("toggle_vpn_button")
            ) {
                if (engineUiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = androidx.compose.ui.graphics.Color.White,
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
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    text = when (engineUiState) {
                        EngineUiState.RUNNING -> "إيقاف محرك التحكم"
                        EngineUiState.STOPPED -> "تشغيل محرك NetBalancer"
                        EngineUiState.AWAITING_CONSENT -> "بانتظار موافقة النظام (VPN)..."
                        EngineUiState.STARTING -> "جاري إطلاق المحرك المحلي..."
                        EngineUiState.STOPPING -> "جاري إيقاف المحرك بأمان..."
                        EngineUiState.FAILED -> "إعادة محاولة تشغيل المحرك"
                        EngineUiState.REVOKED_OR_LOST -> "إعادة طلب موافقة VPN"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            // سطر مؤشرات التشخيص السريعة
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
                        contentDescription = activeTransport.titleAr,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "${activeTransport.titleAr} • MTU ${diagnostics.mtuSize} • IPv4/IPv6",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = "${diagnostics.activeFlowsCount} تدفق • ${diagnostics.droppedByPolicyCount} محجوب",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // شريط فحص المسار الذاتي
            Surface(
                color = MaterialTheme.colorScheme.background.copy(alpha = 0.65f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = diagnostics.lastSelfTestResultAr,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(
                        onClick = onRunSelfTestClick,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.testTag("self_test_button")
                    ) {
                        Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("فحص المسار", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetrySummaryRow(
    totalDownloadBps: Long,
    totalUploadBps: Long,
    todayTotalBytes: Long,
    todayWifiBytes: Long,
    todayCellularBytes: Long,
    bandwidthUnit: BandwidthUnit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // بطاقة سرعة التنزيل
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.weight(1f)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.ArrowDownward,
                            contentDescription = "تنزيل",
                            tint = NetDownloadTeal,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "سرعة التنزيل الإجمالية",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = UnitConverter.formatSpeed(totalDownloadBps, bandwidthUnit),
                        style = MaterialTheme.typography.titleLarge,
                        color = NetDownloadTeal,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // بطاقة سرعة الرفع
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.weight(1f)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.ArrowUpward,
                            contentDescription = "رفع",
                            tint = NetUploadViolet,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "سرعة الرفع الإجمالية",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = UnitConverter.formatSpeed(totalUploadBps, bandwidthUnit),
                        style = MaterialTheme.typography.titleLarge,
                        color = NetUploadViolet,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // بطاقة استهلاك اليوم (موزع بين Wi-Fi وبيانات الهاتف)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "إجمالي استهلاك البيانات المرصود",
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
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Wi-Fi", style = MaterialTheme.typography.labelMedium, color = NetPrimaryBlue)
                        Text(
                            text = UnitConverter.formatVolume(todayWifiBytes),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("بيانات الهاتف", style = MaterialTheme.typography.labelMedium, color = NetUploadViolet)
                        Text(
                            text = UnitConverter.formatVolume(todayCellularBytes),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        QuickActionChip(
            icon = Icons.Default.Apps,
            label = "التطبيقات",
            onClick = { onNavigate(NetBalancerDestination.APPLICATIONS) },
            modifier = Modifier.weight(1f)
        )
        QuickActionChip(
            icon = Icons.Default.Rule,
            label = "القواعد",
            onClick = { onNavigate(NetBalancerDestination.RULES) },
            modifier = Modifier.weight(1f)
        )
        QuickActionChip(
            icon = Icons.Default.DataUsage,
            label = "الحصص",
            onClick = { onNavigate(NetBalancerDestination.QUOTAS) },
            modifier = Modifier.weight(1f)
        )
        QuickActionChip(
            icon = Icons.Default.NetworkCheck,
            label = "فحص المحرك",
            onClick = onRunSelfTestClick,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun QuickActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
                        text = "استهلاك: ${UnitConverter.formatVolume(app.totalTodayBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

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

                PolicyBadgeChip(preset = preset)
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
