package com.adv.core.common

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val timestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault())

private val clockFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return if (unit == 0) {
        "${value.toLong()} ${units[unit]}"
    } else {
        String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
    }
}

fun formatTimestamp(epochMs: Long): String =
    if (epochMs <= 0L) "—"
    else Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(timestampFormatter)

fun formatClock(epochMs: Long): String =
    if (epochMs <= 0L) "—"
    else Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(clockFormatter)

/** Persisted status codes stay English; only the UI layer maps them. */
fun operationStatusLabel(status: String): String = when (status) {
    "RUNNING" -> "进行中"
    "SUCCEEDED" -> "成功"
    "FAILED" -> "失败"
    else -> status
}

fun backupStatusLabel(status: String): String = when (status) {
    "READY" -> "可恢复"
    "RESTORED" -> "已恢复"
    "FAILED" -> "失败"
    else -> status
}

fun formatRelative(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    if (epochMs <= 0L) return "—"
    val delta = (nowMs - epochMs).coerceAtLeast(0L)
    val minutes = delta / 60_000L
    return when {
        minutes < 1L -> "刚刚"
        minutes < 60L -> "${minutes} 分钟前"
        minutes < 60L * 24L -> "${minutes / 60L} 小时前"
        minutes < 60L * 24L * 7L -> "${minutes / (60L * 24L)} 天前"
        else -> formatTimestamp(epochMs)
    }
}
