package com.adv.bb.app

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Bundle
import com.adv.bb.app.guest.GuestProcess
import java.io.File

class AdvApplication : Application() {

    /**
     * 宿主账本 + DI 图。**只有主进程有**（双账本原则）：guest 子进程只跑翻译层，
     * 绝不初始化 Room / DataStore / engine —— 同一个 SQLite 被两个进程打开是
     * Room 明确不支持的场景（锁竞争 / WAL 损坏 / 直接崩）。
     */
    var containerOrNull: AppContainer? = null
        private set

    /** 主进程访问入口；在 `:guest` 里访问会立刻炸掉，属于"用错地方"的编程错误。 */
    val container: AppContainer
        get() = containerOrNull ?: error(
            "AppContainer 只存在于宿主主进程，当前进程=${runCatching { Application.getProcessName() }.getOrNull()}",
        )

    /** 当前进程是不是任一 guest 槽位子进程（P0 `:guest`、P1+ `:pN`）。 */
    val isGuestProcess: Boolean by lazy { GuestProcess.isGuestProcess() }

    override fun onCreate() {
        super.onCreate()
        // native 信号处理器是**进程级**的：guest 的加固壳走到退出分支时是 SIGSEGV，
        // 现在它死在 :guest 进程里，探针必须跟着装到那边才有栈可看。
        com.adv.bb.app.guest.CrashProbe.install(this)
        if (isGuestProcess) {
            GuestProcess.onGuestProcessStart(this)
            return
        }
        dumpPreviousExit()
        containerOrNull = AppContainer(this).also { it.start() }
        watchOrientation()
    }

    /**
     * 宿主 Activity 每次创建/恢复时套用 [DisplayPolicy]。
     *
     * 放在 Application 里而不是各个 Activity：宿主有 MainActivity 和承载虚拟
     * 应用的 GuestActivityProxy 两个入口，集中一处才不会漏。
     *
     * 改造后 GuestActivityProxy 在 `:guest` 进程，这里注册不到它的生命周期 ——
     * 这正是目的：宿主的方向/显示策略不再被虚拟应用的窗口牵动，guest 的方向由
     * 它自己声明（`GuestPackageManager.getActivityInfo` 返回 guest orientation）。
     */
    private fun watchOrientation() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                DisplayPolicy.onResumed(activity)
            }

            override fun onActivityResumed(activity: Activity) {
                DisplayPolicy.onResumed(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                DisplayPolicy.onPaused(activity)
            }

            override fun onActivityStarted(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /**
     * 把上一次进程的退出记录落到 `files/guest-exit-info.txt`。
     *
     * 红果的加固壳走到退出分支时是 **native 崩溃**（ApplicationExitInfo:
     * reason=5 APP CRASH(NATIVE) / status=11 SIGSEGV），Java 侧和普通 logcat
     * 都看不到栈，tombstone 又要 root。`ApplicationExitInfo.traceInputStream`
     * 是唯一拿得到 native 崩溃栈的地方，必须在装 AMS 代理之前调，
     * 否则会被"虚拟应用无退出历史"那条代理挡成空列表。
     */
    private fun dumpPreviousExit() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val manager = getSystemService(ActivityManager::class.java) ?: return
            val exits = manager.getHistoricalProcessExitReasons(null, 0, 6)
            if (exits.isEmpty()) return
            File(filesDir, "guest-exit-info.txt").printWriter().use { writer ->
                exits.forEach { info ->
                    writer.println(
                        "=== pid=${info.pid} reason=${info.reason}" +
                            " status=${info.status} desc=${info.description}",
                    )
                    runCatching {
                        info.traceInputStream?.bufferedReader()?.use { reader ->
                            writer.println(reader.readText())
                        }
                    }
                }
            }
        }
    }
}
