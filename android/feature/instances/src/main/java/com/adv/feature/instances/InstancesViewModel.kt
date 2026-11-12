package com.adv.feature.instances

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adv.core.common.EngineResult
import com.adv.core.data.InstanceOverview
import com.adv.core.data.LaunchRequest
import com.adv.core.domain.InstanceActionsUseCase
import com.adv.core.domain.ObserveInstancesUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InstancesUiState(
    val instances: List<InstanceOverview> = emptyList(),
    val busyInstanceIds: Set<String> = emptySet(),
    val message: String? = null,
    val messageIsError: Boolean = false,
)

class InstancesViewModel(
    observeInstances: ObserveInstancesUseCase,
    private val actions: InstanceActionsUseCase,
    private val onLaunched: (LaunchRequest) -> Unit = {},
) : ViewModel() {

    private val _state = MutableStateFlow(InstancesUiState())
    val state: StateFlow<InstancesUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            observeInstances().collect { instances ->
                _state.update { it.copy(instances = instances) }
            }
        }
        viewModelScope.launch { actions.refresh() }
    }

    fun launch(instanceId: String) = run(
        instanceId = instanceId,
        successMessage = "已启动",
        onSuccess = { request: LaunchRequest -> onLaunched(request) },
    ) { actions.launch(instanceId) }

    fun stop(instanceId: String) = run(instanceId, "已停止") { actions.stop(instanceId) }

    /**
     * 跳转：把已经在后台的虚拟应用搬回前台。
     *
     * 不重新启动、不碰账本 —— 用户按 Home / 返回回到宿主后，guest 的任务卡还在，
     * 这里直接 `moveToFront()`。切不过去（进程已退）就提示先启动。
     */
    fun bringToFront(instanceId: String) {
        if (instanceId in _state.value.busyInstanceIds) return
        val moved = actions.bringToFront(instanceId)
        _state.update {
            it.copy(
                message = if (moved) "已切到虚拟应用前台" else "这个实例没有在运行，先点「启动」",
                messageIsError = !moved,
            )
        }
    }

    fun uninstall(instanceId: String) = run(instanceId, "已卸载，记录已移除") {
        actions.uninstall(instanceId)
    }

    // 首次启动时红果会重新做设备注册，那一轮首页可能显示「网络出错」（服务端回
    // ILLEGAL_ACCESS/110），再启动一次就好 —— 提前说清楚，免得以为按钮把实例搞坏了。
    fun clearData(instanceId: String) = run(
        instanceId,
        "数据已重置；首次启动若显示「网络出错」，再启动一次即可",
    ) {
        actions.clearData(instanceId)
    }

    fun rename(instanceId: String, displayName: String) =
        run(instanceId, "已重命名") { actions.rename(instanceId, displayName) }

    fun refresh() {
        viewModelScope.launch { actions.refresh() }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun <T> run(
        instanceId: String,
        successMessage: String,
        onSuccess: (T) -> Unit = {},
        block: suspend () -> EngineResult<T>,
    ) {
        if (instanceId in _state.value.busyInstanceIds) return
        viewModelScope.launch {
            _state.update { it.copy(busyInstanceIds = it.busyInstanceIds + instanceId) }
            val result = block()
            _state.update { current ->
                current.copy(
                    busyInstanceIds = current.busyInstanceIds - instanceId,
                    message = when (result) {
                        is EngineResult.Success -> successMessage
                        is EngineResult.Failure -> result.error.userMessage
                    },
                    messageIsError = result is EngineResult.Failure,
                )
            }
            // Guest loading (dex + resources) is IO-bound; never do it on the
            // main thread even though the state update above belongs there.
            if (result is EngineResult.Success) {
                withContext(Dispatchers.IO) { onSuccess(result.value) }
            }
        }
    }
}
