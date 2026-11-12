package com.adv.bb.compatibility

import android.os.Build

/**
 * Verification stages from the migration plan. A release is only "supported" once
 * its column in `docs/compatibility/matrix.md` is marked VERIFIED on a real device.
 */
enum class VerificationStage {
    /** Android 12–13: baseline of the original 2.1.0 behaviour. */
    BASELINE,

    /** Android 14–15: system-service and permission changes. */
    SERVICE_AND_PERMISSION,

    /** Android 16–17: dedicated compatibility layer. */
    SPECIAL,
}

enum class AndroidRelease(
    val label: String,
    val apiRange: IntRange,
    val stage: VerificationStage,
    /** Directory under `Bcore/compatibility` that owns the version-specific hooks. */
    val compatibilityDirectory: String,
) {
    ANDROID_12("Android 12 / 12L", 31..32, VerificationStage.BASELINE, "Android12"),
    ANDROID_13("Android 13", 33..33, VerificationStage.BASELINE, "Android13"),
    ANDROID_14("Android 14", 34..34, VerificationStage.SERVICE_AND_PERMISSION, "Android14"),
    ANDROID_15("Android 15", 35..35, VerificationStage.SERVICE_AND_PERMISSION, "Android15"),
    ANDROID_16("Android 16", 36..36, VerificationStage.SPECIAL, "Android16"),
    ANDROID_17("Android 17", 37..Int.MAX_VALUE, VerificationStage.SPECIAL, "Android17");

    val isVerifiedPlatform: Boolean
        get() = this == ANDROID_12 || this == ANDROID_13

    companion object {
        fun forApi(api: Int): AndroidRelease =
            entries.firstOrNull { api in it.apiRange } ?: ANDROID_17

        fun current(): AndroidRelease = forApi(Build.VERSION.SDK_INT)
    }
}
