//! Shamir 2-of-3 recovery shares over GF(256).
//!
//! A 32-byte recovery key is split into three shares. Any two reconstruct the
//! secret; any single share is information-theoretically independent of it.
//! Shares are encoded as a human-transcribable, checksummed base32 string.
//!
//! The field is GF(2^8) with the AES polynomial `x^8 + x^4 + x^3 + x + 1`
//! (`0x11B`). Multiplication and inversion are implemented with fixed-iteration
//! bit arithmetic rather than lookup tables, avoiding data-dependent table
//! accesses.
//!
//! # Scope
//!
//! This module splits and combines the recovery key. It does not decide where
//! shares are stored, when recovery is allowed, or how the recovered keyring is
//! re-protected; those are session and UI concerns.

use data_encoding::BASE32_NOPAD;
use getrandom::getrandom;
use sha2::{Digest, Sha256};
use subtle::ConstantTimeEq;
use zeroize::{Zeroize, Zeroizing};

use crate::error::{CoreError, ErrorCode, Result};
use crate::secret::{SecretBytes, SecretString};

/// Length of the recovery secret in bytes.
pub const RECOVERY_SECRET_LEN: usize = 32;
/// Number of shares produced by the 2-of-3 split.
pub const SHARE_COUNT: u8 = 3;
/// Reconstruction threshold.
pub const THRESHOLD: u8 = 2;
/// Human-readable share prefix.
pub const SHARE_PREFIX: &str = "CKR1-";

const SHARE_VERSION: u8 = 1;
const CHECKSUM_LEN: usize = 4;
const RAW_SHARE_LEN: usize = 1 + 1 + 1 + RECOVERY_SECRET_LEN + CHECKSUM_LEN;
const GROUP_SIZE: usize = 5;

/// A single recovery share.
///
/// The share value is secret and is zeroized on drop. `Debug` never prints it.
pub struct RecoveryShare {
    index: u8,
    value: SecretBytes,
}

impl RecoveryShare {
    /// The share index, in `1..=3`.
    #[must_use]
    pub const fn index(&self) -> u8 {
        self.index
    }

    /// Encode the share as a grouped, checksummed base32 string.
    ///
    /// The returned [`SecretString`] zeroizes on drop. Print or write it down;
    /// do not log it.
    #[must_use]
    pub fn encode(&self) -> SecretString {
        let mut payload = Zeroizing::new(Vec::with_capacity(RAW_SHARE_LEN));
        payload.push(SHARE_VERSION);
        payload.push(self.index);
        payload.push(THRESHOLD);
        payload.extend_from_slice(self.value.expose_secret());
        let checksum = Sha256::digest(&payload[..]);
        payload.extend_from_slice(&checksum[..CHECKSUM_LEN]);

        let encoded = Zeroizing::new(BASE32_NOPAD.encode(&payload));
        let mut grouped = Zeroizing::new(String::with_capacity(encoded.len() + encoded.len() / 5));
        for (position, chunk) in encoded.as_bytes().chunks(GROUP_SIZE).enumerate() {
            if position > 0 {
                grouped.push('-');
            }
            grouped.push_str(std::str::from_utf8(chunk).expect("base32 output is ASCII"));
        }
        let mut text = Zeroizing::new(String::with_capacity(SHARE_PREFIX.len() + grouped.len()));
        text.push_str(SHARE_PREFIX);
        text.push_str(&grouped);
        let share = SecretString::new(&text);
        text.zeroize();
        share
    }

    /// Decode a share from a grouped or ungrouped base32 string.
    ///
    /// Case, whitespace and hyphen placement are normalized. The prefix and
    /// checksum are mandatory.
    ///
    /// # Errors
    ///
    /// Returns a redacted [`CoreError`] for a bad prefix, base32, length,
    /// version, threshold, index or checksum.
    pub fn decode(encoded: &str) -> Result<Self> {
        let normalized = Zeroizing::new(encoded.trim().to_ascii_uppercase());
        if !normalized.starts_with(SHARE_PREFIX) {
            return Err(CoreError::new(ErrorCode::InvalidArgument));
        }
        let body = &normalized[SHARE_PREFIX.len()..];
        let mut compact = Zeroizing::new(String::with_capacity(body.len()));
        for character in body.chars() {
            if !character.is_whitespace() && character != '-' {
                compact.push(character);
            }
        }
        let decoded = Zeroizing::new(
            BASE32_NOPAD
                .decode(compact.as_bytes())
                .map_err(|_| CoreError::new(ErrorCode::InvalidArgument))?,
        );
        if decoded.len() != RAW_SHARE_LEN {
            return Err(CoreError::new(ErrorCode::InvalidArgument));
        }
        if decoded[0] != SHARE_VERSION {
            return Err(CoreError::new(ErrorCode::UnsupportedVersion));
        }
        if decoded[2] != THRESHOLD {
            return Err(CoreError::new(ErrorCode::InvalidArgument));
        }
        let index = decoded[1];
        if !(1..=SHARE_COUNT).contains(&index) {
            return Err(CoreError::new(ErrorCode::InvalidArgument));
        }

        let expected = Sha256::digest(&decoded[..RAW_SHARE_LEN - CHECKSUM_LEN]);
        if !bool::from(decoded[RAW_SHARE_LEN - CHECKSUM_LEN..].ct_eq(&expected[..CHECKSUM_LEN])) {
            return Err(CoreError::new(ErrorCode::CorruptData));
        }

        let mut value = Zeroizing::new([0u8; RECOVERY_SECRET_LEN]);
        value.copy_from_slice(&decoded[3..3 + RECOVERY_SECRET_LEN]);
        Self::new(index, SecretBytes::from_slice(&value[..]))
    }

    fn new(index: u8, value: SecretBytes) -> Result<Self> {
        if !(1..=SHARE_COUNT).contains(&index) || value.len() != RECOVERY_SECRET_LEN {
            return Err(CoreError::new(ErrorCode::InvalidArgument));
        }
        Ok(Self { index, value })
    }
}

impl core::fmt::Debug for RecoveryShare {
    fn fmt(&self, formatter: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        formatter
            .debug_struct("RecoveryShare")
            .field("index", &self.index)
            .field("len", &self.value.len())
            .field("contents", &"<redacted>")
            .finish()
    }
}

/// Split a 32-byte recovery key into three 2-of-3 shares.
///
/// Each byte uses a degree-1 polynomial with a uniformly random non-zero
/// coefficient. A zero coefficient would make every share equal the secret for
/// that byte, so it is rejected by the generator.
///
/// # Errors
///
/// Returns [`ErrorCode::InvalidArgument`] unless the secret is exactly
/// [`RECOVERY_SECRET_LEN`] bytes, or [`ErrorCode::Internal`] if the OS CSPRNG
/// fails.
pub fn split_2_of_3(secret: &SecretBytes) -> Result<[RecoveryShare; 3]> {
    if secret.len() != RECOVERY_SECRET_LEN {
        return Err(CoreError::new(ErrorCode::InvalidArgument));
    }
    let mut coefficients = Zeroizing::new([0u8; RECOVERY_SECRET_LEN]);
    for coefficient in coefficients.iter_mut() {
        loop {
            let mut byte = [0u8; 1];
            getrandom(&mut byte).map_err(|_| CoreError::new(ErrorCode::Internal))?;
            if byte[0] != 0 {
                *coefficient = byte[0];
                break;
            }
        }
    }

    let mut shares = Vec::with_capacity(3);
    for index in 1..=SHARE_COUNT {
        let mut value = Zeroizing::new([0u8; RECOVERY_SECRET_LEN]);
        for position in 0..RECOVERY_SECRET_LEN {
            value[position] =
                secret.expose_secret()[position] ^ gf_mul(coefficients[position], index);
        }
        shares.push(RecoveryShare::new(
            index,
            SecretBytes::from_slice(&value[..]),
        )?);
    }

    let mut shares = shares.into_iter();
    Ok([
        shares.next().expect("three shares"),
        shares.next().expect("three shares"),
        shares.next().expect("three shares"),
    ])
}

/// Reconstruct the recovery key from any two shares.
///
/// # Errors
///
/// Returns [`ErrorCode::InvalidArgument`] for duplicate indexes.
pub fn combine_2_of_3(first: &RecoveryShare, second: &RecoveryShare) -> Result<SecretBytes> {
    let denominator = first.index ^ second.index;
    if denominator == 0 {
        return Err(CoreError::new(ErrorCode::InvalidArgument));
    }
    let inverse = gf_inv(denominator);
    let first_weight = gf_mul(second.index, inverse);
    let second_weight = gf_mul(first.index, inverse);

    let mut secret = Zeroizing::new([0u8; RECOVERY_SECRET_LEN]);
    for position in 0..RECOVERY_SECRET_LEN {
        secret[position] = gf_mul(first.value.expose_secret()[position], first_weight)
            ^ gf_mul(second.value.expose_secret()[position], second_weight);
    }
    Ok(SecretBytes::from_slice(&secret[..]))
}

fn gf_mul(mut left: u8, mut right: u8) -> u8 {
    let mut product = 0u8;
    for _ in 0..8 {
        let mask = 0u8.wrapping_sub(right & 1);
        product ^= left & mask;
        let carry = left >> 7;
        left = (left << 1) ^ (0x1b & 0u8.wrapping_sub(carry));
        right >>= 1;
    }
    product
}

fn gf_pow(mut base: u8, mut exponent: u8) -> u8 {
    let mut result = 1u8;
    while exponent != 0 {
        if exponent & 1 == 1 {
            result = gf_mul(result, base);
        }
        base = gf_mul(base, base);
        exponent >>= 1;
    }
    result
}

fn gf_inv(value: u8) -> u8 {
    debug_assert!(value != 0);
    gf_pow(value, 254)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn secret(byte: u8) -> SecretBytes {
        SecretBytes::from_slice(&[byte; RECOVERY_SECRET_LEN])
    }

    fn raw_share(version: u8, index: u8, threshold: u8, value: &[u8; 32]) -> String {
        let mut payload = Vec::with_capacity(RAW_SHARE_LEN);
        payload.push(version);
        payload.push(index);
        payload.push(threshold);
        payload.extend_from_slice(value);
        let checksum = Sha256::digest(&payload[..]);
        payload.extend_from_slice(&checksum[..CHECKSUM_LEN]);
        format!("{SHARE_PREFIX}{}", BASE32_NOPAD.encode(&payload))
    }

    #[test]
    fn gf_known_vector_and_inverse() {
        assert_eq!(gf_mul(0x57, 0x83), 0xc1);
        assert_eq!(gf_mul(0, 0x83), 0);
        assert_eq!(gf_mul(0x83, 1), 0x83);
        for value in 1u8..=255 {
            assert_eq!(gf_mul(value, gf_inv(value)), 1);
        }
    }

    #[test]
    fn split_creates_three_distinct_shares() {
        let secret = secret(0x42);
        let shares = split_2_of_3(&secret).expect("split");
        assert_eq!(shares.len(), 3);
        for (index, share) in shares.iter().enumerate() {
            assert_eq!(share.index(), index as u8 + 1);
            assert!(share.value != secret);
        }
        assert!(shares[0].value != shares[1].value);
        assert!(shares[1].value != shares[2].value);
    }

    #[test]
    fn all_pairs_reconstruct_the_secret() {
        let secret = secret(0x42);
        let shares = split_2_of_3(&secret).expect("split");
        for (first, second) in [(0, 1), (0, 2), (1, 2)] {
            let recovered = combine_2_of_3(&shares[first], &shares[second]).expect("combine");
            assert!(recovered == secret);
        }
    }

    #[test]
    fn encoded_shares_round_trip_and_combine() {
        let secret = secret(0x24);
        let shares = split_2_of_3(&secret).expect("split");
        let encoded = shares[0].encode();
        let text = encoded.expose_secret();
        assert!(text.starts_with(SHARE_PREFIX));

        let decoded = RecoveryShare::decode(text).expect("decode");
        let recovered = combine_2_of_3(&decoded, &shares[1]).expect("combine");
        assert!(recovered == secret);
    }

    #[test]
    fn decode_accepts_lowercase_and_spaces() {
        let secret = secret(0x11);
        let shares = split_2_of_3(&secret).expect("split");
        let text = shares[0].encode();
        let body = text
            .expose_secret()
            .strip_prefix(SHARE_PREFIX)
            .expect("share prefix");
        let messy = format!("  ckr1-{}  ", body.to_lowercase().replace('-', " "));
        let decoded = RecoveryShare::decode(&messy).expect("decode");
        let recovered = combine_2_of_3(&decoded, &shares[1]).expect("combine");
        assert!(recovered == secret);
    }

    #[test]
    fn checksum_detects_single_character_corruption() {
        let secret = secret(0x33);
        let shares = split_2_of_3(&secret).expect("split");
        let encoded = shares[0].encode();
        let mut characters: Vec<char> = encoded.expose_secret().chars().collect();
        let position = characters.len() - 2;
        characters[position] = if characters[position] == 'A' {
            'B'
        } else {
            'A'
        };
        let corrupted: String = characters.into_iter().collect();
        assert!(RecoveryShare::decode(&corrupted).is_err());
    }

    #[test]
    fn rejects_bad_version_threshold_index_and_length() {
        let value = [7u8; 32];
        assert_eq!(
            RecoveryShare::decode(&raw_share(2, 1, 2, &value))
                .unwrap_err()
                .code(),
            ErrorCode::UnsupportedVersion
        );
        assert_eq!(
            RecoveryShare::decode(&raw_share(1, 1, 1, &value))
                .unwrap_err()
                .code(),
            ErrorCode::InvalidArgument
        );
        assert_eq!(
            RecoveryShare::decode(&raw_share(1, 0, 2, &value))
                .unwrap_err()
                .code(),
            ErrorCode::InvalidArgument
        );
        assert_eq!(
            RecoveryShare::decode(&raw_share(1, 4, 2, &value))
                .unwrap_err()
                .code(),
            ErrorCode::InvalidArgument
        );
        assert_eq!(
            RecoveryShare::decode("CKR1-AAAA").unwrap_err().code(),
            ErrorCode::InvalidArgument
        );
    }

    #[test]
    fn rejects_duplicate_indexes() {
        let secret = secret(0x55);
        let shares = split_2_of_3(&secret).expect("split");
        assert_eq!(
            combine_2_of_3(&shares[0], &shares[0]).unwrap_err().code(),
            ErrorCode::InvalidArgument
        );
    }

    #[test]
    fn rejects_wrong_secret_length() {
        assert_eq!(
            split_2_of_3(&SecretBytes::from_slice(&[0u8; 31]))
                .unwrap_err()
                .code(),
            ErrorCode::InvalidArgument
        );
    }

    #[test]
    fn random_secrets_round_trip_many_times() {
        for counter in 0..50u8 {
            let secret = SecretBytes::from_slice(&[counter; RECOVERY_SECRET_LEN]);
            let shares = split_2_of_3(&secret).expect("split");
            let recovered = combine_2_of_3(&shares[0], &shares[2]).expect("combine");
            assert!(recovered == secret);
        }
    }

    #[test]
    fn debug_is_redacted() {
        let secret = secret(0xAB);
        let shares = split_2_of_3(&secret).expect("split");
        let rendered = format!("{:?}", shares[0]);
        assert!(!rendered.contains("171"));
        assert!(rendered.contains("<redacted>"));
    }
}
