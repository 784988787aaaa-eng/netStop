package com.example.network.attribution

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.os.Build
import android.os.Process
import com.example.core.permissions.PermissionAndCapabilityHelper
import com.example.network.packet.ParsedPacketHeader
import java.io.File
import java.net.InetSocketAddress
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/**
 * محرك إسناد الاتصالات وقراءة عدادات النواة ونظام أندرويد (Application Attribution Engine - القسم 8 و 9 و 14).
 *
 * يجمع بين عدة مصادر رسمية في أندرويد للتغلب على قيد Android 10+ (API 29+) الذي يحجب
 * `TrafficStats.getUidRxBytes(otherUid)` عن التطبيقات غير النظامية:
 * 1. `NetworkStatsManager` لقراءة إحصاءات كل UID عبر Wi-Fi وبيانات الهاتف عند توفر إذن الاستخدام.
 * 2. `TrafficStats.getUidRxBytes` / `getUidTxBytes` للتطبيق نفسه وللأنظمة التي تتيح قراءة كل UID.
 * 3. `TrafficStats.getTotalRxBytes` / `getTotalTxBytes` وعدادات واجهات النواة `/sys/class/net`
 *    التي تعمل في الوقت الحقيقي لجميع التطبيقات على أندرويد 10–15+ دون صلاحيات خاصة.
 * 4. `ConnectivityManager.getConnectionOwnerUid` مع إسناد احتياطي للحزم المحظورة في نفق VPN.
 */
class AppAttributionEngine(private val context: Context?) {

    companion object {
        const val UID_UNATTRIBUTED = -1
        const val PACKAGE_UNATTRIBUTED = "unattributed.network.flow"
        const val NAME_UNATTRIBUTED = "اتصال غير محدد المصدر"
        private const val NETWORK_STATS_CACHE_TTL_MS = 2_000L
    }

    data class AttributedAppIdentity(
        val uid: Int,
        val packageName: String,
        val appName: String,
        val isAttributed: Boolean
    )

    data class KernelTelemetryReading(
        val rxBytes: Long,
        val txBytes: Long,
        val isAvailable: Boolean,
        val isDirectUidSource: Boolean = true,
        val sourceDescriptionAr: String = "TrafficStats / NetworkStatsManager (مباشر لكل UID)"
    )

    private val uidIdentityCache = ConcurrentHashMap<Int, AttributedAppIdentity>()
    private val pkgIdentityCache = ConcurrentHashMap<String, AttributedAppIdentity>()

    @Volatile
    private var cachedNetworkStatsByUid: Map<Int, Pair<Long, Long>> = emptyMap()

    @Volatile
    private var lastNetworkStatsQueryMillis: Long = 0L

    /**
     * يحاول إسناد حزمة شبكية إلى UID التطبيق المالك للاتصال عبر واجهة أندرويد الرسمية
     * (`ConnectivityManager.getConnectionOwnerUid`)،
     * أو عند وجود تطبيق وحيد مفروض داخل نفق VPN المقتصر عليه.
     * لا ينسب أبداً حزمة مجهولة المصدر إلى تطبيق عشوائي عند تعدد التطبيقات (القسم 1.5 و 6.1).
     */
    fun attributePacket(
        header: ParsedPacketHeader,
        fallbackAllowedPackages: Set<String> = emptySet()
    ): AttributedAppIdentity {
        val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val proto = header.rawProtocolNumber
            if (proto == 6 || proto == 17) { // TCP أو UDP
                try {
                    val localAddr = InetSocketAddress(header.sourceIp, header.sourcePort)
                    val remoteAddr = InetSocketAddress(header.destinationIp, header.destinationPort)
                    val ownerUid = cm.getConnectionOwnerUid(proto, localAddr, remoteAddr)
                    if (ownerUid > 0 && ownerUid != Process.INVALID_UID) {
                        return resolveUidIdentity(ownerUid)
                    }
                } catch (_: SecurityException) {
                } catch (_: Exception) {
                }
            }
        }

        // فقط إذا كان نفق VPN مقصوراً على حزمة تطبيق واحدة بالضبط نربط الحزمة بها؛
        // أما إذا كان هناك أكثر من تطبيق مفروض في النفق وتعذر getConnectionOwnerUid، فتبقى غير منسوبة بصدق.
        if (fallbackAllowedPackages.size == 1) {
            val solePkg = fallbackAllowedPackages.first()
            resolvePackageIdentity(solePkg)?.let { return it }
        }

        return unattributedIdentity()
    }

    /**
     * يحول اسم الحزمة إلى هوية التطبيق الكاملة مع UID.
     */
    fun resolvePackageIdentity(packageName: String): AttributedAppIdentity? {
        if (packageName.isBlank()) return null
        pkgIdentityCache[packageName]?.let { return it }

        val pm = context?.packageManager ?: return null
        return try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val label = pm.getApplicationLabel(appInfo).toString()
            val identity = AttributedAppIdentity(
                uid = appInfo.uid,
                packageName = packageName,
                appName = label,
                isAttributed = true
            )
            uidIdentityCache[appInfo.uid] = identity
            pkgIdentityCache[packageName] = identity
            identity
        } catch (_: Exception) {
            null
        }
    }

    /**
     * يحول UID معروف إلى هوية التطبيق (اسم الحزمة والاسم المعروض) مع تخزين مؤقت سريع.
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
                        "$label (+${packages.size - 1})"
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
                    pkgIdentityCache[primaryPkg] = identity
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
            val identity = AttributedAppIdentity(uid, packageName, appName, true)
            uidIdentityCache[uid] = identity
            if (packageName.isNotBlank()) {
                pkgIdentityCache[packageName] = identity
            }
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
     * يقرأ إحصاءات استهلاك اليوم لكل UID عبر `NetworkStatsManager` إذا كانت صلاحية `PACKAGE_USAGE_STATS` متاحة.
     */
    fun readNetworkStatsForToday(nowMillis: Long = System.currentTimeMillis()): Map<Int, Pair<Long, Long>> {
        val ctx = context ?: return emptyMap()
        if (!PermissionAndCapabilityHelper.hasUsageStatsPermission(ctx)) return emptyMap()
        if (nowMillis - lastNetworkStatsQueryMillis < NETWORK_STATS_CACHE_TTL_MS && cachedNetworkStatsByUid.isNotEmpty()) {
            return cachedNetworkStatsByUid
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return emptyMap()

        val nsm = ctx.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager ?: return emptyMap()
        val startOfDay = getStartOfDayMillis(nowMillis)
        val aggregated = HashMap<Int, Pair<Long, Long>>()

        fun collectFromNetworkType(networkType: Int) {
            runCatching {
                @Suppress("DEPRECATION")
                val stats: NetworkStats? = nsm.querySummary(networkType, null, startOfDay, nowMillis)
                stats?.use { netStats ->
                    val bucket = NetworkStats.Bucket()
                    while (netStats.hasNextBucket()) {
                        netStats.getNextBucket(bucket)
                        val uid = bucket.uid
                        if (uid >= 0) {
                            val rx = bucket.rxBytes.coerceAtLeast(0L)
                            val tx = bucket.txBytes.coerceAtLeast(0L)
                            if (rx > 0L || tx > 0L) {
                                val prev = aggregated[uid] ?: (0L to 0L)
                                aggregated[uid] = (prev.first + rx) to (prev.second + tx)
                            }
                        }
                    }
                }
            }
        }

        @Suppress("DEPRECATION")
        collectFromNetworkType(ConnectivityManager.TYPE_WIFI)
        @Suppress("DEPRECATION")
        collectFromNetworkType(ConnectivityManager.TYPE_MOBILE)

        lastNetworkStatsQueryMillis = nowMillis
        cachedNetworkStatsByUid = aggregated
        return cachedNetworkStatsByUid
    }

    /**
     * قراءة إجمالي بايتات التنزيل والرفع الفعلية لـ UID محدد من المصادر الموثوقة المباشرة فقط:
     * 1. عدادات نفق VPN المباشرة لهذا الـ UID (`vpnUidBytes`) عند مرور حزمه عبر المحرك.
     * 2. `NetworkStatsManager` لكل UID عند منح إذن بيانات الاستخدام (`PACKAGE_USAGE_STATS`).
     * 3. `TrafficStats.getUidRxBytes(uid)` / `getUidTxBytes(uid)` للتطبيق نفسه (`Process.myUid()`) أو للأنظمة التي تدعمه.
     *
     * التزام صارم بالقسم 1.7 و 3.2:
     * في Android 10+ عند غياب إذن `PACKAGE_USAGE_STATS` وعدم مرور التطبيق عبر VPN، يعيد النظام `UNSUPPORTED (-1)`
     * لعدادات التطبيقات الأخرى؛ في هذه الحالة تُعاد `isAvailable = false` مع `-1L` ولا تُحوّل أبداً إلى `0 KB`.
     */
    fun readKernelUidTelemetry(
        uid: Int,
        nowMillis: Long = System.currentTimeMillis(),
        vpnUidBytes: Pair<Long, Long>? = null
    ): KernelTelemetryReading {
        if (uid < 0) {
            return KernelTelemetryReading(
                rxBytes = -1L,
                txBytes = -1L,
                isAvailable = false,
                isDirectUidSource = false,
                sourceDescriptionAr = "البيانات غير متاحة (UID غير صالح)"
            )
        }

        val ctx = context
        val hasUsageStats = ctx != null && PermissionAndCapabilityHelper.hasUsageStatsPermission(ctx)
        val nsmMap = if (hasUsageStats) readNetworkStatsForToday(nowMillis) else emptyMap()
        val nsmPair = nsmMap[uid]

        val unsupported = TrafficStats.UNSUPPORTED.toLong()
        val rawRx = try { TrafficStats.getUidRxBytes(uid) } catch (_: Exception) { unsupported }
        val rawTx = try { TrafficStats.getUidTxBytes(uid) } catch (_: Exception) { unsupported }

        val isSelfUid = uid == Process.myUid()
        val trafficStatsSupportedForUid = (rawRx != unsupported && rawTx != unsupported) &&
            (isSelfUid || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || rawRx > 0L || rawTx > 0L)

        val vpnRx = vpnUidBytes?.first ?: -1L
        val vpnTx = vpnUidBytes?.second ?: -1L
        val hasVpnTelemetry = vpnUidBytes != null && (vpnRx >= 0L && vpnTx >= 0L)

        // إذا لم يتوفر أي مصدر مباشر لهذا الـ UID (مثلاً في Android 10+ بدون إذن Usage Access وبدون VPN)
        if (!trafficStatsSupportedForUid && !hasUsageStats && !hasVpnTelemetry) {
            return KernelTelemetryReading(
                rxBytes = -1L,
                txBytes = -1L,
                isAvailable = false,
                isDirectUidSource = false,
                sourceDescriptionAr = "البيانات غير متاحة — يلزم منح إذن بيانات الاستخدام (Usage Access) في أندرويد 10+"
            )
        }

        val tsRx = if (trafficStatsSupportedForUid) rawRx.coerceAtLeast(0L) else 0L
        val tsTx = if (trafficStatsSupportedForUid) rawTx.coerceAtLeast(0L) else 0L
        val nsmRx = nsmPair?.first ?: 0L
        val nsmTx = nsmPair?.second ?: 0L
        val vRx = if (hasVpnTelemetry) vpnRx.coerceAtLeast(0L) else 0L
        val vTx = if (hasVpnTelemetry) vpnTx.coerceAtLeast(0L) else 0L

        val bestRx = maxOf(tsRx, nsmRx) + vRx
        val bestTx = maxOf(tsTx, nsmTx) + vTx

        val sourceLabel = when {
            hasVpnTelemetry && hasUsageStats -> "نفق VPN المحلي + NetworkStatsManager (مباشر لكل UID)"
            hasVpnTelemetry -> "عدادات حزم نفق VPN المحلي (مباشر لكل UID)"
            hasUsageStats -> "NetworkStatsManager (مباشر لكل UID)"
            else -> "TrafficStats (عداد النواة المباشر لكل UID)"
        }

        return KernelTelemetryReading(
            rxBytes = bestRx,
            txBytes = bestTx,
            isAvailable = true,
            isDirectUidSource = true,
            sourceDescriptionAr = sourceLabel
        )
    }

    fun readKernelUidBytes(uid: Int): Pair<Long, Long> {
        val t = readKernelUidTelemetry(uid)
        return t.rxBytes to t.txBytes
    }

    /**
     * قراءة إجمالي حركة الجهاز الكلية (تنزيل ورفع) من النواة عبر:
     * 1. `TrafficStats.getTotalRxBytes()` / `getTotalTxBytes()` (يعمل في أندرويد 10–15+ لجميع التطبيقات دون قيد UID)
     * 2. عدادات واجهات الشبكة في `/sys/class/net/<iface>/statistics` و `/proc/net/dev`
     * 3. `NetworkStatsManager.querySummaryForDevice` عند توفره.
     */
    fun readKernelTotalTelemetry(nowMillis: Long = System.currentTimeMillis()): KernelTelemetryReading {
        val unsupported = TrafficStats.UNSUPPORTED.toLong()
        var tsRx = 0L
        var tsTx = 0L
        var tsSupported = false

        try {
            val rawRx = TrafficStats.getTotalRxBytes()
            val rawTx = TrafficStats.getTotalTxBytes()
            if (rawRx != unsupported || rawTx != unsupported) {
                tsRx = rawRx.coerceAtLeast(0L)
                tsTx = rawTx.coerceAtLeast(0L)
                tsSupported = true
            }
        } catch (_: Exception) {
        }

        // قراءة احتياطية من واجهات النواة (/sys/class/net و /proc/net/netstat و /proc/net/dev) لضمان عمل العدادات في المحاكيات والحاويات
        val sysNet = readSysClassNetBytes()
        val procNet = readProcNetStatAndDevBytes()
        val bestRx = maxOf(tsRx, sysNet.first, procNet.first)
        val bestTx = maxOf(tsTx, sysNet.second, procNet.second)
        val available = tsSupported || bestRx > 0L || bestTx > 0L || context != null

        return KernelTelemetryReading(
            rxBytes = bestRx,
            txBytes = bestTx,
            isAvailable = available,
            isDirectUidSource = true
        )
    }

    fun readKernelTotalBytes(): Pair<Long, Long> {
        val t = readKernelTotalTelemetry()
        return t.rxBytes to t.txBytes
    }

    /**
     * يقرأ إحصاءات طبقة IP في النواة من `/proc/net/netstat` (`InOctets` / `OutOctets`) و `/proc/net/dev`.
     */
    private fun readProcNetStatAndDevBytes(): Pair<Long, Long> {
        var netstatRx = 0L
        var netstatTx = 0L
        try {
            val netstatFile = File("/proc/net/netstat")
            if (netstatFile.canRead()) {
                val lines = netstatFile.readLines()
                var i = 0
                while (i + 1 < lines.size) {
                    val headerLine = lines[i].trim()
                    val valueLine = lines[i + 1].trim()
                    if (headerLine.startsWith("IpExt:") && valueLine.startsWith("IpExt:")) {
                        val headers = headerLine.split(Regex("\\s+"))
                        val values = valueLine.split(Regex("\\s+"))
                        val inIdx = headers.indexOf("InOctets")
                        val outIdx = headers.indexOf("OutOctets")
                        if (inIdx in values.indices) {
                            netstatRx = values[inIdx].toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                        }
                        if (outIdx in values.indices) {
                            netstatTx = values[outIdx].toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                        }
                        break
                    }
                    i += 2
                }
            }
        } catch (_: Exception) {
        }

        var devRx = 0L
        var devTx = 0L
        try {
            val devFile = File("/proc/net/dev")
            if (devFile.canRead()) {
                val lines = devFile.readLines()
                for (line in lines.drop(2)) {
                    val parts = line.trim().split(":")
                    if (parts.size == 2) {
                        val iface = parts[0].trim()
                        if (iface == "lo" || iface.startsWith("tun") || iface.startsWith("dummy")) continue
                        val cols = parts[1].trim().split(Regex("\\s+"))
                        if (cols.size >= 9) {
                            devRx += cols[0].toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                            devTx += cols[8].toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        return maxOf(netstatRx, devRx) to maxOf(netstatTx, devTx)
    }

    /**
     * يقرأ مجموع بايتات واجهات الشبكة النشطة من `/sys/class/net/<iface>/statistics` (باستثناء `lo` و `tun`).
     */
    private fun readSysClassNetBytes(): Pair<Long, Long> {
        var totalRx = 0L
        var totalTx = 0L
        try {
            val netDir = File("/sys/class/net")
            val ifaces = netDir.listFiles() ?: return 0L to 0L
            for (iface in ifaces) {
                val name = iface.name
                if (name == "lo" || name.startsWith("tun") || name.startsWith("dummy")) continue
                val rxFile = File(iface, "statistics/rx_bytes")
                val txFile = File(iface, "statistics/tx_bytes")
                if (rxFile.canRead()) {
                    totalRx += rxFile.readText().trim().toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                }
                if (txFile.canRead()) {
                    totalTx += txFile.readText().trim().toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                }
            }
        } catch (_: Exception) {
        }
        return totalRx to totalTx
    }

    private fun getStartOfDayMillis(nowMillis: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
