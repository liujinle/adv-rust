# 性能基准与验收方案

## 1. 两类基准，别混在一起

| 类型 | 目的 | 工具 | 本仓库是否已实现 |
| --- | --- | --- | --- |
| 模块基准 | 回答"Rust 模块本身贵不贵" | `native/bb-bench`（median/p95/ops） | ✅ |
| 端到端基准 | 回答"重构后虚拟应用是否变慢" | 原版 vs 重构版 A/B，Perfetto + 设备 | ❌ 需设备与两版 APK |

**只有端到端结果可以作为验收依据**；模块基准用于定位回归来源。

## 2. A/B 方法（原版 = A 组，重构版 = B 组）

统一条件：

- 同一台手机、同一系统版本、同一份 APK、同一实例数量；
- 相同网络、存储、电量模式（关闭省电）、相同应用数据；
- 冷启动前 `force-stop` 并清理页缓存（或固定同一预热流程）；
- 每项 ≥ 20 次，记录中位数与 P95，同时记录最小/最大值；
- 每次运行记录 `build fingerprint`、`sdk_int`、ABI。

## 3. 端到端指标与验收目标

以下为**目标值**，不是已测得数据：

| 指标 | 测量方式 | 第一阶段目标 |
| --- | --- | --- |
| 引擎初始化 | `initialize` 前后打点（含元数据读取、目录创建） | 不劣于原版 |
| APK 安装 | 从选择文件到 `Installed` | 不劣于原版 |
| 应用冷启动 | `am start` → 首帧（Perfetto `JIT`/`Choreographer`） | P95 ≤ 原版 × 1.10 |
| 应用热启动 | 同上，进程已存在 | 不劣于原版 |
| 空闲内存 | `dumpsys meminfo` PSS | 争取降低 10%（**非强制**，见下） |
| CPU | 空闲 10 分钟平均值、运行峰值 | 尽量降低 |
| 文件 IO | 顺序读写 64 MiB、随机 4 KiB | 无明显退化 |
| IPC | 单次 request 往返 | 低开销（目标 < 0.2 ms 主机侧） |
| 实例创建 | `create_instance` 任务耗时 | 不劣于原版 |
| 稳定性 | 24 h 循环安装/启动/停止 | 无 native 崩溃、ANR 率不高于原版 |
| 数据隔离 | 双实例交叉读写检查 | 零污染 |

**内存目标不作为承诺**：native 引擎自身也有 RSS 开销，必须测"总 PSS"，不能只报 Rust 侧内存。

## 4. 模块基准（已实现）

```bash
cd native
cargo run -p bb-bench --offline -- --iterations 2000
cargo run -p bb-bench --offline -- --json > bench.json
```

覆盖：配置加载、VFS 路径解析/读文件/用量扫描、IPC 编解码（小消息与 32 实例列表）、
运行时查询（单实例/64 实例）、APK 解析（磁盘/内存）、缓存读取、指标写入/快照、任务提交+等待。

本机参考值（Windows / release profile / 3000 次，仅供回归对比，不代表设备性能）：

```
metric                        iters    median_us       p95_us       max_us        ops/s
config.load_json               3000         0.90         1.30         8.30      1064963
vfs.resolve                    3000         4.50         8.00       127.90       182375
vfs.read_file_16k              3000       204.50       363.20      1416.60         4278
vfs.usage_scan                 3000       853.80      1347.80      2743.20         1073
ipc.encode_request             3000         0.20         0.20         0.50      6393862
ipc.decode_request             3000         0.30         0.40        79.90      3144984
ipc.encode_32_instances        3000         1.30         2.30        22.30       701738
ipc.decode_32_instances        3000         7.20        11.70       138.30       124118
runtime.get_instance           3000         0.30         0.40        53.80      2584647
runtime.list_64_instances      3000         9.30        15.30       133.30        94356
apk.inspect_from_disk           500       158.60       267.20       427.60         5619
apk.inspect_from_memory         500        55.70       115.90       369.50        15063
cache.get_4k                   3000        59.10       112.80       479.60        14532
metrics.incr                   3000         0.10         0.10         1.10     10552234
metrics.snapshot                500         0.20         0.20         0.20      6418485
scheduler.submit_and_wait      1000        14.40        23.00        77.00        82663
```

观察：

- `vfs.read_file_16k` / `vfs.usage_scan` 是磁盘主导（Windows 文件系统），Android 上需重测；
- `apk.inspect_*` 是 APK 解析成本的最小值（fixture 约 1 KB manifest）；真实 APK 的差异主要在压缩与 IO；
- `scheduler.submit_and_wait` 包含线程唤醒，适合作为任务开销上界。

## 5. 设备侧采集（Perfetto）

1. 抓取：`adb shell perfetto -o /data/misc/perfetto-traces/bb.pftrace -t 20s sched freq idle am wm gfx view binder_driver hal dalvik camera input res memory`
2. 关注轨道：`bb-task-*` 线程、JNI 调用区间（用 `atrace` 自定义 section 或 `Trace.beginSection`）、Binder 事务、主线程阻塞、GC。
3. 每次采集同时在应用内导出 `bb_engine_metrics_json`，把 P95 与 trace 对齐（同一时间窗口）。

## 6. 结果记录模板

```json
{
  "build": "B-0.1.0",
  "device": "Pixel X / API 36 / arm64-v8a",
  "apk": "com.example.app 4.2 (arm64-v8a)",
  "instances": 3,
  "cold_start_ms": {"median": 0, "p95": 0},
  "pss_mb": {"idle": 0, "peak": 0},
  "notes": "同机 A/B，A=BlackBox 2.1.0"
}
```

未填真实值的模板不得写进发布说明。
