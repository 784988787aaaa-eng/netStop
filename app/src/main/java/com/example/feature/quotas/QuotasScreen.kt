package com.example.feature.quotas

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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import com.example.core.i18n.LocalAppLanguage
import com.example.core.i18n.tr
import com.example.core.model.AppNetworkProfile
import com.example.core.model.NetworkTypeCondition
import com.example.core.model.QuotaLimitAction
import com.example.core.model.QuotaPeriod
import com.example.core.model.QuotaRule
import com.example.feature.common.AppPackageIcon
import com.example.feature.common.QuotaEditorDialog
import com.example.ui.theme.NetBlockCoral
import com.example.ui.theme.NetDownloadTeal
import com.example.ui.theme.NetPrimaryBlue
import com.example.ui.theme.NetWarningAmber

/**
 * الشاشة الخامسة: إدارة حصص البيانات (Quotas Screen).
 */
@Composable
fun QuotasScreen(
    quotas: List<QuotaRule>,
    apps: List<AppNetworkProfile>,
    onSaveQuota: (
        uid: Int,
        packageName: String,
        appName: String,
        period: QuotaPeriod,
        limitBytes: Long,
        warningPercent: Int,
        actionOnLimit: QuotaLimitAction,
        networkCondition: NetworkTypeCondition,
        customDays: Int,
        existingId: Long,
        currentUsedBytes: Long
    ) -> Unit,
    onResetQuota: (QuotaRule) -> Unit,
    onDeleteQuota: (QuotaRule) -> Unit,
    onSwitchToHistory: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val lang = LocalAppLanguage.current
    var showTargetPicker by remember { mutableStateOf(false) }
    var targetAppForDialog by remember { mutableStateOf<AppNetworkProfile?>(null) }
    var isGlobalTarget by remember { mutableStateOf(false) }
    var editingQuota by remember { mutableStateOf<QuotaRule?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize().testTag("quotas_screen"),
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showTargetPicker = true },
                containerColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("add_quota_fab")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Add, contentDescription = tr(lang, "إضافة حصة", "Add Quota"))
                    Spacer(Modifier.width(6.dp))
                    Text(tr(lang, "إضافة حصة", "Add Quota"), style = MaterialTheme.typography.labelLarge)
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
            if (onSwitchToHistory != null) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = false,
                            onClick = onSwitchToHistory,
                            label = { Text(tr(lang, "الاستهلاك والسجل", "Usage & Log")) },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = true,
                            onClick = {},
                            label = { Text(tr(lang, "حصص البيانات (${quotas.size})", "Data Quotas (${quotas.size})")) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

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
                            tint = NetDownloadTeal,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = tr(
                                lang,
                                "تُرصد الحصص كل ثانية مع تنبيه مبكر وإجراء تلقائي (تقييد السرعة أو الحظر) عند بلوغ السقف.",
                                "Quotas are sampled every second with early warning and automatic throttling or blocking at the cap."
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (quotas.isEmpty()) {
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
                                text = tr(lang, "لا توجد حصص بيانات مضافة", "No Data Quotas Set"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = tr(
                                    lang,
                                    "حدد سقف استهلاك يومي أو أسبوعي أو شهري للجهاز أو لتطبيق معين.",
                                    "Set a daily, weekly, or monthly data cap for the device or any app."
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            Button(onClick = { showTargetPicker = true }) {
                                Text(tr(lang, "إضافة حصة", "Add Quota"))
                            }
                        }
                    }
                }
            } else {
                items(quotas, key = { it.id }) { quota ->
                    QuotaItemCard(
                        quota = quota,
                        onReset = { onResetQuota(quota) },
                        onEdit = {
                            editingQuota = quota
                            isGlobalTarget = quota.uid == -1
                            targetAppForDialog = apps.firstOrNull { it.uid == quota.uid }
                        },
                        onDelete = { onDeleteQuota(quota) }
                    )
                }
            }
        }
    }

    if (showTargetPicker) {
        AlertDialog(
            onDismissRequest = { showTargetPicker = false },
            title = { Text(tr(lang, "اختر نطاق الحصة", "Select Quota Target")) },
            text = {
                LazyColumn(
                    modifier = Modifier.height(360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showTargetPicker = false
                                    editingQuota = null
                                    targetAppForDialog = null
                                    isGlobalTarget = true
                                }
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = tr(lang, "الجهاز بالكامل", "Entire Device"),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = tr(lang, "مراقبة إجمالي استهلاك جميع التطبيقات", "Monitor combined usage across all apps"),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }

                    items(apps, key = { it.uid }) { app ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showTargetPicker = false
                                    editingQuota = null
                                    isGlobalTarget = false
                                    targetAppForDialog = app
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
                                        text = "${tr(lang, "اليوم", "Today")}: ${UnitConverter.formatVolume(app.totalTodayBytes)}",
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
                OutlinedButton(onClick = { showTargetPicker = false }) {
                    Text(tr(lang, "إلغاء", "Cancel"))
                }
            }
        )
    }

    if (isGlobalTarget || targetAppForDialog != null || editingQuota != null) {
        QuotaEditorDialog(
            targetApp = targetAppForDialog,
            existingQuota = editingQuota,
            onDismiss = {
                isGlobalTarget = false
                targetAppForDialog = null
                editingQuota = null
            },
            onSave = { period, limitBytes, warnPct, action, netCond, customDays ->
                val uid = editingQuota?.uid ?: targetAppForDialog?.uid ?: -1
                val pkg = editingQuota?.packageName ?: targetAppForDialog?.packageName ?: "global.device.quota"
                val name = editingQuota?.appName ?: targetAppForDialog?.appName ?: tr(lang, "الجهاز بالكامل", "Entire Device")
                val currentUsed = editingQuota?.usedBytes ?: targetAppForDialog?.totalTodayBytes ?: 0L

                onSaveQuota(
                    uid,
                    pkg,
                    name,
                    period,
                    limitBytes,
                    warnPct,
                    action,
                    netCond,
                    customDays,
                    editingQuota?.id ?: 0L,
                    currentUsed
                )
                isGlobalTarget = false
                targetAppForDialog = null
                editingQuota = null
            }
        )
    }
}

@Composable
private fun QuotaItemCard(
    quota: QuotaRule,
    onReset: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val lang = LocalAppLanguage.current
    val barColor = when {
        quota.isLimitReached -> NetBlockCoral
        quota.isWarningReached -> NetWarningAmber
        else -> NetDownloadTeal
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
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    if (quota.uid >= 0) {
                        AppPackageIcon(packageName = quota.packageName, appName = quota.appName, size = 36.dp)
                    }
                    Column {
                        Text(
                            text = quota.appName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${quota.period.title(lang)} • ${quota.networkCondition.title(lang)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    color = barColor.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "${quota.usagePercent}%",
                        style = MaterialTheme.typography.labelLarge,
                        color = barColor,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            LinearProgressIndicator(
                progress = { quota.usageRatio.coerceIn(0f, 1f) },
                color = barColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${UnitConverter.formatVolume(quota.usedBytes)} / ${UnitConverter.formatVolume(quota.limitBytes)} • ${quota.actionOnLimit.title(lang)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )

                Row {
                    IconButton(onClick = onReset, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = tr(lang, "تصفير", "Reset"), tint = NetDownloadTeal, modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = tr(lang, "تعديل", "Edit"), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = tr(lang, "حذف", "Delete"), tint = NetBlockCoral, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}
