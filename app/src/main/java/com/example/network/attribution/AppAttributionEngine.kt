package com.example.network.attribution

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.os.Build
import android.os.Process
import com.example.network.packet.ParsedPacketHeader
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * محرك إسناد الاتصالات إلى التطبيقات (Application Attribution Engine - القسم الرابع والسادس).
 *
 * المبادئ الهندسية:
 * 1. يستخدم `ConnectivityManager.getConnectionOwnerUid` الرسمي في أندرويد 10+ (API 29+) عند عمل VpnService.
 * 2. لا يختلق هوية تطبيق إذا تعذر الإسناد؛ بل يعيد `UID_UNATTRIBUTED = -1` ("غير محدد").
 * 3. يقرأ عدادات النواة الفعلية لكل UID عبر `TrafficStats.getUidRxBytes` و `getUidTxBytes`.
 */
class AppAttributionEngine(private val context: Context?) {

    companion object {
        const val UID_UNATTRIBUTED = -1
        const val PACKAGE_UNATTRIBUTED = "unattributed.network.flow"
        const val NAME_UNATTRIBUTED = "اتصال غير محدد المصدر"
    }

    data class AttributedAppIdentity(
        val uid: Int,
        val packageName: String,
        val appName: String,
        val isAttributed: Boolean
    )

    private val uidIdentityCache = ConcurrentHashMap<Int, AttributedAppIdentity>()

    /**
     * يحاول إسناد حزمة شبكية إلى UID التطبيق المالك للاتصال عبر واجهة أندرويد الرسمية.
     */
    fun attributePacket(header: ParsedPacketHeader): AttributedAppIdentity {
        val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val proto = header.rawProtocolNumber
            if (proto == 6 || proto == 17) { // TCP أو UDP فقط مدعومان في getConnectionOwnerUid
                try {
                    val localAddr = InetSocketAddress(header.sourceIp, header.sourcePort)
                    val remoteAddr = InetSocketAddress(header.destinationIp, header.destinationPort)
                    val ownerUid = cm.getConnectionOwnerUid(proto, localAddr, remoteAddr)
                    if (ownerUid > 0 && ownerUid != Process.INVALID_UID) {
                        return resolveUidIdentity(ownerUid)
                    }
                } catch (_: SecurityException) {
                    // يرمى إذا لم يكن التطبيق هو خدمة VPN النشطة حاليًا
                } catch (_: Exception) {
                }
            }
        }
        return unattributedIdentity()
    }

    /**
     * يحول UID معروف إلى هوية التطبيق (اسم الحزمة والاسم العربي/المعروض) مع تخزين مؤقت سريع.
     */
    fun resolveUidIdentity(uid: Int): AttributedAppIdentity {
        if (uid < 0 || uid == Process.INVALID_UID) return unattributedIdentity()

        uidIdentityCache[uid]?.let { return it }

        val pm = context?.packageManager
        if (pm != null) {
            try {
                val packages = pm.getPackagesForUid(uid)
                if (!packages.isNullOrEmpty()) {
                    val primaryPkg = packages.first()
                    val appInfo = pm.getApplicationInfo(primaryPkg, 0)
                    val label = pm.getApplicationLabel(appInfo).toString()
                    val displayLabel = if (packages.size > 1) {
                        "$label (+${packages.size - 1} مشترك)"
                    } else {
                        label
                    }
                    val identity = AttributedAppIdentity(
                        uid = uid,
                        packageName = primaryPkg,
                        appName = displayLabel,
                        isAttributed = true
                    )
                    uidIdentityCache[uid] = identity
                    return identity
                }
            } catch (_: PackageManager.NameNotFoundException) {
            } catch (_: Exception) {
            }
        }

        val fallback = when (uid) {
            0 -> AttributedAppIdentity(0, "android.kernel.root", "نواة النظام (Root/Kernel)", true)
            1000 -> AttributedAppIdentity(1000, "android.system", "نظام أندرويد الأساسي", true)
            1051 -> AttributedAppIdentity(1051, "android.netd.dns", "خدمة DNS والاتصال (netd)", true)
            else -> AttributedAppIdentity(uid, "uid.$uid", "تطبيق نظام (UID $uid)", true)
        }
        uidIdentityCache[uid] = fallback
        return fallback
    }

    fun registerKnownIdentity(uid: Int, packageName: String, appName: String) {
        if (uid >= 0) {
            uidIdentityCache[uid] = AttributedAppIdentity(uid, packageName, appName, true)
        }
    }

    fun unattributedIdentity(): AttributedAppIdentity {
        return AttributedAppIdentity(
            uid = UID_UNATTRIBUTED,
            packageName = PACKAGE_UNATTRIBUTED,
            appName = NAME_UNATTRIBUTED,
            isAttributed = false
        )
    }

    /**
     * قراءة إجمالي بايتات التنزيل والرفع الفعلية من نواة أندرويد لـ UID محدد.
     */
    fun readKernelUidBytes(uid: Int): Pair<Long, Long> {
        if (uid < 0) return 0L to 0L
        return try {
            val rx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L)
            val tx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L)
            rx to tx
        } catch (e: Exception) {
            0L to 0L
        }
    }

    /**
     * قراءة إجمالي حركة الجهاز الكلية (تنزيل ورفع) من النواة.
     */
    fun readKernelTotalBytes(): Pair<Long, Long> {
        return try {
            val rx = TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
            val tx = TrafficStats.getTotalTxBytes().coerceAtLeast(0L)
            rx to tx
        } catch (e: Exception) {
            0L to 0L
        }
    }
}
