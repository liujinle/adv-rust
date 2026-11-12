package com.adv.bb.app.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import com.adv.bb.app.AdvApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 命令行"启动实例"的载体（debug only）。
 *
 * 为什么不是广播：Android 10+ 禁止**后台**进程启动 Activity，广播到达时宿主
 * 通常不在前台，`GuestLauncher.launch()` 发出去的 startActivity 会被系统静默
 * 丢弃（现象：命令报成功、trace 却停在 `hook ok`，前台还是桌面）。
 *
 * 由 `am start` 拉起的 Activity 属于前台，可以继续 startActivity 到代理组件，
 * 所以启动这件事走这里，查状态/读日志仍走广播。
 *
 * 用法（脚本已封装）：`adb shell am start -n com.adv.bb.app/.debug.DebugLaunchActivity
 * --es selector_b64 <base64>`，结果同样写 `files/cli-out.txt`。
 */
class DebugLaunchActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 纯跳板窗口，不要焦点也不要触摸：冷启动时 guest Application.onCreate
        // 会占住主线程数秒，这个窗口只要持有焦点，InputDispatcher 派发
        // FocusEvent 就会 5s 超时触发 ANR，vivo 随后按「关闭应用」杀进程
        // （am_kill ... user request after error），表现是启动几秒后弹回桌面。
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        window.setFlags(flags, flags)
        val selector = DebugCommands.decode(intent.getStringExtra(EXTRA_SELECTOR_B64))
            ?: intent.getStringExtra(EXTRA_SELECTOR).orEmpty()
        // ping：只把进程拉起来（解除 stopped 状态），不启动任何实例。
        // `adb install -r` 之后应用是 stopped 的，广播会被系统丢弃，先用它唤醒。
        if (selector.isBlank() || selector == PING) {
            DebugCommands.write(this, "OK 宿主已就绪 pid=${android.os.Process.myPid()}")
            finish()
            return
        }
        val app = application as AdvApplication
        scope.launch {
            val message = runCatching {
                val container = app.container
                DebugCommands.awaitEngine(container)
                val instance = DebugCommands.resolve(container, selector)
                    ?: return@runCatching "ERR 找不到实例：$selector"
                val started = container.instanceActions.launch(instance.instanceId)
                val request = started.valueOrNull()
                    ?: return@runCatching "ERR 启动失败：${started.errorOrNull()?.userMessage}"
                // 与 UI 同一条路径：openGuest 里做槽位分配 + installHook → prepare → startActivity。
                val opened = withContext(Dispatchers.Main) { container.openGuest(request) }
                if (!opened) {
                    return@runCatching "ERR 启动失败：进程池已满或启动记录不可写（本次账本已回滚）"
                }
                "OK 已启动 ${instance.appName} (${instance.instanceId})"
            }.getOrElse {
                "ERR ${it.javaClass.simpleName}: ${it.message}"
            }
            DebugCommands.write(this@DebugLaunchActivity, message)
            Log.i(TAG, message)
            // 代理 Activity 用了 MULTIPLE_TASK，独占任务；这个载体可以退场。
            withContext(Dispatchers.Main) { finish() }
        }
    }

    companion object {
        const val EXTRA_SELECTOR = "selector"
        const val EXTRA_SELECTOR_B64 = "selector_b64"

        /** 只唤醒进程、不启动实例。 */
        const val PING = "__ping__"

        private const val TAG = "BbCli"
    }
}
