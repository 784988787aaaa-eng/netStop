package com.netlimiterpro.data.repository

import android.content.Context
import com.netlimiterpro.domain.model.AppRule
import com.netlimiterpro.domain.model.NetworkPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Persistent local repository for application rules and the default rate.
 *
 * Updates are serialized and written atomically through SharedPreferences.
 * The repository intentionally performs no network calls.
 */
class RuleRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )
    private val lock = Any()

    fun getDefaultLimitBytesPerSecond(): Long =
        preferences.getLong(KEY_DEFAULT_LIMIT, DEFAULT_LIMIT_BYTES_PER_SECOND).coerceAtLeast(0L)

    @Throws(IOException::class)
    fun setDefaultLimitBytesPerSecond(bytesPerSecond: Long) {
        require(bytesPerSecond >= 0L) { "Default limit must be non-negative" }
        synchronized(lock) {
            if (!preferences.edit().putLong(KEY_DEFAULT_LIMIT, bytesPerSecond).commit()) {
                throw IOException("Unable to persist default limit")
            }
        }
    }

    fun getAllRules(): List<AppRule> = synchronized(lock) {
        decodeRules(preferences.getString(KEY_RULES, "[]").orEmpty())
    }

    fun getRule(uid: Int, packageName: String): AppRule? =
        getAllRules().firstOrNull { it.uid == uid && it.packageName == packageName }

    @Throws(IOException::class)
    fun saveRule(rule: AppRule) {
        synchronized(lock) {
            val rules = getAllRules().associateBy { keyFor(it.uid, it.packageName) }.toMutableMap()
            rules[keyFor(rule.uid, rule.packageName)] = rule
            persistRules(rules.values.toList())
        }
    }

    @Throws(IOException::class)
    fun saveRules(rules: Collection<AppRule>) {
        synchronized(lock) {
            val merged = getAllRules().associateBy { keyFor(it.uid, it.packageName) }.toMutableMap()
            rules.forEach { merged[keyFor(it.uid, it.packageName)] = it }
            persistRules(merged.values.toList())
        }
    }

    @Throws(IOException::class)
    fun removeRule(uid: Int, packageName: String) {
        synchronized(lock) {
            val remaining = getAllRules().filterNot { it.uid == uid && it.packageName == packageName }
            persistRules(remaining)
        }
    }

    private fun persistRules(rules: List<AppRule>) {
        val encoded = JSONArray()
        rules.sortedWith(compareBy<AppRule> { it.uid }.thenBy { it.packageName }).forEach { rule ->
            encoded.put(JSONObject().apply {
                put(JSON_UID, rule.uid)
                put(JSON_PACKAGE, rule.packageName)
                put(JSON_WIFI_ENABLED, rule.wifiEnabled)
                put(JSON_MOBILE_ENABLED, rule.mobileDataEnabled)
                put(JSON_WIFI_LIMIT, rule.wifiLimitBytesPerSecond)
                put(JSON_MOBILE_LIMIT, rule.mobileDataLimitBytesPerSecond)
                put(JSON_WIFI_POLICY, rule.wifiPolicy.name)
                put(JSON_MOBILE_POLICY, rule.mobilePolicy.name)
            })
        }
        if (!preferences.edit().putString(KEY_RULES, encoded.toString()).commit()) {
            throw IOException("Unable to persist application rules")
        }
    }

    private fun decodeRules(raw: String): List<AppRule> {
        return try {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val uid = item.optInt(JSON_UID, -1)
                    val packageName = item.optString(JSON_PACKAGE, "")
                    if (uid < 0 || packageName.isBlank()) continue
                    val wifiLimit = item.optLong(JSON_WIFI_LIMIT, 0L).coerceAtLeast(0L)
                    val mobileLimit = item.optLong(JSON_MOBILE_LIMIT, 0L).coerceAtLeast(0L)
                    add(
                        AppRule(
                            uid = uid,
                            packageName = packageName,
                            wifiEnabled = item.optBoolean(JSON_WIFI_ENABLED, false),
                            mobileDataEnabled = item.optBoolean(JSON_MOBILE_ENABLED, false),
                            wifiLimitBytesPerSecond = wifiLimit,
                            mobileDataLimitBytesPerSecond = mobileLimit,
                            wifiPolicy = parsePolicy(item.optString(JSON_WIFI_POLICY, ""), item.optBoolean(JSON_WIFI_ENABLED, false)),
                            mobilePolicy = parsePolicy(item.optString(JSON_MOBILE_POLICY, ""), item.optBoolean(JSON_MOBILE_ENABLED, false))
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parsePolicy(raw: String, legacyEnabled: Boolean): NetworkPolicy =
        runCatching { NetworkPolicy.valueOf(raw) }.getOrElse {
            if (legacyEnabled) NetworkPolicy.THROTTLE else NetworkPolicy.ALLOW
        }

    private fun keyFor(uid: Int, packageName: String): String = "$uid:$packageName"

    companion object {
        private const val PREFERENCES_NAME = "net_limiter_rules"
        private const val KEY_DEFAULT_LIMIT = "default_limit_bytes_per_second"
        private const val KEY_RULES = "app_rules_json"
        private const val DEFAULT_LIMIT_BYTES_PER_SECOND = 250_000L

        private const val JSON_UID = "uid"
        private const val JSON_PACKAGE = "package"
        private const val JSON_WIFI_ENABLED = "wifi_enabled"
        private const val JSON_MOBILE_ENABLED = "mobile_enabled"
        private const val JSON_WIFI_LIMIT = "wifi_limit_bps"
        private const val JSON_MOBILE_LIMIT = "mobile_limit_bps"
        private const val JSON_WIFI_POLICY = "wifi_policy"
        private const val JSON_MOBILE_POLICY = "mobile_policy"
    }
}
