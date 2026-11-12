package com.adv.core.data

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Instance backup/restore as a plain ZIP of the instance data directory.
 *
 * There is no engine-side backup API in this version, so the client archives the
 * directory the engine exposes. Both directions guard against path traversal:
 * a crafted archive must not be able to write outside the instance directory.
 */
internal object InstanceArchive {

    /** Returns the archive size: what the backups list should show. */
    fun zip(sourceDir: File, target: File): Long {
        if (!sourceDir.isDirectory) {
            throw IOException("${sourceDir.absolutePath} is not a directory")
        }
        target.parentFile?.mkdirs()
        val root = sourceDir.toPath()
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            sourceDir.walkTopDown().forEach { file ->
                if (file == sourceDir) return@forEach
                if (Files.isSymbolicLink(file.toPath())) return@forEach
                val relative = root.relativize(file.toPath()).toString().replace('\\', '/')
                if (relative.isEmpty() || relative.contains("..")) return@forEach
                if (file.isDirectory) {
                    zip.putNextEntry(ZipEntry("$relative/"))
                    zip.closeEntry()
                } else if (file.isFile) {
                    zip.putNextEntry(ZipEntry(relative))
                    file.inputStream().use { input -> input.copyTo(zip, 64 * 1024) }
                    zip.closeEntry()
                }
            }
        }
        return target.length()
    }

    fun unzip(archive: File, targetDir: File) {
        val root = targetDir.canonicalFile
        if (!root.exists() && !root.mkdirs()) {
            throw IOException("cannot create ${root.absolutePath}")
        }
        clear(root)
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val destination = File(root, entry.name).canonicalFile
                val inside = destination == root || destination.path.startsWith(root.path + File.separator)
                if (!inside) {
                    throw IOException("zip entry escapes the instance directory: ${entry.name}")
                }
                if (entry.isDirectory) {
                    destination.mkdirs()
                } else {
                    destination.parentFile?.mkdirs()
                    destination.outputStream().use { output -> zip.copyTo(output, 64 * 1024) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun clear(directory: File) {
        directory.listFiles()?.forEach { child ->
            if (Files.isSymbolicLink(child.toPath())) {
                child.delete()
            } else if (child.isDirectory) {
                child.deleteRecursively()
            } else {
                child.delete()
            }
        }
    }
}
