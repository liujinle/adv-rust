package com.adv.core.domain

import com.adv.core.common.EngineResult
import com.adv.core.data.AppRepository
import com.adv.core.data.AppSummary
import com.adv.core.data.SettingsRepository
import com.adv.core.engine.api.ApkInfo
import com.adv.core.engine.api.withRoomFor
import kotlinx.coroutines.flow.Flow

class ObserveAppsUseCase(private val apps: AppRepository) {
    operator fun invoke(): Flow<List<AppSummary>> = apps.observeApps()
}

class ObserveAppUseCase(private val apps: AppRepository) {
    operator fun invoke(appId: String): Flow<AppSummary?> = apps.observeApp(appId)
}

/**
 * One call does inspect + persist: the app library never shows a half-imported
 * row, and the APK copy is already in place when the row appears.
 */
class ImportApkUseCase(private val apps: AppRepository) {
    suspend operator fun invoke(apkUri: String): EngineResult<AppSummary> = apps.importApk(apkUri)
}

class InspectApkUseCase(private val apps: AppRepository) {
    suspend operator fun invoke(apkUri: String): EngineResult<ApkInfo> = apps.inspectApk(apkUri)
}

class DeleteAppUseCase(private val apps: AppRepository) {
    suspend operator fun invoke(appId: String): EngineResult<Unit> = apps.deleteApp(appId)
}

/**
 * Outcome of one "pick an APK and it is installed" action.
 *
 * [instanceId] is the instance created by this import, or `null` when the same
 * build was already installed and no duplicate was made.
 */
data class InstallResult(
    val app: AppSummary,
    val instanceId: String?,
    val existingInstances: Int,
)

/**
 * Import + install in a single step.
 *
 * The picker already asked the user which app they want, so a second screen
 * asking for an instance name is pure friction. The instance is created with the
 * app label and the default policy; re-importing an identical build does not
 * create a second instance, it reports the existing install instead.
 */
class InstallApkUseCase(
    private val importApk: ImportApkUseCase,
    private val createInstance: CreateInstanceUseCase,
    private val settings: SettingsRepository,
) {
    suspend operator fun invoke(apkUri: String): EngineResult<InstallResult> {
        val app = when (val imported = importApk(apkUri)) {
            is EngineResult.Failure -> return imported
            is EngineResult.Success -> imported.value
        }
        if (app.installedInVirtual) {
            return EngineResult.Success(
                InstallResult(app, instanceId = null, existingInstances = app.instanceCount),
            )
        }
        val policy = settings.load().defaultPolicy.withRoomFor(app.fileSizeBytes)
        return when (val created = createInstance(app.appId, app.displayName, policy)) {
            is EngineResult.Failure -> created
            is EngineResult.Success -> EngineResult.Success(
                InstallResult(app, instanceId = created.value, existingInstances = 1),
            )
        }
    }
}
