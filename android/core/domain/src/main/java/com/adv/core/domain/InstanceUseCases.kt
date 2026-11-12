package com.adv.core.domain

import com.adv.core.common.EngineResult
import com.adv.core.data.BackupRow
import com.adv.core.data.InstanceDetails
import com.adv.core.data.InstanceOverview
import com.adv.core.data.InstanceRepository
import com.adv.core.data.LaunchRequest
import com.adv.core.data.PermissionPolicy
import com.adv.core.engine.api.InstancePolicySpec
import kotlinx.coroutines.flow.Flow

class ObserveInstancesUseCase(private val instances: InstanceRepository) {
    operator fun invoke(): Flow<List<InstanceOverview>> = instances.observeInstances()
}

class ObserveInstanceUseCase(private val instances: InstanceRepository) {
    operator fun invoke(instanceId: String): Flow<InstanceDetails?> =
        instances.observeInstance(instanceId)
}

class CreateInstanceUseCase(private val instances: InstanceRepository) {
    suspend operator fun invoke(
        appId: String,
        displayName: String,
        policy: InstancePolicySpec,
    ): EngineResult<String> = instances.createInstance(appId, displayName, policy)
}

/**
 * Instance operations share one entry point: every one of them has to refresh
 * the persisted state, record an operation and map errors the same way.
 */
class InstanceActionsUseCase(
    private val instances: InstanceRepository,
    /**
     * 实例被重置后要一并丢掉宿主侧的按实例缓存（解包出来的 `.so`、内存里的 guest）。
     * 由 app 层注入：`core` 不该知道 `GuestRuntime` 和 `files/guest-libs` 的存在。
     */
    private val onInstanceReset: (String) -> Unit = {},
    /**
     * 停实例 / 清数据 / 卸载之前，先把 guest 子进程杀干净。
     *
     * 账本（实例状态、配额、目录）只在主进程写，guest 在 `:guest` 进程里跑；账本里
     * 记成"已停止"而进程还活着时去删目录，guest 会边清边写，把刚重建的数据目录
     * 写脏（现象就是"清了数据，首启直接崩"）。由 app 层注入，`core` 不认识进程。
     */
    private val onGuestStop: (String) -> Unit = {},
    /**
     * 把已经在运行的实例任务切回前台（列表 / 详情里的「跳转」）。
     *
     * 由 app 层注入 `GuestProcess.moveGuestTaskToFront`：按实例找到它占用的槽位，
     * 再把这个槽位的任务卡 `moveToFront()`。返回 false 表示当前没有可切的任务
     * （进程已退、或还停在启动 gate 里）。
     */
    private val onGuestBringToFront: (String) -> Boolean = { false },
) {

    /** 跳转：纯宿主侧动作，不动账本、不写操作记录。 */
    fun bringToFront(instanceId: String): Boolean = onGuestBringToFront(instanceId)

    suspend fun launch(instanceId: String): EngineResult<LaunchRequest> = instances.launch(instanceId)

    suspend fun stop(instanceId: String): EngineResult<Unit> {
        onGuestStop(instanceId)
        return instances.stop(instanceId)
    }

    /** 卸载：实例、数据目录、备份与操作记录一起移除。 */
    suspend fun uninstall(instanceId: String): EngineResult<Unit> {
        onGuestStop(instanceId)
        return instances.uninstall(instanceId).also { onInstanceReset(instanceId) }
    }

    /** 清数据：把实例重置成刚装好的状态，实例 id / 名称 / 权限 / 备份保留。 */
    suspend fun clearData(instanceId: String): EngineResult<Unit> {
        onGuestStop(instanceId)
        return instances.clearData(instanceId).also { onInstanceReset(instanceId) }
    }

    suspend fun rename(instanceId: String, displayName: String): EngineResult<Unit> =
        instances.rename(instanceId, displayName)

    suspend fun setPermission(
        instanceId: String,
        permission: String,
        policy: PermissionPolicy,
    ): EngineResult<Unit> = instances.setPermissionPolicy(instanceId, permission, policy)

    suspend fun backup(instanceId: String): EngineResult<BackupRow> =
        instances.createBackup(instanceId)

    suspend fun restore(backupId: String): EngineResult<Unit> = instances.restoreBackup(backupId)

    suspend fun storageBytes(instanceId: String): Long = instances.storageBytes(instanceId)

    suspend fun refresh(): EngineResult<Unit> = instances.refreshFromEngine()
}
