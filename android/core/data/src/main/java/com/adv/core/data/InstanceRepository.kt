package com.adv.core.data

import com.adv.core.common.EngineError
import com.adv.core.common.EngineErrorCode
import com.adv.core.common.EngineResult
import com.adv.core.common.Ids
import com.adv.core.common.engineRunCatching
import com.adv.core.database.AppDao
import com.adv.core.database.BackupDao
import com.adv.core.database.BackupRecordEntity
import com.adv.core.database.ConfigDao
import com.adv.core.database.EngineConfigEntity
import com.adv.core.database.InstanceDao
import com.adv.core.database.InstanceEntity
import com.adv.core.database.OperationDao
import com.adv.core.database.OperationRecordEntity
import com.adv.core.database.PermissionDao
import com.adv.core.database.PermissionEntity
import com.adv.core.engine.api.EngineEvent
import com.adv.core.engine.api.InstancePolicySpec
import com.adv.core.engine.api.VirtualInstanceState
import android.os.Build
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * Single writer for instance state: the engine is the source of truth for
 * lifecycle, the database is the source of truth for names, policies and history.
 * Every mutation refreshes the row instead of assuming the call succeeded.
 */
class InstanceRepository(
    private val provider: EngineProvider,
    private val appDao: AppDao,
    private val instanceDao: InstanceDao,
    private val permissionDao: PermissionDao,
    private val operationDao: OperationDao,
    private val backupDao: BackupDao,
    private val configDao: ConfigDao,
    private val layout: StorageLayout,
    private val log: LogBuffer,
) {

    fun observeInstances(): Flow<List<InstanceOverview>> =
        instanceDao.observeOverviews().map { rows -> rows.map { it.toOverview() } }

    fun observeInstance(instanceId: String): Flow<InstanceDetails?> = combine(
        instanceDao.observeOverview(instanceId),
        permissionDao.observeFor(instanceId),
        backupDao.observeFor(instanceId),
        operationDao.observeForInstance(instanceId, HISTORY_LIMIT),
        configDao.observe(policyKey(instanceId)),
    ) { overview, permissions, backups, operations, policy ->
        if (overview == null) {
            null
        } else {
            InstanceDetails(
                overview = overview.toOverview(),
                apkPath = overview.apkPath,
                apkSha256 = overview.apkSha256,
                policy = parsePolicy(policy?.value),
                permissions = permissions.map { it.toRow() },
                backups = backups.map { it.toRow() },
                operations = operations.map { it.toRow() },
            )
        }
    }

    fun observeRecentOperations(limit: Int): Flow<List<OperationRow>> =
        operationDao.observeRecent(limit).map { rows -> rows.map { it.toRow() } }

    suspend fun createInstance(
        appId: String,
        displayName: String,
        policy: InstancePolicySpec,
    ): EngineResult<String> {
        val app = appDao.find(appId)
            ?: return notFound("应用记录不存在：$appId")
        val instanceId = Ids.newInstanceId()

        val installed = provider.use { engine -> engine.install(app.apkPath, instanceId, policy) }
        val result = when (installed) {
            is EngineResult.Failure -> {
                record("create_instance", instanceId, STATUS_FAILED, installed.error.detail)
                log.error("创建实例失败：${installed.error.toLogLine()}")
                return installed
            }
            is EngineResult.Success -> installed.value
        }

        val virtualRoot = provider.use { engine -> engine.status() }.valueOrNull()?.virtualRoot
        val now = System.currentTimeMillis()
        val persisted = engineRunCatching {
            instanceDao.upsert(
                InstanceEntity(
                    instanceId = result.instanceId,
                    appId = appId,
                    displayName = displayName.trim().ifEmpty { app.label.ifBlank { app.packageName } },
                    packageName = app.packageName,
                    dataPath = layout.dataPath(result.instanceId, virtualRoot),
                    state = result.state.name,
                    createdAtMs = now,
                    updatedAtMs = now,
                    lastLaunchedAtMs = null,
                    lastError = null,
                ),
            )
            configDao.upsert(
                EngineConfigEntity(
                    key = policyKey(result.instanceId),
                    value = policy.toJsonString(),
                    version = 1,
                    updatedAtMs = now,
                ),
            )
            val manifestPermissions = decodeStringList(app.permissionsJson)
            if (manifestPermissions.isNotEmpty()) {
                permissionDao.upsertAll(
                    manifestPermissions.map { permission ->
                        PermissionEntity(
                            instanceId = result.instanceId,
                            permission = permission,
                            // Default is "ask": nothing is silently granted, not even
                            // at the virtual layer.
                            policy = PermissionPolicy.PROMPT.name,
                            updatedAtMs = now,
                        )
                    },
                )
            }
            record("create_instance", result.instanceId, STATUS_SUCCEEDED, null)
            log.info("已创建实例 ${result.instanceId}（${app.packageName}）")
        }
        return when (persisted) {
            is EngineResult.Failure -> persisted
            is EngineResult.Success -> EngineResult.Success(result.instanceId)
        }
    }

    /**
     * Everything the host needs before a guest may be marked RUNNING.
     *
     * The engine only owns a state machine here: flipping it without a readable
     * APK copy, a declared launcher, a supported ABI or an existing data
     * directory produces exactly the "运行中 but nothing happens" case. Those
     * checks belong on this side because only the client knows the device.
     */
    suspend fun prepareLaunch(instanceId: String): EngineResult<LaunchRequest> {
        val instance = instanceDao.find(instanceId) ?: return notFound("实例不存在：$instanceId")
        val app = appDao.find(instance.appId) ?: return notFound("应用记录不存在：${instance.appId}")

        val apk = File(app.apkPath)
        if (!apk.isFile || apk.length() <= 0L) {
            return EngineResult.Failure(
                EngineError(EngineErrorCode.NOT_FOUND, "APK 副本已丢失：${app.apkPath}"),
            )
        }
        // 二次打包的包常常删掉 MAIN/LAUNCHER 的 intent-filter，这里再硬失败就
        // 等于把这类包直接判死（UI 上表现为"当前环境不支持"）。真正的回退在
        // GuestRuntime.prepare 里做（Splash → exported → 第一个 Activity）。
        val launcher = app.launcherActivity?.takeIf { it.isNotBlank() }.orEmpty()
        if (launcher.isEmpty()) {
            log.info("${app.packageName} 的 Manifest 未声明启动 Activity，运行时按清单回退")
        }
        if (app.minSdk > Build.VERSION.SDK_INT) {
            return EngineResult.Failure(
                EngineError(
                    EngineErrorCode.UNSUPPORTED,
                    "该应用要求 Android API ${app.minSdk}，本机为 ${Build.VERSION.SDK_INT}",
                ),
            )
        }
        val apkAbis = decodeStringList(app.nativeAbisJson)
        if (apkAbis.isNotEmpty() && apkAbis.none { it in Build.SUPPORTED_ABIS }) {
            return EngineResult.Failure(
                EngineError(
                    EngineErrorCode.UNSUPPORTED,
                    "APK 仅提供 ${apkAbis.joinToString()}，本机 ABI 为 ${Build.SUPPORTED_ABIS.joinToString()}",
                ),
            )
        }
        if (!File(instance.dataPath).isDirectory) {
            return EngineResult.Failure(
                EngineError(EngineErrorCode.NOT_FOUND, "实例数据目录不存在：${instance.dataPath}"),
            )
        }

        return EngineResult.Success(
            LaunchRequest(
                instanceId = instanceId,
                packageName = app.packageName,
                displayName = instance.displayName,
                apkPath = app.apkPath,
                launcherActivity = launcher,
                minSdk = app.minSdk,
                dataPath = instance.dataPath,
            ),
        )
    }

    suspend fun launch(instanceId: String): EngineResult<LaunchRequest> {
        val request = when (val prepared = prepareLaunch(instanceId)) {
            is EngineResult.Failure -> {
                record("launch", instanceId, STATUS_FAILED, prepared.error.detail)
                log.error("启动预检未通过：${prepared.error.toLogLine()}")
                return prepared
            }
            is EngineResult.Success -> prepared.value
        }
        val started = provider.use { engine -> engine.launch(instanceId) }
        if (started is EngineResult.Failure) {
            record("launch", instanceId, STATUS_FAILED, started.error.detail)
            log.error("启动失败：${started.error.toLogLine()}")
            return started
        }
        val now = System.currentTimeMillis()
        val current = provider.use { engine -> engine.getInstance(instanceId) }.valueOrNull()
        instanceDao.updateState(
            instanceId = instanceId,
            state = (current?.state ?: VirtualInstanceState.STARTING).name,
            lastError = current?.lastError,
            nowMs = now,
        )
        instanceDao.markLaunched(instanceId, now)
        record("launch", instanceId, STATUS_SUCCEEDED, null)
        log.info("已启动实例 $instanceId")
        return EngineResult.Success(request)
    }

    suspend fun stop(instanceId: String): EngineResult<Unit> {
        instanceDao.find(instanceId) ?: return notFound("实例不存在：$instanceId")
        val stopped = provider.use { engine -> engine.stop(instanceId) }
        if (stopped is EngineResult.Failure) {
            record("stop", instanceId, STATUS_FAILED, stopped.error.detail)
            return stopped
        }
        val current = provider.use { engine -> engine.getInstance(instanceId) }.valueOrNull()
        // 账本一律落到 STOPPED，**不**回读引擎状态。
        //
        // guest 现在跑在独立的 `:guest` 进程里，被 AMS / LMK 杀掉时引擎根本不知道，
        // 账本会停在 RUNNING；`stop` 之后 `getInstance` 仍可能返回 RUNNING（引擎侧
        // 状态滞后），照抄回去就变成"停不掉，UI 永远显示运行中"（实测 stop 输出 OK
        // 但 list 还是 RUNNING）。用户显式停止的语义是确定的，直接落 STOPPED。
        instanceDao.updateState(
            instanceId = instanceId,
            state = VirtualInstanceState.STOPPED.name,
            lastError = current?.lastError,
            nowMs = System.currentTimeMillis(),
        )
        record("stop", instanceId, STATUS_SUCCEEDED, null)
        log.info("已停止实例 $instanceId")
        return EngineResult.Success(Unit)
    }

    /**
     * 卸载实例：引擎侧移除 + 本地记录一并清空。
     *
     * 和「清除数据」的分工：清除数据只清虚拟数据目录，实例还在；卸载是把这个实例
     * 从设备和列表里彻底移走 —— 数据目录（引擎负责删）、权限行、策略配置、备份
     * 记录连同磁盘上的 zip、以及它名下的操作记录，全部不留下。否则「最近记录」里
     * 会挂着指向已删实例的孤儿行。
     */
    suspend fun uninstall(instanceId: String): EngineResult<Unit> {
        val instance = instanceDao.find(instanceId) ?: return notFound("实例不存在：$instanceId")
        val removed = provider.use { engine -> engine.uninstall(instanceId) }
        if (removed is EngineResult.Failure) {
            record("uninstall", instanceId, STATUS_FAILED, removed.error.detail)
            return removed
        }
        return engineRunCatching {
            backupDao.allFor(instanceId).forEach { backup ->
                runCatching { File(backup.backupPath).delete() }
                backupDao.delete(backup.backupId)
            }
            permissionDao.clear(instanceId)
            operationDao.deleteForInstance(instanceId)
            configDao.delete(policyKey(instanceId))
            instanceDao.delete(instanceId)
            // 这条 trace 只进宿主日志缓冲；实例的操作记录已经清空，不再补记。
            log.info("已卸载实例 $instanceId（${instance.displayName}），相关记录已一并移除")
        }
    }

    /**
     * 清除数据：把实例重置成"刚装好"的状态，实例本身（id / 名称 / 权限 / 策略）保留。
     *
     * 实现上是「卸载 + 用同一份本地 APK 副本、同一个 instanceId 重装」，而不是直接
     * 删数据目录 —— 试过后者，不行：引擎把解包产物（`lib-main`、`app_librarian`、
     * 插件 dex）也放在实例目录里，光删数据目录它仍认为"已安装"，不会重新解包，
     * 启动时 guest 直接崩在 `RuntimeException: had you called RpcService.init(Application,RpcConfig)`，
     * 整页黑屏（2026-11-11 16:39 实测）。重装走的是和「再装一个实例」完全相同的
     * 引擎路径，目录内容与全新安装一致（同一次实验里新建的实例能正常进「剧场」）。
     *
     * 重装用的是 `AppRepository` 里已导入的那份 APK，不需要用户再选文件，所以
     * 代价只是一次本地拷贝 + 解包（几秒），比"删实例、再挑 APK、再装"省事。
     *
     * 必须在停止状态下做：guest 和宿主同进程，运行时会继续往这些目录里写。
     */
    suspend fun clearData(instanceId: String): EngineResult<Unit> {
        val instance = instanceDao.find(instanceId) ?: return notFound("实例不存在：$instanceId")
        if (stateOf(instance.state).isActive) {
            return EngineResult.Failure(
                EngineError(EngineErrorCode.INVALID_STATE, "请先停止实例再清除数据"),
            )
        }
        val app = appDao.find(instance.appId)
            ?: return notFound("应用记录不存在：${instance.appId}")
        val policy = configDao.find(policyKey(instanceId))
            ?.let { row -> runCatching { parsePolicy(row.value) }.getOrNull() }
            ?: InstancePolicySpec()

        val removed = provider.use { engine -> engine.uninstall(instanceId) }
        if (removed is EngineResult.Failure) {
            record("clear_data", instanceId, STATUS_FAILED, removed.error.detail)
            return removed
        }
        val installed = when (val reinstalled = provider.use { engine ->
            engine.install(app.apkPath, instanceId, policy)
        }) {
            is EngineResult.Failure -> {
                record("clear_data", instanceId, STATUS_FAILED, reinstalled.error.detail)
                log.error("清除数据失败（重装）：${reinstalled.error.toLogLine()}")
                return reinstalled
            }
            is EngineResult.Success -> reinstalled.value
        }

        val virtualRoot = provider.use { engine -> engine.status() }.valueOrNull()?.virtualRoot
        val now = System.currentTimeMillis()
        return engineRunCatching {
            instanceDao.upsert(
                InstanceEntity(
                    instanceId = installed.instanceId,
                    appId = app.appId,
                    displayName = instance.displayName,
                    packageName = instance.packageName,
                    dataPath = layout.dataPath(installed.instanceId, virtualRoot),
                    state = installed.state.name,
                    createdAtMs = instance.createdAtMs,
                    updatedAtMs = now,
                    lastLaunchedAtMs = null,
                    lastError = null,
                ),
            )
            record("clear_data", instanceId, STATUS_SUCCEEDED, "重新解包，数据已重置")
            log.info("已清除实例 $instanceId 的数据（重新解包，保留实例名与权限）")
        }
    }

    suspend fun rename(instanceId: String, displayName: String): EngineResult<Unit> {
        val trimmed = displayName.trim()
        if (trimmed.isEmpty()) {
            return EngineResult.Failure(EngineError(EngineErrorCode.INVALID_ARGUMENT, "显示名称不能为空"))
        }
        instanceDao.find(instanceId) ?: return notFound("实例不存在：$instanceId")
        return engineRunCatching {
            instanceDao.rename(instanceId, trimmed, System.currentTimeMillis())
        }
    }

    suspend fun setPermissionPolicy(
        instanceId: String,
        permission: String,
        policy: PermissionPolicy,
    ): EngineResult<Unit> = engineRunCatching {
        permissionDao.upsert(
            PermissionEntity(
                instanceId = instanceId,
                permission = permission,
                policy = policy.name,
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    /** Directory usage of one instance; runs on the caller's IO dispatcher. */
    suspend fun storageBytes(instanceId: String): Long {
        val instance = instanceDao.find(instanceId) ?: return 0L
        return layout.usageBytes(File(instance.dataPath))
    }

    suspend fun createBackup(instanceId: String): EngineResult<BackupRow> {
        val instance = instanceDao.find(instanceId) ?: return notFound("实例不存在：$instanceId")
        if (stateOf(instance.state).isActive) {
            return EngineResult.Failure(
                EngineError(EngineErrorCode.INVALID_STATE, "实例运行中，无法备份"),
            )
        }
        return engineRunCatching {
            val backupId = Ids.newBackupId()
            layout.ensureBackupDir()
            val target = layout.backupFile(backupId)
            val bytes = InstanceArchive.zip(File(instance.dataPath), target)
            val entity = BackupRecordEntity(
                backupId = backupId,
                instanceId = instanceId,
                backupPath = target.absolutePath,
                createdAtMs = System.currentTimeMillis(),
                sizeBytes = bytes,
                status = BACKUP_READY,
            )
            backupDao.upsert(entity)
            record("backup", instanceId, STATUS_SUCCEEDED, target.name)
            log.info("已备份实例 $instanceId（${bytes} 字节）")
            entity.toRow()
        }
    }

    suspend fun restoreBackup(backupId: String): EngineResult<Unit> {
        val backup = backupDao.find(backupId) ?: return notFound("备份不存在：$backupId")
        val instance = instanceDao.find(backup.instanceId) ?: return notFound("实例不存在：${backup.instanceId}")
        if (stateOf(instance.state).isActive) {
            return EngineResult.Failure(
                EngineError(EngineErrorCode.INVALID_STATE, "请先停止实例再恢复备份"),
            )
        }
        return engineRunCatching {
            InstanceArchive.unzip(File(backup.backupPath), File(instance.dataPath))
            backupDao.upsert(backup.copy(status = BACKUP_RESTORED))
            record("restore", instance.instanceId, STATUS_SUCCEEDED, backup.backupPath)
            log.info("已从备份恢复实例 ${instance.instanceId}")
        }
    }

    /** Pulls engine truth into the database; also used by the diagnostics screen. */
    suspend fun refreshFromEngine(): EngineResult<Unit> = provider.use { engine ->
        when (val listed = engine.getInstances()) {
            is EngineResult.Failure -> EngineResult.Failure(listed.error)
            is EngineResult.Success -> {
                val known = instanceDao.all()
                val now = System.currentTimeMillis()
                val live = listed.value.associateBy { it.instanceId }
                known.forEach { instance ->
                    val current = live[instance.instanceId]
                    if (current == null) {
                        if (stateOf(instance.state) != VirtualInstanceState.STOPPED) {
                            instanceDao.updateState(
                                instanceId = instance.instanceId,
                                state = VirtualInstanceState.STOPPED.name,
                                lastError = "引擎中不存在该实例，已按已停止处理",
                                nowMs = now,
                            )
                        }
                    } else if (instance.state != current.state.name || instance.lastError != current.lastError) {
                        instanceDao.updateState(
                            instanceId = instance.instanceId,
                            state = current.state.name,
                            lastError = current.lastError,
                            nowMs = now,
                        )
                    }
                }
                EngineResult.Success(Unit)
            }
        }
    }

    /**
     * 自动命名统一成「<应用名> N」，把历史的两类长名字改短：
     *  - 标签未知时按包名生成：`<packageName> 副本` → `<应用名>`
     *  - 旧副本格式：`<应用名> 副本` / `<应用名> 副本 2` → `<应用名>` / `<应用名> 2`
     *
     * 用户手动改过的名字不匹配 `... 副本(N)`，不会被覆盖。目标名已被同 app 的
     * 其它实例占用时往后顺延，避免出现两个同名实例。每次启动都跑，幂等。
     */
    suspend fun migrateLegacyNames() {
        val now = System.currentTimeMillis()
        val rows = instanceDao.all()
        val taken = rows.groupBy { it.appId }
            .mapValues { (_, list) -> list.map { it.displayName }.toMutableSet() }
            .toMutableMap()
        rows.forEach { instance ->
            val current = instance.displayName
            val match = LEGACY_COPY_NAME.matchEntire(current) ?: return@forEach
            val base = match.groupValues[1]
            val index = match.groupValues[2]
            val label = appDao.find(instance.appId)?.label?.takeIf { it.isNotBlank() }
            // 早期按包名生成的行，标签回填后换成真实应用名。
            val appName = if (label != null && (base == instance.packageName || base == label)) {
                label
            } else {
                base
            }
            val names = taken.getOrPut(instance.appId) { mutableSetOf() }
            var suffix = index.toIntOrNull() ?: 1
            var candidate = if (index.isEmpty()) appName else "$appName $suffix"
            while (candidate in names) {
                suffix += 1
                candidate = "$appName $suffix"
            }
            if (candidate == current) return@forEach
            names.remove(current)
            names.add(candidate)
            instanceDao.rename(instance.instanceId, candidate, now)
            log.info("已迁移实例名：$current → $candidate")
        }
    }

    private val LEGACY_COPY_NAME = Regex("""^(.+) 副本(?: (\d+))?$""")

    /**
     * Cold-start reconciliation: rows persisted as running cannot still be
     * running in a fresh host process. The engine does the same on its side;
     * this keeps the DB from showing a green dot over a dead guest.
     */
    suspend fun reconcileAfterRestart() {
        val now = System.currentTimeMillis()
        instanceDao.all().forEach { instance ->
            if (stateOf(instance.state).isActive) {
                instanceDao.updateState(
                    instanceId = instance.instanceId,
                    state = VirtualInstanceState.STOPPED.name,
                    lastError = "宿主进程重启后自动恢复为已停止",
                    nowMs = now,
                )
            }
        }
        refreshFromEngine()
    }

    /**
     * 运行态对账：`:guest` 子进程被 AMS / LMK 杀掉时引擎完全不知道，Room 会一直停在
     * RUNNING（实测 `run-as kill -9 <guest pid>` 后宿主首页仍显示「运行中 1」）。
     * 冷启动那次 [reconcileAfterRestart] 只能管"宿主进程重启"，管不了运行中掉的进程。
     *
     * [aliveInstanceIds] 是全池仍持有存活进程的实例集合。空集合是确定的全池无进程；
     * 进程表或任一槽位归属读不到时调用方会跳过本轮，不会把 UNKNOWN 传成空集合。
     *
     * [graceMs] 是启动宽限期，避免 gate 拉起槽位之前误判刚启动的实例。
     *
     * @return 被收敛为 STOPPED 的实例 id。
     */
    suspend fun reconcileDeadGuests(
        aliveInstanceIds: Set<String>,
        graceMs: Long = 15_000,
    ): List<String> {
        val now = System.currentTimeMillis()
        val converged = mutableListOf<String>()
        instanceDao.all().forEach { row ->
            val state = stateOf(row.state)
            if (!GuestLivenessReconcilePolicy.shouldStop(
                    state = state,
                    instanceId = row.instanceId,
                    lastLaunchedAtMs = row.lastLaunchedAtMs,
                    aliveInstanceIds = aliveInstanceIds,
                    nowMs = now,
                    graceMs = graceMs,
                )
            ) {
                return@forEach
            }

            instanceDao.updateState(
                instanceId = row.instanceId,
                state = VirtualInstanceState.STOPPED.name,
                lastError = "虚拟应用进程已退出，自动恢复为已停止",
                nowMs = now,
            )
            record("reconcile", row.instanceId, STATUS_SUCCEEDED, "PROCESS_GONE")
            log.info("PROCESS_GONE：${row.instanceId} 从 $state 收敛为 STOPPED")
            converged += row.instanceId
        }
        return converged
    }

    suspend fun applyEvent(event: EngineEvent) {
        when (event) {
            is EngineEvent.InstanceStateChanged -> {
                if (instanceDao.find(event.instanceId) != null) {
                    instanceDao.updateState(
                        instanceId = event.instanceId,
                        state = event.to.name,
                        lastError = null,
                        nowMs = System.currentTimeMillis(),
                    )
                }
            }
            is EngineEvent.Notice -> log.record(
                level = when (event.level) {
                    com.adv.core.engine.api.NoticeLevel.INFO -> LogLevel.INFO
                    com.adv.core.engine.api.NoticeLevel.WARNING -> LogLevel.WARNING
                    com.adv.core.engine.api.NoticeLevel.ERROR -> LogLevel.ERROR
                },
                message = event.message,
            )
            is EngineEvent.TaskProgress -> log.info("任务 ${event.name} 进度 ${event.progress}%")
            is EngineEvent.TaskFinished -> {
                log.record(
                    level = if (event.success) LogLevel.INFO else LogLevel.ERROR,
                    message = "任务 ${event.name} #${event.taskId} ${if (event.success) "完成" else "失败"}",
                )
            }
        }
    }

    private suspend fun record(operation: String, instanceId: String?, status: String, message: String?) {
        val now = System.currentTimeMillis()
        operationDao.upsert(
            OperationRecordEntity(
                taskId = Ids.newTaskId(),
                operation = operation,
                status = status,
                instanceId = instanceId,
                createdAtMs = now,
                finishedAtMs = if (status == STATUS_RUNNING) null else now,
                message = message,
            ),
        )
    }

    private fun notFound(detail: String): EngineResult.Failure =
        EngineResult.Failure(EngineError(EngineErrorCode.NOT_FOUND, detail))

    private fun policyKey(instanceId: String): String = "instance_policy.$instanceId"

    private companion object {
        const val HISTORY_LIMIT = 20
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_SUCCEEDED = "SUCCEEDED"
        const val STATUS_FAILED = "FAILED"
        const val BACKUP_READY = "READY"
        const val BACKUP_RESTORED = "RESTORED"
    }
}
