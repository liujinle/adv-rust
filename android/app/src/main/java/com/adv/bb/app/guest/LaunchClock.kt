package com.adv.bb.app.guest

/**
 * 一次冷启动的端到端计时会话。
 *
 * 计时起点 t0 = 宿主 `GuestLauncher.launch` 收到启动请求的墙钟毫秒，经 gate Intent
 * 的 [GuestLauncher.EXTRA_LAUNCH_START_MS] 传到 `:guest` / `:pN` 进程；gate、proxy 和
 * guest Activity 都在同一个子进程里，所以进程内单例足够贯穿，不必读文件。
 * 终点 = `onResume MainFragmentActivity`（首页），`finish()` 只会输出一次。
 *
 * 输出行统一以 `LAUNCH ` 开头，自带 `+总耗时` 与 `d=距上一阶段`；
 * `GuestRuntime.trace` 再补 epoch 毫秒与槽位前缀，`bb-cli launchtime` 据此排序。
 * 纯 Kotlin、无 Android 依赖，可直接跑 JVM 单测。
 */
internal object LaunchClock {
    private const val PREFIX = "LAUNCH"

    private val lock = Any()
    private var instanceId: String = ""
    private var t0Ms: Long = 0L
    private var lastMs: Long = 0L
    private var finished: Boolean = false

    /** 宿主进程用：它自己不跑 gate/guest，只打一行起点，不初始化本进程的会话。 */
    fun beginLine(instanceId: String, t0Ms: Long, extra: String = ""): String =
        "$PREFIX +0ms d=0ms BEGIN instance=$instanceId t0=$t0Ms$extra"

    /** guest 进程用：用宿主传过来的同一个 t0 起一次会话。 */
    fun begin(instanceId: String, t0Ms: Long): String = synchronized(lock) {
        this.instanceId = instanceId
        this.t0Ms = t0Ms
        this.lastMs = t0Ms
        this.finished = false
        format("BEGIN instance=$instanceId t0=$t0Ms", t0Ms)
    }

    fun mark(label: String, nowMs: Long): String? = synchronized(lock) {
        if (t0Ms == 0L) return null
        format(label, nowMs)
    }

    /** 首页 resume：只报一次总耗时，后续页面 resume 不再改写结论。 */
    fun finish(label: String, nowMs: Long): String? = synchronized(lock) {
        if (t0Ms == 0L || finished) return null
        finished = true
        val total = (nowMs - t0Ms).coerceAtLeast(0)
        val delta = (nowMs - lastMs).coerceAtLeast(0)
        lastMs = nowMs
        "$PREFIX +${total}ms d=${delta}ms DONE total=${total}ms $label instance=$instanceId"
    }

    fun reset() = synchronized(lock) {
        instanceId = ""
        t0Ms = 0L
        lastMs = 0L
        finished = false
    }

    /**
     * 启动会话是否还在进行。
     *
     * 用来区分加固壳的两种 `finishAffinity`：启动期那次是"判定不合格"的自杀，必须拦；
     * 首页起来之后那次是用户主动退出，拦了就永远退不掉。
     */
    fun isRunning(): Boolean = synchronized(lock) { t0Ms != 0L && !finished }

    private fun format(label: String, nowMs: Long): String {
        val total = (nowMs - t0Ms).coerceAtLeast(0)
        val delta = (nowMs - lastMs).coerceAtLeast(0)
        lastMs = nowMs
        return "$PREFIX +${total}ms d=${delta}ms $label"
    }
}
