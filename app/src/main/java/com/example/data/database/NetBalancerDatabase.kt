package com.example.data.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.core.model.ActiveNetworkTransport
import com.example.core.model.AuditEventType
import com.example.core.model.AuditLogItem
import com.example.core.model.NetworkTypeCondition
import com.example.core.model.PolicyPreset
import com.example.core.model.QuotaLimitAction
import com.example.core.model.QuotaPeriod
import com.example.core.model.QuotaRule
import com.example.core.model.RuleDurationMode
import com.example.core.model.TrafficRule
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "traffic_rules")
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val uid: Int,
    val packageName: String,
    val appName: String,
    val presetName: String,
    val maxDownloadBytesPerSec: Long,
    val maxUploadBytesPerSec: Long,
    val networkConditionName: String,
    val durationModeName: String,
    val expiresAtEpochMillis: Long?,
    val priority: Int,
    val isEnabled: Boolean,
    val notes: String,
    val updatedAtMillis: Long
) {
    fun toDomain(): TrafficRule = TrafficRule(
        id = id,
        uid = uid,
        packageName = packageName,
        appName = appName,
        preset = runCatching { PolicyPreset.valueOf(presetName) }.getOrDefault(PolicyPreset.ALLOW),
        maxDownloadBytesPerSec = maxDownloadBytesPerSec,
        maxUploadBytesPerSec = maxUploadBytesPerSec,
        networkCondition = runCatching { NetworkTypeCondition.valueOf(networkConditionName) }.getOrDefault(NetworkTypeCondition.ANY),
        durationMode = runCatching { RuleDurationMode.valueOf(durationModeName) }.getOrDefault(RuleDurationMode.PERMANENT),
        expiresAtEpochMillis = expiresAtEpochMillis,
        priority = priority,
        isEnabled = isEnabled,
        notes = notes,
        updatedAtMillis = updatedAtMillis
    )

    companion object {
        fun fromDomain(rule: TrafficRule): RuleEntity = RuleEntity(
            id = rule.id,
            uid = rule.uid,
            packageName = rule.packageName,
            appName = rule.appName,
            presetName = rule.preset.name,
            maxDownloadBytesPerSec = rule.maxDownloadBytesPerSec,
            maxUploadBytesPerSec = rule.maxUploadBytesPerSec,
            networkConditionName = rule.networkCondition.name,
            durationModeName = rule.durationMode.name,
            expiresAtEpochMillis = rule.expiresAtEpochMillis,
            priority = rule.priority,
            isEnabled = rule.isEnabled,
            notes = rule.notes,
            updatedAtMillis = rule.updatedAtMillis
        )
    }
}

@Entity(tableName = "quota_rules")
data class QuotaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val uid: Int,
    val packageName: String,
    val appName: String,
    val periodName: String,
    val limitBytes: Long,
    val usedBytes: Long,
    val warningPercent: Int,
    val actionOnLimitName: String,
    val networkConditionName: String,
    val periodStartMillis: Long,
    val customDurationDays: Int,
    val isEnabled: Boolean,
    val warningTriggered: Boolean,
    val limitTriggered: Boolean
) {
    fun toDomain(): QuotaRule = QuotaRule(
        id = id,
        uid = uid,
        packageName = packageName,
        appName = appName,
        period = runCatching { QuotaPeriod.valueOf(periodName) }.getOrDefault(QuotaPeriod.DAILY),
        limitBytes = limitBytes,
        usedBytes = usedBytes,
        warningPercent = warningPercent,
        actionOnLimit = runCatching { QuotaLimitAction.valueOf(actionOnLimitName) }.getOrDefault(QuotaLimitAction.THROTTLE_LOW),
        networkCondition = runCatching { NetworkTypeCondition.valueOf(networkConditionName) }.getOrDefault(NetworkTypeCondition.ANY),
        periodStartMillis = periodStartMillis,
        customDurationDays = customDurationDays,
        isEnabled = isEnabled,
        warningTriggered = warningTriggered,
        limitTriggered = limitTriggered
    )

    companion object {
        fun fromDomain(quota: QuotaRule): QuotaEntity = QuotaEntity(
            id = quota.id,
            uid = quota.uid,
            packageName = quota.packageName,
            appName = quota.appName,
            periodName = quota.period.name,
            limitBytes = quota.limitBytes,
            usedBytes = quota.usedBytes,
            warningPercent = quota.warningPercent,
            actionOnLimitName = quota.actionOnLimit.name,
            networkConditionName = quota.networkCondition.name,
            periodStartMillis = quota.periodStartMillis,
            customDurationDays = quota.customDurationDays,
            isEnabled = quota.isEnabled,
            warningTriggered = quota.warningTriggered,
            limitTriggered = quota.limitTriggered
        )
    }
}

/**
 * ملخص استهلاك مجمع لكل تطبيق (لا يخزن الحزم الفردية إطلاقًا التزامًا بالقسم العاشر).
 */
@Entity(tableName = "app_usage_totals")
data class AppUsageTotalEntity(
    @PrimaryKey val uid: Int,
    val packageName: String,
    val appName: String,
    val todayDownloadBytes: Long,
    val todayUploadBytes: Long,
    val weekTotalBytes: Long,
    val monthTotalBytes: Long,
    val wifiBytes: Long,
    val cellularBytes: Long,
    val blockedAttemptsCount: Int,
    val lastUpdatedMillis: Long
)

@Entity(tableName = "audit_events")
data class AuditEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestampMillis: Long,
    val eventTypeName: String,
    val uid: Int,
    val packageName: String,
    val appName: String,
    val summaryAr: String,
    val technicalDetails: String,
    val networkTransportName: String
) {
    fun toDomain(): AuditLogItem = AuditLogItem(
        id = id,
        timestampMillis = timestampMillis,
        eventType = runCatching { AuditEventType.valueOf(eventTypeName) }.getOrDefault(AuditEventType.RULE_APPLIED),
        uid = uid,
        packageName = packageName,
        appName = appName,
        summaryAr = summaryAr,
        technicalDetails = technicalDetails,
        networkTransport = runCatching { ActiveNetworkTransport.valueOf(networkTransportName) }.getOrDefault(ActiveNetworkTransport.WIFI)
    )

    companion object {
        fun fromDomain(item: AuditLogItem): AuditEventEntity = AuditEventEntity(
            id = item.id,
            timestampMillis = item.timestampMillis,
            eventTypeName = item.eventType.name,
            uid = item.uid,
            packageName = item.packageName,
            appName = item.appName,
            summaryAr = item.summaryAr,
            technicalDetails = item.technicalDetails,
            networkTransportName = item.networkTransport.name
        )
    }
}

@Dao
interface NetBalancerDao {
    // القواعد (Traffic Rules)
    @Query("SELECT * FROM traffic_rules ORDER BY priority DESC, updatedAtMillis DESC")
    fun observeAllRules(): Flow<List<RuleEntity>>

    @Query("SELECT * FROM traffic_rules ORDER BY priority DESC, updatedAtMillis DESC")
    suspend fun getAllRulesOnce(): List<RuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRule(rule: RuleEntity): Long

    @Query("DELETE FROM traffic_rules WHERE id = :ruleId")
    suspend fun deleteRuleById(ruleId: Long)

    @Query("DELETE FROM traffic_rules WHERE uid = :uid")
    suspend fun deleteRulesForUid(uid: Int)

    // الحصص (Quotas)
    @Query("SELECT * FROM quota_rules ORDER BY id DESC")
    fun observeAllQuotas(): Flow<List<QuotaEntity>>

    @Query("SELECT * FROM quota_rules ORDER BY id DESC")
    suspend fun getAllQuotasOnce(): List<QuotaEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertQuota(quota: QuotaEntity): Long

    @Query("DELETE FROM quota_rules WHERE id = :quotaId")
    suspend fun deleteQuotaById(quotaId: Long)

    // الإحصاءات المجمعة (Aggregated Usage)
    @Query("SELECT * FROM app_usage_totals")
    fun observeAllUsageTotals(): Flow<List<AppUsageTotalEntity>>

    @Query("SELECT * FROM app_usage_totals")
    suspend fun getAllUsageTotalsOnce(): List<AppUsageTotalEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUsageTotals(items: List<AppUsageTotalEntity>)

    @Query("DELETE FROM app_usage_totals")
    suspend fun clearAllUsageTotals()

    // سجل الأحداث (Audit Log)
    @Query("SELECT * FROM audit_events ORDER BY timestampMillis DESC LIMIT 250")
    fun observeRecentAuditEvents(): Flow<List<AuditEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAuditEvent(event: AuditEventEntity): Long

    @Query("DELETE FROM audit_events WHERE timestampMillis < :cutoffMillis")
    suspend fun pruneAuditEventsOlderThan(cutoffMillis: Long)

    @Query("DELETE FROM audit_events")
    suspend fun clearAllAuditEvents()
}

@Database(
    entities = [
        RuleEntity::class,
        QuotaEntity::class,
        AppUsageTotalEntity::class,
        AuditEventEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class NetBalancerDatabase : RoomDatabase() {
    abstract fun dao(): NetBalancerDao

    companion object {
        @Volatile
        private var instance: NetBalancerDatabase? = null

        fun getInstance(context: Context): NetBalancerDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    NetBalancerDatabase::class.java,
                    "netbalancer_local.db"
                ).fallbackToDestructiveMigration(dropAllTables = true).build().also { instance = it }
            }
        }
    }
}
