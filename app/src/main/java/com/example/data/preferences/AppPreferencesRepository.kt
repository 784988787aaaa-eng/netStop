package com.example.data.preferences

import android.content.Context
import androidx.datastore.preferences.core. booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.core.model.BandwidthUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "netbalancer_preferences")

data class UserSettingsState(
    val isDarkTheme: Boolean = true,
    val bandwidthUnit: BandwidthUnit = BandwidthUnit.KB_PER_SEC,
    val samplingIntervalMs: Long = 1000L,
    val notificationsEnabled: Boolean = true,
    val showSystemApps: Boolean = true,
    val logRetentionDays: Int = 30,
    val autoRestoreRulesOnStartup: Boolean = true
)

/**
 * مخزن الإعدادات التفضيلية المحلي عبر Jetpack DataStore (القسم الثالث والثامن).
 */
class AppPreferencesRepository(private val context: Context) {

    private object Keys {
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val BANDWIDTH_UNIT = stringPreferencesKey("bandwidth_unit")
        val SAMPLING_INTERVAL_MS = longPreferencesKey("sampling_interval_ms")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val SHOW_SYSTEM_APPS = booleanPreferencesKey("show_system_apps")
        val LOG_RETENTION_DAYS = intPreferencesKey("log_retention_days")
        val AUTO_RESTORE_RULES = booleanPreferencesKey("auto_restore_rules")
    }

    val settingsFlow: Flow<UserSettingsState> = context.dataStore.data.map { prefs ->
        val unitName = prefs[Keys.BANDWIDTH_UNIT] ?: BandwidthUnit.KB_PER_SEC.name
        val unit = runCatching { BandwidthUnit.valueOf(unitName) }.getOrDefault(BandwidthUnit.KB_PER_SEC)
        UserSettingsState(
            isDarkTheme = prefs[Keys.DARK_THEME] ?: true,
            bandwidthUnit = unit,
            samplingIntervalMs = (prefs[Keys.SAMPLING_INTERVAL_MS] ?: 1000L).coerceIn(500L, 5000L),
            notificationsEnabled = prefs[Keys.NOTIFICATIONS_ENABLED] ?: true,
            showSystemApps = prefs[Keys.SHOW_SYSTEM_APPS] ?: true,
            logRetentionDays = prefs[Keys.LOG_RETENTION_DAYS] ?: 30,
            autoRestoreRulesOnStartup = prefs[Keys.AUTO_RESTORE_RULES] ?: true
        )
    }

    suspend fun setDarkTheme(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DARK_THEME] = enabled }
    }

    suspend fun setBandwidthUnit(unit: BandwidthUnit) {
        context.dataStore.edit { it[Keys.BANDWIDTH_UNIT] = unit.name }
    }

    suspend fun setSamplingIntervalMs(intervalMs: Long) {
        context.dataStore.edit { it[Keys.SAMPLING_INTERVAL_MS] = intervalMs.coerceIn(500L, 5000L) }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.NOTIFICATIONS_ENABLED] = enabled }
    }

    suspend fun setShowSystemApps(show: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_SYSTEM_APPS] = show }
    }

    suspend fun setLogRetentionDays(days: Int) {
        context.dataStore.edit { it[Keys.LOG_RETENTION_DAYS] = days.coerceIn(1, 365) }
    }

    suspend fun setAutoRestoreRules(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_RESTORE_RULES] = enabled }
    }
}
