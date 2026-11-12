package com.adv.bb.core

import com.adv.bb.nativebridge.NativeBridge
import org.json.JSONObject
import java.io.Closeable

/**
 * Kotlin facade over the native core.
 *
 * Threading contract:
 * * [initialize], [createInstance], [removeInstance] do real filesystem/parse work
 *   and must not run on the main thread — or use [submitTask], which is what the
 *   UI does.
 * * [startInstance] / [stopInstance] / [getInstance] are short metadata updates,
 *   but they still take the engine's metadata lock, so keep them off the UI
 *   thread in production code.
 * * The handle is closed exactly once; [close] is idempotent.
 */
class BbEngine private constructor(initialHandle: Long) : Closeable {

    @Volatile
    private var handle: Long = initialHandle

    val isOpen: Boolean get() = handle != 0L

    fun initialize(config: EngineConfig) {
        val code = NativeBridge.nativeInitializeJson(requireHandle(), config.toJson().toString())
        if (code != BbErrorCode.OK) throw failure(code, "initialize")
    }

    fun createInstance(spec: InstanceSpec): InstanceStatus =
        InstanceStatus.fromJson(dispatch(JSONObject().put("type", "create_instance").put("spec", spec.toJson())).getJSONObject("instance"))

    fun removeInstance(instanceId: String): Boolean {
        val response = dispatch(JSONObject().put("type", "remove_instance").put("instance_id", instanceId))
        return response.optString("message") == "removed"
    }

    fun startInstance(instanceId: String): InstanceStatus =
        InstanceStatus.fromJson(dispatch(JSONObject().put("type", "start_instance").put("instance_id", instanceId)).getJSONObject("instance"))

    fun stopInstance(instanceId: String): InstanceStatus =
        InstanceStatus.fromJson(dispatch(JSONObject().put("type", "stop_instance").put("instance_id", instanceId)).getJSONObject("instance"))

    fun getInstance(instanceId: String): InstanceStatus =
        InstanceStatus.fromJson(dispatch(JSONObject().put("type", "get_instance").put("instance_id", instanceId)).getJSONObject("instance"))

    fun listInstances(): List<InstanceStatus> {
        val array = dispatch(JSONObject().put("type", "list_instances")).getJSONArray("instances")
        return (0 until array.length()).map { index -> InstanceStatus.fromJson(array.getJSONObject(index)) }
    }

    fun inspectApk(apkPath: String): ApkSummary =
        ApkSummary.fromJson(dispatch(JSONObject().put("type", "inspect_apk").put("apk_path", apkPath)).getJSONObject("apk"))

    /**
     * Lifecycle events newer than [sinceSeq], replayed from the runtime buffer.
     *
     * This is the push-free equivalent of a listener: the caller keeps the last
     * sequence number and polls, which keeps the JNI surface string-in/string-out.
     */
    fun events(sinceSeq: Long): List<RuntimeEvent> {
        val array = dispatch(JSONObject().put("type", "events").put("since_seq", sinceSeq))
            .getJSONArray("events")
        return (0 until array.length()).map { index -> RuntimeEvent.fromJson(array.getJSONObject(index)) }
    }

    fun metrics(): JSONObject {
        val response = dispatch(JSONObject().put("type", "metrics"))
        return JSONObject(response.getString("snapshot_json"))
    }

    /** Engine composition/status blob (paths, versions, task counts, cache usage). */
    fun summary(): JSONObject {
        val raw = NativeBridge.nativeSummaryJson(requireHandle())
            ?: throw failure(BbErrorCode.INTERNAL, "summary")
        return JSONObject(raw)
    }

    /**
     * Queues a background task. Supported commands:
     * `parse_apk`, `create_instance`, `remove_instance`, `start_instance`,
     * `stop_instance`, `list_instances`, `cleanup_orphans`.
     */
    fun submitTask(command: JSONObject): Long {
        val taskId = NativeBridge.nativeSubmitTaskJson(requireHandle(), command.toString())
        if (taskId < 0L) throw failure(taskId.toInt(), "submitTask")
        return taskId
    }

    /** `null` means "unknown or already pruned", not "still running". */
    fun taskResult(taskId: Long): TaskOutcome? {
        val raw = NativeBridge.nativeTaskResultJson(requireHandle(), taskId) ?: return null
        return TaskOutcome.fromJson(JSONObject(raw))
    }

    /** Blocking convenience used by instrumentation tests and tooling. */
    fun awaitTask(taskId: Long, timeoutMs: Long = 30_000L, pollMs: Long = 25L): TaskOutcome {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            val outcome = taskResult(taskId)
            if (outcome != null && outcome.state.isTerminal) return outcome
            Thread.sleep(pollMs)
        }
        throw BbException.of(BbErrorCode.TIMED_OUT, "task $taskId did not finish within ${timeoutMs}ms")
    }

    override fun close() {
        val previous = synchronized(this) {
            val value = handle
            handle = 0L
            value
        }
        if (previous != 0L) {
            NativeBridge.nativeDestroy(previous)
        }
    }

    private fun dispatch(request: JSONObject): JSONObject {
        val raw = NativeBridge.nativeRequestJson(requireHandle(), request.toString())
            ?: throw failure(BbErrorCode.INTERNAL, "nativeRequestJson returned null")
        val response = JSONObject(raw)
        if (response.optString("type") == "error") {
            throw BbException(
                response.optInt("code", BbErrorCode.INTERNAL),
                response.optString("code_name", "INTERNAL"),
                response.optString("message"),
            )
        }
        return response
    }

    private fun requireHandle(): Long {
        val current = handle
        if (current == 0L) throw BbException.of(BbErrorCode.NOT_INITIALIZED, "engine is closed")
        return current
    }

    private fun failure(code: Int, operation: String): BbException {
        val detail = runCatching { NativeBridge.nativeLastError(handle) }.getOrNull().orEmpty()
        val message = if (detail.isBlank()) "$operation failed with code $code" else detail
        return BbException.of(code, message)
    }

    companion object {
        /** ABI version of the loaded `.so`; compare with [BbErrorCode] expectations. */
        val abiVersion: Int get() = NativeBridge.nativeAbiVersion()

        fun open(): BbEngine {
            val handle = NativeBridge.nativeCreate()
            if (handle == 0L) {
                throw BbException.of(BbErrorCode.INTERNAL, "nativeCreate returned 0")
            }
            return BbEngine(handle)
        }
    }
}
