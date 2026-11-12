package com.adv.core.engine.impl

import com.adv.bb.core.ApkSummary
import com.adv.bb.core.BbException
import com.adv.bb.core.InstancePolicy
import com.adv.bb.core.InstanceStatus
import com.adv.core.common.EngineError
import com.adv.core.common.EngineErrorCode
import com.adv.core.common.EngineResult
import com.adv.core.engine.api.ApkInfo
import com.adv.core.engine.api.InstancePolicySpec
import com.adv.core.engine.api.VirtualInstance
import com.adv.core.engine.api.VirtualInstanceState
import kotlinx.coroutines.CancellationException
import java.io.File

internal fun InstancePolicySpec.toNative(): InstancePolicy = InstancePolicy(
    allowNetwork = allowNetwork,
    allowExternalStorage = allowExternalStorage,
    allowFileUriExposure = allowFileUriExposure,
    maxStorageBytes = maxStorageBytes,
    allowBackgroundStart = allowBackgroundStart,
)

internal fun InstanceStatus.toVirtual(): VirtualInstance = VirtualInstance(
    instanceId = instanceId,
    packageName = packageName,
    state = VirtualInstanceState.fromNativeName(state.nativeName),
    refs = refs,
    updatedAtMs = updatedAtMs,
    lastError = lastError,
)

internal fun ApkSummary.toApkInfo(file: File, sha256: String?): ApkInfo = ApkInfo(
    packageName = packageName,
    versionCode = versionCode,
    versionName = versionName,
    minSdk = minSdk,
    targetSdk = targetSdk,
    permissions = permissions,
    launcherActivity = launcherActivity,
    nativeAbis = nativeAbis,
    activityCount = activityCount,
    fileSizeBytes = if (fileSize > 0L) fileSize else file.length(),
    localApkPath = file.absolutePath,
    sha256 = sha256,
)

internal fun BbException.toEngineError(): EngineError =
    EngineError(EngineErrorCode.fromNativeName(codeName), message ?: codeName, this)

/**
 * Boundary guard: every native call goes through here so one failure mode maps
 * to one [EngineErrorCode]. Cancellation is rethrown — a cancelled coroutine is
 * not an engine error.
 */
internal inline fun <T> engineCall(operation: String, block: () -> T): EngineResult<T> = try {
    EngineResult.Success(block())
} catch (error: Throwable) {
    when (error) {
        is CancellationException -> throw error
        is BbException -> EngineResult.Failure(error.toEngineError())
        is UnsatisfiedLinkError, is ExceptionInInitializerError -> EngineResult.Failure(
            EngineError(
                EngineErrorCode.NATIVE_UNAVAILABLE,
                "libbb_native.so could not be loaded: ${error.message ?: error::class.java.simpleName}",
                error,
            ),
        )
        is java.io.IOException -> EngineResult.Failure(
            EngineError(EngineErrorCode.IO, "$operation failed: ${error.message ?: "I/O error"}", error),
        )
        is SecurityException -> EngineResult.Failure(
            EngineError(EngineErrorCode.PERMISSION_DENIED, "$operation denied: ${error.message ?: "security"}", error),
        )
        else -> EngineResult.Failure(
            EngineError(EngineErrorCode.INTERNAL, "$operation failed: ${error.message ?: error::class.java.simpleName}", error),
        )
    }
}
