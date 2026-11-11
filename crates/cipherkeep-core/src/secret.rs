//! Secret-memory types.
//!
//! These types make the safe path the easy path:
//!
//! * No `Clone`, so a secret cannot be duplicated by accident.
//! * No `Display` and no transparent `Debug`, so formatting cannot leak bytes.
//! * Constant-time equality for equal-length values.
//! * Explicit zeroization on drop and on [`Zeroize::zeroize`].
//!
//! The buffer length is observable through [`SecretBytes::len`]. Length is not
//! treated as secret; contents always are.

use core::fmt;

use subtle::ConstantTimeEq;
use zeroize::Zeroize;

/// An owned, fixed-length secret byte buffer.
///
/// `SecretBytes` has no spare initialized bytes: every byte in its length is
/// zeroized on drop. Construct it with [`SecretBytes::from_slice`] or
/// `From<&[u8]>`; do not move a secret into a larger `Vec` first.
///
/// ```compile_fail
/// // There is intentionally no `Display` implementation for secrets.
/// let secret = cipherkeep_core::SecretBytes::from_slice(b"hunter2");
/// println!("{}", secret);
/// ```
#[derive(Default)]
pub struct SecretBytes {
    bytes: Vec<u8>,
}

impl SecretBytes {
    /// Copy `secret` into a new zeroizing buffer.
    ///
    /// The vector is created with capacity for `secret.len()` bytes and is
    /// never grown, so it cannot retain stale secret data beyond its length.
    #[must_use]
    pub fn from_slice(secret: &[u8]) -> Self {
        let mut bytes = Vec::with_capacity(secret.len());
        bytes.extend_from_slice(secret);
        Self { bytes }
    }

    /// The number of secret bytes.
    #[must_use]
    pub fn len(&self) -> usize {
        self.bytes.len()
    }

    /// Whether the buffer is empty.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.bytes.is_empty()
    }

    /// Expose the secret bytes for an immediate cryptographic operation.
    ///
    /// The returned slice borrows from `self`; do not copy it into a
    /// non-zeroizing container, format it, or log it.
    #[must_use]
    pub fn expose_secret(&self) -> &[u8] {
        &self.bytes
    }
}

impl fmt::Debug for SecretBytes {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("SecretBytes")
            .field("len", &self.bytes.len())
            .field("contents", &"<redacted>")
            .finish()
    }
}

impl PartialEq for SecretBytes {
    fn eq(&self, other: &Self) -> bool {
        if self.len() != other.len() {
            return false;
        }
        self.expose_secret().ct_eq(other.expose_secret()).into()
    }
}

impl Eq for SecretBytes {}

impl Drop for SecretBytes {
    fn drop(&mut self) {
        self.bytes.zeroize();
    }
}

impl Zeroize for SecretBytes {
    fn zeroize(&mut self) {
        self.bytes.zeroize();
    }
}

impl From<&[u8]> for SecretBytes {
    fn from(value: &[u8]) -> Self {
        Self::from_slice(value)
    }
}

/// An owned UTF-8 secret string.
///
/// Internally this is a [`SecretBytes`] plus a UTF-8 invariant. Length is
/// measured in bytes, matching Rust's `String` semantics.
#[derive(Default)]
pub struct SecretString {
    inner: SecretBytes,
}

impl SecretString {
    /// Copy `value` into a new zeroizing buffer.
    #[must_use]
    pub fn new(value: &str) -> Self {
        Self {
            inner: SecretBytes::from_slice(value.as_bytes()),
        }
    }

    /// The number of bytes in the string.
    #[must_use]
    pub fn len(&self) -> usize {
        self.inner.len()
    }

    /// Whether the string is empty.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.inner.is_empty()
    }

    /// Expose the secret as a string slice for immediate use.
    ///
    /// Do not copy it into a non-zeroizing `String`, format it, or log it.
    #[must_use]
    pub fn expose_secret(&self) -> &str {
        core::str::from_utf8(self.inner.expose_secret())
            .expect("SecretString invariant: contents are valid UTF-8")
    }
}

impl fmt::Debug for SecretString {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("SecretString")
            .field("len", &self.inner.len())
            .field("contents", &"<redacted>")
            .finish()
    }
}

impl PartialEq for SecretString {
    fn eq(&self, other: &Self) -> bool {
        self.inner == other.inner
    }
}

impl Eq for SecretString {}

impl Zeroize for SecretString {
    fn zeroize(&mut self) {
        self.inner.zeroize();
    }
}

impl From<&str> for SecretString {
    fn from(value: &str) -> Self {
        Self::new(value)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn byte_debug_never_contains_contents() {
        let secret = SecretBytes::from_slice(b"correct horse battery staple");
        let rendered = format!("{secret:?}");
        assert!(!rendered.contains("correct"));
        assert!(!rendered.contains("staple"));
        assert!(rendered.contains("<redacted>"));
        assert!(rendered.contains("len"));
    }

    #[test]
    fn string_debug_never_contains_contents() {
        let secret = SecretString::new("hunter2");
        let rendered = format!("{secret:?}");
        assert!(!rendered.contains("hunter2"));
        assert!(rendered.contains("<redacted>"));
    }

    #[test]
    fn equality_compares_contents_and_length() {
        let first = SecretString::new("same-value");
        let second = SecretString::new("same-value");
        let different = SecretString::new("same-valu3");
        let longer = SecretString::new("same-value-longer");

        assert!(first == second);
        assert!(first != different);
        assert!(first != longer);
        assert!(SecretBytes::from_slice(b"abc") == SecretBytes::from_slice(b"abc"));
        assert!(SecretBytes::from_slice(b"abc") != SecretBytes::from_slice(b"abd"));
    }

    #[test]
    fn zeroize_clears_bytes_and_strings() {
        let mut bytes = SecretBytes::from_slice(b"secret");
        bytes.zeroize();
        assert!(bytes.is_empty());

        let mut string = SecretString::new("secret");
        string.zeroize();
        assert!(string.is_empty());
        assert_eq!(string.expose_secret(), "");
    }

    #[test]
    fn lengths_are_byte_lengths() {
        let value = "é";
        let secret = SecretString::new(value);
        assert_eq!(secret.len(), value.len());
        assert_eq!(secret.len(), 2);
        assert!(!secret.is_empty());
    }

    #[test]
    fn conversions_copy_into_the_secret_type() {
        let bytes = SecretBytes::from(&b"abc"[..]);
        assert_eq!(bytes.expose_secret(), b"abc");

        let string = SecretString::from("abc");
        assert_eq!(string.expose_secret(), "abc");
    }

    #[test]
    fn defaults_are_empty() {
        assert!(SecretBytes::default().is_empty());
        assert!(SecretString::default().is_empty());
        assert_eq!(SecretString::default().expose_secret(), "");
    }
}
