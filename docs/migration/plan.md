# 迁移计划：BlackBox 2.1.0 → 模块化引擎

> 本仓库**不包含** BlackBox 2.1.0 源码，也没有修改它。下面的类映射是基于其公开结构的
> 迁移假设，必须用 2.1.0 tag 的真实源码逐项核实后才能进入实现阶段。凡涉及 framework
> 内部行为的结论，都以实际源码 + 设备验证为准，不凭类名推断。

## 1. 阶段划分

| 阶段 | 交付物 | 出口条件 |
| --- | --- | --- |
| P0 骨架（本仓库） | Rust workspace + C ABI/JNI + Kotlin 桥接 + 文档 | `cargo test --workspace` 全绿；JNI 冒烟通过；Kotlin 模块可编译 |
| P1 源码测绘 | 原版 2.1.0 六大部分的调用链笔记（含方法级）、Hook 清单、初始化时序图 | 每个 Hook 有"目标方法/签名/失败降级"三要素 |
| P2 兼容基线 | Android 12–13 可运行；实例生命周期由 `bb-runtime` 驱动 | 原版可运行用例全部通过 |
| P3 数据与解析接管 | `bb-vfs` 接管目录策略；`bb-apk` 接管安装前解析；元数据迁移脚本 | 双实例隔离验证；旧数据可迁移 |
| P4 14–17 适配 | 按版本拆分的兼容模块 + 冲突检测 | 各版本验证清单（见 compatibility/matrix.md）通过 |
| P5 性能与稳定性 | A/B 报告、24h 稳定性、回归基线 | 达到验收目标或明确记录差异 |

## 2. 六大部分 → 新架构

| 原模块 | 需要分析的实现细节 | 新位置 | 迁移策略 |
| --- | --- | --- | --- |
| `BlackBoxCore` | 初始化顺序、系统服务代理安装点、Binder 缓存 | `Bcore/core`（Kotlin）+ `bb-bridge::initialize` | 保留 Android 职责；初始化顺序由 Kotlin 决定，Rust 只做自身初始化 |
| `BActivityThread` | 虚拟主线程、`Instrumentation` 替换、Application 创建 | `Bcore/runtime`（Kotlin） | 重写为可注入的组件，不搬进 Rust |
| `BPackageManager` | 包信息构造、`PackageInfo`/`ActivityInfo` 填充、签名与权限 | `Bcore/services/package` + `bb-apk`（只读解析） | 解析交给 Rust；安装、签名、PackageInstaller 兼容留在 Kotlin |
| `BEnvironment` | 目录结构、`getFilesDir` 等重定向、外部存储映射 | `bb-vfs` + `Bcore/compatibility/storage` | Rust 定义路径策略与布局；Kotlin 做 API 级重定向 |
| Hook 管理 | Hook 框架、方法替换、版本分支 | `Bcore/hook` + `Bcore/compatibility/AndroidXX` | 保留 Kotlin；按 API 区间拆分，避免"全版本共用一套实现" |
| 进程管理 | 虚拟进程创建、组件生命周期、进程回收 | `Bcore/runtime` + `bb-runtime` + `bb-scheduler` | 状态机与元数据归 Rust；进程拉起归 Kotlin |

## 3. 为什么 Rust 不接管 Hook

1. Hook 依赖 ART 内部结构与 `ArtMethod` 布局，版本差异以周为单位变化；
2. Rust 侧无法安全持有 `JNIEnv`、`jobject` 生命周期，跨线程更不可能；
3. Hook 失败必须降级（空实现/原方法），这类控制流在 Kotlin 中更清晰、可测试；
4. 把 Hook 放 Rust 会让"引擎可独立测试"这一核心收益消失。

Rust 的收益点是：路径策略、状态机、协议编解码、APK/AXML 解析、缓存与指标——这些都是纯数据逻辑，可 100% 主机测试，且跨 Android 版本稳定。

## 4. 数据迁移

原版目录（以 2.1.0 实际实现为准）通常会为每个虚拟包建立独立目录。新布局：

```
<root>/virtual/instances/<instance_id>/{files,cache,databases,shared_prefs,code_cache,no_backup,external}
<root>/metadata/instances.json
<root>/cache/<kind>/<hash>-<name>
```

迁移脚本（P3 交付）需要：

1. 扫描旧目录，识别包名与用户数据；
2. 为每个旧包生成 `instance_id`（如 `migrated_<pkg>_<n>`）；
3. 移动/复制目录，写入元数据行（`state=installed`，`apk_version_code` 从 APK 解析）；
4. 冲突处理：同包名多份数据 → 保留多实例，而不是覆盖；
5. 幂等：重复执行不产生重复实例；失败可回滚（先复制、后切换）。

`instance_id` 与包名解耦是这个过程的前提。

## 5. 风险清单

| 风险 | 影响 | 应对 |
| --- | --- | --- |
| 原版 Hook 与特定 ROM 行为耦合 | 换机即崩 | 按 API + ROM 记录适配矩阵，保留开关 |
| 签名校验绕过 | 无法安装部分 APK | 由 Kotlin 侧 PackageManager 代理处理，不进入 Rust |
| 32 位 ABI 缺失 | 老设备无法运行 | `build-android.ps1` 支持 armeabi-v7a；APK ABI 清单校验 |
| 元数据并发写 | 状态丢失 | `JsonFileStore` 原子写 + 内存回滚；后续可切 SQLite 实现 |
| 任务队列被长任务占满 | 安装/启动排队 | 队列上限 + 取消 + iOS 侧（Kotlin）按优先级分队列 |
| 兼容性"看起来支持" | 用户信任受损 | `CompatibilityMatrix` 默认 PENDING，禁止无设备证据标注 VERIFIED |

## 6. 本轮已交付 / 未交付

**已交付**：Rust 核心 11 个 crate（含测试）、C ABI、手写 JNI + 真实 JVM 冒烟、Kotlin 桥接与兼容层骨架、Gradle/NDK 构建脚本、模块基准、设计文档。

**未交付**（需原版源码与设备）：
- 原版 Hook 的具体移植（需 P1 测绘）；
- 真实 guest 进程/Activity 生命周期接管（Kotlin L2）；
- Compose UI（L1）；
- Android 12–17 设备验证结果。
