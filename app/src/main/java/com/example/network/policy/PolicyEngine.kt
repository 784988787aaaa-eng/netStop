package com.example.network.policy

import com.example.core.model.ActiveNetworkTransport
import com.example.core.model.NetworkTypeCondition
import com.example.core.model.PolicyPreset
import com.example.core.model.QuotaLimitAction
import com.example.core.model.QuotaRule
import com.example.core.model.TrafficRule

/**
 * نتيجة تقييم السياسة الفعالة لتطبيق معين في اللحظة الحالية.
 */
data class EffectiveAppPolicy(
    val uid: Int,
    val preset: PolicyPreset,
    val maxDownloadBytesPerSec: Long, // 0 = غير محدود, -1 = حظر
    val maxUploadBytesPerSec: Long,   // 0 = غير محدود, -1 = حظر
    val appliedRuleId: Long? = null,
    val appliedQuotaId: Long? = null,
    val reasonAr: String,
    val conflictResolvedExplanationAr: String? = null
) {
    val isBlocked: Boolean
        get() = preset == PolicyPreset.BLOCK || maxDownloadBytesPerSec < 0L || maxUploadBytesPerSec < 0L

    val isThrottled: Boolean
        get() = !isBlocked && (maxDownloadBytesPerSec > 0L || maxUploadBytesPerSec > 0L)
}

/**
 * محرك السياسات وحسم التعارضات (Policy Engine - القسم الرابع والخامس).
 *
 * ترتيب الأولويات القياسي عند وجود أكثر من قاعدة أو حصة لنفس التطبيق:
 * 1. تجاوز الحصة مع إجراء "حظر الاتصال" (Quota Exceeded -> Block).
 * 2. القواعد المفعلة غير المنتهية والمنطبقة على نوع الشبكة الحالي، مرتبة حسب:
 *    أ) رقم الأولوية (Priority) تنازليًا (100 أعلى من 50).
 *    ب) تخصيص الشبكة (قاعدة مخصصة لـ Wi-Fi أو الهاتف تتقدم على قاعدة "جميع الشبكات" عند تساوي الأولوية).
 *    ج) الصرامة (الحظر يتقدم على تحديد السرعة عند تساوي الأولوية وتخصيص الشبكة).
 *    د) الأحدث تعديلًا (updatedAtMillis).
 * 3. تجاوز الحصة مع إجراء "خفض للسرعة المنخفضة" (يُطبق إذا لم تكن القاعدة الفعالة أكثر تقييدًا).
 * 4. السياسة الافتراضية: سماح كامل (0 = غير محدود).
 */
class PolicyEngine {

    fun evaluateForUid(
        uid: Int,
        rules: List<TrafficRule>,
        quotas: List<QuotaRule>,
        activeTransport: ActiveNetworkTransport,
        nowMillis: Long = System.currentTimeMillis()
    ): EffectiveAppPolicy {
        // 1. فحص الحصص الفعالة والمنطبقة على التطبيق (أو الحصة العامة uid == -1) وعلى نوع الشبكة
        val applicableQuotas = quotas.filter { q ->
            q.isEnabled &&
                (q.uid == uid || q.uid == -1) &&
                matchesNetwork(q.networkCondition, activeTransport)
        }

        val exceededBlockQuota = applicableQuotas.firstOrNull {
            it.isLimitReached && it.actionOnLimit == QuotaLimitAction.BLOCK_CONNECTION
        }
        if (exceededBlockQuota != null) {
            return EffectiveAppPolicy(
                uid = uid,
                preset = PolicyPreset.BLOCK,
                maxDownloadBytesPerSec = -1L,
                maxUploadBytesPerSec = -1L,
                appliedQuotaId = exceededBlockQuota.id,
                reasonAr = "حظر تلقائي لتجاوز ${exceededBlockQuota.period.titleAr} (${exceededBlockQuota.appName})"
            )
        }

        // 2. تصفية القواعد المفعلة وغير المنتهية والمنطبقة على الشبكة الحالية
        val matchingRules = rules.filter { r ->
            r.uid == uid &&
                r.isEnabled &&
                !r.isExpired(nowMillis) &&
                matchesNetwork(r.networkCondition, activeTransport)
        }

        val sortedRules = sortRulesByPriority(matchingRules)
        val winningRule = sortedRules.firstOrNull()

        val conflictNote = if (sortedRules.size > 1 && winningRule != null) {
            val second = sortedRules[1]
            "تم تغليب القاعدة ذات الأولوية (${winningRule.priority}) [${winningRule.preset.titleAr}] على القاعدة ذات الأولوية (${second.priority}) [${second.preset.titleAr}]"
        } else {
            null
        }

        // 3. فحص ما إذا كانت هناك حصة متجاوزة تفرض خفض السرعة إلى المستوى المنخفض
        val exceededThrottleQuota = applicableQuotas.firstOrNull {
            it.isLimitReached && it.actionOnLimit == QuotaLimitAction.THROTTLE_LOW
        }

        if (winningRule != null) {
            if (winningRule.isBlocking()) {
                return EffectiveAppPolicy(
                    uid = uid,
                    preset = PolicyPreset.BLOCK,
                    maxDownloadBytesPerSec = -1L,
                    maxUploadBytesPerSec = -1L,
                    appliedRuleId = winningRule.id,
                    reasonAr = "محظور بموجب قاعدة تحكم (${winningRule.networkCondition.titleAr})",
                    conflictResolvedExplanationAr = conflictNote
                )
            }

            if (exceededThrottleQuota != null) {
                val quotaDown = PolicyPreset.LOW.defaultDownloadBytesPerSec
                val quotaUp = PolicyPreset.LOW.defaultUploadBytesPerSec
                val effectiveDown = if (winningRule.maxDownloadBytesPerSec > 0) {
                    minOf(winningRule.maxDownloadBytesPerSec, quotaDown)
                } else {
                    quotaDown
                }
                val effectiveUp = if (winningRule.maxUploadBytesPerSec > 0) {
                    minOf(winningRule.maxUploadBytesPerSec, quotaUp)
                } else {
                    quotaUp
                }
                return EffectiveAppPolicy(
                    uid = uid,
                    preset = PolicyPreset.LOW,
                    maxDownloadBytesPerSec = effectiveDown,
                    maxUploadBytesPerSec = effectiveUp,
                    appliedRuleId = winningRule.id,
                    appliedQuotaId = exceededThrottleQuota.id,
                    reasonAr = "تقييد مشترك: قاعدة تحكم + خفض سرعة لتجاوز ${exceededThrottleQuota.period.titleAr}",
                    conflictResolvedExplanationAr = conflictNote
                )
            }

            return EffectiveAppPolicy(
                uid = uid,
                preset = winningRule.preset,
                maxDownloadBytesPerSec = winningRule.maxDownloadBytesPerSec,
                maxUploadBytesPerSec = winningRule.maxUploadBytesPerSec,
                appliedRuleId = winningRule.id,
                reasonAr = "قاعدة نشطة: ${winningRule.preset.titleAr} (أولوية ${winningRule.priority})",
                conflictResolvedExplanationAr = conflictNote
            )
        }

        if (exceededThrottleQuota != null) {
            return EffectiveAppPolicy(
                uid = uid,
                preset = PolicyPreset.LOW,
                maxDownloadBytesPerSec = PolicyPreset.LOW.defaultDownloadBytesPerSec,
                maxUploadBytesPerSec = PolicyPreset.LOW.defaultUploadBytesPerSec,
                appliedQuotaId = exceededThrottleQuota.id,
                reasonAr = "خفض تلقائي للسرعة المنخفضة بسبب بلوغ ${exceededThrottleQuota.period.titleAr}"
            )
        }

        return EffectiveAppPolicy(
            uid = uid,
            preset = PolicyPreset.ALLOW,
            maxDownloadBytesPerSec = 0L,
            maxUploadBytesPerSec = 0L,
            reasonAr = "مسموح (الوضع الافتراضي)"
        )
    }

    fun sortRulesByPriority(rules: List<TrafficRule>): List<TrafficRule> {
        return rules.sortedWith(
            compareByDescending<TrafficRule> { it.priority }
                .thenByDescending { networkSpecificityScore(it.networkCondition) }
                .thenByDescending { strictnessScore(it) }
                .thenByDescending { it.updatedAtMillis }
        )
    }

    fun matchesNetwork(condition: NetworkTypeCondition, activeTransport: ActiveNetworkTransport): Boolean {
        return when (condition) {
            NetworkTypeCondition.ANY -> true
            NetworkTypeCondition.WIFI_ONLY -> activeTransport == ActiveNetworkTransport.WIFI
            NetworkTypeCondition.CELLULAR_ONLY -> activeTransport == ActiveNetworkTransport.CELLULAR
        }
    }

    private fun networkSpecificityScore(condition: NetworkTypeCondition): Int {
        return when (condition) {
            NetworkTypeCondition.WIFI_ONLY, NetworkTypeCondition.CELLULAR_ONLY -> 2
            NetworkTypeCondition.ANY -> 1
        }
    }

    private fun strictnessScore(rule: TrafficRule): Int {
        return when {
            rule.isBlocking() -> 100
            rule.maxDownloadBytesPerSec > 0 -> 50
            else -> 10
        }
    }
}
