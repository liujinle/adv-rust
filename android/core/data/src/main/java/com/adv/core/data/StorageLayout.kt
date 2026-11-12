package com.adv.core.data

import java.io.File
import java.nio.file.Files

/**
 * Where the engine keeps its files.
 *
 * `bb-vfs` documents the layout as `<root>/virtual/instances/<id>`; the engine
 * also reports `virtual_root` in its summary, which wins when present. The
 * fallback exists so storage/backup still work before the first status refresh.
 */
class StorageLayout(
    private val engineRoot: File,
    private val backupsDir: File,
) {

    fun instancesRoot(virtualRoot: String?): File =
        (virtualRoot?.takeIf { it.isNotBlank() }?.let(::File) ?: engineRoot.resolve("virtual"))
            .resolve("instances")

    fun instanceDir(instanceId: String, virtualRoot: String?): File =
        instancesRoot(virtualRoot).resolve(instanceId)

    fun dataPath(instanceId: String, virtualRoot: String?): String =
        instanceDir(instanceId, virtualRoot).absolutePath

    fun backupFile(backupId: String): File = backupsDir.resolve("$backupId.zip")

    fun ensureBackupDir(): File {
        if (!backupsDir.exists() && !backupsDir.mkdirs()) {
            throw java.io.IOException("cannot create ${backupsDir.absolutePath}")
        }
        return backupsDir
    }

    /** Recursive byte count; symbolic links are skipped, never followed. */
    fun usageBytes(root: File): Long {
        if (!root.exists()) return 0L
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            if (Files.isSymbolicLink(current.toPath())) continue
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (Files.isSymbolicLink(child.toPath())) continue
                if (child.isDirectory) {
                    stack.addLast(child)
                } else {
                    total += child.length()
                }
            }
        }
        return total
    }
}
