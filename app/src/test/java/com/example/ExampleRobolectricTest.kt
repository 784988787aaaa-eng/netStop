package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.model.NetworkTypeCondition
import com.example.core.model.PolicyPreset
import com.example.core.model.RuleDurationMode
import com.example.data.database.NetBalancerDatabase
import com.example.data.database.RuleEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات Robolectric للتحقق من:
 * 1. موارد التطبيق واسمه الرسمي.
 * 2. حفظ واستعادة القواعد في قاعدة بيانات Room الفعلية.
 * 3. عمل شاشات Jetpack Compose والتنقل العربي (RTL) بين الشاشات الرئيسية.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `verify app name resource and room rule persistence`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("NetBalancer", context.getString(R.string.app_name))

        val db = Room.inMemoryDatabaseBuilder(context, NetBalancerDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val dao = db.dao()
        val ruleEntity = RuleEntity(
            uid = 10888,
            packageName = "com.example.demo",
            appName = "تطبيق تجريبي",
            presetName = PolicyPreset.BLOCK.name,
            maxDownloadBytesPerSec = -1L,
            maxUploadBytesPerSec = -1L,
            networkConditionName = NetworkTypeCondition.WIFI_ONLY.name,
            durationModeName = RuleDurationMode.PERMANENT.name,
            expiresAtEpochMillis = null,
            priority = 90,
            isEnabled = true,
            notes = "اختبار قاعدة الحظر",
            updatedAtMillis = 123456L
        )
        dao.upsertRule(ruleEntity)

        val loaded = dao.getAllRulesOnce()
        assertEquals(1, loaded.size)
        val domainRule = loaded.first().toDomain()
        assertTrue(domainRule.isBlocking())
        assertEquals(90, domainRule.priority)

        // التحقق من حفظ الحصة واسترجاعها بدقة دون تصفير زمن البداية
        val originalTime = 1_700_000_000_000L
        val quotaEntity = com.example.data.database.QuotaEntity(
            uid = 10888,
            packageName = "com.example.demo",
            appName = "تطبيق تجريبي",
            periodName = com.example.core.model.QuotaPeriod.MONTHLY.name,
            limitBytes = 5_000_000L,
            usedBytes = 4_200_000L,
            warningPercent = 80,
            actionOnLimitName = com.example.core.model.QuotaLimitAction.THROTTLE_LOW.name,
            networkConditionName = com.example.core.model.NetworkTypeCondition.ANY.name,
            periodStartMillis = originalTime,
            customDurationDays = 30,
            isEnabled = true,
            warningTriggered = true,
            limitTriggered = false
        )
        val qId = dao.upsertQuota(quotaEntity)
        val loadedQuota = dao.getQuotaById(qId)
        assertTrue(loadedQuota != null)
        assertEquals(originalTime, loadedQuota!!.periodStartMillis)
        assertEquals(4_200_000L, loadedQuota.usedBytes)
        assertTrue(loadedQuota.warningTriggered)
        assertFalse(loadedQuota.limitTriggered)

        db.close()
    }

    @Test
    fun `verify RTL compose dashboard and navigation across tabs`() {
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("dashboard_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("toggle_vpn_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("self_test_button").assertIsDisplayed()

        // التنقل إلى شاشة التطبيقات
        composeTestRule.onNodeWithTag("nav_tab_applications").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("applications_screen").assertIsDisplayed()

        // التنقل إلى شاشة القواعد
        composeTestRule.onNodeWithTag("nav_tab_rules").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("rules_screen").assertIsDisplayed()

        // التنقل إلى شاشة الحصص
        composeTestRule.onNodeWithTag("nav_tab_quotas").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("quotas_screen").assertIsDisplayed()

        // التنقل إلى شاشة السجل
        composeTestRule.onNodeWithTag("nav_tab_history").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("history_screen").assertIsDisplayed()

        // التنقل إلى شاشة الإعدادات
        composeTestRule.onNodeWithTag("nav_tab_settings").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_screen").assertIsDisplayed()
    }
}
