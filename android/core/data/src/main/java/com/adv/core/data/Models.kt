package com.adv.core.data

import com.adv.core.engine.api.EngineStatus
import com.adv.core.engine.api.InstancePolicySpec
import com.adv.core.engine.api.VirtualInstanceState

/**
 * App list / detail row.
 *
 * [label] comes from `android:label` resolved through PackageManager when the APK
 * is imported; it is empty for rows imported before v2, or when the archive
 * cannot be parsed by the platform. [displayName] then falls back to the package
 * name rather than inventing something.
 */
data class AppSummary(
    val appId: String,
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val sha256: String?,
    val apkPath: String,
    val fileSizeBytes: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val permissions: List<String>,
    val nativeAbis: List<String>,
    val launcherActivity: String?,
    val importedAtMs: Long,
    val instanceCount: Int,
    val label: String = "",
    val iconPath: String? = null,
) {
    val installedInVirtual: Boolean get() = instanceCount > 0

    val displayName: String get() = label.ifBlank { packageName }
}

data class InstanceOverview(
    val instanceId: String,
    val displayName: String,
    val packageName: String,
    val versionName: String?,
    val state: VirtualInstanceState,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val lastLaunchedAtMs: Long?,
    val lastError: String?,
    val dataPath: String,
    val iconPath: String? = null,
    /** `android:label` of the backing APK; blank for pre-v2 rows. */
    val appLabel: String? = null,
    /** `versionName`, or the version code when the APK has no textual version. */
    val versionLabel: String? = null,
) {
    val statusLabel: String get() = state.label

    /** What the instance list shows as the title: app name, not the package. */
    val appName: String get() = appLabel?.takeIf { it.isNotBlank() } ?: displayName

    /** 稳定的展示用短 id；同 APK 同版本实例靠它区分，不受重命名 / 旧数据迁移影响。 */
    val shortId: String get() = InstanceTag.shortId(instanceId)

    /**
     * 列表 / 首页 / 详情顶栏的标题：就是实例名（用户可改，旧数据迁移后是
     * 「应用名」「应用名 2」）。不再拼应用名和短 id —— 标题只有一行、必被
     * `TextOverflow.Ellipsis` 截断，实测只剩「红果免费短剧 · #8fc…」，
     * 名字看不见、两个实例还是分不开。短 id 放到 [subtitle]。
     */
    val title: String get() = displayName.ifBlank { appName }.ifBlank { packageName }

    /**
     * "v7.4.6.32 · #ad6446c5"：版本 + 稳定短 id。
     *
     * 短 id 在副标题里完整显示（副标题同样单行，但只有 8 位，不会被截），
     * 这样两个自动命名成一样的实例也能一眼分开。
     */
    val subtitle: String
        get() = listOfNotNull(
            versionLabel?.takeIf { it.isNotBlank() }?.let { "v$it" },
            InstanceTag.hash(instanceId),
        ).joinToString(" · ")
}

/** Virtual-layer permission state. Never rendered as an Android grant. */
enum class PermissionPolicy(val label: String) {
    ALLOW("虚拟放行"),
    PROMPT("虚拟询问"),
    DENY("虚拟拒绝"),
    ;

    companion object {
        fun fromName(name: String?): PermissionPolicy =
            entries.firstOrNull { it.name == name } ?: PROMPT
    }
}

data class PermissionRow(
    val permission: String,
    val policy: PermissionPolicy,
)

data class OperationRow(
    val taskId: String,
    val operation: String,
    val status: String,
    val createdAtMs: Long,
    val finishedAtMs: Long?,
    val message: String?,
) {
    val isFailed: Boolean get() = status == "FAILED"
}

data class BackupRow(
    val backupId: String,
    val createdAtMs: Long,
    val sizeBytes: Long,
    val status: String,
)

data class InstanceDetails(
    val overview: InstanceOverview,
    val apkPath: String?,
    val apkSha256: String?,
    val policy: InstancePolicySpec,
    val permissions: List<PermissionRow>,
    val backups: List<BackupRow>,
    val operations: List<OperationRow>,
)

/**
 * Everything the host needs to bring a guest up: the staged APK copy, the
 * launcher component its manifest declares, and the identity used for logs.
 *
 * Produced by `InstanceRepository.prepareLaunch`, which validates all of it
 * *before* the engine flips the state to RUNNING. Without this step "启动" could
 * report success while no process, class loader or resources existed.
 */
data class LaunchRequest(
    val instanceId: String,
    val packageName: String,
    val displayName: String,
    val apkPath: String,
    val launcherActivity: String,
    val minSdk: Int,
    /**
     * Per-instance data directory from the engine. The guest's `getDataDir()`,
     * `getFilesDir()`, `getCacheDir()`, SharedPreferences and MMKV all resolve
     * against it, because `/data/user/0/<guest package>` does not exist — the
     * guest is not installed on the device.
     */
    val dataPath: String,
)

data class HomeSnapshot(
    val appCount: Int,
    val instanceCount: Int,
    val runningCount: Int,
    val engineState: EngineRunState,
    val recentApps: List<AppSummary>,
    val recentInstances: List<InstanceOverview>,
    val recentOperations: List<OperationRow>,
)

enum class EngineRunState {
    UNINITIALIZED,
    INITIALIZING,
    READY,
    FAILED,
    STOPPED,
    ;

    val label: String
        get() = when (this) {
            UNINITIALIZED -> "未初始化"
            INITIALIZING -> "初始化中"
            READY -> "运行中"
            FAILED -> "初始化失败"
            STOPPED -> "已关闭"
        }
}

enum class LogLevel { INFO, WARNING, ERROR }

data class LogEntry(
    val atMs: Long,
    val level: LogLevel,
    val message: String,
)

data class EngineDiagnostics(
    val engineState: EngineRunState,
    val status: EngineStatus?,
    val logs: List<LogEntry>,
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** The subset of settings that must be known before the engine is constructed. */
data class EngineBootConfig(
    val logLevel: String,
    val maxInstances: Int,
    val cacheBudgetBytes: Long,
)

data class AppSettings(
    val themeMode: ThemeMode,
    val logLevel: String,
    val maxInstances: Int,
    val cacheBudgetBytes: Long,
    val defaultPolicy: InstancePolicySpec,
    /**
     * 禁止横屏：虚拟应用的 Activity 一律按竖屏显示，忽略它自己的横屏请求
     * （红果的播放器会 setRequestedOrientation(LANDSCAPE) 全屏）。
     * 纯宿主侧策略，立即生效，不需要重建引擎。
     */
    val lockPortrait: Boolean,
) {
    companion object {
        val Default = AppSettings(
            themeMode = ThemeMode.SYSTEM,
            logLevel = "info",
            maxInstances = 64,
            cacheBudgetBytes = 128L * 1024L * 1024L,
            defaultPolicy = InstancePolicySpec(),
            lockPortrait = false,
        )
    }
}
