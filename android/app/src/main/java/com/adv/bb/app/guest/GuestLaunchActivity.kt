package com.adv.bb.app.guest

import android.app.Activity
import android.os.Bundle
import android.view.Choreographer

/**
 * 冷启动 gate：先让屏幕上有东西，再去拉 guest。
 *
 * 为什么需要这一页：点「启动」到 guest 画出第一帧之间，主线程被 guest 的
 * `Application.onCreate` 占住（实测 3.4~4.8 秒）。这期间任何 `addView` 都排不进
 * 绘制队列，能显示出来的只有两种窗口 —— 系统按主题画的启动窗口，或者**已经画完**
 * 的宿主页面。实测本机 ROM 的启动窗口只画底色不画图标（`windowSplashScreenAnimatedIcon`
 * 设了 PNG / 矢量都不显示），所以它是纯白；而点「启动」会把宿主任务切到后台，
 * 宿主页面也看不见了。
 *
 * 于是把它做成一个新任务里的普通宿主页面：它自己一帧就能画完遮罩（图标 + 应用名 +
 * 转圈），随后主线程即使被 guest 卡死，这一帧也留在屏幕上；等 guest 真正
 * resume（[GuestRuntime.dismissLaunchGate]）再关掉。
 *
 * 与 `Theme.Adv.Guest` 的 `windowDisablePreview` 配合：guest 自己的任务不显示系统
 * 启动窗口，屏幕才不会又被白色盖住。
 */
open class GuestLaunchActivity : Activity() {

    class P1 : GuestLaunchActivity()
    class P2 : GuestLaunchActivity()
    class P3 : GuestLaunchActivity()

    private var request: com.adv.core.data.LaunchRequest? = null
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 触屏/进程恢复重建时 Intent 里的字段可能已被 guest 的 Intent 顶掉，
        // 盘上那份 guest-launch.json 是最后的兜底。
        val request = GuestLauncher.requestFromIntent(intent)
            ?: GuestLauncher.requestFromRecord(this)
        if (request == null) {
            GuestRuntime.trace("gate 缺少实例参数，直接退出")
            finish()
            return
        }
        this.request = request
        // 用宿主传来的同一个 t0 起计时会话；gate / proxy / guest 都在本子进程里，
        // 进程内单例足够贯穿到首页 resume。缺 extra 时退化为 gate 自己的 onCreate。
        val launchStartMs = intent?.getLongExtra(GuestLauncher.EXTRA_LAUNCH_START_MS, 0L)
            ?.takeIf { it > 0 } ?: System.currentTimeMillis()
        GuestRuntime.trace(LaunchClock.begin(request.instanceId, launchStartMs))
        // 这一页从头到尾不接受焦点/触摸。
        //
        // 实测 ANR 的真凶不是"没画出第一帧"，而是焦点事件：主线程一旦进了 guest 的
        // `Application.onCreate`（6.1 秒），任何一条投给宿主窗口的
        // `FocusEvent(hasFocus=false)` 都会 5 秒等不到 ack，系统立刻判
        // `Input dispatching timed out … Waited 5000ms for FocusEvent(hasFocus=false)`
        // 并杀进程。遮罩自己抢焦点，就等于制造了两条这样的消息（宿主页失焦、gate 失焦）。
        // 让它不可获焦，焦点链上就没有我们的窗口要回话，卡多久都不会因此 ANR。
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        )
        // 同步挂上：这一帧画完之前不启动 guest。
        GuestRuntime.installLaunchGate(this, request.instanceId, request.displayName, request.apkPath)
        GuestRuntime.markLaunchGate(this)
        GuestRuntime.launchMark("gate 遮罩已挂上（${request.displayName}）")

        // 兜底：guest 一直起不来时不能把用户永久关在遮罩里。
        window.decorView.postDelayed({
            GuestRuntime.dismissLaunchGate("gate 超时")
        }, GATE_TIMEOUT_MS)

        // 不获焦 → 收不到 `onWindowFocusChanged`，按帧 + 固定延时启动。
        window.decorView.postDelayed({ startGuest("首帧后") }, START_AFTER_FIRST_FRAME_MS)
    }

    private fun startGuest(reason: String) {
        if (started) return
        started = true
        val request = this.request ?: return
        GuestRuntime.trace("gate 开始拉起 guest（$reason）")
        // 先保证遮罩这一帧真的画完，再进主线程长阻塞。
        val choreographer = Choreographer.getInstance()
        choreographer.postFrameCallback {
            choreographer.postFrameCallback {
                GuestLauncher.launchFromGate(this, request)
            }
        }
    }

    /**
     * 被切到后台 = guest 的窗口已经盖上来，这一页的使命结束。
     *
     * 比"等 guest resume"更可靠：guest 在自己的任务里，挡住这一页时系统一定会先
     * stop 我们，不依赖 guest 那边任何一次回调（那边的主线程可能正忙）。
     * 留 500ms 是为了等 guest 真的画出第一帧，避免摘任务时闪一下宿主列表。
     */
    override fun onStop() {
        super.onStop()
        if (isFinishing) return
        window.decorView.postDelayed({
            runCatching { finishAndRemoveTask() }
            GuestRuntime.trace("gate 已切后台，自行关闭")
        }, 500L)
    }

    /** 遮罩撤掉之前不让用户返回：这时候退回去，guest 还在后台初始化，状态是乱的。 */
    @Deprecated("Activity.onBackPressed is deprecated, but this page is intentionally not a back stack citizen")
    override fun onBackPressed() = Unit

    private companion object {
        const val GATE_TIMEOUT_MS = 20_000L

        /** 遮罩上屏后再等这么久才进主线程长阻塞（留出走完一次焦点/绘制事务的余量）。 */
        const val START_AFTER_FIRST_FRAME_MS = 400L
    }
}
