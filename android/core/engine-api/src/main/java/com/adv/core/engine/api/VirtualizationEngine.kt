package com.adv.core.engine.api

import com.adv.core.common.EngineResult
import kotlinx.coroutines.flow.Flow

/**
 * The contract the client talks to. Nothing above this interface knows about
 * JNI, JSON payloads or file descriptors; nothing below it knows about Compose.
 *
 * `apkUri` accepts a `content://` URI (system file picker), a `file://` URI or an
 * absolute path. Implementations are responsible for staging the bytes into
 * app-private storage before handing the path to the native core.
 */
interface VirtualizationEngine {

    suspend fun initialize(): EngineResult<Unit>

    suspend fun shutdown(): EngineResult<Unit>

    suspend fun inspectApk(apkUri: String): EngineResult<ApkInfo>

    suspend fun install(
        apkUri: String,
        instanceId: String,
        policy: InstancePolicySpec? = null,
    ): EngineResult<InstallResult>

    suspend fun launch(instanceId: String): EngineResult<Unit>

    suspend fun stop(instanceId: String): EngineResult<Unit>

    suspend fun uninstall(instanceId: String): EngineResult<Unit>

    suspend fun getInstances(): EngineResult<List<VirtualInstance>>

    suspend fun getInstance(instanceId: String): EngineResult<VirtualInstance>

    suspend fun status(): EngineResult<EngineStatus>

    /**
     * Lifecycle + task events. The native runtime keeps a replay buffer, so the
     * flow is backed by real engine events rather than a diff of polled state.
     */
    fun observeEvents(): Flow<EngineEvent>
}
