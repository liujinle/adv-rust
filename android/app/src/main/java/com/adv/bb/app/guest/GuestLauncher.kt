package com.adv.bb.app.guest

import android.content.Context
import android.content.Intent
import com.adv.core.data.LaunchRequest
import java.io.File

/**
 * Opens the host-side guest entry for a running instance.
 *
 * Order matters: the instrumentation hook and the guest ClassLoader/Resources
 * must exist *before* `startActivity`, because the framework asks for the
 * Activity synchronously during the launch.
 */
object GuestLauncher {

    const val EXTRA_INSTANCE_ID = "com.adv.bb.app.extra.GUEST_INSTANCE_ID"
    const val EXTRA_HOOK_ERROR = "com.adv.bb.app.extra.HOOK_ERROR"

    /** Guest Activity class to instantiate behind the proxy (defaults to the launcher). */
    const val EXTRA_GUEST_ACTIVITY = "com.adv.bb.app.extra.GUEST_ACTIVITY"

    /** Guest Service class to instantiate behind [GuestServiceProxy]. */
    const val EXTRA_GUEST_SERVICE = "com.adv.bb.app.extra.GUEST_SERVICE"

    /** Intent the guest originally used, restored on the Activity in [GuestRuntime.attach]. */
    const val EXTRA_GUEST_INTENT = "com.adv.bb.app.extra.GUEST_INTENT"

    /**
     * 进程被杀后由 proxy 自己触发重新冷启动的计数（同一个 Intent 链上累加）。
     *
     * 没有它就会死循环：冷启动又失败 → 又恢复 proxy → 又重启。
     */
    const val EXTRA_RESTART_ATTEMPT = "com.adv.bb.app.extra.RESTART_ATTEMPT"

    /** 冷启动计时 t0（epoch 毫秒）：宿主写进 gate / proxy Intent，跨进程贯穿到首页。 */
    const val EXTRA_LAUNCH_START_MS = "com.adv.bb.app.extra.LAUNCH_START_MS"

    private const val EXTRA_GUEST_PACKAGE = "com.adv.bb.app.extra.GUEST_PACKAGE"
    private const val EXTRA_GUEST_LABEL = "com.adv.bb.app.extra.GUEST_LABEL"
    private const val EXTRA_GUEST_APK = "com.adv.bb.app.extra.GUEST_APK"
    private const val EXTRA_GUEST_LAUNCHER = "com.adv.bb.app.extra.GUEST_LAUNCHER"
    private const val EXTRA_GUEST_MIN_SDK = "com.adv.bb.app.extra.GUEST_MIN_SDK"
    private const val EXTRA_GUEST_DATA = "com.adv.bb.app.extra.GUEST_DATA"

    /**
     * 打开 gate 页（[GuestLaunchActivity]），由它先把启动遮罩画出来再真正拉起 guest。
     *
     * 为什么不在这里直接启动：hook + prepare + guest 的 `Application.onCreate` 是
     * **同步**跑在主线程上的（实测 3.4~4.8 秒）。直接启动的话，这 4 秒里屏幕上是
     * 系统按 `Theme.Adv.Guest` 画的启动窗口 —— 实测这台 ROM 只画底色、不画图标，
     * 用户看到的就是一张白纸。gate 是宿主自己的普通 Activity，一帧就能把
     * 「图标 + 应用名 + 转圈」画出来，之后即使主线程被 guest 卡死，画面也留在屏幕上。
     */
    private val launchLock = Any()

    /**
     * 返回 false 表示这次没打开（池满或启动记录写不下去）。调用方要收回本次账本状态。
     * 不同实例不再互相停止；每个槽位只绑定一个实例，池满时明确拒绝，不淘汰活跃实例。
     */
    fun launch(context: Context, request: LaunchRequest): Boolean = synchronized(launchLock) {
        val app = context.applicationContext
        // 计时起点：用户发起启动的那一刻，包含下面的槽位分配与记录落盘。
        val launchStartMs = System.currentTimeMillis()
        if (GuestProcess.moveGuestTaskToFront(app, request.instanceId)) {
            android.util.Log.i("GuestLauncher", "guest task already running, moved to front")
            return@synchronized true
        }

        val recordedSlot = slotForInstance(app, request.instanceId)
        val slot = recordedSlot?.takeIf { candidate ->
            GuestProcess.pids(app, candidate).isEmpty() ||
                recordedInstanceId(app, candidate) == request.instanceId
        } ?: GuestSlots.all.firstOrNull { candidate -> GuestProcess.pids(app, candidate).isEmpty() }
        if (slot == null) {
            GuestRuntime.trace(
                "POOL_EXHAUSTED：${GuestSlots.COUNT} 个槽位都在运行，拒绝启动 ${request.instanceId}",
            )
            return@synchronized false
        }

        // 清掉这个槽位上次死亡留下的任务和记录；其它槽位的任务、进程和记录都不碰。
        GuestProcess.removeGuestTasks(app, slot)
        clearLaunchRecordsForSlot(app, slot)
        if (!saveLaunchRecord(app, request, slot)) {
            GuestRuntime.trace("启动记录写入失败，放弃启动 ${request.instanceId} slot=p$slot")
            return@synchronized false
        }
        val intent = Intent(app, GuestSlots.gateClass(slot))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        putRequest(intent, request)
            .putExtra(EXTRA_LAUNCH_START_MS, launchStartMs)
        return@synchronized runCatching { app.startActivity(intent) }
            .onSuccess {
                GuestRuntime.trace(
                    LaunchClock.beginLine(request.instanceId, launchStartMs, " slot=p$slot"),
                )
                GuestRuntime.trace("实例 ${request.instanceId} 分配槽位 p$slot，gate 已启动")
                android.util.Log.i("GuestLauncher", "gate started in p$slot")
            }
            .onFailure {
                if (GuestProcess.pids(app, slot).isEmpty()) clearLaunchRecord(app, request.instanceId)
                android.util.Log.e("GuestLauncher", "gate start failed", it)
            }
            .isSuccess
    }

    /**
     * 把 [LaunchRequest] 的全部字段写进 Intent。
     *
     * `LaunchRequest` 不是 Parcelable，所以以前只有 gate 的 Intent 带全字段；proxy 的
     * Intent 只带 instanceId，靠 gate 在内存里把 request 递给 `launchFromGate`。
     * guest 搬到 `:guest` 独立进程后这个前提不成立：进程被 AMS 重建时只有 Intent
     * 能留下来，字段缺一个就会恢复成"包名/APK 全空"的诊断页（实测）。
     */
    internal fun putRequest(intent: Intent, request: LaunchRequest): Intent = intent
        .putExtra(EXTRA_INSTANCE_ID, request.instanceId)
        .putExtra(EXTRA_GUEST_PACKAGE, request.packageName)
        .putExtra(EXTRA_GUEST_LABEL, request.displayName)
        .putExtra(EXTRA_GUEST_APK, request.apkPath)
        .putExtra(EXTRA_GUEST_LAUNCHER, request.launcherActivity)
        .putExtra(EXTRA_GUEST_MIN_SDK, request.minSdk)
        .putExtra(EXTRA_GUEST_DATA, request.dataPath)

    /** gate 把遮罩画上屏之后调用：这一步会卡住主线程，直到 guest 的 Application 起来。 */
    internal fun launchFromGate(gate: android.app.Activity, request: LaunchRequest) {
        val attempt = gate.intent?.getIntExtra(EXTRA_RESTART_ATTEMPT, 0) ?: 0
        val launchStartMs = gate.intent?.getLongExtra(EXTRA_LAUNCH_START_MS, 0L) ?: 0L
        GuestRuntime.launchMark("gate 开始拉起 guest")
        runCatching { launchNow(gate.applicationContext, request, attempt, launchStartMs) }
            .onFailure { android.util.Log.e("GuestLauncher", "launch failed", it) }
    }

    /**
     * 进程被 AMS / LMK 杀掉后，从最近任务点回来时走这里：重开 gate 冷启动一次。
     *
     * 恢复出来的是 proxy 自己，而 hook 只存在于当初装它的那个进程里 —— 不重启就
     * 只能停在诊断页。返回 false 表示拉起失败，调用方继续显示诊断页。
     */
    internal fun relaunchFromProxy(
        proxy: android.app.Activity,
        request: LaunchRequest,
        attempt: Int,
    ): Boolean = runCatching {
        val slot = GuestSlots.currentSlot
            ?: slotForInstance(proxy.applicationContext, request.instanceId)
            ?: return@runCatching false
        val intent = putRequest(
            Intent(proxy.applicationContext, GuestSlots.gateClass(slot)),
            request,
        )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            .putExtra(EXTRA_RESTART_ATTEMPT, attempt)
            // 任务卡恢复也是一次全新冷启动，计时从这一刻重新开始。
            .putExtra(EXTRA_LAUNCH_START_MS, System.currentTimeMillis())
        proxy.startActivity(intent)
        proxy.finish()
        true
    }.getOrElse {
        android.util.Log.e("GuestLauncher", "relaunch failed", it)
        false
    }

    /** 每实例一份恢复记录；slot 只是本次运行绑定，instanceId 才是稳定身份。 */
    internal data class GuestLaunchRecord(
        val request: LaunchRequest,
        val slot: Int,
        val updatedAtMs: Long,
        val file: File,
    )

    private fun recordsDir(context: Context) = File(context.filesDir, "guest-launch")

    private fun legacyRecordFile(context: Context) = File(context.filesDir, "guest-launch.json")

    private fun recordFile(context: Context, instanceId: String) =
        File(recordsDir(context), GuestSlotContract.recordFileName(instanceId))

    private fun readLaunchRecord(file: File, legacySlot: Int? = null): GuestLaunchRecord? = runCatching {
        if (!file.isFile) return@runCatching null
        val json = org.json.JSONObject(file.readText())
        val id = json.optString("instanceId")
        val apk = json.optString("apkPath")
        val launcher = json.optString("launcherActivity")
        val slot = json.optInt("slot", legacySlot ?: 0)
        if (id.isBlank() || apk.isBlank() || launcher.isBlank() || slot !in GuestSlots.all) {
            return@runCatching null
        }
        if (!File(apk).isFile) return@runCatching null
        GuestLaunchRecord(
            request = LaunchRequest(
                instanceId = id,
                packageName = json.optString("packageName"),
                displayName = json.optString("displayName"),
                apkPath = apk,
                launcherActivity = launcher,
                minSdk = json.optInt("minSdk"),
                dataPath = json.optString("dataPath"),
            ),
            slot = slot,
            updatedAtMs = json.optLong("updatedAtMs", file.lastModified()),
            file = file,
        )
    }.getOrNull()

    /**
     * 读取全部槽位归属。新版 P0 记录存在时不再叠加旧 `guest-launch.json`，避免升级
     * 前的旧记录把已经复用的 P0 又指回旧实例；只有纯旧版现场才走兼容回读。
     */
    internal fun allLaunchRecords(context: Context): List<GuestLaunchRecord> {
        val records = recordsDir(context).listFiles().orEmpty()
            .filter { it.isFile && it.extension == "json" }
            .mapNotNull { readLaunchRecord(it) }
        if (records.any { it.slot == 0 }) return records
        val legacy = readLaunchRecord(legacyRecordFile(context), legacySlot = 0)
        return if (legacy == null) records else records + legacy
    }

    /** 启动参数必须先可靠落盘再拉起进程，否则 AMS 恢复时无法确定这个槽位属于谁。 */
    private fun saveLaunchRecord(context: Context, request: LaunchRequest, slot: Int): Boolean =
        runCatching {
            val dir = recordsDir(context)
            if (!dir.isDirectory && !dir.mkdirs()) error("创建启动记录目录失败：$dir")
            val target = recordFile(context, request.instanceId)
            val tmp = File(dir, GuestSlotContract.recordTempFileName(request.instanceId))
            val now = System.currentTimeMillis()
            val json = org.json.JSONObject()
                .put("instanceId", request.instanceId)
                .put("packageName", request.packageName)
                .put("displayName", request.displayName)
                .put("apkPath", request.apkPath)
                .put("launcherActivity", request.launcherActivity)
                .put("minSdk", request.minSdk)
                .put("dataPath", request.dataPath)
                .put("slot", slot)
                .put("updatedAtMs", now)
            java.io.FileOutputStream(tmp).use { output ->
                output.write(json.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            if (!tmp.renameTo(target)) error("启动记录 rename 失败：$tmp -> $target")
            true
        }.getOrElse {
            android.util.Log.w("GuestLauncher", "saveLaunchRecord failed", it)
            false
        }

    /** guest Intent 顶掉 extras 后，按当前组件所属槽位找回唯一对应实例。 */
    internal fun requestFromRecord(
        context: Context,
        slot: Int? = GuestSlots.currentSlot,
    ): LaunchRequest? = allLaunchRecords(context)
        .filter { slot == null || it.slot == slot }
        .maxByOrNull { it.updatedAtMs }
        ?.request

    internal fun recordedInstanceId(
        context: Context,
        slot: Int? = GuestSlots.currentSlot,
    ): String? = requestFromRecord(context, slot)?.instanceId

    internal fun slotForInstance(context: Context, instanceId: String): Int? =
        allLaunchRecords(context)
            .filter { it.request.instanceId == instanceId }
            .maxByOrNull { it.updatedAtMs }
            ?.slot

    internal fun clearLaunchRecordsForSlot(context: Context, slot: Int) {
        allLaunchRecords(context).filter { it.slot == slot }.forEach { record ->
            runCatching { record.file.delete() }
        }
    }

    internal fun clearLaunchRecord(context: Context, instanceId: String) {
        allLaunchRecords(context).filter { it.request.instanceId == instanceId }.forEach { record ->
            runCatching { record.file.delete() }
        }
    }

    /**
     * 重建 [LaunchRequest]（gate 的 Intent 里带的是扁平字段，模型本身不是 Parcelable）。
     *
     * `apkPath` / `launcherActivity` 缺一不可：只有 instanceId 的 Intent（旧版 proxy
     * 恢复 / 手工 `am start`）重建出来的 request 会让诊断页全是空值，还会拿空类名去
     * `Class.forName`（`ClassNotFoundException: Invalid name:`）。缺字段时返回 null，
     * 让调用方退回账本查询或直接报错。
     */
    internal fun requestFromIntent(intent: Intent?): LaunchRequest? {
        val id = intent?.getStringExtra(EXTRA_INSTANCE_ID) ?: return null
        val apk = intent.getStringExtra(EXTRA_GUEST_APK).orEmpty()
        val launcher = intent.getStringExtra(EXTRA_GUEST_LAUNCHER).orEmpty()
        if (apk.isBlank() || launcher.isBlank()) return null
        return LaunchRequest(
            instanceId = id,
            packageName = intent.getStringExtra(EXTRA_GUEST_PACKAGE).orEmpty(),
            displayName = intent.getStringExtra(EXTRA_GUEST_LABEL).orEmpty(),
            apkPath = apk,
            launcherActivity = launcher,
            minSdk = intent.getIntExtra(EXTRA_GUEST_MIN_SDK, 0),
            dataPath = intent.getStringExtra(EXTRA_GUEST_DATA).orEmpty(),
        )
    }

    private fun launchNow(
        app: Context,
        request: LaunchRequest,
        attempt: Int = 0,
        launchStartMs: Long = 0L,
    ) {
        // 整条初始化链（hook → prepare → guest Application）由 GuestRuntime 一次
        // 完成，顺序固定且幂等。以前这三步散在这里，第三步在主线程抛异常时会被
        // launchFromGate 的 runCatching 吞掉，startActivity 根本不执行 —— gate 的
        // 「正在启动」遮罩就永远留在屏幕上。现在异常收敛成 ready.error，proxy
        // 至少能起来显示原因。
        GuestRuntime.launchMark("ensureReady 开始")
        val ready = GuestRuntime.ensureReady(app, request)
        GuestRuntime.launchMark(
            "ensureReady 完成 ready=${ready.guest != null} error=${ready.error}",
        )
        android.util.Log.i(
            "GuestLauncher",
            "launch ${request.instanceId} ready=${ready.guest != null} error=${ready.error}",
        )

        // NEW_TASK 单独用会复用宿主 MainActivity 所在的任务（affinity 相同），
        // guest 的启动页因此不是任务根。红果的 SplashActivity 命中
        // `if (!isTaskRoot()) { finish(); return; }` 这个标准写法后直接退出，
        // 表现就是"进得去、几百毫秒后退回桌面"。MULTIPLE_TASK 让代理 Activity
        // 独占一个新任务，isTaskRoot() 才是 true。
        // 全字段写进 Intent：guest 在 `:guest` 独立进程里，进程被 AMS 重建时 gate 早就
        // 不在了，只能靠这份 Intent 把 request 复原。
        val slot = GuestSlots.currentSlot
            ?: slotForInstance(app, request.instanceId)
            ?: error("实例 ${request.instanceId} 没有槽位归属")
        val intent = putRequest(Intent(app, GuestSlots.proxyClass(slot)), request)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            .putExtra(EXTRA_RESTART_ATTEMPT, attempt)
            .putExtra(EXTRA_LAUNCH_START_MS, launchStartMs)
        ready.error?.let { intent.putExtra(EXTRA_HOOK_ERROR, it) }
        GuestRuntime.launchMark("startActivity proxy")
        runCatching { app.startActivity(intent) }
            .onSuccess { android.util.Log.i("GuestLauncher", "startActivity ok") }
            .onFailure { android.util.Log.e("GuestLauncher", "startActivity failed", it) }
    }
}
