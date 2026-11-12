package com.adv.bb.app.guest

import android.os.IBinder
import android.os.IInterface
import android.view.WindowManager
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.LinkedHashMap

/**
 * 单槽位进程内的 Toast 隔离控制器。
 *
 * 一个进程只装一个 guest，所以进程级 INotificationManager / WindowSession 本身已经
 * 把 TN、Handler 和静态状态分开。这里补的是明确的归属和回收：Toast token 与
 * TYPE_TOAST 窗口都登记在当前实例名下，停止/崩溃关机时只 cancel 本进程的 token，
 * 不会误取消另一个槽位的 Toast。系统通知服务仍按宿主 UID 做全局限流，这不是
 * 本控制器能隔离的范畴。
 */
@Suppress("DEPRECATION")
internal class GuestToastController(
    private val notificationService: Any,
    serviceInterface: Class<*>,
    private val instanceId: () -> String?,
    private val trace: (String) -> Unit,
) {
    private val lock = Any()
    private val tokens = LinkedHashMap<IBinder, String>()
    private val toastWindows = LinkedHashSet<Any>()

    @Volatile
    private var closing = false

    @Volatile
    private var windowSession: Any? = null

    /** 兼容两参数旧签名与带 userId/displayId 的厂商扩展签名。 */
    private val cancelToastMethod: Method? = serviceInterface.methods.firstOrNull {
        it.name == "cancelToast" &&
            it.parameterTypes.size >= 2 &&
            it.parameterTypes[0] == String::class.java &&
            it.parameterTypes[1] == IBinder::class.java
    }?.apply { isAccessible = true }

    private val identity: String
        get() = "p${GuestSlots.currentSlot ?: 0}/${instanceId() ?: "unknown"}"

    fun observeEnqueue(method: Method, args: Array<out Any?>?) {
        val text = args.orEmpty()
            .filterIsInstance<CharSequence>()
            .joinToString(" | ")
            .take(200)
        trace("toast[$identity] ${method.name} $text")
        val pkg = args.orEmpty().firstOrNull { it is String } as? String
        val token = args.orEmpty().firstOrNull { it is IBinder } as? IBinder
        if (pkg == null || token == null) return
        synchronized(lock) {
            if (!closing) {
                tokens[token] = pkg
                trim(tokens)
            }
        }
    }

    fun observeCancel(args: Array<out Any?>?) {
        val token = args.orEmpty().firstOrNull { it is IBinder } as? IBinder ?: return
        synchronized(lock) { tokens.remove(token) }
    }

    /**
     * WindowSession 代理在每次 add/remove 前调用。只登记 TYPE_TOAST 的 IWindow，
     * 普通 Activity / Dialog 窗口仍由原来的窗口生命周期管理。
     */
    fun observeWindow(session: Any, method: Method, args: Array<out Any?>?) {
        windowSession = session
        val layoutParams = args.orEmpty()
            .firstOrNull { it is WindowManager.LayoutParams } as? WindowManager.LayoutParams
        val window = args.orEmpty().firstOrNull(::isWindow)
        if (method.name.startsWith("addToDisplay") &&
            layoutParams?.type == WindowManager.LayoutParams.TYPE_TOAST &&
            window != null
        ) {
            synchronized(lock) {
                if (!closing) {
                    toastWindows.add(window)
                    while (toastWindows.size > MAX_TRACKED) {
                        toastWindows.remove(toastWindows.first())
                    }
                }
            }
        } else if (method.name == "remove" && window != null) {
            synchronized(lock) { toastWindows.remove(window) }
        }
    }

    /** 停止当前实例时回收系统 Toast 队列项和本地窗口；其它槽位完全不在表内。 */
    fun cancelAll(reason: String) {
        val pendingTokens: List<Pair<IBinder, String>>
        val pendingWindows: List<Any>
        val session = windowSession
        synchronized(lock) {
            closing = true
            pendingTokens = tokens.map { it.key to it.value }
            pendingWindows = toastWindows.toList()
            tokens.clear()
            toastWindows.clear()
        }
        pendingTokens.forEach { (token, pkg) -> cancelToken(pkg, token) }
        pendingWindows.forEach { window -> removeWindow(session, window) }
        trace("toast[$identity] cancelAll($reason) tokens=${pendingTokens.size} windows=${pendingWindows.size}")
    }

    private fun cancelToken(pkg: String, token: IBinder) {
        val method = cancelToastMethod ?: run {
            trace("toast[$identity] 未找到 cancelToast 签名")
            return
        }
        val args = Array<Any?>(method.parameterTypes.size) { index ->
            when (index) {
                0 -> pkg
                1 -> token
                else -> defaultValue(method.parameterTypes[index])
            }
        }
        runCatching { method.invoke(notificationService, *args) }
            .onFailure { error ->
                val cause = (error as? InvocationTargetException)?.targetException ?: error
                trace("toast[$identity] cancelToast 失败：${cause.javaClass.simpleName}")
            }
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }

    private fun removeWindow(session: Any?, window: Any) {
        if (session == null) return
        val method = runCatching {
            session.javaClass.methods.firstOrNull {
                it.name == "remove" && it.parameterTypes.size == 1
            } ?: Class.forName("android.view.IWindowSession").methods.first {
                it.name == "remove" && it.parameterTypes.size == 1
            }
        }.getOrNull() ?: return
        runCatching {
            method.isAccessible = true
            method.invoke(session, window)
        }.onFailure { error ->
            val cause = (error as? InvocationTargetException)?.targetException ?: error
            trace("toast[$identity] remove window 失败：${cause.javaClass.simpleName}")
        }
    }

    private fun isWindow(value: Any?): Boolean = runCatching {
        value is IInterface && value.asBinder() != null
    }.getOrDefault(false)

    private fun trim(map: LinkedHashMap<IBinder, String>) {
        while (map.size > MAX_TRACKED) map.remove(map.keys.first())
    }

    private companion object {
        const val MAX_TRACKED = 32
    }
}
