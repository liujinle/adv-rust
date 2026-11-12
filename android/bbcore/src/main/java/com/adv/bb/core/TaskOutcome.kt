package com.adv.bb.core

import org.json.JSONObject

enum class TaskState(val nativeName: String) {
    PENDING("pending"),
    RUNNING("running"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    CANCELLED("cancelled");

    val isTerminal: Boolean
        get() = this == SUCCEEDED || this == FAILED || this == CANCELLED

    companion object {
        fun fromNative(name: String): TaskState =
            entries.firstOrNull { it.nativeName == name } ?: PENDING
    }
}

/**
 * Snapshot of one background task.
 *
 * [result] is the decoded JSON payload produced by the task (array or object),
 * so callers do not need to know which command produced it.
 */
data class TaskOutcome(
    val taskId: Long,
    val name: String,
    val state: TaskState,
    val progress: Int,
    val result: Any?,
    val error: BbException?,
    val submittedAtMs: Long,
    val startedAtMs: Long?,
    val finishedAtMs: Long?,
) {
    val isSuccess: Boolean get() = state == TaskState.SUCCEEDED

    companion object {
        fun fromJson(json: JSONObject): TaskOutcome {
            val errorJson = json.optJSONObject("error")
            return TaskOutcome(
                taskId = json.optLong("task_id", 0L),
                name = json.optString("name"),
                state = TaskState.fromNative(json.optString("state")),
                progress = json.optInt("progress", 0),
                result = json.opt("result"),
                error = errorJson?.let {
                    BbException(it.optInt("code", BbErrorCode.INTERNAL), it.optString("name", "INTERNAL"), it.optString("message"))
                },
                submittedAtMs = json.optLong("submitted_at_ms", 0L),
                startedAtMs = json.optLong("started_at_ms", 0L).takeIf { it > 0L },
                finishedAtMs = json.optLong("finished_at_ms", 0L).takeIf { it > 0L },
            )
        }
    }
}
