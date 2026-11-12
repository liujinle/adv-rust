package com.adv.core.data

import com.adv.core.database.AppEntity
import com.adv.core.database.AppWithCount
import com.adv.core.database.BackupRecordEntity
import com.adv.core.database.InstanceEntity
import com.adv.core.database.InstanceOverviewRow
import com.adv.core.database.OperationRecordEntity
import com.adv.core.database.PermissionEntity
import com.adv.core.engine.api.InstancePolicySpec
import com.adv.core.engine.api.VirtualInstanceState
import org.json.JSONArray
import org.json.JSONObject

internal fun AppEntity.toSummary(instanceCount: Int): AppSummary = AppSummary(
    appId = appId,
    packageName = packageName,
    versionName = versionName,
    versionCode = versionCode,
    sha256 = signatureDigest,
    apkPath = apkPath,
    fileSizeBytes = fileSizeBytes,
    minSdk = minSdk,
    targetSdk = targetSdk,
    permissions = decodeStringList(permissionsJson),
    nativeAbis = decodeStringList(nativeAbisJson),
    launcherActivity = launcherActivity,
    importedAtMs = importedAtMs,
    instanceCount = instanceCount,
    label = label,
    iconPath = iconPath,
)

internal fun AppWithCount.toSummary(): AppSummary = app.toSummary(instanceCount)

internal fun InstanceOverviewRow.toOverview(): InstanceOverview = InstanceOverview(
    instanceId = instance.instanceId,
    displayName = instance.displayName,
    packageName = instance.packageName,
    versionName = versionName,
    state = stateOf(instance.state),
    createdAtMs = instance.createdAtMs,
    updatedAtMs = instance.updatedAtMs,
    lastLaunchedAtMs = instance.lastLaunchedAtMs,
    lastError = localizeEngineMessage(instance.lastError),
    dataPath = instance.dataPath,
    iconPath = iconPath,
    appLabel = appLabel,
    versionLabel = versionLabel,
)

internal fun InstanceEntity.toOverview(): InstanceOverview = InstanceOverview(
    instanceId = instanceId,
    displayName = displayName,
    packageName = packageName,
    versionName = null,
    state = stateOf(state),
    createdAtMs = createdAtMs,
    updatedAtMs = updatedAtMs,
    lastLaunchedAtMs = lastLaunchedAtMs,
    lastError = localizeEngineMessage(lastError),
    dataPath = dataPath,
)

/**
 * Engine diagnostics are English one-liners written by the Rust core. The UI must
 * not dump them verbatim, but silently hiding them loses the only clue about a
 * failed launch — so translate the known ones and mark the rest as diagnostics.
 */
internal fun localizeEngineMessage(raw: String?): String? {
    val message = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return when (message) {
        "recovered after unclean shutdown" -> "宿主进程异常退出，已自动恢复为已停止"
        "crashed" -> "虚拟进程崩溃"
        else -> message
    }
}

internal fun PermissionEntity.toRow(): PermissionRow =
    PermissionRow(permission = permission, policy = PermissionPolicy.fromName(policy))

internal fun OperationRecordEntity.toRow(): OperationRow = OperationRow(
    taskId = taskId,
    operation = operation,
    status = status,
    createdAtMs = createdAtMs,
    finishedAtMs = finishedAtMs,
    message = message,
)

internal fun BackupRecordEntity.toRow(): BackupRow = BackupRow(
    backupId = backupId,
    createdAtMs = createdAtMs,
    sizeBytes = sizeBytes,
    status = status,
)

internal fun stateOf(name: String): VirtualInstanceState = VirtualInstanceState.fromNativeName(name)

internal fun encodeStringList(values: List<String>): String = JSONArray(values).toString()

internal fun decodeStringList(raw: String?): List<String> = runCatching {
    val array = JSONArray(raw ?: "[]")
    (0 until array.length()).map { index -> array.optString(index) }
}.getOrDefault(emptyList())

internal fun InstancePolicySpec.toJsonString(): String = JSONObject()
    .put("allow_network", allowNetwork)
    .put("allow_external_storage", allowExternalStorage)
    .put("allow_file_uri_exposure", allowFileUriExposure)
    .put("max_storage_bytes", maxStorageBytes)
    .put("allow_background_start", allowBackgroundStart)
    .toString()

internal fun parsePolicy(raw: String?): InstancePolicySpec {
    if (raw.isNullOrBlank()) return InstancePolicySpec()
    return runCatching {
        val json = JSONObject(raw)
        InstancePolicySpec(
            allowNetwork = json.optBoolean("allow_network", true),
            allowExternalStorage = json.optBoolean("allow_external_storage", false),
            allowFileUriExposure = json.optBoolean("allow_file_uri_exposure", false),
            maxStorageBytes = json.optLong("max_storage_bytes", 512L * 1024L * 1024L),
            allowBackgroundStart = json.optBoolean("allow_background_start", false),
        )
    }.getOrDefault(InstancePolicySpec())
}
