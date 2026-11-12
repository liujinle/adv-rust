package com.adv.bb.app.guest

import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Intent
import androidx.core.app.CoreComponentFactory

/**
 * 组件的"宿主 loader 找不到就回落 guest loader"工厂。
 *
 * 系统重建组件时有几条路径**不经过我们的 Instrumentation**，直接拿进程的宿主
 * ClassLoader 去 `loadClass`：`ActivityThread.handleReceiver` 就是典型 —— 7.2.4.32
 * 运行中收到一次广播，系统要用宿主 loader 实例化 guest 的 `to0.c`，`ClassNotFoundException`
 * 一路抛到 `RuntimeException: Unable to instantiate receiver to0.c`，crash probe
 * 判定主线程异常直接自杀（2026-11-10 18:41:36 那次）。
 *
 * `android:appComponentFactory` 是唯一能插手这条路径的钩子，所以这里继承
 * androidx 的 [CoreComponentFactory]（保留它原有的 `CompatWrapped` 拆包行为），
 * 只在宿主 loader 抛 `ClassNotFoundException` 时去 guest 的 dex loader 里再找一次。
 *
 * 注意：只在 **CNFE** 时回落。其它异常（`ExceptionInInitializerError`、
 * 构造器抛错）原样抛出，不然会把真正的问题藏起来。
 */
class GuestAppComponentFactory : CoreComponentFactory() {

    init {
        // 装配痕迹：清单里换掉 appComponentFactory 之后，系统在**实例化 Application
        // 之前**就会构造它。这个时间点 `ActivityThread.currentApplication()` 还是
        // null，`GuestRuntime.trace` 写不出来，所以这里必须走 logcat。
        android.util.Log.i(TAG, "appComponentFactory 已装配：${javaClass.name}")
        GuestRuntime.trace("appComponentFactory 已装配（GuestAppComponentFactory）")
    }

    override fun instantiateReceiver(
        cl: ClassLoader,
        className: String,
        intent: Intent?,
    ): BroadcastReceiver {
        return try {
            super.instantiateReceiver(cl, className, intent)
        } catch (missing: ClassNotFoundException) {
            guestInstance(className) as? BroadcastReceiver ?: throw missing
        }
    }

    override fun instantiateService(
        cl: ClassLoader,
        className: String,
        intent: Intent?,
    ): Service {
        return try {
            super.instantiateService(cl, className, intent)
        } catch (missing: ClassNotFoundException) {
            guestInstance(className) as? Service ?: throw missing
        }
    }

    override fun instantiateActivity(
        cl: ClassLoader,
        className: String,
        intent: Intent?,
    ): Activity {
        return try {
            super.instantiateActivity(cl, className, intent)
        } catch (missing: ClassNotFoundException) {
            guestInstance(className) as? Activity ?: throw missing
        }
    }

    /** 在 guest 的 dex loader 里找类并实例化；找不到返回 null，由调用方抛原异常。 */
    private fun guestInstance(className: String): Any? {
        val clazz = GuestRuntime.resolveGuestClass(className) ?: return null
        android.util.Log.i(TAG, "宿主 loader 没有 $className，回落到 guest loader")
        GuestRuntime.trace("AppComponentFactory 回落到 guest loader：$className")
        return clazz.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
    }

    private companion object {
        const val TAG = "GuestAppComponentFactory"
    }
}
