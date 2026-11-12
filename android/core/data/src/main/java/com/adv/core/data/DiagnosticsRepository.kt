package com.adv.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/**
 * Polls the engine for its status blob. The JNI surface is synchronous
 * request/response, so a short poll interval is the honest implementation; there
 * is no push channel to subscribe to.
 */
class DiagnosticsRepository(
    private val provider: EngineProvider,
    private val log: LogBuffer,
    private val runState: StateFlow<EngineRunState>,
) {

    fun observe(): Flow<EngineDiagnostics> = flow {
        while (currentCoroutineContext().isActive) {
            emit(snapshot())
            delay(POLL_INTERVAL_MS)
        }
    }.flowOn(Dispatchers.IO)

    suspend fun snapshot(): EngineDiagnostics {
        val status = provider.current?.status()?.valueOrNull()
        return EngineDiagnostics(
            engineState = runState.value,
            status = status,
            logs = log.entries.value,
        )
    }

    private companion object {
        const val POLL_INTERVAL_MS = 2_000L
    }
}
