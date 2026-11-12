package com.adv.core.common

import java.util.UUID

/**
 * Instance ids are UUIDs: `bb_common::InstanceId` accepts `[A-Za-z0-9_.-]` up to
 * 64 bytes, and a UUID satisfies that without any encoding step.
 */
object Ids {
    fun newInstanceId(): String = UUID.randomUUID().toString()

    fun newTaskId(): String = UUID.randomUUID().toString()

    fun newBackupId(): String = UUID.randomUUID().toString()
}

/**
 * Deterministic app key: package + version + content digest.
 *
 * Re-importing the same file updates one row instead of creating duplicates,
 * while a different build of the same package stays a separate row.
 */
fun appKey(packageName: String, versionCode: Long, sha256: String?): String =
    "$packageName@$versionCode#${sha256?.take(16) ?: "unknown"}"
