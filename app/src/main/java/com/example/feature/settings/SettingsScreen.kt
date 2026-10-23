package com.example.feature.settings

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.model.BandwidthUnit
import com.example.core.model.EngineDiagnostics
import com.example.data.preferences.UserSettingsState
import com.example.ui.theme.NetBlockCoral
import com.example.ui.theme.NetPrimaryBlue

/**
 * الشاشة السابعة: الإعدادات، الخصوصية، الصلاحيات، وتوثيق المحرك والقيود التقنية (Settings Screen - القسم الثامن والتاسع).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: UserSettingsState,
    diagnostics: EngineDiagnostics,
    onDarkThemeChange: (Boolean) -> Unit,
    onBandwidthUnitChange: (BandwidthUnit) -> Unit,
    onSamplingIntervalChange: (Long) -> Unit,
    onNotificationsChange: (Boolean) -> Unit,
    onShowSystemAppsChange: (Boolean) -> Unit,
    onRetentionDaysChange: (Int) -> Unit,
    onRequestUsageAccess: () -> Unit,
    onClearAllData: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. المظهر واللغة ووحدات القياس
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
                    Text("المظهر، اللغة، ووحدات القياس", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("الوضع الداكن التقني (#0B1020)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text("تبديل بين المظهر الداكن الاحترافي والمظهر الفاتح", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = settings.isDarkTheme,
                            onCheckedChange = onDarkThemeChange,
                            modifier = Modifier.testTag("dark_theme_switch")
                        )
                    }

                    Text("وحدة عرض السرعة المفضلة (تفصل بين البت والبايت):", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BandwidthUnit.entries.forEach { unit ->
                            FilterChip(
                                selected = settings.bandwidthUnit == unit,
                                onClick = { onBandwidthUnitChange(unit) },
                                label = { Text("${unit.symbolAr} (${unit.symbolEn})") }
                            )
                        }
                    }

                    Text("فترة تحديث القياسات اللحظية:", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(500L to "500 مللي ثانية (فائق)", 1000L to "1 ثانية (متوازن)", 2000L to "2 ثانية (توفير بطارية)").forEach { (ms, label) ->
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

        // 2. إدارة الإشعارات وصلاحيات النظام الاختيارية
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
                    Text("الإشعارات، التطبيقات، والصلاحيات", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("تنبيهات حصص البيانات والقيود", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text("إرسال إشعار عند اقتراب الحصة من النفاد أو تطبيق إجراء الحظر", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            Text("إظهار تطبيقات النظام افتراضيًا", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text("عرض خدمات وحزم أندرويد المسموح باكتشافها ضمن قائمة التطبيقات", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            Text("صلاحية إحصاءات الاستخدام (Usage Access)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = if (diagnostics.isUsageAccessGranted) "الحالة: ممنوحة ومفعلة" else "الحالة: غير ممنوحة (اختيارية لتعزيز دقة السجل التاريخي)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(onClick = onRequestUsageAccess) {
                            Text("إعدادات الإذن")
                        }
                    }
                }
            }
        }

        // 3. الخصوصية والاحتفاظ بالسجل ومسح الإحصاءات
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
                        Text("الخصوصية، التخزين المحلي، والاحتفاظ بالسجل", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }

                    Text(
                        text = "• تُعالج جميع الحزم والإحصاءات محليًا داخل جهازك بنسبة 100%.\n• لا يقرأ التطبيق محتوى الاتصالات (Payloads) ولا يرسل أسماء التطبيقات أو سجل النشاط إلى أي خادم خارجي.\n• لا تُخزن الحزم الفردية في قاعدة البيانات، بل تُحفظ المجاميع والأحداث المهمة فقط.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text("فترة الاحتفاظ بسجل الأحداث:", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(7 to "7 أيام", 30 to "30 يومًا", 90 to "90 يومًا").forEach { (days, label) ->
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
                        Text("مسح جميع الإحصاءات وسجل الأحداث")
                    }
                }
            }
        }

        // 4. معلومات المحرك والقيود التقنية الموثقة (Section 4 & 14)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("معلومات المحرك والقيود التقنية في أندرويد", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        text = "الإصدار: NetBalancer v1.0.0 (البنية المعيارية Kotlin + Compose + Room + VpnService)",
                        style = MaterialTheme.typography.labelLarge,
                        color = NetPrimaryBlue
                    )
                    Text(
                        text = "• سعة جدول التدفقات (FlowTracker): ${diagnostics.activeFlowsCount} / ${diagnostics.maxFlowTableCapacity} تدفق\n" +
                            "• عمق طابور محدد السرعة (TokenBucket): ${diagnostics.packetQueueDepth} / ${diagnostics.maxPacketQueueCapacity} حزمة\n" +
                            "• إجمالي الحزم المفحوصة: ${diagnostics.totalPacketsProcessed} • المسقطة بالسياسة: ${diagnostics.droppedByPolicyCount}\n" +
                            "• الذاكرة المستخدمة: ~${diagnostics.memoryUsedMb} MB • زمن دورة المسار: ${diagnostics.averageLoopLatencyMicros} µs",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "القيود التقنية الموثقة لنظام Android (بدون صلاحية Root):",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "1. يسمح نظام أندرويد بنشاط خدمة VPN واحدة فقط في الوقت نفسه.\n" +
                            "2. حزم النظام الداخلية ذات المعرف UID < 10000 (مثل نواة النظام وخدمة DNS الأساسية) قد تتجاوز مسار VPN لحماية استقرار الجهاز.\n" +
                            "3. التطبيقات التي تشترك في نفس `sharedUserId` تشترك في UID واحد على مستوى النواة، لذا تطبق سياسة الحظر أو السرعة على مستوى UID المشترك.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
