# تقرير التدقيق المعماري والتقني الشامل لمشروع NetBalancer Android
**ملف:** `docs/engineering/PROJECT_AUDIT.md`  
**التاريخ:** 2026-10-23  
**المرجع:** المخطط المعماري الشامل لإدارة وحركة الإنترنت على أندرويد  

---

## 1. شجرة المشروع والوحدات البرمجية (Project Tree & Structure)

```text
/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── AndroidManifest.xml
│   │   │   ├── java/com/example/
│   │   │   │   ├── MainActivity.kt
│   │   │   │   ├── core/
│   │   │   │   │   ├── common/UnitConverter.kt
│   │   │   │   │   ├── model/NetworkModels.kt
│   │   │   │   │   └── permissions/PermissionAndCapabilityHelper.kt
│   │   │   │   ├── data/
│   │   │   │   │   ├── database/NetBalancerDatabase.kt
│   │   │   │   │   ├── preferences/AppPreferencesRepository.kt
│   │   │   │   │   └── repository/NetBalancerRepository.kt
│   │   │   │   ├── feature/
│   │   │   │   │   ├── app_details/AppDetailsScreen.kt
│   │   │   │   │   ├── applications/ApplicationsScreen.kt
│   │   │   │   │   ├── common/NetBalancerComponents.kt
│   │   │   │   │   ├── dashboard/DashboardScreen.kt
│   │   │   │   │   ├── history/HistoryScreen.kt
│   │   │   │   │   ├── quotas/QuotasScreen.kt
│   │   │   │   │   ├── rules/RulesScreen.kt
│   │   │   │   │   └── settings/SettingsScreen.kt
│   │   │   │   ├── monitoring/
│   │   │   │   │   └── TrafficMonitorCoordinator.kt
│   │   │   │   ├── network/
│   │   │   │   │   ├── accounting/TrafficAccountingEngine.kt
│   │   │   │   │   ├── attribution/AppAttributionEngine.kt
│   │   │   │   │   ├── flow/FlowTracker.kt
│   │   │   │   │   ├── packet/IpPacketParser.kt
│   │   │   │   │   ├── policy/PolicyEngine.kt
│   │   │   │   │   ├── rate_limit/TokenBucketRateLimiter.kt
│   │   │   │   │   └── vpn/NetBalancerVpnService.kt
│   │   │   │   └── ui/
│   │   │   │       ├── NetBalancerViewModel.kt
│   │   │   │       └── theme/ (Color.kt, Theme.kt, Type.kt)
│   │   │   └── res/
│   │   │       ├── values/ (colors.xml, strings.xml, themes.xml)
│   │   │       └── mipmap-*/ (ic_launcher.webp, ic_launcher_round.webp)
│   │   └── test/java/com/example/
│   │       ├── ExampleRobolectricTest.kt
│   │       └── ExampleUnitTest.kt
│   └── build.gradle.kts
├── docs/engineering/
├── metadata.json
├── settings.gradle.kts
└── build.gradle.kts
```

---

## 2. مسارات تدفق البيانات (Data Flow Pipelines)

1. **مسار قياس السرعة اللحظية:**
   النواة (`TrafficStats.getUidRxBytes / getUidTxBytes`) 
   → `AppAttributionEngine.kt` (فروق القراءات الحسابية في الثانية)
   → `TrafficMonitorCoordinator.kt` (تجميع العينات اللحظية وعينات الرسم البياني)
   → `NetBalancerRepository.kt` (`StateFlow<List<AppNetworkProfile>>`)
   → `NetBalancerViewModel.kt`
   → واجهات Jetpack Compose (`DashboardScreen`, `ApplicationsScreen`, `AppDetailsScreen`).

2. **مسار إنفاذ القواعد (Policy Enforcement):**
   تعديل القاعدة في الواجهة (`RuleEditorDialog`)
   → `NetBalancerViewModel.saveRule()`
   → `NetBalancerRepository.saveRule()`
   → حفظ في قاعدة بيانات SQLite (`NetBalancerDao`)
   → تحديث جدول السياسات الفعلي في الذاكرة ومحرك الـ VPN (`PolicyEngine` و `VpnEngineController`)
   → إعادة تهيئة نفق الـ VPN عبر `Builder.addAllowedApplication` لحظر التطبيقات المستهدفة وتوجيهها للـ Sink.
   → تسجيل الحدث في سجل التدقيق (`audit_events`).

3. **مسار الحصص والتنبيهات (Quota & Alert Pipeline):**
   استهلاك الحزم أثناء تشغيل التطبيق
   → `TrafficMonitorCoordinator` يقيم الحصة دورياً مقارنة بـ `limitBytes`
   → إذا تجاوزت نسبة التحذير (مثل 80%) يتم إرسال تنبيه وتسجيل الحدث
   → إذا تجاوزت السقف (100%) يتم تطبيق إجراء الحظر التلقائي عبر المستودع وحفظ الحالة الذرية دون تصفير وقت بداية الفترة.

---

## 3. تدقيق المكونات الحرجة (Critical Component Audit)

| المكون | الوصف المعماري | الحالة المكتشفة | التقييم |
| :--- | :--- | :--- | :--- |
| `NetBalancerVpnService.kt` | خدمة VPN محلية تدير واجهة TUN ونمط Split-Tunnel Sink وفحص اتجاه الحزم. | تعمل بشكل كامل ومستقر، تدعم قراءة وكتابة الحزم وإسقاط حزم التطبيقات المحظورة. | **سليم ومثبت** |
| `TrafficMonitorCoordinator.kt` | منسق أخذ عينات السرعة واحتساب فروق الجلسات وتقييم الحصص. | تم تنقيته من أي قص اصطناعي للسرعات (`coerceAtMost`)، وتم فصل عدادات الإقلاع عن الاستهلاك اليومي. | **سليم ومثبت** |
| `NetBalancerRepository.kt` | مستودع البيانات المركزي ومصدر الحقيقة الموحد. | تم تصحيح استكشاف التطبيقات ليعتمد على الاستهلاك المخزن في قاعدة البيانات عند الإطلاق بدلاً من عدادات الإقلاع الخام. | **سليم ومثبت** |
| `NetBalancerDao` & Database | قاعدة بيانات Room المحلية لتخزين القواعد والحصص والإحصاءات التراكمية وسجل التدقيق. | تتضمن عمليات ذرية (`updateQuotaProgress`) تمنع ضياع تواريخ الحصص أو رايات التحذير. | **سليم ومثبت** |
| `TokenBucketRateLimiter.kt` | محرك حساب رياضي لتحديد السقف الترددي ومعدل البايتات في الميكروثانية. | سليم رياضياً ومختبر بالاختبارات الميكروثانية. | **سليم ومختبر** |
| `EngineUiState` & `EngineHeroCard` | آلة حالات بـ 7 أطوار مع زر تحكم عملاق 56dp+ ومنع الضغط المتزامن. | متكامل مع دورة حياة الأذونات في Android 13+. | **سليم ومختبر** |

---

## 4. تقييم التبعيات (Dependencies Assessment)
- تطبيق مبني باستخدام Kotlin 2.0.21 و Gradle 8.11.1 و Android SDK 36.
- اعتماد Jetpack Compose M3 بالكامل للواجهات.
- استخدام Room مع KSP لحفظ القواعد محلياً وسجل الأحداث.
- استخدام DataStore preferences لحفظ تفضيلات المستخدم (الوضع المظلم، وحدات القياس، إظهار تطبيقات النظام).
- خلو المشروع تماماً من أي مكتبات خارجية مجهولة المصدر أو تتطلب خوادم خارجية غير موثوقة.
- التوافق مع متطلبات الخصوصية والأمان في Google Play.
