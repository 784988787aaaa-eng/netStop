package com.netlimiterpro.domain.model

/** Independent policy for a network transport. */
enum class NetworkPolicy { ALLOW, THROTTLE, BLOCK }

/**
 * Per-application network policy. Legacy enabled flags are retained for JSON/API
 * compatibility; policy is authoritative in v8. ALLOW is the default behavior.
 */
data class AppRule(
    val uid: Int,
    val packageName: String,
    val wifiEnabled: Boolean = false,
    val mobileDataEnabled: Boolean = false,
    val wifiLimitBytesPerSecond: Long = 0L,
    val mobileDataLimitBytesPerSecond: Long = 0L,
    val wifiPolicy: NetworkPolicy = if (wifiEnabled) NetworkPolicy.THROTTLE else NetworkPolicy.ALLOW,
    val mobilePolicy: NetworkPolicy = if (mobileDataEnabled) NetworkPolicy.THROTTLE else NetworkPolicy.ALLOW
) {
    init {
        require(uid >= 0) { "UID must be non-negative" }
        require(packageName.isNotBlank()) { "Package name must not be blank" }
        require(wifiLimitBytesPerSecond >= 0L) { "Wi-Fi limit must be non-negative" }
        require(mobileDataLimitBytesPerSecond >= 0L) { "Mobile data limit must be non-negative" }
    }

    fun policyFor(isWifi: Boolean): NetworkPolicy = if (isWifi) wifiPolicy else mobilePolicy
    fun limitFor(isWifi: Boolean): Long = if (isWifi) wifiLimitBytesPerSecond else mobileDataLimitBytesPerSecond
    fun isEnabledFor(isWifi: Boolean): Boolean = policyFor(isWifi) != NetworkPolicy.ALLOW
}
