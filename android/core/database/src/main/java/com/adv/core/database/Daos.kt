package com.adv.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {

    @Query(
        """
        SELECT apps.*, (
            SELECT COUNT(*) FROM instances WHERE instances.appId = apps.appId
        ) AS instanceCount
        FROM apps
        ORDER BY importedAtMs DESC
        """,
    )
    fun observeAll(): Flow<List<AppWithCount>>

    @Query(
        """
        SELECT apps.*, (
            SELECT COUNT(*) FROM instances WHERE instances.appId = apps.appId
        ) AS instanceCount
        FROM apps WHERE appId = :appId
        """,
    )
    fun observe(appId: String): Flow<AppWithCount?>

    @Query("SELECT * FROM apps WHERE appId = :appId")
    suspend fun find(appId: String): AppEntity?

    @Query("SELECT * FROM apps")
    suspend fun all(): List<AppEntity>

    @Query("SELECT COUNT(*) FROM instances WHERE appId = :appId")
    suspend fun countInstances(appId: String): Int

    @Query("UPDATE apps SET label = :label, iconPath = :iconPath WHERE appId = :appId")
    suspend fun updateMetadata(appId: String, label: String, iconPath: String?)

    @Upsert
    suspend fun upsert(app: AppEntity)

    @Query("DELETE FROM apps WHERE appId = :appId")
    suspend fun delete(appId: String)
}

@Dao
interface InstanceDao {

    @Upsert
    suspend fun upsert(instance: InstanceEntity)

    @Query("DELETE FROM instances WHERE instanceId = :instanceId")
    suspend fun delete(instanceId: String)

    @Query("UPDATE instances SET displayName = :displayName, updatedAtMs = :nowMs WHERE instanceId = :instanceId")
    suspend fun rename(instanceId: String, displayName: String, nowMs: Long)

    @Query(
        """
        UPDATE instances
        SET state = :state, updatedAtMs = :nowMs, lastError = :lastError
        WHERE instanceId = :instanceId
        """,
    )
    suspend fun updateState(instanceId: String, state: String, lastError: String?, nowMs: Long)

    @Query("UPDATE instances SET lastLaunchedAtMs = :atMs WHERE instanceId = :instanceId")
    suspend fun markLaunched(instanceId: String, atMs: Long)

    @Query("SELECT * FROM instances WHERE instanceId = :instanceId")
    suspend fun find(instanceId: String): InstanceEntity?

    @Query("SELECT * FROM instances")
    suspend fun all(): List<InstanceEntity>

    @Query("SELECT COUNT(*) FROM instances")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM instances WHERE state IN ('STARTING', 'RUNNING', 'STOPPING')")
    fun observeRunningCount(): Flow<Int>

    @Query(
        """
        SELECT instances.*,
               apps.versionName AS versionName,
               COALESCE(NULLIF(apps.versionName, ''), CAST(apps.versionCode AS TEXT)) AS versionLabel,
               apps.apkPath AS apkPath,
               apps.signatureDigest AS apkSha256,
               apps.label AS appLabel,
               apps.iconPath AS iconPath
        FROM instances
        LEFT JOIN apps ON apps.appId = instances.appId
        ORDER BY instances.createdAtMs DESC, instances.instanceId ASC
        """,
    )
    fun observeOverviews(): Flow<List<InstanceOverviewRow>>

    @Query(
        """
        SELECT instances.*,
               apps.versionName AS versionName,
               COALESCE(NULLIF(apps.versionName, ''), CAST(apps.versionCode AS TEXT)) AS versionLabel,
               apps.apkPath AS apkPath,
               apps.signatureDigest AS apkSha256,
               apps.label AS appLabel,
               apps.iconPath AS iconPath
        FROM instances
        LEFT JOIN apps ON apps.appId = instances.appId
        WHERE instances.instanceId = :instanceId
        """,
    )
    fun observeOverview(instanceId: String): Flow<InstanceOverviewRow?>
}

@Dao
interface PermissionDao {

    @Query("SELECT * FROM permissions WHERE instanceId = :instanceId ORDER BY permission")
    fun observeFor(instanceId: String): Flow<List<PermissionEntity>>

    @Query("SELECT * FROM permissions WHERE instanceId = :instanceId")
    suspend fun findFor(instanceId: String): List<PermissionEntity>

    @Upsert
    suspend fun upsert(permission: PermissionEntity)

    @Upsert
    suspend fun upsertAll(permissions: List<PermissionEntity>)

    @Query("DELETE FROM permissions WHERE instanceId = :instanceId")
    suspend fun clear(instanceId: String)
}

@Dao
interface OperationDao {

    @Upsert
    suspend fun upsert(record: OperationRecordEntity)

    @Query("SELECT * FROM operation_records ORDER BY createdAtMs DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<OperationRecordEntity>>

    @Query(
        """
        SELECT * FROM operation_records
        WHERE instanceId = :instanceId
        ORDER BY createdAtMs DESC LIMIT :limit
        """,
    )
    fun observeForInstance(instanceId: String, limit: Int): Flow<List<OperationRecordEntity>>

    /** 卸载实例时连操作记录一起清掉，别在「最近记录」里留下指向已删实例的孤儿行。 */
    @Query("DELETE FROM operation_records WHERE instanceId = :instanceId")
    suspend fun deleteForInstance(instanceId: String)
}

@Dao
interface BackupDao {

    @Upsert
    suspend fun upsert(record: BackupRecordEntity)

    @Query("SELECT * FROM backup_records WHERE instanceId = :instanceId ORDER BY createdAtMs DESC")
    fun observeFor(instanceId: String): Flow<List<BackupRecordEntity>>

    @Query("SELECT * FROM backup_records WHERE backupId = :backupId")
    suspend fun find(backupId: String): BackupRecordEntity?

    @Query("DELETE FROM backup_records WHERE backupId = :backupId")
    suspend fun delete(backupId: String)

    /** 卸载实例时先把备份列出来，磁盘上的 zip 也要跟着删，否则留下孤儿文件。 */
    @Query("SELECT * FROM backup_records WHERE instanceId = :instanceId")
    suspend fun allFor(instanceId: String): List<BackupRecordEntity>
}

@Dao
interface ConfigDao {

    @Query("SELECT * FROM engine_config")
    fun observeAll(): Flow<List<EngineConfigEntity>>

    @Query("SELECT * FROM engine_config")
    suspend fun all(): List<EngineConfigEntity>

    @Query("SELECT * FROM engine_config WHERE key = :key")
    suspend fun find(key: String): EngineConfigEntity?

    @Query("SELECT * FROM engine_config WHERE key = :key")
    fun observe(key: String): Flow<EngineConfigEntity?>

    @Upsert
    suspend fun upsert(entry: EngineConfigEntity)

    @Query("DELETE FROM engine_config WHERE key = :key")
    suspend fun delete(key: String)
}
