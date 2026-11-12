package com.adv.bb.core

/**
 * Mirror of `bb_common::ErrorCode`. Values are part of the ABI: append only.
 */
object BbErrorCode {
    const val OK = 0
    const val INVALID_ARGUMENT = -1
    const val NOT_FOUND = -2
    const val ALREADY_EXISTS = -3
    const val INVALID_STATE = -4
    const val IO = -5
    const val CORRUPT = -6
    const val UNSUPPORTED = -7
    const val BUSY = -8
    const val TIMED_OUT = -9
    const val PERMISSION_DENIED = -10
    const val TOO_LARGE = -11
    const val NOT_INITIALIZED = -12
    const val PANIC = -13
    const val INTERNAL = -14

    fun name(code: Int): String = when (code) {
        OK -> "OK"
        INVALID_ARGUMENT -> "INVALID_ARGUMENT"
        NOT_FOUND -> "NOT_FOUND"
        ALREADY_EXISTS -> "ALREADY_EXISTS"
        INVALID_STATE -> "INVALID_STATE"
        IO -> "IO"
        CORRUPT -> "CORRUPT"
        UNSUPPORTED -> "UNSUPPORTED"
        BUSY -> "BUSY"
        TIMED_OUT -> "TIMED_OUT"
        PERMISSION_DENIED -> "PERMISSION_DENIED"
        TOO_LARGE -> "TOO_LARGE"
        NOT_INITIALIZED -> "NOT_INITIALIZED"
        PANIC -> "PANIC"
        INTERNAL -> "INTERNAL"
        else -> "UNKNOWN($code)"
    }
}

/** Typed error raised by [BbEngine]. Never carries a native pointer. */
class BbException(
    val code: Int,
    val codeName: String,
    override val message: String,
) : RuntimeException("$codeName ($code): $message") {
    companion object {
        fun of(code: Int, detail: String): BbException = BbException(code, BbErrorCode.name(code), detail)
    }
}
