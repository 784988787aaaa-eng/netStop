package com.example.feature.settings

import android.os.Build
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.example.core.i18n.AppLanguage
import com.example.core.i18n.LocalAppLanguage
import com.example.core.i18n.tr
import com.example.core.model.BandwidthUnit
import com.example.core.model.EngineDiagnostics
import com.example.data.preferences.UserSettingsState
import com.example.ui.theme.NetBlockCoral
import com.example.ui.theme.NetPrimaryBlue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * الشاشة السابعة: الإعدادات، اللغة (العربية / الإنجليزية)، النسخ الاحتياطي، والمعلومات التقنية.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: UserSettingsState,
    diagnostics: EngineDiagnostics,
    onDarkThemeChange: (Boolean) -> Unit,
    onAppLanguageChange: (AppLanguage) -> Unit = {},
    onBandwidthUnitChange: (BandwidthUnit) -> Unit,
    onSamplingIntervalChange: (Long) -> Unit,
    onNotificationsChange: (Boolean) -> Unit,
    onShowSystemAppsChange: (Boolean) -> Unit,
    onRetentionDaysChange: (Int) -> Unit,
    onRequestUsageAccess: () -> Unit,
    onClearAllData: () -> Unit,
    onExportBackupJson: () -> String = { "{}" },
    onImportBackupJson: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val lang = LocalAppLanguage.current
    var backupDialogMode by remember { mutableStateOf<String?>(null) } // "EXPORT" or "IMPORT"
    var backupJsonText by remember { mutableStateOf("") }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. اللغة، المظهر، ووحدات القياس
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Language, contentDescription = null, tint = NetPrimaryBlue)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = tr(lang, "اللغة والمظهر ووحدات القياس", "Language, Appearance & Units"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = tr(lang, "لغة التطبيق واتجاه الواجهة:", "App Language & Layout Direction:"),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = settings.appLanguage == AppLanguage.AR,
                            onClick = { onAppLanguageChange(AppLanguage.AR) },
                            label = { Text("العربية (RTL)") },
                            modifier = Modifier.testTag("language_chip_ar")
                        )
                        FilterChip(
                            selected = settings.appLanguage == AppLanguage.EN,
                            onClick = { onAppLanguageChange(AppLanguage.EN) },
                            label = { Text("English (LTR)") },
                            modifier = Modifier.testTag("language_chip_en")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = tr(lang, "المظهر الداكن التقني", "Technical Dark Theme"),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = tr(lang, "خلفية عالية التباين مريحة للعين", "High-contrast slate dark palette"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.isDarkTheme,
                            onCheckedChange = onDarkThemeChange,
                            modifier = Modifier.testTag("dark_theme_switch")
                        )
                    }

                    Text(
                        text = tr(lang, "وحدة عرض السرعة:", "Speed Display Unit:"),
                        style = MaterialTheme.typography.labelLarge
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BandwidthUnit.entries.forEach { unit ->
                            FilterChip(
                                selected = settings.bandwidthUnit == unit,
                                onClick = { onBandwidthUnitChange(unit) },
                                label = { Text("${unit.symbol(lang)} (${unit.symbolEn})") }
                            )
                        }
                    }

                    Text(
                        text = tr(lang, "فترة تحديث القراءات:", "Sampling Interval:"),
                        style = MaterialTheme.typography.labelLarge
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            500L to tr(lang, "0.5 ثانية (سريع)", "0.5s (Fast)"),
                            1000L to tr(lang, "1 ثانية (متوازن)", "1s (Balanced)"),
                            2000L to tr(lang, "2 ثانية (توفير)", "2s (Battery Saver)")
                        ).forEach { (ms, label) ->
                            FilterChip(
                                selected = settings.samplingIntervalMs == ms,
                                onClick = { onSamplingIntervalChange(ms) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }
        }

        // 2. الإشعارات، التطبيقات، والصلاحيات
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = tr(lang, "الإشعارات والتطبيقات والصلاحيات", "Notifications, Apps & Permissions"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = tr(lang, "تنبيهات الحصص والقيود", "Quota & Policy Alerts"),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = tr(lang, "إشعار عند اقتراب الحصة من الحد أو تطبيق الحظر", "Notify when a quota reaches warning or limit"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.notificationsEnabled,
                            onCheckedChange = onNotificationsChange
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = tr(lang, "إظهار تطبيقات النظام افتراضياً", "Show System Apps by Default"),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = tr(lang, "تضمين حزم أندرويد في قائمة التطبيقات", "Include Android system packages in app list"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.showSystemApps,
                            onCheckedChange = onShowSystemAppsChange
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = tr(lang, "إذن إحصاءات الاستخدام (Usage Access)", "Usage Access Permission"),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (diagnostics.isUsageAccessGranted) {
                                    tr(lang, "الحالة: ممنوح", "Status: Granted")
                                } else {
                                    tr(lang, "الحالة: غير ممنوح (اختياري)", "Status: Not granted (optional)")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(onClick = onRequestUsageAccess) {
                            Text(tr(lang, "الإذن", "Grant"))
                        }
                    }
                }
            }
        }

        // 3. النسخ الاحتياطي والخصوصية وإدارة السجل (مستلهم من NetGuard)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Security, contentDescription = null, tint = NetPrimaryBlue)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = tr(lang, "النسخ الاحتياطي والخصوصية", "Backup, Privacy & Data"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = tr(
                            lang,
                            "تُعالج جميع البيانات محلياً داخل جهازك بنسبة 100% دون أي خوادم خارجية.",
                            "100% on-device processing. No external servers or payload inspection."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                backupJsonText = onExportBackupJson()
                                backupDialogMode = "EXPORT"
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Backup, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(tr(lang, "تصدير القواعد", "Export Rules"))
                        }
                        OutlinedButton(
                            onClick = {
                                backupJsonText = ""
                                backupDialogMode = "IMPORT"
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(tr(lang, "استيراد القواعد", "Import Rules"))
                        }
                    }

                    Text(
                        text = tr(lang, "الاحتفاظ بسجل الأحداث:", "Event Log Retention:"),
                        style = MaterialTheme.typography.labelLarge
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            7 to tr(lang, "7 أيام", "7 Days"),
                            30 to tr(lang, "30 يوماً", "30 Days"),
                            90 to tr(lang, "90 يوماً", "90 Days")
                        ).forEach { (days, label) ->
                            FilterChip(
                                selected = settings.logRetentionDays == days,
                                onClick = { onRetentionDaysChange(days) },
                                label = { Text(label) }
                            )
                        }
                    }

                    Button(
                        onClick = onClearAllData,
                        colors = ButtonDefaults.buttonColors(containerColor = NetBlockCoral),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("clear_stats_button")
                    ) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(tr(lang, "مسح الإحصاءات والسجل", "Clear Statistics & Logs"))
                    }
                }
            }
        }

        // 4. المعلومات التقنية والتشخيص (مستلهم من شاشة المعلومات التقنية في NetGuard)
        item {
            val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
            val lastSyncStr = if (diagnostics.lastSyncTimestampMillis > 0L) {
                timeFormat.format(Date(diagnostics.lastSyncTimestampMillis))
            } else {
                tr(lang, "لم تتم بعد", "None")
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = tr(lang, "المعلومات التقنية وحالة المحرك", "Technical Info & Engine Diagnostics"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "NetBalancer v1.0 • Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) • ${Build.MODEL}",
                        style = MaterialTheme.typography.labelLarge,
                        color = NetPrimaryBlue
                    )
                    Text(
                        text = tr(
                            lang,
                            "• خدمة VPN: ${if (diagnostics.isVpnRunning) "نشطة (tun0)" else "متوقفة"} • الشبكة: ${diagnostics.activeTransport.title(lang)}\n" +
                                "• التدفقات النشطة: ${diagnostics.activeFlowsCount}/${diagnostics.maxFlowTableCapacity} • طابور السرعة: ${diagnostics.packetQueueDepth}/${diagnostics.maxPacketQueueCapacity}\n" +
                                "• الحزم المعالجة: ${diagnostics.totalPacketsProcessed} • المحظورة: ${diagnostics.droppedByPolicyCount}\n" +
                                "• الذاكرة: ~${diagnostics.memoryUsedMb} MB • زمن الدورة: ${diagnostics.averageLoopLatencyMicros} µs • آخر مزامنة: $lastSyncStr",
                            "• VPN Service: ${if (diagnostics.isVpnRunning) "Active (tun0)" else "Stopped"} • Network: ${diagnostics.activeTransport.title(lang)}\n" +
                                "• Active Flows: ${diagnostics.activeFlowsCount}/${diagnostics.maxFlowTableCapacity} • Rate Queue: ${diagnostics.packetQueueDepth}/${diagnostics.maxPacketQueueCapacity}\n" +
                                "• Packets Processed: ${diagnostics.totalPacketsProcessed} • Dropped: ${diagnostics.droppedByPolicyCount}\n" +
                                "• Memory: ~${diagnostics.memoryUsedMb} MB • Loop Latency: ${diagnostics.averageLoopLatencyMicros} µs • Last Sync: $lastSyncStr"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (backupDialogMode != null) {
        AlertDialog(
            onDismissRequest = { backupDialogMode = null },
            title = {
                Text(
                    if (backupDialogMode == "EXPORT") {
                        tr(lang, "تصدير القواعد والحصص (JSON)", "Export Rules & Quotas (JSON)")
                    } else {
                        tr(lang, "استيراد القواعد (JSON)", "Import Rules (JSON)")
                    }
                )
            },
            text = {
                OutlinedTextField(
                    value = backupJsonText,
                    onValueChange = { backupJsonText = it },
                    readOnly = backupDialogMode == "EXPORT",
                    placeholder = { Text("{\"rules\": [...]}") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                )
            },
            confirmButton = {
                if (backupDialogMode == "IMPORT") {
                    Button(
                        onClick = {
                            onImportBackupJson(backupJsonText)
                            backupDialogMode = null
                        }
                    ) {
                        Text(tr(lang, "استيراد", "Import"))
                    }
                } else {
                    Button(onClick = { backupDialogMode = null }) {
                        Text(tr(lang, "تم", "Done"))
                    }
                }
            },
            dismissButton = {
                if (backupDialogMode == "IMPORT") {
                    OutlinedButton(onClick = { backupDialogMode = null }) {
                        Text(tr(lang, "إلغاء", "Cancel"))
                    }
                }
            }
        )
    }
}
