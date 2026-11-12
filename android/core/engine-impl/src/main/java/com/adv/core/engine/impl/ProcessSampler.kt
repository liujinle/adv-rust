package com.adv.core.engine.impl

import android.os.Debug
import android.os.Process
import android.os.SystemClock

/**
 * Host-process resource sampling for the engine centre.
 *
 * * CPU is `getElapsedCpuTime` between two samples, so the first call after
 *   construction is meaningless by design — it only establishes a baseline.
 * * Memory is PSS of *this* process (`Debug.MemoryInfo`), which is the honest
 *   number on Android: guest and host share the process, so summing per-engine
 *   RSS would double-count.
 */
internal class ProcessSampler {

    private var lastCpuMs: Long = Process.getElapsedCpuTime()
    private var lastWallMs: Long = SystemClock.elapsedRealtime()

    @Synchronized
    fun cpuPercent(): Double? {
        val cpuMs = Process.getElapsedCpuTime()
        val wallMs = SystemClock.elapsedRealtime()
        val cpuDelta = (cpuMs - lastCpuMs).coerceAtLeast(0L)
        val wallDelta = wallMs - lastWallMs
        lastCpuMs = cpuMs
        lastWallMs = wallMs
        if (wallDelta <= 0L) return null
        return cpuDelta.toDouble() / wallDelta.toDouble() * 100.0
    }

    fun pssKb(): Long? = runCatching {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        info.totalPss.toLong()
    }.getOrNull()
}
