package com.adv.core.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bounded in-memory log. Diagnostic only: nothing here is written to disk, so it
 * cannot leak guest data into host storage.
 */
class LogBuffer(private val capacity: Int = 300) {

    private val buffer = ArrayDeque<LogEntry>()

    private val state = MutableStateFlow<List<LogEntry>>(emptyList())

    val entries: StateFlow<List<LogEntry>> = state.asStateFlow()

    @Synchronized
    fun record(level: LogLevel, message: String) {
        buffer.addLast(LogEntry(atMs = System.currentTimeMillis(), level = level, message = message))
        while (buffer.size > capacity) {
            buffer.removeFirst()
        }
        state.value = buffer.toList()
    }

    fun info(message: String) = record(LogLevel.INFO, message)

    fun warn(message: String) = record(LogLevel.WARNING, message)

    fun error(message: String) = record(LogLevel.ERROR, message)
}
