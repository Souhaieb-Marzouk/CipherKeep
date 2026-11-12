//! RFC 6238 time-based one-time passwords.
//!
//! Seeds stay in [`SecretString`]/[`SecretBytes`] values and are never included
//! in Debug output or errors. Codes are returned as zeroizing strings so callers
//! can copy them only at the point of use.

use std::collections::BTreeMap;

use data_encoding::BASE32_NOPAD;
use hmac::{Hmac, Mac};
use percent_encoding::percent_decode_str;
use sha1::Sha1;
use sha2::{Sha256, Sha512};
use subtle::ConstantTimeEq;
use url::Url;
use zeroize::Zeroize;

use crate::error::{CoreError, ErrorCode, Result};
use crate::secret::{SecretBytes, SecretString};

/// Default TOTP period in seconds.
pub const DEFAULT_PERIOD: u64 = 30;
/// Minimum accepted TOTP period in seconds.
pub const MIN_PERIOD: u64 = 15;
/// Maximum accepted TOTP period in seconds.
pub const MAX_PERIOD: u64 = 300;
/// Minimum accepted seed length in bytes.
pub const MIN_SECRET_LEN: usize = 10;
/// Maximum accepted seed length in bytes.
pub const MAX_SECRET_LEN: usize = 64;

/// Hash algorithm used by a TOTP entry.
#[repr(u8)]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum TotpAlgorithm {
    /// HMAC-SHA-1.
    Sha1 = 1,
    /// HMAC-SHA-256.
    Sha256 = 2,
    /// HMAC-SHA-512.
    Sha512 = 3,
}

impl TotpAlgorithm {
    /// Parse the `algorithm` query parameter.
    #[must_use]
    pub fn from_name(value: &str) -> Option<Self> {
        match value.to_ascii_uppercase().replace('-', "").as_str() {
            "SHA1" => Some(Self::Sha1),
            "SHA256" => Some(Self::Sha256),
            "SHA512" => Some(Self::Sha512),
            _ => None,
        }
    }

    /// Stable display name.
    #[must_use]
    pub const fn name(self) -> &'static str {
        match self {
            Self::Sha1 => "SHA1",
            Self::Sha256 => "SHA256",
            Self::Sha512 => "SHA512",
        }
    }
}

/// A validated TOTP configuration.
pub struct Totp {
    secret: SecretBytes,
    digits: u8,
    period: u64,
    algorithm: TotpAlgorithm,
}

impl Totp {
    /// Build a TOTP configuration.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::PolicyRejected`] for an unsupported digit count,
    /// period or seed length.
    pub fn new(secret: &[u8], digits: u8, period: u64, algorithm: TotpAlgorithm) -> Result<Self> {
        if !(6..=8).contains(&digits)
            || !(MIN_PERIOD..=MAX_PERIOD).contains(&period)
            || !(MIN_SECRET_LEN..=MAX_SECRET_LEN).contains(&secret.len())
        {
            return Err(CoreError::new(ErrorCode::PolicyRejected));
        }
        Ok(Self {
            secret: SecretBytes::from_slice(secret),
            digits,
            period,
            algorithm,
        })
    }

    /// Decode a base32 seed and build a configuration.
    ///
    /// # Errors
    ///
    /// Returns an error for an invalid seed or policy.
    pub fn from_base32(
        secret: &str,
        digits: u8,
        period: u64,
        algorithm: TotpAlgorithm,
    ) -> Result<Self> {
        let normalized = normalize_base32(secret);
        let mut decoded = BASE32_NOPAD
            .decode(normalized.as_bytes())
            .map_err(|_| CoreError::new(ErrorCode::InvalidArgument))?;
        let result = Self::new(&decoded, digits, period, algorithm);
        decoded.zeroize();
        result
    }

    /// Number of code digits.
    #[must_use]
    pub const fn digits(&self) -> u8 {
        self.digits
    }

    /// Period in seconds.
    #[must_use]
    pub const fn period(&self) -> u64 {
        self.period
    }

    /// Hash algorithm.
    #[must_use]
    pub const fn algorithm(&self) -> TotpAlgorithm {
        self.algorithm
    }

    /// Generate the code for a Unix timestamp.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::Internal`] only if the HMAC key is rejected.
    pub fn code_at(&self, unix_secs: u64) -> Result<SecretString> {
        let counter = unix_secs / self.period;
        let mut digest = hmac_digest(self.algorithm, self.secret.expose_secret(), counter)?;
        let offset = usize::from(digest[digest.len() - 1] & 0x0f);
        if offset + 4 > digest.len() {
            return Err(CoreError::new(ErrorCode::Internal));
        }
        let binary = (u32::from(digest[offset]) & 0x7f) << 24
            | u32::from(digest[offset + 1]) << 16
            | u32::from(digest[offset + 2]) << 8
            | u32::from(digest[offset + 3]);
        let modulus = 10u32.pow(u32::from(self.digits));
        let code = binary % modulus;
        let mut formatted = format!("{code:0width$}", width = usize::from(self.digits));
        digest.zeroize();
        let secret = SecretString::new(&formatted);
        formatted.zeroize();
        Ok(secret)
    }

    /// Seconds until the current step rolls over.
    #[must_use]
    pub fn seconds_remaining(&self, unix_secs: u64) -> u64 {
        let remainder = unix_secs % self.period;
        if remainder == 0 {
            self.period
        } else {
            self.period - remainder
        }
    }

    /// Verify a code with a bounded ±step tolerance.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::InvalidArgument`] for a malformed code.
    pub fn verify(&self, code: &str, unix_secs: u64, tolerance_steps: u8) -> Result<bool> {
        if code.len() != usize::from(self.digits)
            || !code.chars().all(|character| character.is_ascii_digit())
        {
            return Err(CoreError::new(ErrorCode::InvalidArgument));
        }
        let counter = unix_secs / self.period;
        let tolerance = i64::from(tolerance_steps);
        for delta in -tolerance..=tolerance {
            let Some(candidate_step) = counter.checked_add_signed(delta) else {
                continue;
            };
            let mut digest =
                hmac_digest(self.algorithm, self.secret.expose_secret(), candidate_step)?;
            let offset = usize::from(digest[digest.len() - 1] & 0x0f);
            if offset + 4 > digest.len() {
                continue;
            }
            let binary = (u32::from(digest[offset]) & 0x7f) << 24
                | u32::from(digest[offset + 1]) << 16
                | u32::from(digest[offset + 2]) << 8
                | u32::from(digest[offset + 3]);
            let modulus = 10u32.pow(u32::from(self.digits));
            let expected = format!(
                "{:0width$}",
                binary % modulus,
                width = usize::from(self.digits)
            );
            if expected.as_bytes().ct_eq(code.as_bytes()).into() {
                digest.zeroize();
                return Ok(true);
            }
            digest.zeroize();
        }
        Ok(false)
    }
}

impl core::fmt::Debug for Totp {
    fn fmt(&self, formatter: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        formatter
            .debug_struct("Totp")
            .field("digits", &self.digits)
            .field("period", &self.period)
            .field("algorithm", &self.algorithm)
            .finish_non_exhaustive()
    }
}

/// A parsed `otpauth://totp` entry.
pub struct TotpEntry {
    totp: Totp,
    issuer: Option<String>,
    account: String,
}

impl TotpEntry {
    /// The TOTP configuration.
    #[must_use]
    pub const fn totp(&self) -> &Totp {
        &self.totp
    }

    /// Issuer, if supplied.
    #[must_use]
    pub fn issuer(&self) -> Option<&str> {
        self.issuer.as_deref()
    }

    /// Account label.
    #[must_use]
    pub fn account(&self) -> &str {
        &self.account
    }
}

impl core::fmt::Debug for TotpEntry {
    fn fmt(&self, formatter: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        formatter
            .debug_struct("TotpEntry")
            .field("issuer", &self.issuer)
            .field("account", &self.account)
            .field("totp", &self.totp)
            .finish()
    }
}

/// Parse and validate an `otpauth://totp` URI.
///
/// # Errors
///
/// Returns [`ErrorCode::InvalidArgument`] for malformed URIs, missing or
/// invalid seeds, unsupported algorithms, or unsafe metadata.
pub fn parse_otpauth_uri(uri: &str) -> Result<TotpEntry> {
    let parsed = Url::parse(uri).map_err(|_| CoreError::new(ErrorCode::InvalidArgument))?;
    if parsed.scheme() != "otpauth" || parsed.host_str() != Some("totp") {
        return Err(CoreError::new(ErrorCode::InvalidArgument));
    }
    let label = percent_decode_str(parsed.path().trim_start_matches('/'))
        .decode_utf8()
        .map_err(|_| CoreError::new(ErrorCode::InvalidArgument))?;
    if label.is_empty() || label.chars().any(char::is_control) {
        return Err(CoreError::new(ErrorCode::InvalidArgument));
    }
    let (label_issuer, label_account) = match label.split_once(':') {
        Some((issuer, account)) => (Some(issuer.trim().to_string()), account.trim().to_string()),
        None => (None, label.trim().to_string()),
    };
    if label_account.is_empty() || label_account.len() > 256 {
        return Err(CoreError::new(ErrorCode::InvalidArgument));
    }
    let mut params = BTreeMap::new();
    for (key, value) in parsed.query_pairs() {
        if value.chars().any(char::is_control) {
            return Err(CoreError::new(ErrorCode::InvalidArgument));
        }
        params.insert(key.to_ascii_lowercase(), value.to_string());
    }
    let secret = params
        .get("secret")
        .ok_or_else(|| CoreError::new(ErrorCode::InvalidArgument))?;
    let digits = params
        .get("digits")
        .map_or(Ok(6), |value| value.parse::<u8>())
        .map_err(|_| CoreError::new(ErrorCode::InvalidArgument))?;
    let period = params
        .get("period")
        .map_or(Ok(DEFAULT_PERIOD), |value| value.parse::<u64>())
        .map_err(|_| CoreError::new(ErrorCode::InvalidArgument))?;
    let algorithm = match params.get("algorithm") {
        Some(value) => TotpAlgorithm::from_name(value)
            .ok_or_else(|| CoreError::new(ErrorCode::InvalidArgument))?,
        None => TotpAlgorithm::Sha1,
    };
    let issuer = params
        .get("issuer")
        .map_or(label_issuer, |value| Some(value.trim().to_string()));
    if issuer.as_ref().is_some_and(|value| value.len() > 256) {
        return Err(CoreError::new(ErrorCode::InvalidArgument));
    }
    let totp = Totp::from_base32(secret, digits, period, algorithm)?;
    Ok(TotpEntry {
        totp,
        issuer,
        account: label_account,
    })
}

fn normalize_base32(secret: &str) -> String {
    secret
        .chars()
        .filter(|character| !character.is_whitespace() && *character != '-')
        .filter(|character| *character != '=')
        .flat_map(char::to_uppercase)
        .collect()
}

fn hmac_digest(algorithm: TotpAlgorithm, key: &[u8], counter: u64) -> Result<Vec<u8>> {
    let message = counter.to_be_bytes();
    macro_rules! digest {
        ($hash:ty) => {{
            let mut mac = Hmac::<$hash>::new_from_slice(key)
                .map_err(|_| CoreError::new(ErrorCode::Internal))?;
            mac.update(&message);
            mac.finalize().into_bytes().to_vec()
        }};
    }
    Ok(match algorithm {
        TotpAlgorithm::Sha1 => digest!(Sha1),
        TotpAlgorithm::Sha256 => digest!(Sha256),
        TotpAlgorithm::Sha512 => digest!(Sha512),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use zeroize::Zeroize;

    fn secret(len: usize) -> Vec<u8> {
        b"1234567890123456789012345678901234567890123456789012345678901234"[..len].to_vec()
    }

    #[test]
    fn rfc6238_vectors_for_all_hashes() {
        let vectors = [
            (TotpAlgorithm::Sha1, 20, "94287082"),
            (TotpAlgorithm::Sha256, 32, "46119246"),
            (TotpAlgorithm::Sha512, 64, "90693936"),
        ];
        for (algorithm, len, expected) in vectors {
            let totp = Totp::new(&secret(len), 8, 30, algorithm).expect("totp");
            assert_eq!(totp.code_at(59).unwrap().expose_secret(), expected);
        }
    }

    #[test]
    fn tolerance_is_bounded_to_one_step() {
        let totp = Totp::new(&secret(20), 8, 30, TotpAlgorithm::Sha1).expect("totp");
        let code = totp.code_at(59).unwrap();
        assert!(totp.verify(code.expose_secret(), 89, 1).unwrap());
        assert!(!totp.verify(code.expose_secret(), 90, 1).unwrap());
        assert!(!totp.verify(code.expose_secret(), 120, 1).unwrap());
    }

    #[test]
    fn uri_parser_accepts_a_valid_entry() {
        let encoded = BASE32_NOPAD.encode(&secret(20));
        let uri = format!(
            "otpauth://totp/Example:alice?secret={encoded}&issuer=Example&algorithm=SHA1&digits=6&period=30"
        );
        let entry = parse_otpauth_uri(&uri).expect("parse");
        assert_eq!(entry.issuer(), Some("Example"));
        assert_eq!(entry.account(), "alice");
        assert_eq!(entry.totp().digits(), 6);
        assert_eq!(entry.totp().period(), 30);
        assert_eq!(entry.totp().algorithm(), TotpAlgorithm::Sha1);
    }

    #[test]
    fn uri_parser_rejects_malformed_entries() {
        for uri in [
            "https://totp/Example:alice?secret=AAAA",
            "otpauth://hotp/Example:alice?secret=AAAA",
            "otpauth://totp/alice?digits=6",
            "otpauth://totp/alice?secret=not-base32!",
            "otpauth://totp/alice?secret=JBSWY3DPEHPK3PXP&digits=9",
            "otpauth://totp/alice?secret=JBSWY3DPEHPK3PXP&period=2",
            "otpauth://totp/alice?secret=JBSWY3DPEHPK3PXP&algorithm=MD5",
        ] {
            assert!(parse_otpauth_uri(uri).is_err(), "{uri}");
        }
    }

    #[test]
    fn code_length_and_countdown_are_deterministic() {
        let totp = Totp::new(&secret(20), 6, 30, TotpAlgorithm::Sha1).expect("totp");
        let code = totp.code_at(0).unwrap();
        assert_eq!(code.expose_secret().len(), 6);
        assert_eq!(totp.seconds_remaining(0), 30);
        assert_eq!(totp.seconds_remaining(29), 1);
        assert_eq!(totp.seconds_remaining(30), 30);
    }

    #[test]
    fn seeds_and_digests_are_zeroized_on_drop() {
        let mut bytes = secret(20);
        let totp = Totp::new(&bytes, 6, 30, TotpAlgorithm::Sha1).expect("totp");
        bytes.zeroize();
        assert!(totp.verify("000000", 59, 0).is_ok());
    }
}
