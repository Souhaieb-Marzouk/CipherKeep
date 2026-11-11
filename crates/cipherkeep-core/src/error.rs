//! Stable, redacted error contract shared by every platform.
//!
//! Error values deliberately contain no payload. A caller receives a stable
//! numeric code and a static message; it cannot accidentally log a password,
//! key, ciphertext or field value through an error.
//!
//! Authentication failures are intentionally indistinguishable from a corrupt
//! keyring at this boundary. Both must be reported as
//! [`ErrorCode::AuthenticationFailed`] with the same message, so an attacker
//! cannot use error output as a decryption oracle.

use core::fmt;

/// Stable numeric error codes.
///
/// The discriminants are part of the FFI contract and must never be reused or
/// renumbered. New codes are appended.
#[repr(u16)]
#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
#[non_exhaustive]
pub enum ErrorCode {
    /// Caller input was malformed or outside an allowed range.
    InvalidArgument = 1,
    /// A format or schema version is newer than this build supports.
    UnsupportedVersion = 2,
    /// The vault could not be opened. This is deliberately generic.
    AuthenticationFailed = 3,
    /// Data is malformed or fails an integrity check unrelated to unlock.
    CorruptData = 4,
    /// The operation requires an unlocked vault.
    Locked = 5,
    /// The requested item, vault or record does not exist.
    NotFound = 6,
    /// A concurrent change requires explicit user resolution.
    Conflict = 7,
    /// A resource limit (size, count, time) was exceeded.
    LimitExceeded = 8,
    /// The platform storage layer failed. No storage detail is exposed.
    Storage = 9,
    /// An internal invariant failed. No diagnostic detail is exposed.
    Internal = 10,
    /// The parameters are valid encodings but fail the security policy.
    PolicyRejected = 11,
}

impl ErrorCode {
    /// Every code defined by this version, in numeric order.
    pub const ALL: [Self; 11] = [
        Self::InvalidArgument,
        Self::UnsupportedVersion,
        Self::AuthenticationFailed,
        Self::CorruptData,
        Self::Locked,
        Self::NotFound,
        Self::Conflict,
        Self::LimitExceeded,
        Self::Storage,
        Self::Internal,
        Self::PolicyRejected,
    ];

    /// The stable numeric code used by the FFI and storage layers.
    #[must_use]
    pub const fn numeric_code(self) -> u16 {
        self as u16
    }

    /// A stable, machine-readable snake_case name.
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::InvalidArgument => "invalid_argument",
            Self::UnsupportedVersion => "unsupported_version",
            Self::AuthenticationFailed => "authentication_failed",
            Self::CorruptData => "corrupt_data",
            Self::Locked => "locked",
            Self::NotFound => "not_found",
            Self::Conflict => "conflict",
            Self::LimitExceeded => "limit_exceeded",
            Self::Storage => "storage",
            Self::Internal => "internal",
            Self::PolicyRejected => "policy_rejected",
        }
    }

    /// A static, user-safe message with no caller data.
    #[must_use]
    pub const fn message(self) -> &'static str {
        match self {
            Self::InvalidArgument => "invalid input",
            Self::UnsupportedVersion => "unsupported format version",
            Self::AuthenticationFailed => "vault could not be opened",
            Self::CorruptData => "data is malformed",
            Self::Locked => "vault is locked",
            Self::NotFound => "item not found",
            Self::Conflict => "concurrent change requires resolution",
            Self::LimitExceeded => "resource limit exceeded",
            Self::Storage => "storage operation failed",
            Self::Internal => "internal error",
            Self::PolicyRejected => "security policy rejected the parameters",
        }
    }
}

/// A redacted core error.
///
/// The value is `Copy`, contains a single [`ErrorCode`], and has no source
/// chain. Platform layers may log [`CoreError::numeric_code`] and
/// [`CoreError::as_str`], but must never attempt to reconstruct secret data.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct CoreError {
    code: ErrorCode,
}

impl CoreError {
    /// Construct an error from a stable code.
    #[must_use]
    pub const fn new(code: ErrorCode) -> Self {
        Self { code }
    }

    /// The generic authentication failure used for both a wrong master
    /// password and a corrupt keyring.
    ///
    /// Do not add a more specific variant for either case: the distinction is
    /// an oracle.
    #[must_use]
    pub const fn authentication_failed() -> Self {
        Self::new(ErrorCode::AuthenticationFailed)
    }

    /// Return the stable code.
    #[must_use]
    pub const fn code(self) -> ErrorCode {
        self.code
    }

    /// Return the stable numeric code.
    #[must_use]
    pub const fn numeric_code(self) -> u16 {
        self.code.numeric_code()
    }

    /// Return the stable machine-readable name.
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        self.code.as_str()
    }

    /// Return the static, redacted message.
    #[must_use]
    pub const fn message(self) -> &'static str {
        self.code.message()
    }

    /// Whether this is the generic authentication failure.
    #[must_use]
    pub const fn is_authentication_failure(self) -> bool {
        matches!(self.code, ErrorCode::AuthenticationFailed)
    }
}

impl fmt::Display for CoreError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(self.message())
    }
}

impl std::error::Error for CoreError {}

impl From<ErrorCode> for CoreError {
    fn from(code: ErrorCode) -> Self {
        Self::new(code)
    }
}

/// A `Result` whose error is a redacted [`CoreError`].
pub type Result<T> = core::result::Result<T, CoreError>;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn numeric_codes_are_stable() {
        assert_eq!(ErrorCode::InvalidArgument.numeric_code(), 1);
        assert_eq!(ErrorCode::UnsupportedVersion.numeric_code(), 2);
        assert_eq!(ErrorCode::AuthenticationFailed.numeric_code(), 3);
        assert_eq!(ErrorCode::CorruptData.numeric_code(), 4);
        assert_eq!(ErrorCode::Locked.numeric_code(), 5);
        assert_eq!(ErrorCode::NotFound.numeric_code(), 6);
        assert_eq!(ErrorCode::Conflict.numeric_code(), 7);
        assert_eq!(ErrorCode::LimitExceeded.numeric_code(), 8);
        assert_eq!(ErrorCode::Storage.numeric_code(), 9);
        assert_eq!(ErrorCode::Internal.numeric_code(), 10);
        assert_eq!(ErrorCode::PolicyRejected.numeric_code(), 11);
    }

    #[test]
    fn every_code_has_a_safe_name_and_message() {
        for code in ErrorCode::ALL {
            assert!(!code.as_str().is_empty());
            assert!(!code.message().is_empty());
            assert!(code.message().is_ascii());
        }
    }

    #[test]
    fn authentication_failure_is_generic() {
        let error = CoreError::authentication_failed();
        assert_eq!(error.code(), ErrorCode::AuthenticationFailed);
        assert!(error.is_authentication_failure());
        assert_eq!(error.to_string(), "vault could not be opened");
        assert!(!error.to_string().contains("password"));
        assert!(!error.to_string().contains("corrupt"));
    }

    #[test]
    fn error_round_trips_its_code() {
        let error = CoreError::from(ErrorCode::Conflict);
        assert_eq!(error.code(), ErrorCode::Conflict);
        assert_eq!(error.numeric_code(), 7);
        assert_eq!(error.as_str(), "conflict");
        assert_eq!(error.message(), "concurrent change requires resolution");
        assert!(!error.is_authentication_failure());
    }

    #[test]
    fn core_error_is_copy_and_has_no_payload() {
        fn assert_copy<T: Copy>() {}
        assert_copy::<CoreError>();
        let first = CoreError::new(ErrorCode::Locked);
        let second = first;
        assert_eq!(first, second);
    }
}
