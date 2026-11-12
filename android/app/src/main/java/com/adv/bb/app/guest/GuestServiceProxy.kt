package com.adv.bb.app.guest

import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.os.IBinder

/**
 * 槽位化的 guest Service 代理（对应 [GuestActivityProxy] 在 Activity 侧的角色）。
 *
 * guest 的 `startService` / `bindService` 指向的是它自己清单里的组件，宿主清单里
 * 没有这些声明，AMS 只会回 `Unable to start service`（绑定侧表现为调用方一直等
 * `onServiceConnected`，超时后报 `binding service timed out`）。[GuestRuntime] 的
 * `IActivityManager` 代理把 ComponentName 换成当前槽位的这个类，真身类名与原始
 * Intent 走 extra，于是系统登记/调度的都是宿主进程里真实存在的组件。
 *
 * **一个类只能承载一个 guest Service**：AMS 按组件身份复用 ServiceRecord，同一个
 * 组件被第二个服务绑定时不会重新 `onCreate` / `onBind`，`stopService` 也会把别的
 * 服务一起停掉。哔哩哔哩播放页每 8 秒对 `IjkMediaPlayerLocalService` 做一次
 * stop + bind，共享组件会把下载服务连带拆掉，所以按槽位准备 [GuestSlotContract.SERVICE_POOL]
 * 个类：N0（本类）/ S1..S11（嵌套类），槽位 P1~P3 各有自己的 `P1`/`P2`/`P3` 前缀类。
 *
 * 和 Activity 不同，Service 不经过 Instrumentation，系统实例化的是**代理**；guest
 * 的真身由 [GuestRuntime.createGuestService] 自己 new 出来并接上 guest context，
 * 这里只负责把生命周期逐个转发，并保证 guest 的 `onCreate` 早于 `onStartCommand`
 * / `onBind`（guest 里常见的 "Service 构造器读 Application" 否则会拿到 null）。
 */
open class GuestServiceProxy : Service() {

    class S1 : GuestServiceProxy()
    class S2 : GuestServiceProxy()
    class S3 : GuestServiceProxy()
    class S4 : GuestServiceProxy()
    class S5 : GuestServiceProxy()
    class S6 : GuestServiceProxy()
    class S7 : GuestServiceProxy()
    class S8 : GuestServiceProxy()
    class S9 : GuestServiceProxy()
    class S10 : GuestServiceProxy()
    class S11 : GuestServiceProxy()

    /** guest 真身；创建失败保持 null，后续调用直接失败（不再反复尝试）。 */
    @Volatile
    private var delegate: Service? = null

    /**
     * 创建真身用的 Intent。
     *
     * `onCreate` 拿不到 Intent（系统在 `onStartCommand` / `onBind` 才递进来），而
     * guest Service 允许在 `onCreate` 里就做事，所以真身统一等到这两个入口之一
     * 再创建；之后所有生命周期都复用同一个实例。
     */
    private var firstIntent: Intent? = null

    override fun onCreate() {
        super.onCreate()
        GuestRuntime.trace("service proxy onCreate ${javaClass.simpleName}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val service = ensureDelegate(intent) ?: return START_NOT_STICKY
        val guestIntent = GuestRuntime.guestServiceIntent(intent) ?: intent
        val result = runCatching { service.onStartCommand(guestIntent, flags, startId) }
            .onFailure { traceFailure("onStartCommand", it) }
            .getOrDefault(START_NOT_STICKY)
        GuestRuntime.trace(
            "service onStartCommand ${service.javaClass.name} startId=$startId guest=$result",
        )
        // 对系统只承诺 NOT_STICKY：进程重启后 GuestRuntime 里没有这个 guest，
        // 也没有 LaunchRequest 可以重建它，系统按 sticky 拉起来只会是空壳。
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        val service = ensureDelegate(intent) ?: return null
        val guestIntent = GuestRuntime.guestServiceIntent(intent) ?: intent
        val binder = runCatching { service.onBind(guestIntent) }
            .onFailure { traceFailure("onBind", it) }
            .getOrNull()
        GuestRuntime.trace(
            "service onBind ${service.javaClass.name} binder=${binder?.javaClass?.name}",
        )
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        val service = delegate ?: return super.onUnbind(intent)
        val guestIntent = GuestRuntime.guestServiceIntent(intent) ?: intent
        return runCatching { service.onUnbind(guestIntent) }
            .onFailure { traceFailure("onUnbind", it) }
            .getOrDefault(false)
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        val service = delegate ?: return
        runCatching { service.onRebind(GuestRuntime.guestServiceIntent(intent) ?: intent) }
            .onFailure { traceFailure("onRebind", it) }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val service = delegate ?: return
        runCatching { service.onTaskRemoved(rootIntent) }
            .onFailure { traceFailure("onTaskRemoved", it) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val service = delegate ?: return
        runCatching { service.onConfigurationChanged(newConfig) }
            .onFailure { traceFailure("onConfigurationChanged", it) }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        val service = delegate ?: return
        runCatching { service.onLowMemory() }.onFailure { traceFailure("onLowMemory", it) }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val service = delegate ?: return
        runCatching { service.onTrimMemory(level) }.onFailure { traceFailure("onTrimMemory", it) }
    }

    override fun onDestroy() {
        val service = delegate
        delegate = null
        if (service != null) {
            // guest 的 onDestroy 抛异常不该影响系统侧的注销流程。
            runCatching { service.onDestroy() }.onFailure { traceFailure("onDestroy", it) }
            GuestRuntime.forgetGuestService(service)
            GuestRuntime.trace("service onDestroy ${service.javaClass.name}")
        } else {
            GuestRuntime.trace("service proxy onDestroy ${javaClass.simpleName}（无真身）")
        }
        super.onDestroy()
    }

    /** 只创建一次；失败就记住（[delegate] 仍为 null）并返回 null，不反复重试。 */
    private fun ensureDelegate(intent: Intent?): Service? {
        delegate?.let { return it }
        if (firstIntent == null) firstIntent = intent
        val service = GuestRuntime.createGuestService(this, intent ?: firstIntent) ?: return null
        // guest Service 的 onCreate 必须先跑：系统侧的 onCreate 是代理自己的，
        // 与 guest 无任何关系。
        runCatching { service.onCreate() }.onFailure { traceFailure("onCreate", it) }
        delegate = service
        return service
    }

    private fun traceFailure(step: String, error: Throwable) {
        // 带上栈：Service 的失败大多发生在 guest 自己的 onBind/onStartCommand 里，
        // 只记类名+消息看不出是哪一行（例如 KDownloadService 的 ClassCastException）。
        GuestRuntime.trace(
            "guest Service.$step 失败 ${error.javaClass.name}: ${error.message}\n" +
                error.stackTraceToString(),
        )
    }
}

/** 槽位 P1 的 Service 代理池；类名与 [GuestSlotContract.serviceClassName] 一一对应。 */
open class GuestServiceProxyP1 : GuestServiceProxy() {
    class S1 : GuestServiceProxyP1()
    class S2 : GuestServiceProxyP1()
    class S3 : GuestServiceProxyP1()
    class S4 : GuestServiceProxyP1()
    class S5 : GuestServiceProxyP1()
    class S6 : GuestServiceProxyP1()
    class S7 : GuestServiceProxyP1()
    class S8 : GuestServiceProxyP1()
    class S9 : GuestServiceProxyP1()
    class S10 : GuestServiceProxyP1()
    class S11 : GuestServiceProxyP1()
}

/** 槽位 P2 的 Service 代理池。 */
open class GuestServiceProxyP2 : GuestServiceProxy() {
    class S1 : GuestServiceProxyP2()
    class S2 : GuestServiceProxyP2()
    class S3 : GuestServiceProxyP2()
    class S4 : GuestServiceProxyP2()
    class S5 : GuestServiceProxyP2()
    class S6 : GuestServiceProxyP2()
    class S7 : GuestServiceProxyP2()
    class S8 : GuestServiceProxyP2()
    class S9 : GuestServiceProxyP2()
    class S10 : GuestServiceProxyP2()
    class S11 : GuestServiceProxyP2()
}

/** 槽位 P3 的 Service 代理池。 */
open class GuestServiceProxyP3 : GuestServiceProxy() {
    class S1 : GuestServiceProxyP3()
    class S2 : GuestServiceProxyP3()
    class S3 : GuestServiceProxyP3()
    class S4 : GuestServiceProxyP3()
    class S5 : GuestServiceProxyP3()
    class S6 : GuestServiceProxyP3()
    class S7 : GuestServiceProxyP3()
    class S8 : GuestServiceProxyP3()
    class S9 : GuestServiceProxyP3()
    class S10 : GuestServiceProxyP3()
    class S11 : GuestServiceProxyP3()
}
