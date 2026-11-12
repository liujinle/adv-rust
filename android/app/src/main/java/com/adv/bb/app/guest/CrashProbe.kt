package com.adv.bb.app.guest

import android.content.Context
import java.io.File

/**
 * 进程级 native 崩溃探针。
 *
 * 红果的加固壳走到退出分支时是 native SIGSEGV（`ApplicationExitInfo`:
 * reason=5 / status=11），Java 侧完全看不到；`ApplicationExitInfo` 给的
 * trace 是 protobuf，只能看到"崩在 `BasicRouteMonit` 线程"和一堆 JIT 帧。
 * 这里在 native 层接管 `SIGSEGV/SIGBUS/SIGABRT/SIGILL/SIGFPE`，把
 * pc / lr / 出错地址 / 一段 `_Unwind_Backtrace` 连同 `/proc/self/maps`
 * 解析结果写进 `files/native-crash.log`，然后照常让进程死掉
 * （信号改回 SIG_DFL 再 raise），不改变系统侧的行为。
 */
object CrashProbe {

    private const val LIBRARY = "crashprobe"

    private var loaded = false

    /**
     * 安装（或重新安装）信号处理器。
     *
     * 需要在两处调用：宿主启动时一次，guest 的 Application.onCreate 之后再一次 ——
     * 加固壳初始化时会自己装一批信号处理器，晚装的那一方才能生效。
     */
    fun install(context: Context) {
        runCatching {
            if (!loaded) {
                System.loadLibrary(LIBRARY)
                loaded = true
            }
            val log = File(context.filesDir, GuestSlots.nativeCrashFileName(GuestSlots.currentSlot))
            nativeInstall(log.absolutePath)
        }
    }

    private external fun nativeInstall(path: String): Boolean
}
