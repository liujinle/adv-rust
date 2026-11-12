package com.adv.core.domain

import com.adv.core.common.EngineResult
import com.adv.core.data.AppSettings
import com.adv.core.data.DiagnosticsRepository
import com.adv.core.data.EngineCoordinator
import com.adv.core.data.EngineDiagnostics
import com.adv.core.data.EngineRunState
import com.adv.core.data.SettingsRepository
import com.adv.core.data.ThemeMode
import com.adv.core.engine.api.InstancePolicySpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

class ObserveDiagnosticsUseCase(private val diagnostics: DiagnosticsRepository) {
    operator fun invoke(): Flow<EngineDiagnostics> = diagnostics.observe()

    suspend fun snapshot(): EngineDiagnostics = diagnostics.snapshot()
}

class EngineControlUseCase(private val coordinator: EngineCoordinator) {

    val state: StateFlow<EngineRunState> = coordinator.state

    fun retry() = coordinator.retry()
}

class ObserveSettingsUseCase(private val settings: SettingsRepository) {
    operator fun invoke(): Flow<AppSettings> = settings.observe()
}

class UpdateSettingsUseCase(private val settings: SettingsRepository) {

    suspend fun setThemeMode(mode: ThemeMode): EngineResult<Unit> = settings.setThemeMode(mode)

    suspend fun setLogLevel(level: String): EngineResult<Unit> = settings.setLogLevel(level)

    suspend fun setMaxInstances(value: Int): EngineResult<Unit> = settings.setMaxInstances(value)

    suspend fun setCacheBudgetBytes(value: Long): EngineResult<Unit> =
        settings.setCacheBudgetBytes(value)

    suspend fun setLockPortrait(value: Boolean): EngineResult<Unit> = settings.setLockPortrait(value)

    suspend fun setDefaultPolicy(policy: InstancePolicySpec): EngineResult<Unit> =
        settings.setDefaultPolicy(policy)
}
