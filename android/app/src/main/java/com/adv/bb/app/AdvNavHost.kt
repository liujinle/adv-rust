package com.adv.bb.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.adv.feature.apps.AppsScreen
import com.adv.feature.apps.AppsViewModel
import com.adv.feature.diagnostics.DiagnosticsScreen
import com.adv.feature.diagnostics.DiagnosticsViewModel
import com.adv.feature.home.HomeScreen
import com.adv.feature.home.HomeViewModel
import com.adv.feature.instances.InstanceDetailScreen
import com.adv.feature.instances.InstanceDetailViewModel
import com.adv.feature.instances.InstancesScreen
import com.adv.feature.instances.InstancesViewModel
import com.adv.feature.settings.SettingsScreen
import com.adv.feature.settings.SettingsViewModel

private object Routes {
    const val HOME = "home"
    const val APPS = "apps"
    const val INSTANCES = "instances"
    const val INSTANCE_DETAIL = "instances/{instanceId}"
    const val SETTINGS = "settings"
    const val DIAGNOSTICS = "diagnostics"

    fun instanceDetail(instanceId: String) = "instances/$instanceId"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "首页", Icons.Filled.Home),
    Tab(Routes.APPS, "应用", Icons.AutoMirrored.Filled.List),
    Tab(Routes.INSTANCES, "实例", Icons.Filled.PlayArrow),
    Tab(Routes.SETTINGS, "设置", Icons.Filled.Settings),
)

@Composable
fun AdvNavHost(container: AppContainer) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = tabs.any { it.route == currentRoute }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = { navController.switchTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.HOME) {
                HomeRoute(
                    container = container,
                    onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                    onOpenApps = { navController.switchTab(Routes.APPS) },
                    onOpenInstances = { navController.switchTab(Routes.INSTANCES) },
                    onOpenInstance = { navController.navigate(Routes.instanceDetail(it)) },
                )
            }
            composable(Routes.APPS) {
                AppsRoute(container)
            }
            composable(Routes.INSTANCES) {
                InstancesRoute(
                    container = container,
                    onOpenDetail = { navController.navigate(Routes.instanceDetail(it)) },
                )
            }
            composable(Routes.INSTANCE_DETAIL) { entry ->
                val instanceId = entry.arguments?.getString("instanceId").orEmpty()
                InstanceDetailRoute(
                    container = container,
                    instanceId = instanceId,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsRoute(
                    container = container,
                    onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                )
            }
            composable(Routes.DIAGNOSTICS) {
                DiagnosticsRoute(container = container, onBack = { navController.popBackStack() })
            }
        }
    }
}

private fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun rememberApkPicker(onPicked: (String) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) onPicked(uri.toString())
    }
    return { launcher.launch(arrayOf("*/*")) }
}

@Composable
private fun HomeRoute(
    container: AppContainer,
    onOpenDiagnostics: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenInstances: () -> Unit,
    onOpenInstance: (String) -> Unit,
) {
    val viewModel: HomeViewModel = viewModel(factory = container.homeViewModelFactory())
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pickApk = rememberApkPicker { uri -> viewModel.import(uri) }
    HomeScreen(
        state = state,
        onPickApk = pickApk,
        onOpenDiagnostics = onOpenDiagnostics,
        onOpenApps = onOpenApps,
        onOpenInstances = onOpenInstances,
        onOpenInstance = onOpenInstance,
        onDismissMessage = viewModel::dismissMessage,
    )
}

@Composable
private fun AppsRoute(container: AppContainer) {
    val viewModel: AppsViewModel = viewModel(factory = container.appsViewModelFactory())
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pickApk = rememberApkPicker { uri -> viewModel.import(uri) }
    AppsScreen(
        state = state,
        onPickApk = pickApk,
        onInstallAnother = viewModel::installAnother,
        onDelete = viewModel::delete,
        onDismissMessage = viewModel::dismissMessage,
    )
}

@Composable
private fun InstancesRoute(container: AppContainer, onOpenDetail: (String) -> Unit) {
    val viewModel: InstancesViewModel = viewModel(factory = container.instancesViewModelFactory())
    val state by viewModel.state.collectAsStateWithLifecycle()
    InstancesScreen(
        state = state,
        onLaunch = viewModel::launch,
        onStop = viewModel::stop,
        onBringToFront = viewModel::bringToFront,
        onRename = viewModel::rename,
        onUninstall = viewModel::uninstall,
        onOpenDetail = onOpenDetail,
        onRefresh = viewModel::refresh,
        onDismissMessage = viewModel::dismissMessage,
    )
}

@Composable
private fun InstanceDetailRoute(
    container: AppContainer,
    instanceId: String,
    onBack: () -> Unit,
) {
    val viewModel: InstanceDetailViewModel = viewModel(
        factory = container.instanceDetailViewModelFactory(instanceId),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    InstanceDetailScreen(
        state = state,
        onBack = onBack,
        onLaunch = viewModel::launch,
        onStop = viewModel::stop,
        onBringToFront = viewModel::bringToFront,
        onBackup = viewModel::backup,
        onRestore = viewModel::restore,
        onSetPermission = viewModel::setPermission,
        onRefreshStorage = viewModel::refreshStorage,
        onClearData = viewModel::clearData,
        onUninstall = { viewModel.uninstall(onDone = onBack) },
        onDismissMessage = viewModel::dismissMessage,
    )
}

@Composable
private fun SettingsRoute(container: AppContainer, onOpenDiagnostics: () -> Unit) {
    val viewModel: SettingsViewModel = viewModel(factory = container.settingsViewModelFactory())
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScreen(
        state = state,
        onThemeChange = viewModel::setTheme,
        onLockPortraitChange = viewModel::setLockPortrait,
        onLogLevelChange = viewModel::setLogLevel,
        onMaxInstancesChange = viewModel::shiftMaxInstances,
        onCacheBudgetChange = viewModel::shiftCacheBudgetMb,
        onNetworkChanged = { allowed ->
            viewModel.updatePolicy { it.copy(allowNetwork = allowed) }
        },
        onExternalStorageChanged = { allowed ->
            viewModel.updatePolicy { it.copy(allowExternalStorage = allowed) }
        },
        onBackgroundStartChanged = { allowed ->
            viewModel.updatePolicy { it.copy(allowBackgroundStart = allowed) }
        },
        onRestartEngine = viewModel::restartEngine,
        onOpenDiagnostics = onOpenDiagnostics,
        onDismissMessage = viewModel::dismissMessage,
    )
}

@Composable
private fun DiagnosticsRoute(container: AppContainer, onBack: () -> Unit) {
    val viewModel: DiagnosticsViewModel = viewModel(factory = container.diagnosticsViewModelFactory())
    val state by viewModel.state.collectAsStateWithLifecycle()
    DiagnosticsScreen(
        state = state,
        onRetry = viewModel::retry,
        onRefresh = viewModel::refresh,
        onBack = onBack,
    )
}
