package com.example.core.model

import com.example.core.i18n.AppLanguage

/**
 * وحدات قياس سرعة النقل (تفرق بدقة بين البت bit والبايت Byte، وبين الثنائي KiB/s والعشري KB/s).
 * الوحدة الداخلية الموحدة في جميع الحسابات: بايت/ثانية (Bytes/sec = B/s).
 * - 1 Byte = 8 bits
 * - 1 Kbps = 1,000 bits/sec = 125 B/s
 * - 1 Mbps = 1,000,000 bits/sec = 125,000 B/s
 * - 1 KiB/s (KB/s ثنائي) = 1,024 B/s (10 KiB/s = 10,240 B/s، 20 KiB/s = 20,480 B/s)
 * - 1 MiB/s (MB/s ثنائي) = 1,048,576 B/s
 * - 1 KB/s (عشري) = 1,000 B/s (10 KB/s = 10,000 B/s، 20 KB/s = 20,000 B/s)
 */
enum class BandwidthUnit(
    val symbolAr: String,
    val symbolEn: String,
    val isBits: Boolean,
    val bytesPerSecondMultiplier: Double
) {
    KBPS("كيلوبت/ث", "Kbps", true, 1000.0 / 8.0),
    MBPS("ميجابت/ث", "Mbps", true, 1_000_000.0 / 8.0),
    KB_PER_SEC("كيلوبايت/ث", "KiB/s", false, 1024.0),
    MB_PER_SEC("ميجابايت/ث", "MiB/s", false, 1024.0 * 1024.0),
    DECIMAL_KB_PER_SEC("كيلوبايت عشري/ث", "KB/s", false, 1000.0);

    fun symbol(lang: AppLanguage): String = if (lang == AppLanguage.AR) symbolAr else symbolEn
}

/**
 * وحدات قياس حجم البيانات التراكمي (بالبايت).
 */
enum class VolumeUnit(
    val symbolAr: String,
    val symbolEn: String,
    val bytesMultiplier: Long
) {
    KB("كيلوبايت", "KiB", 1024L),
    MB("ميجابايت", "MiB", 1024L * 1024L),
    GB("جيجابايت", "GiB", 1024L * 1024L * 1024L);

    fun symbol(lang: AppLanguage): String = if (lang == AppLanguage.AR) symbolAr else symbolEn
}

/**
 * حالة توفر بيانات القياس من النواة أو النظام (القسم 1 و 3).
 * القاعدة الصارمة: القياس غير المتاح (UNAVAILABLE) لا يُعرض أبداً كصفر (0).
 */
enum class DataAvailabilityStatus(
    val labelAr: String,
    val labelEn: String
) {
    AVAILABLE("بيانات متاحة", "Traffic available"),
    UNAVAILABLE("البيانات غير متاحة", "Traffic unavailable");

    fun label(lang: AppLanguage): String = if (lang == AppLanguage.AR) labelAr else labelEn
}

/**
 * الحالة التنفيذية الفعلية للقاعدة في محرك الشبكة مقابل حالتها المحفوظة (القسم 6 و 10.4 و 12).
 */
enum class RuleEnforcementState(
    val badgeAr: String,
    val badgeEn: String,
    val explanationAr: String,
    val explanationEn: String,
    val isActivelyEnforced: Boolean
) {
    ENFORCED_IN_KERNEL_VPN(
        badgeAr = "فعالة (عزل VPN)",
        badgeEn = "Active (VPN Blocked)",
        explanationAr = "القاعدة مطبقة فعلياً في محرك VPN ويتم حظر حزم التطبيق",
        explanationEn = "Rule is actively enforced in the local VPN tunnel",
        isActivelyEnforced = true
    ),
    ACTIVE_MONITORED_RATE(
        badgeAr = "فعالة (تحديد سرعة)",
        badgeEn = "Limit active",
        explanationAr = "القاعدة نشطة في محرك Token Bucket وجدولة الحزم عبر VPN",
        explanationEn = "Rate limit is actively enforced via VPN packet pacing",
        isActivelyEnforced = true
    ),
    SAVED_ONLY_VPN_OFF(
        badgeAr = "مضبوطة فقط (المحرك متوقف)",
        badgeEn = "Limit configured (VPN off)",
        explanationAr = "القاعدة محفوظة لكنها غير مطبقة لأن محرك VPN متوقف حالياً",
        explanationEn = "Configured in database, but inactive while VPN engine is stopped",
        isActivelyEnforced = false
    ),
    INACTIVE_NETWORK_MISMATCH(
        badgeAr = "بانتظار شبكة مطابقة",
        badgeEn = "Waiting for matching network",
        explanationAr = "القاعدة مخصصة لنوع شبكة يختلف عن الشبكة المتصلة حالياً",
        explanationEn = "Rule applies to a different network type than currently active",
        isActivelyEnforced = false
    ),
    DISABLED_BY_USER(
        badgeAr = "قاعدة غير مفعلة",
        badgeEn = "Disabled",
        explanationAr = "تم إيقاف تفعيل هذه القاعدة يدوياً",
        explanationEn = "Manually disabled by user",
        isActivelyEnforced = false
    ),
    EXPIRED_TIME_LIMIT(
        badgeAr = "منتهية المدة",
        badgeEn = "Expired",
        explanationAr = "انتهت الفترة الزمنية المحددة لسريان القاعدة",
        explanationEn = "Rule duration has expired",
        isActivelyEnforced = false
    ),
    FAILED_TO_APPLY(
        badgeAr = "تعذر تطبيق القاعدة",
        badgeEn = "Failed to apply",
        explanationAr = "تعذر تطبيق القاعدة في واجهة VPN بسبب قيود النظام",
        explanationEn = "Could not apply rule on VPN interface due to OS restrictions",
        isActivelyEnforced = false
    );

    val labelAr: String get() = badgeAr
    fun badge(lang: AppLanguage): String = if (lang == AppLanguage.AR) badgeAr else badgeEn
    fun explanation(lang: AppLanguage): String = if (lang == AppLanguage.AR) explanationAr else explanationEn
}

/**
 * مستويات سياسة السرعة والتحكم لكل تطبيق مع قيم افتراضية موثقة بالبايت/ثانية وقابلة للتخصيص.
 * - LOW = 10 KiB/s (10,240 B/s) تنزيل • 10 KiB/s (10,240 B/s) رفع
 * - MEDIUM = 20 KiB/s (20,480 B/s) تنزيل • 20 KiB/s (20,480 B/s) رفع
 * - HIGH = 160 KiB/s (163,840 B/s) تنزيل • 64 KiB/s (65,536 B/s) رفع
 */
enum class PolicyPreset(
    val titleAr: String,
    val titleEn: String,
    val shortAr: String,
    val shortEn: String,
    val descriptionAr: String,
    val descriptionEn: String,
    val defaultDownloadBytesPerSec: Long, // 0 = غير محدود، -1 = محظور
    val defaultUploadBytesPerSec: Long
) {
    ALLOW(
        titleAr = "غير محدود (مسموح)",
        titleEn = "Unlimited (Allow)",
        shortAr = "مسموح",
        shortEn = "Allowed",
        descriptionAr = "اتصال مباشر بأقصى سرعة متاحة دون قيود",
        descriptionEn = "Unrestricted connection at full network speed",
        defaultDownloadBytesPerSec = 0L,
        defaultUploadBytesPerSec = 0L
    ),
    HIGH(
        titleAr = "تحديد سرعة (160 KiB/s)",
        titleEn = "High Limit (160 KiB/s)",
        shortAr = "160 KiB/s",
        shortEn = "160 KiB/s",
        descriptionAr = "سقف 160 KiB/s (163,840 B/s) تنزيل • 64 KiB/s رفع",
        descriptionEn = "Cap: 160 KiB/s (163,840 B/s) Down • 64 KiB/s Up",
        defaultDownloadBytesPerSec = 160L * 1024L,
        defaultUploadBytesPerSec = 64L * 1024L
    ),
    MEDIUM(
        titleAr = "تحديد سرعة (20 KiB/s)",
        titleEn = "20 KiB/s Limit",
        shortAr = "20 KiB/s",
        shortEn = "20 KiB/s",
        descriptionAr = "سقف 20 KiB/s (20,480 B/s) تنزيل • 20 KiB/s (20,480 B/s) رفع",
        descriptionEn = "Cap: 20 KiB/s (20,480 B/s) Down • 20 KiB/s (20,480 B/s) Up",
        defaultDownloadBytesPerSec = 20L * 1024L,
        defaultUploadBytesPerSec = 20L * 1024L
    ),
    LOW(
        titleAr = "تحديد سرعة (10 KiB/s)",
        titleEn = "10 KiB/s Limit",
        shortAr = "10 KiB/s",
        shortEn = "10 KiB/s",
        descriptionAr = "سقف 10 KiB/s (10,240 B/s) تنزيل • 10 KiB/s (10,240 B/s) رفع",
        descriptionEn = "Cap: 10 KiB/s (10,240 B/s) Down • 10 KiB/s (10,240 B/s) Up",
        defaultDownloadBytesPerSec = 10L * 1024L,
        defaultUploadBytesPerSec = 10L * 1024L
    ),
    CUSTOM(
        titleAr = "تحديد سرعة مخصصة",
        titleEn = "Custom Limit",
        shortAr = "مخصصة",
        shortEn = "Custom",
        descriptionAr = "تحديد سرعة مستقلة للتنزيل والرفع بالبايت/ثانية",
        descriptionEn = "Independent download and upload speed limits",
        defaultDownloadBytesPerSec = 20L * 1024L,
        defaultUploadBytesPerSec = 10L * 1024L
    ),
    BLOCK(
        titleAr = "حظر الاتصال",
        titleEn = "Block",
        shortAr = "محظور",
        shortEn = "Blocked",
        descriptionAr = "عزل التطبيق وإسقاط اتصالاته عبر نفق VPN المحلي",
        descriptionEn = "Isolate app and drop connections via local VPN",
        defaultDownloadBytesPerSec = -1L,
        defaultUploadBytesPerSec = -1L
    );

    fun title(lang: AppLanguage): String = if (lang == AppLanguage.AR) titleAr else titleEn
    fun shortLabel(lang: AppLanguage): String = if (lang == AppLanguage.AR) shortAr else shortEn
    fun description(lang: AppLanguage): String = if (lang == AppLanguage.AR) descriptionAr else descriptionEn
}

/**
 * شروط نوع الشبكة لتطبيق القاعدة.
 */
enum class NetworkTypeCondition(
    val titleAr: String,
    val titleEn: String,
    val shortAr: String,
    val shortEn: String
) {
    ANY("جميع الشبكات (Wi-Fi + هاتف)", "All Networks (Wi-Fi + Cellular)", "الكل", "All"),
    WIFI_ONLY("شبكة Wi-Fi فقط", "Wi-Fi Only", "Wi-Fi", "Wi-Fi"),
    CELLULAR_ONLY("بيانات الهاتف فقط", "Cellular Only", "الهاتف", "Cellular");

    fun title(lang: AppLanguage): String = if (lang == AppLanguage.AR) titleAr else titleEn
    fun shortLabel(lang: AppLanguage): String = if (lang == AppLanguage.AR) shortAr else shortEn
}

/**
 * نوع الشبكة النشطة حاليًا على الجهاز.
 */
enum class ActiveNetworkTransport(
    val titleAr: String,
    val titleEn: String
) {
    WIFI("Wi-Fi", "Wi-Fi"),
    CELLULAR("بيانات الهاتف", "Cellular"),
    ETHERNET("Ethernet", "Ethernet"),
    DISCONNECTED("غير متصل", "Disconnected"),
    UNKNOWN("غير محدد", "Unknown");

    fun title(lang: AppLanguage): String = if (lang == AppLanguage.AR) titleAr else titleEn
}

/**
 * مدة سريان القاعدة (مفصولة عن السرعة والحصة).
 */
enum class RuleDurationMode(
    val titleAr: String,
    val titleEn: String,
    val durationMillis: Long?
) {
    PERMANENT("دائمة حتى التغيير", "Permanent", null),
    ONE_HOUR("لمدة ساعة واحدة", "1 Hour", 3_600_000L),
    SIX_HOURS("لمدة 6 ساعات", "6 Hours", 6 * 3_600_000L),
    UNTIL_MIDNIGHT("حتى نهاية اليوم", "24 Hours", 24 * 3_600_000L);

    fun title(lang: AppLanguage): String = if (lang == AppLanguage.AR) titleAr else titleEn
}

/**
 * تصنيف إمكانية المراقبة والتحكم لكل تطبيق وفق صلاحيات أندرويد الفعلية (القسم الثامن والتاسع).
 */
enum class ControlCapabilityStatus(
    val badgeAr: String,
    val badgeEn: String,
    val explanationAr: String,
    val explanationEn: String
) {
    CONTROL_AVAILABLE(
        badgeAr = "حظر ومراقبة",
        badgeEn = "Full Control",
        explanationAr = "يدعم المراقبة اللحظية والحظر وتحديد السرعة عبر نفق VPN المحلي.",
        explanationEn = "Supports live monitoring, firewall blocking, and rate limiting via local VPN."
    ),
    MONITORING_AVAILABLE(
        badgeAr = "مراقبة فقط",
        badgeEn = "Monitoring Only",
        explanationAr = "المراقبة متاحة؛ شغّل المحرك لتفعيل الحظر وتحديد السرعة.",
        explanationEn = "Live telemetry active. Start the engine to enforce rules and limits."
    ),
    LIMITED_CONTROL(
        badgeAr = "تطبيق نظام",
        badgeEn = "System App",
        explanationAr = "حزمة نظام أو UID مشترك؛ يُنصح بعدم حظرها لتفادي تأثر خدمات الجهاز.",
        explanationEn = "System package or shared UID. Blocking may affect core device services."
    ),
    REQUIRES_PERMISSION(
        badgeAr = "يلزم إذن VPN",
        badgeEn = "Needs VPN Permission",
        explanationAr = "يلزم منح إذن VPN لتفعيل التحكم بالحركة.",
        explanationEn = "Grant VPN permission to enable traffic control."
    ),
    UNSUPPORTED_ON_DEVICE(
        badgeAr = "غير مدعوم",
        badgeEn = "Unsupported",
        explanationAr = "حزمة نظام داخلية (UID < 10000) لا تمر عبر مسار VPN للمستخدم.",
        explanationEn = "Core system process (UID < 10000) bypasses user-space VPN routing."
    ),
    UNATTRIBUTED_SOURCE(
        badgeAr = "غير منسوب",
        badgeEn = "Unattributed",
        explanationAr = "حزم شبكية لم يسمح النظام بإسنادها إلى تطبيق محدد.",
        explanationEn = "Packets that the OS kernel did not attribute to a specific app UID."
    );

    fun badge(lang: AppLanguage): String = if (lang == AppLanguage.AR) badgeAr else badgeEn
    fun explanation(lang: AppLanguage): String = if (lang == AppLanguage.AR) explanationAr else explanationEn
}

/**
 * حالات دورة حياة محرك الشبكة و VPN المحلي في واجهة المستخدم (القسم 11 و 16).
 */
enum class EngineUiState(
    val titleAr: String,
    val titleEn: String,
    val descriptionAr: String,
    val descriptionEn: String,
    val canToggle: Boolean,
    val isLoading: Boolean
) {
    STOPPED(
        titleAr = "المحرك متوقف",
        titleEn = "Engine Stopped",
        descriptionAr = "المراقبة نشطة للإحصاءات فقط. شغّل المحرك لتطبيق القواعد وتحديد السرعة.",
        descriptionEn = "Monitoring usage only. Start the engine to enforce rules and speed limits.",
        canToggle = true,
        isLoading = false
    ),
    AWAITING_CONSENT(
        titleAr = "يلزم منح إذن VPN",
        titleEn = "VPN Permission Needed",
        descriptionAr = "اقبل نافذة نظام أندرويد لتفعيل التحكم المحلي بالحركة.",
        descriptionEn = "Approve the Android VPN dialog to activate local traffic control.",
        canToggle = false,
        isLoading = true
    ),
    STARTING(
        titleAr = "جاري بدء المحرك...",
        titleEn = "Starting Engine...",
        descriptionAr = "تهيئة واجهة النفق المحلي وتطبيق القواعد.",
        descriptionEn = "Initializing local TUN interface and applying rules.",
        canToggle = false,
        isLoading = true
    ),
    RUNNING(
        titleAr = "المحرك يعمل",
        titleEn = "Engine Active",
        descriptionAr = "نفق VPN المحلي نشط. يتم تطبيق الحظر والحصص وسقف السرعة.",
        descriptionEn = "Local VPN active. Enforcing firewall rules, quotas, and speed limits.",
        canToggle = true,
        isLoading = false
    ),
    STOPPING(
        titleAr = "جاري إيقاف المحرك...",
        titleEn = "Stopping Engine...",
        descriptionAr = "إغلاق واجهة النفق والعودة إلى وضع المراقبة.",
        descriptionEn = "Closing TUN interface and returning to passive monitoring.",
        canToggle = false,
        isLoading = true
    ),
    FAILED(
        titleAr = "تعذر تشغيل المحرك",
        titleEn = "Engine Failed",
        descriptionAr = "تعذّر تشغيل المحرك. حاول مجدداً.",
        descriptionEn = "Engine failed to start. Try again.",
        canToggle = true,
        isLoading = false
    ),
    REVOKED_OR_LOST(
        titleAr = "يلزم منح إذن VPN",
        titleEn = "VPN Permission Revoked",
        descriptionAr = "تم سحب إذن VPN من النظام أو بواسطة تطبيق آخر.",
        descriptionEn = "VPN permission was revoked by system settings or another VPN app.",
        canToggle = true,
        isLoading = false
    );

    fun title(lang: AppLanguage): String = if (lang == AppLanguage.AR) titleAr else titleEn
    fun description(lang: AppLanguage): String = if (lang == AppLanguage.AR) descriptionAr else descriptionEn
}

/**
 * قاعدة تحكم مفصولة المفاهيم (السرعة القصوى، نوع الشبكة، المدة، الأولوية، والحالة التنفيذية).
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
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    fun isExpired(nowMillis: Long = System.currentTimeMillis()): Boolean {
        return expiresAtEpochMillis != null && nowMillis > expiresAtEpochMillis
    }

    fun isBlocking(): Boolean = preset == PolicyPreset.BLOCK || maxDownloadBytesPerSec < 0 || maxUploadBytesPerSec < 0

    fun isFullBlock(): Boolean = isBlocking()

    fun isRateLimited(): Boolean = !isBlocking() && (maxDownloadBytesPerSec > 0L || maxUploadBytesPerSec > 0L)

    /**
     * هل تحظر هذه القاعدة شبكة Wi-Fi؟
     */
    fun isWifiBlocked(): Boolean {
        return isEnabled && isBlocking() &&
            (networkCondition == NetworkTypeCondition.ANY || networkCondition == NetworkTypeCondition.WIFI_ONLY)
    }

    /**
     * هل تحظر هذه القاعدة بيانات الهاتف؟
     */
    fun isCellularBlocked(): Boolean {
        return isEnabled && isBlocking() &&
            (networkCondition == NetworkTypeCondition.ANY || networkCondition == NetworkTypeCondition.CELLULAR_ONLY)
    }

    /**
     * يحسب الحالة التنفيذية الفعلية للقاعدة في المحرك مقارنة بحالتها المحفوظة (القسم 6 و 10.4 و 12).
     */
    fun evaluateEnforcementState(
        isVpnRunning: Boolean,
        activeTransport: ActiveNetworkTransport,
        failedPackages: Set<String> = emptySet(),
        hasApplyError: Boolean = false,
        nowMillis: Long = System.currentTimeMillis()
    ): RuleEnforcementState {
        if (!isEnabled) return RuleEnforcementState.DISABLED_BY_USER
        if (isExpired(nowMillis)) return RuleEnforcementState.EXPIRED_TIME_LIMIT
        if (hasApplyError || failedPackages.contains(packageName)) return RuleEnforcementState.FAILED_TO_APPLY

        val networkMatches = when (networkCondition) {
            NetworkTypeCondition.ANY -> true
            NetworkTypeCondition.WIFI_ONLY -> activeTransport == ActiveNetworkTransport.WIFI || activeTransport == ActiveNetworkTransport.ETHERNET
            NetworkTypeCondition.CELLULAR_ONLY -> activeTransport == ActiveNetworkTransport.CELLULAR
        }
        if (!networkMatches) return RuleEnforcementState.INACTIVE_NETWORK_MISMATCH

        if (!isVpnRunning) return RuleEnforcementState.SAVED_ONLY_VPN_OFF

        return if (isBlocking()) {
            RuleEnforcementState.ENFORCED_IN_KERNEL_VPN
        } else {
            RuleEnforcementState.ACTIVE_MONITORED_RATE
        }
    }
}

/**
 * دورة حصة البيانات التراكمية.
 */
enum class QuotaPeriod(
    val titleAr: String,
    val titleEn: String,
    val defaultDays: Int
) {
    DAILY("حصة يومية", "Daily Quota", 1),
    WEEKLY("حصة أسبوعية", "Weekly Quota", 7),
    MONTHLY("حصة شهرية", "Monthly Quota", 30),
    CUSTOM("حصة مخصصة", "Custom Quota", 14);

    fun title(lang: AppLanguage): String = if (lang == AppLanguage.AR) titleAr else titleEn
}

/**
 * الإجراء المتخذ عند بلوغ الحد الأقصى للحصة.
 */
enum class QuotaLimitAction(
    val titleAr: String,
    val titleEn: String,
    val descriptionAr: String,
    val descriptionEn: String
) {
    NOTIFY_ONLY("تنبيه فقط", "Notify Only", "إرسال إشعار تحذيري مع استمرار الاتصال", "Send warning alert without blocking"),
    THROTTLE_LOW("تحديد السرعة (منخفضة)", "Throttle to Low Speed", "تقييد التطبيق تلقائياً إلى 32 KB/s", "Automatically cap speed at 32 KB/s"),
    BLOCK_CONNECTION("حظر الاتصال تلقائياً", "Block Connection", "قطع اتصال التطبيق عبر VPN حتى تصفير الحصة", "Block app traffic via VPN until reset");

    fun title(lang: AppLanguage): String = if (lang == AppLanguage.AR) titleAr else titleEn
    fun description(lang: AppLanguage): String = if (lang == AppLanguage.AR) descriptionAr else descriptionEn
}

/**
 * نموذج حصة استهلاك البيانات (اليومية/الأسبوعية/الشهرية/المخصصة).
 */
data class QuotaRule(
    val id: Long = 0L,
    val uid: Int, // -1 تعني حصة عامة لجميع التطبيقات
    val packageName: String,
    val appName: String,
    val period: QuotaPeriod,
    val limitBytes: Long,
    val usedBytes: Long,
    val warningPercent: Int = 80,
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
 * معلومات التطبيق وحركته الشبكية اللحظية والتراكمية (القسم 9 و 10).
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
    val dataAvailability: DataAvailabilityStatus = DataAvailabilityStatus.AVAILABLE,
    val activeRule: TrafficRule? = null,
    val activeRuleEnforcementState: RuleEnforcementState? = null,
    val activeQuota: QuotaRule? = null,
    val lastActiveTimestamp: Long = 0L,
    val lastSampleTimestampMillis: Long = System.currentTimeMillis(),
    val measurementSourceAr: String = "TrafficStats (عدادات النواة لكل UID)"
) {
    val totalTodayBytes: Long get() = todayDownloadBytes + todayUploadBytes
    val isCurrentlyActive: Boolean
        get() = dataAvailability == DataAvailabilityStatus.AVAILABLE &&
            (downloadBytesPerSec > 0L || uploadBytesPerSec > 0L || activeFlowsCount > 0)
}

enum class IpVersion(val label: String) { IPV4("IPv4"), IPV6("IPv6") }

enum class PacketProtocol(val label: String, val number: Int) {
    TCP("TCP", 6),
    UDP("UDP", 17),
    ICMP("ICMP", 1),
    ICMPV6("ICMPv6", 58),
    DNS("DNS", 53),
    OTHER("Other", -1)
}

enum class FlowState(val labelAr: String, val labelEn: String) {
    NEW("اتصال جديد", "New"),
    ESTABLISHED("نشط", "Established"),
    THROTTLED("محدد السرعة", "Throttled"),
    BLOCKED("محظور", "Blocked"),
    CLOSING("قيد الإغلاق", "Closing"),
    EXPIRED("منتهي", "Expired");

    fun label(lang: AppLanguage): String = if (lang == AppLanguage.AR) labelAr else labelEn
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
enum class AuditEventType(
    val titleAr: String,
    val titleEn: String
) {
    ENGINE_STARTED("تشغيل المحرك", "Engine Started"),
    ENGINE_STOPPED("إيقاف المحرك", "Engine Stopped"),
    RULE_APPLIED("تطبيق قاعدة", "Rule Applied"),
    CONFLICT_RESOLVED("حسم تعارض", "Conflict Resolved"),
    CONNECTION_BLOCKED("حظر اتصال", "Connection Blocked"),
    SPEED_THROTTLED("تحديد السرعة", "Speed Throttled"),
    QUOTA_WARNING("تحذير حصة", "Quota Warning"),
    QUOTA_EXCEEDED("تجاوز الحصة", "Quota Exceeded"),
    NETWORK_CHANGED("تغيير الشبكة", "Network Changed"),
    ERROR_RECOVERY("تنبيه المحرك", "Engine Alert");

    fun title(lang: AppLanguage): String = if (lang == AppLanguage.AR) titleAr else titleEn
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
 * تشخيصات محرك الشبكة والأداء والذاكرة (القسم 10.7 و 16 و 17).
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
    val statsSourceStatusAr: String = "عدادات النواة TrafficStats متاحة ونشطة",
    val lastErrorAr: String? = null,
    val lastSuccessTimestampMillis: Long = 0L,
    val lastSyncTimestampMillis: Long = 0L,
    val failedRulePackages: Set<String> = emptySet(),
    val lastSelfTestResultAr: String = "لم يُشغّل الفحص الذاتي بعد",
    val lastSelfTestPassed: Boolean = true
)
