package com.adv.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adv.core.common.EngineResult
import com.adv.core.data.AppSettings
import com.adv.core.data.EngineRunState
import com.adv.core.data.ThemeMode
import com.adv.core.domain.EngineControlUseCase
import com.adv.core.domain.ObserveSettingsUseCase
import com.adv.core.domain.UpdateSettingsUseCase
import com.adv.core.engine.api.InstancePolicySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: AppSettings = AppSettings.Default,
    val engineState: EngineRunState = EngineRunState.UNINITIALIZED,
    val message: String? = null,
    val messageIsError: Boolean = false,
)

class SettingsViewModel(
    observeSettings: ObserveSettingsUseCase,
    private val updateSettings: UpdateSettingsUseCase,
    private val engineControl: EngineControlUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            observeSettings().collect { settings ->
                _state.update { it.copy(settings = settings) }
            }
        }
        viewModelScope.launch {
            engineControl.state.collect { engineState ->
                _state.update { it.copy(engineState = engineState) }
            }
        }
    }

    fun setTheme(mode: ThemeMode) = write("外观已更新") { updateSettings.setThemeMode(mode) }

    fun setLogLevel(level: String) = write("日志级别将在下次启动生效") {
        updateSettings.setLogLevel(level)
    }

    fun shiftMaxInstances(delta: Int) {
        val next = (_state.value.settings.maxInstances + delta).coerceIn(1, 256)
        write("最大实例数将在下次启动生效") { updateSettings.setMaxInstances(next) }
    }

    fun shiftCacheBudgetMb(delta: Int) {
        val currentMb = (_state.value.settings.cacheBudgetBytes / MIB).toInt()
        val next = (currentMb + delta).coerceIn(16, 2048)
        write("缓存预算将在下次启动生效") {
            updateSettings.setCacheBudgetBytes(next.toLong() * MIB)
        }
    }

    fun setLockPortrait(locked: Boolean) = write(if (locked) "已禁止横屏" else "已允许横屏") {
        updateSettings.setLockPortrait(locked)
    }

    fun updatePolicy(transform: (InstancePolicySpec) -> InstancePolicySpec) {
        val next = transform(_state.value.settings.defaultPolicy)
        write("默认实例策略已更新") { updateSettings.setDefaultPolicy(next) }
    }

    fun restartEngine() {
        engineControl.retry()
        _state.update { it.copy(message = "正在重新初始化引擎", messageIsError = false) }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun write(successMessage: String, block: suspend () -> EngineResult<Unit>) {
        viewModelScope.launch(Dispatchers.IO) {
            when (val result = block()) {
                is EngineResult.Success -> _state.update {
                    it.copy(message = successMessage, messageIsError = false)
                }
                is EngineResult.Failure -> _state.update {
                    it.copy(message = result.error.userMessage, messageIsError = true)
                }
            }
        }
    }

    private companion object {
        const val MIB = 1024L * 1024L
    }
}
