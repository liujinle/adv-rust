package com.adv.core.common

/**
 * The single result type that crosses layer boundaries.
 *
 * Deliberately not [kotlin.Result]: the failure side must carry a mapped
 * [EngineError] so the UI never has to parse native error strings.
 */
sealed interface EngineResult<out T> {

    data class Success<out T>(val value: T) : EngineResult<T>

    data class Failure(val error: EngineError) : EngineResult<Nothing>

    val isSuccess: Boolean get() = this is Success

    fun valueOrNull(): T? = when (this) {
        is Success -> value
        is Failure -> null
    }

    fun errorOrNull(): EngineError? = when (this) {
        is Success -> null
        is Failure -> error
    }
}

inline fun <T, R> EngineResult<T>.map(transform: (T) -> R): EngineResult<R> = when (this) {
    is EngineResult.Success -> EngineResult.Success(transform(value))
    is EngineResult.Failure -> this
}

inline fun <T, R> EngineResult<T>.flatMap(transform: (T) -> EngineResult<R>): EngineResult<R> = when (this) {
    is EngineResult.Success -> transform(value)
    is EngineResult.Failure -> this
}

inline fun <T> EngineResult<T>.onSuccess(block: (T) -> Unit): EngineResult<T> {
    if (this is EngineResult.Success) block(value)
    return this
}

inline fun <T> EngineResult<T>.onFailure(block: (EngineError) -> Unit): EngineResult<T> {
    if (this is EngineResult.Failure) block(error)
    return this
}

inline fun <T> EngineResult<T>.getOrElse(fallback: (EngineError) -> T): T = when (this) {
    is EngineResult.Success -> value
    is EngineResult.Failure -> fallback(error)
}

fun <T> EngineResult<T>.getOrThrow(): T = when (this) {
    is EngineResult.Success -> value
    is EngineResult.Failure -> throw IllegalStateException(error.detail, error.cause)
}

/** Wraps a blocking call so unexpected exceptions become [EngineErrorCode.INTERNAL]. */
inline fun <T> engineRunCatching(block: () -> T): EngineResult<T> = try {
    EngineResult.Success(block())
} catch (error: Throwable) {
    EngineResult.Failure(EngineError(EngineErrorCode.INTERNAL, error.message ?: "unexpected failure", error))
}
