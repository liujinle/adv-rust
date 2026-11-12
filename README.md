# adv-rust — BlackBox 应用虚拟化引擎（Rust Core 重构）

以 BlackBox 2.1.0 为源码基线的架构重构工程。核心思路不是"给原项目加一层 Rust JNI"，
而是把可独立测试的底层能力（路径策略、状态机、协议、APK/AXML 解析、调度、指标）沉到 Rust，
把必须与 Android Framework 同步演进的虚拟化机制（Activity/Instrumentation/系统服务代理）
留在 Kotlin。

> **边界先说清楚**
>
> * 这不是完整 Android 虚拟机，也不是内核级安全沙箱。
> * 宿主与虚拟应用共享 UID 与进程权限；文件路径隔离**不等于**安全边界。
> * 不承诺虚拟应用无法访问宿主可访问的资源。
> * Android 17 兼容性目前**没有**设备验证结论，见 `docs/compatibility/matrix.md`。

## 1. 当前状态

| 部分 | 状态 | 证据 |
| --- | --- | --- |
| Rust 核心 11 个 crate | ✅ 已实现 | `cargo test --workspace --offline`：150 个用例全绿 |
| C ABI + JNI | ✅ 已实现并验证 | 真实 JVM 冒烟：`tests/jni/run-smoke.ps1` 通过 |
| APK / 二进制 AXML 解析 | ✅ 已实现 | 合成 APK fixture 往返测试、随机/截断输入不 panic |
| 二进制 IPC 协议 | ✅ 已实现 | 全变体 round-trip、前缀截断、随机字节 fuzz |
| 模块基准 | ✅ 已实现 | `cargo run -p bb-bench`，见 `docs/benchmark/plan.md` |
| Kotlin 桥接 + 兼容层骨架 | ✅ 已实现，JNI 在 API 36 真机加载成功 | `android/bbcore`（`.so` 由 `native/build-android.ps1` 交叉编译） |
| 原版 Hook 移植 | ⛔ 未开始 | 需按 2.1.0 真实源码做 P1 测绘，见 `docs/migration/plan.md` |
| Android 客户端（L1 Compose） | ✅ Debug APK 已在 arm64 / Android 16 真机跑通 | `android/app`、`android/core/*`、`android/feature/*` |
| Android 12–17 设备验证 | ⛔ 未开始 | 矩阵状态全部 `PENDING` |
| Android JVM 单测 / CI / release 归档 | ✅ 已实现 | `tools/verify.ps1`、`.github/workflows/ci.yml`、`:app:archiveRelease` |

本机工具链限制：已安装 Rust（nightly 1.101，edition 2024）、JDK 17、NDK 27/28/30、Android SDK 34–37，
但**未安装** `aarch64-linux-android` 等 Rust target，且 crates.io 不可达（全部构建 `--offline`）。
因此 Rust 交叉编译产物仍需单独生成；Kotlin 模块已由 `tools/verify.ps1` 和 CI 做编译、JVM 单测与构建验证。

## 2. 目录结构

```
adv-rust/
├── native/                     Rust workspace
│   ├── bb-common/              错误码 / 标识 / 状态机 / 日志 / 时钟（零依赖）
│   ├── bb-config/              引擎与实例策略
│   ├── bb-storage/             元数据持久化（InstanceStore trait + 原子写 JSON）
│   ├── bb-vfs/                 虚拟路径映射与实例目录
│   ├── bb-runtime/             实例生命周期状态机（refcount + 事件）
│   ├── bb-ipc/                 二进制 + JSON 双协议
│   ├── bb-scheduler/           固定线程池任务队列（可取消/可观测/panic 隔离）
│   ├── bb-apk/                 ZIP + 二进制 AndroidManifest 解析
│   ├── bb-resource/            字节预算缓存（LRU + TTL）
│   ├── bb-monitor/             计数器 / Gauge / 延迟分位数
│   ├── bb-bridge/              引擎装配 + C ABI + JNI（cdylib = bb_native）
│   ├── bb-bench/               模块基准工具
│   └── build-android.ps1       NDK 交叉编译并 stage 到 jniLibs
├── android/
│   ├── bbcore/                 Kotlin 桥接、模型、兼容层骨架、Gradle + Cargo 任务
│   ├── app/                    应用入口、导航、手动 DI
│   ├── core/                   common / engine-api / engine-impl / database / data / domain / designsystem
│   └── feature/                home / apps / instances / settings / diagnostics（Compose）
├── tests/jni/                  真实 JVM 的 JNI 冒烟测试（Java）
└── docs/
    ├── architecture/           总览 + JNI/ABI 契约
    ├── compatibility/          Android 12–17 矩阵（全部待验证）
    ├── benchmark/              模块基准 + 设备 A/B 方法
    └── migration/              2.1.0 迁移计划与风险
```

## 3. 快速开始

```bash
# 全部单元/集成测试（离线可用）
cd native
cargo test --workspace --offline

# 模块基准（median / P95 / ops per second）
cargo run -p bb-bench --offline -- --iterations 2000
cargo run -p bb-bench --offline -- --json

# JNI 冒烟：编译 cdylib + javac + java，验证字符串编组与错误码
pwsh -File ../tests/jni/run-smoke.ps1
```

Android（需一次性网络准备；实测环境 JDK 17 + SDK 36 + NDK 27 + Gradle 8.13）：

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
pwsh -File native/build-android.ps1          # 产出 android/bbcore/src/main/jniLibs/<abi>/libbb_native.so
# 只测单台 arm64 设备时可加快：pwsh -File native/build-android.ps1 -Profile debug -Abis arm64-v8a
cd android && ./gradlew :bbcore:assembleDebug
cd android && ./gradlew :app:assembleDebug      # 客户端（Compose UI + Room + 引擎适配）
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 3.1 统一校验与发布归档

Windows 本机一条命令会设置 JDK 17、跑 Rust workspace 测试、Android JVM 单测、debug APK
和 release 归档；CI 使用同一批 Gradle 任务：

```powershell
pwsh -File tools/verify.ps1
pwsh -File tools/verify.ps1 -RequireSigned   # 强制归档产物必须已签名
```

签名材料只从环境变量或 `~/.gradle/gradle.properties` 读取，不写入仓库：
`RELEASE_KEYSTORE_FILE`、`RELEASE_KEYSTORE_PASSWORD`、`RELEASE_KEY_ALIAS`、
`RELEASE_KEY_PASSWORD`，可选 `RELEASE_SIGNING_REQUIRED=true`。没有签名材料时
`:app:archiveRelease` 会产出明确标注 `unsigned` 的 APK；CI 的 `v*` tag 任务要求 secrets
齐全并产出已签名归档。产物位于
`android/app/build/outputs/archive/adv-rust-<versionName>-release/`，内含
`build-info.json`、APK，以及启用混淆时才有的 mapping 文件。Room schema 导出文件位于
`android/core/database/schemas/`，由 `DbSchemaTest` 校验版本漂移。

## 4. 已实现的引擎能力

- **实例身份**：`instance_id` 与包名解耦，支持同包名多实例；`PackageName`/`InstanceId` 按安装规则校验。
- **状态机**：`created → preparing → installed → starting → running → stopping → stopped`（`failed` 可重试），非法迁移返回 `INVALID_STATE` 且不改状态；引用计数支持多组件并存；进程重启后把残留 `running` 恢复为 `stopped` 并保留诊断。
- **数据隔离**：每实例独立目录（`files/cache/databases/shared_prefs/code_cache/no_backup/external`）；拒绝 `..`、绝对路径、`:`、控制字符；写入前检查符号链接逃逸；支持配额与用量统计、孤儿目录清理。
- **安装流程**：Rust 解析 APK manifest（包名、版本、min/target SDK、权限、四类组件、launcher、ABI、cleartext），校验请求包名与 APK 一致；元数据与目录要么都建成功，要么都回滚。
- **协议**：Kotlin 主路径用带 `type` 标签的 JSON；高频路径用 tag+varint 二进制，带帧格式与 4 MiB 上限。
- **任务**：`parse_apk / create_instance / remove_instance / start_instance / stop_instance / list_instances / cleanup_orphans`，返回 task id，支持进度、取消、超时，panic 被捕获为 `PANIC`。
- **FFI 安全**：句柄注册表（重复 destroy 安全）、所有导出函数 `catch_unwind`、字符串所有权显式、错误码稳定。

## 5. 与 BlackBox 2.1.0 的关系（重要）

本仓库**没有**拷贝或修改 BlackBox 源码，也无法在此环境访问其 2.1.0 tag。因此：

- `docs/migration/plan.md` 中的类映射（`BlackBoxCore`/`BActivityThread`/`BPackageManager`/`BEnvironment`/Hook/进程管理）是**待核实假设**；
- 具体 Hook 目标方法必须在拿到源码后逐项测绘（目标签名、失败降级、API 区间）；
- 在完成测绘与设备验证前，不宣称"兼容原版功能"或"支持 Android 17"。

## 6. 下一步（建议顺序）

1. 拉取并测绘 BlackBox 2.1.0：初始化时序、Hook 清单、`BPackageManager` 数据构造、`BEnvironment` 路径规则。
2. 安装 Rust Android target，跑通 `assembleNative` + `:bbcore:assembleDebug`，在 API 31–33 设备上建立基线。
3. 用 Kotlin 实现 L2 的代理 Activity/Instrumentation 与系统服务代理，由 `bb-runtime` 驱动状态。
4. 编译并真机验证客户端骨架（首页/应用库/实例/设置/引擎中心），修正 Gradle 与 Compose 版本落差；诊断页已直接渲染 `CompatibilityMatrix.report()`。
5. 建立 A/B 端到端基准与 24h 稳定性回归。
