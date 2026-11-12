# 红果 7.4.3.32 虚拟化运行报告

## 结论（一句话）

启动页已经能真正跑起来、guest 数据也开始正常落盘，但进程最终仍被 **native SIGSEGV** 带走 —— 这是加固壳的退出动作（不是我们的 Java 代码，也不是普通崩溃），首页因此始终起不来。

---

## 一、退出机制：两条腿，一 Java 一 native

### 1. Java 侧：`finishAffinity`，已经能摘掉

在 `IActivityClientController` 代理里抓到的调用栈：

```
android.app.Activity.finishAffinity(Activity.java:7688)
org.checkerframework.checker.signature.query.security.̐.̐(Native Method)
org.checkerframework.checker.signature.query.security.̐.̕(Native Method)
org.checkerframework.checker.signature.query.security.̍.run(Native Method)
android.os.Handler.handleCallback → Looper.loop
```

壳在 `SplashActivity.onResume` 里往主线程 `Handler` 塞一个 Runnable（类名伪装成
`org.checkerframework…security.*`），~47ms 后执行。现在 `armShellGuard()` 会持续扫描
主线程 `MessageQueue`，把这类消息直接从链表上摘掉（日志：`dropped shell callback …`）。

### 2. native 侧：SIGSEGV，这才是真正的死因

`ApplicationExitInfo`（本机 `dumpsys activity exit-info`，以及应用内自写的
`files/guest-exit-info.txt`）显示：

```
reason=5 (APP CRASH(NATIVE))  status=11 (SIGSEGV)
signal: SEGV_MAPERR / null pointer dereference
faulting thread: BasicRouteMonit…
process: com.phoenix.read
```

即使用户态把 `finishAffinity` 全部拦掉，进程仍会在启动页 resume 之后 ~1s 内
SIGSEGV。崩溃线程名稳定为 `BasicRouteMonit`（RouteMonitor 的后台线程），栈里只能
看到 `LockSupport.parkNanos` / `art::Unsafe_park` 这类等待帧 + JIT 代码，说明真正的
出错帧在 JIT 或壳自己的 `.so` 里 —— 这是"判定不合格 → 自我了断"，Java 侧无法阻止。

补充：曾尝试"Activity 造完立刻把 Instrumentation 还给 guest"（怀疑 RouteMonitor 校验
`ActivityThread.mInstrumentation`），实测无效，已回退。

---

## 二、本轮修掉的真实问题（已验证）

| # | 位置 | 问题 | 修复 |
|---|------|------|------|
| 1 | `spoofProcessIdentity` | 把 `applicationInfo.dataDir` 改写成 `/data/user/0/com.phoenix.read`（不存在的路径），guest 的 prefs / MMKV / databases **全部没落盘**，实例目录里 `files`、`shared_prefs` 一直是空的 —— 隐私同意状态、首启标记每次丢失 | 恢复真实 dataDir（`…/instances/<uuid>`）。修复后 `shared_prefs` 出现 `SP_EXPERIMENT_CACHE.xml`、`local_settings_sp.xml` 等十余个文件 |
| 2 | `rewriteForGuest` | 代理组件 intent 被二次重写，`EXTRA_GUEST_ACTIVITY` 变成 `GuestActivityProxy` 自己，`newActivity` 里 `Class.forName` 直接 CNFE | 已是代理组件或已带该 extra 时直接放行 |
| 3 | `startGuestActivity` | `Intent(Context, Class)` 取的是 guest 包名 `com.phoenix.read`，组件指向未安装的包，系统静默拒收 | 显式 `ComponentName(宿主包名, 代理类)` |
| 4 | `skipSplash` | 壳把启动页退掉后任务变空 → 宿主被拉回前台、进程随后被收 | 先 `clearFinishing` 保住启动页，再从启动页自身发起跳转（同任务），允许重开一次启动页 |
| 5 | `installCrashProbe` | 后台线程未捕获异常会带走整个进程 | 只有主线程异常转发原 handler，后台线程只记录 |
| 6 | `callActivityOnResume` | 壳每次都把启动页退掉，判定永远跑不完 | 新增 `armShellGuard()`：主线程队列扫描 + 摘消息，并记录出现过的 Runnable 类名（`main queue cb …`） |

---

## 三、定位到但还没跑通的：首页的初始化链

反编译 + 自写 dex 交叉引用工具确认了 RPC/服务注册的唯一入口：

```
com.dragon.read.app.launch.task.g4.c(Application)
  → g4.b(Application)
      → kf1.j.h(RpcConfig)      // RpcService.init
      → kf1.j.a/b/c(...)        // 各 RPC / 业务服务注册
      → kf1.j.i()               // 断言"已初始化"
```

首页 `MainFragmentActivity` 里 `requestShortSeriesFeedButtonRedDot` 走到
`rpc.rpc.c.f()` → `kf1.j.e()` → `kf1.j.i()` 就抛
`had you called RpcService.init(Application,RpcConfig)`。

现状：`initGuestServices()` 会在跳首页前补跑 `g4.c(app)`，并且**必须放在后台线程**——
放主线程会卡死在 `com.bytedance.rpc.c.<init>` 的 `HandlerThread.getLooper()` 里
（主线程被自己等死，已实测）。后台跑 + 8s 超时 + 线程栈转储已就位，但因为进程先
SIGSEGV，这段还没机会验证到底能不能把 RPC 拉起来。

---

## 四、下一步（按性价比）

1. **拿 native 崩溃的真实帧**。`ApplicationExitInfo.traceInputStream` 是 protobuf，
   可视化很差；更直接的是给宿主加一个极小的 JNI 层：`sigaction(SIGSEGV)` +
   `unwind` 打印 `pc/lr` 与模块名，或用 `debuggerd` 的 `tombstoned` 客户端接口。
   有了确切的 `.so + 偏移`，才能判断是"壳的固定自杀点"还是"我们环境缺东西导致它解引用空指针"。
2. **让判定通过**（根治）。已伪装的：进程名 / `sArgV0` / `/proc/self/comm` /
   DDM app name / uid / `sourceDir` / dataDir。还没伪装的：dex 与 native 的**实际加载路径**
   （`/proc/self/maps` 里是宿主数据目录），这是 native 校验最容易抓的一处。
3. 若 1 的结论是"固定自杀点"，则只能走"壳校验放行"或"native 层 patch 该 .so"，
   Java 侧再补初始化也只是拖时间。
4. 与主线无关但值得保留：`g4.c(app)` 的后台补跑 + 超时转储，一旦进程能活过 2s，
   这条链路就是首页能否起来的关键。

---

## 五、代码状态

- `guest/GuestRuntime.kt`：壳消息摘除、服务注册补跑、dataDir 修复、代理启动修正
- `guest/GuestInstrumentation.kt`：`newActivity` 增加 target/extras 打点
- `AdvApplication.kt`：启动时把上次进程的退出记录（含 native 栈）写到
  `files/guest-exit-info.txt`，必须在 AMS 代理安装之前调用

`./gradlew :app:assembleDebug` 通过，lint 无新增问题，APK 已安装到设备。
运行时日志：`files/guest-trace.log`（生命周期 / 跳过启动页 / 壳消息 / 服务注册）。
