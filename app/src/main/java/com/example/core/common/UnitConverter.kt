package com.example.core.common

import com.example.core.model.BandwidthUnit
import com.example.core.model.VolumeUnit
import java.util.Locale
import kotlin.math.roundToLong

/**
 * محول الوحدات الشبكية الدقيق (القسم الخامس).
 * يفصل بصرامة بين البت (bits: Kbps / Mbps) والبايت (Bytes: KB/s, MB/s, KB, MB, GB).
 *
 * القواعد القياسية المعتمدة:
 * - 1 Byte = 8 bits
 * - 1 Kbps = 1,000 bits/s = 125 Bytes/s
 * - 1 Mbps = 1,000,000 bits/s = 125,000 Bytes/s
 * - 1 KB/s = 1,024 Bytes/s = 8,192 bits/s
 * - 1 MB/s = 1,048,576 Bytes/s
 * - 1 KB = 1,024 Bytes | 1 MB = 1,048,576 Bytes | 1 GB = 1,073,741,824 Bytes
 */
object UnitConverter {

    fun speedToBytesPerSecond(value: Double, unit: BandwidthUnit): Long {
        if (value <= 0.0 || value.isNaN() || value.isInfinite()) return 0L
        return (value * unit.bytesPerSecondMultiplier).roundToLong()
    }

    fun bytesPerSecondToUnit(bytesPerSec: Long, unit: BandwidthUnit): Double {
        if (bytesPerSec <= 0L) return 0.0
        return bytesPerSec.toDouble() / unit.bytesPerSecondMultiplier
    }

    fun volumeToBytes(value: Double, unit: VolumeUnit): Long {
        if (value <= 0.0 || value.isNaN() || value.isInfinite()) return 0L
        return (value * unit.bytesMultiplier.toDouble()).roundToLong()
    }

    fun bytesToVolumeUnit(bytes: Long, unit: VolumeUnit): Double {
        if (bytes <= 0L) return 0.0
        return bytes.toDouble() / unit.bytesMultiplier.toDouble()
    }

    /**
     * تنسيق السرعة بالوحدة الثنائية (KiB/s = 1,024 B/s) بدقة رقمين عشريين عند الحاجة.
     * مثال: 10,000 B/s -> "9.77 KiB/s" | 10,240 B/s -> "10.00 KiB/s"
     */
    fun formatBinaryKiBPerSec(bytesPerSec: Long, decimals: Int = 2): String {
        if (bytesPerSec < 0L) return "Blocked"
        val kib = bytesPerSec.toDouble() / 1024.0
        return String.format(Locale.US, "%.${decimals}f KiB/s", kib)
    }

    /**
     * تنسيق السرعة بالوحدة العشرية (KB/s = 1,000 B/s).
     * مثال: 10,000 B/s -> "10.0 KB/s"
     */
    fun formatDecimalKBPerSec(bytesPerSec: Long, decimals: Int = 1): String {
        if (bytesPerSec < 0L) return "Blocked"
        val kb = bytesPerSec.toDouble() / 1000.0
        return String.format(Locale.US, "%.${decimals}f KB/s", kb)
    }

    /**
     * تنسيق السرعة اللحظية للعرض بالوحدة المفضلة للمستخدم أو تلقائيًا مع الحفاظ على اتجاه الأرقام في RTL.
     */
    fun formatSpeed(
        bytesPerSec: Long,
        preferredUnit: BandwidthUnit = BandwidthUnit.KB_PER_SEC,
        useArabicSymbols: Boolean = true
    ): String {
        if (bytesPerSec < 0L) return if (useArabicSymbols) "محظور" else "Blocked"
        if (bytesPerSec == 0L) {
            val zeroUnit = if (useArabicSymbols) preferredUnit.symbolAr else preferredUnit.symbolEn
            return "0 $zeroUnit"
        }

        if (preferredUnit == BandwidthUnit.DECIMAL_KB_PER_SEC) {
            val kbs = bytesPerSec.toDouble() / 1000.0
            val unitLabel = if (useArabicSymbols) BandwidthUnit.DECIMAL_KB_PER_SEC.symbolAr else BandwidthUnit.DECIMAL_KB_PER_SEC.symbolEn
            return String.format(Locale.US, "%.1f %s", kbs, unitLabel)
        }

        if (preferredUnit.isBits) {
            val bitsPerSec = bytesPerSec * 8.0
            return if (bitsPerSec >= 1_000_000.0 || preferredUnit == BandwidthUnit.MBPS && bitsPerSec >= 100_000.0) {
                val mbps = bitsPerSec / 1_000_000.0
                val unitLabel = if (useArabicSymbols) BandwidthUnit.MBPS.symbolAr else BandwidthUnit.MBPS.symbolEn
                String.format(Locale.US, "%.2f %s", mbps, unitLabel)
            } else {
                val kbps = bitsPerSec / 1_000.0
                val unitLabel = if (useArabicSymbols) BandwidthUnit.KBPS.symbolAr else BandwidthUnit.KBPS.symbolEn
                String.format(Locale.US, "%.1f %s", kbps, unitLabel)
            }
        } else {
            return if (bytesPerSec >= 1024L * 1024L || (preferredUnit == BandwidthUnit.MB_PER_SEC && bytesPerSec >= 100L * 1024L)) {
                val mbs = bytesPerSec.toDouble() / (1024.0 * 1024.0)
                val unitLabel = if (useArabicSymbols) BandwidthUnit.MB_PER_SEC.symbolAr else BandwidthUnit.MB_PER_SEC.symbolEn
                String.format(Locale.US, "%.2f %s", mbs, unitLabel)
            } else {
                val kbs = bytesPerSec.toDouble() / 1024.0
                val unitLabel = if (useArabicSymbols) BandwidthUnit.KB_PER_SEC.symbolAr else BandwidthUnit.KB_PER_SEC.symbolEn
                String.format(Locale.US, "%.1f %s", kbs, unitLabel)
            }
        }
    }

    /**
     * تنسيق حجم البيانات التراكمي (KiB / MiB / GiB) بدقة، مع رفض القيم السالبة (البيانات غير المتاحة).
     */
    fun formatVolume(bytes: Long, useArabicSymbols: Boolean = true): String {
        if (bytes < 0L) {
            return if (useArabicSymbols) "البيانات غير متاحة" else "Traffic unavailable"
        }
        if (bytes == 0L) {
            return if (useArabicSymbols) "0 كيلوبايت" else "0 KiB"
        }
        val gb = 1024.0 * 1024.0 * 1024.0
        val mb = 1024.0 * 1024.0
        val kb = 1024.0

        return when {
            bytes >= gb -> {
                val unit = if (useArabicSymbols) VolumeUnit.GB.symbolAr else VolumeUnit.GB.symbolEn
                String.format(Locale.US, "%.2f %s", bytes / gb, unit)
            }
            bytes >= mb -> {
                val unit = if (useArabicSymbols) VolumeUnit.MB.symbolAr else VolumeUnit.MB.symbolEn
                String.format(Locale.US, "%.1f %s", bytes / mb, unit)
            }
            else -> {
                val unit = if (useArabicSymbols) VolumeUnit.KB.symbolAr else VolumeUnit.KB.symbolEn
                String.format(Locale.US, "%.0f %s", (bytes / kb).coerceAtLeast(1.0), unit)
            }
        }
    }
}
