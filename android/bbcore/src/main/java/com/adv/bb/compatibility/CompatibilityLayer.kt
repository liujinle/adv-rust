package com.adv.bb.compatibility

import android.content.Context
import android.os.Build
import com.adv.bb.core.BbEngine

/**
 * One version-scoped adaptation (a hook group, not a single hook).
 *
 * Implementations live under `compatibility/<AndroidXX>/...` and declare the exact
 * API range they were verified on. The registry refuses overlapping ranges for the
 * same [surface], so "which implementation runs on API 34" is answered statically
 * instead of by a chain of `if (SDK_INT >= ...)`.
 */
interface HookAdapter {
    val id: String

    /** Framework surface this adapter patches, e.g. `activity`, `notification`. */
    val surface: String

    val supportedApis: IntRange

    fun install(context: CompatibilityContext)
}

class CompatibilityContext(
    val applicationContext: Context,
    val engine: BbEngine,
    val log: (String) -> Unit = {},
)

data class AdapterStatus(
    val id: String,
    val surface: String,
    val supportedApis: IntRange,
    val installed: Boolean,
    val reason: String,
)

/**
 * Registry + installer for version-scoped adapters.
 *
 * This class is intentionally *not* magic: it does not discover hooks, it does not
 * patch anything by itself. The actual BlackBox hook implementations are ported
 * one by one and registered here, which keeps the compatibility surface auditable.
 */
class CompatibilityLayer(private val context: CompatibilityContext) {

    private val adapters = LinkedHashMap<String, HookAdapter>()
    private val installed = LinkedHashSet<String>()
    private val reasons = HashMap<String, String>()

    fun register(adapter: HookAdapter): CompatibilityLayer {
        require(adapter.id.isNotEmpty()) { "adapter id must not be empty" }
        require(!adapters.containsKey(adapter.id)) { "duplicate adapter id ${adapter.id}" }
        adapters[adapter.id] = adapter
        return this
    }

    fun registerAll(vararg adapters: HookAdapter): CompatibilityLayer {
        adapters.forEach { register(it) }
        return this
    }

    /**
     * Installs every adapter whose range contains [sdkInt].
     *
     * A failing adapter is recorded and skipped: one broken hook group must not
     * prevent the engine from starting (the same policy the original BlackBox
     * applies when a framework method is missing).
     */
    fun installAll(sdkInt: Int = Build.VERSION.SDK_INT): List<String> {
        installed.clear()
        reasons.clear()
        for (adapter in adapters.values) {
            if (sdkInt !in adapter.supportedApis) {
                reasons[adapter.id] = "API $sdkInt is outside ${adapter.supportedApis}"
                continue
            }
            try {
                adapter.install(context)
                installed += adapter.id
                reasons[adapter.id] = "installed"
            } catch (error: Throwable) {
                reasons[adapter.id] = "failed: ${error.message ?: error::class.java.simpleName}"
                context.log("hook adapter ${adapter.id} failed: $error")
            }
        }
        return installed.toList()
    }

    fun status(sdkInt: Int = Build.VERSION.SDK_INT): List<AdapterStatus> =
        adapters.values.map { adapter ->
            AdapterStatus(
                id = adapter.id,
                surface = adapter.surface,
                supportedApis = adapter.supportedApis,
                installed = adapter.id in installed && sdkInt in adapter.supportedApis,
                reason = reasons[adapter.id] ?: "not installed",
            )
        }

    /**
     * Fails fast at startup when two adapters claim the same surface and the same
     * API level. Returns the conflicting surface names.
     */
    fun findRangeConflicts(): List<String> {
        val conflicts = LinkedHashSet<String>()
        val bySurface = adapters.values.groupBy { it.surface }
        for ((surface, group) in bySurface) {
            for (firstIndex in group.indices) {
                for (secondIndex in firstIndex + 1 until group.size) {
                    val first = group[firstIndex]
                    val second = group[secondIndex]
                    if (first.supportedApis.first <= second.supportedApis.last &&
                        second.supportedApis.first <= first.supportedApis.last
                    ) {
                        conflicts += surface
                    }
                }
            }
        }
        return conflicts.toList()
    }
}
