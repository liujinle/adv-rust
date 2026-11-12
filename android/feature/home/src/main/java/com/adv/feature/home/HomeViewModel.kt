package com.adv.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adv.core.common.EngineResult
import com.adv.core.data.HomeSnapshot
import com.adv.core.domain.InstallApkUseCase
import com.adv.core.domain.ObserveHomeUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val snapshot: HomeSnapshot? = null,
    val importing: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
)

class HomeViewModel(
    private val observeHome: ObserveHomeUseCase,
    private val installApk: InstallApkUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            observeHome().collect { snapshot ->
                _state.update { it.copy(snapshot = snapshot) }
            }
        }
    }

    /** Import + install in one call, same as the app library. */
    fun import(apkUri: String) {
        if (_state.value.importing) return
        viewModelScope.launch {
            _state.update { it.copy(importing = true, message = null) }
            when (val result = installApk(apkUri)) {
                is EngineResult.Success -> {
                    val installed = result.value
                    _state.update {
                        it.copy(
                            importing = false,
                            message = if (installed.instanceId != null) {
                                "已安装 ${installed.app.displayName}"
                            } else {
                                "${installed.app.displayName} 已安装（${installed.existingInstances} 个实例）"
                            },
                            messageIsError = false,
                        )
                    }
                }
                is EngineResult.Failure -> _state.update {
                    it.copy(
                        importing = false,
                        message = result.error.userMessage,
                        messageIsError = true,
                    )
                }
            }
        }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }
}
