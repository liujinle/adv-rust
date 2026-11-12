# JNI / C ABI 契约

## 1. 原则

1. JNI 只做数据传输与调用，不做业务判断。
2. Rust 不保存未经管理的 JNI 引用；字符串在调用内复制并立即释放。
3. 不跨线程复用 `JNIEnv`；任务闭包不捕获任何 JNI 类型。
4. Rust panic 不得穿过 FFI：所有导出函数用 `catch_unwind` 包裹，映射为 `PANIC (-13)`。
5. 统一错误码；错误详情通过 `last_error` 获取，仅用于日志/诊断。
6. 耗时操作走 task id，不阻塞 UI 线程。
7. 路径参数由 Kotlin 传入绝对路径，Rust 不再猜测宿主目录。

## 2. JNI 符号表

类：`com.adv.bb.nativebridge.NativeBridge`（Kotlin `object`，编译为同名 class）

| Kotlin / Java 声明 | 返回 | 说明 |
| --- | --- | --- |
| `nativeAbiVersion()` | `int` | C ABI 版本，当前 `1`；不匹配应拒绝加载 |
| `nativeCreate()` | `long` | 创建未初始化引擎，失败返回 `0` |
| `nativeDestroy(long)` | `void` | 幂等；重复调用是 no-op |
| `nativeInitializeJson(long, String)` | `int` | 入参为 `EngineConfig` JSON；0 成功，负数错误码 |
| `nativeRequestJson(long, String)` | `String?` | `bb_ipc::Request` → `bb_ipc::Response`；传输失败返回 null |
| `nativeSubmitTaskJson(long, String)` | `long` | 返回 >0 的 task id，或负数错误码 |
| `nativeTaskResultJson(long, long)` | `String?` | 任务快照 JSON；未知 id 返回 null |
| `nativeSummaryJson(long)` | `String?` | 引擎状态（路径、版本、任务数、缓存占用） |
| `nativeLastError(long)` | `String?` | 最近一次错误详情 |

JNI 函数表索引（来自 JDK `jni.h`，0-based，含 4 个 reserved 槽）：

| 函数 | 索引 |
| --- | --- |
| `NewStringUTF` | 167 |
| `GetStringUTFChars` | 169 |
| `ReleaseStringUTFChars` | 170 |

> 已用 `tests/jni/run-smoke.ps1` 在真实 JVM 上验证：加载 `.dll`、字符串双向编组、错误码路径、任务轮询均通过。

## 3. C ABI

`native/bb-bridge/src/ffi.rs`：

| 符号 | 说明 |
| --- | --- |
| `bb_abi_version()` | ABI 版本 |
| `bb_engine_create()` / `bb_engine_destroy(handle)` | 句柄创建/释放（注册表校验，重复释放安全） |
| `bb_engine_initialize_json(handle, json)` | `EngineConfig` 初始化 |
| `bb_engine_request_json(handle, json)` | 通用请求入口 |
| `bb_engine_submit_task_json(handle, json)` | 提交任务 |
| `bb_engine_task_result_json(handle, task_id)` | 查询任务 |
| `bb_engine_cancel_task(handle, task_id)` | 取消任务 |
| `bb_engine_metrics_json(handle)` | 指标快照 |
| `bb_engine_summary_json(handle)` | 引擎状态 |
| `bb_engine_last_error(handle)` | 错误详情 |
| `bb_string_free(char*)` | 释放本库返回的字符串 |

**所有权**：返回 `char*` 的函数把所有权转移给调用方，必须用 `bb_string_free` 释放；返回 null 表示失败或"无结果"。

## 4. 错误码

| 值 | 名称 | 典型场景 |
| ---: | --- | --- |
| 0 | OK | 成功 |
| -1 | INVALID_ARGUMENT | 非法 JSON、包名/实例 id 非法、APK 包名不匹配 |
| -2 | NOT_FOUND | 实例/APK/条目不存在 |
| -3 | ALREADY_EXISTS | 实例 id 重复、引擎重复初始化 |
| -4 | INVALID_STATE | 非法状态迁移（如 Created → Running） |
| -5 | IO | 文件系统错误 |
| -6 | CORRUPT | 元数据/ZIP/AXML 结构损坏、CRC 失败 |
| -7 | UNSUPPORTED | ZIP64、加密 ZIP、协议版本不匹配 |
| -8 | BUSY | 实例数达到上限、队列满、实例正在迁移 |
| -9 | TIMED_OUT | 等待任务超时 |
| -10 | PERMISSION_DENIED | 路径逃逸、符号链接越界、绝对路径 |
| -11 | TOO_LARGE | 超过消息/条目/配额上限 |
| -12 | NOT_INITIALIZED | 引擎未初始化或已关闭 |
| -13 | PANIC | Rust 侧 panic（已捕获，进程仍安全） |
| -14 | INTERNAL | 锁中毒等内部不一致 |

## 5. 协议

### 5.1 JSON（Kotlin 主路径）

请求通过 `"type"` 区分：

```json
{"type":"create_instance","spec":{"instance_id":"instance_001","package_name":"com.example.app","apk_path":"/data/user/0/com.adv.bb/files/app.apk"}}
{"type":"start_instance","instance_id":"instance_001"}
{"type":"stop_instance","instance_id":"instance_001"}
{"type":"get_instance","instance_id":"instance_001"}
{"type":"list_instances"}
{"type":"inspect_apk","apk_path":"/sdcard/app.apk"}
{"type":"remove_instance","instance_id":"instance_001"}
{"type":"metrics"}
{"type":"hello","protocol":1}
```

响应：

```json
{"type":"instance","instance":{"instance_id":"instance_001","package_name":"com.example.app","state":5,"state_name":"running","refs":1,"updated_at_ms":1794216119885,"last_error":null}}
{"type":"instances","instances":[...]}
{"type":"apk","apk":{...}}
{"type":"metrics","snapshot_json":"{...}"}
{"type":"ok","message":"removed"}
{"type":"error","code":-4,"code_name":"INVALID_STATE","message":"instance x: created -> running is not allowed"}
```

### 5.2 任务命令

```json
{"command":"parse_apk","path":"/sdcard/app.apk"}
{"command":"create_instance","spec":{...}}
{"command":"remove_instance","instance_id":"instance_001"}
{"command":"start_instance","instance_id":"instance_001"}
{"command":"stop_instance","instance_id":"instance_001"}
{"command":"list_instances"}
{"command":"cleanup_orphans"}
```

任务快照：

```json
{"task_id":7,"name":"create_instance","state":"succeeded","progress":100,
 "result":{"instance_id":"instance_001","state":3,"state_name":"installed", "...": "..."},
 "error":null,"submitted_at_ms":1794216119800,"started_at_ms":1794216119801,"finished_at_ms":1794216119885}
```

`state ∈ {pending, running, succeeded, failed, cancelled}`。任务失败时 `error = {code, name, message}`。

### 5.3 二进制（内部/进程间）

`bb-ipc`：`tag u8` + LEB128 varint + 长度前缀字符串；帧格式为 `u32 LE 长度 + payload`。

已覆盖的编码测试：全部 21 个样例的 round-trip、每个前缀截断都返回错误（不 panic）、2000 组随机字节不 panic、长度字段上限检查、尾部多余字节拒绝。

## 6. 线程规则（Kotlin 侧）

| 调用 | 建议线程 |
| --- | --- |
| `BbEngine.open/close` | 任意（内部加锁），建议 Application 启动 |
| `initialize` | 后台（涉及建目录、读元数据） |
| `createInstance` / `inspectApk` | 后台/任务队列（APK 解析） |
| `startInstance` / `stopInstance` / `listInstances` | 后台（元数据锁） |
| `taskResult` | 任意（短查询，仍建议 IO 协程） |

`BbEngine.close()` 会等待工作线程结束（join），不要在 UI 线程调用。
