package com.adv.core.engine.impl

import android.content.Context
import android.os.Build
import com.adv.bb.compatibility.CompatibilityMatrix
import com.adv.bb.core.BbEngine
import com.adv.bb.core.BbErrorCode
import com.adv.bb.core.BbException
import com.adv.bb.core.EngineConfig
import com.adv.bb.core.InstanceSpec
import com.adv.bb.core.InstanceStatus
import com.adv.bb.core.TaskOutcome
import com.adv.core.common.EngineError
import com.adv.core.common.EngineErrorCode
import com.adv.core.common.EngineResult
import com.adv.core.engine.api.ApkInfo
import com.adv.core.engine.api.EngineEvent
import com.adv.core.engine.api.EngineStatus
import com.adv.core.engine.api.InstallResult
import com.adv.core.engine.api.InstancePolicySpec
import com.adv.core.engine.api.VirtualInstance
import com.adv.core.engine.api.VirtualInstanceState
import com.adv.core.engine.api.VirtualizationEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Engine tuning the client exposes in Settings; mirror of `bb_config`. */
data class EngineLimits(
    val maxInstances: Int = 64,
    val ioThreads: Int = 2,
    val logLevel: String = "info",
    val cacheBudgetBytes: Long = 128L * 1024L * 1024L,
)

/**
 * Adapter over the existing Rust core. This is the only class in the client that
 * knows the bridge exists.
 *
 * Not thread-safe by caller contract: [initialize] and [shutdown] are serialised
 * internally; every other call is safe to issue from any dispatcher because the
 * native handle itself is behind the bridge's own locks.
 */
class BbVirtualizationEngine(
    context: Context,
    private val engineRoot: File,
    private val limits: EngineLimits = EngineLimits(),
) : VirtualizationEngine {

    private val appContext = context.applicationContext
    private val staging = ApkStaging(appContext, File(appContext.filesDir, "imported-apks"))
    private val sampler = ProcessSampler()
    private val gate = Mutex()
    private val hub = MutableSharedFlow<EngineEvent>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val pumpScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var engine: BbEngine? = null

    @Volatile
    private var pumpJob: Job? = null

    @Volatile
    private var eventSeq: Long = 0L

    @Volatile
    private var lastError: String? = null

    override suspend fun initialize(): EngineResult<Unit> = withContext(Dispatchers.IO) {
        gate.withLock {
            if (engine != null) return@withLock EngineResult.Success(Unit)
            try {
                if (!engineRoot.exists() && !engineRoot.mkdirs()) {
                    error("cannot create ${engineRoot.absolutePath}")
                }
                val created = BbEngine.open()
                try {
                    created.initialize(
                        EngineConfig(
                            rootPath = engineRoot.absolutePath,
                            maxInstances = limits.maxInstances,
                            ioThreads = limits.ioThreads,
                            logLevel = limits.logLevel,
                            cacheBudgetBytes = limits.cacheBudgetBytes,
                        ),
                    )
                } catch (error: Throwable) {
                    created.close()
                    throw error
                }
                engine = created
                lastError = null
                startEventPump(created)
                EngineResult.Success(Unit)
            } catch (error: Throwable) {
                failureFrom("initialize", error)
            }
        }
    }

    override suspend fun shutdown(): EngineResult<Unit> = withContext(Dispatchers.IO) {
        gate.withLock {
            pumpJob?.cancel()
            pumpJob = null
            val current = engine
            engine = null
            if (current != null) {
                try {
                    current.close()
                } catch (error: Throwable) {
                    lastError = error.message
                }
            }
            EngineResult.Success(Unit)
        }
    }

    override suspend fun inspectApk(apkUri: String): EngineResult<ApkInfo> =
        withContext(Dispatchers.IO) {
            engineCall("inspectApk") {
                val file = staging.stage(apkUri)
                val summary = requireEngine().inspectApk(file.absolutePath)
                summary.toApkInfo(file, staging.sha256(file))
            }
        }

    override suspend fun install(
        apkUri: String,
        instanceId: String,
        policy: InstancePolicySpec?,
    ): EngineResult<InstallResult> =
        withContext(Dispatchers.IO) {
            engineCall("install") {
                val file = staging.stage(apkUri)
                val native = requireEngine()
                // The engine rejects a package-name mismatch, so the client never
                // has to trust its own cache here.
                val summary = native.inspectApk(file.absolutePath)
                val spec = InstanceSpec(
                    instanceId = instanceId,
                    packageName = summary.packageName,
                    apkPath = file.absolutePath,
                    policy = policy?.toNative(),
                )
                val command = JSONObject()
                    .put("command", "create_instance")
                    .put("spec", spec.toJson())
                val taskId = native.submitTask(command)
                val outcome = awaitTask(native, taskId, "install")
                val instance = instanceFromTask(outcome)
                    ?: throw BbException.of(BbErrorCode.INTERNAL, "create_instance returned no instance")
                InstallResult(
                    instanceId = instance.instanceId,
                    packageName = instance.packageName,
                    state = instance.state,
                )
            }
        }

    override suspend fun launch(instanceId: String): EngineResult<Unit> =
        withContext(Dispatchers.IO) {
            engineCall("launch") {
                requireEngine().startInstance(instanceId)
                Unit
            }
        }

    override suspend fun stop(instanceId: String): EngineResult<Unit> =
        withContext(Dispatchers.IO) {
            engineCall("stop") {
                requireEngine().stopInstance(instanceId)
                Unit
            }
        }

    override suspend fun uninstall(instanceId: String): EngineResult<Unit> =
        withContext(Dispatchers.IO) {
            engineCall("uninstall") {
                requireEngine().removeInstance(instanceId)
                Unit
            }
        }

    override suspend fun getInstances(): EngineResult<List<VirtualInstance>> =
        withContext(Dispatchers.IO) {
            engineCall("getInstances") {
                requireEngine().listInstances().map(InstanceStatus::toVirtual)
            }
        }

    override suspend fun getInstance(instanceId: String): EngineResult<VirtualInstance> =
        withContext(Dispatchers.IO) {
            engineCall("getInstance") {
                requireEngine().getInstance(instanceId).toVirtual()
            }
        }

    override suspend fun status(): EngineResult<EngineStatus> = withContext(Dispatchers.IO) {
        val jni = probeJni()
        val native = engine
        if (native == null) {
            return@withContext EngineResult.Success(
                EngineStatus(
                    initialized = false,
                    jniLoaded = jni.loaded,
                    abiVersion = jni.abiVersion,
                    coreVersion = null,
                    protocolVersion = null,
                    rootPath = engineRoot.absolutePath,
                    virtualRoot = null,
                    instanceCount = 0,
                    runningCount = 0,
                    recoveredInstances = 0,
                    queuedTasks = 0,
                    activeTasks = 0,
                    cacheEntries = 0,
                    cacheBytes = 0L,
                    processCpuPercent = sampler.cpuPercent(),
                    processPssKb = sampler.pssKb(),
                    hostSdk = Build.VERSION.SDK_INT,
                    startedAtMs = null,
                    lastError = lastError,
                    compatibilityReport = CompatibilityMatrix.report(Build.VERSION.SDK_INT),
                ),
            )
        }
        engineCall("status") {
            val summary = native.summary()
            val instances = native.listInstances()
            val cache = summary.optJSONObject("cache")
            EngineStatus(
                initialized = true,
                jniLoaded = jni.loaded,
                abiVersion = summary.optInt("abi_version", jni.abiVersion),
                coreVersion = summary.optString("core_version").takeIf { it.isNotEmpty() },
                protocolVersion = summary.optInt("protocol_version", 0).takeIf { it > 0 },
                rootPath = summary.optString("root_path").takeIf { it.isNotEmpty() },
                virtualRoot = summary.optString("virtual_root").takeIf { it.isNotEmpty() },
                instanceCount = instances.size,
                runningCount = instances.count { it.state.isActive },
                recoveredInstances = summary.optInt("recovered_instances", 0),
                queuedTasks = summary.optInt("tasks_pending", 0),
                activeTasks = summary.optInt("tasks_active", 0),
                cacheEntries = cache?.optInt("entries", 0) ?: 0,
                cacheBytes = cache?.optLong("bytes", 0L) ?: 0L,
                processCpuPercent = sampler.cpuPercent(),
                processPssKb = sampler.pssKb(),
                hostSdk = Build.VERSION.SDK_INT,
                startedAtMs = summary.optLong("started_at_ms", 0L).takeIf { it > 0L },
                lastError = lastError,
                compatibilityReport = CompatibilityMatrix.report(Build.VERSION.SDK_INT),
            )
        }
    }

    override fun observeEvents(): Flow<EngineEvent> = hub.asSharedFlow()

    private fun startEventPump(native: BbEngine) {
        pumpJob?.cancel()
        pumpJob = pumpScope.launch {
            while (isActive) {
                try {
                    val batch = native.events(eventSeq)
                    for (event in batch) {
                        if (event.seq > eventSeq) eventSeq = event.seq
                        hub.tryEmit(
                            EngineEvent.InstanceStateChanged(
                                instanceId = event.instanceId,
                                from = event.from?.let { VirtualInstanceState.fromNativeName(it.nativeName) },
                                to = VirtualInstanceState.fromNativeName(event.to.nativeName),
                                atMs = event.atMs,
                                reason = event.reason,
                            ),
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    lastError = error.message
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun awaitTask(
        native: BbEngine,
        taskId: Long,
        label: String,
        timeoutMs: Long = 120_000L,
    ): TaskOutcome {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastProgress = -1
        while (System.currentTimeMillis() < deadline) {
            val outcome = native.taskResult(taskId)
            if (outcome != null) {
                if (outcome.progress != lastProgress) {
                    lastProgress = outcome.progress
                    hub.tryEmit(EngineEvent.TaskProgress(taskId, outcome.name, outcome.progress))
                }
                if (outcome.state.isTerminal) {
                    hub.tryEmit(
                        EngineEvent.TaskFinished(
                            taskId = taskId,
                            name = outcome.name,
                            success = outcome.isSuccess,
                            message = outcome.error?.message,
                        ),
                    )
                    if (!outcome.isSuccess) {
                        throw outcome.error ?: BbException.of(BbErrorCode.INTERNAL, "$label failed")
                    }
                    return outcome
                }
            }
            delay(TASK_POLL_MS)
        }
        throw BbException.of(BbErrorCode.TIMED_OUT, "$label did not finish within ${timeoutMs}ms")
    }

    private fun instanceFromTask(outcome: TaskOutcome): VirtualInstance? {
        val json = outcome.result as? JSONObject ?: return null
        return InstanceStatus.fromJson(json).toVirtual()
    }

    private fun requireEngine(): BbEngine =
        engine ?: throw BbException.of(BbErrorCode.NOT_INITIALIZED, "engine is not initialized")

    private fun probeJni(): JniProbe = try {
        JniProbe(loaded = true, abiVersion = BbEngine.abiVersion)
    } catch (error: Throwable) {
        JniProbe(loaded = false, abiVersion = 0)
    }

    private fun failureFrom(operation: String, error: Throwable): EngineResult.Failure {
        val mapped = when (error) {
            is BbException -> error.toEngineError()
            is UnsatisfiedLinkError, is ExceptionInInitializerError -> EngineError(
                EngineErrorCode.NATIVE_UNAVAILABLE,
                "libbb_native.so could not be loaded: ${error.message ?: error::class.java.simpleName}",
                error,
            )
            else -> EngineError(
                EngineErrorCode.INTERNAL,
                "$operation failed: ${error.message ?: error::class.java.simpleName}",
                error,
            )
        }
        lastError = mapped.detail
        return EngineResult.Failure(mapped)
    }

    private data class JniProbe(val loaded: Boolean, val abiVersion: Int)

    private companion object {
        const val POLL_INTERVAL_MS = 500L
        const val TASK_POLL_MS = 50L
    }
}
