package com.adv.bb.app.guest

import android.app.Activity
import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import com.adv.core.data.LaunchRequest
import dalvik.system.DexClassLoader
import java.io.File

/**
 * What we could actually obtain for a guest inside the host process.
 *
 * Every field is measured, not assumed: the point of this report is that a
 * failed launch says *where* it failed instead of leaving the instance marked
 * RUNNING with nothing behind it.
 */
data class GuestLoadReport(
    val classLoaderOk: Boolean,
    val launcherLoadable: Boolean,
    val resourcesLoaded: Boolean,
    val notes: List<String>,
) {
    val ok: Boolean get() = classLoaderOk && launcherLoadable && resourcesLoaded
}

/**
 * First stage of the host-side guest runtime: load the staged APK's dex and
 * resources with the same primitives the compatibility layer will use.
 *
 * This is deliberately side-effect free — it does not attach the guest Activity.
 * It answers "can this APK be loaded at all on this device", which is the
 * prerequisite for the Activity/Instrumentation stage.
 */
class GuestLoader(private val context: Context) {

    fun load(request: LaunchRequest): GuestLoadReport {
        val notes = mutableListOf<String>()

        val dexDir = File(context.codeCacheDir, "guest/${request.instanceId}").apply { mkdirs() }
        notes += "dex 缓存目录：${dexDir.absolutePath}"

        // Android 14+ refuses to load code from a writable file ("Writable dex
        // file is not allowed"), and the staged copy is created writable. Mark it
        // read-only once; the bytes never change after import.
        val apk = File(request.apkPath)
        if (apk.canWrite()) {
            notes += if (apk.setReadOnly()) "APK 已设为只读" else "APK 设为只读失败"
        }

        val loader = runCatching {
            DexClassLoader(request.apkPath, dexDir.absolutePath, null, context.classLoader.parent)
        }.onFailure {
            notes += "DexClassLoader 失败：${it.javaClass.simpleName}: ${it.message}"
        }.getOrNull()
        if (loader != null) notes += "DexClassLoader 已创建"

        var launcherLoadable = false
        if (loader != null) {
            runCatching {
                val clazz = Class.forName(request.launcherActivity, false, loader)
                launcherLoadable = Activity::class.java.isAssignableFrom(clazz)
                notes += "launcher 类：${clazz.name}（Activity=${launcherLoadable}）"
            }.onFailure {
                notes += "launcher 类加载失败：${it.javaClass.simpleName}: ${it.message}"
            }
        }

        var resourcesLoaded = false
        runCatching {
            val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
            val addAssetPath = AssetManager::class.java
                .getDeclaredMethod("addAssetPath", String::class.java)
                .apply { isAccessible = true }
            val cookie = addAssetPath.invoke(assets, request.apkPath) as? Int ?: 0
            val host = context.resources
            val guest = Resources(assets, host.displayMetrics, host.configuration)
            val appNameId = guest.getIdentifier("app_name", "string", request.packageName)
            resourcesLoaded = cookie != 0
            notes += "addAssetPath cookie=$cookie，app_name=0x${appNameId.toString(16)}"
        }.onFailure {
            notes += "资源加载失败：${it.javaClass.simpleName}: ${it.message}"
        }

        notes += probeInstrumentationHook()

        return GuestLoadReport(
            classLoaderOk = loader != null,
            launcherLoadable = launcherLoadable,
            resourcesLoaded = resourcesLoaded,
            notes = notes,
        )
    }

    /**
     * Can the host swap `ActivityThread.mInstrumentation`?
     *
     * On API 34+ those members are hidden API; whether a plain reflection call is
     * allowed decides if the attach stage can stay in Kotlin or has to go through
     * the native bridge (JNI field access is not subject to the hidden API list).
     * This probes the real call instead of assuming.
     */
    private fun probeInstrumentationHook(): List<String> {
        val notes = mutableListOf<String>()
        runCatching {
            val threadClass = Class.forName("android.app.ActivityThread")
            val current = threadClass.getDeclaredMethod("currentActivityThread")
                .apply { isAccessible = true }
            val thread = current.invoke(null) ?: error("currentActivityThread() == null")
            notes += "ActivityThread 可获取"

            val field = threadClass.getDeclaredField("mInstrumentation")
                .apply { isAccessible = true }
            val existing = field.get(thread)
            notes += "mInstrumentation = ${existing?.javaClass?.name}"
            // Write back the same object: proves the field is settable without
            // changing behaviour yet.
            field.set(thread, existing)
            notes += "mInstrumentation 可写"
        }.onFailure {
            notes += "hidden API 探测失败：${it.javaClass.simpleName}: ${it.message}"
        }
        return notes
    }
}
