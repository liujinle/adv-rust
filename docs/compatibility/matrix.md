# Android 12–17 兼容性矩阵

> **状态说明**：本仓库不包含设备测试结果。下表中的 hook 项状态均为 `PENDING`（未验证），
> 与代码中的 `CompatibilityMatrix` 一致。**不得仅通过提高 `compileSdk` 宣称兼容。**

## 1. 版本划分与验证节点

| 范围 | API | 阶段 | 兼容目录（规划） | 验证出口条件 |
| --- | --- | --- | --- | --- |
| Android 12 / 12L | 31–32 | BASELINE | `compatibility/Android12` | 原版 2.1.0 可运行用例全部通过；无新增崩溃 |
| Android 13 | 33 | BASELINE | `compatibility/Android13` | 同上 + 通知权限、媒体权限流程 |
| Android 14 | 34 | SERVICE_AND_PERMISSION | `compatibility/Android14` | FGS 类型、隐式 Intent 限制、动态注册 receiver 导出标志 |
| Android 15 | 35 | SERVICE_AND_PERMISSION | `compatibility/Android15` | 前台服务超时、EDGE_TO_EDGE 强制、16KB page size 前置检查 |
| Android 16 | 36 | SPECIAL | `compatibility/Android16` | 新 SDK 行为变更逐项验证（以正式 SDK 为准） |
| Android 17 | 37+ | SPECIAL | `compatibility/Android17` | 实机 + 正式 SDK 行为验证；无证据则标记 BLOCKED |

云真机/模拟器只能作为补充，**结论必须来自真实设备**。

## 2. Framework 表面与风险

| 表面 | 风险 | 缓解策略 | 引入 API |
| --- | --- | --- | --- |
| Activity 启动 | Instrumentation / ActivityTaskManager 内部实现变化，代理 Activity 注册失效 | 按 API 区间独立适配；保留代理 Activity 与 Instrumentation 注入点 | 31 |
| PackageManager | 包可见性与 `queryIntentActivities` 过滤导致虚拟包查询为空 | 宿主声明 `<queries>`；PackageManager 代理优先返回虚拟包缓存 | 30 |
| Storage | Scoped Storage 与 `/sdcard` 重定向不一致 | 统一走 `bb-vfs` 映射；禁止暴露宿主真实路径 | 30 |
| Foreground Service | 类型强制声明、后台启动限制 | 代理 Service 完整声明 `foregroundServiceType`；必要时降级 bind | 31 |
| 权限 / AppOps | 运行时权限按宿主 UID 校验，虚拟授权无法映射 | 虚拟权限表 + AppOps 代理，未授权直接返回 false | 23 |
| Notification | POST_NOTIFICATIONS、渠道、trampoline 限制 | NotificationManager 代理改写 channel/PendingIntent；宿主申请权限 | 33 |
| PendingIntent | Android 12 起必须显式 mutable/immutable | 代理层补齐 flag；无法判定则拒绝创建 | 31 |
| ContentProvider | Application.onCreate 之前初始化；URI 授权语义变化 | 虚拟 Provider 延迟安装；URI 授权走 ActivityManager 代理 | 31 |
| Native Library | ABI 不匹配、linker namespace、extractNativeLibs | 加载前校验 APK `lib/<abi>` 清单；按实例目录解压后 dlopen | 23 |
| Binder | 系统服务接口签名随版本变化 | 服务代理按版本实现；失败降级为空实现并记录诊断 | 31 |
| 进程管理 | 后台限制导致虚拟进程被回收 | 运行状态持久化 + 恢复为 STOPPED；必要时前台服务保活 | 26 |

## 3. 代码中的落地方式

```kotlin
// 每个 hook 组声明自己验证过的 API 区间
interface HookAdapter {
    val id: String
    val surface: String           // activity / package / notification ...
    val supportedApis: IntRange
    fun install(context: CompatibilityContext)
}

// 注册与安装；失败只记录，不阻塞引擎启动（与原版容错策略一致）
val layer = CompatibilityLayer(CompatibilityContext(appContext, engine, logger))
    .register(Android12ActivityHooks())
    .register(Android14NotificationHooks())
layer.installAll()
layer.findRangeConflicts()        // 同一 surface 区间重叠 → 启动期报错
layer.status()                    // 诊断页展示
```

`CompatibilityMatrix.entries` 与上表一一对应，`report(sdkInt)` 可直接用于"关于/诊断"页。

## 4. 验证清单（每个版本）

- [ ] 冷启动宿主 → 打开实例列表
- [ ] 安装 APK（含 AndroidManifest 二进制解析、包名不一致拒绝）
- [ ] 首次启动 / 二次启动（热启动）
- [ ] Activity 旋转、返回栈、TaskAffinity
- [ ] Service 前台/后台启动，通知展示
- [ ] BroadcastReceiver 静态/动态注册
- [ ] ContentProvider 读写与 URI 授权
- [ ] 同包名双实例并行运行，数据互不可见
- [ ] 权限拒绝路径（网络、存储、通知）
- [ ] 进程被杀后重启 → 实例状态恢复为 STOPPED
- [ ] 24 小时稳定性：无 native 崩溃、无 ANR 增长

## 5. 与 Rust Core 的边界

Rust Core **不读取 `Build.VERSION.SDK_INT`**，也不按 API Level 分支。所有版本差异集中在 Kotlin 兼容层；Rust 只处理与版本无关的规则（路径、状态机、协议、解析）。这样升级 Android 时无需重新验证 native 逻辑，只需回归解析器的健壮性测试。

例外：`bb-apk` 解析 `targetSdkVersion` 等字段，但仅作为数据输出，不改变行为。
