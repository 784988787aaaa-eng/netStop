package com.example.feature.common

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SignalCellularOff
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.core.common.UnitConverter
import com.example.core.i18n.LocalAppLanguage
import com.example.core.i18n.tr
import com.example.core.model.AppNetworkProfile
import com.example.core.model.BandwidthUnit
import com.example.core.model.ControlCapabilityStatus
import com.example.core.model.NetworkTypeCondition
import com.example.core.model.PolicyPreset
import com.example.core.model.QuotaLimitAction
import com.example.core.model.QuotaPeriod
import com.example.core.model.QuotaRule
import com.example.core.model.RuleDurationMode
import com.example.core.model.SpeedSamplePoint
import com.example.core.model.TrafficRule
import com.example.core.model.VolumeUnit
import com.example.ui.theme.NetBlockCoral
import com.example.ui.theme.NetDownloadTeal
import com.example.ui.theme.NetPrimaryBlue
import com.example.ui.theme.NetSuccessEmerald
import com.example.ui.theme.NetUploadViolet
import com.example.ui.theme.NetWarningAmber

/**
 * يعرض أيقونة التطبيق الحقيقية من PackageManager مع أيقونة بديلة أنيقة.
 */
@Composable
fun AppPackageIcon(
    packageName: String,
    appName: String,
    size: Dp = 42.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val iconBitmap: ImageBitmap? = remember(packageName) {
        runCatching {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            if (drawable is BitmapDrawable && drawable.bitmap != null) {
                drawable.bitmap.asImageBitmap()
            } else {
                val w = drawable.intrinsicWidth.coerceAtLeast(64)
                val h = drawable.intrinsicHeight.coerceAtLeast(64)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bmp.asImageBitmap()
            }
        }.getOrNull()
    }

    if (iconBitmap != null) {
        Image(
            bitmap = iconBitmap,
            contentDescription = appName,
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(10.dp))
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Android,
                contentDescription = appName,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(size * 0.6f)
            )
        }
    }
}

/**
 * شريط تنبيه واضح وغير مزعج عند توقف المحرك مع وجود قواعد محفوظة (مستلهم من شريط التنبيه في NetGuard).
 */
@Composable
fun EngineInactiveWarningBanner(
    isVpnRunning: Boolean,
    enabledRulesCount: Int,
    onStartEngineClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (isVpnRunning) return
    val lang = LocalAppLanguage.current

    Surface(
        color = NetWarningAmber.copy(alpha = 0.14f),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, NetWarningAmber.copy(alpha = 0.38f), RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = NetWarningAmber,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = if (enabledRulesCount > 0) {
                        tr(
                            lang,
                            "المحرك متوقف — $enabledRulesCount قاعدة محفوظة بانتظار التشغيل",
                            "Engine off — $enabledRulesCount saved rules waiting for activation"
                        )
                    } else {
                        tr(
                            lang,
                            "المحرك متوقف — شغّله لتفعيل الحظر وتحديد السرعة",
                            "Engine off — start it to enforce blocking and speed limits"
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                onClick = onStartEngineClick,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    text = tr(lang, "تشغيل", "Start"),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * نافذة التمهيد التوضيحية قبل طلب إذن VPN من النظام (مستلهمة من NetGuard لشرح أن VPN محلي 100%).
 */
@Composable
fun VpnConsentExplanationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val lang = LocalAppLanguage.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = null,
                tint = NetPrimaryBlue,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = tr(lang, "التحكم المحلي عبر خدمة VPN", "Local VPN Traffic Control"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = tr(
                        lang,
                        "يستخدم NetBalancer واجهة VPN المحلية في أندرويد لتصفية اتصالات التطبيقات وتحديد سرعتها داخل جهازك مباشرة دون الحاجة إلى صلاحية Root.",
                        "NetBalancer uses Android's built-in VpnService to filter app connections and enforce speed limits directly on your device without root."
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = tr(
                                lang,
                                "• معالجة محلية 100% داخل جهازك.\n• لا يتم إرسال حركتك إلى أي خادم خارجي.\n• ستظهر نافذة أندرويد الرسمية للموافقة على إنشاء الاتصال المحلي.",
                                "• 100% on-device packet processing.\n• Traffic is never routed to external servers.\n• Android will now ask you to approve the local VPN connection."
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                modifier = Modifier.testTag("confirm_vpn_intro_button")
            ) {
                Text(tr(lang, "موافق ومتابعة", "Continue"))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(tr(lang, "إلغاء", "Cancel"))
            }
        }
    )
}

/**
 * زر تبديل سريع بنقرة واحدة لشبكة Wi-Fi أو بيانات الهاتف في صف التطبيق (مستلهم من سرعة تفاعل NetGuard).
 */
@Composable
fun QuickNetworkToggleIconButton(
    isWifi: Boolean,
    isBlocked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lang = LocalAppLanguage.current
    val fgColor = if (isBlocked) NetBlockCoral else NetDownloadTeal
    val bgColor = if (isBlocked) NetBlockCoral.copy(alpha = 0.16f) else NetDownloadTeal.copy(alpha = 0.12f)
    val icon = when {
        isWifi && isBlocked -> Icons.Default.WifiOff
        isWifi && !isBlocked -> Icons.Default.Wifi
        !isWifi && isBlocked -> Icons.Default.SignalCellularOff
        else -> Icons.Default.CellTower
    }
    val contentDesc = if (isWifi) {
        if (isBlocked) tr(lang, "Wi-Fi محظور (اضغط للسماح)", "Wi-Fi blocked (tap to allow)")
        else tr(lang, "Wi-Fi مسموح (اضغط للحظر)", "Wi-Fi allowed (tap to block)")
    } else {
        if (isBlocked) tr(lang, "الهاتف محظور (اضغط للسماح)", "Cellular blocked (tap to allow)")
        else tr(lang, "الهاتف مسموح (اضغط للحظر)", "Cellular allowed (tap to block)")
    }

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
            .size(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDesc,
                tint = fgColor,
                modifier = Modifier.size(19.dp)
            )
        }
    }
}

/**
 * رسم بياني لحظي مزدوج المنحنى لسرعة التنزيل والرفع باستخدام Compose Canvas.
 */
@Composable
fun LiveTrafficAreaChart(
    samples: List<SpeedSamplePoint>,
    bandwidthUnit: BandwidthUnit,
    modifier: Modifier = Modifier
) {
    val lang = LocalAppLanguage.current
    val maxSpeedBytes = remember(samples) {
        val peak = samples.maxOfOrNull { maxOf(it.downloadBytesPerSec, it.uploadBytesPerSec) } ?: 0L
        peak.coerceAtLeast(64L * 1024L) // حد أدنى للمقياس 64 KB/s
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(NetDownloadTeal)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = tr(lang, "التنزيل", "Download"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(NetUploadViolet)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = tr(lang, "الرفع", "Upload"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = tr(
                    lang,
                    "الذروة: ${UnitConverter.formatSpeed(maxSpeedBytes, bandwidthUnit)}",
                    "Peak: ${UnitConverter.formatSpeed(maxSpeedBytes, bandwidthUnit)}"
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(8.dp))

        val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(136.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.55f))
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                .padding(10.dp)
        ) {
            ComposeCanvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                for (i in 1..3) {
                    val y = h * (i / 4f)
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y),
                        end = Offset(w, y),
                        strokeWidth = 1f
                    )
                }

                if (samples.size < 2) return@ComposeCanvas

                val stepX = w / (samples.size - 1).coerceAtLeast(1)

                val downPath = Path()
                val downAreaPath = Path()
                val upPath = Path()
                val upAreaPath = Path()

                samples.forEachIndexed { index, pt ->
                    val x = index * stepX
                    val downRatio = (pt.downloadBytesPerSec.toFloat() / maxSpeedBytes.toFloat()).coerceIn(0f, 1f)
                    val upRatio = (pt.uploadBytesPerSec.toFloat() / maxSpeedBytes.toFloat()).coerceIn(0f, 1f)
                    val downY = h - (downRatio * (h - 6f))
                    val upY = h - (upRatio * (h - 6f))

                    if (index == 0) {
                        downPath.moveTo(x, downY)
                        downAreaPath.moveTo(x, h)
                        downAreaPath.lineTo(x, downY)

                        upPath.moveTo(x, upY)
                        upAreaPath.moveTo(x, h)
                        upAreaPath.lineTo(x, upY)
                    } else {
                        downPath.lineTo(x, downY)
                        downAreaPath.lineTo(x, downY)

                        upPath.lineTo(x, upY)
                        upAreaPath.lineTo(x, upY)
                    }

                    if (index == samples.lastIndex) {
                        downAreaPath.lineTo(x, h)
                        downAreaPath.close()
                        upAreaPath.lineTo(x, h)
                        upAreaPath.close()
                    }
                }

                drawPath(
                    path = downAreaPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(NetDownloadTeal.copy(alpha = 0.30f), Color.Transparent)
                    )
                )
                drawPath(
                    path = upAreaPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(NetUploadViolet.copy(alpha = 0.25f), Color.Transparent)
                    )
                )

                drawPath(
                    path = downPath,
                    color = NetDownloadTeal,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                )
                drawPath(
                    path = upPath,
                    color = NetUploadViolet,
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
    }
}

/**
 * شارة السياسة المطبقة حاليًا على التطبيق.
 */
@Composable
fun PolicyBadgeChip(
    preset: PolicyPreset,
    compact: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val lang = LocalAppLanguage.current
    val (bgColor, fgColor, icon) = when (preset) {
        PolicyPreset.ALLOW -> Triple(NetSuccessEmerald.copy(alpha = 0.16f), NetSuccessEmerald, Icons.Default.CheckCircle)
        PolicyPreset.HIGH -> Triple(NetPrimaryBlue.copy(alpha = 0.18f), NetPrimaryBlue, Icons.Default.Speed)
        PolicyPreset.MEDIUM -> Triple(NetDownloadTeal.copy(alpha = 0.18f), NetDownloadTeal, Icons.Default.Speed)
        PolicyPreset.LOW -> Triple(NetWarningAmber.copy(alpha = 0.18f), NetWarningAmber, Icons.Default.Speed)
        PolicyPreset.CUSTOM -> Triple(NetUploadViolet.copy(alpha = 0.18f), NetUploadViolet, Icons.Default.Tune)
        PolicyPreset.BLOCK -> Triple(NetBlockCoral.copy(alpha = 0.20f), NetBlockCoral, Icons.Default.Block)
    }

    val labelText = if (compact) preset.shortLabel(lang) else preset.title(lang)

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = labelText,
                tint = fgColor,
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = labelText,
                style = MaterialTheme.typography.labelSmall,
                color = fgColor,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * شارة حالة إمكانية المراقبة والتحكم الفعلية (القسم السادس).
 */
@Composable
fun CapabilityStatusBadge(status: ControlCapabilityStatus) {
    val lang = LocalAppLanguage.current
    val (bg, fg) = when (status) {
        ControlCapabilityStatus.CONTROL_AVAILABLE -> NetSuccessEmerald.copy(alpha = 0.15f) to NetSuccessEmerald
        ControlCapabilityStatus.MONITORING_AVAILABLE -> NetPrimaryBlue.copy(alpha = 0.15f) to NetPrimaryBlue
        ControlCapabilityStatus.LIMITED_CONTROL -> NetWarningAmber.copy(alpha = 0.16f) to NetWarningAmber
        ControlCapabilityStatus.REQUIRES_PERMISSION -> NetWarningAmber.copy(alpha = 0.20f) to NetWarningAmber
        ControlCapabilityStatus.UNSUPPORTED_ON_DEVICE,
        ControlCapabilityStatus.UNATTRIBUTED_SOURCE -> NetBlockCoral.copy(alpha = 0.16f) to NetBlockCoral
    }

    Surface(
        color = bg,
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = status.badge(lang),
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/**
 * نافذة إنشاء أو تعديل قاعدة تحكم متكاملة (القسم الخامس والثامن).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RuleEditorDialog(
    app: AppNetworkProfile,
    existingRule: TrafficRule? = null,
    onDismiss: () -> Unit,
    onSave: (
        preset: PolicyPreset,
        customDownBps: Long,
        customUpBps: Long,
        networkCondition: NetworkTypeCondition,
        durationMode: RuleDurationMode,
        priority: Int,
        notes: String
    ) -> Unit
) {
    val lang = LocalAppLanguage.current
    var selectedPreset by remember { mutableStateOf(existingRule?.preset ?: PolicyPreset.MEDIUM) }
    var selectedUnit by remember { mutableStateOf(BandwidthUnit.KB_PER_SEC) }
    var downSpeedInput by remember {
        val initialBytes = existingRule?.maxDownloadBytesPerSec?.takeIf { it > 0 } ?: 256L * 1024L
        mutableStateOf((initialBytes / 1024L).toString())
    }
    var upSpeedInput by remember {
        val initialBytes = existingRule?.maxUploadBytesPerSec?.takeIf { it > 0 } ?: 128L * 1024L
        mutableStateOf((initialBytes / 1024L).toString())
    }
    var selectedNetwork by remember { mutableStateOf(existingRule?.networkCondition ?: NetworkTypeCondition.ANY) }
    var selectedDuration by remember { mutableStateOf(existingRule?.durationMode ?: RuleDurationMode.PERMANENT) }
    var prioritySlider by remember { mutableFloatStateOf((existingRule?.priority ?: 60).toFloat()) }
    var notesInput by remember { mutableStateOf(existingRule?.notes ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = if (existingRule == null) {
                        tr(lang, "إضافة قاعدة تحكم", "Add Traffic Rule")
                    } else {
                        tr(lang, "تعديل قاعدة التحكم", "Edit Traffic Rule")
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${app.appName} (UID ${app.uid})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = tr(lang, "1. السياسة ومستوى السرعة:", "1. Policy & Speed Preset:"),
                    style = MaterialTheme.typography.labelLarge
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PolicyPreset.entries.forEach { preset ->
                        FilterChip(
                            selected = selectedPreset == preset,
                            onClick = { selectedPreset = preset },
                            label = { Text(preset.title(lang)) },
                            modifier = Modifier.testTag("preset_chip_${preset.name}")
                        )
                    }
                }
                Text(
                    text = selectedPreset.description(lang),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (selectedPreset == PolicyPreset.CUSTOM) {
                    Text(
                        text = tr(lang, "وحدة السرعة:", "Speed Unit:"),
                        style = MaterialTheme.typography.labelLarge
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BandwidthUnit.entries.forEach { unit ->
                            FilterChip(
                                selected = selectedUnit == unit,
                                onClick = { selectedUnit = unit },
                                label = { Text("${unit.symbol(lang)} (${unit.symbolEn})") }
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = downSpeedInput,
                            onValueChange = { downSpeedInput = it.filter { ch -> ch.isDigit() || ch == '.' } },
                            label = {
                                Text(
                                    tr(
                                        lang,
                                        "حد التنزيل (${selectedUnit.symbolEn})",
                                        "Download (${selectedUnit.symbolEn})"
                                    )
                                )
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = upSpeedInput,
                            onValueChange = { upSpeedInput = it.filter { ch -> ch.isDigit() || ch == '.' } },
                            label = {
                                Text(
                                    tr(
                                        lang,
                                        "حد الرفع (${selectedUnit.symbolEn})",
                                        "Upload (${selectedUnit.symbolEn})"
                                    )
                                )
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Text(
                    text = tr(lang, "2. شرط الشبكة:", "2. Network Condition:"),
                    style = MaterialTheme.typography.labelLarge
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NetworkTypeCondition.entries.forEach { cond ->
                        FilterChip(
                            selected = selectedNetwork == cond,
                            onClick = { selectedNetwork = cond },
                            label = { Text(cond.title(lang)) }
                        )
                    }
                }

                Text(
                    text = tr(lang, "3. مدة السريان:", "3. Duration:"),
                    style = MaterialTheme.typography.labelLarge
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RuleDurationMode.entries.forEach { dur ->
                        FilterChip(
                            selected = selectedDuration == dur,
                            onClick = { selectedDuration = dur },
                            label = { Text(dur.title(lang)) }
                        )
                    }
                }

                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = tr(lang, "4. الأولوية:", "4. Priority:"),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = "${prioritySlider.toInt()} / 100",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = prioritySlider,
                        onValueChange = { prioritySlider = it },
                        valueRange = 1f..100f
                    )
                }

                OutlinedTextField(
                    value = notesInput,
                    onValueChange = { notesInput = it },
                    label = { tr(lang, "ملاحظة (اختياري)", "Note (optional)").let { Text(it) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val downVal = downSpeedInput.toDoubleOrNull() ?: 256.0
                    val upVal = upSpeedInput.toDoubleOrNull() ?: 128.0
                    val customDownBytes = UnitConverter.speedToBytesPerSecond(downVal, selectedUnit)
                    val customUpBytes = UnitConverter.speedToBytesPerSecond(upVal, selectedUnit)
                    onSave(
                        selectedPreset,
                        customDownBytes,
                        customUpBytes,
                        selectedNetwork,
                        selectedDuration,
                        prioritySlider.toInt(),
                        notesInput.trim()
                    )
                },
                modifier = Modifier.testTag("save_rule_confirm_button")
            ) {
                Text(tr(lang, "حفظ وتطبيق", "Save & Apply"))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(tr(lang, "إلغاء", "Cancel"))
            }
        }
    )
}

/**
 * نافذة إنشاء أو تعديل حصة بيانات (يومية / أسبوعية / شهرية / مخصصة).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuotaEditorDialog(
    targetApp: AppNetworkProfile?, // null تعني حصة عامة لجميع التطبيقات
    existingQuota: QuotaRule? = null,
    onDismiss: () -> Unit,
    onSave: (
        period: QuotaPeriod,
        limitBytes: Long,
        warningPercent: Int,
        actionOnLimit: QuotaLimitAction,
        networkCondition: NetworkTypeCondition,
        customDays: Int
    ) -> Unit
) {
    val lang = LocalAppLanguage.current
    var selectedPeriod by remember { mutableStateOf(existingQuota?.period ?: QuotaPeriod.DAILY) }
    var selectedVolumeUnit by remember { mutableStateOf(VolumeUnit.MB) }
    var limitInput by remember {
        val mb = ((existingQuota?.limitBytes ?: (500L * 1024L * 1024L)) / (1024L * 1024L)).coerceAtLeast(1L)
        mutableStateOf(mb.toString())
    }
    var warningSlider by remember { mutableFloatStateOf((existingQuota?.warningPercent ?: 80).toFloat()) }
    var selectedAction by remember { mutableStateOf(existingQuota?.actionOnLimit ?: QuotaLimitAction.THROTTLE_LOW) }
    var selectedNetwork by remember { mutableStateOf(existingQuota?.networkCondition ?: NetworkTypeCondition.ANY) }
    var customDaysInput by remember { mutableStateOf((existingQuota?.customDurationDays ?: 14).toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = if (existingQuota == null) {
                        tr(lang, "إعداد حصة بيانات", "Set Data Quota")
                    } else {
                        tr(lang, "تعديل حصة البيانات", "Edit Data Quota")
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = targetApp?.appName ?: existingQuota?.appName ?: tr(lang, "الجهاز بالكامل", "Entire Device"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = tr(lang, "1. دورة الحصة:", "1. Quota Period:"),
                    style = MaterialTheme.typography.labelLarge
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    QuotaPeriod.entries.forEach { period ->
                        FilterChip(
                            selected = selectedPeriod == period,
                            onClick = { selectedPeriod = period },
                            label = { Text(period.title(lang)) }
                        )
                    }
                }

                if (selectedPeriod == QuotaPeriod.CUSTOM) {
                    OutlinedTextField(
                        value = customDaysInput,
                        onValueChange = { customDaysInput = it.filter { ch -> ch.isDigit() } },
                        label = { Text(tr(lang, "عدد الأيام", "Duration (days)")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Text(
                    text = tr(lang, "2. الحد الأقصى والوحدة:", "2. Data Cap & Unit:"),
                    style = MaterialTheme.typography.labelLarge
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = limitInput,
                        onValueChange = { limitInput = it.filter { ch -> ch.isDigit() || ch == '.' } },
                        label = { Text("${tr(lang, "الحد", "Cap")} (${selectedVolumeUnit.symbolEn})") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    VolumeUnit.entries.forEach { unit ->
                        FilterChip(
                            selected = selectedVolumeUnit == unit,
                            onClick = { selectedVolumeUnit = unit },
                            label = { Text(unit.symbolEn) },
                            colors = FilterChipDefaults.filterChipColors()
                        )
                    }
                }

                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = tr(lang, "3. تنبيه مبكر عند:", "3. Early Warning Threshold:"),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = "${warningSlider.toInt()}%",
                            style = MaterialTheme.typography.labelLarge,
                            color = NetWarningAmber
                        )
                    }
                    Slider(
                        value = warningSlider,
                        onValueChange = { warningSlider = it },
                        valueRange = 50f..95f
                    )
                }

                Text(
                    text = tr(lang, "4. الإجراء عند بلوغ الحد:", "4. Action When Reached:"),
                    style = MaterialTheme.typography.labelLarge
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    QuotaLimitAction.entries.forEach { action ->
                        FilterChip(
                            selected = selectedAction == action,
                            onClick = { selectedAction = action },
                            label = { Text("${action.title(lang)} — ${action.description(lang)}") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Text(
                    text = tr(lang, "5. نوع الشبكة:", "5. Network Scope:"),
                    style = MaterialTheme.typography.labelLarge
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NetworkTypeCondition.entries.forEach { cond ->
                        FilterChip(
                            selected = selectedNetwork == cond,
                            onClick = { selectedNetwork = cond },
                            label = { Text(cond.title(lang)) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val value = limitInput.toDoubleOrNull() ?: 500.0
                    val bytes = UnitConverter.volumeToBytes(value, selectedVolumeUnit)
                    val customDays = customDaysInput.toIntOrNull()?.coerceIn(1, 365) ?: 14
                    onSave(
                        selectedPeriod,
                        bytes,
                        warningSlider.toInt(),
                        selectedAction,
                        selectedNetwork,
                        customDays
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.testTag("save_quota_confirm_button")
            ) {
                Text(tr(lang, "حفظ الحصة", "Save Quota"))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(tr(lang, "إلغاء", "Cancel"))
            }
        }
    )
}

@Composable
fun SpeedMetricPill(
    isDownload: Boolean,
    formattedSpeed: String,
    modifier: Modifier = Modifier
) {
    val lang = LocalAppLanguage.current
    val color = if (isDownload) NetDownloadTeal else NetUploadViolet
    val icon = if (isDownload) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = if (isDownload) tr(lang, "تنزيل", "Download") else tr(lang, "رفع", "Upload"),
            tint = color,
            modifier = Modifier.size(13.dp)
        )
        Text(
            text = formattedSpeed,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold
        )
    }
}
