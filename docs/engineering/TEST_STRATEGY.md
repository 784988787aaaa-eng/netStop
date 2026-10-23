# استراتيجية الاختبار والتحقق: NetBalancer Android
**ملف:** `docs/engineering/TEST_STRATEGY.md`  

---

## 1. فلسفة الاختبار (Testing Philosophy)
- **اختبارات سريعة ومحلية بدون محاكي:** الاعتماد الكامل على Robolectric ومكتبات JUnit المحلية للتحقق السريع والمستمر داخل بيئة البناء السحابية.
- **تغطية المسارات الحرجة (Critical User Journeys - CUJs):**
  1. بدء وإيقاف محرك الشبكة عبر زر القيادة الرئيسي في الشاشة الرئيسية.
  2. إنشاء وتحديث وتطبيق قواعد الحظر والسماح للتطبيقات.
  3. إنشاء وحفظ وتحديث مسار الحصص الاستهلاكية دون تصفير وقت بداية الفترة.
  4. التحقق الرياضي الدقيق لخوارزمية Token Bucket ومعدلات التدفق.
  5. فحص اتجاه الحزم الواردة والصادرة على واجهة الـ VPN.

---

## 2. مصفوفة الاختبارات المنفذة

### أ. اختبارات الوحدة (`ExampleUnitTest.kt`)
1. `testTokenBucketRefill`: التحقق من ملء الرموز بمعدل دقيق زمنياً حسب الميكروثانية.
2. `testTokenBucketExhaustion`: التحقق من استهلاك الرموز ورفض الحزم عند نفاد السعة.
3. `testFlowTrackerState`: تسجيل التدفقات، زيادة عدد الحزم، وتنظيف التدفقات الخاملة.
4. `testAppCapabilityEvaluation`: التحقق من مخرجات تصنيف التطبيقات (عادي، نظام، حزم مشتركة).
5. `testEngineDiagnostics`: دقة حساب الحزم المسقطة، والمعالجة، والسرعات اللحظية.
6. `testTrafficSnapshotMath`: دقة حساب الفوارق اللحظية ومعدل البايتات في الثانية.
7. `testRulePriorityComparison`: أسبقية القواعد حسب الأولوية وتاريخ التحديث.
8. `testEngineUiStateProperties`: التأكد من مطابقة خصائص `canToggle` و `isLoading` لكل طور من أطوار المحرك السبعة.
9. `testQuotaRulePeriodStartPreservation`: التحقق من ثبات وقت بداية الفترة ورايات التنبيه عند تحديث الاستهلاك.
10. `testPacketDirectionalityCheck`: فحص عنوان الوجهة لتحديد اتجاه الحزمة بدقة.

### ب. اختبارات Robolectric وواجهة المستخدم (`ExampleRobolectricTest.kt`)
1. `testDashboardRendersVpnToggleHeroCard`: التحقق من رسم بطاقة الـ Hero وزر التشغيل الرئيسي `toggle_vpn_button`.
2. `testSelfTestButtonExecution`: التحقق من تنفيذ زر الفحص الذاتي واختبار صحة جميع الوحدات البرمجية.
3. `testQuotaPersistenceInRoomDatabase`: التحقق من دوام بيانات الحصص وتحديث استهلاكها الذري في قاعدة بيانات SQLite المحلية.

---

## 3. أوامر تشغيل الاختبارات
```bash
gradle :app:testDebugUnitTest
```
