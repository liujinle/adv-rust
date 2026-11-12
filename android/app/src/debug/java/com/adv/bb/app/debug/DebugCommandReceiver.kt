package com.adv.bb.app.debug

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.adv.bb.app.AdvApplication
import com.adv.bb.app.AppContainer
import com.adv.bb.app.guest.GuestControlReceiver
import com.adv.bb.app.guest.GuestLauncher
import com.adv.bb.app.guest.GuestProcess
import com.adv.bb.app.guest.GuestRuntime
import com.adv.bb.app.guest.GuestSlots
import com.adv.core.common.EngineResult
import com.adv.core.data.InstanceTag
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID

/**
 * 调试命令入口，**只在 debug 变体里存在**（`src/debug`）。
 *
 * 目的：改一行 guest 运行时代码就要验证时，不必再"进应用 → 应用库 → 详情 →
 * 安装 → 实例页 → 启动 → 点隐私弹窗"走一遍 UI。宿主机侧用 `tools/bb-cli.ps1`
 * 发一条广播即可，结果写到 `files/cli-out.txt` 由脚本读回。
 *
 * 协议：
 * ```
 * adb shell am broadcast -a com.adv.bb.DEBUG_CMD -p com.adv.bb.app \
 *     --es cmd launch --es arg_b64 <utf8-base64>
 * ```
 * 中文/空格一律走 `arg_b64`：`adb shell` 对非 ASCII 的转义不可靠，base64 才稳定。
 * `arg` 仅用于纯 ASCII 的选择器。
 *
 * 命令：
 * - `list` / `ls`      实例列表（id / 实例名 / 短 id / 版本 / 包名 / 状态）
 * - `apps`             已导入应用（名称 / 包名 / 版本 / 实例数）
 * - `rename <选择器>|<新名称>` 重命名实例，同包同版本时用它给两个实例起不同的名字
 * - `launch <选择器>`   启动实例；选择器 = 实例 id 前缀 / `#短id` / 包名 / 实例名片段
 * - `stop <选择器>`     停止实例
 * - `cleardata <选择器>` 清空实例的虚拟数据目录（实例保留，等价 UI 的「清数据」）
 * - `rm <选择器>`       卸载实例（数据 + 备份 + 操作记录一起删，等价 UI 的「卸载」）
 * - `state`            引擎状态
 * - `procs`            宿主 + P0~P3 槽位归属视图
 * - `killguest [选择器]` 不动账本，只杀指定槽位（不传=全池）
 * - `toast <选择器>`   在指定槽位触发 Toast，验证隔离与日志归属
 * - `trace [n]`        聚合 guest-trace*.log 末尾 n 行（默认 120）
 * - `cleartrace`       删掉所有 guest-trace*.log（每轮验证前清一次）
 * - `help`             命令列表
 */
class DebugCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val pending = goAsync()
        val cmd = intent.getStringExtra(EXTRA_CMD).orEmpty().trim().lowercase()
        val arg = DebugCommands.decode(intent.getStringExtra(EXTRA_ARG_B64))
            ?: intent.getStringExtra(EXTRA_ARG)
        // 广播回调在主线程序列里，命令要等引擎/磁盘，必须挪出去。
        Thread({
            val output = try {
                execute(context, cmd.ifBlank { "help" }, arg)
            } catch (error: Throwable) {
                "ERR ${error.javaClass.name}: ${error.message}"
            }
            DebugCommands.write(context, output)
            Log.i(TAG, output.take(4000))
            pending.finish()
        }, "bb-cli").start()
    }

    private fun execute(context: Context, cmd: String, arg: String?): String {
        val app = context.applicationContext as? AdvApplication
            ?: return "ERR applicationContext 不是 AdvApplication"
        // 进程刚被广播拉起时 Application.onCreate 可能还在跑。
        if (!awaitApplication(app)) return "ERR 宿主还没有初始化完成，稍后重试"
        val container = app.container
        return runBlocking {
            when (cmd) {
                "help", "" -> HELP
                "state" -> state(container)
                "procs", "guest" -> procs(context)
                "killguest" -> killGuest(context, container, arg)
                "toast" -> toast(context, container, arg)
                "apps" -> apps(container)
                "list", "ls" -> instances(container)
                "rename" -> rename(container, arg)
                "launch" -> launch(container, arg)
                "import", "imp" -> importApk(context, container, arg)
                "rm", "remove", "uninstall" -> remove(container, arg)
                "cleardata", "wipe" -> clearData(container, arg)
                "stop" -> stop(container, arg)
                "trace" -> trace(context, arg)
                "cleartrace", "clear" -> clearTrace(context)
                "classes" -> classes(arg)
                "methods" -> methods(arg)
                "factory" -> factory(arg)
                "dismiss" -> dismiss()
                else -> "ERR 未知命令：$cmd\n$HELP"
            }
        }
    }

    /**
     * 广播可能比 `Application.onCreate` 先到（冷启动），这里等一小会儿。
     *
     * 只能等 `containerOrNull`：接收器固定跑在主进程，而 `:guest` 进程按双账本
     * 原则根本不建 AppContainer。
     */
    private fun awaitApplication(app: AdvApplication): Boolean {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (app.containerOrNull != null) return true
            Thread.sleep(50)
        }
        return app.containerOrNull != null
    }

    private fun state(container: AppContainer): String {
        val engine = container.coordinator.state.value
        return "engine=${engine.name}(${engine.label})"
    }

    /**
     * 模拟 LMK / AMS 杀进程：**只关进程和任务卡，完全不动账本**。
     * 不传选择器保留旧语义（关闭全池）；传选择器时只杀该实例所在槽位，其它实例不受影响。
     */
    private suspend fun killGuest(context: Context, container: AppContainer, arg: String?): String {
        if (arg.isNullOrBlank()) {
            val before = GuestProcess.poolSnapshot(context)?.running.orEmpty()
            if (before.isEmpty()) return "OK 进程池本来就没在跑（账本未改）"
            val gone = GuestProcess.shutdown(context)
            return if (gone) {
                "OK 已杀掉全池（pid=${before.joinToString(",") { it.pid.toString() }}），账本保持原样，等待 5s 对账"
            } else {
                "ERR 仍有槽位关不掉（${before.joinToString(",") { "p${it.slot}:${it.pid}" }}）"
            }
        }
        val instance = DebugCommands.resolve(container, arg)
            ?: return "ERR 找不到实例：$arg"
        val slot = GuestLauncher.slotForInstance(context, instance.instanceId)
            ?: return "ERR 实例 ${instance.instanceId} 没有运行槽位（账本未改）"
        val before = GuestProcess.pids(context, slot)
        if (before.isEmpty()) return "OK p$slot 本来就没在跑（账本未改）"
        val gone = GuestProcess.shutdown(context, instance.instanceId)
        return if (gone) {
            "OK 已杀掉 p$slot（pid=${before.joinToString(",")}），账本保持原样，等待 5s 对账"
        } else {
            "ERR p$slot 关不掉（pid=${before.joinToString(",")}）"
        }
    }

    /** 宿主与 P0~P3 全池进程视图，输出每个槽位的 pid 和持久归属实例。 */
    private fun procs(context: Context): String {
        val snapshot = GuestProcess.poolSnapshot(context)
        val pool = GuestSlots.all.joinToString("\n") { slot ->
            val running = snapshot?.running?.filter { it.slot == slot }.orEmpty()
            val pids = running.joinToString(",") { it.pid.toString() }.ifBlank { "-" }
            val ids = running.joinToString(",") { it.instanceId ?: "UNKNOWN" }.ifBlank { "-" }
            "p$slot ${GuestSlots.processName(context.packageName, slot)} pid=$pids instance=$ids"
        }
        val prefix = if (snapshot == null) "pool=UNKNOWN（进程表读取失败）\n" else ""
        return "host pid=${android.os.Process.myPid()} process=${context.packageName}\n$prefix$pool"
    }

    private suspend fun apps(container: AppContainer): String {
        val rows = withTimeoutOrNull(5_000) { container.observeApps().first() }.orEmpty()
        if (rows.isEmpty()) return "（还没有导入任何 APK）"
        return rows.joinToString("\n") { app ->
            "${app.displayName}\t${app.packageName}\t${app.versionName ?: app.versionCode}" +
                "\t${formatBytes(app.fileSizeBytes)}\t实例 ${app.instanceCount}"
        }
    }

    /**
     * 同 APK 同版本时 `appName` 全部一样，所以这里输出用户改过的 `displayName`、
     * 稳定的 `#短id` 和版本；三者都能当选择器（见 [DebugCommands.resolve]）。
     */
    private suspend fun instances(container: AppContainer): String {
        val rows = withTimeoutOrNull(5_000) { container.observeInstances().first() }.orEmpty()
        if (rows.isEmpty()) return "（没有实例）"
        return rows.joinToString("\n") { row ->
            "${row.instanceId}\t${row.displayName}\t${InstanceTag.hash(row.instanceId)}" +
                "\tv${row.versionLabel ?: "?"}\t${row.packageName}\t${row.state}" +
                (row.lastError?.let { "\t$it" } ?: "")
        }
    }

    /** 给实例改名：`<选择器>|<新名称>`，用于把两个同名同版本实例区分开。 */
    private suspend fun rename(container: AppContainer, arg: String?): String {
        val parts = arg?.split('|', limit = 2)
        val selector = parts?.getOrNull(0)?.trim()
        val name = parts?.getOrNull(1)?.trim()
        if (selector.isNullOrBlank() || name.isNullOrBlank()) {
            return "ERR 用法：rename <选择器> <新名称>（例：rename ad6446c5 红果-主号）"
        }
        val instance = DebugCommands.resolve(container, selector)
            ?: return "ERR 找不到实例：$selector"
        val renamed = container.instanceActions.rename(instance.instanceId, name)
        renamed.errorOrNull()?.let { return "ERR 重命名失败：${it.userMessage}" }
        return "OK 已重命名 ${InstanceTag.hash(instance.instanceId)} → $name (${instance.instanceId})"
    }

    /**
     * 广播版启动：只在宿主已经在前台时可用。
     *
     * Android 10+ 禁止后台进程启动 Activity，宿主在桌面时这条路径会被系统静默
     * 丢弃（命令报成功、前台还是桌面）。脚本里的 `launch` 走
     * [DebugLaunchActivity]；这里留着是给"已经停在应用里、临时换实例"的场景。
     */
    private suspend fun launch(container: AppContainer, arg: String?): String {
        if (arg.isNullOrBlank()) return "ERR 需要选择器：实例 id 前缀 / 包名 / 名称片段"
        DebugCommands.awaitEngine(container)
        val instance = DebugCommands.resolve(container, arg)
            ?: return "ERR 找不到实例：$arg"
        val started = container.instanceActions.launch(instance.instanceId)
        val request = started.valueOrNull()
            ?: return "ERR 启动失败：${started.errorOrNull()?.userMessage}" +
                " (${started.errorOrNull()?.detail})"
        // 和 UI 一样在主线程开 guest 入口：installHook/prepare 都在这一步。
        Handler(Looper.getMainLooper()).post {
            runCatching { container.openGuest(request) }
                .onSuccess { opened ->
                    if (!opened) {
                        // 另一个实例在跑且关不掉：openGuest 已经把账本收回 STOPPED。
                        Log.e(TAG, "openGuest 未打开（上一个实例没关掉）")
                    }
                }
                .onFailure { Log.e(TAG, "openGuest 失败", it) }
        }
        return "OK 已启动 ${instance.appName} (${instance.instanceId})（宿主须在前台）"
    }

    /**
     * 导入一个 APK 并顺手建实例（等价于 UI 的「选文件 → 导入 → 安装」）。
     *
     * 路径必须是**宿主进程读得到的**绝对路径：`/sdcard/Download` 这类公共目录
     * 在 scoped storage 下会被拒（宿主没申请存储权限），要先把 APK 放到
     * `/sdcard/Android/data/com.adv.bb.app/files/` 再用绝对路径传进来；传进来的
     * 文件会被 [stageInternal] 复制到内部 `files/imported-apks/`，见那里的说明。
     *
     * 233 MB 的包要算 sha256 + 解析清单，十几秒起步，所以先把"进行中"写进结果
     * 文件，脚本靠它区分"还没开始"和"还在跑"。
     */
    private suspend fun importApk(
        context: Context,
        container: AppContainer,
        arg: String?,
    ): String {
        if (arg.isNullOrBlank()) return "ERR 需要 APK 路径（绝对路径，或宿主有权限的 content:// URI）"
        val path = arg.trim()
        if (!path.contains("://") && !File(path).isFile) {
            return "ERR 路径不存在或读不到：$path"
        }
        DebugCommands.write(context, "进行中 导入 $path")
        DebugCommands.awaitEngine(container)
        val apkPath = if (path.contains("://")) path else stageInternal(context, File(path)).absolutePath
        return when (val installed = container.installApk(apkPath)) {
            is EngineResult.Failure ->
                "ERR 导入失败：${installed.error.userMessage} (${installed.error.detail})"
            is EngineResult.Success -> {
                val result = installed.value
                val app = result.app
                val instanceId = result.instanceId
                if (instanceId == null) {
                    "OK 已存在同版本 ${app.displayName} v${app.versionName ?: app.versionCode}" +
                        "，实例 ${result.existingInstances} 个（未新建）"
                } else {
                    "OK 已导入 ${app.displayName} v${app.versionName ?: app.versionCode}" +
                        " (${app.packageName}) 实例 $instanceId apk=${app.apkPath}"
                }
            }
        }
    }

    /**
     * 把外部路径的 APK 复制进 `files/imported-apks/` 并去掉写位。
     *
     * Android 14+ 的 `DexClassLoader` 只接受**不可写**的 dex/APK，否则
     * `SecurityException: Writable dex file ... is not allowed`。外部存储
     * （`/sdcard/...`）永远"可写"，`setReadOnly()` 在 FUSE 上不生效，所以走
     * CLI 导入时必须先落到内部目录 —— 这和 UI 导入（SAF 给 content://，由
     * `ApkStaging` 复制一份）最终落点是同一个目录。
     */
    private fun stageInternal(context: Context, source: File): File {
        val dir = File(context.filesDir, "imported-apks").apply { mkdirs() }
        if (source.parentFile?.absolutePath == dir.absolutePath) return source
        val target = File(dir, "${UUID.randomUUID()}.apk")
        try {
            source.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
            if (target.canWrite()) target.setReadOnly()
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
        return target
    }

    /** 卸载实例（含 dataDir / 备份 / 操作记录），等价 UI 里的「卸载」。 */
    private suspend fun remove(container: AppContainer, arg: String?): String {
        if (arg.isNullOrBlank()) return "ERR 需要选择器：实例 id 前缀 / 包名 / 名称片段"
        val instance = DebugCommands.resolve(container, arg)
            ?: return "ERR 找不到实例：$arg"
        val removed = container.instanceActions.uninstall(instance.instanceId)
        removed.errorOrNull()?.let { return "ERR 卸载失败：${it.userMessage} (${it.detail})" }
        return "OK 已卸载 ${instance.appName} (${instance.instanceId})，记录已移除"
    }

    /** 清数据（等价 UI 里的「清数据」）：实例保留，虚拟数据目录清空重建。 */
    private suspend fun clearData(container: AppContainer, arg: String?): String {
        if (arg.isNullOrBlank()) return "ERR 需要选择器：实例 id 前缀 / 包名 / 名称片段"
        val instance = DebugCommands.resolve(container, arg)
            ?: return "ERR 找不到实例：$arg"
        val cleared = container.instanceActions.clearData(instance.instanceId)
        cleared.errorOrNull()?.let { return "ERR 清数据失败：${it.userMessage} (${it.detail})" }
        return "OK 已清除 ${instance.appName} (${instance.instanceId}) 的数据，实例保留"
    }

    private suspend fun stop(container: AppContainer, arg: String?): String {
        if (arg.isNullOrBlank()) return "ERR 需要选择器：实例 id 前缀 / 包名 / 名称片段"
        val instance = DebugCommands.resolve(container, arg)
            ?: return "ERR 找不到实例：$arg"
        val stopped = container.instanceActions.stop(instance.instanceId)
        stopped.errorOrNull()?.let { return "ERR 停止失败：${it.userMessage}" }
        return "OK 已停止 ${instance.appName} (${instance.instanceId})"
    }

    /** 只向实例当前槽位的显式 receiver 发 Toast 动作，绝不广播到其它槽位。 */
    private suspend fun toast(context: Context, container: AppContainer, arg: String?): String {
        val value = arg?.takeIf { it.isNotBlank() }
            ?: return "ERR 需要选择器：实例 id 前缀 / 包名 / 名称片段"
        val parts = value.split('|', limit = 2)
        val selector = parts[0].trim()
        val text = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "Toast 隔离测试"
        val instance = DebugCommands.resolve(container, selector)
            ?: return "ERR 找不到实例：$selector"
        val slot = GuestLauncher.slotForInstance(context, instance.instanceId)
            ?: return "ERR 实例 ${instance.instanceId} 没有运行槽位"
        val pids = GuestProcess.pids(context, slot)
        if (pids.isEmpty()) return "ERR p$slot 进程没有在跑（先 launch ${instance.instanceId}）"
        val marked = "[p$slot ${instance.instanceId.take(8)}] $text"
        context.sendBroadcast(
            Intent(GuestControlReceiver.ACTION_TEST_TOAST)
                .setComponent(
                    ComponentName(context.packageName, GuestSlots.receiverClassName(slot)),
                )
                .putExtra(GuestControlReceiver.EXTRA_TOAST_TEXT, marked)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
        )
        return "OK 已向 p$slot 发送 Toast 测试（pid=${pids.joinToString(",")}）：$marked"
    }

    private fun traceFiles(context: Context): List<File> =
        context.filesDir.listFiles { _, name ->
            name == TRACE_FILE || (name.startsWith("guest-trace-p") && name.endsWith(".log"))
        }.orEmpty().sortedBy { it.name }

    /** 按槽位聚合诊断日志；P0 的旧文件名继续保留。 */
    private fun trace(context: Context, arg: String?): String {
        val files = traceFiles(context)
        if (files.isEmpty()) return "（还没有 guest-trace*.log）"
        val limit = arg?.trim()?.toIntOrNull()?.coerceIn(1, 4000) ?: 120
        return files.joinToString("\n\n") { file ->
            val lines = file.readLines()
            "== ${file.name} 共 ${lines.size} 行，末 $limit 行 ==\n" +
                lines.takeLast(limit).joinToString("\n")
        }
    }

    /** 按片段在 guest dex 类清单里找类，并试 `Class.forName`（不带初始化）。 */
    private fun classes(arg: String?): String {
        val pattern = arg?.trim()
        if (pattern.isNullOrBlank()) return "ERR 需要一个类名片段（正则）：classes TTVideo"
        return GuestRuntime.inspectClasses(pattern, limit = 60)
    }

    /** 打印 guest 类的构造器 / 方法签名，用来确认混淆入口的真实参数表。 */
    private fun methods(arg: String?): String {
        val className = arg?.trim()
        if (className.isNullOrBlank()) return "ERR 需要完整类名：methods xt0.a"
        return GuestRuntime.inspectMethods(className)
    }

    /** 关掉 guest 的强制弹窗（返回键 / 点遮罩都关不掉的那种）。 */
    private fun dismiss(): String = GuestRuntime.dismissDialogs()

    /** 自检组件工厂的回落路径（解析 guest 类 + 无参构造 + 类型判定）。 */
    private fun factory(arg: String?): String {
        val className = arg?.trim()
        if (className.isNullOrBlank()) return "ERR 需要完整类名：factory to0.c"
        return GuestRuntime.probeFactory(className)
    }

    private fun clearTrace(context: Context): String {
        val files = traceFiles(context)
        files.forEach { file -> runCatching { file.delete() } }
        return if (files.isEmpty()) {
            "（本来就没有 guest-trace*.log）"
        } else {
            "OK 已清空 ${files.size} 个日志：${files.joinToString(",") { it.name }}"
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> "%.1f KB".format(bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }

    companion object {
        const val ACTION = "com.adv.bb.DEBUG_CMD"
        const val EXTRA_CMD = "cmd"
        const val EXTRA_ARG = "arg"
        const val EXTRA_ARG_B64 = "arg_b64"

        /** 脚本读回结果的文件（[DebugCommands.OUT_FILE]），debug 包可 run-as cat。 */
        const val OUT_FILE = DebugCommands.OUT_FILE
        private const val TRACE_FILE = "guest-trace.log"
        private const val TAG = "BbCli"

        private val HELP = """
            命令：list | apps | import <apk路径> | launch <选择器> | stop <选择器> | cleardata <选择器> | rm <选择器> | rename <选择器>|<新名称> | state | procs | killguest [选择器] | toast <选择器>[|文本] | trace [n] | launchtime | cleartrace | classes <片段> | methods <类名> | factory <类名> | help
            选择器：实例 id（可前缀）/ #短id / 包名 / 实例名片段；同包同版本多实例请用 #短id 或先 rename。
            list 输出完整 id / 实例名 / #短id / 版本 / 包名 / 状态，launchtime 输出启动到首页的分段耗时。
            procs 输出 P0~P3，toast 只发往该实例所属槽位，trace/cleartrace 聚合所有槽位日志。
            用法：adb shell am broadcast -a com.adv.bb.DEBUG_CMD -p com.adv.bb.app --es cmd launch --es arg_b64 <base64>
            注意：import 的 APK 必须宿主读得到（放 /sdcard/Android/data/com.adv.bb.app/files/ 再用绝对路径）
        """.trimIndent()
    }
}
