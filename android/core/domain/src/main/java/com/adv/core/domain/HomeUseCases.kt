package com.adv.core.domain

import com.adv.core.data.AppRepository
import com.adv.core.data.EngineRunState
import com.adv.core.data.HomeSnapshot
import com.adv.core.data.InstanceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

/**
 * Home is a shortcut surface, not a monitoring dashboard: one combined snapshot
 * with counts, the engine state and the last few things the user touched.
 */
class ObserveHomeUseCase(
    private val apps: AppRepository,
    private val instances: InstanceRepository,
    private val engineState: StateFlow<EngineRunState>,
) {

    operator fun invoke(): Flow<HomeSnapshot> = combine(
        apps.observeApps(),
        instances.observeInstances(),
        instances.observeRecentOperations(RECENT_OPERATIONS),
        engineState,
    ) { appList, instanceList, operations, state ->
        HomeSnapshot(
            appCount = appList.size,
            instanceCount = instanceList.size,
            runningCount = instanceList.count { it.state.isActive },
            engineState = state,
            recentApps = appList.take(RECENT_APPS),
            recentInstances = instanceList
                .filter { it.lastLaunchedAtMs != null }
                .sortedByDescending { it.lastLaunchedAtMs ?: 0L }
                .take(RECENT_INSTANCES),
            recentOperations = operations,
        )
    }

    private companion object {
        const val RECENT_APPS = 4
        const val RECENT_INSTANCES = 3
        const val RECENT_OPERATIONS = 4
    }
}
