# 架构总览

## 1. 目标与非目标

**目标**：把 BlackBox 式应用级虚拟化演进为模块化、可测试、可长期维护的引擎，核心能力包括无 Root 运行第三方 APK、实例数据隔离、组件生命周期托管、系统服务适配、同包名多实例、Android 12–17 兼容。

**非目标**（必须写在最前面，避免对外承诺错误）：

| 不是 | 原因 |
| --- | --- |
| 完整 Android 虚拟机 | 没有独立内核、没有 guest 内核态，ART 仍是宿主的 ART |
| 内核级安全沙箱 | 虚拟应用与宿主共享 UID；文件路径隔离不等于安全边界 |
| 独立 SELinux 域 / 独立进程权限 | 需要 root 或系统签名 |
| 跨应用数据不可见保证 | 宿主拥有的资源（如 `/sdcard`）虚拟应用仍可能通过绕过代理访问 |

因此本项目的正确定位是：**产品级虚拟化与兼容层**，用于多开、隔离数据、统一管理；不能用于运行不可信代码的安全隔离。

## 2. 五层职责

| 层 | 技术 | 职责 | 是否依赖 Android |
| --- | --- | --- | --- |
| L1 Presentation | Kotlin + Compose | 实例列表、安装、启动、策略设置、诊断 | 是 |
| L2 Android Compatibility | Kotlin/Java | Instrumentation、Activity/Service/Provider/Receiver、PackageManager、系统服务代理 | 是（深度） |
| L3 Rust Native Core | Rust | VFS、IPC、调度、元数据、状态机、APK/AXML 解析、指标、缓存 | 否 |
| L4 Storage & Runtime | SQLite/文件系统 | 元数据持久化、APK 缓存、实例目录 | 部分 |
| L5 Android OS | Framework/ART/Binder/kernel | 真实运行环境 | — |

**关键架构决策：不把 BlackBox 的虚拟化机制整体搬进 Rust。** Activity 启动、Instrumentation、系统服务 Binder 代理必须留在 Android 侧，因为它们需要与 framework 内部实现同步演进。Rust 承担的是"可独立测试的高性能底层"。

## 3. Crate 依赖图

```
bb-common            错误码 / 标识 / 状态枚举 / 日志 / 时钟（无依赖）
├── bb-config        配置与策略
├── bb-storage       元数据持久化（InstanceStore trait + JSON 原子写）
├── bb-vfs           虚拟路径映射与目录隔离
├── bb-ipc           二进制 + JSON 双协议
├── bb-scheduler     固定线程池任务队列
├── bb-apk           ZIP + 二进制 AXML 解析
├── bb-resource      字节预算缓存
├── bb-monitor       计数器 / Gauge / 延迟直方图
└── bb-runtime       实例状态机（依赖 storage）
        └── bb-bridge   C ABI + JNI + 引擎装配（依赖全部）
```

依赖是单向的：`bb-common` 不知道 Android，`bb-bridge` 是唯一接触 FFI 的 crate。任何 crate 都不链接 `libandroid`，因此可以 100% 在主机上跑测试。

## 4. 关键数据流

### 4.1 安装（install）

```
Kotlin: submitTask({"command":"create_instance","spec":{...}})
  ↓ JNI（JSON 字符串）
bb-bridge: 参数校验 → bb-apk::inspect(path)
  ↓  package 必须与请求一致，否则 INVALID_ARGUMENT，不产生任何副作用
bb-runtime::create()  → Created（写元数据）
bb-vfs::ensure_layout() → instances/<id>/{files,cache,databases,shared_prefs,code_cache,no_backup,external}
bb-runtime::install() → Preparing → Installed
  ↓
失败路径：反向删除元数据 + 目录（要么都成功，要么都回滚）
```

### 4.2 启动（start）

```
Kotlin（L2）负责真正的进程/组件拉起
  ↓ 进程就绪
bb-bridge: start_instance
  ↓ vfs.enforce_quota(policy.max_storage_bytes)
bb-runtime::start()  → Starting → Running（refs = 1）
  ↓ 第二个组件启动
bb-runtime::start()  → refs = 2（幂等、引用计数）
```

`Running` 的含义由 Rust 定义，但**触发者**是 Android 层：Rust 不探测进程，只记录上报结果。这样两边不会各自维护一份"是否在运行"的判断。

### 4.3 停止与恢复

- `stop`：refs 递减，最后一个组件释放时 → `Stopping → Stopped`。
- 进程重启后 `Runtime::recover_inconsistent()` 把 `Starting/Running/Stopping` 统一改为 `Stopped`，并写入 `last_error = "recovered after unclean shutdown"`。这是唯一绕过状态迁移表的操作，因为它描述的是"上一进程已经死亡"这一事实。

## 5. 关键决策记录（ADR 摘要）

| 决策 | 替代方案 | 理由 |
| --- | --- | --- |
| Rust 只做引擎，不做 Hook | 用 Rust 重写 Hook 框架 | Hook 需要跟随 framework 内部实现，且必须与 ART/JNI 交互；放 Rust 会形成不可测试的黑盒 |
| 元数据用原子重写 JSON，而非 SQLite | rusqlite bundled | 无 native 依赖（跨 4 个 ABI 少一个编译单元）、可用 `adb shell cat` 排查；实例数上限 64–4096，文档只有几十 KB。`InstanceStore` trait 预留 SQLite 实现 |
| 双协议：二进制 + JSON | 统一 Protobuf | 高频路径用 tag+varint；调试/UI 路径需要可读性。两套编码都有 round-trip 与 fuzz 测试 |
| 手写 JNI 绑定 | `jni` crate | 依赖表更短，索引来自 JDK `jni.h` 并已用真实 JVM 冒烟测试验证 |
| 任务用自有线程池，不引入 Tokio | tokio | 任务是短同步 IO；async 运行时增加体积与调试成本 |
| 路径策略拒绝 `..`/绝对路径/`:`/控制字符 | 依赖 `canonicalize` | 先做词法拒绝，再用 canonicalize 检查符号链接逃逸，两者互补 |
| Handle 注册表 | 直接解引用裸指针 | 让重复 `destroy` 变成 no-op，而不是 use-after-free |

## 6. 线程模型

| 组件 | 并发原语 | 说明 |
| --- | --- | --- |
| `BbEngine` | `Mutex<Option<Arc<EngineInner>>>` | 只在初始化/关闭/取快照时持有 |
| `Runtime` | `RwLock<BTreeMap>` + `Mutex<VecDeque>` | 元数据写入与事件推送分离 |
| `JsonFileStore` | `Mutex` + temp-file rename | 写入期间持有锁，保证内存与磁盘一致 |
| `Scheduler` | mpsc + worker 线程 + `Condvar` | 任务可取消、可观测，panic 被捕获 |
| `ResourceCache` | `Mutex` | 索引与预算在同一临界区 |
| JNI | 无共享 `JNIEnv` | 任何线程都不缓存 `JNIEnv`/局部引用 |

## 7. 不承诺项（对外必须一致）

1. 不承诺虚拟应用无法访问宿主可访问的文件。
2. 不承诺"Rust 化"带来 10% 内存下降；native 引擎本身也有开销，需实测。
3. 不承诺 Android 17 兼容；当前状态是"待设备验证"（见 `docs/compatibility/matrix.md`）。
4. 不承诺原版全部功能兼容；兼容率需要 A/B 测试用例清单支撑。
