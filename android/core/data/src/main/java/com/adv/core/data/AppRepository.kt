package com.adv.core.data

import com.adv.core.common.EngineError
import com.adv.core.common.EngineErrorCode
import com.adv.core.common.EngineResult
import com.adv.core.common.appKey
import com.adv.core.common.engineRunCatching
import com.adv.core.database.AppDao
import com.adv.core.database.AppEntity
import com.adv.core.engine.api.ApkInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

class AppRepository(
    private val provider: EngineProvider,
    private val appDao: AppDao,
    private val log: LogBuffer,
    private val metadata: ApkMetadata,
) {

    fun observeApps(): Flow<List<AppSummary>> =
        appDao.observeAll().map { rows -> rows.map { it.toSummary() } }

    fun observeApp(appId: String): Flow<AppSummary?> =
        appDao.observe(appId).map { it?.toSummary() }

    /** Read-only APK inspection used by the import preview. */
    suspend fun inspectApk(apkUri: String): EngineResult<ApkInfo> =
        provider.use { engine -> engine.inspectApk(apkUri) }

    /**
     * Inspects the picked file and records one `apps` row. Re-importing an
     * identical build replaces the row instead of duplicating it (the app id is
     * derived from package + version + digest).
     */
    suspend fun importApk(apkUri: String): EngineResult<AppSummary> {
        val inspected = provider.use { engine -> engine.inspectApk(apkUri) }
        val info = when (inspected) {
            is EngineResult.Failure -> return inspected
            is EngineResult.Success -> inspected.value
        }

        return engineRunCatching {
            val appId = appKey(info.packageName, info.versionCode, info.sha256)
            // The id is deterministic, so an identical re-import upserts the same
            // row. Without this the previous staged copy would leak: a 200 MB APK
            // duplicated on every import attempt.
            reclaimPreviousCopy(appId, info.localApkPath)
            // Label + icon come from the platform parser; keep the package name
            // as the fallback rather than showing nothing.
            val resolved = metadata.resolve(info.localApkPath, appId)
            val entity = AppEntity(
                appId = appId,
                packageName = info.packageName,
                versionCode = info.versionCode,
                versionName = info.versionName,
                signatureDigest = info.sha256,
                minSdk = info.minSdk,
                targetSdk = info.targetSdk,
                apkPath = info.localApkPath,
                fileSizeBytes = info.fileSizeBytes,
                // 引擎解析不出 launcher 时用平台解析的兜底：二次打包的包常把
                // MAIN/LAUNCHER 的 intent-filter 删掉，空 launcher 的实例起不来。
                launcherActivity = info.launcherActivity?.takeIf { it.isNotBlank() }
                    ?: resolved?.launcherActivity,
                permissionsJson = encodeStringList(info.permissions),
                nativeAbisJson = encodeStringList(info.nativeAbis),
                importedAtMs = System.currentTimeMillis(),
                label = resolved?.label.orEmpty(),
                iconPath = resolved?.iconPath,
            )
            appDao.upsert(entity)
            log.info("已导入 ${resolved?.label ?: info.packageName} v${info.versionName ?: info.versionCode}")
            entity.toSummary(appDao.countInstances(appId))
        }
    }

    /**
     * Fills label/icon for rows imported before schema v2 (or when the platform
     * failed to parse the archive the first time). Safe to call repeatedly: rows
     * that already have both values are skipped.
     */
    suspend fun backfillMetadata() {
        appDao.all().forEach { app ->
            if (app.label.isNotBlank() && app.iconPath != null) return@forEach
            if (!File(app.apkPath).isFile) return@forEach
            val resolved = metadata.resolve(app.apkPath, app.appId) ?: return@forEach
            runCatching {
                appDao.updateMetadata(
                    appId = app.appId,
                    label = resolved.label,
                    iconPath = resolved.iconPath ?: app.iconPath,
                )
            }
        }
    }

    /** Refuses to drop an APK that still backs an instance. */
    suspend fun deleteApp(appId: String): EngineResult<Unit> {
        val inUse = appDao.countInstances(appId)
        if (inUse > 0) {
            return EngineResult.Failure(
                EngineError(EngineErrorCode.INVALID_STATE, "仍有 $inUse 个实例使用该应用"),
            )
        }
        return engineRunCatching {
            appDao.find(appId)?.let { previous ->
                // The row owns a private copy staged at import time; dropping the
                // row without the file would strand hundreds of megabytes.
                runCatching { File(previous.apkPath).delete() }
                previous.iconPath?.let { path -> runCatching { File(path).delete() } }
            }
            appDao.delete(appId)
            log.info("已移除应用记录 $appId")
        }
    }

    /** Deletes the staged copy an earlier import of the same build left behind. */
    private suspend fun reclaimPreviousCopy(appId: String, incomingPath: String) {
        val previous = appDao.find(appId) ?: return
        if (previous.apkPath == incomingPath) return
        val old = File(previous.apkPath)
        val incomingDir = File(incomingPath).parentFile ?: return
        // Only ever delete our own staging copies, never the user's document.
        if (old.parentFile?.absolutePath != incomingDir.absolutePath) return
        runCatching { old.delete() }
    }
}
