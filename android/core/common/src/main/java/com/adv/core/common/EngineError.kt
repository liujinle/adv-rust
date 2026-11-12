package com.adv.core.common

/**
 * Client-side mirror of the stable native error codes.
 *
 * The enum is resolved from the native `code_name` (never from raw strings shown
 * to the user): `INVALID_STATE` today stays `INVALID_STATE` even if the Rust
 * message wording changes.
 */
enum class EngineErrorCode(val userMessage: String) {
    INVALID_ARGUMENT("参数不合法"),
    NOT_FOUND("目标不存在"),
    ALREADY_EXISTS("目标已存在"),
    INVALID_STATE("当前状态不允许该操作"),
    IO("读写失败"),
    CORRUPT("数据已损坏"),
    UNSUPPORTED("当前环境不支持"),
    BUSY("引擎正忙，请稍后再试"),
    TIMED_OUT("操作超时"),
    PERMISSION_DENIED("权限不足"),
    TOO_LARGE("数据超出限制"),
    NOT_INITIALIZED("引擎尚未初始化"),
    PANIC("引擎内部异常"),
    INTERNAL("内部错误"),
    NATIVE_UNAVAILABLE("Native 引擎未加载"),
    UNKNOWN("未知错误"),
    ;

    companion object {
        fun fromNativeName(name: String?): EngineErrorCode {
            if (name.isNullOrBlank()) return UNKNOWN
            val normalized = name.substringBefore('(').trim()
            return entries.firstOrNull { it.name == normalized } ?: UNKNOWN
        }
    }
}

/**
 * @property detail raw diagnostic text (native message or stack summary). Shown
 *   in the diagnostics screen only — never as the primary user-facing message.
 */
data class EngineError(
    val code: EngineErrorCode,
    val detail: String,
    val cause: Throwable? = null,
) {
    val userMessage: String get() = code.userMessage

    fun toLogLine(): String = "[${code.name}] $detail"

    companion object {
        fun of(code: EngineErrorCode, detail: String, cause: Throwable? = null) =
            EngineError(code, detail, cause)

        fun notInitialized() = EngineError(EngineErrorCode.NOT_INITIALIZED, "engine handle is not open")
    }
}
