package com.example.core.i18n

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * اللغات المدعومة في واجهة NetBalancer (القسم 9).
 */
enum class AppLanguage(
    val code: String,
    val nativeLabel: String,
    val shortBadge: String,
    val isRtl: Boolean
) {
    AR("ar", "العربية", "عربي", true),
    EN("en", "English", "EN", false)
}

val LocalAppLanguage = staticCompositionLocalOf { AppLanguage.AR }

/**
 * مساعد نصوص ثنائي اللغة يتبع مبدأ "كلام أقل، معنى أكثر" (القسم 9 و 10).
 */
fun tr(lang: AppLanguage, ar: String, en: String): String {
    return if (lang == AppLanguage.AR) ar else en
}
