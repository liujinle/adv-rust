package com.adv.bb.app.guest

/**
 * guest 槽位的纯 Kotlin 契约：不引用 Android Framework，便于本地 JVM 单测直接覆盖
 * 进程归属、组件路由、诊断文件名和按实例恢复文件名。生产代码 [GuestSlots] 必须
 * 全部委托到这里，避免测试判定与实际路由各写一份。
 */
internal object GuestSlotContract {

    const val COUNT: Int = 4
    const val LEGACY_PROCESS_SUFFIX: String = ":guest"

    private const val PROXY_BASE = "com.adv.bb.app.guest.GuestActivityProxy"
    private const val GATE_BASE = "com.adv.bb.app.guest.GuestLaunchActivity"
    private const val RECEIVER_BASE = "com.adv.bb.app.guest.GuestControlReceiver"

    /**
     * guest Service 的槽位代理类。
     *
     * guest 的 `startService` / `bindService` 指向的组件宿主清单里没有，AMS 会直接
     * `Unable to start service`；和 Activity 一样，先把 ComponentName 换成这个代理，
     * 真身类名走 extra，由代理创建并驱动 guest 的 Service 生命周期。
     *
     * 一个代理类只能承载**一个** guest Service：AMS 按组件身份复用 ServiceRecord，
     * 同一个组件被第二个服务绑定时不会重新 onCreate/onBind，stopService 也会把
     * 别的服务一起停掉（哔哩哔哩的 `IjkMediaPlayerLocalService` 每 8 秒 stop+bind
     * 一次，共享组件时下载服务被连带拆掉）。所以按槽位准备一个类池，
     * [SERVICE_POOL] 是每槽位的上限。
     */
    private const val SERVICE_BASE = "com.adv.bb.app.guest.GuestServiceProxy"

    const val SERVICE_POOL: Int = 12

    val all: IntRange get() = 0 until COUNT

    private fun requireSlot(slot: Int) {
        require(slot in all) { "未知 guest 槽位：$slot" }
    }

    fun processSuffix(slot: Int): String {
        requireSlot(slot)
        return if (slot == 0) LEGACY_PROCESS_SUFFIX else ":p$slot"
    }

    fun processName(hostPackage: String, slot: Int): String = hostPackage + processSuffix(slot)

    fun fromProcessName(name: String?): Int? = all.firstOrNull { slot ->
        name?.endsWith(processSuffix(slot)) == true
    }

    private fun nestedClassName(base: String, slot: Int): String {
        requireSlot(slot)
        return if (slot == 0) base else "$base${'$'}P$slot"
    }

    fun proxyClassName(slot: Int): String = nestedClassName(PROXY_BASE, slot)

    fun gateClassName(slot: Int): String = nestedClassName(GATE_BASE, slot)

    fun receiverClassName(slot: Int): String = nestedClassName(RECEIVER_BASE, slot)

    /**
     * 槽位 [slot] 的第 [index] 个 Service 代理类名。
     *
     * `index = 0` 沿用可读名字（`GuestServiceProxy` / `GuestServiceProxyP1`），
     * 其余是嵌套类 `$S1`..`$S11`；声明顺序与 `GuestServiceProxy.kt`、
     * `AndroidManifest.xml` 必须一致（单测按同一函数断言）。
     */
    fun serviceClassName(slot: Int, index: Int = 0): String {
        requireSlot(slot)
        require(index in 0 until SERVICE_POOL) { "未知 service 代理下标：$index" }
        val base = if (slot == 0) SERVICE_BASE else "$SERVICE_BASE" + "P$slot"
        return if (index == 0) base else "$base${'$'}S$index"
    }

    fun isProxyClass(name: String?): Boolean = all.any { proxyClassName(it) == name }

    fun isGateClass(name: String?): Boolean = all.any { gateClassName(it) == name }

    fun isServiceClass(name: String?): Boolean = all.any { slot ->
        (0 until SERVICE_POOL).any { index -> serviceClassName(slot, index) == name }
    }

    fun slotForComponent(name: String?): Int? = all.firstOrNull { slot ->
        proxyClassName(slot) == name || gateClassName(slot) == name || isServiceClassOf(name, slot)
    }

    private fun isServiceClassOf(name: String?, slot: Int): Boolean =
        (0 until SERVICE_POOL).any { index -> serviceClassName(slot, index) == name }

    fun traceFileName(slot: Int?): String {
        if (slot != null) requireSlot(slot)
        return when (slot) {
            null, 0 -> "guest-trace.log"
            else -> "guest-trace-p$slot.log"
        }
    }

    fun nativeCrashFileName(slot: Int?): String {
        if (slot != null) requireSlot(slot)
        return when (slot) {
            null, 0 -> "native-crash.log"
            else -> "native-crash-p$slot.log"
        }
    }

    /** instanceId 来自引擎，仍按不可信输入处理：路径分隔符、点号一律替换掉。 */
    fun recordFileName(instanceId: String): String {
        require(instanceId.isNotBlank()) { "instanceId 不能为空" }
        val safe = instanceId.map { char ->
            if (char.isLetterOrDigit() || char == '-' || char == '_') char else '_'
        }.joinToString("")
        return "$safe.json"
    }

    fun recordTempFileName(instanceId: String): String = recordFileName(instanceId) + ".tmp"
}
