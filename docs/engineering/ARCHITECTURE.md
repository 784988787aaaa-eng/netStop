# معمارية النظام: NetBalancer Android
**ملف:** `docs/engineering/ARCHITECTURE.md`  

---

## 1. نظرة عامة على الطبقات المعمارية (Layered Architecture)

تم تصميم NetBalancer وفق نمط Clean Architecture + MVVM المعياري الموصى به رسمياً في تطوير أندرويد الحديث:

```text
┌────────────────────────────────────────────────────────┐
│             Presentation Layer (Jetpack Compose)       │
│  - DashboardScreen       - ApplicationsScreen          │
│  - AppDetailsScreen      - RulesScreen                 │
│  - QuotasScreen          - HistoryScreen               │
│  - SettingsScreen        - NetBalancerViewModel        │
└───────────────────────────┬────────────────────────────┘
                            │ StateFlow / Events
┌───────────────────────────▼────────────────────────────┐
│                      Domain Layer                      │
│  - PolicyPreset          - EngineUiState               │
│  - QuotaRule             - FlowRecord                  │
│  - AuditLogItem          - AppNetworkProfile           │
└───────────────────────────┬────────────────────────────┘
                            │
┌───────────────────────────▼────────────────────────────┐
│                       Data Layer                       │
│  - NetBalancerRepository (Single Source of Truth)      │
│  - NetBalancerDatabase (Room: Rules, Quotas, Audit)    │
│  - AppPreferencesRepository (DataStore Preferences)    │
└─────────────┬───────────────────────────┬──────────────┘
              │                           │
┌─────────────▼─────────────┐   ┌─────────▼──────────────┐
│       Network Engine      │   │  Platform Integration  │
│ - NetBalancerVpnService   │   │ - TrafficStats         │
│ - TokenBucketRateLimiter  │   │ - PackageManager       │
│ - IpPacketParser          │   │ - ConnectivityManager  │
│ - FlowTracker             │   │ - NotificationManager  │
└───────────────────────────┘   └────────────────────────┘
```

---

## 2. تفصيل مكونات محرك الشبكة (Network Engine Architecture)

### 2.1 معمارية نفق الـ VPN (Selective Split-Tunnel Sink)
- **إنشاء الواجهة:** تُنشأ واجهة TUN بعنوان IP محلي افتراضي: `10.215.173.1/32` (IPv4) و `fd00:6e62::1/128` (IPv6) بحجم MTU قدره 1500 بايت.
- **توجيه التطبيقات:**
  - التطبيقات المسموح لها: تخرج من الواجهات الفيزيائية مباشرة دون أي معالجة إضافية في الـ VPN، محققة أقصى أداء معتاد.
  - التطبيقات المحظورة: تُضاف إلى نفق الـ VPN عبر `Builder.addAllowedApplication(pkg)`، وتُسقط حزمها فوراً داخل حلقة معالجة الحزم في الـ VPN، مع احتسابها ضمن الحزم المسقطة (`droppedPacketsCount`).
- **فحص اتجاه الحزم:**
  - يتم فحص `destinationIp` مقارنة بـ `10.215.173.1` و `fd00:6e62::1`.
  - إذا تطابق، تعتبر الحزمة واردة (`isDownload = true`).
  - إذا اختلف، تعتبر الحزمة صادرة (`isOutbound = true`).

### 2.2 محرك أخذ العينات اللحظية (TrafficMonitorCoordinator)
- يعمل في كوروتين دوري كل 1000ms.
- يقرأ `readKernelUidBytes(uid)` من خلال `TrafficStats`.
- يحسب فارق البايتات منذ العينة السابقة ليحسب `downloadBytesPerSec` و `uploadBytesPerSec`.
- يجمع فوارق الجلسة مع الأساس اليومي (`baseTodayDown + sessionRxBytes`) لحساب استهلاك اليوم بدقة تامة دون تداخل مع عدادات تشغيل الهاتف السابقة.
- يقيم الحصص النشطة ويطلق التحذيرات أو الحظر التلقائي.

---

## 3. إدارة التزامن وحفظ البيانات
- جميع عمليات قاعدة البيانات تُنفذ عبر `Dispatchers.IO`.
- يتم استخدام `StateFlow` في ViewModel مع `collectAsStateWithLifecycle()` في الواجهات لضمان عدم استهلاك المعالج عند وجود التطبيق في الخلفية.
- يتم تطبيق سياسة الحفظ الذري لتقدم الحصص لمنع الكتابة فوق أوقات بدء الفترات أو إلغاء رايات التنبيه.
