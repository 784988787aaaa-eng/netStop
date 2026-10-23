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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
            contentDescription = "أيقونة $appName",
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
                contentDescription = "أيقونة افتراضية لـ $appName",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(size * 0.6f)
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
                    Text("التنزيل", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(NetUploadViolet)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text("الرفع", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                text = "الذروة: ${UnitConverter.formatSpeed(maxSpeedBytes, bandwidthUnit)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(8.dp))

        val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.55f))
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                .padding(10.dp)
        ) {
            ComposeCanvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                // خطوط الشبكة الأفقية
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
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val (bgColor, fgColor, icon) = when (preset) {
        PolicyPreset.ALLOW -> Triple(NetSuccessEmerald.copy(alpha = 0.16f), NetSuccessEmerald, Icons.Default.CheckCircle)
        PolicyPreset.HIGH -> Triple(NetPrimaryBlue.copy(alpha = 0.18f), NetPrimaryBlue, Icons.Default.Speed)
        PolicyPreset.MEDIUM -> Triple(NetDownloadTeal.copy(alpha = 0.18f), NetDownloadTeal, Icons.Default.Speed)
        PolicyPreset.LOW -> Triple(NetWarningAmber.copy(alpha = 0.18f), NetWarningAmber, Icons.Default.Speed)
        PolicyPreset.CUSTOM -> Triple(NetUploadViolet.copy(alpha = 0.18f), NetUploadViolet, Icons.Default.Tune)
        PolicyPreset.BLOCK -> Triple(NetBlockCoral.copy(alpha = 0.20f), NetBlockCoral, Icons.Default.Block)
    }

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
                contentDescription = preset.titleAr,
                tint = fgColor,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = preset.titleAr,
                style = MaterialTheme.typography.labelMedium,
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
            text = status.badgeAr,
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
                    text = if (existingRule == null) "إضافة قاعدة تحكم شبكية" else "تعديل قاعدة التحكم",
                    style = MaterialTheme.typography.titleLarge
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
                Text("1. مستوى سياسة التحكم والسرعة:", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PolicyPreset.entries.forEach { preset ->
                        FilterChip(
                            selected = selectedPreset == preset,
                            onClick = { selectedPreset = preset },
                            label = { Text(preset.titleAr) },
                            modifier = Modifier.testTag("preset_chip_${preset.name}")
                        )
                    }
                }
                Text(
                    text = selectedPreset.descriptionAr,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (selectedPreset == PolicyPreset.CUSTOM) {
                    Text("وحدة قياس حد السرعة المخصص:", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BandwidthUnit.entries.forEach { unit ->
                            FilterChip(
                                selected = selectedUnit == unit,
                                onClick = { selectedUnit = unit },
                                label = { Text("${unit.symbolAr} (${unit.symbolEn})") }
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = downSpeedInput,
                            onValueChange = { downSpeedInput = it.filter { ch -> ch.isDigit() || ch == '.' } },
                            label = { Text("حد التنزيل (${selectedUnit.symbolEn})") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = upSpeedInput,
                            onValueChange = { upSpeedInput = it.filter { ch -> ch.isDigit() || ch == '.' } },
                            label = { Text("حد الرفع (${selectedUnit.symbolEn})") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Text("2. شرط نوع الشبكة:", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NetworkTypeCondition.entries.forEach { cond ->
                        FilterChip(
                            selected = selectedNetwork == cond,
                            onClick = { selectedNetwork = cond },
                            label = { Text(cond.titleAr) }
                        )
                    }
                }

                Text("3. مدة سريان القاعدة:", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RuleDurationMode.entries.forEach { dur ->
                        FilterChip(
                            selected = selectedDuration == dur,
                            onClick = { selectedDuration = dur },
                            label = { Text(dur.titleAr) }
                        )
                    }
                }

                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("4. أولوية القاعدة عند التعارض:", style = MaterialTheme.typography.labelLarge)
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
                    label = { Text("ملاحظة توثيقية (اختياري)") },
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
                Text("حفظ وتطبيق القاعدة")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("إلغاء")
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
                    text = if (existingQuota == null) "تعيين حصة استهلاك بيانات" else "تعديل حصة البيانات",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = targetApp?.appName ?: existingQuota?.appName ?: "حصة عامة للجهاز بالكامل",
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
                Text("1. دورة الحصة الزمنية:", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    QuotaPeriod.entries.forEach { period ->
                        FilterChip(
                            selected = selectedPeriod == period,
                            onClick = { selectedPeriod = period },
                            label = { Text(period.titleAr) }
                        )
                    }
                }

                if (selectedPeriod == QuotaPeriod.CUSTOM) {
                    OutlinedTextField(
                        value = customDaysInput,
                        onValueChange = { customDaysInput = it.filter { ch -> ch.isDigit() } },
                        label = { Text("عدد أيام الدورة المخصصة") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Text("2. حجم الحصة المسموح والوحدة:", style = MaterialTheme.typography.labelLarge)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = limitInput,
                        onValueChange = { limitInput = it.filter { ch -> ch.isDigit() || ch == '.' } },
                        label = { Text("الحد الأقصى (${selectedVolumeUnit.symbolEn})") },
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
                        Text("3. نسبة التنبيه المبكر قبل النفاد:", style = MaterialTheme.typography.labelLarge)
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

                Text("4. الإجراء التلقائي عند بلوغ الحد:", style = MaterialTheme.typography.labelLarge)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    QuotaLimitAction.entries.forEach { action ->
                        FilterChip(
                            selected = selectedAction == action,
                            onClick = { selectedAction = action },
                            label = { Text("${action.titleAr} — ${action.descriptionAr}") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Text("5. نوع الشبكة الخاضع للحصة:", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NetworkTypeCondition.entries.forEach { cond ->
                        FilterChip(
                            selected = selectedNetwork == cond,
                            onClick = { selectedNetwork = cond },
                            label = { Text(cond.titleAr) }
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
                Text("حفظ الحصة")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("إلغاء")
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
    val color = if (isDownload) NetDownloadTeal else NetUploadViolet
    val icon = if (isDownload) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = if (isDownload) "سرعة التنزيل" else "سرعة الرفع",
            tint = color,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = formattedSpeed,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold
        )
    }
}
