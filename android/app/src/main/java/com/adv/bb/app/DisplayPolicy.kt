package com.adv.bb.app

import android.app.Activity
import android.content.pm.ActivityInfo

/**
 * 宿主界面的方向策略。
 *
 * 设置里的"禁止横屏"只作用于**宿主自己**：宿主的 Activity 固定竖屏，不随系统
 * 或传感器旋转。虚拟应用的界面挂在宿主窗口
 * （[com.adv.bb.app.guest.GuestActivityProxy]）里，所以它也跟着竖屏，但这里
 * 不去改 guest 的 Activity 对象——那是 guest 自己的事。
 *
 * 由 [AdvApplication] 注册的 ActivityLifecycleCallbacks 驱动：创建和 resume 时
 * 各校正一次。guest 内部若把自己的窗口请求成横屏，下一次生命周期回调会把宿主
 * 窗口按回竖屏。
 */
object DisplayPolicy {

    @Volatile
    var lockPortrait: Boolean = false
        set(value) {
            field = value
            // 立即作用到当前界面，不必等下一次 resume。
            current?.let { applyTo(it) }
        }

    @Volatile
    private var current: Activity? = null

    fun onResumed(activity: Activity) {
        current = activity
        applyTo(activity)
    }

    fun onPaused(activity: Activity) {
        if (current === activity) current = null
    }

    private fun applyTo(activity: Activity) {
        if (activity.isFinishing) return
        val target = if (lockPortrait) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            // 松开开关时把宿主还回"跟随系统"，不留下一个死的竖屏锁。
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (activity.requestedOrientation == target) return
        runCatching { activity.requestedOrientation = target }
    }
}
