package dev.cipherkeep.core

/**
 * Stable numeric codes for [FfiException].
 *
 * The Rust side owns these numbers; this table is kept in lock-step with
 * `FfiError::numeric_code` and `ErrorCode` in `cipherkeep-core`.
 */
object ErrorCodes {
    const val INVALID_ARGUMENT = 1
    const val UNSUPPORTED_VERSION = 2
    const val AUTHENTICATION_FAILED = 3
    const val CORRUPT_DATA = 4
    const val LOCKED = 5
    const val NOT_FOUND = 6
    const val CONFLICT = 7
    const val LIMIT_EXCEEDED = 8
    const val STORAGE = 9
    const val INTERNAL = 10
    const val POLICY_REJECTED = 11

    fun code(error: FfiException): Int = when (error) {
        is FfiException.InvalidArgument -> INVALID_ARGUMENT
        is FfiException.UnsupportedVersion -> UNSUPPORTED_VERSION
        is FfiException.AuthenticationFailed -> AUTHENTICATION_FAILED
        is FfiException.CorruptData -> CORRUPT_DATA
        is FfiException.Locked -> LOCKED
        is FfiException.NotFound -> NOT_FOUND
        is FfiException.Conflict -> CONFLICT
        is FfiException.LimitExceeded -> LIMIT_EXCEEDED
        is FfiException.Storage -> STORAGE
        is FfiException.Internal -> INTERNAL
        is FfiException.PolicyRejected -> POLICY_REJECTED
    }

    fun message(code: Int): String = when (code) {
        INVALID_ARGUMENT -> "invalid input"
        UNSUPPORTED_VERSION -> "unsupported format version"
        AUTHENTICATION_FAILED -> "vault could not be opened"
        CORRUPT_DATA -> "data is malformed"
        LOCKED -> "vault is locked"
        NOT_FOUND -> "item not found"
        CONFLICT -> "concurrent change requires resolution"
        LIMIT_EXCEEDED -> "resource limit exceeded"
        STORAGE -> "storage operation failed"
        INTERNAL -> "internal error"
        POLICY_REJECTED -> "security policy rejected the parameters"
        else -> "internal error"
    }
}

/** Numeric code for a generated UniFFI exception. */
fun FfiException.numericCode(): Int = ErrorCodes.code(this)

/** Redacted message for a generated UniFFI exception. */
fun FfiException.redactedMessage(): String = ErrorCodes.message(numericCode())
