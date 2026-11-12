package com.adv.feature.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adv.core.common.EngineResult
import com.adv.core.data.AppSummary
import com.adv.core.domain.CreateInstanceUseCase
import com.adv.core.domain.DeleteAppUseCase
import com.adv.core.domain.InstallApkUseCase
import com.adv.core.domain.ObserveAppsUseCase
import com.adv.core.domain.ObserveSettingsUseCase
import com.adv.core.engine.api.InstancePolicySpec
import com.adv.core.engine.api.withRoomFor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppsUiState(
    val apps: List<AppSummary> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
)

class AppsViewModel(
    observeApps: ObserveAppsUseCase,
    observeSettings: ObserveSettingsUseCase,
    private val installApk: InstallApkUseCase,
    private val createInstance: CreateInstanceUseCase,
    private val deleteApp: DeleteAppUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(AppsUiState())
    val state: StateFlow<AppsUiState> = _state.asStateFlow()

    private var defaultPolicy: InstancePolicySpec = InstancePolicySpec()

    init {
        viewModelScope.launch {
            observeApps().collect { apps ->
                _state.update { it.copy(apps = apps) }
            }
        }
        viewModelScope.launch {
            observeSettings().collect { settings ->
                defaultPolicy = settings.defaultPolicy
            }
        }
    }

    /** Picking an APK is the whole install flow: no dialog, no second step. */
    fun import(apkUri: String) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            when (val result = installApk(apkUri)) {
                is EngineResult.Success -> {
                    val installed = result.value
                    _state.update {
                        it.copy(
                            busy = false,
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
                    it.copy(busy = false, message = result.error.userMessage, messageIsError = true)
                }
            }
        }
    }

    /** Second copy of an app that is already installed; name is generated. */
    fun installAnother(app: AppSummary) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            // 自动名只用「应用名 + 序号」：以前是「应用名 副本 2」，同版本多实例时
            // 列表标题被「副本」撑得太长。序号从 2 起（第 1 个实例直接叫应用名）。
            val name = if (app.instanceCount == 0) {
                app.displayName
            } else {
                "${app.displayName} ${app.instanceCount + 1}"
            }
            when (val result = createInstance(app.appId, name, defaultPolicy.withRoomFor(app.fileSizeBytes))) {
                is EngineResult.Success -> _state.update {
                    it.copy(busy = false, message = "已创建实例：$name", messageIsError = false)
                }
                is EngineResult.Failure -> _state.update {
                    it.copy(busy = false, message = result.error.userMessage, messageIsError = true)
                }
            }
        }
    }

    fun delete(app: AppSummary) {
        viewModelScope.launch {
            when (val result = deleteApp(app.appId)) {
                is EngineResult.Success -> _state.update {
                    it.copy(message = "已移除 ${app.packageName}", messageIsError = false)
                }
                is EngineResult.Failure -> _state.update {
                    it.copy(message = result.error.userMessage, messageIsError = true)
                }
            }
        }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }
}
