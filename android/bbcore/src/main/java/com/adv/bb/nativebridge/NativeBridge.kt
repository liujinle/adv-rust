package com.adv.bb.nativebridge

/**
 * 1:1 mirror of the symbols exported from `native/bb-bridge/src/jni.rs`.
 *
 * Rules that keep this file honest:
 * * package/class/method names *are* the ABI — renaming a method silently breaks
 *   the lookup at runtime, so the host smoke test
 *   (`tests/jni/java/com/adv/bb/nativebridge/NativeBridge.java`) declares the
 *   same methods and is executed against the real `.so`/`.dll`;
 * * string arguments are copied into Rust and released before returning: no JNI
 *   local reference outlives a call;
 * * anything returning a string returns null on failure; details are available
 *   through [nativeLastError].
 *
 * Application code should use `com.adv.bb.core.BbEngine`, not this object.
 */
object NativeBridge {

    const val LIBRARY_NAME: String = "bb_native"

    init {
        System.loadLibrary(LIBRARY_NAME)
    }

    external fun nativeAbiVersion(): Int

    external fun nativeCreate(): Long

    external fun nativeDestroy(handle: Long)

    /** @return 0 on success, negative `ErrorCode` on failure. */
    external fun nativeInitializeJson(handle: Long, configJson: String): Int

    /** @return the JSON response, or null if the call itself failed. */
    external fun nativeRequestJson(handle: Long, requestJson: String): String?

    /** @return a positive task id, or a negative `ErrorCode`. */
    external fun nativeSubmitTaskJson(handle: Long, commandJson: String): Long

    /** @return the task snapshot JSON, or null while the id is unknown. */
    external fun nativeTaskResultJson(handle: Long, taskId: Long): String?

    external fun nativeSummaryJson(handle: Long): String?

    external fun nativeLastError(handle: Long): String?
}
