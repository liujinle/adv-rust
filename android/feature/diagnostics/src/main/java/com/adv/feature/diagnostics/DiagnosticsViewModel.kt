package com.adv.feature.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adv.core.data.EngineDiagnostics
import com.adv.core.domain.EngineControlUseCase
import com.adv.core.domain.ObserveDiagnosticsUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiagnosticsUiState(
    val diagnostics: EngineDiagnostics? = null,
)

class DiagnosticsViewModel(
    private val observeDiagnostics: ObserveDiagnosticsUseCase,
    private val engineControl: EngineControlUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(DiagnosticsUiState())
    val state: StateFlow<DiagnosticsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            observeDiagnostics().collect { diagnostics ->
                _state.update { it.copy(diagnostics = diagnostics) }
            }
        }
    }

    fun retry() = engineControl.retry()

    fun refresh() {
        viewModelScope.launch {
            val snapshot = observeDiagnostics.snapshot()
            _state.update { it.copy(diagnostics = snapshot) }
        }
    }
}
