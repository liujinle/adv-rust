package com.adv.core.database

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One APK build. Apps and instances are separate on purpose: the same APK can
 * back several virtual instances, and an instance must keep pointing at the exact
 * version/digest it was created from.
 *
 * [signatureDigest] is the SHA-256 of the APK file. The native core does not yet
 * expose the signing certificate, so this is *not* a certificate fingerprint and
 * the UI labels it accordingly.
 */
@Entity(
    tableName = "apps",
    indices = [Index(value = ["packageName", "versionCode"])],
)
data class AppEntity(
    @PrimaryKey val appId: String,
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val signatureDigest: String?,
    val minSdk: Int,
    val targetSdk: Int,
    val apkPath: String,
    val fileSizeBytes: Long,
    val launcherActivity: String?,
    /** JSON array of requested permissions, kept verbatim from the manifest. */
    val permissionsJson: String,
    /** JSON array of `lib/<abi>` entries present in the APK. */
    val nativeAbisJson: String,
    val importedAtMs: Long,
    /**
     * `android:label` resolved through PackageManager at import time. Empty for
     * rows imported before v2; the UI (and a background backfill) falls back to
     * [packageName] until it is filled in.
     *
     * The default must stay in sync with `MIGRATION_1_2`, otherwise Room rejects
     * the migrated schema at open time.
     */
    @ColumnInfo(defaultValue = "")
    val label: String = "",
    /** Absolute path of the PNG icon copied into app-private storage, if any. */
    val iconPath: String? = null,
)

@Entity(
    tableName = "instances",
    foreignKeys = [
        ForeignKey(
            entity = AppEntity::class,
            parentColumns = ["appId"],
            childColumns = ["appId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("appId")],
)
data class InstanceEntity(
    @PrimaryKey val instanceId: String,
    val appId: String,
    val displayName: String,
    val packageName: String,
    /** Absolute path of the instance data directory (`<root>/virtual/instances/<id>`). */
    val dataPath: String,
    /** `VirtualInstanceState` name, stored as text so the DB survives enum changes. */
    val state: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val lastLaunchedAtMs: Long?,
    val lastError: String?,
)

/**
 * Virtual-layer permission policy. This is *not* an Android runtime grant: the
 * guest shares the host UID, so the UI must never render this as "已授权".
 */
@Entity(
    tableName = "permissions",
    primaryKeys = ["instanceId", "permission"],
    foreignKeys = [
        ForeignKey(
            entity = InstanceEntity::class,
            parentColumns = ["instanceId"],
            childColumns = ["instanceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("instanceId")],
)
data class PermissionEntity(
    val instanceId: String,
    val permission: String,
    val policy: String,
    val updatedAtMs: Long,
)

@Entity(tableName = "engine_config")
data class EngineConfigEntity(
    @PrimaryKey val key: String,
    val value: String,
    val version: Int,
    val updatedAtMs: Long,
)

@Entity(
    tableName = "operation_records",
    indices = [Index("instanceId"), Index("createdAtMs")],
)
data class OperationRecordEntity(
    @PrimaryKey val taskId: String,
    val operation: String,
    /** `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED`. */
    val status: String,
    val instanceId: String?,
    val createdAtMs: Long,
    val finishedAtMs: Long?,
    val message: String?,
)

@Entity(
    tableName = "backup_records",
    indices = [Index("instanceId"), Index("createdAtMs")],
)
data class BackupRecordEntity(
    @PrimaryKey val backupId: String,
    val instanceId: String,
    val backupPath: String,
    val createdAtMs: Long,
    val sizeBytes: Long,
    /** `READY`, `RESTORING`, `RESTORED`, `FAILED`. */
    val status: String,
)

/** Projection: app row plus how many instances currently use it. */
data class AppWithCount(
    @Embedded val app: AppEntity,
    val instanceCount: Int,
)

/** Projection: instance row plus the APK facts its detail page shows. */
data class InstanceOverviewRow(
    @Embedded val instance: InstanceEntity,
    val versionName: String?,
    /** `versionName`, or the version code when the APK has no textual version. */
    val versionLabel: String?,
    val apkPath: String?,
    val apkSha256: String?,
    val appLabel: String?,
    val iconPath: String?,
)
