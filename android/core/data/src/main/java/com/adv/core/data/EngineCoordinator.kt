package com.adv.core.data

import com.adv.core.common.EngineResult
import com.adv.core.engine.api.VirtualizationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the engine lifecycle so no screen has to.
 *
 * Responsibilities kept deliberately narrow: construct the engine from stored
 * settings, initialise it once, reconcile persisted rows after a process restart,
 * then forward lifecycle events into the repository. Retry is explicit (the
 * diagnostics screen exposes a button) rather than an invisible restart loop.
 */
class EngineCoordinator(
    private val provider: EngineProvider,
    private val settings: SettingsRepository,
    private val instances: InstanceRepository,
    private val log: LogBuffer,
    private val engineFactory: (EngineBootConfig) -> VirtualizationEngine,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(EngineRunState.UNINITIALIZED)

    val state: StateFlow<EngineRunState> = _state.asStateFlow()

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { bootstrap() }
    }

    /** Tears the current attempt down and starts a clean one. */
    fun retry() {
        val previous = provider.current
        provider.detach()
        job?.cancel()
        job = null
        _state.value = EngineRunState.UNINITIALIZED
        job = scope.launch {
            previous?.shutdown()
            bootstrap()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        scope.launch {
            provider.current?.shutdown()
            provider.detach()
            _state.value = EngineRunState.STOPPED
            log.info("引擎已关闭")
        }
    }

    private suspend fun bootstrap() {
        _state.value = EngineRunState.INITIALIZING
        val config = runCatching { settings.load() }.getOrDefault(AppSettings.Default)
        val engine = engineFactory(
            EngineBootConfig(
                logLevel = config.logLevel,
                maxInstances = config.maxInstances,
                cacheBudgetBytes = config.cacheBudgetBytes,
            ),
        )
        provider.attach(engine)

        when (val initialized = engine.initialize()) {
            is EngineResult.Failure -> {
                _state.value = EngineRunState.FAILED
                log.error("引擎初始化失败：${initialized.error.toLogLine()}")
                engine.shutdown()
                provider.detach()
                return
            }
            is EngineResult.Success -> Unit
        }

        val status = engine.status().valueOrNull()
        log.info(
            "引擎就绪 core=${status?.coreVersion ?: "unknown"} abi=${status?.abiVersion ?: 0} " +
                "instances=${status?.instanceCount ?: 0}",
        )
        instances.reconcileAfterRestart()
        _state.value = EngineRunState.READY

        engine.observeEvents().collect { event -> instances.applyEvent(event) }
    }
}
