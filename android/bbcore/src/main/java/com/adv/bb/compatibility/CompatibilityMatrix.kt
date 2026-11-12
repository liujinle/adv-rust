package com.adv.bb.compatibility

enum class VerificationStatus {
    /** Not yet exercised on a real device. */
    PENDING,
    IN_PROGRESS,
    VERIFIED,
    BLOCKED,
}

/**
 * Machine-readable form of `docs/compatibility/matrix.md`.
 *
 * Keeping the matrix in code (not only in a document) means the diagnostics screen
 * shows the same status the team tracks, and a release note can be generated from
 * it. Every entry starts as [VerificationStatus.PENDING]: claims of compatibility
 * without a device test are exactly what this table exists to prevent.
 */
data class CompatibilityRisk(
    val surface: String,
    val risk: String,
    val mitigation: String,
    val introducedAtApi: Int,
    val status: VerificationStatus = VerificationStatus.PENDING,
)

object CompatibilityMatrix {

    val entries: List<CompatibilityRisk> = listOf(
        CompatibilityRisk(
            surface = "activity",
            risk = "Activity 启动链依赖 Instrumentation / ActivityTaskManager 内部实现，15 之后调整频繁",
            mitigation = "按 API 区间编译独立适配层，宿主侧保留代理 Activity 与 Instrumentation 注入点",
            introducedAtApi = 31,
        ),
        CompatibilityRisk(
            surface = "package",
            risk = "包可见性与 queryIntentActivities 过滤导致虚拟包信息查询为空",
            mitigation = "在宿主 Manifest 中声明 <queries>，PackageManager 代理优先返回虚拟包缓存",
            introducedAtApi = 30,
        ),
        CompatibilityRisk(
            surface = "storage",
            risk = "Scoped Storage 与 /sdcard 重定向不一致",
            mitigation = "统一走 VirtualEnvironment 路径映射，禁止直接暴露宿主真实路径",
            introducedAtApi = 30,
        ),
        CompatibilityRisk(
            surface = "service",
            risk = "前台服务类型强制声明、后台启动限制导致虚拟 Service 无法拉起",
            mitigation = "代理 Service 声明齐全的 foregroundServiceType，按策略降级为 bind",
            introducedAtApi = 31,
        ),
        CompatibilityRisk(
            surface = "permission",
            risk = "运行时权限与 AppOps 校验以宿主身份执行，虚拟授权无法映射",
            mitigation = "虚拟权限表 + AppOps 代理，未授权调用直接返回 false",
            introducedAtApi = 23,
        ),
        CompatibilityRisk(
            surface = "notification",
            risk = "POST_NOTIFICATIONS 运行时权限、渠道与 trampoline 限制",
            mitigation = "NotificationManager 代理统一改写 channel 与 PendingIntent，宿主申请通知权限",
            introducedAtApi = 33,
        ),
        CompatibilityRisk(
            surface = "pending_intent",
            risk = "Android 12 起 PendingIntent 必须显式声明 mutability",
            mitigation = "代理层补齐 FLAG_IMMUTABLE / FLAG_MUTABLE，无法判定时拒绝创建",
            introducedAtApi = 31,
        ),
        CompatibilityRisk(
            surface = "provider",
            risk = "ContentProvider 在 Application.onCreate 之前初始化，URI 授权语义变化",
            mitigation = "虚拟 Provider 延迟到首个访问时安装，URI 授权走 ActivityManager 代理",
            introducedAtApi = 31,
        ),
        CompatibilityRisk(
            surface = "native_library",
            risk = "ABI 不匹配、linker namespace 与 extractNativeLibs 策略变化",
            mitigation = "加载前校验 APK 内 lib/<abi> 清单，按虚拟实例目录解压并 dlopen",
            introducedAtApi = 23,
        ),
        CompatibilityRisk(
            surface = "binder",
            risk = "系统服务接口签名随版本变化，直接反射调用易失败",
            mitigation = "服务代理按 API 分版本实现，调用失败降级到空实现并记录诊断",
            introducedAtApi = 31,
        ),
        CompatibilityRisk(
            surface = "process",
            risk = "后台执行与进程优先级限制导致虚拟进程被回收",
            mitigation = "运行状态持久化 + 前台服务保活，进程死亡后由 bb-runtime 恢复为 STOPPED",
            introducedAtApi = 26,
        ),
    )

    fun forRelease(release: AndroidRelease): List<CompatibilityRisk> =
        entries.filter { it.introducedAtApi <= release.apiRange.last }

    fun pendingCount(): Int = entries.count { it.status == VerificationStatus.PENDING }

    fun report(sdkInt: Int): String {
        val release = AndroidRelease.forApi(sdkInt)
        return buildString {
            appendLine("compatibility sdk=$sdkInt release=${release.label} stage=${release.stage}")
            for (entry in forRelease(release)) {
                appendLine("- [${entry.status}] ${entry.surface}: ${entry.risk}")
            }
        }
    }
}
