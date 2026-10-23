package com.example.core.model

/**
 * وحدات قياس سرعة النقل (تفرق بدقة بين البت bit والبايت Byte).
 * 1 Byte = 8 bits
 * Kbps = 1,000 bits/sec | Mbps = 1,000,000 bits/sec
 * KB/s = 1,024 Bytes/sec | MB/s = 1,048,576 Bytes/sec
 */
enum class BandwidthUnit(
    val symbolAr: String,
    val symbolEn: String,
    val isBits: Boolean,
    val bytesPerSecondMultiplier: Double
) {
    KBPS("كيلوبت/ث", "Kbps", true, 1000.0 / 8.0),
    MBPS("ميجابت/ث", "Mbps", true, 1_000_000.0 / 8.0),
    KB_PER_SEC("كيلوبايت/ث", "KB/s", false, 1024.0),
    MB_PER_SEC("ميجابايت/ث", "MB/s", false, 1024.0 * 1024.0);
}

/**
 * وحدات قياس حجم البيانات التراكمي (بالبايت).
 */
enum class VolumeUnit(
    val symbolAr: String,
    val symbolEn: String,
    val bytesMultiplier: Long
) {
    KB("كيلوبايت", "KB", 1024L),
    MB("ميجابايت", "MB", 1024L * 1024L),
    GB("جيجابايت", "GB", 1024L * 1024L * 1024L);
}

/**
 * مستويات سياسة السرعة والتحكم لكل تطبيق مع قيم افتراضية موثقة وقابلة للتخصيص.
 */
enum class PolicyPreset(
    val titleAr: String,
    val descriptionAr: String,
    val defaultDownloadBytesPerSec: Long, // 0 = غير محدود، -1 = محظور
    val defaultUploadBytesPerSec: Long
) {
    ALLOW(
        titleAr = "سماح كامل",
        descriptionAr = "اتصال مباشر بأقصى سرعة متاحة للشبكة دون تقييد",
        defaultDownloadBytesPerSec = 0L,
        defaultUploadBytesPerSec = 0L
    ),
    HIGH(
        titleAr = "سرعة مرتفعة",
        descriptionAr = "تنزيل 640 KB/s (5.12 Mbps) • رفع 256 KB/s (2.05 Mbps)",
        defaultDownloadBytesPerSec = 640L * 1024L,
        defaultUploadBytesPerSec = 256L * 1024L
    ),
    MEDIUM(
        titleAr = "سرعة متوسطة",
        descriptionAr = "تنزيل 160 KB/s (1.28 Mbps) • رفع 64 KB/s (512 Kbps)",
        defaultDownloadBytesPerSec = 160L * 1024L,
        defaultUploadBytesPerSec = 64L * 1024L
    ),
    LOW(
        titleAr = "سرعة منخفضة",
        descriptionAr = "تنزيل 32 KB/s (256 Kbps) • رفع 16 KB/s (128 Kbps)",
        defaultDownloadBytesPerSec = 32L * 1024L,
        defaultUploadBytesPerSec = 16L * 1024L
    ),
    CUSTOM(
        titleAr = "إعداد مخصص",
        descriptionAr = "حدود سرعة تنزيل ورفع مستقلة يحددها المستخدم",
        defaultDownloadBytesPerSec = 256L * 1024L,
        defaultUploadBytesPerSec = 128L * 1024L
    ),
    BLOCK(
        titleAr = "حظر الاتصال",
        descriptionAr = "منع حركة الشبكة للتطبيق عبر محرك VPN المحلي",
        defaultDownloadBytesPerSec = -1L,
        defaultUploadBytesPerSec = -1L
    )
}

/**
 * شروط نوع الشبكة لتطبيق القاعدة.
 */
enum class NetworkTypeCondition(val titleAr: String) {
    ANY("جميع الشبكات (Wi-Fi + هاتف)"),
    WIFI_ONLY("شبكة Wi-Fi فقط"),
    CELLULAR_ONLY("بيانات الهاتف فقط")
}

/**
 * نوع الشبكة النشطة حاليًا على الجهاز.
 */
enum class ActiveNetworkTransport(val titleAr: String) {
    WIFI("شبكة لاسلكية Wi-Fi"),
    CELLULAR("بيانات الهاتف المحمول"),
    ETHERNET("شبكة سلكية Ethernet"),
    DISCONNECTED("غير متصل بالإنترنت"),
    UNKNOWN("شبكة غير محددة")
}

/**
 * مدة سريان القاعدة (مفصولة عن السرعة والحصة).
 */
enum class RuleDurationMode(val titleAr: String, val durationMillis: Long?) {
    PERMANENT("دائمة حتى التغيير", null),
    ONE_HOUR("لمدة ساعة واحدة", 3_600_000L),
    SIX_HOURS("لمدة 6 ساعات", 6 * 3_600_000L),
    UNTIL_MIDNIGHT("حتى نهاية اليوم", 24 * 3_600_000L)
}

/**
 * تصنيف إمكانية المراقبة والتحكم لكل تطبيق وفق صلاحيات أندرويد الفعلية (القسم السادس).
 */
enum class ControlCapabilityStatus(
    val badgeAr: String,
    val explanationAr: String
) {
    CONTROL_AVAILABLE(
        badgeAr = "التحكم متاح",
        explanationAr = "يدعم المراقبة اللحظية وتحديد السرعة والحظر عبر محرك VPN"
    ),
    MONITORING_AVAILABLE(
        badgeAr = "المراقبة متاحة",
        explanationAr = "المراقبة متاحة؛ شغّل محرك VPN لتفعيل الحظر أو تقييد السرعة"
    ),
    LIMITED_CONTROL(
        badgeAr = "التحكم محدود",
        explanationAr = "تطبيق نظام أساسي أو يشترك في UID؛ يُنصح بعدم حظره لتجنب تأثر خدمات النظام"
    ),
    REQUIRES_PERMISSION(
        badgeAr = "يتطلب إذنًا إضافيًا",
        explanationAr = "يتطلب موافقة VPN أو إذن إحصاءات الاستخدام لقراءة بيانات النظام التاريخية"
    ),
    UNSUPPORTED_ON_DEVICE(
        badgeAr = "غير مدعوم على الجهاز",
        explanationAr = "حزمة نظام داخلية (UID < 10000) لا تمر عبر مسار VPN للمستخدم"
    ),
    UNATTRIBUTED_SOURCE(
        badgeAr = "تعذر تحديد مصدر الاتصال",
        explanationAr = "حزم شبكية لم يسمح النظام بإسنادها إلى UID تطبيق محدد"
    )
}

/**
 * حالات دورة حياة محرك الشبكة و VPN المحلي في واجهة المستخدم (القسم الثاني والثالث).
 */
enum class EngineUiState(
    val titleAr: String,
    val descriptionAr: String,
    val canToggle: Boolean,
    val isLoading: Boolean
) {
    STOPPED(
        titleAr = "المحرك متوقف",
        descriptionAr = "المراقبة المحلية نشطة لقراءة الإحصاءات فقط؛ اضغط لتشغيل محرك VPN وتفعيل الحظر والتقييد",
        canToggle = true,
        isLoading = false
    ),
    AWAITING_CONSENT(
        titleAr = "بانتظار موافقة النظام",
        descriptionAr = "يرجى قبول نافذة إذن VPN المعروضة من نظام أندرويد لتفعيل المحرك",
        canToggle = false,
        isLoading = true
    ),
    STARTING(
        titleAr = "جاري تشغيل المحرك...",
        descriptionAr = "يتم تهيئة واجهة TUN وفحص سلامة مكدس الحزم وقنوات التوجيه",
        canToggle = false,
        isLoading = true
    ),
    RUNNING(
        titleAr = "محرك التحكم نشط",
        descriptionAr = "نفق VPN المحلي معزول؛ يتم تطبيق سياسات الحظر الفعلي على النواة وتقييد السرعة ومراقبة التدفقات",
        canToggle = true,
        isLoading = false
    ),
    STOPPING(
        titleAr = "جاري إيقاف المحرك...",
        descriptionAr = "يتم إغلاق واجهة TUN وتحرير الموارد والعودة إلى وضع المراقبة الخفيفة",
        canToggle = false,
        isLoading = true
    ),
    FAILED(
        titleAr = "فشل في تشغيل المحرك",
        descriptionAr = "حدث خطأ أثناء تأسيس واجهة VPN؛ تحقق من الأذونات أو اضغط لإعادة المحاولة",
        canToggle = true,
        isLoading = false
    ),
    REVOKED_OR_LOST(
        titleAr = "تم سحب إذن VPN",
        descriptionAr = "تم إلغاء صلاحية VPN من قبل المستخدم أو بواسطة تطبيق شبكة آخر",
        canToggle = true,
        isLoading = false
    );
}

/**
 * قاعدة تحكم مفصولة المفاهيم (السرعة القصوى، نوع الشبكة، المدة، الأولوية).
 */
data class TrafficRule(
    val id: Long = 0L,
    val uid: Int,
    val packageName: String,
    val appName: String,
    val preset: PolicyPreset,
    val maxDownloadBytesPerSec: Long, // 0 = غير محدود, -1 = حظر
    val maxUploadBytesPerSec: Long,   // 0 = غير محدود, -1 = حظر
    val networkCondition: NetworkTypeCondition = NetworkTypeCondition.ANY,
    val durationMode: RuleDurationMode = RuleDurationMode.PERMANENT,
    val expiresAtEpochMillis: Long? = null,
    val priority: Int = 50, // 1 (أقل) إلى 100 (أعلى أولوية)
    val isEnabled: Boolean = true,
    val notes: String = "",
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    fun isExpired(nowMillis: Long = System.currentTimeMillis()): Boolean {
        return expiresAtEpochMillis != null && nowMillis > expiresAtEpochMillis
    }

    fun isBlocking(): Boolean = preset == PolicyPreset.BLOCK || maxDownloadBytesPerSec < 0 || maxUploadBytesPerSec < 0
}

/**
 * دورة حصة البيانات التراكمية.
 */
enum class QuotaPeriod(val titleAr: String, val defaultDays: Int) {
    DAILY("حصة يومية", 1),
    WEEKLY("حصة أسبوعية", 7),
    MONTHLY("حصة شهرية", 30),
    CUSTOM("حصة مخصصة", 14)
}

/**
 * الإجراء المتخذ عند بلوغ الحد الأقصى للحصة.
 */
enum class QuotaLimitAction(val titleAr: String, val descriptionAr: String) {
    NOTIFY_ONLY("تنبيه فقط", "إرسال إشعار تحذيري مع استمرار الاتصال الطبيعي"),
    THROTTLE_LOW("خفض للسرعة المنخفضة", "تقييد التطبيق تلقائيًا إلى السرعة المنخفضة (256 Kbps)"),
    BLOCK_CONNECTION("حظر الاتصال تلقائيًا", "قطع اتصال التطبيق بالشبكة حتى إعادة تعيين الحصة")
}

/**
 * نموذج حصة استهلاك البيانات (اليومية/الأسبوعية/الشهرية/المخصصة).
 * يوثق هامش التجاوز المحتمل (In-flight buffer margin ~32KB - 64KB) بسبب الحزم العابرة في المسار.
 */
data class QuotaRule(
    val id: Long = 0L,
    val uid: Int, // -1 تعني حصة عامة لجميع التطبيقات
    val packageName: String,
    val appName: String,
    val period: QuotaPeriod,
    val limitBytes: Long,
    val usedBytes: Long,
    val warningPercent: Int = 80, // تنبيه عند 80% افتراضيًا
    val actionOnLimit: QuotaLimitAction = QuotaLimitAction.THROTTLE_LOW,
    val networkCondition: NetworkTypeCondition = NetworkTypeCondition.ANY,
    val periodStartMillis: Long = System.currentTimeMillis(),
    val customDurationDays: Int = 14,
    val isEnabled: Boolean = true,
    val warningTriggered: Boolean = false,
    val limitTriggered: Boolean = false
) {
    val usageRatio: Float
        get() = if (limitBytes > 0) (usedBytes.toFloat() / limitBytes.toFloat()).coerceIn(0f, 1.5f) else 0f

    val usagePercent: Int
        get() = (usageRatio * 100f).toInt()

    val isWarningReached: Boolean
        get() = isEnabled && limitBytes > 0 && usagePercent >= warningPercent && usedBytes < limitBytes

    val isLimitReached: Boolean
        get() = isEnabled && limitBytes > 0 && usedBytes >= limitBytes
}

/**
 * معلومات التطبيق وحركته الشبكية اللحظية والتراكمية.
 */
data class AppNetworkProfile(
    val uid: Int,
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean,
    val sharedUidCount: Int = 1,
    val downloadBytesPerSec: Long = 0L,
    val uploadBytesPerSec: Long = 0L,
    val sessionDownloadBytes: Long = 0L,
    val sessionUploadBytes: Long = 0L,
    val todayDownloadBytes: Long = 0L,
    val todayUploadBytes: Long = 0L,
    val weekTotalBytes: Long = 0L,
    val monthTotalBytes: Long = 0L,
    val wifiBytes: Long = 0L,
    val cellularBytes: Long = 0L,
    val activeFlowsCount: Int = 0,
    val blockedAttemptsCount: Int = 0,
    val capabilityStatus: ControlCapabilityStatus = ControlCapabilityStatus.CONTROL_AVAILABLE,
    val activeRule: TrafficRule? = null,
    val activeQuota: QuotaRule? = null,
    val lastActiveTimestamp: Long = 0L
) {
    val totalTodayBytes: Long get() = todayDownloadBytes + todayUploadBytes
    val isCurrentlyActive: Boolean get() = downloadBytesPerSec > 0L || uploadBytesPerSec > 0L || activeFlowsCount > 0
}

enum class IpVersion(val label: String) { IPV4("IPv4"), IPV6("IPv6") }

enum class PacketProtocol(val label: String, val number: Int) {
    TCP("TCP", 6),
    UDP("UDP", 17),
    ICMP("ICMP", 1),
    ICMPV6("ICMPv6", 58),
    DNS("DNS", 53),
    OTHER("أخرى", -1)
}

enum class FlowState(val labelAr: String) {
    NEW("اتصال جديد"),
    ESTABLISHED("نشط ومستقر"),
    THROTTLED("مقيد السرعة"),
    BLOCKED("محظور"),
    CLOSING("قيد الإغلاق"),
    EXPIRED("منتهي")
}

/**
 * سجل تدفق شبكي (Flow Record) داخل محرك FlowTracker.
 */
data class FlowRecord(
    val flowKey: String,
    val uid: Int,
    val packageName: String,
    val appName: String,
    val ipVersion: IpVersion,
    val protocol: PacketProtocol,
    val sourceIp: String,
    val sourcePort: Int,
    val destinationIp: String,
    val destinationPort: Int,
    val dnsQueryHost: String? = null,
    val bytesDown: Long = 0L,
    val bytesUp: Long = 0L,
    val packetsDown: Int = 0,
    val packetsUp: Int = 0,
    val state: FlowState = FlowState.NEW,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val lastSeenMillis: Long = System.currentTimeMillis()
)

/**
 * نقطة زمنية لرسم منحنى سرعة الشبكة اللحظي.
 */
data class SpeedSamplePoint(
    val timestampMillis: Long,
    val downloadBytesPerSec: Long,
    val uploadBytesPerSec: Long
)

/**
 * أنواع أحداث سجل المراقبة والقيود.
 */
enum class AuditEventType(val titleAr: String) {
    ENGINE_STARTED("تشغيل محرك الشبكة"),
    ENGINE_STOPPED("إيقاف محرك الشبكة"),
    RULE_APPLIED("تطبيق قاعدة تحكم"),
    CONFLICT_RESOLVED("حسم تعارض قواعد"),
    CONNECTION_BLOCKED("حظر محاولة اتصال"),
    SPEED_THROTTLED("تطبيق تحديد السرعة"),
    QUOTA_WARNING("تحذير اقتراب من الحصة"),
    QUOTA_EXCEEDED("تجاوز الحصة وتنفيذ الإجراء"),
    NETWORK_CHANGED("تغيير نوع الشبكة"),
    ERROR_RECOVERY("استرداد تلقائي من خطأ")
}

/**
 * عنصر في سجل الأحداث والتغييرات ومحاولات الاتصال.
 */
data class AuditLogItem(
    val id: Long = 0L,
    val timestampMillis: Long = System.currentTimeMillis(),
    val eventType: AuditEventType,
    val uid: Int = -1,
    val packageName: String = "",
    val appName: String = "النظام",
    val summaryAr: String,
    val technicalDetails: String = "",
    val networkTransport: ActiveNetworkTransport = ActiveNetworkTransport.WIFI
)

/**
 * تشخيصات محرك الشبكة والأداء والذاكرة (القسم الرابع والعاشر).
 */
data class EngineDiagnostics(
    val isVpnRunning: Boolean = false,
    val isVpnPrepared: Boolean = false,
    val isUsageAccessGranted: Boolean = false,
    val activeTransport: ActiveNetworkTransport = ActiveNetworkTransport.WIFI,
    val mtuSize: Int = 1400,
    val ipv4Enabled: Boolean = true,
    val ipv6Enabled: Boolean = true,
    val activeFlowsCount: Int = 0,
    val maxFlowTableCapacity: Int = 2048,
    val packetQueueDepth: Int = 0,
    val maxPacketQueueCapacity: Int = 512,
    val totalPacketsProcessed: Long = 0L,
    val droppedByPolicyCount: Long = 0L,
    val throttledDelayedPackets: Long = 0L,
    val unattributedPacketsCount: Long = 0L,
    val averageLoopLatencyMicros: Long = 180L,
    val memoryUsedMb: Long = 24L,
    val lastSelfTestResultAr: String = "لم يُشغّل الفحص الذاتي بعد",
    val lastSelfTestPassed: Boolean = true
)
