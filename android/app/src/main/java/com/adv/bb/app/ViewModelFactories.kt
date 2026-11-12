package com.adv.bb.app

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.adv.feature.apps.AppsViewModel
import com.adv.feature.diagnostics.DiagnosticsViewModel
import com.adv.feature.home.HomeViewModel
import com.adv.feature.instances.InstanceDetailViewModel
import com.adv.feature.instances.InstancesViewModel
import com.adv.feature.settings.SettingsViewModel

fun AppContainer.homeViewModelFactory(): ViewModelProvider.Factory = viewModelFactory {
    initializer { HomeViewModel(observeHome, installApk) }
}

fun AppContainer.appsViewModelFactory(): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        AppsViewModel(
            observeApps = observeApps,
            observeSettings = observeSettings,
            installApk = installApk,
            createInstance = createInstance,
            deleteApp = deleteApp,
        )
    }
}

fun AppContainer.instancesViewModelFactory(): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        InstancesViewModel(
            observeInstances = observeInstances,
            actions = instanceActions,
            // openGuest 现在有 Boolean 返回值（启动没成时账本要回滚），
            // ViewModel 的 onLaunched 是 Unit 回调，这里显式丢弃。
            onLaunched = { openGuest(it) },
        )
    }
}

fun AppContainer.instanceDetailViewModelFactory(instanceId: String): ViewModelProvider.Factory =
    viewModelFactory {
        initializer {
            InstanceDetailViewModel(
                instanceId = instanceId,
                observeInstance = observeInstance,
                actions = instanceActions,
                onLaunched = { openGuest(it) },
            )
        }
    }

fun AppContainer.settingsViewModelFactory(): ViewModelProvider.Factory = viewModelFactory {
    initializer { SettingsViewModel(observeSettings, updateSettings, engineControl) }
}

fun AppContainer.diagnosticsViewModelFactory(): ViewModelProvider.Factory = viewModelFactory {
    initializer { DiagnosticsViewModel(observeDiagnostics, engineControl) }
}
