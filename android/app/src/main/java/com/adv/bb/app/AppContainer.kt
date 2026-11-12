package com.adv.bb.app

import android.content.Context
import com.adv.bb.app.guest.GuestLauncher
import com.adv.bb.app.guest.GuestProcess
import com.adv.core.data.ApkMetadata
import com.adv.core.data.AppRepository
import com.adv.core.data.DiagnosticsRepository
import com.adv.core.data.EngineCoordinator
import com.adv.core.data.EngineProvider
import com.adv.core.data.InstanceRepository
import com.adv.core.data.LaunchRequest
import com.adv.core.data.LogBuffer
import com.adv.core.data.SettingsRepository
import com.adv.core.data.StorageLayout
import com.adv.core.database.AdvDatabase
import com.adv.core.domain.CreateInstanceUseCase
import com.adv.core.domain.DeleteAppUseCase
import com.adv.core.domain.EngineControlUseCase
import com.adv.core.domain.ImportApkUseCase
import com.adv.core.domain.InstallApkUseCase
import com.adv.core.domain.InstanceActionsUseCase
import com.adv.core.domain.ObserveAppUseCase
import com.adv.core.domain.ObserveAppsUseCase
import com.adv.core.domain.ObserveDiagnosticsUseCase
import com.adv.core.domain.ObserveHomeUseCase
import com.adv.core.domain.ObserveInstanceUseCase
import com.adv.core.domain.ObserveInstancesUseCase
import com.adv.core.domain.ObserveSettingsUseCase
import com.adv.core.domain.UpdateSettingsUseCase
import com.adv.core.engine.impl.BbVirtualizationEngine
import com.adv.core.engine.impl.EngineLimits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Manual dependency graph.
 *
 * No DI framework: the graph is small, explicit, and every edge is visible in one
 * place — which matters more here than saving a few lines of wiring.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val logBuffer = LogBuffer()

    private val database = AdvDatabase.open(appContext)

    private val engineRoot = File(appContext.filesDir, "engine")

    private val storageLayout = StorageLayout(
        engineRoot = engineRoot,
        backupsDir = File(appContext.filesDir, "backups"),
    )

    val provider = EngineProvider()

    val settingsRepository = SettingsRepository(database.configDao())

    private val apkMetadata = ApkMetadata(
        context = appContext,
        iconsDir = File(appContext.filesDir, "app-icons"),
    )

    val appRepository = AppRepository(
        provider = provider,
        appDao = database.appDao(),
        log = logBuffer,
        metadata = apkMetadata,
    )

    val instanceRepository = InstanceRepository(
        provider = provider,
        appDao = database.appDao(),
        instanceDao = database.instanceDao(),
        permissionDao = database.permissionDao(),
        operationDao = database.operationDao(),
        backupDao = database.backupDao(),
        configDao = database.configDao(),
        layout = storageLayout,
        log = logBuffer,
    )

    val coordinator = EngineCoordinator(
        provider = provider,
        settings = settingsRepository,
        instances = instanceRepository,
        log = logBuffer,
        engineFactory = { boot ->
            BbVirtualizationEngine(
                context = appContext,
                engineRoot = engineRoot,
                limits = EngineLimits(
                    maxInstances = boot.maxInstances,
                    logLevel = boot.logLevel,
                    cacheBudgetBytes = boot.cacheBudgetBytes,
                ),
            )
        },
    )

    val diagnosticsRepository = DiagnosticsRepository(
        provider = provider,
        log = logBuffer,
        runState = coordinator.state,
    )

    val observeHome = ObserveHomeUseCase(appRepository, instanceRepository, coordinator.state)
    val observeApps = ObserveAppsUseCase(appRepository)
    val observeApp = ObserveAppUseCase(appRepository)
    val importApk = ImportApkUseCase(appRepository)
    val deleteApp = DeleteAppUseCase(appRepository)

    val observeInstances = ObserveInstancesUseCase(instanceRepository)
    val observeInstance = ObserveInstanceUseCase(instanceRepository)
    val createInstance = CreateInstanceUseCase(instanceRepository)
    val instanceActions = InstanceActionsUseCase(
        instances = instanceRepository,
        onInstanceReset = { instanceId ->
            // 清数据 / 卸载之后，宿主侧按实例缓存的东西也必须一起丢，否则下次启动会
            // 复用上一份解包出来的 .so（`files/guest-libs/<id>` 非空就直接复用），
            // 新装的实例等于跑在旧的原生库里 —— 实测启动页会卡在初始化、强跳首页后
            // 崩在 `had you called RpcService.init`。
            runCatching { File(appContext.filesDir, "guest-libs/$instanceId").deleteRecursively() }
            runCatching { com.adv.bb.app.guest.GuestRuntime.forget(instanceId) }
        },
        onGuestStop = { instanceId ->
            // 只停这个实例绑定的槽位；清 A 的数据不能再把 B 的进程杀掉。
            runCatching { GuestProcess.shutdown(appContext, instanceId) }
        },
        onGuestBringToFront = { instanceId ->
            // 用户已经退出虚拟应用（回桌面/回宿主）时，把它的任务卡原样搬回前台，
            // 不重启 guest、不重建 Activity。
            runCatching { GuestProcess.moveGuestTaskToFront(appContext, instanceId) }
                .getOrDefault(false)
        },
    )

    /** Pick an APK → import → install, one call, no confirmation step. */
    val installApk = InstallApkUseCase(importApk, createInstance, settingsRepository)

    val observeSettings = ObserveSettingsUseCase(settingsRepository)
    val updateSettings = UpdateSettingsUseCase(settingsRepository)

    val observeDiagnostics = ObserveDiagnosticsUseCase(diagnosticsRepository)
    val engineControl = EngineControlUseCase(coordinator)

    /** 打开 guest 入口；失败只回滚本次实例，不再停止其它正在运行的实例。 */
    fun openGuest(request: LaunchRequest): Boolean {
        val opened = GuestLauncher.launch(appContext, request)
        if (!opened) {
            startupScope.launch { runCatching { instanceActions.stop(request.instanceId) } }
        }
        return opened
    }

    /**
     * 运行态对账看护：`:guest` 子进程被杀后 5s 内把账本从 RUNNING 收敛为 STOPPED。
     * 只在主进程创建（[AdvApplication] 已保证 `:guest` 不建 AppContainer）。
     */
    val runtimeWatch = GuestRuntimeWatch(
        context = appContext,
        instances = instanceRepository,
        engineState = coordinator.state,
        scope = startupScope,
    )

    fun start() {
        runtimeWatch.start()
        // 保持既有冷启动契约：宿主重启后账本全部 STOPPED。池化后必须先把仍存活的
        // 槽位一起关掉，否则 Room/Rust 已停止而旧 guest 还在写实例目录。
        startupScope.launch {
            GuestProcess.shutdown(appContext)
            coordinator.start()
        }
        // Display policy applies to the live host window, so it is pushed as soon
        // as the setting changes instead of at next launch.
        startupScope.launch {
            observeSettings().collect { settings ->
                DisplayPolicy.lockPortrait = settings.lockPortrait
            }
        }
        // Old rows (schema v1) have no label/icon yet; fill them in the background
        // so the first list render already has something to show.
        startupScope.launch {
            appRepository.backfillMetadata()
            // Labels are now available, so names generated from the package name
            // can be rewritten to `<appName> 副本`.
            instanceRepository.migrateLegacyNames()
        }
    }
}
