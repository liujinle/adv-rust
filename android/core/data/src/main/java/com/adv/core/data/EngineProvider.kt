package com.adv.core.data

import com.adv.core.common.EngineError
import com.adv.core.common.EngineResult
import com.adv.core.engine.api.VirtualizationEngine

/**
 * Indirection between "the engine exists" and "the engine is initialised".
 *
 * The engine instance is created by [EngineCoordinator] after the stored engine
 * settings are read, which is later than repository construction. Repositories
 * therefore resolve it through this holder instead of capturing it in a
 * constructor, and get a typed `NOT_INITIALIZED` failure while the engine is
 * still booting.
 */
class EngineProvider {

    @Volatile
    private var engine: VirtualizationEngine? = null

    val current: VirtualizationEngine? get() = engine

    fun attach(engine: VirtualizationEngine) {
        this.engine = engine
    }

    fun detach() {
        engine = null
    }

    suspend fun <T> use(block: suspend (VirtualizationEngine) -> EngineResult<T>): EngineResult<T> {
        val current = engine ?: return EngineResult.Failure(EngineError.notInitialized())
        return block(current)
    }
}
