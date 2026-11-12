package com.adv.bb.app.guest

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver

/**
 * 固定 guest 进程槽位。每个槽位仍然只装一个实例，因此 GuestRuntime 的进程级
 * Instrumentation / ClassLoader / Toast / WindowManager 单例不需要改成多实例 map。
 *
 * 字符串与归属判定全部委托给 [GuestSlotContract]，让 JVM 单测与生产路由使用同一份规则。
 * P0 保留旧类名、`:guest` 和旧 taskAffinity，升级前已经存在的任务卡仍能恢复。
 */
internal object GuestSlots {

    const val COUNT = GuestSlotContract.COUNT
    val all: IntRange = GuestSlotContract.all

    /**
     * 必须在 guest 伪装进程名之前读取。AdvApplication.onCreate 的分流会最早触发这里，
     * 之后即使 setArgV0 把 /proc/self/cmdline 改成 guest 包名，槽位身份也不会丢。
     */
    val currentSlot: Int? by lazy {
        GuestSlotContract.fromProcessName(Application.getProcessName())
    }

    fun processSuffix(slot: Int): String = GuestSlotContract.processSuffix(slot)

    fun processName(hostPackage: String, slot: Int): String =
        GuestSlotContract.processName(hostPackage, slot)

    fun fromProcessName(name: String?): Int? = GuestSlotContract.fromProcessName(name)

    fun proxyClass(slot: Int): Class<out Activity> = when (slot) {
        0 -> GuestActivityProxy::class.java
        1 -> GuestActivityProxy.P1::class.java
        2 -> GuestActivityProxy.P2::class.java
        3 -> GuestActivityProxy.P3::class.java
        else -> error("未知 guest 槽位：$slot")
    }

    fun gateClass(slot: Int): Class<out Activity> = when (slot) {
        0 -> GuestLaunchActivity::class.java
        1 -> GuestLaunchActivity.P1::class.java
        2 -> GuestLaunchActivity.P2::class.java
        3 -> GuestLaunchActivity.P3::class.java
        else -> error("未知 guest 槽位：$slot")
    }

    fun receiverClass(slot: Int): Class<out BroadcastReceiver> = when (slot) {
        0 -> GuestControlReceiver::class.java
        1 -> GuestControlReceiver.P1::class.java
        2 -> GuestControlReceiver.P2::class.java
        3 -> GuestControlReceiver.P3::class.java
        else -> error("未知 guest 槽位：$slot")
    }

    fun proxyClassName(slot: Int): String = GuestSlotContract.proxyClassName(slot)

    fun gateClassName(slot: Int): String = GuestSlotContract.gateClassName(slot)

    fun receiverClassName(slot: Int): String = GuestSlotContract.receiverClassName(slot)

    fun serviceClassName(slot: Int, index: Int = 0): String =
        GuestSlotContract.serviceClassName(slot, index)

    fun isProxyClass(name: String?): Boolean = GuestSlotContract.isProxyClass(name)

    fun isGateClass(name: String?): Boolean = GuestSlotContract.isGateClass(name)

    fun isServiceClass(name: String?): Boolean = GuestSlotContract.isServiceClass(name)

    fun slotForComponent(name: String?): Int? = GuestSlotContract.slotForComponent(name)

    fun traceFileName(slot: Int?): String = GuestSlotContract.traceFileName(slot)

    fun nativeCrashFileName(slot: Int?): String = GuestSlotContract.nativeCrashFileName(slot)
}
