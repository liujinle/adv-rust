package com.adv.core.engine.impl

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/**
 * Copies picked APKs into app-private storage before the native core reads them.
 *
 * `content://` grants are per-activity and expire; the engine needs a path it can
 * reopen later, so the bytes are copied once and the copy becomes the source of
 * truth for every later install of that build.
 */
internal class ApkStaging(private val context: Context, private val directory: File) {

    fun stage(apkUri: String): File {
        existingFile(apkUri)?.let { return it }

        val uri = Uri.parse(apkUri)
        val scheme = uri.scheme
        if (scheme != "content" && scheme != "file") {
            throw IOException("unsupported apk uri: $apkUri")
        }
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("cannot create ${directory.absolutePath}")
        }
        val target = File(directory, "${UUID.randomUUID()}.apk")
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("cannot open $apkUri")
        try {
            input.use { source ->
                target.outputStream().use { sink -> source.copyTo(sink) }
            }
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
        if (target.length() == 0L) {
            target.delete()
            throw IOException("$apkUri produced an empty file")
        }
        return target
    }

    fun sha256(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }.getOrNull()

    /** A path the engine can read directly, without copying. */
    fun existingFile(apkUri: String): File? {
        val candidate = when {
            !apkUri.contains("://") -> File(apkUri)
            apkUri.startsWith("file://") -> apkUri.removePrefix("file://").let(::File)
            else -> return null
        }
        return candidate.takeIf { it.isFile }
    }
}
