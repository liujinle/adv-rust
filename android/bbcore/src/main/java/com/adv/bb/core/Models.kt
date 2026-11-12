package com.adv.bb.core

import org.json.JSONObject

/**
 * Guest lifecycle as the core sees it. The Android layer reports real
 * Activity/Service events; the mapping to these values is decided by the core.
 */
enum class InstanceState(val code: Int, val nativeName: String) {
    CREATED(1, "created"),
    PREPARING(2, "preparing"),
    INSTALLED(3, "installed"),
    STARTING(4, "starting"),
    RUNNING(5, "running"),
    STOPPING(6, "stopping"),
    STOPPED(7, "stopped"),
    FAILED(8, "failed");

    val isActive: Boolean
        get() = this == STARTING || this == RUNNING || this == STOPPING

    val isLaunchable: Boolean
        get() = this == INSTALLED || this == STOPPED

    companion object {
        fun fromNative(name: String, code: Int): InstanceState =
            entries.firstOrNull { it.nativeName == name }
                ?: entries.firstOrNull { it.code == code }
                ?: CREATED
    }
}

/**
 * Product-level flags for one instance. Not a security boundary: the guest shares
 * the host UID, so this only controls which proxies accept which calls.
 */
data class InstancePolicy(
    val allowNetwork: Boolean = true,
    val allowExternalStorage: Boolean = false,
    val allowFileUriExposure: Boolean = false,
    val maxStorageBytes: Long = 512L * 1024L * 1024L,
    val allowBackgroundStart: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("allow_network", allowNetwork)
        .put("allow_external_storage", allowExternalStorage)
        .put("allow_file_uri_exposure", allowFileUriExposure)
        .put("max_storage_bytes", maxStorageBytes)
        .put("allow_background_start", allowBackgroundStart)

    companion object {
        fun fromJson(json: JSONObject): InstancePolicy = InstancePolicy(
            allowNetwork = json.optBoolean("allow_network", true),
            allowExternalStorage = json.optBoolean("allow_external_storage", false),
            allowFileUriExposure = json.optBoolean("allow_file_uri_exposure", false),
            maxStorageBytes = json.optLong("max_storage_bytes", 512L * 1024L * 1024L),
            allowBackgroundStart = json.optBoolean("allow_background_start", false),
        )
    }
}

data class EngineConfig(
    val rootPath: String,
    val maxInstances: Int = 64,
    val ioThreads: Int = 2,
    val logLevel: String = "info",
    val metricsHistory: Int = 256,
    val cacheBudgetBytes: Long = 128L * 1024L * 1024L,
    val cacheTtlMs: Long = 7L * 24L * 60L * 60L * 1000L,
    val defaultPolicy: InstancePolicy = InstancePolicy(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("schema_version", 1)
        .put("root_path", rootPath)
        .put("max_instances", maxInstances)
        .put("io_threads", ioThreads)
        .put("log_level", logLevel)
        .put("metrics_history", metricsHistory)
        .put("cache_budget_bytes", cacheBudgetBytes)
        .put("cache_ttl_ms", cacheTtlMs)
        .put("default_policy", defaultPolicy.toJson())
}

/**
 * `instanceId` is deliberately independent from the package name so two copies of
 * the same APK (different version or signer) can coexist.
 */
data class InstanceSpec(
    val instanceId: String,
    val packageName: String,
    val apkPath: String,
    val policy: InstancePolicy? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("instance_id", instanceId)
        .put("package_name", packageName)
        .put("apk_path", apkPath)
        .apply { policy?.let { put("policy", it.toJson()) } }
}

data class InstanceStatus(
    val instanceId: String,
    val packageName: String,
    val state: InstanceState,
    val refs: Int,
    val updatedAtMs: Long,
    val lastError: String?,
) {
    companion object {
        fun fromJson(json: JSONObject): InstanceStatus = InstanceStatus(
            instanceId = json.getString("instance_id"),
            packageName = json.getString("package_name"),
            state = InstanceState.fromNative(json.optString("state_name"), json.optInt("state", 0)),
            refs = json.optInt("refs", 0),
            updatedAtMs = json.optLong("updated_at_ms", 0L),
            lastError = json.optString("last_error").takeIf { it.isNotEmpty() && !json.isNull("last_error") },
        )
    }
}

/**
 * One entry from the runtime's bounded event replay buffer
 * (`bb_common::RuntimeEvent`). State names, not numeric codes.
 */
data class RuntimeEvent(
    val seq: Long,
    val atMs: Long,
    val instanceId: String,
    val from: InstanceState?,
    val to: InstanceState,
    val reason: String,
) {
    companion object {
        fun fromJson(json: JSONObject): RuntimeEvent = RuntimeEvent(
            seq = json.optLong("seq", 0L),
            atMs = json.optLong("at_ms", 0L),
            instanceId = json.optString("instance_id"),
            from = if (json.isNull("from")) {
                null
            } else {
                json.optString("from").takeIf { it.isNotEmpty() }?.let { InstanceState.fromNative(it, 0) }
            },
            to = InstanceState.fromNative(json.optString("to"), 0),
            reason = json.optString("reason"),
        )
    }
}

data class ApkSummary(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val minSdk: Int,
    val targetSdk: Int,
    val permissions: List<String>,
    val launcherActivity: String?,
    val nativeAbis: List<String>,
    val activityCount: Int,
    val fileSize: Long,
) {
    companion object {
        fun fromJson(json: JSONObject): ApkSummary = ApkSummary(
            packageName = json.getString("package"),
            versionCode = json.optLong("version_code", 0L),
            versionName = json.optString("version_name").takeIf { it.isNotEmpty() && !json.isNull("version_name") },
            minSdk = json.optInt("min_sdk", 0),
            targetSdk = json.optInt("target_sdk", 0),
            permissions = json.stringList("permissions"),
            launcherActivity = json.optString("launcher_activity").takeIf { it.isNotEmpty() && !json.isNull("launcher_activity") },
            nativeAbis = json.stringList("native_abis"),
            activityCount = json.optInt("activity_count", 0),
            fileSize = json.optLong("file_size", 0L),
        )
    }
}

private fun JSONObject.stringList(key: String): List<String> {
    val array = optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).map { index -> array.optString(index) }
}
