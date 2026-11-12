package com.adv.bb.app.guest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process

/**
 * 宿主主进程 → `:guest` 进程的关机通道。
 *
 * 为什么不能直接 `Process.killProcess(guestPid)`：guest 的任务卡还在（前台或有
 * Activity 存活）时，AMS 会在进程死后**立刻把进程拉回来恢复 Activity 栈**。
 * 实测 `bb-cli stop` 杀完 3 秒内 `:guest` 就是新 pid 且账本还是 RUNNING。
 *
 * 正确顺序是「先让 guest 自己 `finishAndRemoveTask()` 摘掉任务，再退出进程」：
 * 任务没了，AMS 就不会再重建它。强杀只作为兜底（[GuestProcess.shutdown] 里）。
 */
open class GuestControlReceiver : BroadcastReceiver() {

    class P1 : GuestControlReceiver()
    class P2 : GuestControlReceiver()
    class P3 : GuestControlReceiver()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TEST_TOAST) {
            val text = intent.getStringExtra(EXTRA_TOAST_TEXT).orEmpty()
                .ifBlank { "Toast 隔离测试" }
            val result = GuestRuntime.showDebugToast(text)
            GuestRuntime.trace("Toast 测试广播 result=$result text=$text")
            return
        }
        if (intent.action != ACTION_SHUTDOWN) return
        val handled = GuestRuntime.shutdownFromHost("宿主 stop / cleardata / 卸载")
        GuestRuntime.trace("关机广播已收到 handled=$handled pid=${Process.myPid()}")
        // 有 guest Activity 可摘时多等一会儿：`finishAndRemoveTask` 是异步的，
        // 任务还没摘掉就退出进程，AMS 会把我们重新拉起来（就变成了"停不掉"）。
        val delay = if (handled) 600L else 0L
        Handler(Looper.getMainLooper()).postDelayed({
            Process.killProcess(Process.myPid())
        }, delay)
    }

    companion object {
        const val ACTION_SHUTDOWN = "com.adv.bb.app.guest.ACTION_SHUTDOWN"
        const val ACTION_TEST_TOAST = "com.adv.bb.app.guest.ACTION_TEST_TOAST"
        const val EXTRA_TOAST_TEXT = "com.adv.bb.app.guest.extra.TOAST_TEXT"
    }
}
