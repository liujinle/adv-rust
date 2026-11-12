package com.adv.core.data

import com.adv.core.engine.api.VirtualInstanceState

/** 运行态对账的纯判定：不碰 Room / 系统时钟，便于覆盖启动宽限期和多实例存活集合。 */
internal object GuestLivenessReconcilePolicy {

    fun shouldStop(
        state: VirtualInstanceState,
        instanceId: String,
        lastLaunchedAtMs: Long?,
        aliveInstanceIds: Set<String>,
        nowMs: Long,
        graceMs: Long,
    ): Boolean {
        if (!state.isActive) return false
        val launchedAt = lastLaunchedAtMs ?: 0L
        if (nowMs - launchedAt < graceMs) return false
        return instanceId !in aliveInstanceIds
    }
}
