package com.adv.bb.app.guest

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Process

/**
 * guest 槽位进程的生命周期工具。账本仍只由主进程写；这里只管理 OS 进程、任务卡和
 * 持久恢复记录，所有按实例操作都先解析 instanceId -> slot，绝不按包名跨实例停止。
 */
object GuestProcess {

    /** P0 的旧 manifest 进程后缀；P1+ 使用 `:pN`。 */
    const val SUFFIX = GuestSlotContract.LEGACY_PROCESS_SUFFIX

    data class RunningGuest(
        val slot: Int,
        val pid: Int,
        val processName: String,
        val instanceId: String?,
    )

    data class PoolSnapshot(
        val running: List<RunningGuest>,
    ) {
        /** 有进程却读不到归属时是 UNKNOWN，看护不能把 UNKNOWN 当成空集合判死。 */
        val complete: Boolean get() = running.all { it.instanceId != null }
        val aliveInstanceIds: Set<String> get() = running.mapNotNull { it.instanceId }.toSet()
    }

    fun isGuestProcess(): Boolean = GuestSlots.currentSlot != null

    private fun runningProcesses(context: Context) =
        context.getSystemService(ActivityManager::class.java)?.runningAppProcesses

    fun pids(context: Context): List<Int> = runningProcesses(context).orEmpty()
        .filter { GuestSlots.fromProcessName(it.processName) != null }
        .map { it.pid }

    fun pids(context: Context, slot: Int): List<Int> {
        val target = GuestSlots.processName(context.packageName, slot)
        return runningProcesses(context).orEmpty()
            .filter { it.processName == target }
            .map { it.pid }
    }

    /** null = 进程表读取失败；complete=false = 进程表可读但至少一条归属未知。 */
    fun poolSnapshot(context: Context): PoolSnapshot? {
        val processes = runningProcesses(context) ?: return null
        val running = processes.mapNotNull { process ->
            val slot = GuestSlots.fromProcessName(process.processName) ?: return@mapNotNull null
            RunningGuest(
                slot = slot,
                pid = process.pid,
                processName = process.processName,
                instanceId = GuestLauncher.recordedInstanceId(context, slot),
            )
        }.sortedBy { it.slot }
        return PoolSnapshot(running)
    }

    /** 兼容旧调用：不带实例就是关闭全部槽位。 */
    fun shutdown(context: Context, timeoutMs: Long = 3_000): Boolean =
        GuestSlots.all.all { slot -> shutdownSlot(context, slot, timeoutMs) }

    /** 只停止指定实例；没有归属记录表示它不持有运行槽位，其它实例不受影响。 */
    fun shutdown(context: Context, instanceId: String, timeoutMs: Long = 3_000): Boolean {
        val slot = GuestLauncher.slotForInstance(context, instanceId)
        if (slot == null) {
            GuestLauncher.clearLaunchRecord(context, instanceId)
            return true
        }
        return shutdownSlot(context, slot, timeoutMs)
    }

    private fun shutdownSlot(context: Context, slot: Int, timeoutMs: Long): Boolean {
        // 先摘任务再退进程，避免 AMS 按残留 Activity 栈立刻重建同名进程。
        removeGuestTasks(context, slot)
        val before = pids(context, slot)
        if (before.isEmpty()) {
            GuestLauncher.clearLaunchRecordsForSlot(context, slot)
            return true
        }
        runCatching {
            context.sendBroadcast(
                Intent(GuestControlReceiver.ACTION_SHUTDOWN)
                    .setComponent(ComponentName(context.packageName, GuestSlots.receiverClassName(slot)))
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
            )
        }
        var gone = awaitGone(context, slot, timeoutMs)
        if (!gone) {
            GuestRuntime.trace("p$slot 关机广播未生效，强杀 ${pids(context, slot)}")
            pids(context, slot).forEach { pid -> runCatching { Process.killProcess(pid) } }
            gone = awaitGone(context, slot, 1_500)
        }
        if (gone) GuestLauncher.clearLaunchRecordsForSlot(context, slot)
        return gone
    }

    /** 同实例打开只移动它自己的任务；归属仍只认按槽位保存的恢复记录。 */
    internal fun moveGuestTaskToFront(context: Context, instanceId: String): Boolean {
        val slot = GuestLauncher.slotForInstance(context, instanceId) ?: return false
        if (pids(context, slot).isEmpty()) return false
        val running = GuestLauncher.recordedInstanceId(context, slot)
        if (running != instanceId) {
            GuestRuntime.trace("拒绝切前台：p$slot 跑的是 $running，请求的是 $instanceId")
            return false
        }
        val manager = context.getSystemService(ActivityManager::class.java) ?: return false
        val task = manager.appTasks.orEmpty().firstOrNull {
            val base = runCatching { it.taskInfo?.baseActivity }.getOrNull()?.className
            GuestSlots.slotForComponent(base) == slot
        } ?: return false
        return runCatching { task.moveToFront() }.isSuccess
    }

    /** slot=null 时移除全部槽位任务；指定 slot 时其它实例的任务保持不动。 */
    internal fun removeGuestTasks(context: Context, slot: Int? = null) {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        manager.appTasks.orEmpty().forEach { task ->
            val base = runCatching { task.taskInfo?.baseActivity }.getOrNull()?.className
            val taskSlot = GuestSlots.slotForComponent(base)
            if (taskSlot == null || (slot != null && taskSlot != slot)) return@forEach
            GuestRuntime.trace("移除 guest 任务 slot=p$taskSlot base=$base")
            runCatching { task.finishAndRemoveTask() }
                .onFailure { GuestRuntime.trace("移除 guest 任务失败：${it.javaClass.simpleName}") }
        }
    }

    private fun awaitGone(context: Context, slot: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (pids(context, slot).isEmpty()) return true
            Thread.sleep(50)
        }
        return pids(context, slot).isEmpty()
    }

    fun onGuestProcessStart(app: Application) {
        GuestRuntime.trace(
            "guest 进程启动 slot=p${GuestSlots.currentSlot} pid=${Process.myPid()} " +
                "app=${app.javaClass.name} process=${Application.getProcessName()}",
        )
    }
}
