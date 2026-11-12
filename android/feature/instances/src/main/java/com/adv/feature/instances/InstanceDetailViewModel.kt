package com.adv.feature.instances

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adv.core.common.EngineResult
import com.adv.core.data.InstanceDetails
import com.adv.core.data.LaunchRequest
import com.adv.core.data.PermissionPolicy
import com.adv.core.domain.InstanceActionsUseCase
import com.adv.core.domain.ObserveInstanceUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InstanceDetailUiState(
    val details: InstanceDetails? = null,
    val storageBytes: Long = 0L,
    val busy: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
)

class InstanceDetailViewModel(
    private val instanceId: String,
    observeInstance: ObserveInstanceUseCase,
    private val actions: InstanceActionsUseCase,
    private val onLaunched: (LaunchRequest) -> Unit = {},
) : ViewModel() {

    private val _state = MutableStateFlow(InstanceDetailUiState())
    val state: StateFlow<InstanceDetailUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            observeInstance(instanceId).collect { details ->
                _state.update { it.copy(details = details) }
                val bytes = withContext(Dispatchers.IO) { actions.storageBytes(instanceId) }
                _state.update { it.copy(storageBytes = bytes) }
            }
        }
    }

    fun launch() {
        if (_state.value.busy) return
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(busy = true) }
            val result = actions.launch(instanceId)
            _state.update { current ->
                current.copy(
                    busy = false,
                    message = when (result) {
                        is EngineResult.Success -> "已启动"
                        is EngineResult.Failure -> result.error.userMessage
                    },
                    messageIsError = result is EngineResult.Failure,
                )
            }
            if (result is EngineResult.Success) onLaunched(result.value)
        }
    }

    fun stop() = act("已停止") { actions.stop(instanceId) }

    /** 跳转：把还在后台的虚拟应用任务搬回前台，不重新启动。 */
    fun bringToFront() {
        if (_state.value.busy) return
        val moved = actions.bringToFront(instanceId)
        _state.update {
            it.copy(
                message = if (moved) "已切到虚拟应用前台" else "这个实例没有在运行，先点「启动」",
                messageIsError = !moved,
            )
        }
    }

    fun uninstall(onDone: () -> Unit = {}) = act("已卸载，记录已移除", onDone) {
        actions.uninstall(instanceId)
    }

    fun clearData() = act("数据已清除，可重新启动") {
        actions.clearData(instanceId)
    }

    fun backup() = act("备份完成") { actions.backup(instanceId) }

    fun restore(backupId: String) = act("恢复完成") { actions.restore(backupId) }

    fun setPermission(permission: String, policy: PermissionPolicy) =
        act("权限策略已更新") { actions.setPermission(instanceId, permission, policy) }

    fun refreshStorage() {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { actions.storageBytes(instanceId) }
            _state.update { it.copy(storageBytes = bytes) }
        }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun act(
        successMessage: String,
        onDone: () -> Unit = {},
        block: suspend () -> EngineResult<*>,
    ) {
        if (_state.value.busy) return
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(busy = true) }
            val result = block()
            _state.update { current ->
                current.copy(
                    busy = false,
                    message = when (result) {
                        is EngineResult.Success -> successMessage
                        is EngineResult.Failure -> result.error.userMessage
                    },
                    messageIsError = result is EngineResult.Failure,
                )
            }
            if (result is EngineResult.Success) onDone()
        }
    }
}
