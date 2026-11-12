package com.adv.core.data

import com.adv.core.common.EngineResult
import com.adv.core.common.engineRunCatching
import com.adv.core.database.ConfigDao
import com.adv.core.database.EngineConfigEntity
import com.adv.core.engine.api.InstancePolicySpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * `engine_config` is the only settings store: no SharedPreferences, no second
 * source of truth.
 *
 * `log_level`, `max_instances` and `cache_budget_bytes` are read before the
 * engine is constructed, so changing them takes effect on the next app start —
 * the settings screen says so instead of pretending a live re-configure exists.
 */
class SettingsRepository(private val configDao: ConfigDao) {

    fun observe(): Flow<AppSettings> = configDao.observeAll().map { entries -> entries.toSettings() }

    suspend fun load(): AppSettings = configDao.all().toSettings()

    suspend fun setThemeMode(mode: ThemeMode): EngineResult<Unit> = write(Keys.THEME, mode.name)

    suspend fun setLogLevel(level: String): EngineResult<Unit> = write(Keys.LOG_LEVEL, level)

    suspend fun setMaxInstances(value: Int): EngineResult<Unit> = write(Keys.MAX_INSTANCES, value.toString())

    suspend fun setCacheBudgetBytes(value: Long): EngineResult<Unit> =
        write(Keys.CACHE_BUDGET, value.toString())

    suspend fun setLockPortrait(value: Boolean): EngineResult<Unit> =
        write(Keys.LOCK_PORTRAIT, value.toString())

    suspend fun setDefaultPolicy(policy: InstancePolicySpec): EngineResult<Unit> {
        val json = policy.toJsonString()
        return write(Keys.DEFAULT_POLICY, json)
    }

    private suspend fun write(key: String, value: String): EngineResult<Unit> = engineRunCatching {
        configDao.upsert(
            EngineConfigEntity(
                key = key,
                value = value,
                version = 1,
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    private fun List<EngineConfigEntity>.toSettings(): AppSettings {
        val values = associate { it.key to it.value }
        return AppSettings(
            themeMode = runCatching { ThemeMode.valueOf(values[Keys.THEME].orEmpty()) }
                .getOrDefault(ThemeMode.SYSTEM),
            logLevel = values[Keys.LOG_LEVEL] ?: "info",
            maxInstances = values[Keys.MAX_INSTANCES]?.toIntOrNull()?.coerceIn(1, 256) ?: 64,
            cacheBudgetBytes = values[Keys.CACHE_BUDGET]?.toLongOrNull() ?: (128L * 1024L * 1024L),
            defaultPolicy = parsePolicy(values[Keys.DEFAULT_POLICY]),
            lockPortrait = values[Keys.LOCK_PORTRAIT] == "true",
        )
    }

    private object Keys {
        const val THEME = "ui.theme"
        const val LOCK_PORTRAIT = "ui.lock_portrait"
        const val LOG_LEVEL = "engine.log_level"
        const val MAX_INSTANCES = "engine.max_instances"
        const val CACHE_BUDGET = "engine.cache_budget_bytes"
        const val DEFAULT_POLICY = "policy.default"
    }
}
