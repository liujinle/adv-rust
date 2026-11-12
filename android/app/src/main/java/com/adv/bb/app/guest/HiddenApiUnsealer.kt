package com.adv.bb.app.guest

import android.content.Context
import android.system.Os
import dalvik.system.DexFile
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * hidden API 豁免。
 *
 * 为什么必须有它：`Process.setArgV0` / `Process.sArgV0` / `ActivityThread.mBoundApplication`
 * 这些都是隐藏成员。Android 9+ 会把它们从 `getDeclaredFields` / `getDeclaredMethod` 里
 * 直接剔除（不是抛异常，是"看不见"），所以反射拿到的结果和"这个字段不存在"一模一样。
 * 进程名伪造的失败链就是这么来的：
 *
 *   1. `getDeclaredMethod("setArgV0")` → NoSuchMethodException
 *   2. `declaredFields` 里一个 String 静态字段都没有 → 写不到缓存
 *   3. 最终 `Process.myProcessName()` 和 `/proc/self/cmdline` 都还是宿主名
 *   → 红果的 Timon 进程门禁判定"进程名不在清单里" → `finishAffinity()` 自杀
 *
 * 而 Android 15/16 把 `VMRuntime.setHiddenApiExemptions` 本身也列进 BLOCKED，
 * 普通反射同样拿不到，只能在 core-platform 域里调。做法是把 APK 里的
 * [HiddenApiExempt] 用 `DexFile.loadClass(name, null)` 以 boot class loader 再定义一份。
 *
 * 判定成功与否不看返回值，而是实测一个被拦住的成员能否被读到。
 */
internal object HiddenApiUnsealer {

    private const val HELPER_CLASS = "com.adv.bb.app.guest.HiddenApiExempt"
    private const val HELPER_METHOD = "exempt"
    private const val EXEMPT_ALL_PREFIX = "L"

    /** 用来验证豁免是否生效的成员：`ActivityThread.H.EXECUTE_TRANSACTION` 是隐藏 int。 */
    private const val PROBE_CLASS = "android.app.ActivityThread\$H"
    private const val PROBE_FIELD = "EXECUTE_TRANSACTION"

    private const val MAX_DEX_ENTRIES = 32

    @Volatile
    private var done = false

    /** 探针快照：把"被拦住了"和"成员真的不存在"区分开，两者报错方式一样。 */
    private class Snapshot(
        val readable: Boolean,
        val setArgV0: Boolean,
        val sArgV0: Boolean,
        val fieldCount: Int,
    ) {
        override fun toString(): String =
            "hiddenReadable=$readable setArgV0=$setArgV0 sArgV0=$sArgV0 fields=$fieldCount"
    }

    fun unseal(context: Context): Boolean {
        if (done) return true
        synchronized(this) {
            if (done) return true

            val before = snapshot()
            GuestRuntime.trace("hiddenApi[before] $before")
            if (before.readable) {
                GuestRuntime.trace("hiddenApi 已可访问，跳过豁免")
                done = true
                return true
            }

            val fromApk = tryFromApkDex(context)
            if (snapshot().readable) {
                GuestRuntime.trace("hiddenApi[after] 豁免成功（直接加载 APK dex，$fromApk）${snapshot()}")
                done = true
                return true
            }

            val fromCopy = tryFromReadOnlyDexCopy(context)
            val after = snapshot()
            if (after.readable) {
                GuestRuntime.trace("hiddenApi[after] 豁免成功（只读 dex 副本，$fromCopy）$after")
                done = true
                return true
            }

            GuestRuntime.trace(
                "hiddenApi[after] 豁免失败 apkDex=$fromApk readonlyCopy=$fromCopy $after",
            )
            return false
        }
    }

    private fun snapshot(): Snapshot {
        val readable = runCatching {
            Class.forName(PROBE_CLASS)
                .getDeclaredField(PROBE_FIELD)
                .apply { isAccessible = true }
                .getInt(null)
            true
        }.getOrDefault(false)
        val fields = runCatching { android.os.Process::class.java.declaredFields }
            .getOrDefault(emptyArray())
        val methods = runCatching { android.os.Process::class.java.declaredMethods }
            .getOrDefault(emptyArray())
        return Snapshot(
            readable = readable,
            setArgV0 = methods.any { it.name == "setArgV0" },
            sArgV0 = fields.any { it.name == "sArgV0" },
            fieldCount = fields.size,
        )
    }

    /** 直接 `DexFile(apk)`：APK 本身只读，不触发 ART 的可写 dex 限制。 */
    private fun tryFromApkDex(context: Context): String {
        var dexFile: DexFile? = null
        return try {
            @Suppress("DEPRECATION")
            dexFile = DexFile(File(context.applicationInfo.sourceDir))
            val helper = dexFile.loadClass(HELPER_CLASS, null)
                ?: return "helper 未被 boot class loader 定义"
            invokeHelper(helper) ?: "ok"
        } catch (t: Throwable) {
            "${t.javaClass.simpleName}: ${t.message}"
        } finally {
            runCatching { dexFile?.close() }
        }
    }

    /** 多 dex 场景下 1 取不到类时，把 classesN.dex 拷到 code_cache、去写位后再打开。 */
    private fun tryFromReadOnlyDexCopy(context: Context): String {
        val cacheDir = context.codeCacheDir ?: context.cacheDir
        var zipFile: ZipFile? = null
        var last = "APK 内没有 dex"
        return try {
            zipFile = ZipFile(context.applicationInfo.sourceDir)
            for (index in 1..MAX_DEX_ENTRIES) {
                val entryName =
                    if (index == 1) "classes.dex" else String.format(Locale.US, "classes%d.dex", index)
                val entry = zipFile.getEntry(entryName) ?: continue
                val failure = loadFromReadOnlyCopy(zipFile, entry, File(cacheDir, "bb-hiddenapi-$index.dex"))
                if (failure == null) return "ok"
                last = failure
            }
            last
        } catch (t: Throwable) {
            "${t.javaClass.simpleName}: ${t.message}"
        } finally {
            runCatching { zipFile?.close() }
        }
    }

    private fun loadFromReadOnlyCopy(zipFile: ZipFile, entry: ZipEntry, target: File): String? {
        var dexFile: DexFile? = null
        return try {
            zipFile.getInputStream(entry).use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output, 16 * 1024) }
            }
            // Android 14+ 只校验模式位：去掉写位即可加载
            runCatching { Os.chmod(target.absolutePath, "444".toInt(8)) }
                .onFailure { target.setReadOnly() }
            @Suppress("DEPRECATION")
            dexFile = DexFile(target)
            val helper = dexFile.loadClass(HELPER_CLASS, null)
                ?: return "helper 不在 ${entry.name}"
            invokeHelper(helper)
        } catch (t: Throwable) {
            "${t.javaClass.simpleName}: ${t.message}"
        } finally {
            runCatching { dexFile?.close() }
            target.delete()
        }
    }

    private fun invokeHelper(helper: Class<*>): String? = try {
        val method = helper.getDeclaredMethod(HELPER_METHOD, String::class.java)
        val result = method.invoke(null, EXEMPT_ALL_PREFIX)
        if (result == true) null else "helper 返回 $result"
    } catch (t: Throwable) {
        "${t.javaClass.simpleName}: ${t.message}"
    }
}
