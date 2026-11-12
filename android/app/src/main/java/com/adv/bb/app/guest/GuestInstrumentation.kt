package com.adv.bb.app.guest

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.UserHandle

/**
 * Instrumentation that makes the system create the guest's Activity where it
 * asked for our proxy component.
 *
 * `newActivity` receives the proxy class name and the launch Intent; when that
 * Intent carries a guest instance we return an instance of the guest's launcher
 * Activity instead. From that point the framework's own launch sequence
 * (attach → theme → onCreate → onResume) drives the guest, which is exactly how
 * the original virtualization engines get a real Activity without touching AMS.
 *
 * All other calls are left to the real instrumentation by copying its context
 * fields at construction time: a fresh `Instrumentation()` has a null `mThread`
 * and would break every lifecycle dispatch it forwards.
 */
class GuestInstrumentation(private val base: Instrumentation) : Instrumentation() {

    init {
        copyField("mThread")
        copyField("mInstrContext")
        copyField("mAppContext")
        copyField("mComponent")
        copyField("mUiAutomationConnection")
    }

    override fun newActivity(cl: ClassLoader?, className: String?, intent: Intent?): Activity {
        val instanceId = intent?.getStringExtra(GuestLauncher.EXTRA_INSTANCE_ID)
        val guest = GuestRuntime.guestFor(instanceId)
        val target = intent?.getStringExtra(GuestLauncher.EXTRA_GUEST_ACTIVITY)
        GuestRuntime.trace(
            "newActivity className=$className guest=${guest != null}" +
                " target=$target" +
                " keys=${intent?.extras?.keySet()?.joinToString(",")}",
        )
        // 同一个 ShortSeriesActivity，从「我的 → 历史」点能正常播放，从找剧页
        // 点封面却报「网络错误」：keys 集合一样，差异只可能在 extras 的值
        // （vid / series_id / play_type）。只对这张页面打一次只读快照做对比。
        if (target?.endsWith("ShortSeriesActivity") == true) {
            val extras = intent?.extras
            val values = extras?.keySet()?.joinToString(";") { key ->
                val value = runCatching { extras.get(key)?.toString()?.take(60) }.getOrDefault("-")
                "$key=$value"
            }
            GuestRuntime.trace("series extras -> $values")
        }
        if (guest == null) return super.newActivity(cl, className, intent)
        return runCatching {
            // Application before Activity: guest Activity constructors are
            // allowed to touch the Application's static context.
            GuestRuntime.ensureInitialized(GuestRuntime.hostContext(), guest)
            // Application.onCreate runs before the Activity class is even
            // loaded: 红果 reads its static App.context() from Activity <clinit>
            // and dies with ExceptionInInitializerError otherwise.
            GuestRuntime.startApplication(guest)
            // The proxy is shared by every guest Activity: the real class travels
            // in an extra, so `newActivity` builds whatever the guest asked for.
            // 首页已经露出过之后，壳再把自己拉回启动页是纯空转（每次都会重新盖
            // 启动图），这里直接改写成首页。
            val targetClass = GuestRuntime.redirectSplashIfHomeShown(
                guest,
                intent?.getStringExtra(GuestLauncher.EXTRA_GUEST_ACTIVITY)
                    ?: guest.launcherActivity,
            )
            val clazz = Class.forName(targetClass, true, guest.classLoader)
            GuestRuntime.trace("newActivity loaded ${clazz.name}")
            val activity = clazz.getDeclaredConstructor().newInstance() as Activity
            GuestRuntime.trace("newActivity made ${activity.javaClass.name}")
            GuestRuntime.remember(activity, guest)
            // Guest hook frameworks (Mira) install themselves over us while the
            // app initialises. ActivityThread reads mInstrumentation again for
            // callActivityOnCreate, so take it back before returning — otherwise
            // attach() is skipped and the Activity keeps the host's theme.
            GuestRuntime.ensureHook()
            activity
        }.getOrElse {
            // Fall through to the proxy Activity so the user sees the reason
            // instead of a blank window.
            GuestRuntime.trace("newActivity failed ${it.javaClass.name}: ${it.message}")
            android.util.Log.e("GuestInstrumentation", "guest Activity 创建失败", it)
            super.newActivity(cl, className, intent)
        }
    }

    /**
     * Redirects a guest `startActivity` to the proxy component.
     *
     * Guest apps launch their own Activities all the time (splash → main,
     * privacy dialog, player…). None of those components exist in the host
     * manifest, so AMS would reject them and 红果 answers with
     * `Process.killProcess` (its Timon gate). Rewriting the intent here keeps
     * the launch inside the host while `newActivity` instantiates the real class.
     */
    // `Instrumentation.execStartActivity` is hidden from the SDK stubs, so it
    // cannot be marked `override` — but a method with the same signature still
    // wins the virtual dispatch at runtime. The call is forwarded to [base]
    // reflectively (invoking it on `this` would recurse).
    @Suppress("unused", "UNUSED_PARAMETER")
    fun execStartActivity(
        who: Context?,
        contextThread: IBinder?,
        token: IBinder?,
        target: Activity?,
        intent: Intent?,
        requestCode: Int,
        options: Bundle?,
    ): Instrumentation.ActivityResult? {
        // 首页已在前台时壳还会再要一次启动页：丢掉，别把用户从首页再次丢进白屏。
        if (GuestRuntime.shouldDropSplashRelaunch(intent)) return null
        return forwarded(
            arrayOf<Class<*>>(
                Context::class.java, IBinder::class.java, IBinder::class.java, Activity::class.java,
                Intent::class.java, Int::class.javaPrimitiveType!!, Bundle::class.java,
            ),
            arrayOf(
                who, contextThread, token, target, rewriteForGuest(who, target, intent),
                requestCode, options,
            ),
        )
    }

    @Suppress("unused", "UNUSED_PARAMETER")
    fun execStartActivity(
        who: Context?,
        contextThread: IBinder?,
        token: IBinder?,
        target: Activity?,
        intent: Intent?,
        requestCode: Int,
        options: Bundle?,
        user: UserHandle?,
    ): Instrumentation.ActivityResult? {
        if (GuestRuntime.shouldDropSplashRelaunch(intent)) return null
        return forwarded(
            arrayOf<Class<*>>(
                Context::class.java, IBinder::class.java, IBinder::class.java, Activity::class.java,
                Intent::class.java, Int::class.javaPrimitiveType!!, Bundle::class.java,
                UserHandle::class.java,
            ),
            arrayOf(
                who, contextThread, token, target, rewriteForGuest(who, target, intent),
                requestCode, options, user,
            ),
        )
    }

    private fun forwarded(
        paramTypes: Array<Class<*>>,
        args: Array<Any?>,
    ): Instrumentation.ActivityResult? = runCatching {
        Instrumentation::class.java
            .getMethod("execStartActivity", *paramTypes)
            .apply { isAccessible = true }
            .invoke(base, *args) as Instrumentation.ActivityResult?
    }.getOrElse {
        GuestRuntime.trace("execStartActivity 转发失败：$it")
        null
    }

    private fun rewriteForGuest(@Suppress("UNUSED_PARAMETER") who: Context?, @Suppress("UNUSED_PARAMETER") target: Activity?, intent: Intent?): Intent? =
        intent?.let { GuestRuntime.rewriteForGuest(it) }

    override fun callActivityOnCreate(activity: Activity, icicle: Bundle?) {
        val guest = GuestRuntime.guestOf(activity)
        GuestRuntime.trace(
            "callActivityOnCreate ${activity.javaClass.name} guest=${guest != null}" +
                " icicle=${if (icicle == null) "null" else icicle.keySet().joinToString(",")}",
        )
        guest?.let { GuestRuntime.attach(activity, it) }
        super.callActivityOnCreate(activity, icicle)
        guest?.let { GuestRuntime.onGuestActivityCreated(activity) }
    }

    // Lifecycle traces: the guest's own logs are silenced by its native hooks,
    // so this is the only way to see whether an Activity was started and then
    // finished itself.
    override fun callActivityOnStart(activity: Activity) {
        GuestRuntime.trace("onStart ${activity.javaClass.simpleName}")
        super.callActivityOnStart(activity)
    }

    override fun callActivityOnRestart(activity: Activity) {
        GuestRuntime.trace("onRestart ${activity.javaClass.simpleName}")
        super.callActivityOnRestart(activity)
    }

    override fun callActivityOnResume(activity: Activity) {
        val simpleName = activity.javaClass.simpleName
        GuestRuntime.trace("onResume $simpleName")
        // 每个页面 resume 顺带把崩溃探针抢回来（只在被顶掉时才记一条 trace）。
        GuestRuntime.rearmCrashProbe("onResume $simpleName")
        super.callActivityOnResume(activity)
        if (GuestRuntime.guestOf(activity) != null) {
            GuestRuntime.onGuestActivityResumed(activity)
            GuestRuntime.launchMark("onResume $simpleName")
            // 首页 resume 就是「启动到进首页」的终点；后面的播放页/详情页不再改写结论。
            if (simpleName == "MainFragmentActivity") {
                GuestRuntime.launchDone("首页 onResume")
            }
            // 启动遮罩所在的 gate 任务要等**首页内容铺上**再摘：只等"启动页 resume"
            // 会露出一段纯黑空窗（实测 8~10 秒），首页 resume 时内容往往也还在路上。
            // gate 自己有 20s 超时兜底，不会把用户永久关在启动图里。
            if (simpleName == "MainFragmentActivity") {
                GuestRuntime.dismissLaunchGateWhenReady(activity, "首页内容铺上")
            } else {
                GuestRuntime.trace("gate 保持：$simpleName 还不是首页，等内容铺上再摘")
            }
            // 壳在 onResume 里往主线程塞"退出"消息，先把消息队列守住。
            GuestRuntime.armShellGuard()
            // 主线程此刻正好排到自己：能撤的启动图就现在撤，别等下一次轮询
            // （冷启动后首页数据加载会把主线程占满，轮询实测晚 4 秒多）。
            GuestRuntime.flushPendingOverlay(activity)
        }
        // 加固壳在 onResume 里判定"运行时被动过"，随即把自己 finish 掉。
        // finish 的 binder 请求已经被 ActivityClient 代理拦下，剩下的只是这个
        // 本地标记；清掉它，Activity 就能继续留在前台。
        if (GuestRuntime.guestOf(activity) != null && activity.isFinishing) {
            val cleared = GuestRuntime.clearFinishing(activity)
            GuestRuntime.trace("cleared finishing on ${activity.javaClass.simpleName} -> $cleared")
        }
        // 启动页可能自己走不完（后台初始化任务抛异常被吞掉），到点兜底进首页。
        GuestRuntime.armSplashFallback(activity)
    }

    override fun callActivityOnPostCreate(activity: Activity, icicle: Bundle?) {
        GuestRuntime.trace("onPostCreate ${activity.javaClass.simpleName}")
        super.callActivityOnPostCreate(activity, icicle)
        // 这一层是"guest 已经 attach 完、窗口还没内容"的时刻：冷启动白屏就从这里开始。
        // 启动页盖到窗口销毁；首页等内容铺上再撤（见 GuestRuntime.installLaunchOverlay）。
        GuestRuntime.installLaunchOverlay(activity)
    }

    override fun callActivityOnPause(activity: Activity) {
        GuestRuntime.trace("onPause ${activity.javaClass.simpleName}")
        GuestRuntime.onGuestActivityPaused(activity)
        // onPause 是 finish() 链上的第一站：此刻主线程栈里还留着调用者。
        // 红果的加固壳就是在这里把自己"退掉"的，但系统侧只留下 finish/kill，
        // 看不出是谁先动的手，所以只对"正在 finish 的 guest Activity"抓一次栈。
        if (GuestRuntime.guestOf(activity) != null && activity.isFinishing) {
            GuestRuntime.trace(
                "finish stack (${activity.javaClass.simpleName}):\n" +
                    Thread.currentThread().stackTrace.joinToString("\n") { "    at $it" },
            )
            // 壳把启动页退掉了，但进程还在：直接拉首页，别让用户看到退回宿主。
            GuestRuntime.skipSplash(activity)
        }
        super.callActivityOnPause(activity)
    }

    override fun callActivityOnStop(activity: Activity) {
        GuestRuntime.trace("onStop ${activity.javaClass.simpleName}")
        super.callActivityOnStop(activity)
    }

    override fun callActivityOnDestroy(activity: Activity) {
        GuestRuntime.trace("onDestroy ${activity.javaClass.simpleName}")
        val guest = GuestRuntime.guestOf(activity)
        super.callActivityOnDestroy(activity)
        // 页面全退掉 = 用户主动退出：收尾进程，别让实例继续挂在 RUNNING。
        if (guest != null) GuestRuntime.onGuestActivityDestroyed(activity)
    }

    override fun callActivityOnNewIntent(activity: Activity, intent: Intent?) {
        GuestRuntime.trace("onNewIntent ${activity.javaClass.simpleName}")
        super.callActivityOnNewIntent(activity, intent)
    }

    private fun copyField(name: String) {
        runCatching {
            val field = Instrumentation::class.java.getDeclaredField(name)
                .apply { isAccessible = true }
            field.set(this, field.get(base))
        }
    }
}
