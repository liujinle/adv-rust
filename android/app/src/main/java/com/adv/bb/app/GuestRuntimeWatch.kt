package com.adv.bb.app

import android.content.Context
import com.adv.bb.app.guest.GuestProcess
import com.adv.bb.app.guest.GuestRuntime
import com.adv.core.data.EngineRunState
import com.adv.core.data.InstanceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 运行态对账看护（宿主主进程，5s 一次）。
 *
 * 双账本原则下，账本（Room + `instances.json`）只在主进程写，而 guest 活在
 * `:guest` 子进程里。子进程被 AMS / LMK 杀掉时引擎不会收到任何通知，于是账本停在
 * RUNNING、宿主首页一直显示「运行中 1」，点进去却是另一个实例（或什么都没有）。
 * 冷启动的 `reconcileAfterRestart()` 只覆盖"宿主自己重启"，覆盖不了这个场景。
 *
 * 判定只用两处进程级事实，不猜：
 * - [GuestProcess.poolSnapshot]：全槽位的进程 pid / 槽位快照；
 * - 每槽位按实例分文件的启动记录（task Intent 的 extras 会被 guest Intent 顶掉）。
 * 进程表读取失败或任一存活槽位归属缺失时都按 UNKNOWN 跳过，保留现有账本状态。
 *
 * 参考 `D:\app\adv` 的 `AppState.startRuntimeWatch()`：`ON_RESUME` 补一次 +
 * 常驻 5s 看护 + `AtomicBoolean` 防重入。这里把「立即补一次」留给
 * [reconcileOnce]（可由 Activity 的 onResume 调用），自身只在引擎 READY 后常驻轮询。
 */
class GuestRuntimeWatch(
    private val context: Context,
    private val instances: InstanceRepository,
    private val engineState: StateFlow<EngineRunState>,
    private val scope: CoroutineScope,
    private val intervalMs: Long = 5_000,
    private val launchGraceMs: Long = 15_000,
) {

    private val guard = AtomicBoolean(false)

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch(Dispatchers.IO) {
            // 等引擎 READY：bootstrap 里 reconcileAfterRestart() 正在收敛历史 RUNNING，
            // 抢在它前面会把同一批行重复写一遍，还可能和 refreshFromEngine 打架。
            runCatching { engineState.first { it == EngineRunState.READY } }
            while (isActive) {
                reconcileOnce()
                delay(intervalMs)
            }
        }
    }

    /**
     * 对账一轮。异常一律吞掉并**保留现有状态** —— 读失败不等于"进程没了"，
     * 把读失败当空列表会凭空把运行中的实例判死（参考项目 `ReconcileOutcome`
     * 刻意区分 loadError / saveError 就是这个教训）。
     */
    suspend fun reconcileOnce() {
        if (!guard.compareAndSet(false, true)) return
        try {
            val snapshot = GuestProcess.poolSnapshot(context)
            if (snapshot == null || !snapshot.complete) {
                val unknown = snapshot?.running?.filter { it.instanceId == null }.orEmpty()
                GuestRuntime.trace(
                    "运行态对账跳过：进程表或槽位归属未知（保留现有状态）unknown=$unknown",
                )
                return
            }
            val alive = snapshot.aliveInstanceIds
            val gone = instances.reconcileDeadGuests(alive, launchGraceMs)
            gone.forEach { id ->
                GuestRuntime.trace(
                    "PROCESS_GONE：$id 已收敛为 STOPPED（pool=${snapshot.running} alive=$alive）",
                )
            }
        } catch (error: Throwable) {
            GuestRuntime.trace(
                "运行态对账异常（保留现有状态）：${error.javaClass.simpleName} ${error.message}",
            )
        } finally {
            guard.set(false)
        }
    }
}
