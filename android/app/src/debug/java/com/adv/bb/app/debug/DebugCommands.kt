package com.adv.bb.app.debug

import android.content.Context
import com.adv.bb.app.AppContainer
import com.adv.core.data.EngineRunState
import com.adv.core.data.InstanceOverview
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Base64

/**
 * debug 命令的公共部分：结果落盘、参数解码、引擎等待、实例选择器解析。
 *
 * 广播（[DebugCommandReceiver]）和 Activity（[DebugLaunchActivity]）都要用：
 * 前者适合查状态（不打扰前台），后者适合"启动"——Android 10+ 禁止后台进程
 * 启动 Activity，只有由 shell 拉起的 Activity 才能继续 startActivity 到 guest。
 */
internal object DebugCommands {

    /** 结果文件，`files/` 下，脚本用 `run-as cat` 读回。 */
    const val OUT_FILE = "cli-out.txt"

    fun write(context: Context, text: String) {
        runCatching { File(context.filesDir, OUT_FILE).writeText(text) }
    }

    /** `adb shell` 对非 ASCII 转义不可靠，中文参数一律 UTF-8 base64 传。 */
    fun decode(value: String?): String? = value?.let {
        runCatching { String(Base64.getDecoder().decode(it), Charsets.UTF_8) }.getOrNull()
    }

    suspend fun awaitEngine(container: AppContainer) {
        if (container.coordinator.state.value == EngineRunState.READY) return
        withTimeoutOrNull(15_000) {
            container.coordinator.state.first { it == EngineRunState.READY }
        }
    }

    /**
     * 选择器：完整 id / id 前缀 / `#短id` / 包名（精确或片段）/ 实例自定义名 / 应用名片段。
     *
     * 同 APK 同版本多实例时 `appName` 全都一样，必须让 `displayName`（含「副本 N」或
     * 用户重命名）和 `list` 里显示的 `#短id` 也能当选择器，否则看得见却选不中。
     */
    suspend fun resolve(container: AppContainer, selector: String): InstanceOverview? {
        val needle = selector.trim()
        if (needle.isEmpty()) return null
        val tag = needle.removePrefix("#")
        val rows = withTimeoutOrNull(5_000) { container.observeInstances().first() }.orEmpty()
        return rows.firstOrNull { it.instanceId == needle }
            ?: rows.firstOrNull { it.instanceId.startsWith(needle, ignoreCase = true) }
            ?: rows.firstOrNull { tag.isNotEmpty() && it.shortId.equals(tag, ignoreCase = true) }
            ?: rows.firstOrNull { it.packageName.equals(needle, ignoreCase = true) }
            ?: rows.firstOrNull { it.packageName.contains(needle, ignoreCase = true) }
            ?: rows.firstOrNull { it.displayName.contains(needle, ignoreCase = true) }
            ?: rows.firstOrNull { it.appName.contains(needle, ignoreCase = true) }
    }
}
