// GuestRuntime 就是 hidden API 代理层：HiddenApiUnsealer 负责运行时豁免，
// 这些反射点是虚拟化能力本身，release lintVital 不应把它们当作新增 fatal error。
@file:android.annotation.SuppressLint("BlockedPrivateApi")

package com.adv.bb.app.guest

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.Instrumentation
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentProvider
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.MessageQueue
import android.view.ContextThemeWrapper
import com.adv.core.data.InstanceTag
import com.adv.core.data.LaunchRequest
import dalvik.system.DexClassLoader
import java.io.File
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Host-side runtime for one guest process.
 *
 * Two jobs:
 * 1. build the guest ClassLoader, Resources and ApplicationInfo (including an
 *    extracted native library directory) and keep them reachable for the
 *    Instrumentation that will create the Activity;
 * 2. swap `ActivityThread.mInstrumentation` once, so a launch of the proxy
 *    component is redirected to the guest's launcher Activity.
 *
 * Everything here is process-global because ActivityThread is: there is exactly
 * one instrumentation per process.
 */
object GuestRuntime {

    internal class Guest(
        val instanceId: String,
        val apkPath: String,
        val packageName: String,
        val launcherActivity: String,
        val classLoader: DexClassLoader,
        val resources: Resources,
        val applicationInfo: ApplicationInfo,
        val packageInfo: PackageInfo,
        val dataDir: String,
    ) {
        /** Guest Application, created once with the guest Context as its base. */
        @Volatile
        var application: Application? = null

        /** Shared guest Context; every guest Activity is rebound to this one. */
        @Volatile
        var context: GuestContext? = null

        /** Real ContextImpl backed by a guest LoadedApk, when it could be built. */
        @Volatile
        var contextImpl: Context? = null

        /** LoadedApk the ContextImpl was built from; kept for `mApplication` wiring. */
        @Volatile
        var loadedApk: Any? = null

        /** Set once `Application.onCreate` has run, which is deferred (see attach()). */
        @Volatile
        var applicationStarted = false

        /**
         * dex 里的类名清单，第一次枚举后缓存。
         *
         * 25 万个类名枚举一次 ~1s，而"补跑启动任务"要用它来发现入口；缓存到
         * 实例上，后面的 resume/relaunch 不再重复扫。
         */
        @Volatile
        var classNames: List<String>? = null

        /**
         * 服务注册（RPC / 账号）是否已经开始 / 已经结束。
         *
         * 注册任务在 `Application.onCreate` 之后立刻跑，而不是等跳首页时才跑：
         * 启动页自己的后台任务（`SplashFragment$a.run` → 登录引导）第一次读账号
         * 配置就在 `onResume` 后 ~700ms，晚于注册就必然撞
         * `not init TTAccount config`，异常被吞后启动链停在原地。
         */
        @Volatile
        var servicesStarted = false

        @Volatile
        var servicesDone = false

        /** 注册结束后要执行的动作（在主线程跑）；skipSplash 会挂一个进来。 */
        val servicesWaiters = Collections.synchronizedList(mutableListOf<() -> Unit>())

        /**
         * 启动页自己的"初始化 → 跳首页"链条已经断掉（后台任务抛了未捕获异常）。
         *
         * 断了就永远停在启动图上，不必再等满 `SPLASH_FALLBACK_MS` 的兜底窗口。
         */
        @Volatile
        var splashChainBroken = false
    }

    private val guests = ConcurrentHashMap<String, Guest>()
    private val activityGuests = Collections.synchronizedMap(WeakHashMap<Activity, Guest>())



    /** Package name currently spoofed as this process's identity, if any. */
    @Volatile
    private var spoofedProcess: String? = null

    @Volatile
    private var hookError: String? = null

    @Volatile
    private var hookInstalled = false

    @Volatile
    private var crashProbeInstalled = false

    /** 我们自己那层 handler：抢回来时要按引用比对，不能靠 `installCrashProbe` 的开关。 */
    @Volatile
    private var crashProbeHandler: Thread.UncaughtExceptionHandler? = null

    private const val CRASH_PROBE_WATCH_MS = 120_000L
    private const val CRASH_PROBE_WATCH_STEP_MS = 250L

    /** 主线程异常兜底的额度：一段时间里连续崩太多次就别硬撑，交还给系统。 */
    private const val MAX_MAIN_LOOP_RECOVERIES = 8
    private const val MAIN_LOOP_RECOVERY_WINDOW_MS = 10_000L
    private val mainLoopRecoveries = Collections.synchronizedList(mutableListOf<Long>())

    /**
     * 把未处理异常的堆栈写进 trace，**并保住主线程消息循环**。
     *
     * 系统这边只会留下 `APP CRASH(EXCEPTION)`、`trace=null`：红果的加固壳会接管
     * 自己的 UncaughtExceptionHandler，logcat 又被 ROM 限流，堆栈两边都看不到。
     * 这里包一层，只为了让崩溃可定位。
     *
     * guest 和宿主**同一个进程**：guest 的主线程一崩，交回系统默认的
     * `KillApplicationHandler` 就会把宿主也带走（7.2.4.32 首页那次
     * `Fragment already added: StaggeredFeedTab` 就是这么把整锅掀掉的，重启后只剩
     * 宿主的「虚拟应用」诊断页）。所以主线程的可恢复 RuntimeException 不交给原
     * handler，而是在同一线程上重新进入 `Looper.loop()`，把后续消息接着跑完：
     * 抛异常的那条消息已经被消费，下一次布局/绘制不会重复执行同一个操作。
     */
    private fun installCrashProbe() {
        if (crashProbeInstalled) return
        crashProbeInstalled = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val mainThread = android.os.Looper.getMainLooper().thread
        val handler = Thread.UncaughtExceptionHandler { thread, error ->
            runCatching {
                trace("CRASH thread=${thread?.name} ${error.javaClass.name}: ${error.message}")
                trace(error.stackTraceToString())
            }
            runCatching { onGuestCrash(error) }
            // 业务线程的未捕获异常不致命。加固壳/插件框架在初始化不完整时会抛
            // （实测：首页后台任务 -> MiraPluginServiceManagerImpl.isMiraPlugin
            // 的 List 为 null），转发给原 handler 会直接带走整个进程。
            when {
                thread !== mainThread -> trace("swallowed crash on ${thread?.name} (non-main)")
                error is Error || !allowMainRecovery() -> {
                    trace("main 崩溃不再兜底（额度用尽或是 Error），交回原 handler")
                    previous?.uncaughtException(thread, error)
                }
                else -> {
                    trace("main 异常已兜住，重新进入 Looper.loop()")
                    recoverMainLoop(previous)
                }
            }
        }
        crashProbeHandler = handler
        Thread.setDefaultUncaughtExceptionHandler(handler)
        startCrashProbeWatchdog()
    }

    /**
     * 把 default handler 抢回来。
     *
     * 红果的崩溃上报 SDK（Npth / Crashly）在自己的初始化里会把 default handler 换成
     * 它自己的，之后业务线程再崩就直接进系统的 `KillApplicationHandler` —— 我们的探针
     * 收不到、logcat 里只有 `FATAL EXCEPTION`、进程被带走。实测这条路径干掉了整轮
     * 启动：`SplashFragment` 后台任务里的 `not init TTAccount config`（15:57:18）
     * 没被吞，1 秒后 `am_proc_died`，用户被踢回桌面。
     *
     * 它在什么时候换我们不知道，所以除了生命周期回调，还要有个看门狗持续抢。
     */
    internal fun rearmCrashProbe(reason: String) {
        val ours = crashProbeHandler ?: return
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current === ours) return
        Thread.setDefaultUncaughtExceptionHandler(ours)
        trace("crash probe 被 ${current?.javaClass?.name ?: "null"} 顶掉，已抢回（$reason）")
    }

    /** 启动后 120 秒内每 250ms 抢一次：guest 的崩溃 SDK 主要在初始化阶段换 handler。 */
    private fun startCrashProbeWatchdog() {
        Thread({
            val deadline = System.currentTimeMillis() + CRASH_PROBE_WATCH_MS
            while (System.currentTimeMillis() < deadline) {
                runCatching { rearmCrashProbe("watchdog") }
                runCatching { Thread.sleep(CRASH_PROBE_WATCH_STEP_MS) }
            }
            trace("crash probe watchdog 结束，当前=${Thread.getDefaultUncaughtExceptionHandler()?.javaClass?.name}")
        }, "crash-probe-watch").start()
    }

    /** 滑动窗口内只允许有限次主线程兜底，避免崩溃风暴里死循环。 */
    private fun allowMainRecovery(): Boolean {
        val now = System.currentTimeMillis()
        synchronized(mainLoopRecoveries) {
            mainLoopRecoveries.removeAll { now - it > MAIN_LOOP_RECOVERY_WINDOW_MS }
            if (mainLoopRecoveries.size >= MAX_MAIN_LOOP_RECOVERIES) return false
            mainLoopRecoveries.add(now)
            return true
        }
    }

    /**
     * `Looper.loop()` 在异常抛出时已经退出，主线程本该结束。这里在同一线程
     * 重新进入 loop()，让 Activity 继续处理消息；期间再崩继续接住，超过额度
     * 或遇到 Error 才把最后一次异常交回系统的 KillApplicationHandler。
     */
    private fun recoverMainLoop(previous: Thread.UncaughtExceptionHandler?) {
        var extra = 0
        while (true) {
            try {
                android.os.Looper.loop()
                return
            } catch (error: Throwable) {
                runCatching {
                    trace("main loop 再次异常 ${error.javaClass.name}: ${error.message}")
                    trace(error.stackTraceToString())
                }
                if (error is Error || ++extra > MAX_MAIN_LOOP_RECOVERIES) {
                    previous?.uncaughtException(Thread.currentThread(), error)
                    return
                }
            }
        }
    }

    /** Installs the instrumentation hook. Idempotent; returns the failure reason. */
    @Synchronized
    fun installHook(): String? {
        if (hookInstalled) return hookError
        hookError = runCatching {
            val threadClass = Class.forName("android.app.ActivityThread")
            val current = threadClass.getDeclaredMethod("currentActivityThread")
                .apply { isAccessible = true }
            val thread = current.invoke(null) ?: error("currentActivityThread() 返回 null")
            val field = threadClass.getDeclaredField("mInstrumentation").apply { isAccessible = true }
            val base = field.get(thread) as? Instrumentation
                ?: error("ActivityThread.mInstrumentation 不是 Instrumentation")
            if (base !is GuestInstrumentation) {
                baseInstrumentation = base
                field.set(thread, GuestInstrumentation(base))
            }
            null
        }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
        hookInstalled = true
        return hookError
    }

    /**
     * Takes `ActivityThread.mInstrumentation` back if the guest app replaced it.
     *
     * 红果 bundles ByteDance's Mira hook framework: its Application installs a
     * `MiraInstrumentation` over ours while initialising. Left alone the guest
     * Activity would then be created by *their* instrumentation — our `attach()`
     * (resources, class loader, Application) would never run, and Mira's own
     * Activity hook dies on reflection it expects from a real host process.
     */
    internal fun ensureHook() {
        runCatching {
            val threadClass = Class.forName("android.app.ActivityThread")
            val thread = threadClass.getDeclaredMethod("currentActivityThread")
                .apply { isAccessible = true }
                .invoke(null) ?: return
            val field = threadClass.getDeclaredField("mInstrumentation").apply { isAccessible = true }
            val current = field.get(thread) as? Instrumentation ?: return
            if (current is GuestInstrumentation) {
                trace("hook ok (${current.javaClass.simpleName})")
                return
            }
            field.set(thread, GuestInstrumentation(current))
            trace("hook retaken from ${current.javaClass.name}")
            android.util.Log.i(
                "GuestRuntime",
                "guest 应用替换了 Instrumentation，已重新接管：${current.javaClass.name}",
            )
        }.onFailure { android.util.Log.w("GuestRuntime", "重新接管 Instrumentation 失败", it) }
    }

    @Volatile
    private var baseInstrumentation: Instrumentation? = null

    /**
     * Wraps `ActivityTaskManager.getService()` so every `startActivity` gets a
     * chance to be redirected — whichever Instrumentation overload the caller
     * picked.
     *
     * Overriding `Instrumentation.execStartActivity` is not enough on Android
     * 36: the framework also calls hidden 10/13-argument variants (and guests
     * install their own instrumentation, e.g. 红果's RouteInstrumentation), so a
     * launch of an undeclared guest component slips through and the guest's
     * ActivityNotFound → `finish()` → empty task → process killed.
     */
    internal fun installStartActivityRedirect(): String? {
        synchronized(atmHookLock) {
            if (atmHooked) return null
            return runCatching {
                val atmClass = Class.forName("android.app.ActivityTaskManager")
                val getService = atmClass.getDeclaredMethod("getService").apply { isAccessible = true }
                val service = getService.invoke(null) ?: error("getService 返回 null")
                val serviceInterface = getService.returnType
                val singleton = atmClass
                    .getDeclaredField("IActivityTaskManagerSingleton")
                    .apply { isAccessible = true }
                    .get(null) ?: error("IActivityTaskManagerSingleton 为 null")
                val instanceField = Class.forName("android.util.Singleton")
                    .getDeclaredField("mInstance")
                    .apply { isAccessible = true }

                val proxy = java.lang.reflect.Proxy.newProxyInstance(
                    serviceInterface.classLoader,
                    arrayOf(serviceInterface),
                ) { _, method, args ->
                    if (args != null && method.name.startsWith("startActivity")) {
                        var drop = false
                        for (index in args.indices) {
                            val argument = args[index]
                            if (argument is Intent) {
                                // 首页已经在前台时，壳/加固框架还会反复把自己拉回启动页
                                // （实测每次首页 resume 后 1 秒内来一次，一轮 3~4 秒）。
                                // 这里是最靠近 AMS 的一层，直接不转发，当前首页留着不动。
                                if (shouldDropSplashRelaunch(argument)) drop = true
                                args[index] = rewriteForGuest(argument)
                            }
                        }
                        if (drop) returnDefault(method.returnType) else {
                            // guest 的 startActivity 是最后一次能"借"Instrumentation
                            // 的时机：系统马上就要 newActivity 了。
                            ensureHook()
                        }
                    } else if (method.name.contains("finish", true) ||
                        method.name.contains("remove", true) ||
                        method.name.contains("detach", true)
                    ) {
                        // 壳不止从 Activity 侧退自己，也直接对 AMS 动手；
                        // 这些调用以前是静默穿透的，看不出是谁把任务关掉。
                        trace("ATM.${method.name}")
                    }
                    try {
                        method.invoke(service, *args)
                    } catch (error: java.lang.reflect.InvocationTargetException) {
                        throw error.targetException ?: error
                    }
                }
                instanceField.set(singleton, proxy)
                atmHooked = true
                trace("ActivityTaskManager hook installed")
                null
            }.getOrElse {
                trace("ActivityTaskManager hook 失败：$it")
                "${it.javaClass.simpleName}: ${it.message}"
            }
        }
    }

    /**
     * 把本进程的 [ContentProvider] 包成系统要的 `ContentProviderHolder`。
     *
     * `ContentResolver` 要的是 binder 接口 `IContentProvider`，本地 provider 的
     * 那个实例藏在 `ContentProvider.mTransport` 里；直接给 provider 对象会在
     * 拆包时 ClassCastException。
     */
    private fun guestProviderHolder(entry: GuestProvider): Any? = runCatching {
        val holderClass = Class.forName("android.app.ContentProviderHolder")
        val iface = Class.forName("android.content.IContentProvider")
        val transport = ContentProvider::class.java
            .getDeclaredField("mTransport")
            .apply { isAccessible = true }
            .get(entry.provider)
        val holder = newProviderHolder(holderClass, iface, entry.info, transport)
        // 本进程 provider 不需要系统帮忙 release。
        runCatching { holderClass.getField("noReleaseNeeded").set(holder, true) }
        holder
    }.getOrElse {
        trace("guest provider holder 失败：${it.javaClass.simpleName}: ${it.message}")
        null
    }

    /**
     * 按本机实际签名构造 `ContentProviderHolder`。
     *
     * `(ProviderInfo, IContentProvider)` 这个签名不是每个版本都有：本机
     * （Android 16 / Oplus）上它已经不在了，直接按旧签名取构造器就是
     * `NoSuchMethodException`，每个 provider 都打一条失败日志、AMS 兜底路径也
     * 拿不到 holder。这里按"参数类型能填就填"的顺序挑构造器，绕开版本差异。
     */
    private fun newProviderHolder(
        holderClass: Class<*>,
        iface: Class<*>,
        info: ProviderInfo,
        transport: Any?,
    ): Any {
        val copy = ProviderInfo(info)
        var lastError: Throwable? = null
        for (ctor in holderClass.declaredConstructors.sortedBy { it.parameterCount }) {
            val types = ctor.parameterTypes
            // 只认这套类型：别的参数（Parcel、binder 容器之类）没法凭空造。
            if (types.any { it != ProviderInfo::class.java && it != iface && !it.isPrimitive }) continue
            val args = types.map { type ->
                when {
                    type == ProviderInfo::class.java -> copy
                    type == iface -> transport
                    type == java.lang.Boolean.TYPE -> false
                    type == java.lang.Integer.TYPE -> 0
                    type == java.lang.Long.TYPE -> 0L
                    else -> null
                }
            }
            val holder = runCatching {
                ctor.isAccessible = true
                ctor.newInstance(*args.toTypedArray())
            }.onFailure { lastError = it }.getOrNull() ?: continue
            // provider 未必是构造参数，缺这一步调用方拿到的是空 provider。
            runCatching { holderClass.getField("provider").set(holder, transport) }
            return holder
        }
        throw lastError ?: NoSuchMethodException(
            "ContentProviderHolder 构造器：" + holderClass.declaredConstructors.joinToString("; ") {
                it.parameterTypes.joinToString(",", "(", ")") { type -> type.simpleName }
            },
        )
    }

    private val atmHookLock = Any()

    @Volatile
    private var atmHooked = false

    /**
     * Wraps `IActivityManager` so guest calls that need *system* permissions do
     * not kill the process.
     *
     * 红果 ships ByteDance's Timon: `AutoStartObserver.recordFirstStartComponent`
     * calls `ActivityManager.getHistoricalProcessExitReasons` on a worker thread
     * right after Application start. That API needs `android.permission.DUMP`,
     * which a normal app never has, so the binder throws `SecurityException`;
     * Timon does not catch it and the process dies with
     * `APP CRASH(EXCEPTION)` and no stack in logcat.
     *
     * Returning an empty list is also the *truthful* answer here: the guest has
     * no exit history of its own — anything the system reports belongs to the
     * host process.
     */
    internal fun installActivityManagerProxy(): String? {
        synchronized(amHookLock) {
            if (amHooked) return null
            return runCatching {
                val amClass = Class.forName("android.app.ActivityManager")
                val getService = amClass.getDeclaredMethod("getService").apply { isAccessible = true }
                val service = getService.invoke(null) ?: error("getService 返回 null")
                val serviceInterface = getService.returnType
                val singleton = amClass
                    .getDeclaredField("IActivityManagerSingleton")
                    .apply { isAccessible = true }
                    .get(null) ?: error("IActivityManagerSingleton 为 null")
                val instanceField = Class.forName("android.util.Singleton")
                    .getDeclaredField("mInstance")
                    .apply { isAccessible = true }

                val proxy = java.lang.reflect.Proxy.newProxyInstance(
                    serviceInterface.classLoader,
                    arrayOf(serviceInterface),
                ) { _, method, args ->
                    when (method.name) {
                        // 需要 DUMP 权限；虚拟应用没有自己的退出历史，返回空即事实。
                        // 注意 AIDL 的返回类型是 ParceledListSlice 而不是 List：
                        // 直接给 emptyList 会被 Binder Proxy 转成 ClassCastException。
                        "getHistoricalProcessExitReasons" -> {
                            trace("AMS.getHistoricalProcessExitReasons -> empty (proxy hit)")
                            if (method.returnType == java.util.List::class.java) {
                                emptyList<Any>()
                            } else {
                                emptyExitReasonsSlice()
                            }
                        }
                        // 系统侧（AMS 的 ProcessRecord）记的仍然是宿主进程名。
                        // 加固壳/Timon 用这个结构判定"我是谁"，不改就前功尽弃。
                        "getMyMemoryState" -> {
                            val result = try {
                                method.invoke(service, *(args ?: emptyArray()))
                            } catch (error: java.lang.reflect.InvocationTargetException) {
                                throw error.targetException ?: error
                            }
                            val name = spoofedProcess
                            val info = args?.getOrNull(0)
                            if (name != null && info is android.app.ActivityManager.RunningAppProcessInfo) {
                                trace(
                                    "AMS.getMyMemoryState pid=${info.pid}" +
                                        " name=${info.processName} -> $name",
                                )
                                info.processName = name
                            }
                            result
                        }
                        // guest 的 provider 不在系统 PMS 里，ContentResolver 会一路
                        // 问到 AMS，然后拿到 "Failed to find provider info"。
                        // 命中我们自己 attach 过的 provider 就直接给 holder。
                        "getContentProvider" -> {
                            val authority = args?.firstOrNull { it is String } as? String
                            val holder = authority
                                ?.let { guestProviders[it] }
                                ?.let { guestProviderHolder(it) }
                            if (holder != null) {
                                trace("AMS.getContentProvider($authority) -> guest provider")
                                holder
                            } else {
                                try {
                                    method.invoke(service, *(args ?: emptyArray()))
                                } catch (error: java.lang.reflect.InvocationTargetException) {
                                    throw error.targetException ?: error
                                }
                            }
                        }
                        else -> {
                            // 谁在让 guest 退出：finish/kill 类调用全部留痕，
                            // 否则系统侧只看得到 pause + exit_self，看不出动手的人。
                            if (method.name.contains("finish", true) ||
                                method.name.contains("kill", true) ||
                                method.name.contains("forceStop", true) ||
                                method.name.contains("moveTask", true)
                            ) {
                                trace("AMS.${method.name} from guest")
                            }
                            if (method.name == "startActivity" || method.name == "startActivityAsUser") {
                                val intent = args?.getOrNull(2) as? Intent
                                val target = intent?.component?.flattenToShortString()
                                    ?: intent?.action ?: "-"
                                if (target.contains("launcher", true) ||
                                    target == Intent.ACTION_MAIN ||
                                    target.startsWith("com.android.")
                                ) {
                                    trace("AMS.${method.name} -> $target")
                                }
                            }
                            // guest 的 Service 组件翻译：这一层是 startService /
                            // bindService / stopService 真正进 AMS 的地方。不翻译的话
                            // 系统只回 `Unable to start service`（绑定侧表现为
                            // "binding service timed out"），guest 侧永远等不到
                            // onServiceConnected —— 哔哩哔哩的下载服务就是这样一直
                            // 「正在缓冲…0KB/s」。宿主/系统组件的 Intent 原样放行。
                            var guestService: String? = null
                            if (args != null && method.name in SERVICE_CALLS) {
                                for (index in args.indices) {
                                    val argument = args[index] as? Intent ?: continue
                                    val rewritten = rewriteServiceForGuest(argument)
                                    if (rewritten !== argument) {
                                        guestService = rewritten.getStringExtra(
                                            GuestLauncher.EXTRA_GUEST_SERVICE,
                                        )
                                    }
                                    args[index] = rewritten
                                }
                            }
                            val result = try {
                                method.invoke(service, *(args ?: emptyArray()))
                            } catch (error: java.lang.reflect.InvocationTargetException) {
                                throw error.targetException ?: error
                            }
                            // 返回值就是 AMS 的判定：startService 回组件名/null，
                            // bindService 回 0 表示已受理、负数/异常说明被拒。没有这条
                            // 日志就只能看到"guest 反复重试绑定"，看不出是谁拒的。
                            if (guestService != null) {
                                trace("AMS.${method.name}($guestService) -> $result")
                            }
                            result
                        }
                    }
                }
                instanceField.set(singleton, proxy)
                amHooked = true
                // 探针：回读一次，确认 Singleton 里装的确实是我们的代理。
                // 若不是，说明 guest 走的是 ServiceManager 直连或更早缓存的引用。
                val current = getService.invoke(null)
                trace(
                    "ActivityManager proxy installed, getService=" +
                        "${current?.javaClass?.name} ours=${current === proxy}",
                )
                null
            }.getOrElse {
                trace("ActivityManager proxy 失败：$it")
                "${it.javaClass.simpleName}: ${it.message}"
            }
        }
    }

    /**
     * `ParceledListSlice` 的构造器是 @hide 但 public，签名 `(List)`。
     * 反射构造，避免编译期依赖隐藏 API（unseal 之后调用不受拦截）。
     */
    private fun emptyExitReasonsSlice(): Any {
        val sliceClass = Class.forName("android.content.pm.ParceledListSlice")
        return sliceClass.getConstructor(List::class.java).newInstance(emptyList<Any>())
    }

    /**
     * 吞掉 guest 运行期间的 `ActivityClient.finishActivityAffinity`。
     *
     * 红果的 Epic 加固壳在 `SplashActivity.onResume` 之后由一个 Handler 回调
     * （`org.checkerframework.checker.signature.query.security.̐`）自己调用
     * `Activity.finishAffinity()`：不崩、不 ANR，系统侧只看得到 finish + exit_self。
     * 先把它拦住，验证"退出确实来自这一步"，再看拦掉之后能不能继续走到首页。
     *
     * 只在 guest Activity 还活着时拦，宿主自己按返回退出不受影响。
     */
    internal fun installActivityClientProxy(): String? {
        synchronized(clientHookLock) {
            if (clientHooked) return null
            return runCatching {
                val clientClass = Class.forName("android.app.ActivityClient")
                val singleton = clientClass
                    .getDeclaredField("INTERFACE_SINGLETON")
                    .apply { isAccessible = true }
                    .get(null) ?: error("INTERFACE_SINGLETON 为 null")
                val singletonClass = Class.forName("android.util.Singleton")
                val get = singletonClass.getDeclaredMethod("get").apply { isAccessible = true }
                val service = get.invoke(singleton) ?: error("get() 返回 null")
                val iface = Class.forName("android.app.IActivityClientController")
                val instanceField = singletonClass.getDeclaredField("mInstance")
                    .apply { isAccessible = true }

                val proxy = java.lang.reflect.Proxy.newProxyInstance(
                    iface.classLoader,
                    arrayOf(iface),
                ) { _, method, args ->
                    val guestAlive = lastGuestActivity?.isFinishing == false
                    // 只拦「启动期的启动页」：那是加固壳判定不合格后的自杀。
                    // 启动完成后的 finishAffinity 是用户主动退出，必须放行 ——
                    // 以前无条件拦，结果用户按返回退到桌面，Activity 没销毁、
                    // 进程和账本都还挂在 RUNNING。
                    val launchInFlight = LaunchClock.isRunning()
                    val onSplash = lastGuestActivity?.javaClass?.name
                        ?.contains("Splash", ignoreCase = true) == true
                    if (method.name.startsWith("finishActivity") && guestAlive &&
                        launchInFlight && onSplash
                    ) {
                        val caller = Thread.currentThread().stackTrace
                            .firstOrNull { !it.className.startsWith("java.") }
                        trace("ActivityClient.${method.name} blocked by $caller")
                        returnDefault(method.returnType)
                    }
                    try {
                        method.invoke(service, *(args ?: emptyArray()))
                    } catch (error: java.lang.reflect.InvocationTargetException) {
                        throw error.targetException ?: error
                    }
                }
                instanceField.set(singleton, proxy)
                clientHooked = true
                trace("ActivityClient proxy installed")
                null
            }.getOrElse {
                trace("ActivityClient proxy 失败：$it")
                "${it.javaClass.simpleName}: ${it.message}"
            }
        }
    }

    private val windowHookLock = Any()

    @Volatile
    private var windowSessionHooked = false

    /**
     * `IWindowSession` 代理：把 guest 自建窗口的包名换回宿主包名。
     *
     * `Toast` 的窗口包名取自 `context.getPackageName()`，guest 拿到的是
     * `com.phoenix.read`；`WindowManagerService.addWindow` 会校验"这个包属于调用
     * uid"，而 guest 包根本没装在宿主 uid 下 —— 于是主线程直接
     * `RuntimeException: Adding window failed`（Caused by: SecurityException:
     * Package com.phoenix.read not in UID 10440），进程必崩。
     *
     * 窗口本来就是宿主进程开的，包名写宿主才是事实；Activity 自己的窗口本来就是
     * 宿主包名，只有 Toast / PopupWindow 这种自建 `LayoutParams` 的会带 guest 名。
     */
    internal fun installWindowSessionProxy(): String? {
        synchronized(windowHookLock) {
            if (windowSessionHooked) return null
            return runCatching {
                val globalClass = Class.forName("android.view.WindowManagerGlobal")
                val field = globalClass.getDeclaredField("sWindowSession").apply { isAccessible = true }
                val session = field.get(null)
                    ?: return@runCatching "sWindowSession 尚未创建"
                val iface = Class.forName("android.view.IWindowSession")
                val hostPackage = hostContext()?.packageName
                    ?: return@runCatching "拿不到宿主包名"
                val proxy = java.lang.reflect.Proxy.newProxyInstance(
                    iface.classLoader,
                    arrayOf(iface),
                ) { _, method, args ->
                    toastController?.observeWindow(session, method, args)
                    if (args != null) {
                        for (argument in args) {
                            if (argument is android.view.WindowManager.LayoutParams &&
                                argument.packageName != null &&
                                argument.packageName != hostPackage
                            ) {
                                trace(
                                    "window ${method.name}: ${argument.packageName} -> $hostPackage",
                                )
                                argument.packageName = hostPackage
                            }
                        }
                    }
                    try {
                        method.invoke(session, *(args ?: emptyArray()))
                    } catch (error: java.lang.reflect.InvocationTargetException) {
                        throw error.targetException ?: error
                    }
                }
                field.set(null, proxy)
                windowSessionHooked = true
                trace("WindowSession proxy installed")
                null
            }.getOrElse {
                trace("WindowSession proxy 失败：${it.javaClass.simpleName}: ${it.message}")
                "${it.javaClass.simpleName}: ${it.message}"
            }
        }
    }

    /** 被拦下的 binder 调用要返回合法默认值，否则调用方拆箱时 NPE。 */
    /** 当前正在跑的 guest Activity 类名，用于把代理组件翻译回 guest 的组件。 */
    internal val currentGuestActivityName: String?
        get() = lastGuestActivity?.javaClass?.name

    /**
     * 宿主要求关掉这个虚拟应用（`stop` / `cleardata` / 卸载）时，在 `:guest` 里执行。
     *
     * 只 `finish()` 不够：栈里的下一页会被顶上来，任务卡还在；而且进程死后 AMS 看到
     * 还有存活的 task 会**立刻把进程重建**（实测 `stop` 后 3 秒内 `:guest` 就换了新
     * pid，账本还是 RUNNING）。所以必须 `finishAndRemoveTask()` 把整张卡片摘掉。
     *
     * 返回 false = 还没有任何 guest Activity（例如仍停在 gate），调用方直接退出进程。
     */
    internal fun shutdownFromHost(reason: String): Boolean {
        // 宿主已经决定关闭了，别再让"用户退出"那条路重复处理一次。
        shuttingDown = true
        toastController?.cancelAll(reason)
        val activity = lastGuestActivity
        trace("宿主要求关闭虚拟应用（$reason）activity=${activity?.javaClass?.name}")
        if (activity == null) return false
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching {
                activity.finishAndRemoveTask()
            }.onFailure {
                trace("finishAndRemoveTask 失败：${it.javaClass.simpleName} ${it.message}")
            }
        }
        return true
    }

    /**
     * 用户把 guest 页面全部退掉（回到桌面）后，实例和进程不能还挂着。
     *
     * 以前 `finishAffinity` 被无条件吞掉：用户按返回只看到桌面，`list` 仍是
     * RUNNING、recents 任务卡还在，下次点宿主图标就回到一个幽灵实例。
     * 现在 Activity 全销毁后主动摘任务卡 + 结束进程，宿主侧 5s 运行态对账
     * 会据此把它收敛成 STOPPED。
     *
     * Activity 切换有交错（新 Activity 先 create、旧的后 destroy），所以延迟一轮
     * 再确认；按 Home 进后台不会 destroy，不会被误判成退出。
     */
    internal fun onGuestActivityDestroyed(activity: Activity) {
        liveGuestActivities.remove(activity)
        if (lastResumedGuest === activity) lastResumedGuest = null
        if (shuttingDown) return
        Handler(Looper.getMainLooper()).postDelayed({
            if (shuttingDown) return@postDelayed
            val remaining = liveGuestActivities.count { !it.isFinishing && !it.isDestroyed }
            if (remaining > 0) return@postDelayed
            exitGuest("guest 页面已全部退出")
        }, EXIT_SETTLE_MS)
    }

    internal fun onGuestActivityCreated(activity: Activity) {
        liveGuestActivities.add(activity)
    }

    internal fun onGuestActivityResumed(activity: Activity) {
        liveGuestActivities.add(activity)
        lastResumedGuest = activity
        // 首页（任何非启动页的 guest 页面）已经露过面：之后再有人想开启动页，
        // 一律改去首页 —— 见 [redirectSplashIfHomeShown]。
        val guest = guestOf(activity) ?: return
        if (!activity.javaClass.name.contains("Splash", ignoreCase = true)) {
            homeShown.add(guest.instanceId)
        }
    }

    /** 本次启动里哪些实例的首页已经露过面。 */
    private val homeShown = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /**
     * 首页已经露出过之后，把"再开一次启动页"改写成首页。
     *
     * 红果的加固壳进首页后还会把自己拉回启动页（实测 3.5s 一轮，连跑 4 轮）：每轮
     * 都新建一个 `SplashActivity`，启动图也跟着重新盖一遍，用户看到的就是"启动
     * 一直不结束、白屏反复"。首页既然已经能正常显示，这一轮启动页没有任何意义，
     * 直接把目标换成首页。
     */
    /**
     * 首页已经在前台时，壳再发起"启动页"就整条丢掉（不转发给 AMS）。
     *
     * 只改写目标是不够的：重定向会再造一个首页，把当前这个 pause 掉，又要等 3 秒
     * 重新加载（实测 `启动页重入被改写成首页` 之后总耗时从 13.8s 涨到 22s）。丢弃这次
     * 启动、让当前首页留着，用户就不会看到第二次白屏。
     *
     * 只有"当前确实有一个非启动页的 guest 页面在恢复态"时才丢，避免把应用丢成空白。
     */
    internal fun shouldDropSplashRelaunch(intent: Intent?): Boolean {
        val component = intent?.component ?: return false
        if (GuestSlots.isProxyClass(component.className)) return false
        if (!component.className.contains("Splash", ignoreCase = true)) return false
        val guest = guestForPackage(component.packageName) ?: return false
        if (!homeShown.contains(guest.instanceId)) return false
        val front = foregroundGuestActivity() ?: return false
        if (front.javaClass.name.contains("Splash", ignoreCase = true)) return false
        trace("丢弃启动页重入：${component.className}（${front.javaClass.simpleName} 已在前台）")
        return true
    }

    internal fun redirectSplashIfHomeShown(guest: Guest, targetClass: String): String {
        if (!targetClass.contains("Splash", ignoreCase = true)) return targetClass
        if (!homeShown.contains(guest.instanceId)) return targetClass
        val home = homeActivityOverrides[guest.packageName] ?: return targetClass
        trace("启动页重入被改写成首页：$targetClass -> $home")
        return home
    }

    internal fun onGuestActivityPaused(activity: Activity) {
        if (lastResumedGuest === activity) lastResumedGuest = null
    }

    /**
     * 当前停在前台的 guest 页面（最近一次 resume、且没被 pause / finish / destroy）。
     *
     * 用途是"启动兜底自我否决"：`skipSplash` 的决策在 watchdog 线程里做，真正执行
     * 却排到主线程；主线程被 guest 的 `Application.onCreate` / 首页数据加载占住时，
     * 决策与执行能差出一两秒 —— 实测 +13.7s 首页已经 resume，+13.8s 兜底仍然把首页
     * pause 掉、重开启动页，白烧 4 秒，用户看到首页闪一下又回白屏。
     */
    private fun foregroundGuestActivity(): Activity? {
        val candidate = lastResumedGuest ?: return null
        if (candidate.isFinishing || candidate.isDestroyed) return null
        return candidate
    }

    /** 用户主动退出：先摘任务卡再结束进程，否则 AMS 会把空进程重建回来。 */
    private fun exitGuest(reason: String) {
        if (shuttingDown) return
        shuttingDown = true
        trace("guest 退出（$reason）：摘任务卡并结束进程")
        toastController?.cancelAll(reason)
        runCatching { lastGuestActivity?.finishAndRemoveTask() }
            .onFailure { trace("退出时摘任务卡失败：${it.javaClass.simpleName} ${it.message}") }
        Handler(Looper.getMainLooper()).postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, EXIT_KILL_DELAY_MS)
    }

    /** debug 显式广播用 guest Context 触发一次真实 Toast，验证槽位归属和回收。 */
    internal fun showDebugToast(text: String): String {
        val guest = guests.values.firstOrNull()
            ?: return "ERR 当前进程没有已装载的 guest"
        val context = guest.context ?: guest.application ?: hostContext()
            ?: return "ERR 当前进程没有可用 Context"
        val slot = GuestSlots.currentSlot?.let { "p$it" } ?: "p0"
        Handler(Looper.getMainLooper()).post {
            runCatching {
                android.widget.Toast.makeText(
                    context,
                    text,
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }.onFailure { trace("debug toast 失败：${it.javaClass.simpleName} ${it.message}") }
        }
        val controller = toastController != null
        trace("debug toast queued slot=$slot controller=$controller text=$text")
        return "$slot/${guest.instanceId} controller=$controller"
    }

    /** 壳判定失败后会自己退掉的启动页 → 直接换成真正的首页。 */
    private val homeActivityOverrides = mapOf(
        "com.phoenix.read" to "com.dragon.read.pages.main.MainFragmentActivity",
    )

    /**
     * 启动页"卡死"判定窗口（第一次判定，之后按 [SPLASH_WATCH_STEP_MS] 继续轮询）。
     *
     * 4 秒对老数据够用，对**全新数据**不够：首启流程（建缓存目录、拉启动配置、注册服务）
     * 实测 5~6 秒，4 秒就强跳首页会让首页 `onCreate` 崩在
     * `had you called RpcService.init(Application,RpcConfig)`、整页黑屏。
     * 光把窗口放大也不够 —— 真正决定能不能跳的是 RPC 是否就绪，见
     * [skipSplash] 里的 `guestRpcReady` 判定，这里只负责"多久看一次"。
     */
    private const val SPLASH_FALLBACK_MS = 8_000L

    /** 启动页兜底的轮询步长。 */
    private const val SPLASH_WATCH_STEP_MS = 1_000L

    /** 已经安排过兜底的启动页，避免每次 resume 都排一次。 */
    private val splashFallbackArmed =
        Collections.synchronizedMap(WeakHashMap<Activity, Boolean>())

    /**
     * 启动页超时兜底。
     *
     * 红果把"初始化 → 跳首页"放在启动页的一个后台 Runnable
     * (`SplashFragment$a.run`) 里。这条链在虚拟环境下会抛
     * `IllegalStateException: not init TTAccount config`：异常被
     * [installCrashProbe] 吞掉，进程活着、启动页却永远停在原地，用户看到的就是
     * 一张不动的启动图。这里在 resume 时挂一个定时器，到点还停在启动页就
     * **先重开一次启动页**，让它自己走完完整链路；再不走通才直接拉首页。
     */
    internal fun armSplashFallback(activity: Activity) {
        if (!activity.javaClass.name.contains("Splash", ignoreCase = true)) return
        val guest = guestOf(activity) ?: return
        installSplashOverlay(activity, guest)
        if (homeActivityOverrides[guest.packageName] == null) return
        if (splashFallbackArmed.put(activity, true) != null) return
        watchSplash(activity, guest, waited = 0)
    }

    /** 白屏遮罩的延后时间：正常启动页能自己画出内容的话，不该被盖住。 */
    private const val SPLASH_OVERLAY_DELAY_MS = 600L

    /** 遮罩 View 的 tag，防止 resume 多次重复叠加。 */
    private const val SPLASH_OVERLAY_TAG = "bb.splash.overlay"

    /**
     * 给"白屏启动页"盖一层品牌启动图。
     *
     * 红果的 `SplashActivity` 在虚拟环境里只能画出一张纯白窗口：启动图资源和初始化
     * 链一断，`SplashFragment` 的内容就永远出不来，冷启动前 3~9 秒用户看到的是白屏
     * （这就是"怎么一直是白屏"）。这里不碰 guest 的任何逻辑，只在**启动页自己的
     * 代理窗口**上叠一层「图标 + 应用名 + 转圈」；启动页结束、窗口销毁时它跟着消失，
     * 不需要任何额外的生命周期判断。
     */
    private fun installSplashOverlay(
        activity: Activity,
        guest: Guest?,
        delayMs: Long = SPLASH_OVERLAY_DELAY_MS,
        autoRemove: Boolean = false,
        minVisibleMs: Long = SPLASH_OVERLAY_MIN_MS,
        capMs: Long = SPLASH_OVERLAY_CAP_MS,
        fallbackTitle: String = "正在启动…",
        immediate: Boolean = false,
        iconOverride: android.graphics.drawable.Drawable? = null,
    ) {
        val decor = activity.window?.decorView as? android.view.ViewGroup ?: return
        val add = Runnable {
            if (activity.isFinishing || activity.isDestroyed) return@Runnable
            if (decor.findViewWithTag<android.view.View>(SPLASH_OVERLAY_TAG) != null) {
                return@Runnable
            }
            runCatching {
                val overlay = buildSplashOverlay(activity, guest, fallbackTitle, iconOverride)
                overlay.tag = SPLASH_OVERLAY_TAG
                decor.addView(
                    overlay,
                    android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                runCatching { decor.bringChildToFront(overlay) }
                trace("splash 白屏遮罩已挂上（${guest?.packageName ?: "boot"}）")
                if (autoRemove) scheduleOverlayRemoval(activity, overlay, minVisibleMs, capMs)
            }.onFailure {
                trace("splash 白屏遮罩失败：${it.javaClass.simpleName} ${it.message}")
            }
        }
        // `immediate` 关键：冷启动时主线程马上就会被 guest 的 `Application.onCreate`
        // 占住好几秒，`postDelayed` 的 Runnable 根本轮不上，遮罩永远不出现。
        if (immediate) add.run() else Handler(Looper.getMainLooper()).postDelayed(add, delayMs)
    }

    /** 挂在某个 Activity 窗口上、等着撤掉的启动图。 */
    private class PendingOverlay(
        val view: android.view.View,
        val startedMs: Long,
        val minVisibleMs: Long,
        val capMs: Long,
    )

    /** 待撤遮罩：以 Activity 为键，Activity 销毁即自动失效。 */
    private val pendingOverlays: MutableMap<Activity, PendingOverlay> =
        Collections.synchronizedMap(WeakHashMap<Activity, PendingOverlay>())

    /**
     * 启动图撤场：首页这类页面**不能一直盖着**，等 guest 真的把内容铺上就撤。
     *
     * 判定用 `android.R.id.content` 的子 View 数量 —— guest 调 `setContentView`
     * 时这个容器才有孩子；再等 [minVisibleMs]（内容挂上不等于画完），或到 [capMs] 硬撤。
     *
     * 除了 120ms 轮询，还在 guest 页面 `onPostResume` 时主动来一发
     * （[flushPendingOverlay]）：冷启动后 guest 的主线程被首页数据加载占满，
     * 轮询排不上队，实测遮罩在首页 resume 之后又多盖了 4 秒多；`onPostResume`
     * 是主线程自己排到的那一刻，也是能撤得最早的时刻。
     */
    private fun scheduleOverlayRemoval(
        activity: Activity,
        overlay: android.view.View,
        minVisibleMs: Long,
        capMs: Long,
    ) {
        pendingOverlays[activity] = PendingOverlay(
            view = overlay,
            startedMs = System.currentTimeMillis(),
            minVisibleMs = minVisibleMs,
            capMs = capMs,
        )
        val handler = Handler(Looper.getMainLooper())
        val poll = object : Runnable {
            override fun run() {
                if (tryRemoveOverlay(activity)) return
                handler.postDelayed(this, 120L)
            }
        }
        handler.postDelayed(poll, 120L)
    }

    /** 主线程上主动催一次遮罩撤场（`onPostResume` 调用），条件不满足就等轮询。 */
    internal fun flushPendingOverlay(activity: Activity) {
        tryRemoveOverlay(activity)
    }

    /** 条件满足就撤，返回 true 表示这一层已经处理完（撤了或跟着页面一起没了）。 */
    private fun tryRemoveOverlay(activity: Activity): Boolean {
        val pending = pendingOverlays[activity] ?: return true
        if (activity.isFinishing || activity.isDestroyed) {
            pendingOverlays.remove(activity)
            return true
        }
        val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
        val elapsed = System.currentTimeMillis() - pending.startedMs
        val ready = (content?.childCount ?: 0) > 0 && elapsed >= pending.minVisibleMs
        if (!ready && elapsed < pending.capMs) return false
        pendingOverlays.remove(activity)
        runCatching { (pending.view.parent as? android.view.ViewGroup)?.removeView(pending.view) }
        trace("splash 白屏遮罩已撤下（${elapsed}ms）")
        launchMark("首页遮罩撤下 elapsed=${elapsed}ms")
        return true
    }

    /**
     * 代理窗口刚起来时先盖一层启动图（供 `GuestActivityProxy.onCreate` 调用）。
     *
     * 冷启动白屏有两段：guest `Application.onCreate` 之后的**启动页白屏**，以及
     * 首页还没拿到数据时的**空首页**。启动页那层一直盖到窗口销毁；首页那层等
     * 内容铺上就撤。
     */
    internal fun installLaunchOverlay(activity: Activity) {
        val guest = guestOf(activity) ?: return
        val isSplash = activity.javaClass.name.contains("Splash", ignoreCase = true)
        if (isSplash) {
            // 新一轮启动都是从启动页开始的：把"首页已盖过"的标记清掉，
            // 让启动页之后的那张首页还能再盖一次。
            launchOverlayCovered.remove(guest.instanceId)
            launchOverlayBoot.remove(guest.instanceId)
        } else if (!launchOverlayCovered.add(guest.instanceId)) {
            // 只在**启动后的第一张非启动页**上盖（就是首页）。后面的播放页、详情页
            // 都是用户主动跳的，再盖 2~4 秒白屏纯属添乱。
            return
        }
        installSplashOverlay(
            activity = activity,
            guest = guest,
            delayMs = if (isSplash) 150L else 0L,
            autoRemove = !isSplash,
        )
    }

    /** 本次启动里已经给哪张"首页"盖过启动图，避免每跳一页都盖。 */
    private val launchOverlayCovered = Collections.synchronizedSet(mutableSetOf<String>())

    /** 启动瞬间那层遮罩（proxy onCreate）是否已经盖过。 */
    private val launchOverlayBoot = Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * 自动撤场的那层遮罩（首页）至少停留多久。
     *
     * 原来是 2000ms，但冷启动里首页的遮罩往往在 `onCreate` 之后 5 秒才挂上（guest 的
     * 首页 `onCreate` 本身很慢），那时内容已经铺好，多留 2 秒纯属让用户盯着启动图。
     * 现在 900ms 足够盖住"setContentView 到真正画出来"的空窗。
     */
    private const val SPLASH_OVERLAY_MIN_MS = 900L

    /** 遮罩硬上限，避免某些页面一直不设内容 View 时被永久盖住。 */
    private const val SPLASH_OVERLAY_CAP_MS = 4_000L

    /**
     * 代理窗口刚起来、guest Activity 还没创建时先盖一层启动图。
     *
     * 这是白屏最长的一段：从点"启动"到 `SplashActivity` 出现，中间是 guest
     * `Application.onCreate`（实测 2.4~4.8 秒），此时窗口已经在前台、里面什么都没有，
     * 用户看到的就是纯白。这里在 `GuestActivityProxy.onCreate` 就挂上「转圈 + 应用名」，
     * 等 guest 真正把内容铺上再撤。
     *
     * 这一层用 `Guest?`（可能为 null）：`onlyGuest()` 在最早那次 onCreate 里通常还
     * 没建好 Guest，拿不到图标就退化成纯文字 + 转圈，不能因此不盖。
     */
    internal fun installBootOverlay(activity: Activity, instanceId: String) {
        // 一次启动只盖一次：否则用户点进播放页、详情页时会被这块遮罩糊 3.5 秒。
        if (!launchOverlayBoot.add(instanceId)) return
        val guest = onlyGuest()?.takeIf { it.instanceId == instanceId }
        installSplashOverlay(
            activity = activity,
            guest = guest,
            delayMs = 80L,
            autoRemove = true,
            minVisibleMs = 3_500L,
            capMs = 9_000L,
            // 同步挂上：紧接着的 `load(...)` 会把 guest 的 `Application.onCreate`
            // 拉起来（实测 2.4~4.8s 卡住主线程），任何 postDelayed 都轮不上。
            immediate = true,
        )
    }

    /**
     * 点「启动」的那一瞬间，把遮罩挂到**当前宿主页面**上。
     *
     * 为什么不能等 guest 的窗口：`GuestInstrumentation.newActivity` 会用 guest 真正的
     * Activity 顶掉代理类，代理的 `onCreate` 根本不执行；而 guest 的
     * `Application.onCreate` 又是**同步跑在主线程**上的（实测 3.4~4.8 秒），这段时间
     * 谁都没法往窗口里塞 View —— 屏幕上是系统画的启动窗口（纯白）。
     *
     * 所以遮罩必须在启动**之前**就画进宿主窗口，并且配 `Theme.Adv.Guest` 里的
     * `windowDisablePreview`：没有系统启动窗口盖着，宿主这一帧才会一直留在屏幕上，
     * 直到 guest 画出自己的内容。
     */
    internal fun installLaunchGate(
        activity: Activity,
        instanceId: String,
        label: String,
        apkPath: String,
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val guest = onlyGuest()?.takeIf { it.instanceId == instanceId }
        // gate 比 `prepare()` 更早：Guest 还没建好，图标只能直接从 APK 里读，
        // 否则用户看到的是一张只有文字的启动页（连是哪个应用都认不出来）。
        val icon = runCatching {
            if (guest != null) return@runCatching null
            val pm = activity.packageManager
            val info = pm.getPackageArchiveInfo(apkPath, 0) ?: return@runCatching null
            info.applicationInfo?.apply {
                sourceDir = apkPath
                publicSourceDir = apkPath
            }?.loadIcon(pm)
        }.getOrNull()
        installSplashOverlay(
            activity = activity,
            guest = guest,
            // 不设自动撤场：gate 是个专用页面，guest 起来后整页会被切到后台并自行
            // 关闭。设了上限反而会在冷启动偏慢（实测有 7.7 秒的一轮）时把遮罩撤成白屏。
            autoRemove = false,
            fallbackTitle = label.ifBlank { "正在启动…" },
            immediate = true,
            iconOverride = icon,
        )
    }

    /** 正在给这次启动兜着遮罩的 gate 页（[GuestLaunchActivity]）。 */
    private var launchGate: java.lang.ref.WeakReference<Activity>? = null

    internal fun markLaunchGate(activity: Activity) {
        launchGate = java.lang.ref.WeakReference(activity)
    }

    /**
     * guest 自己 resume（或代理页兜底接管）之后关掉 gate。
     *
     * 留 400ms 再关：`callActivityOnResume` 时 guest 的窗口只挂上了、还没画完，立刻
     * 把 gate 的任务摘掉会闪一下宿主列表。
     */
    internal fun dismissLaunchGate(reason: String) {
        val gate = launchGate?.get()
        if (gate == null) {
            trace("gate 无需关闭（$reason）：本次启动没有 gate 页")
            return
        }
        launchGate = null
        Handler(Looper.getMainLooper()).postDelayed({
            val finished = runCatching { gate.finishAndRemoveTask() }.isSuccess
            trace("gate 已关闭（$reason）ok=$finished")
            launchMark("gate 已关闭")
        }, 400L)
    }

    /**
     * 首页已经 resume、但内容还没铺上时，先别摘 gate。
     *
     * 实测这一段会露出纯黑/纯白空窗 8~10 秒（guest 首页 `onCreate` 里要拉一堆数据，
     * 窗口先出来了、内容还没挂）。gate 是白底启动图，继续盖着比让用户看黑屏好；
     * 内容一铺上就摘，最多再等 [GATE_CONTENT_WAIT_MS] 兜底。
     */
    internal fun dismissLaunchGateWhenReady(activity: Activity, reason: String) {
        if (launchGate?.get() == null) {
            dismissLaunchGate(reason)
            return
        }
        val handler = Handler(Looper.getMainLooper())
        val started = System.currentTimeMillis()
        val poll = object : Runnable {
            override fun run() {
                val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                val gone = activity.isFinishing || activity.isDestroyed
                val elapsed = System.currentTimeMillis() - started
                // `setContentView` 比"真正画出东西"早 1~2 秒，所以内容挂上之后还要
                // 再留 [GATE_CONTENT_GRACE_MS]：这段时间正是那段纯黑空窗。
                val ready = gone ||
                    ((content?.childCount ?: 0) > 0 && elapsed >= GATE_CONTENT_GRACE_MS)
                if (ready || elapsed >= GATE_CONTENT_WAIT_MS) {
                    dismissLaunchGate(if (ready) reason else "$reason(超时兜底)")
                    return
                }
                handler.postDelayed(this, 120L)
            }
        }
        handler.postDelayed(poll, 120L)
    }

    /** 等内容铺上的最长等待；超时就当它好了，别把用户永久关在启动图里。 */
    private const val GATE_CONTENT_WAIT_MS = 6_000L

    /** 内容挂上之后仍留在启动图上的时间，用来盖住"已 setContentView、还没画出来"。 */
    private const val GATE_CONTENT_GRACE_MS = 1_800L

    /** 启动图本体：纯 View 拼装，不依赖宿主任何布局资源。 */
    private fun buildSplashOverlay(
        activity: Activity,
        guest: Guest?,
        fallbackTitle: String,
        iconOverride: android.graphics.drawable.Drawable? = null,
    ): android.view.View {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val root = android.widget.FrameLayout(activity).apply {
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        val column = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }
        val appInfo = runCatching { guest?.application?.applicationInfo }.getOrNull()
        val icon = android.widget.ImageView(activity).apply {
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            runCatching {
                val drawable = iconOverride
                    ?: appInfo?.loadIcon(activity.packageManager)
                if (drawable != null) setImageDrawable(drawable)
            }
        }
        column.addView(
            icon,
            android.widget.LinearLayout.LayoutParams(dp(72), dp(72)).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            },
        )
        val label = android.widget.TextView(activity).apply {
            text = runCatching {
                appInfo?.loadLabel(activity.packageManager)?.toString()
            }.getOrNull() ?: fallbackTitle
            textSize = 17f
            setTextColor(0xFF1F1F1F.toInt())
            gravity = android.view.Gravity.CENTER
        }
        column.addView(
            label,
            android.widget.LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) },
        )
        val spinner = android.widget.ProgressBar(activity).apply {
            // 品牌蓝，跟宿主其它转圈一致；默认是 Material 主题的绿。
            indeterminateTintList =
                android.content.res.ColorStateList.valueOf(0xFF0A84FF.toInt())
        }
        column.addView(
            spinner,
            android.widget.LinearLayout.LayoutParams(dp(26), dp(26)).apply {
                topMargin = dp(22)
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            },
        )
        root.addView(
            column,
            android.widget.FrameLayout.LayoutParams(-2, -2).apply {
                gravity = android.view.Gravity.CENTER
            },
        )
        return root
    }

    /**
     * 启动页兜底的轮询（替代原来"一次 postDelayed + hasWindowFocus 判定"）。
     *
     * 原来那版有个致命边界：`hasWindowFocus()` 一旦为 false 就直接 `return`，
     * 而 `splashFallbackArmed` 已经记成"排过了"，于是这一次误判就把兜底永久
     * 丢弃。实测 7.2.4.32 冷启动时桌面弹了微信悬浮横幅，焦点被抢走，启动页
     * 就那样停了一分多钟（trace 里既没有 "splash 超时" 也没有 "skip splash"）。
     *
     * 现在改成按"guest 最后一张 Activity 还是不是这张启动页"判定（首页压上来
     * 就不再管），焦点只写进日志、完全不参与决策：悬浮通知把焦点抢走也不影响
     * 4s 的判定节奏。
     *
     * 到点后的动作分两拍（见 [skipSplash]）：第一次超时**重开启动页**，因为壳的
     * 不合格判定是一次性的，第二个实例能自己走完 `handlePrivacyCheckAndProceed`
     * → 初始化 → 跳首页；只有已经重开过还没动静，才 `force` 直接拉首页。
     * 之前一律 `force=true` 会把首页在启动页还没把 tab / Fragment 状态铺好时硬拽
     * 起来，实测 7.2.4.32 因此必崩 `IllegalStateException: Fragment already
     * added: StaggeredFeedTab`（主线程异常 → 进程被杀 → 宿主诊断页）。
     */
    private fun watchSplash(activity: Activity, guest: Guest, waited: Long) {
        Handler(Looper.getMainLooper()).postDelayed({
            if (activity.isFinishing || activity.isDestroyed) return@postDelayed
            if (!stillOnSplash(activity, guest)) return@postDelayed
            val elapsed = waited + SPLASH_WATCH_STEP_MS
            if (elapsed < SPLASH_FALLBACK_MS) {
                watchSplash(activity, guest, elapsed)
                return@postDelayed
            }
            val reloaded = (splashReloads[guest.instanceId] ?: 0) >= 1
            trace(
                "splash ${elapsed}ms 仍未跳首页（hasWindowFocus=${activity.hasWindowFocus()}," +
                    " reloaded=$reloaded），${if (reloaded) "直接拉首页" else "重开一次启动页"}",
            )
            skipSplash(activity, force = reloaded)
        }, SPLASH_WATCH_STEP_MS)
    }

    /** guest 的前台还停在 [splash] 上（=还没跳首页）。 */
    private fun stillOnSplash(splash: Activity, guest: Guest): Boolean {
        val top = lastGuestActivity ?: return true
        if (guestOf(top) !== guest) return true
        if (top === splash) return true
        return top.javaClass.name.contains("Splash", ignoreCase = true)
    }

    /**
     * 业务线程抛异常时的启动页兜底。
     *
     * 启动页把"初始化 → 跳首页"整条链放在 `SplashFragment$a.run`（后台线程）里，
     * 里面任何一步抛异常（实测是读账号配置 `not init TTAccount config`），链就断
     * 在那里，用户要盯着启动图等满 [SPLASH_FALLBACK_MS] 才等到超时兜底。这里在
     * 异常发生的当下就判定"启动页这条路已经走不通"，立刻把首页拉起来。
     */
    private fun onGuestCrash(error: Throwable) {
        runCatching { probeCrashLoaders(error) }
        val fromSplash = error.stackTrace.any { frame ->
            frame.className.contains("pages.splash", ignoreCase = true)
        }
        if (!fromSplash) return
        val guest = onlyGuest() ?: return
        if (guest.splashChainBroken) return
        guest.splashChainBroken = true
        trace(
            "启动页启动链中断（${error.javaClass.simpleName}: ${error.message}），" +
                "不再等 ${SPLASH_FALLBACK_MS}ms 兜底",
        )
        val splash = synchronized(splashFallbackArmed) {
            splashFallbackArmed.keys.firstOrNull { it != null && guestOf(it) === guest }
        } ?: return
        Handler(Looper.getMainLooper()).post {
            if (splash.isFinishing || splash.isDestroyed) return@post
            skipSplash(splash, force = true)
        }
    }

    /**
     * 崩溃现场找"第二个 ClassLoader"。
     *
     * `r3` / `p2` / `yy7.l` / `fh1.c` 这些混淆短名在 APK 的 `DexClassLoader` 里
     * 一律 CNFE，但崩溃栈里明明有它们 —— 说明加固壳（Epic）把解壳后的 dex 交给
     * 了别的 loader。执行出错代码的线程上，`contextClassLoader` 往往就是那个
     * loader：用它把复盘里给出的初始化入口（7.2 的 `r3.h(app)` 等）试出来，
     * 就不用再猜类名了。
     */
    private fun probeCrashLoaders(error: Throwable) {
        if (!error.message.orEmpty().contains("TTAccount") &&
            !error.message.orEmpty().contains("RpcService")
        ) {
            return
        }
        val guest = onlyGuest() ?: return
        val thread = Thread.currentThread()
        val chain = LinkedHashSet<ClassLoader>()
        var loader: ClassLoader? = thread.contextClassLoader
        while (loader != null && chain.size < 6) {
            chain += loader
            loader = loader.parent
        }
        chain += guest.classLoader
        val report = StringBuilder("loaders ctx=${thread.contextClassLoader?.javaClass?.name}")
        for (item in chain) {
            val hits = CRASH_PROBE_CLASSES.map { name ->
                val ok = runCatching { Class.forName(name, false, item) }.isSuccess
                "$name=${if (ok) "ok" else "-"}"
            }
            report.append("\n  ${item.javaClass.name} ${hits.joinToString(" ")}")
        }
        trace(report.toString())
    }

    /** 复盘里 7.2 用到的初始化入口（账号 / RPC / Fresco）。 */
    private val CRASH_PROBE_CLASSES = listOf("r3", "p2", "y", "yy7.l", "fh1.c")

    /**
     * 跳过被壳判定不合格的启动页，直接拉起首页。
     *
     * 红果的启动页在 `onResume` 之后由壳统一判定，不通过就 `finishAffinity()`；
     * 这个判定在 native 层，Java 侧拦不住（Mira 的 hook 会绕过我们的
     * `IActivityClientController` 代理）。既然进程还活着，就直接把首页拉起来，
     * 让用户看到界面而不是退回宿主。
     */
    internal fun skipSplash(activity: Activity, force: Boolean = false) {
        val guest = guestOf(activity) ?: return
        val splashClass = activity.javaClass.name
        if (!splashClass.contains("Splash", ignoreCase = true)) return
        val target = homeActivityOverrides[guest.packageName] ?: return
        val context = guest.context ?: return
        // 直接跳首页会在 MainFragmentActivity.onCreate 里同步用 RPC
        // （requestShortSeriesFeedButtonRedDot → "had you called RpcService.init"），
        // 而 RPC 是启动页自己的启动任务初始化的 —— 这里一次都没跑完。
        // 所以先重开启动页：壳的判定是"一次"判定，第二个实例能走完
        // handlePrivacyCheckAndProceed → 初始化 → 自己跳首页的完整链路。
        val attempt = splashReloads.merge(guest.instanceId, 1, Int::plus) ?: 1
        // `force` comes from the timeout watchdog: the splash never finished its
        // own launch chain (a background task threw and the chain stopped), so
        // re-opening it would just wait another round for nothing.
        //
        // 但强跳之前必须先确认 RPC 层已经好了：首页 `onCreate` 里
        // `requestShortSeriesFeedButtonRedDot` 是同步走的 RPC，而 RPC 是启动页的
        // 启动任务初始化的 —— 没初始化完就被拽上去，必崩
        // `had you called RpcService.init(Application,RpcConfig)`，整页黑屏
        // （2026-11-11 17:0x 清数据后的全新实例连续复现三轮）。
        val rpcReady = guestRpcReady(guest)
        // 重开启动页最多一次（壳的判定是"一次"判定，第二个实例往往能走通）。再多开
        // 就是空转：壳把每个新启动页又判掉、我们又重开一次，实测 3.5s 一轮、连开 4 轮，
        // 期间启动图反复盖上，用户看到的就是"一直白屏 / 启动不完"。
        //
        // 所以：链已经断（`splashChainBroken`）、本次是超时兜底（`force`）、或者已经
        // 重开过一次 —— 一律直接跳首页；只有 RPC 还没就绪时才允许继续等启动页。
        val reloadAllowed = !guest.splashChainBroken && !force && when {
            // RPC 没就绪时宁可多等几轮启动页，也别把首页拽起来崩在同步 RPC 上。
            rpcReady == false -> attempt <= MAX_SPLASH_RELOADS + MAX_SPLASH_STALLS
            else -> attempt <= MAX_SPLASH_RELOADS
        }
        val next = if (reloadAllowed) splashClass else target
        trace(
            "skip 决策：attempt=$attempt force=$force rpcReady=${rpcReady ?: "未知"}" +
                " chainBroken=${guest.splashChainBroken}" +
                " -> ${if (next == target) "首页" else "启动页"}",
        )
        // 启动页留着别退：壳的 finishAffinity 已经被拦掉，本地 mFinished 一清，
        // 任务就不会变空 —— 任务空了系统会把宿主拉回来（看起来像闪退），
        // 后台还会把进程收掉。
        clearFinishing(activity)
        val launch = {
            runCatching {
                // 决策在 watchdog 线程上做，执行排到主线程：主线程忙的时候两者能差
                // 一两秒，实测首页已经 resume 了兜底还在拉启动页（白烧 ~4s，用户看到
                // 首页闪一下又回白屏）。执行前按"当前真正在前台的页面"复查一次。
                val front = foregroundGuestActivity()
                if (front != null && front !== activity &&
                    !front.javaClass.name.contains("Splash", ignoreCase = true)
                ) {
                    trace(
                        "skip splash 跳过：${front.javaClass.simpleName} 已在前台" +
                            "（$splashClass#$attempt -> $next）",
                    )
                    return@runCatching
                }
                ensureHook()
                // 从启动页自己发起：同一个任务里，新的启动页/首页直接压在它上面。
                startGuestActivity(guest, next, activity)
                trace("skip splash $splashClass#$attempt -> $next")
            }.onFailure {
                trace("skip splash 失败：${it.javaClass.simpleName} ${it.message}")
            }
            Unit
        }
        if (next != target) {
            launch()
            return
        }
        // 首页 onCreate 同步取 RPC，必须先把红果自己的服务注册跑完。注册会新建
        // `HandlerThread` 并 `getLooper()` 等待，放在主线程上会把自己等死，
        // 所以整段挪到后台线程，就绪后再回主线程拉首页。注册通常已经在
        // `Application.onCreate` 之后跑完（见 startApplication），这里只是等结果。
        awaitGuestServices(guest, launch)
    }

    /** 拉起 guest 的某个 Activity，走代理组件 + extras（不依赖 hook 的重写）。 */
    private fun startGuestActivity(guest: Guest, targetClass: String, from: Activity? = null) {
        // 必须显式拼宿主包名：`Intent(Context, Class)` 取的是 getPackageName()，
        // 而 guest context 上那是 com.phoenix.read —— 组件会指向一个没安装的包，
        // 启动请求直接被系统拒掉（表现为"点了没反应"）。
        val hostPackage = hostContext()?.packageName ?: return
        val intent = Intent().apply {
            component = ComponentName(hostPackage, GuestSlots.proxyClassName(currentSlot()))
            putExtra(GuestLauncher.EXTRA_INSTANCE_ID, guest.instanceId)
            putExtra(GuestLauncher.EXTRA_GUEST_ACTIVITY, targetClass)
            putExtra(
                GuestLauncher.EXTRA_GUEST_INTENT,
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setClassName(guest.packageName, targetClass)
                },
            )
        }
        if (from != null && !from.isFinishing) {
            from.startActivity(intent)
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            (guest.context ?: hostContext() ?: return).startActivity(intent)
        }
    }

    /** 启动页最多重开几次，之后一律当首页入口处理。 */
    private const val MAX_SPLASH_RELOADS = 1

    /**
     * RPC 还没就绪时，额外允许再等几轮（每轮 [SPLASH_FALLBACK_MS]）。
     *
     * 硬上限存在的意义：万一某个版本的探针永远返回 false，也不能把用户永久困在启动图。
     */
    private const val MAX_SPLASH_STALLS = 3

    /**
     * 补跑红果自己的服务注册（RPC / 账号都在这里初始化）。
     *
     * `com.dragon.read.app.launch.task.m0` / `g4` 是启动任务里的注册入口：它构造
     * `RpcConfig` 交给 RPC 框架、把各个 RPC/业务服务登记进去，最后断言"注册完成"。
     * 启动页被壳 50ms 就退掉时这个任务跑不完：首页里第一次取服务就撞
     * `had you called RpcService.init(Application,RpcConfig)`，启动页自己的后台
     * 任务则撞 `not init TTAccount config`。
     *
     * 时间点很关键：这段必须在**启动页的后台任务之前**开始跑（见
     * [startApplication]），否则注册再晚 4 秒，启动页那条链早就抛异常停住了。
     * 所以这里只负责"起一次"，谁来等结果由 [awaitGuestServices] 决定。
     */
    private fun ensureGuestServices(guest: Guest) {
        if (guest.servicesStarted) return
        guest.servicesStarted = true
        val started = System.currentTimeMillis()
        val application = guest.application
        if (application == null) {
            finishGuestServices(guest, "no application")
            return
        }
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        Thread({
            runCatching {
                // 账号初始化排最前：启动页 onResume 后 ~700ms 就读账号配置，
                // 排在 m0 注册链之后实测只差 91ms 就崩在它前面。
                runPriorityLaunchTasks(guest, application)
                trace("guest services 入口 ${invokeLaunchRegistration(guest, application)}")
                trace("launch.task 补跑：${runExtraLaunchTasks(guest, application)}")
                // 注册成功与否只有 RpcService 自己的断言说了算：n() 不抛即 ready。
                trace("rpc 状态：${rpcStatus(guest)}")
            }.onFailure {
                val cause = (it as? java.lang.reflect.InvocationTargetException)?.targetException ?: it
                trace("guest services init 失败：${cause.javaClass.name}: ${cause.message}")
                trace(cause.stackTrace.take(12).joinToString("\n") { "    at $it" })
            }.onSuccess {
                trace("guest services init ok in ${System.currentTimeMillis() - started}ms")
            }
            if (finished.compareAndSet(false, true)) {
                finishGuestServices(guest, "done")
            }
        }, "guest-services").start()
        // 注册里会新建 HandlerThread 并阻塞等 looper，万一卡住得知道卡在谁的栈上。
        Thread({
            Thread.sleep(SERVICES_TIMEOUT_MS)
            if (finished.compareAndSet(false, true)) {
                trace(
                    "guest services 超时，线程栈：\n" + Thread.getAllStackTraces().entries.joinToString("\n") {
                        "  ${it.key.name}:\n" + it.value.take(6).joinToString("\n") { f -> "      at $f" }
                    },
                )
                finishGuestServices(guest, "timeout")
            }
        }, "guest-services-watch").start()
    }

    /** 注册结束（成功 / 失败 / 超时都算），把等待者统一放回主线程。 */
    private fun finishGuestServices(guest: Guest, reason: String) {
        guest.servicesDone = true
        trace("executors after services=${probeExecutors(guest)}")
        val waiters = synchronized(guest.servicesWaiters) {
            val pending = guest.servicesWaiters.toList()
            guest.servicesWaiters.clear()
            pending
        }
        if (waiters.isEmpty()) return
        trace("guest services $reason，回调 ${waiters.size} 个等待者")
        val main = Handler(Looper.getMainLooper())
        waiters.forEach { waiter -> main.post { runCatching { waiter() } } }
    }

    /**
     * 等注册跑完再继续（首页 onCreate 会同步取服务）。
     *
     * 注册可能在启动页阶段就已经跑完，这时直接放行；没跑完就挂一个等待者并
     * 确保它已经启动 —— 调用方不必关心注册是哪个时间点发起的。
     */
    private fun awaitGuestServices(guest: Guest, onReady: () -> Unit) {
        if (guest.servicesDone) {
            onReady()
            return
        }
        guest.servicesWaiters.add(onReady)
        ensureGuestServices(guest)
    }

    /**
     * 补跑目标应用"启动任务"里注册 RPC / 业务服务的那一段。
     *
     * 7.2 的链路（dex 交叉引用实测，classes3）：
     * ```
     * com.dragon.read.app.launch.task.m0.f(Application)
     *   → m0.t(Application) → p2.e()
     *       → com.bytedance.rpc.m.i(Application, RpcConfig)   // RpcService.init
     *       → com.bytedance.rpc.m.b/c/k/m(...)                // 各业务服务注册
     * ```
     * 7.4 是同一个位置换了混淆名：`g4.c(Application) → g4.b(Application) → kf1.j.h(...)`。
     * 两代都试，谁先成功用谁。必须在后台线程调用：`RpcService.init` 内部会
     * new 一个 HandlerThread 并阻塞等 looper，主线程调用等于自己等死。
     */
    private fun invokeLaunchRegistration(guest: Guest, application: Application): String {
        patchKevaExecutor(guest)
        val task = runCatching {
            Class.forName("com.dragon.read.app.launch.task.m0", true, guest.classLoader)
        }.getOrNull()
        if (task != null) {
            val steps = launchTaskSteps(task)
            if (steps.isEmpty() && Runnable::class.java.isAssignableFrom(task)) {
                // 7.4 的 `m0` 是 Runnable：`run()` 里就是整条启动任务队列（账号、
                // 插件、实验都在里面），所以它没有 `(Application)` 入口可挑。
                val run = runCatching {
                    task.getDeclaredMethod("run").apply { isAccessible = true }
                }.getOrNull()
                if (run != null) {
                    trace("m0 是 Runnable，调用 run() 补跑启动任务队列")
                    return runStep("m0.run", run, application)
                }
            }
            val report = steps.map { method -> runStep(method.name, method, application) }
            // 只要有一步入过就算这条链跑通；全挂说明这个版本的 `m0` 不是入口，
            // 继续往下试 g4，而不是把一串失败报告当成结果返回。
            if (report.any { it.endsWith(STEP_OK) }) return report.joinToString(",")
            if (report.isNotEmpty()) trace("m0 全部失败：${report.joinToString(",")}")
        }
        val registry = Class.forName(
            "com.dragon.read.app.launch.task.g4",
            true,
            guest.classLoader,
        )
        // 7.4 的 m0 是个 Runnable（没有任何 `(Application)` 入口），真正的启动任务
        // 在 g4 这一批里：`c` 只是 RPC 注册，账号（TTAccount）等在上面其它方法。
        // 能跑的静态入口先全跑一遍，别只挑一个。
        val g4Steps = registry.declaredMethods
            .filter { method ->
                method.parameterCount == 1 &&
                    method.parameterTypes[0] == Application::class.java &&
                    method.returnType == Void.TYPE
            }
            .map { it.apply { isAccessible = true } }
        if (g4Steps.isNotEmpty()) {
            trace("g4 入口：" + g4Steps.joinToString(",") { it.name })
            // 同一批任务共用一个实例：它们在一个对象上攒状态，逐步 new 会各跑各的。
            val shared = runCatching {
                registry.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            }.getOrNull()
            val report = g4Steps.map { runStep("g4.${it.name}", it, application, shared) }
            if (report.any { it.endsWith(STEP_OK) }) return report.joinToString(",")
        }
        val instance = registry.getDeclaredConstructor().newInstance()
        val (register, argument) = pickRegisterMethod(registry, application)
        if (argument == null) register.invoke(instance) else register.invoke(instance, argument)
        return "g4.${register.name}/${register.parameterCount}"
    }

    /** `m0` 里带 Application 的入口，按"主启动链 → 单步"的顺序。 */
    private val LAUNCH_TASK_METHODS = listOf("f", "t", "n", "s")

    /** 一次最多跑多少个启动任务入口：多余的不跑，免得在一条后台线程上串到超时。 */
    private const val MAX_LAUNCH_STEPS = 24

    /**
     * `m0` 里所有 `(Application) -> void` 的入口，名单里的排前面。
     *
     * 混淆名随版本换（7.2 是 `f`/`t`，7.4 又换了一批），固定名单只能覆盖实测过的
     * 那几个。启动任务里除了 RPC 还有账号、推送、实验初始化，漏一个就是
     * "`rpc 状态：ready` 但后台 `not init TTAccount config`"。所以按签名自动补齐，
     * 方法表一起写进 trace，跑过哪些、什么结果都能对照。
     */
    private fun launchTaskSteps(task: Class<*>): List<java.lang.reflect.Method> {
        // 只认参数，不限定返回类型：`m0.f` 实测不是 void（上一版按名字取到过并跑通），
        // 加了 `returnType == void` 反而把唯一入口过滤掉，服务注册整段被跳过。
        val candidates = task.declaredMethods.filter { method ->
            method.parameterCount == 1 && method.parameterTypes[0] == Application::class.java
        }
        trace(
            "m0 入口：" + candidates.joinToString(",") { method ->
                method.name +
                    if (java.lang.reflect.Modifier.isStatic(method.modifiers)) "" else "*"
            },
        )
        if (candidates.isEmpty()) {
            trace(
                "m0 方法表：" + task.declaredMethods.joinToString("; ") { method ->
                    "${method.name}(" + method.parameterTypes.joinToString(",") { it.simpleName } + ")"
                },
            )
        }
        val named = LAUNCH_TASK_METHODS.mapNotNull { name ->
            candidates.firstOrNull { it.name == name }?.apply { isAccessible = true }
        }
        val rest = candidates
            .filter { method -> named.none { it.name == method.name } }
            .map { it.apply { isAccessible = true } }
        return (named + rest).take(MAX_LAUNCH_STEPS)
    }

    private const val STEP_OK = " ok"

    /**
     * 跑 `m0` 里的一个入口，返回"谁、跑没跑过"。
     *
     * 这些入口互相有调用关系（`f` → `t` → `p2.e()`），注册链断在哪一步只有
     * 逐个跑才看得出来，所以这里只记录、不把异常往外抛：一步失败不能挡住后面
     * 的步骤，最后整串报告由 [invokeLaunchRegistration] 交回上层写进 trace。
     *
     * 静态入口直接调；不是静态的才现场 new 一个接收者（`m0` 是 Kotlin object
     * 时方法全是实例方法）。
     */
    private fun runStep(
        name: String,
        method: java.lang.reflect.Method,
        application: Application,
        receiver: Any? = null,
    ): String = runCatching {
        val target = receiver ?: receiverOf(method)
        // `m0.run()` 这种无参入口：不能传 application，否则 IllegalArgumentException。
        if (method.parameterCount == 0) method.invoke(target) else method.invoke(target, application)
        name + STEP_OK
    }.getOrElse {
        val cause = (it as? java.lang.reflect.InvocationTargetException)?.targetException ?: it
        trace("m0.$name 失败：${cause.javaClass.name}: ${cause.message}")
        "$name ${cause.javaClass.simpleName}"
    }

    /**
     * 方法调用需要的接收者：静态 `null`，Kotlin object 取 `INSTANCE`，
     * 其余现场 new 一个。
     *
     * 不能一律 `newInstance`：object 的构造器是私有的，new 出来的第二份和真正的
     * 单例状态对不上（启动任务在单例里攒状态，第二步就找不到第一步写的东西）。
     */
    private fun receiverOf(method: java.lang.reflect.Method): Any? {
        if (java.lang.reflect.Modifier.isStatic(method.modifiers)) return null
        val owner = method.declaringClass
        runCatching {
            owner.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        }.getOrNull()?.let { return it }
        return owner.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
    }

    /**
     * Keva 执行器补丁。
     *
     * 字节系 KV（`com.bytedance.keva.Keva`）把落盘和回调都排在自己起的执行器上；
     * 启动任务一用到它就得等这条线程，而虚拟进程里它未必起得来、也没人 join，
     * 结果就是启动链停在原地（服务注册超时转储里那条等待帧）。这里把执行器换成
     * "在调用线程上直接跑"的实现：写入语义不变，只是不再等另一条线程。
     *
     * 类名/字段名都不在 SDK 里，所以按候选类名探测、按字段类型匹配；命中的字段和
     * 落空的候选全部写进 trace —— 没打上补丁时，下一轮照着 `keva fields=…` 修正。
     */
    private fun patchKevaExecutor(guest: Guest) {
        val inline = java.util.concurrent.Executor { command -> command.run() }
        var loaded = 0
        var patched = 0
        for (name in KEVA_CLASSES) {
            val clazz = runCatching { Class.forName(name, true, guest.classLoader) }.getOrNull()
                ?: continue
            loaded++
            var fields = 0
            for (field in clazz.declaredFields) {
                if (!java.util.concurrent.Executor::class.java.isAssignableFrom(field.type)) continue
                if (!java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                fields++
                runCatching {
                    field.isAccessible = true
                    field.set(null, inline)
                    patched++
                    trace("keva executor $name.${field.name} -> inline")
                }.onFailure {
                    trace("keva executor $name.${field.name} 写入失败：${it.javaClass.simpleName}")
                }
            }
            if (fields == 0) {
                trace(
                    "keva fields $name=" + clazz.declaredFields.joinToString(",") {
                        "${it.name}:${it.type.simpleName}"
                    },
                )
            }
        }
        trace("keva executor patched=$patched loaded=$loaded")
    }

    /** 字节 / 腾讯两套 Keva 的候选类名，按可能命中的顺序。 */
    private val KEVA_CLASSES = listOf(
        "com.bytedance.keva.Keva",
        "com.bytedance.keva.KevaExecutor",
        "com.bytedance.keva.KevaBuilder",
        "com.bytedance.keva.KevaImpl",
        "com.tencent.keva.Keva",
    )

    /**
     * 读 RPC 服务自己的状态。
     *
     * 7.2 是 `com.bytedance.rpc.m.n()`（`had you called RpcService.init(Application,RpcConfig)`
     * 那条断言），7.4 换成了 `kf1.j` 那一套。两代都探；类整个找不到时如实写
     * "缺失"，别把"类名过时"混进"没初始化"里 —— 上一版只看第一个类名，
     * 7.4 上就一直报 CNFE，看不出注册到底成功没有。
     */
    private fun rpcStatus(guest: Guest): String {
        val notes = mutableListOf<String>()
        for (className in RPC_STATUS_CLASSES) {
            val clazz = runCatching { Class.forName(className, true, guest.classLoader) }.getOrNull()
            if (clazz == null) {
                notes += "$className 缺失"
                continue
            }
            val method = RPC_STATUS_METHODS.firstNotNullOfOrNull { name ->
                runCatching { clazz.getDeclaredMethod(name).apply { isAccessible = true } }.getOrNull()
            }
            if (method == null) {
                notes += "$className 无 ${RPC_STATUS_METHODS.joinToString("/")}()"
                continue
            }
            return runCatching {
                method.invoke(null)
                "$className.${method.name} ready"
            }.getOrElse {
                val cause = (it as? java.lang.reflect.InvocationTargetException)?.targetException ?: it
                "$className.${method.name} not ready: ${cause.javaClass.simpleName}: ${cause.message}"
            }
        }
        return notes.joinToString("; ")
    }

    /** RPC 服务状态入口：7.2 的 `com.bytedance.rpc.m` 与 7.4 的 `kf1.j`。 */
    private val RPC_STATUS_CLASSES = listOf("com.bytedance.rpc.m", "kf1.j")

    /**
     * guest 的 RPC 层是否就绪。
     *
     * `gg1.j` 是红果的服务定位器，`g()` 返回它是否已经注册完（首页会通过
     * `gg1.j.e()` 取 RPC 服务，没注册就抛 `had you called RpcService.init`）。
     * 返回 `null` 表示这个版本没有这个探针，调用方按"不知道"处理、别因此卡死。
     */
    private fun guestRpcReady(guest: Guest): Boolean? {
        val clazz = runCatching {
            Class.forName(RPC_LOCATOR_CLASS, false, guest.classLoader)
        }.getOrNull() ?: return null
        val method = runCatching {
            clazz.getDeclaredMethod("g").apply { isAccessible = true }
        }.getOrNull() ?: return null
        return runCatching { method.invoke(null) as? Boolean }.getOrNull()
    }

    /** 红果的服务定位器（混淆名，随版本变；探不到就返回 null）。 */
    private const val RPC_LOCATOR_CLASS = "gg1.j"

    /**
     * 补跑 `launch.task` 包里其它启动任务入口。
     *
     * 两代入口集合完全不同（7.2 是 `m0.f/t/n/s/a`，7.4 是 `g4.b/c` 加另外四个类），
     * 固定名单只能覆盖实测过的那一版。这里直接从 dex 类清单里按"有 `(Application)`
     * 静态方法"筛，排除已经跑过的 `m0`/`g4` 和进程门禁 `TimonInitializer`，
     * 其余按清单顺序补跑。
     *
     * 目的：账号（`not init TTAccount config`）、推送、实验这些初始化散落在这批
     * 任务里；Application 只跑一半时它们就是"永远未初始化"。
     */
    private fun runExtraLaunchTasks(guest: Guest, application: Application): String {
        val all = guestClassNames(guest)
            .filter { it.startsWith("com.dragon.read.app.launch.task.") && !it.contains('$') }
            .filter { it !in SKIPPED_LAUNCH_TASKS }
        // 关键任务由 runPriorityTask 单独处理（它的入口是实例方法），这里只跑其余。
        val names = all.filter { it !in PRIORITY_LAUNCH_TASKS }
        // 关键任务已由 runPriorityLaunchTasks 在注册链之前跑过，这里只跑其余。
        val report = mutableListOf<String>()
        var used = 0
        for (name in names) {
            if (used >= MAX_EXTRA_TASK_CLASSES) break
            val clazz = runCatching { Class.forName(name, false, guest.classLoader) }.getOrNull()
                ?: continue
            val methods = clazz.declaredMethods.filter { method ->
                method.parameterCount == 1 &&
                    method.parameterTypes[0] == Application::class.java &&
                    java.lang.reflect.Modifier.isStatic(method.modifiers)
            }
            if (methods.isEmpty()) continue
            used++
            methods.forEach { method ->
                method.isAccessible = true
                report += runStep("${name.substringAfterLast('.')}.${method.name}", method, application)
            }
        }
        return if (report.isEmpty()) "无可用入口" else report.joinToString(",")
    }

    /** 在服务注册链之前把账号这类关键任务跑掉（它们决定启动页能不能继续）。 */
    private fun runPriorityLaunchTasks(guest: Guest, application: Application) {
        if (PRIORITY_LAUNCH_TASKS.isEmpty()) return
        val results = PRIORITY_LAUNCH_TASKS.mapNotNull { runPriorityTask(guest, it, application) }
        trace("priority 任务：${results.joinToString(",").ifEmpty { "无" }}")
    }

    /**
     * 跑关键启动任务（账号初始化）。
     *
     * 同一个类在不同版本里入口签名不一样：
     * - 7.2.4.32 反编译：`r3.k(Application)` 是 **静态**，内部
     *   `b16.f.j(TTAccountConfig, boolean)` 写账号配置；
     * - 7.2.3.65 实测：`r3.k(Application)` 和 `r3.h(Application)` 都是**实例方法**，
     *   另有一个静态桥 `r3.a(r3, Application)` —— 复盘当年补跑的就是 `r3.h(app)`。
     *
     * 只按静态方法筛选会整类漏掉（这正是 `not init TTAccount config` 一直没修掉的
     * 原因），所以静态 / 实例两条路都试，`h` 优先，第一个成功的算数，只跑一次。
     */
    private fun runPriorityTask(guest: Guest, name: String, application: Application): String? {
        val clazz = runCatching { Class.forName(name, false, guest.classLoader) }.getOrNull()
        if (clazz == null) {
            trace("priority $name 缺失")
            return null
        }
        fun takesApplication(type: Class<*>) =
            type == Application::class.java || type.isAssignableFrom(application.javaClass)

        // 7.4.6.32 实测（`methods` 命令）：`r3` 只有 `<init>(Application)` 和
        // 无参 `run()`，是 Runnable 形态；旧版的 `k(Application)` / `h(Application)`
        // 已经不存在。只按 `(Application)` 方法筛会整类漏跑，TTAccount 一直是
        // 未初始化态，请求头里 `Authorization: Bearer ` / `Openid:` 恒为空。
        // 所以构造也优先用 `(Application)`，并把无参 `run()` 作为兜底入口。
        val appInstance = clazz.declaredConstructors.firstOrNull {
            it.parameterCount == 1 && takesApplication(it.parameterTypes[0])
        }?.let { ctor ->
            runCatching {
                ctor.isAccessible = true
                ctor.newInstance(application)
            }.getOrNull()
        }
        val instance = appInstance ?: runCatching {
            clazz.getDeclaredConstructor().newInstance()
        }.getOrNull()

        // `k` 才是写账号配置的那条（7.2.4.32 反编译：`k → b16.f.j(config, boolean)`），
        // `h` 是复盘里用的入口，其余兜底。谁先不抛异常就用谁。
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        for (method in clazz.declaredMethods
            .filter { it.parameterCount == 1 && takesApplication(it.parameterTypes[0]) }
            .sortedBy { when (it.name) { "k" -> 0; "h" -> 1; else -> 2 } }
        ) {
            val isStatic = java.lang.reflect.Modifier.isStatic(method.modifiers)
            if (!isStatic && instance == null) continue
            val target = if (isStatic) null else instance
            actions += (if (isStatic) "static.${method.name}" else "inst.${method.name}") to {
                method.isAccessible = true
                method.invoke(target, application)
                Unit
            }
        }
        val runnable = clazz.declaredMethods.firstOrNull {
            it.name == "run" && it.parameterCount == 0
        }
        if (runnable != null && instance != null) {
            actions += "run" to {
                runnable.isAccessible = true
                runnable.invoke(instance)
                Unit
            }
        }
        for ((label, action) in actions) {
            val failure = runCatching { action() }.exceptionOrNull()
            if (failure == null) return "${name.substringAfterLast('.')}.$label ok"
            val cause = (failure as? java.lang.reflect.InvocationTargetException)?.targetException ?: failure
            trace("priority $name $label 失败：${cause.javaClass.simpleName}: ${cause.message}")
        }
        trace("priority $name 无可用入口：" + clazz.declaredMethods.joinToString(",") { it.name })
        return null
    }

    /** 已由 [invokeLaunchRegistration] 处理、不能再重复跑的启动任务类。 */
    private val SKIPPED_LAUNCH_TASKS = setOf(
        "com.dragon.read.app.launch.task.m0",
        "com.dragon.read.app.launch.task.g4",
        "com.dragon.read.app.launch.task.TimonInitializer",
    )

    /**
     * 账号初始化任务（7.2.4.32 反编译确认：`r3.k(Application)` →
     * `b16.f.j(TTAccountConfig, boolean)`；不跑它，`TTAccount` 永远是未初始化态）。
     */
    private val PRIORITY_LAUNCH_TASKS = listOf("com.dragon.read.app.launch.task.r3")

    /** 一次最多补跑多少个类，避免在一条后台线程上串到超时。 */
    private const val MAX_EXTRA_TASK_CLASSES = 40

    /**
     * guest dex 里的类清单（只读）。
     *
     * 启动任务、账号 SDK 全是混淆短名，反查它们的入口只能靠"类清单"：APK 已经
     * 被 DexClassLoader 加载，`BaseDexClassLoader.pathList.dexElements[].dexFile`
     * 里就有每个 dex 的 `DexFile.entries()`。这是唯一不依赖反编译工具的枚举方式。
     */
    private fun guestClassNames(guest: Guest): List<String> {
        guest.classNames?.let { return it }
        val names = runCatching {
            val pathListField = Class.forName("dalvik.system.BaseDexClassLoader")
                .getDeclaredField("pathList")
                .apply { isAccessible = true }
            val pathList = pathListField.get(guest.classLoader) ?: return@runCatching emptyList()
            val elementsField = pathList.javaClass.getDeclaredField("dexElements")
                .apply { isAccessible = true }
            val elements = elementsField.get(pathList) as? Array<*> ?: return@runCatching emptyList()
            val result = ArrayList<String>(8192)
            for (element in elements) {
                if (element == null) continue
                val dexField = runCatching {
                    element.javaClass.getDeclaredField("dexFile").apply { isAccessible = true }
                }.getOrNull() ?: continue
                val dex = dexField.get(element) as? dalvik.system.DexFile ?: continue
                @Suppress("DEPRECATION")
                val entries = dex.entries()
                while (entries.hasMoreElements()) result += entries.nextElement()
            }
            result
        }.onFailure {
            trace("dex 枚举失败：${it.javaClass.simpleName}: ${it.message}")
        }.getOrDefault(emptyList())
        guest.classNames = names
        return names
    }

    /** 断言方法名：`n()` 是 7.2，`i()` 是 7.4 的 `kf1.j.i()`。 */
    private val RPC_STATUS_METHODS = listOf("n", "i")

    /**
     * 调试用：按正则片段在 guest dex 类清单里找类，并逐个试 `Class.forName`。
     *
     * "栈里有、forName 说没有"是这套虚拟化里最容易误判的一类结论（混淆短名、
     * 运行时自建的 ClassLoader、`<clinit>` 抛 `ExceptionInInitializerError` 都会
     * 表现成 CNFE）。所以这里刻意用 `initialize = false` 只验证"能不能解析到类"，
     * 并把 dex 清单里是否命中一起列出来，两种信息分开看。
     */
    internal fun inspectClasses(pattern: String, limit: Int = 40): String {
        val guest = onlyGuest() ?: return "ERR 没有运行中的 guest"
        val regex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
            ?: return "ERR 正则不合法：$pattern"
        // 直接按完整类名试一次：热修/补丁 dex 可能是在第一次枚举之后才注入
        // `pathList.dexElements` 的，缓存的类清单会漏掉它们。
        val direct = runCatching { Class.forName(pattern, false, guest.classLoader) }
        val directNote = direct.fold(
            onSuccess = { "直接加载 ok (loader=${it.classLoader?.javaClass?.name})" },
            onFailure = { "${it.javaClass.simpleName}: ${it.message}" },
        )
        val elementCount = runCatching {
            val pathList = Class.forName("dalvik.system.BaseDexClassLoader")
                .getDeclaredField("pathList").apply { isAccessible = true }
                .get(guest.classLoader)
            val elements = pathList!!.javaClass.getDeclaredField("dexElements")
                .apply { isAccessible = true }
                .get(pathList) as? Array<*>
            elements?.size ?: -1
        }.getOrDefault(-1)
        val names = guestClassNames(guest)
        if (names.isEmpty()) return "ERR dex 类清单为空（枚举失败，看 trace）"
        val matched = names.filter { regex.containsMatchIn(it) }
        val head = "dexElements=$elementCount，缓存的类清单 ${names.size} 个，匹配 ${matched.size} 个\n" +
            "直接按 '$pattern' 加载：$directNote\n"
        return head + matched.take(limit).joinToString("\n") { name ->
            val resolved = runCatching {
                Class.forName(name, false, guest.classLoader)
            }
            val note = resolved.fold(
                onSuccess = { "ok" },
                onFailure = { "${it.javaClass.simpleName}: ${it.message}" },
            )
            "$name -> $note"
        }
    }

    /**
     * 调试用：打印 guest 类的构造器 / 方法签名。
     *
     * 各版本的混淆入口名和参数表都不一样（`xt0.a.p()` 在 7.2.3.65 有、7.2.4.32
     * 没有），凭猜调用只会拿到 `NoSuchMethodException`。这里直接把真实签名打出来。
     */
    internal fun inspectMethods(className: String): String {
        val guest = onlyGuest() ?: return "ERR 没有运行中的 guest"
        val clazz = runCatching { Class.forName(className, false, guest.classLoader) }
            .getOrElse { return "ERR 加载失败：${it.javaClass.simpleName}: ${it.message}" }
        val builder = StringBuilder("${clazz.name} extends ${clazz.superclass?.name}\n")
        for (ctor in clazz.declaredConstructors) {
            builder.append("<init>(")
                .append(ctor.parameterTypes.joinToString(",") { it.name })
                .append(")\n")
        }
        for (method in clazz.declaredMethods.take(80)) {
            val modifiers = java.lang.reflect.Modifier.toString(method.modifiers)
            builder.append(modifiers).append(' ')
                .append(method.returnType.name).append(' ')
                .append(method.name).append('(')
                .append(method.parameterTypes.joinToString(",") { it.name })
                .append(")\n")
        }
        return builder.toString().trimEnd()
    }

    /**
     * 自检：[GuestAppComponentFactory] 回落逻辑的两步 —— 在 guest loader 里解析类、
     * 用无参构造实例化，并确认类型对不对。
     *
     * 系统那条路径（`ActivityThread.handleReceiver` → `instantiateReceiver`）不好
     * 人为触发，所以把工厂真正做的事单独跑一遍：7.2.4.32 的 `to0.c` 能过这一步，
     * 收广播时就不会再 CNFE 崩掉进程。
     */
    internal fun probeFactory(className: String): String {
        val clazz = resolveGuestClass(className)
            ?: return "ERR guest loader 里没有 $className"
        val instance = runCatching {
            clazz.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        }.getOrElse {
            val cause = (it as? java.lang.reflect.InvocationTargetException)?.targetException ?: it
            return "ERR 实例化失败：${cause.javaClass.simpleName}: ${cause.message}"
        }
        val kind = when (instance) {
            is BroadcastReceiver -> "BroadcastReceiver"
            is Service -> "Service"
            is Activity -> "Activity"
            else -> "其它(${clazz.superclass?.name})"
        }
        return "OK $className -> $kind，构造器可用，工厂可直接接管"
    }

    private const val SERVICES_TIMEOUT_MS = 8_000L

    /** 混淆名都很短，按可能性排列；`c` 是 7.4 实测命中的那个。 */
    private val REGISTER_METHOD_NAMES = listOf("c", "b", "a", "d", "e", "f")

    /**
     * 找到启动任务注册入口。
     *
     * 类名 `com.dragon.read.app.launch.task.g4` 在 7.4 上是 `c(Application)`，
     * 7.2 上同一个类的方法签名已经变了（`NoSuchMethodException: c [Application]`）。
     * 混淆后的方法名无法预测，所以按"带 Application → 无参"的顺序探测常见短名；
     * 全部落空时把完整方法表写进 trace，下一轮即可按真实签名适配。
     */
    private fun pickRegisterMethod(
        registry: Class<*>,
        application: Application,
    ): Pair<java.lang.reflect.Method, Any?> {
        // 7.2 的 g4 是个 Kotlin 函数对象：唯一方法就是 `invoke(Object)`，
        // 语义等价于 7.4 的 `c(Application)`（内部同样走 kf1.j.h(RpcConfig)
        // → 各业务服务注册 → kf1.j.i() 断言）。之前的名单全是短名，永远匹配不到。
        runCatching {
            val invoke = registry.getDeclaredMethod("invoke", Any::class.java)
                .apply { isAccessible = true }
            return invoke to application
        }
        runCatching {
            val invoke = registry.getDeclaredMethod("invoke").apply { isAccessible = true }
            return invoke to null
        }
        for (name in REGISTER_METHOD_NAMES) {
            runCatching {
                val withApp = registry.getDeclaredMethod(name, Application::class.java)
                    .apply { isAccessible = true }
                return withApp to application
            }
            runCatching {
                val noArg = registry.getDeclaredMethod(name).apply { isAccessible = true }
                return noArg to null
            }
        }
        trace(
            "g4 方法表：" + registry.declaredMethods.joinToString { method ->
                "${method.name}(${method.parameterTypes.joinToString { it.simpleName }})"
            },
        )
        throw NoSuchMethodException("g4 上没有可用的注册入口")
    }

    /**
     * 拦截加固壳"自我了断"的那条消息。
     *
     * 壳的退出不是普通 finish：它在 `SplashActivity.onResume` 里 native 侧
     * `Handler.post` 一个 Runnable（类名落在 `org.checkerframework…security.*`
     * 这种伪装命名空间里），47ms 后 `run()` 里 native 调 `finishAffinity()`，
     * 拦不到 binder 就换条路把进程杀掉，而且顺手把
     * `ActivityThread.mInstrumentation` 重置成裸 `Instrumentation()` ——
     * 于是 guest 首页再也建不出来。
     *
     * Runnable 本身是 native 方法改不了，但消息还躺在主线程 MessageQueue 里
     * 时是普通 Java 对象：把这条消息摘掉，判定就不会发生。
     */
    internal fun armShellGuard() {
        val handler = Handler(Looper.getMainLooper())
        trace("shell guard armed")
        var round = 0
        val tick = object : Runnable {
            override fun run() {
                dropShellCallbacks()
                if (++round < SHELL_GUARD_ROUNDS) handler.postDelayed(this, SHELL_GUARD_INTERVAL_MS)
            }
        }
        handler.post(tick)
    }

    private fun dropShellCallbacks() {
        runCatching {
            val queue = Looper.getMainLooper().queue
            val head = queueHeadField.get(queue) as? Message ?: return
            var previous: Message? = null
            var current: Message? = head
            while (current != null) {
                val next = messageNextField.get(current) as? Message
                current.callback?.javaClass?.name?.let { name ->
                    if (seenCallbacks.add(name)) trace("main queue cb $name")
                }
                if (isShellCallback(current)) {
                    if (previous == null) {
                        queueHeadField.set(queue, next)
                    } else {
                        messageNextField.set(previous, next)
                    }
                    trace("dropped shell callback ${current.callback?.javaClass?.name}")
                } else {
                    previous = current
                }
                current = next
            }
        }.onFailure { trace("shell guard 扫描失败：${it.javaClass.simpleName} ${it.message}") }
    }

    private fun isShellCallback(message: Message): Boolean {
        val name = message.callback?.javaClass?.name ?: return false
        return shellNamespaces.any { name.startsWith(it) }
    }

    /** 壳用的伪装命名空间；消息回调落在这里就是那条"退出"指令。 */
    private val shellNamespaces = listOf(
        "org.checkerframework.checker.signature.query.security",
        "sgcore0.",
    )

    private const val SHELL_GUARD_INTERVAL_MS = 6L
    private const val SHELL_GUARD_ROUNDS = 1_600

    /** 只用于把主线程队列里出现过的 Runnable 记一次，排查壳还有哪些"退出"指令。 */
    private val seenCallbacks = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private val queueHeadField by lazy {
        MessageQueue::class.java.getDeclaredField("mMessages").apply { isAccessible = true }
    }

    private val messageNextField by lazy {
        Message::class.java.getDeclaredField("next").apply { isAccessible = true }
    }

    private val splashReloads = ConcurrentHashMap<String, Int>()

    /**
     * 清掉 Activity 的 finishing 标记。
     *
     * 加固壳判定失败后走 `finishAffinity()`：binder 请求已经被
     * `IActivityClientController` 代理吞掉，但 `Activity.mFinished` 已在本地置位，
     * 系统随后照样走 pause/destroy。把标记清回去，Activity 才留得住。
     */
    internal fun clearFinishing(activity: Activity): Boolean = runCatching {
        Class.forName("android.app.Activity")
            .getDeclaredField("mFinished")
            .apply { isAccessible = true }
            .setBoolean(activity, false)
        !activity.isFinishing
    }.getOrDefault(false)

    private fun returnDefault(type: Class<*>): Any? = when (type) {
        Void.TYPE -> null
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        else -> null
    }

    private val clientHookLock = Any()

    @Volatile
    private var clientHooked = false

    private val amHookLock = Any()

    @Volatile
    private var amHooked = false

    /**
     * 需要做组件翻译的 `IActivityManager` 方法。
     *
     * 全部都是"第一个 Intent 参数就是目标组件"的签名；`unbindService` 只有连接
     * 对象、`stopServiceToken` 只有 token，二者本来就不带组件，不需要翻译。
     */
    private val SERVICE_CALLS = setOf(
        "startService",
        "startServiceInstance",
        "stopService",
        "bindService",
        "bindIsolatedService",
        "bindServiceInstance",
        "peekService",
    )

    /**
     * Rewrites a guest-owned Activity launch into the proxy component.
     *
     * Returns [intent] untouched for anything that is not addressed to a guest
     * package, so the host and system targets keep working.
     */
    internal fun rewriteForGuest(intent: Intent): Intent {
        val targetComponent = intent.component ?: return intent
        // 已经是代理组件的 intent 不能再改：重写会把 GuestActivityProxy 自己
        // 写成目标类，newActivity 随后 Class.forName("…GuestActivityProxy")
        // 在 guest 的 ClassLoader 里根本不存在，Activity 直接建不出来。
        if (GuestSlots.isProxyClass(targetComponent.className)) return intent
        if (intent.hasExtra(GuestLauncher.EXTRA_GUEST_ACTIVITY)) return intent
        val guest = guestForPackage(targetComponent.packageName) ?: return intent
        val host = hostContext() ?: return intent
        val rewritten = Intent(intent).apply {
            component = ComponentName(host.packageName, GuestSlots.proxyClassName(currentSlot()))
            putExtra(GuestLauncher.EXTRA_INSTANCE_ID, guest.instanceId)
            putExtra(GuestLauncher.EXTRA_GUEST_ACTIVITY, targetComponent.className)
            putExtra(GuestLauncher.EXTRA_GUEST_INTENT, Intent(intent))
        }
        trace("startActivity ${targetComponent.className} -> proxy")
        return rewritten
    }

    /**
     * Rewrites a guest `startService` / `bindService` into the slot's
     * [GuestServiceProxy]. Returns [intent] untouched when it does not target a
     * guest package (host and system services must reach AMS as they are).
     *
     * guest 的服务组件同样不在宿主清单里，AMS 只会回 `Unable to start service`
     * （bind 侧是 `Binder has been finalized`/超时）。真身类名和原始 Intent 走
     * extra，由代理创建实例并转发生命周期。
     */
    internal fun rewriteServiceForGuest(intent: Intent): Intent {
        val target = intent.component ?: return intent
        // 已经是代理：不能再改，否则真身类名会被写成代理类名。
        if (GuestSlots.isServiceClass(target.className)) return intent
        if (intent.hasExtra(GuestLauncher.EXTRA_GUEST_SERVICE)) return intent
        val guest = guestForPackage(target.packageName) ?: return intent
        val host = hostContext() ?: return intent
        val index = serviceProxyIndex(guest, target.className)
        val rewritten = Intent(intent).apply {
            component = ComponentName(
                host.packageName,
                GuestSlots.serviceClassName(currentSlot(), index),
            )
            putExtra(GuestLauncher.EXTRA_INSTANCE_ID, guest.instanceId)
            putExtra(GuestLauncher.EXTRA_GUEST_SERVICE, target.className)
            putExtra(GuestLauncher.EXTRA_GUEST_INTENT, Intent(intent))
        }
        trace("service ${target.className} -> proxy#$index")
        return rewritten
    }

    /**
     * 每个 guest Service 固定占一个代理类下标，进程内不再变化。
     *
     * 必须稳定：AMS 用组件身份区分 ServiceRecord，同一个 guest 服务的
     * start / bind / stop 落到不同代理类上就会变成"另一个服务"。
     */
    private val serviceProxyIndexByClass = HashMap<String, Int>()
    private val serviceProxyIndexInUse = HashMap<String, MutableSet<Int>>()

    private fun serviceProxyIndex(guest: Guest, className: String): Int =
        synchronized(serviceProxyIndexByClass) {
            val key = "${guest.instanceId}|$className"
            serviceProxyIndexByClass[key]?.let { return it }
            val used = serviceProxyIndexInUse.getOrPut(guest.instanceId) { mutableSetOf() }
            val index = (0 until GuestSlotContract.SERVICE_POOL).firstOrNull { it !in used }
            if (index == null) {
                // 池满：退回 0 号（会和别的服务共享组件，stopService 会误伤），
                // 但至少 guest 还能启动，trace 里能直接看到是哪一类超限。
                trace("service 代理池已满（${GuestSlotContract.SERVICE_POOL}），$className 共享 proxy#0")
                return 0
            }
            used.add(index)
            serviceProxyIndexByClass[key] = index
            index
        }

    /** 代理收到的 Intent 换回 guest 原来的那份（没有就原样用代理 Intent）。 */
    internal fun guestServiceIntent(intent: Intent?): Intent? {
        val source = intent ?: return null
        @Suppress("DEPRECATION")
        val original = source.getParcelableExtra<Intent>(GuestLauncher.EXTRA_GUEST_INTENT)
        return original ?: source
    }

    /**
     * 记录 Toast 文本并按槽位登记 token，用于停止时精确 cancel。
     *
     * 参考项目只声明了 `Toast.sService`，没有消费者。这里把 `Toast` 与
     * `NotificationManager` 两个静态服务字段都挂到同一个 [GuestToastController] 代理：
     * 系统 text Toast 读的是 Toast 自己的缓存，只换 NotificationManager 会漏掉。
     * 一槽位一 guest，TN / Handler 随进程天然隔离；控制器负责归属、日志和回收。
     */
    internal fun installToastLogger(): String? {
        synchronized(atmHookLock) {
            if (toastHooked) return null
            return runCatching {
                val nmClass = Class.forName("android.app.NotificationManager")
                val getService = nmClass.getDeclaredMethod("getService").apply { isAccessible = true }
                val service = getService.invoke(null) ?: error("getService 返回 null")
                val serviceInterface = getService.returnType
                val field = nmClass.getDeclaredField("sService").apply { isAccessible = true }
                val controller = GuestToastController(
                    notificationService = service,
                    serviceInterface = serviceInterface,
                    instanceId = { guests.values.firstOrNull()?.instanceId },
                    trace = ::trace,
                )
                val proxy = java.lang.reflect.Proxy.newProxyInstance(
                    serviceInterface.classLoader,
                    arrayOf(serviceInterface),
                ) { _, method, args ->
                    when {
                        method.name.startsWith("enqueueTextToast") ||
                            method.name.startsWith("enqueueToast") ->
                            controller.observeEnqueue(method, args)

                        method.name.startsWith("cancelToast") -> controller.observeCancel(args)
                    }
                    try {
                        method.invoke(service, *args)
                    } catch (error: java.lang.reflect.InvocationTargetException) {
                        throw error.targetException ?: error
                    }
                }
                // Android 的 Toast 类自己缓存了 INotificationManager；只改
                // NotificationManager.sService 时 text Toast 会绕过我们的代理。
                val toastServiceField = runCatching {
                    Class.forName("android.widget.Toast").declaredFields.firstOrNull { candidate ->
                        java.lang.reflect.Modifier.isStatic(candidate.modifiers) &&
                            candidate.type == serviceInterface
                    }?.apply { isAccessible = true }
                }.getOrNull()
                field.set(null, proxy)
                val toastFieldInstalled = runCatching {
                    toastServiceField?.set(null, proxy)
                    toastServiceField != null
                }.getOrDefault(false)
                toastController = controller
                toastHooked = true
                trace("NotificationManager/Toast hook installed toastStatic=$toastFieldInstalled")
                null
            }.getOrElse {
                trace("Toast hook 失败：$it")
                "${it.javaClass.simpleName}: ${it.message}"
            }
        }
    }

    @Volatile
    private var toastHooked = false

    @Volatile
    private var toastController: GuestToastController? = null

    private fun currentSlot(): Int = GuestSlots.currentSlot ?: 0

    internal fun guestForPackage(packageName: String?): Guest? =
        packageName?.let { name -> guests.values.firstOrNull { it.packageName == name } }

    /**
     * Loads dex, resources, package metadata and native libraries for [request].
     *
     * Synchronous on purpose: the instrumentation must find the guest already
     * built when the Activity is created, which happens inside `startActivity`
     * on the calling thread.
     */
    internal fun prepare(context: Context, request: LaunchRequest): Guest {
        // One ClassLoader per instance, for the whole process lifetime.
        //
        // A second DexClassLoader over the same APK cannot load libflutter.so
        // again: ART refuses to map the same library path into two loaders
        // ("already opened by ClassLoader ..."), and every guest class (Flutter
        // singleton included) would be a second, unrelated copy. Re-launching is
        // therefore a matter of reusing this object, not rebuilding it.
        guests[request.instanceId]?.let { cached ->
            if (cached.apkPath == request.apkPath) return cached
        }

        val apk = File(request.apkPath)
        if (apk.canWrite()) apk.setReadOnly()

        // Native libs must be on the loader's search path: Flutter resolves
        // libdartjni.so / libflutter.so through ClassLoader.findLibrary, and a
        // null library path leaves only /system/lib64 to look in.
        val nativeLibDir = extractNativeLibs(context, request)

        val dexDir = File(context.codeCacheDir, "guest/${request.instanceId}").apply { mkdirs() }
        val loader = DexClassLoader(
            request.apkPath,
            dexDir.absolutePath,
            nativeLibDir,
            context.classLoader.parent,
        )

        val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
        val addAssetPath = AssetManager::class.java
            .getDeclaredMethod("addAssetPath", String::class.java)
            .apply { isAccessible = true }
        addAssetPath.invoke(assets, request.apkPath)
        val host = context.resources
        val resources = Resources(assets, host.displayMetrics, host.configuration)

        // Flutter resolves its bundled `flutter_assets` (fonts, shaders, manifests)
        // through an AssetManager it obtained at engine start-up. Making the guest
        // APK visible to the host AssetManager keeps that path working even when
        // the engine holds the host instance instead of the guest one.
        runCatching { addAssetPath.invoke(context.assets, request.apkPath) }
            .onFailure { android.util.Log.e("GuestRuntime", "宿主 assets 追加 guest 失败", it) }

        val packageInfo = loadPackageInfo(context, request, nativeLibDir)

        // Diagnostics: Flutter registers fonts from flutter_assets/FontManifest.json
        // through this AssetManager. If these can't be opened, icons render as tofu.
        runCatching {
            val manifest = assets.open("flutter_assets/FontManifest.json").use { it.readBytes() }
            android.util.Log.i("GuestRuntime", "FontManifest ok ${manifest.size}B")
            val font = assets.open("flutter_assets/fonts/MaterialIcons-Regular.otf")
                .use { it.readBytes() }
            android.util.Log.i("GuestRuntime", "MaterialIcons ok ${font.size}B")
        }.onFailure { android.util.Log.e("GuestRuntime", "asset read failed", it) }

        // 二次打包的包（比如"纯净版"）经常把 MAIN/LAUNCHER 的 intent-filter 删掉，
        // 引擎解析出来的 launcher 就是空串。这里回退到清单里最像启动页的 Activity，
        // 否则后面 Class.forName("") 会直接失败，表现为"点了没反应"。
        val launcherActivity = request.launcherActivity.ifBlank {
            val activities = packageInfo.activities.orEmpty()
            val fallback = activities.firstOrNull { it.name.contains("Splash", ignoreCase = true) }?.name
                ?: activities.firstOrNull { it.exported }?.name
                ?: activities.firstOrNull()?.name
                ?: ""
            trace("launcher 未声明，回退到 $fallback")
            fallback
        }

        val guest = Guest(
            instanceId = request.instanceId,
            apkPath = request.apkPath,
            packageName = request.packageName,
            launcherActivity = launcherActivity,
            classLoader = loader,
            resources = resources,
            applicationInfo = requireNotNull(packageInfo.applicationInfo) { "guest ApplicationInfo 缺失" },
            packageInfo = packageInfo,
            dataDir = File(request.dataPath).apply { mkdirs() }.absolutePath,
        )

        // Context and Application are built on first attach: they need a real
        // LoadedApk/ContextImpl, which requires the ActivityThread and is only
        // safe on the main thread.
        guests[request.instanceId] = guest
        // 先豁免 hidden API 再装 hook：`ActivityTaskManager.getService` 这类成员
        // 在没豁免时反射里**不存在**（getDeclaredMethod 直接 NoSuchMethodException），
        // ATM hook 因此一直装不上（trace: "ActivityTaskManager hook 失败"）。
        // 之前只在 ensureGuestContext 里豁免，那时这个 hook 早就装失败了。
        HiddenApiUnsealer.unseal(context)
        // Redirect guest Activity launches before any guest code can send one.
        installStartActivityRedirect()
        installToastLogger()
        // 首次 addView 之前 `sWindowSession` 通常还是 null，这里先试一次，
        // 真正装上是在 Activity attach 之后（那时启动页已经把 session 建好了）。
        installWindowSessionProxy()
        return guest
    }

    /** Instantiates and attaches the Application declared by the guest manifest. */
    private fun createApplication(context: Context, guest: Guest): Application? {
        val className = guest.applicationInfo.className
        return runCatching {
            val application = if (className.isNullOrBlank()) {
                // No Application declared: use the instrumented one so the asset
                // path stays observable.
                GuestApplication()
            } else {
                // 构造期也可能建池（Java 单例线程池挂在静态字段里），
                // 所以这里再压一次 bytex 开关，保证 onCreate 之前一定已生效。
                fixBytexThreadPool(guest)
                val clazz = Class.forName(className, true, guest.classLoader)
                clazz.getDeclaredConstructor().newInstance() as Application
            }
            // Publish the instance before it is initialised: plugin code calls
            // getApplicationContext() (and casts it to Application) from inside
            // attachBaseContext/onCreate, and {@link GuestContext} answers with
            // whatever this field holds.
            guest.application = application
            guest.loadedApk?.let { apk ->
                setField(apk.javaClass, apk, "mApplication", application)
            }
            // attachBaseContext is declared on ContextWrapper, not Application.
            val attach = ContextWrapper::class.java
                .getDeclaredMethod("attachBaseContext", Context::class.java)
                .apply { isAccessible = true }
            attach.invoke(application, context)
            // onCreate is deliberately left to startApplication(): a full app
            // init (MMKV, push, hook frameworks) may not run inside the
            // framework's own launch transaction.
            application
        }.onFailure {
            guest.application = null
            android.util.Log.e("GuestRuntime", "创建 guest Application 失败：$className", it)
        }.getOrNull()
    }

    /**
     * Runs `Application.onCreate` once, from inside our Activity launch.
     *
     * Real Android calls it before any Activity exists. Here it runs right
     * before `Activity.performCreate` instead: the Application still sees its
     * Activity come up as the very next step, but its heavy initialisation no
     * longer sits inside `Instrumentation.newActivity`, where a stolen
     * ActivityThread.mInstrumentation would silently skip `attach()`.
     */
    internal fun startApplication(guest: Guest) {
        if (guest.applicationStarted) return
        guest.applicationStarted = true
        val application = guest.application ?: return
        trace("startApplication ${application.javaClass.name}")
        // 硬约束：bytex 线程收敛开关必须赶在任何线程池被创建之前。
        trace("bytex threadpool=${fixBytexThreadPool(guest)}")
        launchMark("Application.onCreate 开始")
        runCatching { application.onCreate() }
            .onFailure {
                trace("Application.onCreate 失败 ${it.javaClass.name}: ${it.message}")
                android.util.Log.e("GuestRuntime", "guest Application.onCreate 失败", it)
            }
        trace("Application.onCreate done")
        launchMark("Application.onCreate 完成")
        trace("executors after create=${probeExecutors(guest)}")
        // 加固壳在自己的 Application 初始化里会重装一批信号处理器，
        // 这一步把 SIGSEGV 那套再抢回来，否则崩的时候拿不到 pc/lr。
        hostContext()?.let { CrashProbe.install(it) }
        trace("crash probe reinstalled")
        probeSafeMode(guest)
        // Hook frameworks installed by the app (Mira in 红果) replace
        // ActivityThread.mInstrumentation while initialising; take it back so
        // later lifecycle calls keep going through us.
        ensureHook()
        initGuestProviders(guest)
        ensureMiraPlugins(guest, application)
        // 注册越早越好：启动页在 onResume 后 ~700ms 就会在自己的后台任务里读
        // 账号配置（`not init TTAccount config`），而之前这里是等跳首页时才跑，
        // 晚了 4 秒。放到后台线程是因为 RPC 初始化会阻塞等待 HandlerThread。
        ensureGuestServices(guest)
        launchMark("guest services 已发起")
        enablePlayerLogging(guest)
        enableNetworkLogging(guest)
        ensureAppLogStarted(guest)
        traceCleartextPolicy(guest)
        trace("rx schedulers=${probeRxSchedulers(guest)}")
        trace("request trace=${installRequestTrace(guest)}")
        launchMark("startApplication 完成")
    }

    /** guest Service 实例 → 所属 guest；代理在 onDestroy 里查回用。 */
    private val guestServices = Collections.synchronizedMap(WeakHashMap<Service, Guest>())

    internal fun guestServiceOf(service: Service?): Guest? =
        service?.let { guestServices[it] }

    internal fun forgetGuestService(service: Service?) {
        if (service != null) guestServices.remove(service)
    }

    /**
     * 创建并接上 guest 的 Service 真身。
     *
     * Service 不经过 Instrumentation：系统实例化的是 [GuestServiceProxy]，guest 的
     * 类由我们自己 new，然后把框架 `Service.attach` 会写的字段手工补齐 —— base
     * context 换成 guest context（assets/resources/packageName 按 guest 回答），
     * token 与 IActivityManager 用代理那一份，这样 guest 里的 `stopSelf()` /
     * `startForeground()` 落到系统侧指向的仍是这个真实登记的组件。
     *
     * 生命周期由代理逐个转发；这里只负责造对象，不调 `onCreate`。
     */
    internal fun createGuestService(proxy: Service, intent: Intent?): Service? {
        if (intent == null) {
            trace("service 代理没有 Intent，无法确定目标 Service")
            return null
        }
        val className = intent.getStringExtra(GuestLauncher.EXTRA_GUEST_SERVICE)
            ?: intent.component?.className
        if (className.isNullOrBlank()) {
            trace("service 代理缺少目标类名：$intent")
            return null
        }
        val guest = guestFor(intent.getStringExtra(GuestLauncher.EXTRA_INSTANCE_ID))
            ?: guestForPackage(intent.component?.packageName)
            ?: onlyGuest()
        if (guest == null) {
            // 进程被系统重建（例如绑定恢复）时 Runtime 里没有 guest 实例，缺
            // LaunchRequest 也无法重建：老实失败，比造一个半残实例好。
            trace("service $className 没有对应 guest（进程可能刚被重建）")
            return null
        }
        return runCatching {
            ensureInitialized(proxy, guest)
            // guest 的 Service 允许在 Application 生命周期之前被创建？不行 ——
            // Service 构造器/onCreate 普遍直接读 Application 的静态上下文。
            startApplication(guest)
            ensureHook()
            val clazz = Class.forName(className, true, guest.classLoader)
            val service = clazz.getDeclaredConstructor().apply { isAccessible = true }
                .newInstance() as? Service ?: error("$className 不是 android.app.Service")
            attachGuestService(proxy, service, guest)
            guestServices[service] = guest
            trace("service 创建 ${clazz.name}（${guest.instanceId}）")
            service
        }.getOrElse {
            trace(
                "service 创建失败 $className：${it.javaClass.simpleName}: ${it.message}\n" +
                    it.stackTraceToString(),
            )
            android.util.Log.e("GuestRuntime", "guest Service 创建失败：$className", it)
            null
        }
    }

    /** 把 guest Service 的 Context / Application / token 接到宿主进程里。 */
    private fun attachGuestService(proxy: Service, service: Service, guest: Guest) {
        val context = guest.context ?: hostContext() ?: return
        val token = runCatching {
            Service::class.java.getDeclaredField("mToken").apply { isAccessible = true }.get(proxy)
        }.getOrNull()
        val manager = runCatching {
            Class.forName("android.app.ActivityManager")
                .getDeclaredMethod("getService")
                .apply { isAccessible = true }
                .invoke(null)
        }.getOrNull()
        // Service extends ContextWrapper：换掉 mBase，资产/资源/包名/类加载器全按
        // guest 回答，系统服务仍然走宿主（和 GuestContext 的约定一致）。
        setField(ContextWrapper::class.java, service, "mBase", context)
        setField(Service::class.java, service, "mApplication", guest.application)
        setField(Service::class.java, service, "mClassName", service.javaClass.name)
        if (token != null) setField(Service::class.java, service, "mToken", token)
        if (manager != null) setField(Service::class.java, service, "mActivityManager", manager)
    }

    /**
     * 请求级埋点（复盘 0.9.19 的手段）。
     *
     * "播放页一个请求都不发" 和 "发了但失败" 是两条完全不同的排查线，而
     * videoshop 把两者都渲染成「网络错误，请稍后重试」。这里用 `Proxy` 实现字节
     * 的 `com.bytedance.retrofit2.intercept.Interceptor` 装进 retrofit，把每条
     * 请求的 `code/耗时/URL` 写进 trace —— 播放页有没有 `video_model` 请求、
     * 返回码是多少，一眼就能看出来。
     */
    private fun installRequestTrace(guest: Guest): String {
        val interceptor = runCatching {
            Class.forName("com.bytedance.retrofit2.intercept.Interceptor", false, guest.classLoader)
        }.getOrNull() ?: return "Interceptor 缺失"
        val utils = runCatching {
            Class.forName("com.bytedance.ttnet.utils.RetrofitUtils", false, guest.classLoader)
        }.getOrNull() ?: return "RetrofitUtils 缺失"
        val methods = utils.declaredMethods
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .joinToString(",") { "${it.name}(${it.parameterTypes.joinToString(",") { p -> p.simpleName }})" }
        trace("retrofit utils=$methods")

        val proxy = runCatching {
            java.lang.reflect.Proxy.newProxyInstance(guest.classLoader, arrayOf(interceptor)) { _, method, args ->
                if (method.name != "intercept" || args.isNullOrEmpty()) {
                    return@newProxyInstance when (method.returnType) {
                        java.lang.Boolean.TYPE -> false
                        Integer.TYPE -> 0
                        java.lang.Long.TYPE -> 0L
                        else -> null
                    }
                }
                val chain = args[0]
                val request = runCatching {
                    chain.javaClass.getMethod("request").invoke(chain)
                }.getOrNull() ?: return@newProxyInstance null
                val url = extractRequestUrl(request)
                // 公共参数（device_id / iid / aid / 签名头）都挂在 request header 上，
                // 只读不改：服务端回 PARAM_INVALID 时，第一件事是看这些有没有缺。
                if (url != null && TRACE_HEADER_PATTERN.containsMatchIn(url)) {
                    val shape = runCatching {
                        val m = request.javaClass.getMethod("getMethod").invoke(request)
                        val b = request.javaClass.getMethod("getBody").invoke(request)
                        "method=$m body=${b?.javaClass?.name ?: "null"}"
                    }.getOrDefault("shape=?")
                    trace("HDR $url -> $shape | ${describeHeaders(request)}")
                    // 播放接口的参数全在 POST body 里（URL 上没有 query），
                    // 取不到 body 就分不清"没带 vid"和"服务端拒绝"。
                    if (PLAYER_TRACE_PATTERN.containsMatchIn(url)) {
                        trace("REQBODY $url | ${describeRequestBody(request)}")
                        trace("STUB $url | ${describeStub(request)}")
                        // app 内这条响应体读不到（interceptor 阶段已 EOF），
                        // 用同一份 URL / body / header 另开一条连接重放一次，
                        // 直接看服务端到底回了什么。独立连接，不影响 app 自己那条。
                        if ("video_model" in url) {
                            replayPlayerRequest(request, url)
                        }
                    }
                    // 第一条真实请求发出时 AppLog 必然已经初始化完，此时取值才有意义。
                    if (applogProbed.compareAndSet(false, true)) {
                        trace("APPLOG ${probeAppLog(guest)}")
                    }
                }
                val proceed = chain.javaClass.methods
                    .firstOrNull { it.name == "proceed" && it.parameterCount == 1 }
                val effective = when {
                    url == null -> request
                    // 全局补公共参数：guest 自己的注入链没跑起来，实测一次冷启动里
                    // 有 **139 个** `*.snssdk.com` 请求光秃秃上路（`reading/user`、
                    // `bookshelf`、`commerceapi`、`ugc` …），服务端只能靠 query 认设备。
                    // 与其逐个接口打补丁，不如统一补一整套。
                    ENABLE_GLOBAL_APPLOG_PARAMS && !HAS_AID.containsMatchIn(url) &&
                        API_HOST_PATTERN.containsMatchIn(url) -> {
                        // AppLog 还没起来时（冷启动最前面几条）它给不出参数，
                        // 这时退回手写的那 6 个静态应用参数，别让首页/剧场重启时
                        // 又掉回 `100103 PARAM_INVALID`。
                        val patched = patchUrlWithAppLog(request, guest, url, "公共参数")
                        if (patched !== request) {
                            patched
                        } else if (ENABLE_BOOKAPI_COMMON_PARAMS &&
                            BOOKAPI_TAB_PATTERN.containsMatchIn(url)
                        ) {
                            patchCommonParams(request, url)
                        } else {
                            request
                        }
                    }
                    ENABLE_BOOKAPI_COMMON_PARAMS && BOOKAPI_TAB_PATTERN.containsMatchIn(url) ->
                        patchCommonParams(request, url)
                    else -> request
                }
                // 播放接口全是 POST 且声明 `Content-Encoding: gzip`，但 `REQBODY`
                // 打出来的是明文 JSON（压缩本该由 TNet 的 TTRequestCompressManager
                // 在发送前做）。拿到数据的接口全是 GET，空 200 的全是 POST，
                // 所以先验证"声明了 gzip、发的却是明文"这一条：摘掉声明让两者一致。
                val send = when {
                    url == null -> effective
                    ENABLE_PLAYER_POST_FIX && PLAYER_POST_PATTERN.containsMatchIn(url) ->
                        stripHeader(effective, "Content-Encoding")
                    ENABLE_PLAYER_BODY_PATCH && "video_model" in url ->
                        // 第一条保持原样做基线，后面的都换成详情接口给的 vid。
                        if (playerPatchCount.incrementAndGet() <= 1) {
                            effective
                        } else {
                            patchPlayerVideoId(effective, PLAYER_PATCH_VID)
                        }
                    ENABLE_PLAYER_URL_PARAMS && PLAYER_POST_PATTERN.containsMatchIn(url) ->
                        patchCommonParams(effective, url)
                    ENABLE_PLAYER_APPLOG_PARAMS && PLAYER_POST_PATTERN.containsMatchIn(url) ->
                        patchUrlWithAppLog(effective, guest, url, "播放接口公共参数")
                    else -> effective
                }
                val started = System.currentTimeMillis()
                try {
                    val response = proceed!!.invoke(chain, send)
                    val code = runCatching {
                        response.javaClass.getMethod("code").invoke(response)
                    }.getOrNull()
                    trace("REQ ${code ?: "?"} ${System.currentTimeMillis() - started}ms $url")
                    var out = response
                    if (url != null && TRACE_BODY_PATTERN.containsMatchIn(url)) {
                        out = dumpAndRestoreBody(response, url)
                    }
                    out
                } catch (t: Throwable) {
                    val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
                    trace("REQ FAIL ${cause.javaClass.simpleName} ${System.currentTimeMillis() - started}ms $url")
                    throw cause
                }
            }
        }.getOrNull() ?: return "Proxy 失败"

        for (method in utils.declaredMethods) {
            if (!java.lang.reflect.Modifier.isStatic(method.modifiers)) continue
            if (method.parameterCount != 1) continue
            if (!method.parameterTypes[0].isAssignableFrom(proxy.javaClass)) continue
            val ok = runCatching {
                method.isAccessible = true
                method.invoke(null, proxy)
            }.isSuccess
            if (ok) return "已装入 ${method.name}"
        }
        return "未找到装入点（方法表见上）"
    }

    /**
     * 补齐 `reading/bookapi` tab 接口缺失的应用公共参数 —— 首页/剧场
     * 「网络出错，请点击重试」的根因。
     *
     * guest 的 Application 是 Tinker 的 `MuteApplicationStub`（静默占位），真实
     * `com.dragon.read.app.MainApplication` 的 `onCreateAlways`、
     * `onCreateAfterPrivacyConfirmed` 初始化链没有执行，于是
     * `AppLog.hasStarted()` 为 false、`getAid()` 为空、`getRequestHeader()` 为 null。
     * `AppLog.addNetCommonParams` 只能补出 `iid`、`device_id`、`openudid`、
     * `_rticket` 这类设备参数，补不出 `aid`、`app_name`、`version_code`、
     * `version_name`、`channel`，服务端因此对整类 tab 接口回
     * `100103 PARAM_INVALID`（HTTP 200 只是表象）。
     *
     * 下面这组值取自 guest 自己发的 `i.snssdk.com/service/settings/v3` 请求（实读），
     * 对 7.4.6.32 固定；升级 guest 版本时要同步 `version_code`、`version_name`。
     * 实测补上后 `100103` 从 4~6 次降到 0，首页与剧场都出现真实榜单内容。
     * 治本方向是补跑 `MainApplication` 初始化链，风险高，先由这层兼容保证可用。
     */
    private const val ENABLE_BOOKAPI_COMMON_PARAMS = true
    private val BOOKAPI_TAB_PATTERN = Regex(
        "reading/bookapi/(bookmall/tab|sstimor/tab|sstimor/entry_type)",
    )
    private const val BOOKAPI_COMMON_PARAMS =
        "aid=8662&app_name=novelread&version_code=74632&version_name=7.4.6.32" +
            "&channel=update_64&device_platform=android"
    private val HAS_AID = Regex("[?&]aid=")

    private fun patchCommonParams(request: Any, url: String): Any {
        // 已经带 aid 说明 guest 自己注入过，不要重复追加。
        if (HAS_AID.containsMatchIn(url)) return request
        return runCatching {
            val builder = request.javaClass.getMethod("newBuilder").invoke(request)
            val patched = url + (if ('?' in url) "&" else "?") + BOOKAPI_COMMON_PARAMS
            builder.javaClass.getMethod("url", String::class.java).invoke(builder, patched)
            val built = builder.javaClass.getMethod("build").invoke(builder)
            trace("PATCH 公共参数 -> ${url.substringAfter("/reading/").take(80)}")
            built
        }.getOrDefault(request)
    }

    /**
     * 问 AppLog 要一整套公共参数（`aid` / `iid` / `device_id` / `_rticket` / `cdid` …）。
     *
     * 手写的 [BOOKAPI_COMMON_PARAMS] 只有 6 个静态项，够让 `bookmall/tab` 通过；
     * 但播放接口在 `tt_agw` 网关后面，除了应用身份大概率还要**设备身份**。
     * 这里让 AppLog 自己把 query 生成出来（时间戳也由它负责），再拼到目标 URL 上。
     */
    private fun appLogCommonQuery(guest: Guest, sampleUrl: String): String? = runCatching {
        val appLog = Class.forName("com.bytedance.applog.AppLog", false, guest.classLoader)
        val levelClass = Class.forName("com.bytedance.bdinstall.Level", false, guest.classLoader)
        val level = levelClass.enumConstants?.firstOrNull() ?: return@runCatching null
        val method = appLog.methods.firstOrNull {
            it.name == "addNetCommonParams" && it.parameterCount == 4 &&
                it.parameterTypes[1] == String::class.java
        } ?: return@runCatching null
        method.isAccessible = true
        val filled = method.invoke(null, guest.application, sampleUrl, false, level)?.toString()
            ?: return@runCatching null
        filled.substringAfter('?', "").ifBlank { null }
    }.getOrNull()

    private fun patchUrlWithAppLog(request: Any, guest: Guest, url: String, tag: String): Any {
        if (HAS_AID.containsMatchIn(url)) return request
        // 传**去掉原 query 的**基址：AppLog 会把参数追加在后面，只看它新增的那部分，
        // 否则原 query 会被复制一遍（`?book_type=5&…&book_type=5&…`）。
        val query = appLogCommonQuery(guest, url.substringBefore('?')) ?: return request
        return runCatching {
            val builder = request.javaClass.getMethod("newBuilder").invoke(request)
            val patched = url + (if ('?' in url) "&" else "?") + query
            builder.javaClass.getMethod("url", String::class.java).invoke(builder, patched)
            val built = builder.javaClass.getMethod("build").invoke(builder)
            trace("PATCH $tag（${query.length}B）-> ${url.substringAfter("snssdk.com")}")
            built
        }.getOrDefault(request)
    }

    private val applogProbed = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 只读探一次设备注册链：`AppLog.addNetCommonParams` 负责把 `aid` / `did` /
     * `iid` 等公共参数追加到 URL。整类 `reading/bookapi` 下的接口返回
     * `100103 PARAM_INVALID`，而 Retrofit 层看到的 URL 上一个公共参数都没有，
     * 所以要确认是 AppLog 没启动、设备标识为空，还是注入函数本身没生效。
     * 全程只调静态 getter 和一次纯字符串拼接，不发网络请求。
     */
    private fun probeAppLog(guest: Guest): String {
        val clazz = runCatching {
            Class.forName("com.bytedance.applog.AppLog", false, guest.classLoader)
        }.getOrNull() ?: return "AppLog 缺失"
        fun call(name: String): String = runCatching {
            clazz.getMethod(name).invoke(null)?.toString()?.take(80) ?: "null"
        }.getOrDefault("ERR")
        val state = listOf(
            "hasStarted", "getAid", "getAppId", "getIid", "getDid", "getClientUdid",
            "getOpenUdid", "getSsid", "getSessionId", "getAbSdkVersion",
        ).joinToString(",") { "$it=${call(it)}" }
        val headers = runCatching {
            val map = clazz.getMethod("getRequestHeader").invoke(null) as? Map<*, *>
            map?.entries?.joinToString(";") { "${it.key}=${it.value.toString().take(60)}" }
                ?.take(600) ?: "null"
        }.getOrDefault("ERR")
        val headerJson = runCatching {
            clazz.getMethod("getHeader").invoke(null)?.toString()?.take(500) ?: "null"
        }.getOrDefault("ERR")
        val common = runCatching {
            val levelClass = Class.forName(
                "com.bytedance.bdinstall.Level", false, guest.classLoader,
            )
            val level = levelClass.enumConstants?.firstOrNull()
                ?: return@runCatching "Level 枚举为空"
            val method = clazz.methods.firstOrNull {
                it.name == "addNetCommonParams" && it.parameterCount == 4 &&
                    it.parameterTypes[1] == String::class.java
            } ?: return@runCatching "addNetCommonParams 缺失"
            method.isAccessible = true
            val url = method.invoke(
                null, guest.application,
                "https://reading.snssdk.com/reading/bookapi/bookmall/tab/v?probe=1",
                false, level,
            )?.toString() ?: return@runCatching "null"
            val keys = listOf(
                "aid=", "iid=", "did=", "device_id=", "app_name=", "version_code=",
                "channel=", "cdid=", "openudid=", "udid=", "ts=", "_rticket=",
            )
            val picked = url.substringAfter('?').split('&')
                .filter { p -> keys.any { p.startsWith(it) } }
            "len=${url.length} picked=${picked.joinToString(";").take(700)}"
        }.getOrDefault("ERR")
        return "$state | requestHeader=$headers | headerJson=$headerJson | commonUrl=$common"
    }

    /**
     * 只对这些接口抓响应体，避免把整条链路拖慢。
     *
     * 21:32 对照实验结论：停掉播放接口的 BODY 抓取后播放页**仍然报网络错误**，
     * 所以 `dumpAndRestoreBody` 不是根因（7.4.6.32 的 `vl0.a$a` 没有静态
     * `create`，走只读分支；`peekTypedInput` 也正确跳过了唯一流）。
     * 现在把播放接口加回来，靠 `BODY-NESTED` 从 Retrofit 包装里读真正的
     * 业务模型（`BookApiERR` / code / message），查 200 之下的业务错误。
     */
    private val TRACE_BODY_PATTERN = Regex(
        "bookmall/tab|sstimor/entry_type|sstimor/tab|" +
            "video_model|video_detail|media/play/strategy|multi_video_detail|" +
            // 对照组：详情页确实渲染出了数据，它能读到 body 就说明 trace 层没被
            // 整条链路堵死，`markable=false` 只发生在播放接口。
            "inner_feed_detail",
    )

    /**
     * 对这些"200 却报 PARAM_INVALID / 网络错误"的接口抓请求头。
     *
     * `inner_feed_detail` 必须在列：它同样是 **POST + gzip 体**（Cronet 层
     * `fixedContentLength=201`），却拿到了 10749B 数据，是唯一的"成功的 POST"
     * 参照物，少了它就没法回答"播放接口到底差在哪"。
     */
    private val TRACE_HEADER_PATTERN =
        Regex(
            "bookmall/tab|video_model|video_detail|media/play/strategy|sstimor/tab|" +
                "device_register|inner_feed_detail",
        )

    /**
     * 播放链路的请求体也想看一眼（参数全在 body 里）。
     *
     * `inner_feed_detail` 是**对照组**：它的数据确实渲染到了短剧详情页，
     * 拿它和播放接口比"响应体在这一层是不是同样读不到"，才能判断
     * `markable=false` 是普遍现象还是播放接口独有的。
     */
    private val PLAYER_TRACE_PATTERN =
        Regex("video_model|video_detail|media/play/strategy|inner_feed_detail")

    /**
     * 播放链路的接口（原来拿了 200 却 `content-length=0`）。
     *
     * 只认 `novel/player/` 前缀：早期宽匹配会把 `lf3-reading.fqnovelpic.com`
     * 的静态图和 `commerce/playlet_same_product` 一起补参数，纯属噪音。
     */
    private val PLAYER_POST_PATTERN =
        Regex("novel/player/(video_model|video_detail|multi_video_model|multi_video_detail)")

    /**
     * 摘 `Content-Encoding` 的 A/B 开关。
     *
     * 2026-11-11 06:04 实测：摘掉后 `content-length` 仍是 **0**（`POSTFIX 摘掉
     * Content-Encoding（header 7->6）` + 紧随的 `RESPHDR … content-length=0`），
     * 所以"声明了 gzip 却发明文"不是根因，默认关。
     */
    private const val ENABLE_PLAYER_POST_FIX = false

    /**
     * `video_id` 替换实验：验"vid 无效 ⇒ 空 200"这条假设。
     *
     * 2026-11-11 06:22 实测：换成 `inner_feed_detail` 自己返回的 vid 后
     * `BODY-TEE … reads=1 0B` 依旧，假设否掉，默认关。
     */
    private const val ENABLE_PLAYER_BODY_PATCH = false

    /**
     * 给播放接口 URL 补公共参数（`aid` / `app_name` / `version_code` …）。
     *
     * 能拿到数据的 `bookmall/tab` 靠的就是这组参数；`novel/player` 的 POST
     * 连一个 query 都没有。补上去看服务端是否认这个"应用身份"。
     *
     * 2026-11-11 06:24 实测：补完 `BODY-TEE … reads=1 0B` 依旧（`multi_video_model`
     * 的 preload / player 也一样），假设否掉，默认关。
     */
    private const val ENABLE_PLAYER_URL_PARAMS = false

    /**
     * 用 AppLog 生成的全套公共参数（含 `device_id` / `iid` / `_rticket`）补播放接口 URL。
     *
     * **这是「播放页网络错误」的最终解**。前面的对照组实验已经把别的原因全排掉：
     * 发送头（含 `X-SS-STUB`、`Content-Encoding: gzip`、`User-Agent`）与能拿到
     * 10749B 的 `inner_feed_detail` 完全一致，`X-SS-STUB` 就是压缩后 body 的 MD5，
     * 换 vid、补 6 个静态应用参数、摘 `Content-Encoding` 全都不起作用。真正缺的是
     * **URL 上的设备身份**：`novel/player` 下的 POST 是唯一连 query 都没有的请求，
     * `tt_agw` 认不出设备就直接回 `200 + content-length: 0`。补上
     * `device_id` / `iid` / `openudid` / `_rticket` / `*_session_id` 这一整套后，
     * `video_model/v1` 立刻返回 20KB 业务体（`video_list[*].main_url` /
     * `backup_url` 都在里面），真人剧第 19 集正常起播。
     *
     * 和 [ENABLE_PLAYER_URL_PARAMS] 的区别：那个只补 6 个静态应用参数（对播放无效）。
     */
    private const val ENABLE_PLAYER_APPLOG_PARAMS = true

    /**
     * 全局补公共参数（默认开）。
     *
     * 只对 `*.snssdk.com` 且 URL 上还没有 `aid` 的请求生效——第三方域名和静态资源
     * CDN（`fqnovelpic` / `douyinpic` / `bytegecko`）一律不动，避免给图片地址拼参数。
     * 治本仍然是补跑 guest 的 `MainApplication` 初始化链（`AppLog` 的 URL 注入依赖
     * 它），那一步风险高，先用这一层把可用性兜住。
     */
    private const val ENABLE_GLOBAL_APPLOG_PARAMS = true

    private val API_HOST_PATTERN = Regex("^https://[a-z0-9.\\-]*\\.snssdk\\.com/")

    /** 详情接口 `inner_feed_detail` 自己返回的 req_ids 之一。 */
    private const val PLAYER_PATCH_VID = "7704857878514895896"

    /**
     * 摘掉指定请求头后重建 request。
     *
     * `Request.Builder.headers(List)` 是**整体替换**语义，所以传入过滤后的列表即可；
     * 重建失败就原样返回，绝不把请求搞坏。
     */
    /**
     * 播放接口 POST 体的 `video_id` 替换实验。
     *
     * 三个空 200 的接口全是 `novel/player` 下的 POST，而它们的发送头（含签名
     * `X-SS-STUB`、`Content-Encoding: gzip`、`User-Agent`）与能拿到 10749B 的
     * `inner_feed_detail` **完全一致**，所以先排掉最便宜的假设："请求里的 vid
     * 是旧的/无效的，服务端查不到就回空 200"。用详情接口自己返回的 vid 替换试试。
     *
     * 第一条请求保持原样做基线，之后每条都替换——一次冷启动就能对照。
     * 重建失败一律返回原请求。
     */
    private val playerPatchCount = java.util.concurrent.atomic.AtomicInteger(0)

    private fun patchPlayerVideoId(request: Any, videoId: String): Any = runCatching {
        val body = request.javaClass.getMethod("getBody").invoke(request)
            ?: return@runCatching request
        val buffer = java.io.ByteArrayOutputStream()
        body.javaClass.getMethod("writeTo", java.io.OutputStream::class.java).invoke(body, buffer)
        // 到这一步 body 已经是**压缩后**的字节（233B 与 Cronet 的
        // `fixedContentLength` 一致），改 JSON 前必须先解压、改完再压回去。
        val raw = buffer.toByteArray()
        val gzipped = raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()
        val text = if (gzipped) {
            String(
                java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(raw)).use { it.readBytes() },
                Charsets.UTF_8,
            )
        } else {
            String(raw, Charsets.UTF_8)
        }
        val patched = text.replace(
            Regex("\"video_id\"\\s*:\\s*\"\\d+\""),
            "\"video_id\":\"$videoId\"",
        )
        if (patched == text) {
            trace("BODYPATCH 未匹配 video_id（body ${raw.size}B gzip=$gzipped 开头=${text.take(60)}）")
            return@runCatching request
        }
        val payload = if (gzipped) {
            java.io.ByteArrayOutputStream().also { out ->
                java.util.zip.GZIPOutputStream(out).use { it.write(patched.toByteArray(Charsets.UTF_8)) }
            }.toByteArray()
        } else {
            patched.toByteArray(Charsets.UTF_8)
        }
        val loader = request.javaClass.classLoader
        val typedClass = Class.forName("com.bytedance.retrofit2.mime.TypedByteArray", false, loader)
        val typed = typedClass
            .getConstructor(String::class.java, ByteArray::class.java, Array<String>::class.java)
            .newInstance(
                "application/json; charset=utf-8",
                payload,
                if (gzipped) arrayOf("gzip") else null,
            )
        val outputClass = Class.forName("com.bytedance.retrofit2.mime.TypedOutput", false, loader)
        val builder = request.javaClass.getMethod("newBuilder").invoke(request)
        builder.javaClass.getMethod("method", String::class.java, outputClass).invoke(builder, "POST", typed)
        val built = builder.javaClass.getMethod("build").invoke(builder)
        trace("BODYPATCH video_id -> $videoId")
        built
    }.onFailure {
        trace("BODYPATCH 失败 ${it.javaClass.simpleName}: ${it.message}")
    }.getOrDefault(request)

    /**
     * 把签名头 `X-SS-STUB` 和 body 的 MD5 摆在一起。
     *
     * 这是"成功 POST"和"空 200 POST"之间唯一还没验过的差异：两次请求的
     * header 集合、`Content-Encoding`、`User-Agent` 完全一致，`X-SS-STUB`
     * 却在服务端可能被判无效——被判无效时字节网关的回法正是**空 200**。
     * 把 stub 与「压缩后字节的 MD5」「解压后明文的 MD5」并排打出来，
     * 就能看出签名到底签的是哪一份，以及两个接口是否一致。
     */
    private fun describeStub(request: Any): String {
        val stub = runCatching {
            val list = request.javaClass.getMethod("getHeaders").invoke(request) as List<*>
            list.filterNotNull().firstNotNullOfOrNull { header ->
                val name = header.javaClass.getMethod("getName").invoke(header) as? String
                if (!"X-SS-STUB".equals(name, true)) {
                    null
                } else {
                    header.javaClass.getMethod("getValue").invoke(header) as? String
                }
            }
        }.getOrNull()
        val body = runCatching { request.javaClass.getMethod("getBody").invoke(request) }.getOrNull()
            ?: return "stub=$stub body=null"
        val raw = runCatching {
            val out = java.io.ByteArrayOutputStream()
            body.javaClass.getMethod("writeTo", java.io.OutputStream::class.java).invoke(body, out)
            out.toByteArray()
        }.getOrNull() ?: return "stub=$stub body 不可读"
        val gzip = raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()
        val plain = if (gzip) {
            runCatching {
                java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(raw))
                    .use { it.readBytes() }
            }.getOrNull()
        } else {
            raw
        }
        fun md5(bytes: ByteArray?): String = if (bytes == null) {
            "null"
        } else {
            java.security.MessageDigest.getInstance("MD5").digest(bytes)
                .joinToString("") { String.format("%02x", it) }
        }
        return "stub=$stub gzip=$gzip raw=${raw.size}B rawmd5=${md5(raw)} " +
            "plain=${plain?.size ?: -1}B plainmd5=${md5(plain)}"
    }

    private fun stripHeader(request: Any, name: String): Any = runCatching {
        val list = request.javaClass.getMethod("getHeaders").invoke(request) as? List<*>
            ?: return@runCatching request
        val kept = list.filterNotNull().filter { header ->
            val headerName = runCatching {
                header.javaClass.getMethod("getName").invoke(header) as? String
            }.getOrNull()
            !name.equals(headerName, true)
        }
        if (kept.size == list.size) return@runCatching request
        val builder = request.javaClass.getMethod("newBuilder").invoke(request)
        builder.javaClass.getMethod("headers", List::class.java).invoke(builder, kept)
        val built = builder.javaClass.getMethod("build").invoke(builder)
        trace("POSTFIX 摘掉 $name（header ${list.size}->${kept.size}）")
        built
    }.getOrDefault(request)

    private val httpInfoDumped = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * 摊开 `vl0.a$a` 里那个 `HttpRequestInfo`（字段 c）。
     *
     * interceptor 看到的 URL/header 只是**发送前**的形态：公共参数、签名头
     * （X-Gorgon / X-Khronos / X-Argus）都是 TNet 在更下层追加的，所以
     * "服务端为什么回空 200"必须看真正发出去的那一份。`HttpRequestInfo` 是
     * TNet 自己记录本次请求的对象，含最终 URL / header / 收发字节数。
     * 每个接口只摊一次，避免刷屏。
     */
    private fun dumpHttpInfo(body: Any, url: String) {
        if (!httpInfoDumped.add(url.substringBefore('?'))) return
        val budget = intArrayOf(80)
        for (field in body.javaClass.declaredFields) {
            val value = runCatching {
                field.isAccessible = true
                field.get(body)
            }.getOrNull() ?: continue
            if (value is java.io.InputStream) continue
            if (value.javaClass.name.startsWith("com.bytedance.retrofit2")) continue
            trace("HTTPINFO $url field=${field.name} type=${value.javaClass.name}")
            dumpFieldsDeep(value, "  ", 2, budget)
        }
    }

    /**
     * 逐层摊字段。
     *
     * `CronetHttpURLConnection` 上挂着**真正发出去的 URL、请求头、响应头、
     * 收发字节数**——interceptor 那层的 `Request` 只是发送前的草稿。
     * 只对 `com.ttnet.org.chromium.net.*` / `URLConnection` 这类连接对象再展开
     * 一层，其余只打 `toString`，并用 budget 兜住输出量。
     */
    private fun dumpFieldsDeep(target: Any, indent: String, depth: Int, budget: IntArray) {
        if (depth <= 0) return
        var cls: Class<*>? = target.javaClass
        while (cls != null && cls != Any::class.java) {
            for (field in runCatching { cls.declaredFields }.getOrDefault(emptyArray())) {
                // `HttpURLConnection` 里一大片 `HTTP_OK=200` 这种静态常量，跳过。
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                if (budget[0]-- <= 0) return
                val value = runCatching {
                    field.isAccessible = true
                    field.get(target)
                }.getOrNull() ?: continue
                val text = when (value) {
                    is ByteArray -> "${value.size}B"
                    is Map<*, *> -> value.entries.joinToString(";") { "${it.key}=${it.value}" }
                    is List<*> -> value.joinToString(";") { it.toString() }
                    else -> value.toString()
                }
                // 请求头/响应头（List / Map）要全量，截断会正好截掉区分点。
                val limit = if (value is List<*> || value is Map<*, *>) 1500 else 240
                trace("HTTPINFO $indent${field.name}(${field.type.simpleName})=${text.take(limit)}")
                if (value is java.net.URLConnection ||
                    value.javaClass.name.startsWith("com.ttnet.org.chromium.net")
                ) {
                    dumpFieldsDeep(value, "$indent  ", depth - 1, budget)
                }
            }
            cls = cls.superclass
        }
    }

    /**
     * 打印 request body 的可读文本。
     *
     * 只调 `TypedOutput.writeTo()` 把请求体写进内存：它是请求侧的对象，
     * 写多少遍都不影响后续发送，和"读响应流会掏空下游"完全不同。
     * gzip 的请求体先解压再截断，避免日志里全是乱码。
     */
    private fun describeRequestBody(request: Any): String = runCatching {
        val body = request.javaClass.getMethod("getBody").invoke(request)
            ?: return "requestBody=null"
        val writeTo = body.javaClass.methods.firstOrNull {
            it.name == "writeTo" && it.parameterCount == 1
        } ?: return "requestBody=${body.javaClass.name} 无 writeTo"
        val sink = java.io.ByteArrayOutputStream()
        writeTo.invoke(body, sink)
        var bytes = sink.toByteArray()
        if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            bytes = runCatching {
                java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(bytes))
                    .use { it.readBytes() }
            }.getOrDefault(bytes)
        }
        "requestBody(${body.javaClass.simpleName} ${bytes.size}B)=" +
            String(bytes, Charsets.UTF_8).take(1500)
    }.getOrElse { "requestBody=读取失败 ${it.javaClass.simpleName}: ${it.message}" }

    /** 请求体的原始字节（`TypedOutput.writeTo` 是幂等写，不影响发送）。 */
    private fun requestBodyBytes(request: Any): ByteArray? = runCatching {
        val body = request.javaClass.getMethod("getBody").invoke(request) ?: return null
        val writeTo = body.javaClass.methods.firstOrNull {
            it.name == "writeTo" && it.parameterCount == 1
        } ?: return null
        val sink = java.io.ByteArrayOutputStream()
        writeTo.invoke(body, sink)
        sink.toByteArray()
    }.getOrNull()

    private val replayPlayerDone = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 用同一条请求的 URL / method / body / header 另开连接重放一次。
     *
     * app 自己那条响应体在 interceptor 阶段已经 EOF，拿不到业务 JSON；
     * 重放走的是全新的 HttpURLConnection，跟 app 的链路完全隔离，
     * 只为了看清"服务端到底回了什么、是不是空"。
     */
    private fun replayPlayerRequest(request: Any, url: String) {
        if (!replayPlayerDone.compareAndSet(false, true)) return
        val method = runCatching {
            request.javaClass.getMethod("getMethod").invoke(request) as? String
        }.getOrNull() ?: "POST"
        val body = requestBodyBytes(request)
        val headers = runCatching {
            val list = request.javaClass.getMethod("getHeaders").invoke(request) as? List<*> ?: emptyList<Any>()
            list.mapNotNull { header ->
                val item = header ?: return@mapNotNull null
                val name = runCatching {
                    item.javaClass.getMethod("getName").invoke(item) as? String
                }.getOrNull() ?: return@mapNotNull null
                val value = runCatching {
                    item.javaClass.getMethod("getValue").invoke(item) as? String
                }.getOrNull() ?: return@mapNotNull null
                name to value
            }
        }.getOrDefault(emptyList())

        Thread({
            runCatching {
                val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.requestMethod = method
                connection.instanceFollowRedirects = true
                headers.forEach { (name, value) ->
                    if (value.isNotBlank() && !name.equals("Content-Length", true)) {
                        runCatching { connection.setRequestProperty(name, value) }
                    }
                }
                // 要原始字节，自己解压，别让服务端按 gzip 回。
                connection.setRequestProperty("Accept-Encoding", "identity")
                if (body != null && method != "GET") {
                    connection.doOutput = true
                    connection.outputStream.use { it.write(body) }
                }
                val code = connection.responseCode
                val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.use { it.readBytes() }
                    ?.let { String(it, Charsets.UTF_8) }
                    .orEmpty()
                trace("REPLAY-RAW code=$code ${text.length}B $url -> ${text.take(1500)}")
            }.onFailure {
                trace("REPLAY-RAW 失败：${it.javaClass.simpleName}: ${it.message}")
            }
        }, "guest-replay").start()
    }

    /**
     * 只读打印 request 的 header（公共参数）。
     *
     * 字节的 `device_id` / `iid` / `aid` / `X-Argus` 等不是拼在 URL 上的，
     * 而是 TTNet 的公共参数拦截器塞进 header/公共参数表。服务端回
     * `BookApiERR: PARAM_INVALID` 时，先看这些字段有没有缺比猜业务参数有用。
     * 这里只做反射读，不碰 request/response，绝不影响请求链。
     */
    private fun describeHeaders(request: Any): String {
        val candidates = mutableListOf<String>()
        for (method in request.javaClass.methods) {
            if (method.parameterCount != 0 || !method.name.contains("header", ignoreCase = true)) continue
            val value = runCatching { method.invoke(request) }.getOrNull() ?: continue
            candidates += when (value) {
                is Map<*, *> -> "${method.name}=" + value.entries.joinToString(";") {
                    "${it.key}=${it.value.toString().take(80)}"
                }
                is List<*> -> "${method.name}=" + value.joinToString(";") { it.toString().take(80) }
                is Array<*> -> "${method.name}=" + value.joinToString(";") { it.toString().take(80) }
                else -> "${method.name}=${value.toString().take(200)}"
            }
        }
        var cls: Class<*>? = request.javaClass
        while (cls != null) {
            for (field in runCatching { cls.declaredFields }.getOrDefault(emptyArray())) {
                if (!field.name.contains("header", ignoreCase = true)) continue
                val value = runCatching {
                    field.isAccessible = true
                    field.get(request)
                }.getOrNull() ?: continue
                candidates += "field:${field.name}=" + when (value) {
                    is Map<*, *> -> value.entries.joinToString(";") { "${it.key}=${it.value.toString().take(80)}" }
                    is List<*> -> value.joinToString(";") { it.toString().take(80) }
                    else -> value.toString().take(200)
                }
            }
            cls = cls.superclass
        }
        return candidates.distinct().joinToString(" | ").take(1500).ifBlank { "无 header 入口" }
    }

    /**
     * 非消费式读响应体前几 KB（走 okhttp 的 `peekBody`）。
     *
     * 播放页 200 了却 still 显示"网络错误"，只剩一种可能：响应体里没有可用播放
     * 地址。把 `video_model/v1` 的返回前 700 字符打出来就能定论。
     */
    /**
     * 读出响应体并**原样塞回去**。
     *
     * 播放页请求全 200 却 still 报"网络错误"，只剩"响应体里没有播放地址"这一种
     * 可能。字节这版 `Response.body()` 是匿名包装类（既没 `peekBody` 也没
     * `source()`），只能 `string()` 读完再用 `ResponseBody.create` 重建一份塞回
     * response，否则下游 converter 会读到已经耗尽的流。
     *
     * 重建不出来就**不读**（宁可没有日志，也不能把请求搞坏）。
     */
    /**
     * 探一下响应体能不能"偷看"。
     *
     * 对照组（`inner_feed_detail`，它的数据确实渲染到了详情页）与播放接口表现
     * 完全一致：都是 `vl0.a$a.a = ql0.b`、`markable=false`、字段声明类型 `ql0.b`、
     * 唯一构造器 `(InputStream, SsCall)`。所以 `markable=false` 是这一层**所有**
     * 响应体的形态（`ql0.b` 只是 cronet 流的壳，不支持 mark），不是播放接口独有
     * 的毛病——不用再往播放接口的特例上找。既然不能 mark/reset，就改用 tee
     * （见 `installTee`）。
     */
    private val bodyOneShot = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 一次性读空响应取证的开关：默认关（会破坏当次响应体）。 */
    private const val ENABLE_BODY_ONESHOT = false

    /** tee 抄写开关：透传 app 的读，不改变数据，失败也不动原流。 */
    private const val ENABLE_BODY_TEE = true

    /** 单个响应最多抄这么多字节，防止大文件（视频分片）把内存和日志撑爆。 */
    private const val MAX_TEE_BYTES = 4 * 1024 * 1024

    private fun peekResponseBody(body: Any, url: String) {
        for (field in body.javaClass.declaredFields) {
            runCatching { field.isAccessible = true }
            val value = runCatching { field.get(body) }.getOrNull() ?: continue
            if (value !is java.io.InputStream) continue
            val markable = runCatching { value.markSupported() }.getOrDefault(false)
            // 光有 `markable=false` 不足以定方案：要"边读边抄"就得把外层字段换成自己
            // 实现的 InputStream，能不能换取决于字段的**声明类型**（声明成 `ql0.b`
            // 就塞不进普通流）和 `ql0.b` 的构造器签名。两个都记下来才能一次改对。
            val ctors = value.javaClass.declaredConstructors.joinToString(";") { c ->
                c.parameterTypes.joinToString(",") { it.simpleName }
            }
            trace(
                "BODY-PEEK $url field=${field.name} fieldType=${field.type.name} " +
                    "type=${value.javaClass.name} markable=$markable ctors=[$ctors]",
            )
            if (!markable) {
                // 常态分支：这条链路的响应体全是不可 mark 的 cronet 流。
                // 先挂 tee（不改变 app 读到的字节），拿到完整 body 再谈别的。
                if (ENABLE_BODY_TEE) installTee(body, field, value, url)
                // 一次性取证：整条链路里第一个播放响应会被读空（下游拿不到 body），
                // 之后的请求照常。只用来确认服务端到底回了什么。
                if (ENABLE_BODY_ONESHOT && bodyOneShot.compareAndSet(false, true)) {
                    val consumed = runCatching {
                        val available = runCatching { value.available() }.getOrDefault(-1)
                        val first = runCatching { value.read() }.getOrDefault(-2)
                        val rest = value.readBytes()
                        trace("BODY-ONESHOT 探针 available=$available firstRead=$first restLen=${rest.size}")
                        val bytes = if (first >= 0) byteArrayOf(first.toByte()) + rest else rest
                        var payload = bytes
                        if (payload.size > 2 && payload[0] == 0x1f.toByte() &&
                            payload[1] == 0x8b.toByte()
                        ) {
                            payload = runCatching {
                                java.util.zip.GZIPInputStream(
                                    java.io.ByteArrayInputStream(payload),
                                ).use { it.readBytes() }
                            }.getOrDefault(payload)
                        }
                        String(payload, Charsets.UTF_8)
                    }.getOrElse { "读取失败 ${it.javaClass.simpleName}: ${it.message}" }
                    trace("BODY-ONESHOT $url = ${consumed.take(1500)}")
                }
                continue
            }
            val bytes = runCatching {
                value.mark(8 * 1024 * 1024)
                val buffer = java.io.ByteArrayOutputStream()
                value.copyTo(buffer)
                value.reset()
                buffer.toByteArray()
            }.getOrElse {
                trace("BODY-PEEK 失败：${it.javaClass.simpleName} ${it.message}")
                return
            }
            var payload = bytes
            if (payload.size > 2 && payload[0] == 0x1f.toByte() && payload[1] == 0x8b.toByte()) {
                payload = runCatching {
                    java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(payload))
                        .use { it.readBytes() }
                }.getOrDefault(payload)
            }
            trace("BODY-PEEK $url ${payload.size}B=${String(payload, Charsets.UTF_8).take(1200)}")
            return
        }
    }

    /**
     * 用 tee 流把响应体"顺路抄下来"。
     *
     * 字段声明类型是 `ql0.b` 而不是 `InputStream`，所以要把我们自己的流包进一个
     * 新的 `ql0.b(InputStream, SsCall)` 再回填：`SsCall` 从原对象字段里捞，
     * 捞不到就传 null。tee 原样透传 app 要读的字节，只在旁边复制一份，读完
     * （或 app 关流）时打印——不 mark、不预读、不改变语义。
     *
     * 任何一步失败都保持原流不动：宁可没有日志，也不能把请求搞坏。
     */
    private fun installTee(
        wrapper: Any,
        field: java.lang.reflect.Field,
        source: java.io.InputStream,
        url: String,
    ) {
        val ctor = runCatching {
            source.javaClass.declaredConstructors.firstOrNull {
                it.parameterCount == 2 &&
                    it.parameterTypes[0].isAssignableFrom(java.io.InputStream::class.java)
            }
        }.getOrNull() ?: run {
            trace("BODY-TEE $url 没有 (InputStream,X) 构造器，无法包流")
            return
        }
        val sink = java.io.ByteArrayOutputStream()
        val tee = object : java.io.FilterInputStream(source) {
            private var finished = false
            // read 调用次数：0 次说明 app 压根没走我们这条流（body 被别处消费/丢弃），
            // 大于 0 而字节数是 0 才是"流到这儿已经空了"。两种结论天差地别。
            private var reads = 0

            private fun record(buffer: ByteArray, offset: Int, length: Int) {
                if (finished || length <= 0) return
                if (sink.size() + length > MAX_TEE_BYTES) {
                    finished = true
                    trace("BODY-TEE $url 超过 ${MAX_TEE_BYTES}B，停止抄写（app 侧不受影响）")
                    return
                }
                sink.write(buffer, offset, length)
            }

            private fun finish() {
                if (finished) return
                finished = true
                val bytes = sink.toByteArray()
                var payload = bytes
                if (payload.size > 2 && payload[0] == 0x1f.toByte() && payload[1] == 0x8b.toByte()) {
                    payload = runCatching {
                        java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(payload))
                            .use { it.readBytes() }
                    }.getOrDefault(payload)
                }
                trace("BODY-TEE $url reads=$reads ${bytes.size}B=" +
                    String(payload, Charsets.UTF_8).take(4000))
            }

            override fun read(): Int {
                reads++
                val one = super.read()
                if (one >= 0) record(byteArrayOf(one.toByte()), 0, 1) else finish()
                return one
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                reads++
                val n = super.read(b, off, len)
                if (n > 0) record(b, off, n) else if (n < 0) finish()
                return n
            }

            override fun close() {
                // app 只读一部分就关流时，也要把已经抄到的部分落进日志。
                finish()
                super.close()
            }
        }

        val callType = ctor.parameterTypes[1]
        var call: Any? = null
        var cls: Class<*>? = source.javaClass
        while (cls != null && call == null) {
            for (f in runCatching { cls.declaredFields }.getOrDefault(emptyArray())) {
                val value = runCatching {
                    f.isAccessible = true
                    f.get(source)
                }.getOrNull() ?: continue
                if (callType.isInstance(value)) {
                    call = value
                    break
                }
            }
            cls = cls.superclass
        }

        val replacement = runCatching {
            ctor.isAccessible = true
            ctor.newInstance(tee, call)
        }.getOrNull() ?: run {
            trace("BODY-TEE $url 包流失败（" +
                ctor.parameterTypes.joinToString(",") { it.simpleName } + "），保持原流")
            return
        }
        runCatching { field.set(wrapper, replacement) }.onFailure {
            trace("BODY-TEE $url 回填失败 ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun dumpAndRestoreBody(response: Any, url: String): Any {
        val body = runCatching { response.javaClass.getMethod("body").invoke(response) }.getOrNull()
            ?: run {
                // 打不出任何 BODY 行时必须区分两种原因：根本没有 `body()` 方法，
                // 还是 `body()` 返回 null（无响应体/已被消费）。否则对照组一组
                // 空日志会被误读成"响应体读不到"。
                trace("BODY-NULL $url response=${response.javaClass.name}")
                return response
            }
        // 响应头只在"要抓 body"的接口上打一次：`Content-Length` / `Content-Encoding`
        // 能直接回答"服务端到底回了多少字节、是不是加密/压缩过的"，
        // 这是区分"服务端真回了空，还是我们这边没读到"的唯一硬证据。
        // 对照组一起摊：只打播放接口就没法回答"能拿到数据的请求到底多带了什么头"。
        if (TRACE_BODY_PATTERN.containsMatchIn(url)) dumpHttpInfo(body, url)
        runCatching {
            val list = response.javaClass.getMethod("headers").invoke(response) as? List<*> ?: return@runCatching
            val text = list.joinToString(";") { item ->
                val name = runCatching {
                    item!!.javaClass.getMethod("getName").invoke(item) as? String
                }.getOrNull().orEmpty()
                val value = runCatching {
                    item!!.javaClass.getMethod("getValue").invoke(item) as? String
                }.getOrNull().orEmpty()
                "$name=$value"
            }
            trace("RESPHDR $url -> ${text.take(600)}")
        }
        // 这个 body 是 TTNet 自己的匿名子类（`network.http.impl.a$a`），
        // `create` 在它的**父类** `ResponseBody` 上，所以要用 methods 而不是
        // declaredMethods。
        val bodyFactory = body.javaClass.methods.firstOrNull { method ->
            java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                method.name == "create" &&
                method.parameterCount == 2 &&
                method.parameterTypes.any { it == ByteArray::class.java }
        } ?: run {
            // 这条链路在 converter **之后**：body() 已经是反序列化好的 RPC 模型
            // （`BookstoreTabResponse` / `a$a`），没有 create 可重建。模型本身
            // 就是判定依据（code / message / data 是否为空），直接摊平打出来。
            val raw = peekTypedInput(body)
            if (raw != null) trace("BODY-RAW $url -> ${raw.take(900)}")
            val described = describe(body)
            trace("BODY $url -> $described")
            traceIllegalAccess(url, described)
            if (PLAYER_TRACE_PATTERN.containsMatchIn(url)) peekResponseBody(body, url)
            // 7.4.6.32 的 body() 是 `vl0.a$a`（Retrofit Response 包装）：字段 a
            // 才是反序列化后的业务模型（BookstoreTabResponse 一类），只摊平一层
            // 只会看到 `<ql0.b>`。挑第一个非框架类字段再摊平一层才能看到
            // code / message。全程反射读字段，不碰响应式流。
            var nested: Any? = null
            for (field in body.javaClass.declaredFields) {
                val item = runCatching {
                    field.isAccessible = true
                    field.get(body)
                }.getOrNull() ?: continue
                val name = item.javaClass.name
                if (item !== body && item !is java.io.InputStream && item !is ByteArray &&
                    !name.startsWith("android.") && !name.startsWith("java.") &&
                    !name.startsWith("com.bytedance.frameworks.") &&
                    !name.startsWith("com.bytedance.ttnet.") &&
                    !name.startsWith("com.bytedance.retrofit2.")
                ) {
                    nested = item
                    break
                }
            }
            if (nested != null) {
                val nestedText = describe(nested)
                trace("BODY-NESTED $url -> $nestedText")
                traceIllegalAccess(url, nestedText)
            }
            return response
        }
        val mediaType = runCatching {
            body.javaClass.getMethod("contentType").invoke(body)
        }.getOrNull()
        val text = runCatching {
            body.javaClass.getMethod("string").invoke(body) as? String
        }.getOrNull()
        if (text == null) return response
        trace("BODY $url -> ${text.take(700)}")
        val rebuilt = runCatching {
            bodyFactory.isAccessible = true
            val bytes = text.toByteArray(Charsets.UTF_8)
            val args = if (bodyFactory.parameterTypes[0] == ByteArray::class.java) {
                arrayOf(bytes, mediaType)
            } else {
                arrayOf(mediaType, bytes)
            }
            bodyFactory.invoke(null, *args)
        }.getOrNull() ?: run {
            trace("BODY 重建失败，返回原 response（body 已被读取）")
            return response
        }
        val builder = runCatching { response.javaClass.getMethod("newBuilder").invoke(response) }
            .getOrNull() ?: return response
        val bodySetter = builder.javaClass.methods.firstOrNull {
            it.name == "body" && it.parameterCount == 1
        } ?: return response
        return runCatching {
            bodySetter.invoke(builder, rebuilt)
            builder.javaClass.getMethod("build").invoke(builder)
        }.getOrDefault(response)
    }

    /**
     * 关掉 guest 弹出的 Dialog。
     *
     * 7.2.4.32 会员版带一个"立即更新到 7.4.6.32"的强制弹窗：
     * `setCancelable(false)`、点遮罩/返回键都不关，只有一个按钮会跳浏览器，
     * 整个首页被它盖住，自动化也点不到下面的内容。UI 层没路可走，只能从
     * `WindowManagerGlobal.mViews` 里把 Dialog 的 DecorView 找出来：
     * `DecorView.mWindow` → `Window.getCallback()` 就是 `Dialog` 自己，
     * 直接 `dismiss()`。
     */
    internal fun dismissDialogs(): String {
        val global = runCatching {
            Class.forName("android.view.WindowManagerGlobal")
                .getDeclaredMethod("getInstance").apply { isAccessible = true }
                .invoke(null)
        }.getOrElse { return "ERR WindowManagerGlobal 拿不到：${it.javaClass.simpleName}" }
        val roots = runCatching {
            val field = global.javaClass.getDeclaredField("mViews").apply { isAccessible = true }
            when (val value = field.get(global)) {
                is Array<*> -> value.toList()
                is List<*> -> value
                else -> emptyList()
            }
        }.getOrElse { return "ERR mViews 读不到：${it.javaClass.simpleName}" }
        var closed = 0
        for (root in roots) {
            if (root == null) continue
            val callback = runCatching {
                var clazz: Class<*>? = root.javaClass
                var window: Any? = null
                while (clazz != null && window == null) {
                    val field = clazz.declaredFields.firstOrNull {
                        android.view.Window::class.java.isAssignableFrom(it.type)
                    }
                    if (field != null) {
                        field.isAccessible = true
                        window = field.get(root)
                    }
                    clazz = clazz.superclass
                }
                window?.javaClass?.getMethod("getCallback")?.invoke(window)
            }.getOrNull()
            if (callback is android.app.Dialog && callback.isShowing) {
                runCatching { callback.dismiss() }.onSuccess { closed++ }
            }
        }
        trace("dismiss dialogs -> $closed / ${roots.size} 个根 View")
        return "OK 已关闭 $closed 个 Dialog（共 ${roots.size} 个根 View）"
    }

    /**
     * 非消费式读 `TypedInput.in()` 的内容。
     *
     * Cronet 那一路的 `body()` 是 `yk0.a$a`（实现 `TypedInput`，只有
     * `in()/length()/mimeType()`），没有 `ResponseBody.create` 可重建。7.2.3.65
     * 里 `in()` 每次都返回同一个 `tk0.b`，且 `reset()` 会抛 IOException，所以
     * **只有在两次 `in()` 给出不同流对象、或者流真的支持 mark/reset 时才读**。
     * 读唯一一份流会把下游 converter 的 body 变成空，播放链当场断掉，宁可没有日志。
     */
    private fun peekTypedInput(body: Any): String? {
        val method = runCatching { body.javaClass.getMethod("in") }.getOrNull() ?: return null
        val first = runCatching { method.invoke(body) }.getOrNull() as? java.io.InputStream
        if (first == null) {
            trace("BODY-RAW 取流失败：${body.javaClass.name}.in() 不可用")
            return null
        }
        val second = runCatching { method.invoke(body) }.getOrNull()
        return runCatching {
            if (first !== second) {
                String(first.readBytes(), Charsets.UTF_8)
            } else if (first.markSupported()) {
                first.mark(Int.MAX_VALUE)
                val bytes = first.readBytes()
                first.reset()
                String(bytes, Charsets.UTF_8)
            } else {
                // 同一个流又不能 reset（实测 `tk0.b` 的 reset() 抛 IOException）：
                // **绝不读**。`e(MediaType, byte[])` 那条线就是因为把唯一一份
                // 响应流读掉了，下游 converter 拿到空 body，播放链直接断掉。
                // 宁可没有日志，也不能把请求搞坏。
                trace("BODY-RAW 跳过：${first.javaClass.name} 是同一份流且无法复位")
                null
            }
        }.onFailure { trace("BODY-RAW 读取失败：${it.javaClass.simpleName}: ${it.message}") }.getOrNull()
    }

    /**
     * 专门标出 `ILLEGAL_ACCESS`（服务端业务码 110）。
     *
     * 2026-11-11 17:26~17:36 同一实例实测：
     * - `cleardata` 之后的**第一个进程**里，`bookmall/tab` 回
     *   `{"code":"110","log_id":…,"message":"ILLEGAL_ACCESS"}`（HTTP 200），首页/剧场
     *   只剩「网络出错，请点击重试」；进程内点重试（17:28:53）仍是 110。
     * - `am force-stop` 后重新冷启动（第二个进程）同一个接口回
     *   `BookApiERR str=[SUCCESS] {value=0}`，推荐流出现真实短剧并可播放，
     *   剧场「找剧」页出完整榜单，全程无 110。
     * - 请求侧可以排除：两次 URL 的 query key 集合都是 97 个、`AppLog.hasStarted=true`、
     *   `aid=8662` / `iid` / `device_id` 齐全 ⇒ 服务端对"刚注册的设备"的一次性拒绝，
     *   不是容器把请求改坏了。
     *
     * 判定只认**值**，不认枚举常量名：`BookApiERR` 是枚举，摊平时静态常量里就有
     * `ILLEGAL_ACCESS` 这个名字，早先用 `contains("ILLEGAL_ACCESS")` 会把 `SUCCESS`
     * 的响应也误报成 110（2026-11-11 17:33 那轮就出现了 12 条假阳性），
     * 所以改成匹配 `str=[ILLEGAL_ACCESS]` / `message=ILLEGAL_ACCESS` / `"code":"110"`。
     */
    private fun traceIllegalAccess(url: String, text: String) {
        if (!ILLEGAL_ACCESS_HIT.containsMatchIn(text)) return
        trace(
            "接口被判 ILLEGAL_ACCESS(110)：$url —— 首个冷启动进程里服务端拒绝" +
                "（设备注册/签名未就绪）：点重试、重开界面都无效，force-stop 后重新冷启动即可",
        )
    }

    private val ILLEGAL_ACCESS_HIT = Regex(
        """str=\[ILLEGAL_ACCESS]|message=ILLEGAL_ACCESS|"code":"?110"?[,}]""",
    )

    /** 把已反序列化的模型摊平打印一层字段；只用于排查"200 但页面报网络错误"。 */
    private fun describe(value: Any?): String {
        value ?: return "null"
        val clazz = value.javaClass
        val fields = runCatching { clazz.declaredFields }.getOrNull() ?: return clazz.name
        val parts = mutableListOf<String>()
        for (field in fields.take(40)) {
            // 静态字段是**类级噪音**，不是这次响应的值：`BookApiERR` 这类枚举会把所有
            // 错误常量（`ILLEGAL_ACCESS` / `PARAM_INVALID` …）、`$VALUES`、
            // kotlinx 的 `Companion` 全当静态字段摊出来，既淹掉真正的 `value=0`，
            // 又会让"响应里出现了 ILLEGAL_ACCESS"这种假阳性判定成立。
            if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
            runCatching {
                field.isAccessible = true
                val item = field.get(value) ?: return@runCatching
                parts += when (item) {
                    is String, is Number, is Boolean -> "${field.name}=${item.toString().take(200)}"
                    is ByteArray -> "${field.name}=bytes(${item.size}):${String(item, Charsets.UTF_8).take(300)}"
                    else -> "${field.name}=<${item.javaClass.name}>"
                }
            }
        }
        // 有些 TTNet 包装类的 toString() 直接带响应 JSON，比翻字段快。
        val text = runCatching { value.toString().take(500) }.getOrDefault("")
        return "${clazz.name} str=[$text] {${parts.joinToString(",")}}"
    }

    private fun peekBodyText(response: Any): String? {
        // 路线 1：okhttp 的 peekBody（有的版本 raw() 就是 okhttp3.Response）
        val raw = runCatching { response.javaClass.getMethod("raw").invoke(response) }.getOrNull()
        if (raw != null) {
            val peek = runCatching {
                raw.javaClass.getMethod("peekBody", java.lang.Long.TYPE).invoke(raw, 3000L)
            }.getOrNull()
            if (peek != null) {
                runCatching { peek.javaClass.getMethod("string").invoke(peek) as? String }
                    .getOrNull()?.let { return it }
            }
        }
        // 路线 2：body().source() 的 Buffer 克隆 —— copy 出来读，不消费原流
        val body = runCatching { response.javaClass.getMethod("body").invoke(response) }.getOrNull()
            ?: return "无 body()（raw=${raw?.javaClass?.simpleName}）"
        val source = runCatching { body.javaClass.getMethod("source").invoke(body) }.getOrNull()
            ?: return "body=${body.javaClass.simpleName} 无 source()"
        runCatching { source.javaClass.getMethod("request", java.lang.Long.TYPE).invoke(source, 4096L) }
        val buffer = findFieldValue(source, "buffer") ?: return "source 无 buffer"
        val clone = runCatching { buffer.javaClass.getMethod("clone").invoke(buffer) }.getOrNull()
            ?: return "buffer 无 clone"
        return runCatching { clone.javaClass.getMethod("readUtf8").invoke(clone) as? String }.getOrNull()
    }

    private fun findFieldValue(target: Any, name: String): Any? {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            val field = runCatching { cls.getDeclaredField(name) }.getOrNull()
            if (field != null) {
                return runCatching {
                    field.isAccessible = true
                    field.get(target)
                }.getOrNull()
            }
            cls = cls.superclass
        }
        return null
    }

    /**
     * 从字节的 retrofit Request 里抠 URL。
     *
     * 这一版 `Request` 没有 `url()`，`toString()` 也没重写（只输出类名@hash），
     * 所以按"零参 url* 方法 → String 字段 → HttpUrl 字段"三级兜底找。
     */
    private fun extractRequestUrl(request: Any): String {
        for (method in request.javaClass.methods) {
            if (method.parameterCount != 0) continue
            if (method.returnType != String::class.java) continue
            if (!method.name.contains("url", ignoreCase = true)) continue
            val value = runCatching { method.invoke(request) as? String }.getOrNull()
            if (!value.isNullOrBlank()) return value
        }
        var cls: Class<*>? = request.javaClass
        while (cls != null) {
            for (field in runCatching { cls.declaredFields }.getOrDefault(emptyArray())) {
                runCatching {
                    field.isAccessible = true
                    val value = field.get(request) ?: return@runCatching
                    if (field.type == String::class.java) {
                        val text = value as String
                        if (text.contains("http")) return text
                    } else if (field.type.name.contains("HttpUrl")) {
                        val text = value.toString()
                        if (text.isNotBlank()) return text
                    }
                }
            }
            cls = cls.superclass
        }
        return "?"
    }

    /**
     * Rx 调度器探针（复盘 4.4 的判定手段）。
     *
     * 播放页"拿到 seriesId 之后一个请求都不发、View 树里连 SurfaceView 都没有"
     * 的典型成因：`Schedulers.io()` / `computation()` / `single()` 投进去的任务
     * 永不执行。四个调度器各投一个空任务，1.2 秒内没被调用就是 STUCK。
     */
    private fun probeRxSchedulers(guest: Guest): String {
        val schedulers = runCatching {
            Class.forName("io.reactivex.schedulers.Schedulers", false, guest.classLoader)
        }.getOrNull() ?: return "Schedulers 缺失"
        val reports = mutableListOf<String>()
        for (name in RX_SCHEDULER_NAMES) {
            val scheduler = runCatching { schedulers.getMethod(name).invoke(null) }.getOrNull()
            if (scheduler == null) {
                reports += "$name=?"
                continue
            }
            val latch = java.util.concurrent.CountDownLatch(1)
            val started = System.nanoTime()
            val ran = runCatching {
                val worker = scheduler.javaClass.getMethod("createWorker").invoke(scheduler)
                val task = Runnable { latch.countDown() }
                worker.javaClass.getMethod("schedule", Runnable::class.java).invoke(worker, task)
                latch.await(1200, java.util.concurrent.TimeUnit.MILLISECONDS)
            }.getOrDefault(false)
            val cost = (System.nanoTime() - started) / 1_000_000
            reports += "$name=${if (ran) "${cost}ms" else "STUCK"}"
        }
        return reports.joinToString(" ")
    }

    private val RX_SCHEDULER_NAMES = listOf("io", "computation", "single", "newThread")

    /**
     * bytex 线程收敛开关（0.9.21 复盘里的"总根因"）。
     *
     * 字节的 bytex 把每个线程池的 `ThreadFactory` 换成 `ThreadFactoryProxy`，
     * `newThread` 时把线程包成 `ThreadProxy`。`SuperThreadPool.isEnable(type)` 为
     * 真时，包过的线程在沙箱里**只挂着、不执行任务**：任务收下了、线程起了、
     * 不报错、`completedTaskCount` 恒为 0。所有异步初始化（RPC/账号/请求回调/
     * 图片管线/RxJava）全部静默停摆，UI 上统一渲染成「网络错误」。
     *
     * 三个开关（数值来自原项目实测）：
     * - `setEnableType(0)`：让 `isEnable(8)/isEnable(1)` 恒 false，不再包线程；
     * - `setAllowAllCoreThreadTimeOut(true)`：核心线程走 `poll(keepAliveTime)`，
     *   绕开被 bytex 换成 `return poll()` 的坏 `take()`；
     * - `setEnableBlockFetchTask(true)`：否则 `poll(timeout)` 同样退化成无参 poll，
     *   核心线程纯用户态空转（78 线程 783% CPU 那次）。
     *
     * **顺序硬约束**：必须在任何线程池创建之前调用，池一旦建出来就救不回来。
     */
    private fun fixBytexThreadPool(guest: Guest): String {
        // 必须 initialize=true：`INSTANCE` 是 Kotlin object 在 <clinit> 里赋值的，
        // 不初始化就是 null，后面什么也设不上。
        val clazz = runCatching { Class.forName(BYTEX_POOL_CLASS, true, guest.classLoader) }.getOrNull()
            ?: return "缺失"
        // Kotlin object：INSTANCE 字段名会被混淆，按"类型等于自身"的静态字段兜底找。
        val instance = runCatching {
            clazz.declaredFields.firstOrNull { field ->
                java.lang.reflect.Modifier.isStatic(field.modifiers) && clazz.isAssignableFrom(field.type)
            }?.apply { isAccessible = true }?.get(null)
        }.getOrNull() ?: return "无实例字段"

        val applied = mutableListOf<String>()
        fun setter(name: String, value: Any, type: Class<*>) {
            val method = runCatching { clazz.getDeclaredMethod(name, type) }.getOrNull() ?: return
            runCatching {
                method.isAccessible = true
                method.invoke(instance, value)
            }.onSuccess { applied += "$name=${value}" }
        }
        setter("setEnableType", 0, Integer.TYPE)
        setter("setAllowAllCoreThreadTimeOut", true, java.lang.Boolean.TYPE)
        setter("setEnableBlockFetchTask", true, java.lang.Boolean.TYPE)
        if (applied.isEmpty()) {
            return "无匹配 setter：" + clazz.declaredMethods.joinToString(",") { "${it.name}/${it.parameterCount}" }.take(220)
        }
        return applied.joinToString(",")
    }

    private const val BYTEX_POOL_CLASS =
        "com.ss.android.ugc.bytex.pthread.base.convergence.SuperThreadPool"

    /**
     * 线程池状态探针：`active/poolSize/queue/completed`。
     *
     * 只看 `completedTaskCount` 就能区分两种故障：任务投不进去（queue 一直在涨）
     * 和"只收不做"（active 有值、completed 恒 0）。这是复盘里判定 bytex 的依据。
     */
    private fun probeExecutors(guest: Guest): String {
        val clazz = runCatching {
            Class.forName(TT_EXECUTORS_CLASS, false, guest.classLoader)
        }.getOrNull() ?: return "缺失"
        val parts = mutableListOf<String>()
        for (getter in EXECUTOR_GETTERS) {
            val method = runCatching { clazz.getDeclaredMethod(getter) }.getOrNull() ?: continue
            val pool = runCatching {
                method.isAccessible = true
                method.invoke(null)
            }.getOrNull() ?: continue
            val label = getter.removePrefix("get").removeSuffix("Executor").lowercase()
            parts += "$label=${poolState(pool)}"
        }
        return if (parts.isEmpty()) "无 getter" else parts.joinToString(" ")
    }

    private fun poolState(pool: Any): String {
        val active = readNumberField(pool, "activeCount")
        val size = readNumberField(pool, "poolSize")
        val completed = readNumberField(pool, "completedTaskCount")
        val queued = runCatching {
            val queue = pool.javaClass.getMethod("getQueue").invoke(pool) as? java.util.Collection<*>
            if (queue == null) "-" else queue.count().toString()
        }.getOrDefault("-")
        return "$active/$size/q$queued/c$completed"
    }

    private fun readNumberField(target: Any, name: String): String {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            val field = runCatching { cls.getDeclaredField(name) }.getOrNull()
            if (field != null) {
                return runCatching {
                    field.isAccessible = true
                    field.get(target)?.toString() ?: "null"
                }.getOrDefault("err")
            }
            cls = cls.superclass
        }
        return "-"
    }

    private const val TT_EXECUTORS_CLASS = "com.bytedance.common.utility.concurrent.TTExecutors"

    private val EXECUTOR_GETTERS = listOf(
        "getNormalExecutor",
        "getIOExecutor",
        "getCPUExecutor",
        "getBackgroundExecutor",
        "getDefaultExecutor",
    )

    /**
     * 明文 HTTP 策略探针。
     *
     * 短剧播放地址是 http CDN；明文策略是进程级的（看宿主 manifest，guest 自己
     * 的 `usesCleartextTraffic` 不算数）。策略为 false 时取地址的请求会被网络库
     * 直接拒掉，播放页只显示"网络错误，请稍后重试"。
     */
    private fun traceCleartextPolicy(guest: Guest) {
        val permitted = runCatching {
            android.security.NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted
        }.getOrNull()
        val guestDeclared =
            (guest.applicationInfo.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC) != 0
        trace("cleartext 进程级=$permitted guestManifest=$guestDeclared")
    }

    /**
     * 把 TTVideoEngine（红果播放器）自己的日志打开。
     *
     * 播放页失败时界面上只有一句"网络错误，请稍后重试"：videoshop 会把播放器的
     * 各种错误码统一映射成这条文案，logcat 里看不到原因。`TTVideoEngineLog` 是
     * 纯 Java 的静态开关，打开后 `E/TTVideoEngineLog` 才会打出真正的失败点。
     */
    private fun enablePlayerLogging(guest: Guest) {
        // 实测这个包里日志类在 utils 下（tag 才叫 TTVideoEngineLog）。
        val candidates = listOf(
            "com.ss.ttvideoengine.utils.TTVideoEngineLog",
            "com.ss.ttvideoengine.TTVideoEngineLog",
        )
        val clazz = candidates.firstNotNullOfOrNull { name ->
            runCatching { Class.forName(name, false, guest.classLoader) }.getOrNull()
        }
        if (clazz == null) {
            trace("player log 类缺失")
            return
        }
        val intType = Int::class.javaPrimitiveType ?: return
        val statics = clazz.declaredMethods.filter { java.lang.reflect.Modifier.isStatic(it.modifiers) }
        trace(
            "player log ${clazz.name} methods=" +
                statics.joinToString(",") { "${it.name}/${it.parameterCount}" },
        )
        val readTurnOn = runCatching {
            clazz.getDeclaredMethod("getLogTurnOn").apply { isAccessible = true }
        }.getOrNull()
        trace("player log turnOn 当前值=${runCatching { readTurnOn?.invoke(null) }.getOrNull()}")
        // 实测这个版本只有两参 turnOn；参数类型随 SDK 版本换过（int,int 或 int,boolean），
        // 这里按实际签名枚举几组常量，再用 getLogTurnOn() 判断哪组真的打开了。
        val turnOn = statics.firstOrNull { it.name == "turnOn" && it.parameterCount == 2 }
        if (turnOn == null) {
            trace("player log 没有两参 turnOn")
            return
        }
        turnOn.isAccessible = true
        val valueSets: List<List<Any>> = turnOn.parameterTypes.map { type ->
            when {
                type == intType -> listOf(1, 0, 2)
                type == java.lang.Boolean.TYPE -> listOf(true, false)
                else -> emptyList()
            }
        }
        if (valueSets.any { it.isEmpty() }) {
            trace("player log turnOn 参数类型不支持：${turnOn.parameterTypes.joinToString { it.name }}")
            return
        }
        var combos = listOf(emptyList<Any>())
        for (values in valueSets) {
            combos = combos.flatMap { prefix -> values.map { prefix + it } }
        }
        for (args in combos) {
            runCatching { turnOn.invoke(null, *args.toTypedArray()) }
            val now = runCatching { readTurnOn?.invoke(null) }.getOrNull()
            if (now != null && "$now" != "0") {
                trace("player log turnOn$args -> $now")
                return
            }
        }
        trace("player log turnOn 全部无效")
    }

    /**
     * 打开字节系 SDK 的通用日志（TTNet / AppLog / 播放链路）。
     *
     * `Logger.setLogLevel(0)` 是这些 SDK 的总闸：0=VERBOSE，之后 TTNet 才会把
     * 请求 URL、响应码和错误原因打到 logcat。播放页只有一句"网络错误"，
     * 不看这层日志无法区分「服务端返回业务错误」和「拉流失败」。
     */
    private fun enableNetworkLogging(guest: Guest) {
        val clazz = runCatching {
            Class.forName("com.bytedance.common.utility.Logger", false, guest.classLoader)
        }.getOrNull()
        if (clazz == null) {
            trace("net log 类缺失")
            return
        }
        val before = runCatching {
            clazz.getDeclaredMethod("getLogLevel").apply { isAccessible = true }.invoke(null)
        }.getOrNull()
        val applied = runCatching {
            clazz.getDeclaredMethod("setLogLevel", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(null, 0)
            true
        }.getOrDefault(false)
        val after = runCatching {
            clazz.getDeclaredMethod("getLogLevel").apply { isAccessible = true }.invoke(null)
        }.getOrNull()
        trace("net log level $before -> $after (applied=$applied)")
    }

    /**
     * 补启动 AppLog（设备注册 / 公共参数 aid 的来源）。
     *
     * guest 的 Application 是 Tinker 的 `MuteApplicationStub`，真实
     * `MainApplication` 的初始化链没跑，于是 `AppLog.hasStarted=false`、
     * `getAid()` 为空、`getRequestHeader()` 为 null，整轮 trace 里
     * **`device_register` 一次都没有**。`reading/bookapi` 已经靠手工补参数绕过，
     * 但播放接口（`video_model`）拿到的响应体是空的，最大嫌疑就是设备没注册、
     * 请求签名用的 aid 为空。
     *
     * `AppLog.init(Context, InitConfig)` 是公开 API，参数用 guest 自己
     * `service/settings` 请求里那套实读值（aid=8662 / channel=update_64）。
     */
    private fun ensureAppLogStarted(guest: Guest) {
        runCatching {
            val appLogClass = Class.forName("com.bytedance.applog.AppLog", false, guest.classLoader)
            val started = appLogClass.getMethod("hasStarted").invoke(null) as? Boolean ?: false
            if (started) {
                trace("applog 已启动，跳过")
                return
            }
            val configClass = Class.forName(
                "com.bytedance.applog.InitConfig", false, guest.classLoader,
            )
            val config = configClass
                .getConstructor(String::class.java, String::class.java)
                .newInstance("8662", "update_64")
            fun setter(name: String, value: Any) {
                runCatching {
                    configClass.getMethod(name, value.javaClass).invoke(config, value)
                }.onFailure { trace("applog $name 设置失败：${it.javaClass.simpleName}") }
            }
            setter("setChannel", "update_64")
            setter("setAppName", "novelread")
            runCatching {
                configClass.getMethod("setAutoStart", java.lang.Boolean.TYPE)
                    .invoke(config, true)
            }
            val application = guest.application ?: return
            appLogClass.getMethod("init", android.content.Context::class.java, configClass)
                .invoke(null, application, config)
            trace("applog init 已调用")
            // init 之后注册是异步的，过一会儿回读一次状态。
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching {
                    trace(
                        "applog after=${appLogClass.getMethod("hasStarted").invoke(null)}" +
                            " aid=${appLogClass.getMethod("getAid").invoke(null)}" +
                            " iid=${appLogClass.getMethod("getIid").invoke(null)}",
                    )
                }
            }, 6_000)
        }.onFailure {
            trace("applog init 失败：${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /**
     * 插件管理器兜底初始化。
     *
     * 7.2 的插件线程一开跑就撞 `MiraPluginServiceManagerImpl.isMiraPlugin` 的
     * `List.size()` NPE：它读的是 `xt0.a.p()`，该列表在插件管理器没初始化时是
     * null。NPE 被吞掉后插件检查链断在这里，首页的 Feed 接口一直转圈。
     *
     * 这里先看 `p()` 是否已有值；为空就把管理器拉起来再复查一次，两次结果都写进
     * trace，方便判断到底是"没初始化"还是"初始化了但列表为空"。
     */
    private fun ensureMiraPlugins(guest: Guest, application: Application) {
        val registry = runCatching { Class.forName("xt0.a", true, guest.classLoader) }
            .getOrNull() ?: return
        val readPlugins = runCatching {
            registry.getDeclaredMethod("p").apply { isAccessible = true }
        }.getOrNull()
        fun snapshot(label: String) {
            readPlugins ?: return
            val plugins = runCatching { readPlugins.invoke(null) as? List<*> }.getOrNull()
            trace("mira plugins($label)=${plugins?.size ?: "null"}")
        }
        snapshot("before")
        if (runCatching { readPlugins?.invoke(null) }.getOrNull() == null) {
            runCatching {
                registry.getDeclaredMethod("k", Application::class.java)
                    .apply { isAccessible = true }
                    .invoke(null, application)
                trace("mira plugin manager init ok")
            }.onFailure {
                val cause = (it as? java.lang.reflect.InvocationTargetException)?.targetException ?: it
                trace("mira plugin manager init 失败：${cause.javaClass.simpleName}: ${cause.message}")
            }
            snapshot("after")
        }
    }

    /** Providers already brought up, so a second launch does not repeat them. */
    private val startedProviders = Collections.synchronizedSet(mutableSetOf<String>())

    /** 手动 attach 过的 guest provider 及其元数据。 */
    private data class GuestProvider(val provider: ContentProvider, val info: ProviderInfo)

    /**
     * authority → 手动实例化的 guest provider。
     *
     * 系统的 PMS/AMS 不知道这些组件存在，`ContentResolver` 一访问就抛
     * `Failed to find provider info for com.phoenix.read.pm.PPMP`（红果短剧页
     * 因此显示"网络错误"）。AMS 代理从这里直接给出本进程的 provider。
     */
    private val guestProviders = ConcurrentHashMap<String, GuestProvider>()

    /**
     * 补跑 guest 自己声明的 ContentProvider。
     *
     * 系统只初始化**宿主**清单里的 provider（`ActivityThread.installContentProviders`
     * 只看当前 LoadedApk），guest 的几十个 provider 在虚拟环境里一次都没执行过。
     * 红果的账号/网络/推送 SDK 恰好都挂在这些 provider 上（`TokenObjectProvider`、
     * `BDInstallProvider`、`MiraPluginPackageManagerProvider`、`ReparoProvider`…），
     * 于是 `Application.onCreate` 跑完了、`TTAccount config` 仍然是 not init，
     * 首页每个接口都报"网络出错"。
     *
     * 这里按 `initOrder` 手动走一遍 `attachInfo`（它会调用 `onCreate`）。只处理
     * 属于当前进程的 provider；每个都独立 catch，某个 SDK 初始化失败不能挡住
     * 启动链。
     */
    private fun initGuestProviders(guest: Guest) {
        val providers = guest.packageInfo.providers ?: return
        val context = guest.context ?: return
        providers
            .asSequence()
            .filter { info ->
                info.name.isNullOrBlank().not() &&
                    (info.processName.isNullOrEmpty() || info.processName == guest.packageName)
            }
            .sortedBy { it.initOrder }
            .forEach { info ->
                val name = info.name ?: return@forEach
                if (!startedProviders.add("${guest.instanceId}:$name")) return@forEach
                runCatching {
                    val clazz = Class.forName(name, true, guest.classLoader)
                    val provider = clazz.getDeclaredConstructor().newInstance() as ContentProvider
                    val providerInfo = ProviderInfo(info)
                    provider.attachInfo(context, providerInfo)
                    // 系统不认识这些 authority，guest 通过 ContentResolver 访问
                    // 时会走 AMS 并拿到 "Failed to find provider info"（红果的
                    // 短剧页就是这么失败的）。登记下来，由 AMS 代理把请求接住。
                    providerAuthorities(providerInfo).forEach { authority ->
                        guestProviders[authority] = GuestProvider(provider, providerInfo)
                        registerProviderInActivityThread(authority, provider, providerInfo)
                    }
                    trace("provider ok $name")
                }.onFailure {
                    val cause = (it as? java.lang.reflect.InvocationTargetException)?.targetException ?: it
                    trace("provider 失败 $name: ${cause.javaClass.simpleName}: ${cause.message}")
                }
            }
    }

    /**
     * 把 provider 塞进 ActivityThread 的本地 provider 缓存。
     *
     * `ContentResolver.acquireProvider` 第一步就是 `ActivityThread.acquireExistingProvider`，
     * 查的就是这张表；命中后不会再问 AMS（Android 16 的获取路径已经不走
     * `IActivityManager.getContentProvider`，代理拦不到）。
     *
     * 记录类型是 ActivityThread 的私有内部类，构造签名随版本变过，所以两种都试。
     */
    private fun registerProviderInActivityThread(
        authority: String,
        local: ContentProvider,
        info: ProviderInfo,
    ) {
        runCatching {
            val threadClass = Class.forName("android.app.ActivityThread")
            val thread = threadClass
                .getDeclaredMethod("currentActivityThread")
                .apply { isAccessible = true }
                .invoke(null) ?: return
            // 字段名在不同版本间没变过，但取值可能是 null（第一次 acquire 前
            // 系统还没建表），所以按名字找并且允许自己建一张。
            val mapField = threadClass.declaredFields
                .firstOrNull { it.name.contains("ProviderMap", ignoreCase = true) }
                ?: error("找不到 provider 缓存字段")
            mapField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            var map = mapField.get(thread) as? MutableMap<Any, Any>
            if (map == null) {
                map = android.util.ArrayMap<Any, Any>()
                mapField.set(thread, map)
            }

            val keyClass = Class.forName("android.app.ActivityThread\$ProviderKey")
            val key = keyClass
                .getDeclaredConstructor(String::class.java, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .newInstance(authority, 0)

            val transport = ContentProvider::class.java
                .getDeclaredField("mTransport")
                .apply { isAccessible = true }
                .get(local)
            val iface = Class.forName("android.content.IContentProvider")
            val recordClass = Class.forName("android.app.ActivityThread\$ProviderClientRecord")
            val names = arrayOf(authority)
            val holderClass = Class.forName("android.app.ContentProviderHolder")
            val holder = guestProviderHolder(GuestProvider(local, info))
            // 构造签名随版本变过，硬编码 4 参数会全军覆没：
            //   Android 14：ProviderClientRecord(ActivityThread, String[],
            //       IContentProvider, ContentProvider, ContentProviderHolder)
            //   部分版本：少第一个 ActivityThread，或最后一参是 ProviderInfo /
            //       ProviderInfo[]。
            // 7.4.6.32 在 vivo Android 14 上实测 36 个 provider 全部
            // "构造失败"，导致 ContentResolver 找不到 com.phoenix.read.pm.PPMP，
            // 短剧/播放页统一渲染成「网络错误」。改成按真实参数类型填值。
            val argsByType: Map<Class<*>, Any?> = mapOf(
                threadClass to thread,
                Array<String>::class.java to names,
                iface to transport,
                ContentProvider::class.java to local,
                holderClass to holder,
                Array<ProviderInfo>::class.java to arrayOf(info),
                ProviderInfo::class.java to info,
            )
            var record: Any? = null
            for (ctor in recordClass.declaredConstructors.sortedByDescending { it.parameterCount }) {
                val types = ctor.parameterTypes
                if (types.any { !argsByType.containsKey(it) }) continue
                val args = types.map { argsByType[it] }.toTypedArray()
                record = runCatching {
                    ctor.apply { isAccessible = true }.newInstance(*args)
                }.getOrNull()
                if (record != null) {
                    trace("provider map ctor(${types.joinToString(",") { it.simpleName }})")
                    break
                }
            }
            if (record == null) error("ProviderClientRecord 构造失败")
            map[key] = record
            trace("provider map <- $authority")
        }.onFailure {
            val ctors = runCatching {
                Class.forName("android.app.ActivityThread\$ProviderClientRecord")
                    .declaredConstructors
                    .joinToString("; ") { ctor ->
                        ctor.parameterTypes.joinToString(",", "(", ")") { type -> type.simpleName }
                    }
            }.getOrDefault("-")
            trace("provider map 失败 $authority: ${it.javaClass.simpleName}: ${it.message}")
            trace("ProviderClientRecord ctors=$ctors")
        }
    }

    /** `ProviderInfo.authorities` 不在 SDK stub 里，只能反射读。 */
    private fun providerAuthorities(info: ProviderInfo): List<String> = runCatching {
        val field = runCatching { ProviderInfo::class.java.getField("authorities") }
            .getOrElse {
                ProviderInfo::class.java.declaredFields
                    .firstOrNull { it.name.contains("authorit", ignoreCase = true) }
                    ?: error("ProviderInfo 上没有 authorities 字段")
            }
        field.isAccessible = true
        val value = field.get(info) as? String
        value?.split(';')?.map(String::trim)?.filter { it.isNotEmpty() }.orEmpty()
    }.onFailure {
        trace("authorities 读取失败：${it.javaClass.simpleName}: ${it.message}")
    }.getOrDefault(emptyList())

    internal fun guestFor(instanceId: String?): Guest? = instanceId?.let { guests[it] }

    fun forget(instanceId: String) {
        guests.remove(instanceId)
    }

    internal fun remember(activity: Activity, guest: Guest) {
        activityGuests[activity] = guest
    }

    internal fun guestOf(activity: Activity?): Guest? =
        activity?.let { activityGuests[it] }

    /** Guest owning [context], by identity of its ClassLoader. */
    internal fun guestOfContext(context: Context?): Guest? =
        context?.let { ctx -> guests.values.firstOrNull { it.classLoader === ctx.classLoader } }

    /** The single running guest, when only one instance is alive in this process. */
    internal fun onlyGuest(): Guest? = guests.values.singleOrNull()

    /**
     * 在已加载的 guest ClassLoader 里找类，找不到返回 null。
     *
     * 给 [GuestAppComponentFactory] 兜底用：系统在 `handleReceiver` 里用**宿主**
     * 的 ClassLoader 实例化 guest 的接收器（例如 7.2.4.32 的 `to0.c`），拿不到
     * 类就抛 `ClassNotFoundException` 把整个进程带走。这里按实例顺序试 guest
     * 的 dex loader，命中就交给工厂去实例化。
     *
     * 用 `initialize = false`：只是要能解析到类，别在这里触发 `<clinit>`。
     */
    internal fun resolveGuestClass(className: String): Class<*>? {
        for (guest in guests.values) {
            runCatching { Class.forName(className, false, guest.classLoader) }
                .getOrNull()
                ?.let { return it }
        }
        return null
    }

    /**
     * Rebinds the Activity to the guest before its `onCreate` runs.
     *
     * - `mBase` → [GuestContext]: assets/resources/packageName/classLoader/appInfo
     *   now answer with guest values, while system services still reach the host.
     * - `mResources` → the guest's resource table, used by every View it inflates.
     * - `mApplicationInfo` → sourceDir/nativeLibraryDir point at the guest APK, which
     *   is what loaders (Flutter's ReLinker, for instance) use to find `lib*.so`.
     */
    internal fun attach(activity: Activity, guest: Guest) {
        trace("attach ${activity.javaClass.name}")
        // 四槽位的 recents 卡片原本都叫 `@string/app_name`（BlackBox ADV），
        // 同名同版本时根本分不出是哪张卡。proxy Intent 已带实例名，直接改任务描述。
        runCatching {
            val label = GuestLauncher.requestFromIntent(activity.intent)?.displayName.orEmpty()
            if (label.isNotBlank()) {
                activity.setTaskDescription(
                    ActivityManager.TaskDescription(
                        InstanceTag.recents(label, guest.instanceId),
                    ),
                )
                trace("recents 标题 -> ${InstanceTag.recents(label, guest.instanceId)}")
            }
        }.onFailure { trace("recents 标题设置失败：${it.javaClass.simpleName} ${it.message}") }
        lastGuestActivity = activity
        val context = ensureGuestContext(activity, guest)
        setField(ContextWrapper::class.java, activity, "mBase", context)
        // mResources lives on ContextThemeWrapper, not Activity: writing it on
        // Activity silently no-ops (NoSuchFieldException) and leaves
        // getResources() pointing at the host resource table — the reason guest
        // layouts, fonts and shaders were all resolving against the host APK.
        setField(ContextThemeWrapper::class.java, activity, "mResources", guest.resources)
        // The framework already built a theme against the host resource table
        // (ActivityThread applies the proxy's ActivityInfo theme before
        // onCreate). Drop it, or applying a guest theme id below lands in host
        // resources and throws Resources$NotFoundException.
        setField(ContextThemeWrapper::class.java, activity, "mTheme", null)
        // ActivityThread already applied the *proxy's* theme (host ActivityInfo)
        // before onCreate, so AppCompat guests die with "You need to use a
        // Theme.AppCompat theme". Re-apply the theme the guest manifest declares.
        guest.packageInfo.activities
            ?.firstOrNull { it.name == activity.javaClass.name }
            ?.theme
            ?.takeIf { it != 0 }
            ?.let { themeRes ->
                runCatching { activity.setTheme(themeRes) }
                    .onFailure { trace("setTheme 0x${themeRes.toString(16)} 失败：$it") }
            }
        // The Activity was launched through the proxy component, so the framework
        // handed it the rewritten intent. Guests read component/extras from
        // getIntent(), so give them back the one they actually sent — and when the
        // launch came straight from the host (nothing was packed into the extra),
        // synthesise the launcher intent the app would have received on its own.
        // Leaving the proxy component here is visible to guests: 红果's Epic shell
        // reads getIntent().getComponent() while deciding whether to keep running.
        runCatching {
            val current = activity.intent
            val restored = current?.getParcelableExtra<Intent>(GuestLauncher.EXTRA_GUEST_INTENT)
                ?: Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    component = ComponentName(guest.packageName, activity.javaClass.name)
                }
            activity.intent = restored
            trace("intent restored -> ${restored.component?.flattenToShortString()}")
        }.onFailure { trace("还原 Intent 失败：$it") }
        // ActivityInfo/component must describe the guest Activity, not the proxy:
        // privacy/process gates (红果's Timon records
        // "Enter ... com.adv.bb.app.guest.GuestActivityProxy" and then kills the
        // process) and anything that calls getComponentName()/getActivityInfo()
        // otherwise see a class that does not belong to the app.
        guest.packageInfo.activities
            ?.firstOrNull { it.name == activity.javaClass.name }
            ?.let { activityInfo ->
                setField(Activity::class.java, activity, "mActivityInfo", activityInfo)
                runCatching {
                    setField(
                        Activity::class.java,
                        activity,
                        "mComponent",
                        ComponentName(activityInfo.packageName, activityInfo.name),
                    )
                }
            }
        guest.application?.let { application ->
            setField(Activity::class.java, activity, "mApplication", application)
            // Keep the Application on the same base as the Activity so
            // getAssets()/getResources() answer with guest data everywhere.
            setField(ContextWrapper::class.java, application, "mBase", context)
        }
        // 启动页的窗口已经 add 过，`WindowManagerGlobal.sWindowSession` 此刻一定
        // 就绪：在这里接管，首页/Toast 再建窗口时包名才换得回来。
        installWindowSessionProxy()
        // Application.onCreate right before Activity.performCreate, as Android
        // would have done before the Activity was constructed.
        startApplication(guest)
        startMainThreadWatchdog()
        // Never stringify the Theme here: Resources$Theme.toString() resolves
        // every style id it inherits and throws NotFoundException when one is
        // missing from the table it is bound to.
        trace("attach done")
        launchMark("attach 完成 ${activity.javaClass.simpleName}")
        traceActivityIdentity(activity)
    }

    /**
     * 壳校验快照。
     *
     * 红果的 Epic 加固壳在 `SplashActivity.onResume` 之后由
     * `org.checkerframework.checker.signature.query.security.̐` 主动
     * `finishAffinity()`：不崩、不 kill，只是"判定不合格然后自己退"。
     * 它读的一定是下面这组值之一，所以先把它们摆出来，而不是继续猜。
     */
    /**
     * 红果 SafeModeController 探针。
     *
     * 字节系在 Application 侧有一套"连续启动即崩"的计数：达到阈值后启动页直接走
     * 安全模式分支，不做任何 UI 初始化 —— 表现就是 `SplashActivity.onResume` 之后
     * 立刻 finishAffinity / 自杀。判据是私有实现，只能反射读回来。
     * 类名/方法名按 7.2.3.65 反编译结果，其它包名一律跳过。
     */
    private fun probeSafeMode(guest: Guest) {
        if (!guest.packageName.startsWith("com.phoenix.read")) return
        runCatching {
            val clazz = guest.classLoader.loadClass("com.dragon.read.app.h1")
            val inst = clazz.getMethod("f").invoke(null)
            val needSafeMode = clazz.getMethod("n").invoke(inst)
            val count = runCatching {
                clazz.getDeclaredMethod("d").apply { isAccessible = true }.invoke(inst)
            }.getOrElse { "ERR(${it.javaClass.simpleName})" }
            trace("Safe needSafeMode=$needSafeMode comboCrashCount=$count")
        }.onFailure {
            trace("Safe probe skipped: ${it.javaClass.simpleName} ${it.message}")
        }
    }

    private fun traceActivityIdentity(activity: Activity) {
        runCatching {
            val info = activity.applicationInfo
            val intent = activity.intent
            trace(
                "activity identity pkg=${activity.packageName}" +
                    " component=${activity.componentName?.flattenToShortString()}" +
                    " base=${activity.baseContext?.javaClass?.name}" +
                    " app=${activity.application?.javaClass?.name}" +
                    " taskId=${activity.taskId} taskRoot=${activity.isTaskRoot}" +
                    " intent=${intent?.component?.flattenToShortString()}" +
                    " action=${intent?.action}" +
                    " sourceDir=${info?.sourceDir}" +
                    " dataDir=${info?.dataDir}" +
                    " nativeLib=${info?.nativeLibraryDir}" +
                    " infoUid=${info?.uid} myUid=${android.os.Process.myUid()}" +
                    " infoProcess=${info?.processName}",
            )
        }.onFailure { trace("activity identity 探针失败：$it") }
    }

    /**
     * Brings the guest Context and Application up before an Activity exists.
     *
     * Several SDK-backed apps read a static `Context` (assigned in
     * `Application.attachBaseContext`) while their Activity classes are still
     * being *constructed*. 红果 is the proof: its SplashActivity dies with
     * `ExceptionInInitializerError: App has not been initialized !` unless the
     * Application is already created. `newActivity` runs on the main thread,
     * which is everything this needs.
     */
    internal fun ensureInitialized(host: Context?, guest: Guest) {
        if (host == null || guest.context != null) return
        ensureGuestContext(host, guest)
    }

    /**
     * [ensureInitialized], pinned to the main thread.
     *
     * LoadedApk/ContextImpl and every guest Application init assume they run on
     * the thread that owns the ActivityThread, so the launch path hops onto it
     * (and waits, because `startActivity` must not race the Application).
     */
    internal fun ensureInitializedOnMain(host: Context, guest: Guest) {
        if (guest.context != null) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // 以前这里没有兜底：guest Application 在初始化链上抛异常
            // （`ExperimentManager has not been init` / `RpcService.init`）时，
            // 异常会一路冒到 launchFromGate 的 runCatching，startActivity 永远不执行，
            // gate 的「正在启动」遮罩就一直留在屏幕上。吞掉并留痕，让 proxy 至少
            // 能起来显示诊断原因。
            try {
                ensureGuestContext(host, guest)
                // The guest Application class alone may install a hook framework
                // (Mira does it from Application <clinit>), so take the
                // instrumentation back before the launch transaction starts.
                ensureHook()
            } catch (error: Throwable) {
                trace("guest 初始化失败（主线程）：${error.javaClass.simpleName} ${error.message}")
                android.util.Log.e("GuestRuntime", "guest 初始化失败", error)
            }
            return
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try {
                ensureGuestContext(host, guest)
                ensureHook()
            } catch (error: Throwable) {
                android.util.Log.e("GuestRuntime", "guest 初始化失败", error)
            } finally {
                latch.countDown()
            }
        }
        latch.await(15, java.util.concurrent.TimeUnit.SECONDS)
    }

    /** 一次性初始化链的结果。[error] 非 null 时让 proxy 显示诊断页，别把 gate 卡死。 */
    internal data class GuestReady(val guest: Guest?, val error: String?)

    private val readyLock = Any()

    @Volatile
    private var readyFor: String? = null

    /**
     * 初始化链的顺序是硬约束（参考 `D:\app\adv` 的 `BlackBoxBootstrap.ensure()` +
     * `AppInstrumentation.callApplicationOnCreate` 的严格顺序）：
     * 1. 换 `ActivityThread.mInstrumentation` —— 必须早于任何 guest 代码运行；
     * 2. [prepare] —— 建 ClassLoader / Resources，装 ATM、WindowSession、startActivity 重定向等 hook；
     * 3. [ensureInitializedOnMain] —— 把 guest 的 Application 拉起来，且必须在
     *    `startActivity` 之前同步完成（SplashActivity 在类初始化时就读静态 Context）。
     *
     * **幂等**：同一个 `(instanceId, apkPath)` 只跑一次。重复点"启动"、gate 重建、
     * 进程内重入都不会再走一遍 guest 的初始化链 —— 那条链上的
     * `ExperimentManager` / `RpcService.init` 重跑就会抛，是"清数据后首启必崩"的
     * 一大来源。`installHook()` 与 `prepare()` 自己已有 guard，这里补的是整条链的状态。
     *
     * 所有异常收敛成 [GuestReady.error]：过去异常被 `launchFromGate` 外层吞掉后
     * `startActivity` 根本不执行，用户只看到 gate 的「正在启动」转圈。
     */
    internal fun ensureReady(host: Context, request: LaunchRequest): GuestReady =
        synchronized(readyLock) {
            val key = "${request.instanceId}|${request.apkPath}"
            if (readyFor == key) {
                guestFor(request.instanceId)?.let { return@synchronized GuestReady(it, null) }
            }
            val hookError = installHook()
            if (hookError != null) {
                return@synchronized GuestReady(null, "hook 安装失败：$hookError")
            }
            launchMark("hook 完成")
            val guest = runCatching { prepare(host, request) }.getOrElse {
                return@synchronized GuestReady(
                    null,
                    "加载失败：${it.javaClass.simpleName}: ${it.message}",
                )
            }
            launchMark("prepare 完成（ClassLoader / Resources / 动态代理）")
            runCatching { ensureInitializedOnMain(host, guest) }.onFailure {
                trace("guest 初始化异常（继续启动）：${it.javaClass.simpleName} ${it.message}")
                android.util.Log.e("GuestRuntime", "guest 初始化异常", it)
            }
            launchMark("Application 构造完成")
            readyFor = key
            GuestReady(guest, null)
        }

    private val traceLock = Any()

    /** 启动分段点：没有会话（例如广播触发的后台路径）时静默跳过，不影响功能。 */
    internal fun launchMark(label: String) {
        LaunchClock.mark(label, System.currentTimeMillis())?.let { trace(it) }
    }

    /** 首页 resume：整轮启动的总耗时，只记一次。 */
    internal fun launchDone(label: String) {
        LaunchClock.finish(label, System.currentTimeMillis())?.let { trace(it) }
    }

    /**
     * File trace, for guests that hook `android.util.Log` natively.
     *
     * 红果 (ByteDance Mute/Mira/Lancet, bytehook + shadowhook) silences logcat
     * from its Application.onCreate onwards, so logcat cannot answer "did attach
     * run?". Writing to the host files dir survives that.
     */
    internal fun trace(message: String) {
        runCatching {
            val dir = hostContext()?.filesDir ?: return
            val slot = GuestSlots.currentSlot
            val label = slot?.let { "p$it" } ?: "host"
            val line = "${System.currentTimeMillis()} [$label] $message\n"
            // 每槽位一份日志；P0 与宿主仍共用旧文件，用进程锁避免跨进程交错。
            val file = File(dir, GuestSlots.traceFileName(slot))
            synchronized(traceLock) {
                java.io.FileOutputStream(file, true).use { output ->
                    var fileLock: java.nio.channels.FileLock? = null
                    try {
                        fileLock = runCatching { output.channel.lock() }.getOrNull()
                        output.write(line.toByteArray(Charsets.UTF_8))
                        output.fd.sync()
                    } finally {
                        fileLock?.let { runCatching { it.release() } }
                    }
                }
            }
        }
    }

    /** Host Application, for callers that run before any Activity exists. */
    internal fun hostContext(): Context? = runCatching {
        Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication")
            .apply { isAccessible = true }
            .invoke(null) as? Context
    }.getOrNull()

    /**
     * Makes the process look like the guest's own process.
     *
     * ByteDance apps ship **Timon**, a process gate that records which process
     * entered a component and kills anything that is not a process its manifest
     * declares. Running a guest inside the host process therefore reads as an
     * intruder (`process_record/com.adv.bb.app`, then `Process.killProcess`
     * ~70ms after `SplashActivity.onResume`, with no crash anywhere).
     * The name is patched in three places because each is read by different
     * code: `/proc/self/cmdline` (native), ActivityThread's bound data
     * (`Application.getProcessName()`), and `Process.myProcessName()`.
     */
    private fun spoofProcessIdentity(guest: Guest) {
        if (spoofedProcess == guest.packageName) return
        spoofedProcess = guest.packageName
        val name = guest.packageName

        // 1) bionic argv[0] → /proc/self/cmdline. Removed from the SDK on recent
        //    releases (`Process.setArgV0` no longer exists on API 36); when it is
        //    available it is the only way to fix what native checks read.
        runCatching {
            android.os.Process::class.java
                .getDeclaredMethod("setArgV0", String::class.java)
                .apply { isAccessible = true }
                .invoke(null, name)
            trace("setArgV0 ok, cmdline=${readCmdline()}")
        }.onFailure { trace("setArgV0 不可用：${it.javaClass.simpleName}") }

        // 2) `Process.myProcessName()` answers from a cached static, not from
        //    cmdline. BlackBox writes the same field natively
        //    (NativeCore.setStaticStringField(Process, "sArgV0", ...)).
        val processFields = runCatching {
            android.os.Process::class.java.declaredFields.toList()
        }.getOrDefault(emptyList())
        var cachePatched = false
        processFields.filter { it.type == String::class.java }.forEach { field ->
            val fieldName = field.name
            if (!fieldName.contains("argV0", true) && !fieldName.contains("processName", true)) {
                return@forEach
            }
            runCatching {
                field.isAccessible = true
                field.set(null, name)
                cachePatched = true
                trace("Process.$fieldName -> $name")
            }.onFailure { trace("Process.$fieldName 写入失败：${it.javaClass.simpleName}") }
        }
        if (!cachePatched) {
            val methodNames = runCatching {
                android.os.Process::class.java.declaredMethods
                    .filter {
                        it.name.contains("rgV0", true) ||
                            it.name.contains("ProcessName", true) ||
                            java.lang.reflect.Modifier.isNative(it.modifiers)
                    }
                    .joinToString(",") { "${it.name}(${it.parameterCount})" }
            }.getOrDefault("")
            trace("Process 无字符串缓存字段；methods=$methodNames")
        }

        // 3) ActivityThread's bound data — what Application.getProcessName() reads.
        runCatching {
            val threadClass = Class.forName("android.app.ActivityThread")
            val thread = threadClass.getDeclaredMethod("currentActivityThread")
                .apply { isAccessible = true }
                .invoke(null)
            val bindData = threadClass.getDeclaredField("mBoundApplication")
                .apply { isAccessible = true }
                .get(thread)
            bindData?.let {
                it.javaClass.getDeclaredField("processName")
                    .apply { isAccessible = true }
                    .set(it, name)
            }
        }.onFailure { trace("ActivityThread.processName 补丁失败：${it.javaClass.simpleName}") }

        // 4) DDM app name — the name debugger/AMS-side tooling reports.
        runCatching {
            Class.forName("android.ddm.DdmHandleAppName")
                .getDeclaredMethod("setAppName", String::class.java, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(null, name, 0)
            trace("DdmHandleAppName -> $name")
        }.onFailure { trace("DdmHandleAppName 失败：${it.javaClass.simpleName}") }

        // dataDir 必须留在真实可写目录（…/instances/<uuid>）。
        // 之前把它改写成 /data/user/0/<guest 包名> 只是"看起来更像自己"，
        // 代价是 guest 的 prefs / MMKV / databases 全部写在不存在且无权限的
        // 路径上：实例目录里的 files、shared_prefs 至今是空的，隐私弹窗的
        // 同意状态、首启标记都存不下来，每次都退化成全新安装。
        trace("dataDir=${guest.applicationInfo.dataDir}")

        // `setArgV0` 只改 argv，内核的 task name（/proc/self/comm，15 字节截断）
        // 还是宿主包名。加固壳把这两个值做一致性校验时，这里就是唯一的破绽。
        runCatching {
            val comm = File("/proc/self/comm").readText().trim()
            trace("proc comm=$comm target=$name same=${comm == name.take(15)}")
        }.onFailure { trace("proc comm 读取失败：${it.javaClass.simpleName}") }

        // 一次性探针：Epic 壳走 ActivityClient.finishActivityAffinity 退出，
        // 先把这个类持有的 singleton 字段名打出来，下一步才能决定拦哪一层。
        runCatching {
            Class.forName("android.app.ActivityClient").declaredFields.joinToString(",") {
                "${it.name}:${it.type.simpleName}"
            }
        }.onSuccess { trace("ActivityClient fields=$it") }
            .onFailure { trace("ActivityClient 探针失败：$it") }

        trace(
            "process identity -> $name" +
                " myProcessName=${runCatching { android.os.Process.myProcessName() }.getOrNull()}" +
                " getProcessName=${processNameOrNull()}" +
                " sArgV0=${runCatching {
                    android.os.Process::class.java.getDeclaredField("sArgV0")
                        .apply { isAccessible = true }
                        .get(null)
                }.getOrNull()}" +
                " cmdline=${readCmdline()}",
        )
    }

    /** Reads the kernel-visible process name (`/proc/self/cmdline`). */
    private fun readCmdline(): String = runCatching {
        java.io.FileInputStream(File("/proc/self/cmdline")).use { input ->
            val buffer = ByteArray(256)
            val read = input.read(buffer)
            if (read <= 0) {
                "<empty>"
            } else {
                var end = 0
                while (end < read && buffer[end] != 0.toByte()) end++
                String(buffer, 0, end, Charsets.UTF_8)
            }
        }
    }.getOrElse { "<err ${it.javaClass.simpleName}>" }

    /**
     * Dumps the main thread stack into the trace file while a guest is coming
     * up. Guests that kill their own process leave no crash, no tombstone and no
     * exception — this is the only way to see *who* called `exit`.
     */
    internal fun startMainThreadWatchdog() {
        if (watchdogStarted) return
        watchdogStarted = true
        Thread({
            val deadline = System.currentTimeMillis() + 4_000
            while (System.currentTimeMillis() < deadline) {
                val activity = lastGuestActivity
                val state = "finishing=${activity?.isFinishing}"
                val frames = Looper.getMainLooper().thread.stackTrace
                    .take(10)
                    .joinToString("\n") { "    at $it" }
                trace("$state main[$frames\n]")
                Thread.sleep(8)
            }
        }, "guest-watchdog").apply { isDaemon = true }.start()
    }

    @Volatile
    private var watchdogStarted = false

    @Volatile
    private var lastGuestActivity: Activity? = null

    /** 最近一次 resume 的 guest 页面；`skipSplash` 用它判断"首页是不是已经在前台"。 */
    @Volatile
    private var lastResumedGuest: Activity? = null

    /** 宿主 stop 与用户退出都会结束进程，两条路只允许走一次。 */
    @Volatile
    private var shuttingDown = false

    /** 当前存活的 guest Activity；全部销毁 = 用户退出了这个虚拟应用。 */
    private val liveGuestActivities: MutableSet<Activity> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    /** 等一轮 Activity 切换的交错（新的 create、旧的后 destroy）再判定退出。 */
    private const val EXIT_SETTLE_MS = 1_200L

    /** 摘任务卡是异步的，立刻杀进程会被 AMS 当成"还有任务"而重建。 */
    private const val EXIT_KILL_DELAY_MS = 600L

    private fun processNameOrNull(): String? = runCatching {
        Class.forName("android.app.Application").getMethod("getProcessName").invoke(null) as? String
    }.getOrNull()

    /**
     * Guest Context built on a real `ContextImpl`.
     *
     * A ContextWrapper that only overrides a handful of getters is not enough:
     * code paths that ask for `getPackageResourcePath()`, `getCodeCacheDir()` or
     * `getAssets()` through the framework see the host package. Building a
     * LoadedApk for the guest APK and letting `ContextImpl.createAppContext()`
     * wire it up gives the guest a context whose every path points at its own APK.
     */
    private fun ensureGuestContext(host: Context, guest: Guest): Context {
        guest.context?.let { return it }

        // 必须早于任何进程名反射：隐藏成员被过滤时，下面的 setArgV0 / sArgV0
        // 会像"根本不存在"一样静默失效，进程名只会改对 getProcessName 这一路。
        installCrashProbe()
        HiddenApiUnsealer.unseal(host)
        installActivityManagerProxy()
        installActivityClientProxy()
        spoofProcessIdentity(guest)
        val impl = guest.contextImpl ?: createContextImpl(host, guest)
        guest.contextImpl = impl
        // Some guests (红果 / Mira) rewrite the package name stored in
        // ContextImpl.mContentResolver by reflection right before the first
        // Activity create. That field is lazy and still null at this point, so
        // their set() lands on a null target and kills the process.
        runCatching { impl?.contentResolver }

        // Without a guest ContextImpl, wrap the host's own base context: an
        // Activity or Application must never become the base of another one.
        val base = impl ?: (host as? ContextWrapper)?.baseContext ?: host
        val context = GuestContext(base, guest)
        guest.context = context

        if (guest.application == null) {
            // createApplication also links the Application into the LoadedApk, so
            // framework paths that resolve it through packageInfo see it too.
            guest.application = createApplication(context, guest)
        }
        // Consistency probe: anti-tamper code compares what the sandbox reports
        // against what PackageManager says about the installed app. Any mismatch
        // (uid, code path, signature, class loader) is a reason to bail out.
        runCatching {
            val pm = context.packageManager
            val installed = runCatching {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(guest.packageName, android.content.pm.PackageManager.GET_SIGNATURES)
            }.getOrNull()
            trace(
                "probe myUid=${android.os.Process.myUid()} appInfoUid=${guest.applicationInfo.uid}" +
                    " installedUid=${installed?.applicationInfo?.uid}" +
                    " codePath=${context.packageCodePath}" +
                    " installedPath=${installed?.applicationInfo?.sourceDir}" +
                    " loader=${context.classLoader?.javaClass?.name}" +
                    " dataDir=${context.applicationInfo.dataDir}",
            )
        }.onFailure { trace("probe 失败：$it") }

        android.util.Log.i(
            "GuestRuntime",
            "guest context impl=" + (
                impl?.let { "${it.javaClass.simpleName}(op=${it.opPackageName})" }
                    ?: "unavailable (fallback to host base)"
                ) + " application=${guest.application?.javaClass?.name}",
        )
        return context
    }

    /**
     * LoadedApk + ContextImpl for the guest APK; null when the framework refuses.
     *
     * [host] supplies the package name that Binder calls must carry: the guest
     * package is not installed, so AppOps rejects it. Everything else — source
     * dir, native library dir, class loader, resources — comes from the guest.
     */
    private fun createContextImpl(host: Context, guest: Guest): Context? = runCatching {
        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = threadClass.getDeclaredMethod("currentActivityThread")
            .apply { isAccessible = true }
            .invoke(null) ?: error("ActivityThread 不可用")

        val compatClass = Class.forName("android.content.res.CompatibilityInfo")
        val compat = compatClass.getDeclaredField("DEFAULT_COMPATIBILITY_INFO")
            .apply { isAccessible = true }
            .get(null)

        // getPackageInfoNoCheck is an *instance* method. Reflecting it as a
        // static call invokes it on a null receiver and dies with
        // NullPointerException("null receiver") inside Method.invoke, before
        // any LoadedApk work happens — that is the real cause of the
        // "创建 guest ContextImpl 失败" we kept seeing.
        val noCheck = threadClass.declaredMethods.firstOrNull { method ->
            method.name == "getPackageInfoNoCheck" &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes[1] == compatClass
        } ?: error("getPackageInfoNoCheck 缺失")
        noCheck.isAccessible = true

        // Binder identity must stay the host's. AppOps — and every provider
        // behind it (Settings among them) — verifies that the calling package
        // belongs to the calling uid; the guest package is not installed on the
        // device, so any IPC carrying "com.resume.resume_builder" is rejected
        // with "SecurityException: Package ... does not belong to <uid>".
        // On API 36 ContextImpl has neither mOpPackageName nor
        // mAttributionSource to patch: both are derived from the ApplicationInfo
        // the LoadedApk was built from. So build the LoadedApk from a *copy* of
        // the guest info whose package name is the host's — sourceDir,
        // nativeLibraryDir and dataDir stay guest, only the identity moves.
        val identityInfo = ApplicationInfo(guest.applicationInfo).apply {
            packageName = host.packageName
        }

        // getPackageInfoNoCheck keys its cache by applicationInfo.packageName and
        // *returns the cached instance* when that name is already there. The host
        // package is always cached, so calling it with the host name would hand
        // back the host's own LoadedApk — and the two lines below would then
        // overwrite the host's class loader and resources with the guest's.
        // Every later host Activity would be instantiated from the guest APK and
        // die with ClassNotFoundException ("再启动就打不开"). Hide the host entry
        // while the guest LoadedApk is built, then put it straight back: the guest
        // owns a fresh object, the host keeps its own.
        val packagesField = threadClass.getDeclaredField("mPackages").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val packages = packagesField.get(thread) as MutableMap<String, Any>
        val resourcePackages = runCatching {
            threadClass.getDeclaredField("mResourcePackages").apply { isAccessible = true }
                .get(thread) as MutableMap<String, Any>
        }.getOrNull()

        val hostEntry = packages.remove(host.packageName)
        val hostResourceEntry = resourcePackages?.remove(host.packageName)
        val loadedApk = try {
            noCheck.invoke(thread, identityInfo, compat) ?: error("LoadedApk 为 null")
        } finally {
            // The guest LoadedApk must never stay reachable under the host name,
            // or the next host launch would load its classes from the guest APK.
            if (hostEntry != null) packages[host.packageName] = hostEntry
            else packages.remove(host.packageName)
            if (hostResourceEntry != null) resourcePackages?.set(host.packageName, hostResourceEntry)
        }

        val loadedApkClass = loadedApk.javaClass
        setField(loadedApkClass, loadedApk, "mClassLoader", guest.classLoader)
        setField(loadedApkClass, loadedApk, "mResources", guest.resources)
        guest.loadedApk = loadedApk

        val contextImplClass = Class.forName("android.app.ContextImpl")
        val createAppContext = contextImplClass
            .getDeclaredMethod("createAppContext", threadClass, loadedApkClass)
            .apply { isAccessible = true }
        createAppContext.invoke(null, thread, loadedApk) as? Context
            ?: error("ContextImpl 为 null")
    }.onFailure {
        android.util.Log.e("GuestRuntime", "创建 guest ContextImpl 失败", it)
    }.getOrNull()

    private fun setField(owner: Class<*>, target: Any, name: String, value: Any?) {
        runCatching {
            val field = owner.getDeclaredField(name).apply { isAccessible = true }
            field.set(target, value)
        }.onFailure {
            android.util.Log.w("GuestRuntime", "set $name 失败：${it.javaClass.simpleName}", it)
        }
    }

    private fun loadPackageInfo(
        context: Context,
        request: LaunchRequest,
        nativeLibDir: String,
    ): PackageInfo {
        val archive = runCatching {
            context.packageManager.getPackageArchiveInfo(
                request.apkPath,
                // GET_PROVIDERS is required for [initGuestProviders]: without it
                // `PackageInfo.providers` is null and the guest's SDK bootstrap
                // providers (account/network/push) never run.
                PackageManager.GET_META_DATA or
                    PackageManager.GET_ACTIVITIES or
                    PackageManager.GET_PROVIDERS or
                    // Service 组件翻译之后，guest 会自己反查服务信息
                    // （`resolveService` / `getServiceInfo` 读 processName /
                    // foregroundServiceType），没有这张表就只能转发给宿主 PMS 然后
                    // NameNotFoundException。
                    PackageManager.GET_SERVICES,
            )
        }.getOrNull()

        val info = archive?.applicationInfo ?: ApplicationInfo()
        // Point every path at the staged APK: loaders (Flutter's ReLinker for
        // one) resolve lib*.so and assets from exactly these fields.
        info.sourceDir = request.apkPath
        info.publicSourceDir = request.apkPath
        info.nativeLibraryDir = nativeLibDir
        info.packageName = request.packageName
        // The archive's ApplicationInfo has uid/procName unset (-1). Anti-tamper
        // code compares them against Process.myUid()/getProcessName() and bails
        // out ("环境异常" Toast + finishAffinity) when they disagree.
        info.uid = android.os.Process.myUid()
        info.processName = request.packageName
        // ContextImpl derives shared_prefs / files / cache / code_cache from the
        // LoadedApk's data dirs. Point them at the instance directory: the guest
        // package is not installed, so /data/user/0/<guest package> does not
        // exist and every write fails with ENOENT (MMKV, SharedPreferences,
        // Tinker) — which is what made 红果 abort during Application.onCreate.
        val dataDir = File(request.dataPath).apply { mkdirs() }.absolutePath
        info.dataDir = dataDir
        // The per-storage variants are hidden from the SDK; both must follow the
        // same directory or a guest that reads device-protected storage falls
        // back to the (non-existent) /data/user/0/<guest package>.
        listOf("credentialProtectedDataDir", "deviceProtectedDataDir").forEach { name ->
            runCatching { ApplicationInfo::class.java.getField(name).set(info, dataDir) }
        }

        return archive?.also {
            it.applicationInfo = info
            it.packageName = request.packageName
        } ?: PackageInfo().apply {
            packageName = request.packageName
            applicationInfo = info
        }
    }

    /** Unpacks the `lib/<abi>/` shared objects once per instance; loaders need a real directory. */
    private fun extractNativeLibs(context: Context, request: LaunchRequest): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val dir = File(context.filesDir, "guest-libs/${request.instanceId}/$abi").apply { mkdirs() }
        if (dir.listFiles()?.isNotEmpty() == true) return dir.absolutePath

        runCatching {
            ZipFile(request.apkPath).use { zip ->
                val prefix = "lib/$abi/"
                zip.entries().asSequence()
                    .filter { it.name.startsWith(prefix) && it.name.endsWith(".so") }
                    .forEach { entry ->
                        val out = File(dir, entry.name.removePrefix(prefix))
                        zip.getInputStream(entry).use { input ->
                            out.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
            }
        }
        return dir.absolutePath
    }
}
