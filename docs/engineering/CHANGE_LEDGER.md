# سجل إدارة التحديثات والتغييرات (Change Ledger)
**ملف:** `docs/engineering/CHANGE_LEDGER.md`  

---

| المعرف | التاريخ | الوصف | الملفات المرتبطة | المشكلة المعالجة | حالة التنفيذ | دليل التحقق | الدخول للنسخة النهائية |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **NB-001** | 2026-10-23 | إلغاء قص السرعة الصوري وعرض سرعات النواة الحقيقية | `TrafficMonitorCoordinator.kt` | قص السرعات اصطناعياً عبر `coerceAtMost` | مكتمل | `TrafficMonitorCoordinator.kt` | نعم |
| **NB-002** | 2026-10-23 | تصحيح استكشاف التطبيقات الأولي وعزل قيم الإقلاع | `NetBalancerRepository.kt` | تسريب عدادات إقلاع الهاتف في استهلاك اليوم | مكتمل | `discoverInstalledApps` | نعم |
| **NB-003** | 2026-10-23 | تحديث تقدم الحصص دون تصفير تاريخ البداية | `NetBalancerDatabase.kt`, `NetBalancerRepository.kt` | تصفير `periodStartMillis` ومراحل التحذير عند كل تعديل | مكتمل | `updateQuotaProgress` | نعم |
| **NB-004** | 2026-10-23 | تصحيح اتجاه حركة الحزم في الـ VPN | `NetBalancerVpnService.kt` | افتراض أن الحزم الخارجة تمثل تنزيلاً | مكتمل | اختبار IP الوجهة | نعم |
| **NB-005** | 2026-10-23 | آلة حالات المحرك السباعية وزر قيادة 56dp+ | `NetworkModels.kt`, `DashboardScreen.kt`, `MainActivity.kt` | ضبابية حالة تشغيل الـ VPN وتعليق النقرات | مكتمل | `toggle_vpn_button` | نعم |
| **NB-006** | 2026-10-23 | تسلسل طلب أذونات الإشعارات والـ VPN | `MainActivity.kt` | ظهور نوافذ أذونات متعارضة في Android 13+ | مكتمل | تسلسل Callback الأذونات | نعم |
| **NB-007** | 2026-10-23 | تعزيز حزمة اختبارات الوحدة و Robolectric | `ExampleUnitTest.kt`, `ExampleRobolectricTest.kt` | غياب اختبارات لآلة الحالات والحصص واتجاه الحزم | مكتمل | 13 اختباراً ناجحاً | نعم |
| **NB-008** | 2026-10-23 | التوثيق المعماري الشامل ودراسة NetGuard | `NETGUARD_REFERENCE_ANALYSIS.md`, `docs/engineering/*` | غياب التوثيق الهندسي الشامل | مكتمل | إنشاء 11 ملف توثيق | نعم |
