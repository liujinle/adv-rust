package com.adv.core.engine.api

/**
 * Snapshot of an APK as the native parser sees it, plus what the client can
 * derive locally (content digest, staged copy path).
 *
 * The engine does not expose the signing certificate in this version, so
 * [sha256] is the digest of the APK file — labelled as such in the UI instead of
 * pretending to be a certificate fingerprint.
 */
data class ApkInfo(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val minSdk: Int,
    val targetSdk: Int,
    val permissions: List<String>,
    val launcherActivity: String?,
    val nativeAbis: List<String>,
    val activityCount: Int,
    val fileSizeBytes: Long,
    val localApkPath: String,
    val sha256: String?,
)

/** Guest lifecycle as reported by the engine's state machine. */
enum class VirtualInstanceState {
    CREATED,
    PREPARING,
    INSTALLED,
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    FAILED,
    ;

    val isActive: Boolean get() = this == STARTING || this == RUNNING || this == STOPPING

    val canLaunch: Boolean get() = this == INSTALLED || this == STOPPED

    val canStop: Boolean get() = this == STARTING || this == RUNNING

    val label: String
        get() = when (this) {
            CREATED -> "已创建"
            PREPARING -> "准备中"
            INSTALLED -> "已安装"
            STARTING -> "启动中"
            RUNNING -> "运行中"
            STOPPING -> "停止中"
            STOPPED -> "已停止"
            FAILED -> "失败"
        }

    companion object {
        fun fromNativeName(name: String?): VirtualInstanceState =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: CREATED
    }
}

data class VirtualInstance(
    val instanceId: String,
    val packageName: String,
    val state: VirtualInstanceState,
    val refs: Int,
    val updatedAtMs: Long,
    val lastError: String?,
)

/**
 * Product-level policy handed to the engine at install time.
 *
 * Not a security boundary and not an Android permission grant: the guest shares
 * the host UID, so this only decides which virtual-layer proxies answer calls.
 */
data class InstancePolicySpec(
    val allowNetwork: Boolean = true,
    val allowExternalStorage: Boolean = false,
    val allowFileUriExposure: Boolean = false,
    val maxStorageBytes: Long = 512L * 1024L * 1024L,
    val allowBackgroundStart: Boolean = false,
)

/**
 * Widen the quota so a large APK can actually be started.
 *
 * The quota is checked against the *instance directory*, not the APK: the
 * extracted native libs, dex cache and the app's own data all count. 红果's
 * 252 MB APK lands at ~600 MB right after install, so the fixed 512 MB default
 * turned "installed" into "starts with TOO_LARGE, forever" — the policy is
 * frozen at creation time, so there is no way back from the UI.
 *
 * Six times the archive size, never below the configured default.
 *
 * 3× was still too tight on 7.4.6.32: the guest downloads 14 Mira plugin jars
 * into `files/.patchs` (measured ~268 MB on top of the APK copy, extracted
 * native libs and caches), taking the instance directory from ~600 MB to
 * ~848 MB while the quota stayed at APK×3 = ~791 MB. `start_instance` calls
 * `vfs.enforce_quota`, so a well-used instance would then fail to launch with
 * TOO_LARGE ("数据超出限制") forever — and the policy is frozen at creation.
 */
fun InstancePolicySpec.withRoomFor(apkBytes: Long): InstancePolicySpec {
    if (apkBytes <= 0L) return this
    return copy(maxStorageBytes = maxOf(maxStorageBytes, apkBytes * 6))
}

data class InstallResult(
    val instanceId: String,
    val packageName: String,
    val state: VirtualInstanceState,
)

/**
 * Everything the engine centre shows. Values the bridge cannot provide stay
 * `null` rather than being guessed.
 */
data class EngineStatus(
    val initialized: Boolean,
    val jniLoaded: Boolean,
    val abiVersion: Int,
    val coreVersion: String?,
    val protocolVersion: Int?,
    val rootPath: String?,
    val virtualRoot: String?,
    val instanceCount: Int,
    val runningCount: Int,
    val recoveredInstances: Int,
    val queuedTasks: Int,
    val activeTasks: Int,
    val cacheEntries: Int,
    val cacheBytes: Long,
    val processCpuPercent: Double?,
    val processPssKb: Long?,
    val hostSdk: Int,
    val startedAtMs: Long?,
    val lastError: String?,
    val compatibilityReport: String?,
)

sealed interface EngineEvent {
    data class InstanceStateChanged(
        val instanceId: String,
        val from: VirtualInstanceState?,
        val to: VirtualInstanceState,
        val atMs: Long,
        val reason: String,
    ) : EngineEvent

    data class TaskProgress(
        val taskId: Long,
        val name: String,
        val progress: Int,
    ) : EngineEvent

    data class TaskFinished(
        val taskId: Long,
        val name: String,
        val success: Boolean,
        val message: String?,
    ) : EngineEvent

    data class Notice(
        val level: NoticeLevel,
        val message: String,
    ) : EngineEvent
}

enum class NoticeLevel { INFO, WARNING, ERROR }
