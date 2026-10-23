package com.example.feature.history

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.common.UnitConverter
import com.example.core.model.AppNetworkProfile
import com.example.core.model.AuditEventType
import com.example.core.model.AuditLogItem
import com.example.feature.common.AppPackageIcon
import com.example.ui.theme.NetBlockCoral
import com.example.ui.theme.NetDownloadTeal
import com.example.ui.theme.NetPrimaryBlue
import com.example.ui.theme.NetUploadViolet
import com.example.ui.theme.NetWarningAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * الشاشة السادسة: سجل الاستهلاك والأحداث ومحاولات الاتصال المحظورة (History Screen - القسم الثامن).
 */
@Composable
fun HistoryScreen(
    apps: List<AppNetworkProfile>,
    auditLogs: List<AuditLogItem>,
    selectedFilter: AuditEventType?,
    onSelectFilter: (AuditEventType?) -> Unit,
    modifier: Modifier = Modifier
) {
    val dateFormatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }
    val topApps = remember(apps) { apps.sortedByDescending { it.totalTodayBytes }.take(5) }
    val maxAppBytes = topApps.firstOrNull()?.totalTodayBytes?.coerceAtLeast(1L) ?: 1L

    val filteredLogs = remember(auditLogs, selectedFilter) {
        if (selectedFilter == null) auditLogs else auditLogs.filter { it.eventType == selectedFilter }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("history_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. ملخص أعلى التطبيقات استهلاكًا وتوزيع الشبكة
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
                    Text(
                        text = "التطبيقات الأعلى استهلاكًا في السجل التراكمي",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    topApps.forEach { app ->
                        val ratio = (app.totalTodayBytes.toFloat() / maxAppBytes.toFloat()).coerceIn(0.03f, 1f)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    AppPackageIcon(packageName = app.packageName, appName = app.appName, size = 28.dp)
                                    Text(app.appName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                }
                                Text(
                                    text = UnitConverter.formatVolume(app.totalTodayBytes),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = NetDownloadTeal
                                )
                            }
                            LinearProgressIndicator(
                                progress = { ratio },
                                color = NetPrimaryBlue,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(3.dp))
                            )
                        }
                    }
                }
            }
        }

        // 2. شرائح تصفية السجل الزمني للأحداث
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "السجل الزمني للتغييرات والأحداث (${filteredLogs.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = selectedFilter == null,
                        onClick = { onSelectFilter(null) },
                        label = { Text("الكل") }
                    )
                    AuditEventType.entries.forEach { eventType ->
                        FilterChip(
                            selected = selectedFilter == eventType,
                            onClick = { onSelectFilter(eventType) },
                            label = { Text(eventType.titleAr) }
                        )
                    }
                }
            }
        }

        if (filteredLogs.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "لا توجد أحداث مسجلة تطابق التصفية المختارة حاليًا.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(18.dp)
                    )
                }
            }
        } else {
            items(filteredLogs, key = { it.id }) { log ->
                AuditEventRowCard(log = log, formattedDate = dateFormatter.format(Date(log.timestampMillis)))
            }
        }
    }
}

@Composable
private fun AuditEventRowCard(
    log: AuditLogItem,
    formattedDate: String
) {
    val badgeColor = when (log.eventType) {
        AuditEventType.CONNECTION_BLOCKED, AuditEventType.QUOTA_EXCEEDED -> NetBlockCoral
        AuditEventType.QUOTA_WARNING, AuditEventType.CONFLICT_RESOLVED, AuditEventType.ERROR_RECOVERY -> NetWarningAmber
        AuditEventType.SPEED_THROTTLED, AuditEventType.RULE_APPLIED -> NetUploadViolet
        else -> NetPrimaryBlue
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = badgeColor.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = log.eventType.titleAr,
                        style = MaterialTheme.typography.labelMedium,
                        color = badgeColor,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Text(
                    text = formattedDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = log.summaryAr,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (log.technicalDetails.isNotBlank()) {
                Text(
                    text = log.technicalDetails,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(2.dp))
            Text(
                text = "التطبيق: ${log.appName} • الشبكة: ${log.networkTransport.titleAr}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
