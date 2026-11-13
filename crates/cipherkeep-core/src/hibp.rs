//! Opt-in HIBP Pwned Passwords range check.
//!
//! Only the first five SHA-1 hex characters leave the device. The suffix is
//! compared locally and the response is treated as untrusted input.

use sha1::{Digest, Sha1};
use subtle::ConstantTimeEq;

use crate::error::{CoreError, ErrorCode, Result};

/// Length of the k-anonymity prefix sent to the provider.
pub const PREFIX_LEN: usize = 5;
/// Length of the SHA-1 hex suffix kept locally.
pub const SUFFIX_LEN: usize = 35;

/// Transport abstraction. Implementations must use HTTPS and reject redirects.
pub trait RangeFetcher {
    /// Fetch `https://api.pwnedpasswords.com/range/<prefix>`.
    ///
    /// # Errors
    ///
    /// Returns a provider or transport error.
    fn fetch(&self, prefix: &str) -> Result<String>;
}

/// SHA-1 hex prefix and suffix for a password.
#[must_use]
pub fn hash_password(password: &str) -> (String, String) {
    let digest = Sha1::digest(password.as_bytes());
    let hex = digest
        .iter()
        .map(|byte| format!("{byte:02X}"))
        .collect::<String>();
    (hex[..PREFIX_LEN].to_string(), hex[PREFIX_LEN..].to_string())
}

/// Parse a range response and return the count for `suffix`.
///
/// # Errors
///
/// Rejects a response larger than [`MAX_RESPONSE`] bytes.
pub fn count_suffix(body: &str, suffix: &str) -> Result<u64> {
    if body.len() > MAX_RESPONSE || suffix.len() != SUFFIX_LEN {
        return Err(CoreError::new(ErrorCode::InvalidArgument));
    }
    let wanted = suffix.as_bytes();
    let mut count = 0u64;
    for line in body.lines() {
        let Some((candidate, value)) = line.split_once(':') else {
            continue;
        };
        let candidate = candidate.trim();
        if candidate.len() != SUFFIX_LEN {
            continue;
        }
        let equal = candidate.as_bytes().ct_eq(wanted).unwrap_u8() == 1;
        if equal {
            count = value.trim().parse::<u64>().unwrap_or(0);
            break;
        }
    }
    Ok(count)
}

/// Check a password through a range transport.
///
/// # Errors
///
/// Returns the transport or malformed-response error.
pub fn check_password(password: &str, fetcher: &dyn RangeFetcher) -> Result<u64> {
    let (prefix, suffix) = hash_password(password);
    if prefix.len() != PREFIX_LEN {
        return Err(CoreError::new(ErrorCode::Internal));
    }
    let body = fetcher.fetch(&prefix)?;
    count_suffix(&body, &suffix)
}

/// Upper bound on a range response body.
pub const MAX_RESPONSE: usize = 4 * 1024 * 1024;

#[cfg(test)]
mod tests {
    use super::{check_password, count_suffix, hash_password, RangeFetcher, PREFIX_LEN};
    use std::cell::RefCell;

    struct Capture {
        prefix: RefCell<String>,
        body: String,
    }

    impl RangeFetcher for Capture {
        fn fetch(&self, prefix: &str) -> crate::error::Result<String> {
            *self.prefix.borrow_mut() = prefix.to_string();
            Ok(self.body.clone())
        }
    }

    #[test]
    fn hashes_to_five_char_prefix() {
        let (prefix, suffix) = hash_password("password");
        assert_eq!(prefix, "5BAA6");
        assert_eq!(prefix.len(), PREFIX_LEN);
        assert_eq!(suffix.len(), 35);
    }

    #[test]
    fn detects_known_suffix_without_sending_it() {
        let (prefix, suffix) = hash_password("password");
        let capture = Capture {
            prefix: RefCell::new(String::new()),
            body: format!("{suffix}:12345\r\nFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF:1\r\n"),
        };
        let count = check_password("password", &capture).expect("check");
        assert_eq!(count, 12_345);
        assert_eq!(*capture.prefix.borrow(), prefix);
        assert_eq!(capture.prefix.borrow().len(), 5);
    }

    #[test]
    fn unknown_suffix_returns_zero() {
        let body = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA:5\n";
        assert_eq!(
            count_suffix(body, "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB").expect("parse"),
            0
        );
    }

    #[test]
    fn malformed_lines_are_ignored() {
        let body = "not-a-line\nZZZ:1\nBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB:7\n";
        assert_eq!(
            count_suffix(body, "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB").expect("parse"),
            7
        );
    }

    #[test]
    fn oversized_or_short_inputs_are_rejected() {
        assert!(count_suffix("x", "short").is_err());
    }
}
