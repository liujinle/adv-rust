package com.adv.core.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import java.io.File
import java.io.FileOutputStream

/**
 * Resolves the user-visible name and launcher icon of an APK.
 *
 * The Rust core parses the manifest but not the resource table, so `android:label`
 * and the icon are read here through the platform's own archive parser. The APK is
 * the private copy staged at import time, and PackageManager only reads it — no
 * storage permission and no package visibility required.
 */
class ApkMetadata(context: Context, private val iconsDir: File) {

    private val appContext = context.applicationContext

    data class Resolved(
        val label: String,
        val iconPath: String?,
        /** 平台解析出的启动 Activity；引擎解析不到时用它兜底。 */
        val launcherActivity: String? = null,
    )

    fun resolve(apkPath: String, appId: String): Resolved? {
        val archive = runCatching {
            @Suppress("DEPRECATION") // new flag overload needs API 33; deprecated one still works
            appContext.packageManager.getPackageArchiveInfo(
                apkPath,
                android.content.pm.PackageManager.GET_ACTIVITIES,
            )
        }.getOrNull() ?: return null
        val info = archive.applicationInfo ?: return null

        // Documented trick: PackageManager cannot open the APK's resources until
        // the archive paths are pointed at the file.
        info.sourceDir = apkPath
        info.publicSourceDir = apkPath

        val label = runCatching {
            appContext.packageManager.getApplicationLabel(info).toString()
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

        val iconPath = runCatching {
            saveIcon(appId, info.loadIcon(appContext.packageManager))
        }.getOrNull()

        val launcher = resolveLauncher(archive)
        if (label == null && iconPath == null && launcher == null) return null
        return Resolved(label ?: archive.packageName, iconPath, launcher)
    }

    /**
     * 从清单里挑一个启动 Activity。
     *
     * 二次打包的包（"纯净版"/去广告版）经常把 MAIN+LAUNCHER 的 intent-filter
     * 整段删掉，引擎解析出来的 launcher 就是空串 —— 实例能建但永远起不来。
     * 这里按 Splash → exported → 第一个 的顺序兜底，至少给它一个合法入口。
     */
    private fun resolveLauncher(archive: android.content.pm.PackageInfo): String? {
        val activities = archive.activities.orEmpty()
        if (activities.isEmpty()) return null
        return activities.firstOrNull { it.name.contains("Splash", ignoreCase = true) }?.name
            ?: activities.firstOrNull { it.exported }?.name
            ?: activities.firstOrNull()?.name
    }

    private fun saveIcon(appId: String, drawable: Drawable): String {
        val size = ICON_PX
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)

        iconsDir.mkdirs()
        val target = File(iconsDir, "${appId.toFileName()}.png")
        FileOutputStream(target).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        return target.absolutePath
    }

    private fun String.toFileName(): String = replace(Regex("[^A-Za-z0-9._-]"), "_")

    private companion object {
        /** 48dp at xxxhdpi; the UI downsamples. */
        const val ICON_PX = 192
    }
}
