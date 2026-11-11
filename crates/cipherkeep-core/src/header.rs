//! Versioned, authenticated vault header.
//!
//! The header binds the Argon2id parameters, salt, keyring nonce and keyring
//! ciphertext into one deterministic encoding. The fixed 48-byte prefix
//! (magic through salt) is the AAD for the keyring envelope implemented by
//! changing any header byte therefore invalidates the keyring wrap.
//!
//! Wire format, version 1, big-endian:
//!
//! ```text
//! offset  size  field
//! 0       4     magic "CKV1"
//! 4       2     format version (1)
//! 6       1     KDF algorithm (1 = Argon2id)
//! 7       1     reserved, zero
//! 8       4     m_cost_kib
//! 12      4     t_cost
//! 16      1     p_cost
//! 17      15    reserved, zero
//! 32      16    salt
//! 48      24    keyring nonce
//! 72      4     keyring ciphertext length (including Poly1305 tag)
//! 76      N     keyring ciphertext
//! ```

use core::fmt;

use crate::error::{CoreError, ErrorCode, Result};
use crate::kdf::{KdfAlgorithm, KdfParams, KdfPolicy, SALT_LEN};

/// Magic bytes at the start of every vault header.
pub const MAGIC: [u8; 4] = *b"CKV1";

/// Current vault header format version.
pub const FORMAT_VERSION: u16 = 1;

/// Length of the keyring nonce in bytes.
pub const NONCE_LEN: usize = 24;

/// Length of the AAD prefix (magic through salt) in bytes.
pub const AAD_PREFIX_LEN: usize = 48;

/// Minimum encoded header length in bytes, excluding keyring ciphertext.
pub const MIN_HEADER_LEN: usize = 76;

/// Maximum accepted keyring ciphertext length in bytes (1 MiB).
pub const MAX_KEYRING_CIPHERTEXT_LEN: usize = 1_048_576;

/// Maximum accepted encoded header length in bytes.
pub const MAX_HEADER_LEN: usize = MIN_HEADER_LEN + MAX_KEYRING_CIPHERTEXT_LEN;

/// A parsed vault header.
pub struct VaultHeader {
    kdf: KdfParams,
    keyring_nonce: [u8; NONCE_LEN],
    keyring_ciphertext: Vec<u8>,
}

impl VaultHeader {
    /// Construct a header from validated parts.
    ///
    /// # Errors
    ///
    /// * [`ErrorCode::PolicyRejected`] if the KDF parameters are below policy.
    /// * [`ErrorCode::InvalidArgument`] for an empty keyring ciphertext, an
    ///   all-zero nonce, or invalid KDF parameters.
    /// * [`ErrorCode::LimitExceeded`] if the ciphertext is too large.
    pub fn new(
        kdf: KdfParams,
        keyring_nonce: [u8; NONCE_LEN],
        keyring_ciphertext: Vec<u8>,
    ) -> Result<Self> {
        kdf.validate(&KdfPolicy::default())?;
        Self::validate_parts(
            &keyring_nonce,
            &keyring_ciphertext,
            ErrorCode::InvalidArgument,
        )?;
        Ok(Self {
            kdf,
            keyring_nonce,
            keyring_ciphertext,
        })
    }

    /// Decode a header using the default security policy.
    ///
    /// # Errors
    ///
    /// Returns a redacted [`CoreError`] for a bad magic value, unsupported
    /// version, unknown KDF, non-zero reserved bytes, wrong length, empty or
    /// oversized ciphertext, all-zero nonce, or parameters below policy.
    pub fn decode(bytes: &[u8]) -> Result<Self> {
        Self::decode_with_policy(bytes, &KdfPolicy::default())
    }

    /// Decode a header using an explicit security policy.
    ///
    /// # Errors
    ///
    /// As [`VaultHeader::decode`].
    pub fn decode_with_policy(bytes: &[u8], policy: &KdfPolicy) -> Result<Self> {
        if bytes.len() < MIN_HEADER_LEN {
            return Err(CoreError::new(ErrorCode::CorruptData));
        }
        if bytes.len() > MAX_HEADER_LEN {
            return Err(CoreError::new(ErrorCode::LimitExceeded));
        }
        if bytes[0..4] != MAGIC {
            return Err(CoreError::new(ErrorCode::CorruptData));
        }

        let version = u16::from_be_bytes([bytes[4], bytes[5]]);
        if version != FORMAT_VERSION {
            return Err(CoreError::new(ErrorCode::UnsupportedVersion));
        }

        // Validate the algorithm id; only Argon2id exists today.
        KdfAlgorithm::try_from(bytes[6])?;

        if bytes[7] != 0 || bytes[17..32].iter().any(|byte| *byte != 0) {
            return Err(CoreError::new(ErrorCode::CorruptData));
        }

        let m_cost_kib = u32::from_be_bytes([bytes[8], bytes[9], bytes[10], bytes[11]]);
        let t_cost = u32::from_be_bytes([bytes[12], bytes[13], bytes[14], bytes[15]]);
        let p_cost = bytes[16];

        let mut salt = [0u8; SALT_LEN];
        salt.copy_from_slice(&bytes[32..48]);

        let mut keyring_nonce = [0u8; NONCE_LEN];
        keyring_nonce.copy_from_slice(&bytes[48..72]);

        let ciphertext_len =
            u32::from_be_bytes([bytes[72], bytes[73], bytes[74], bytes[75]]) as usize;
        if ciphertext_len == 0 {
            return Err(CoreError::new(ErrorCode::CorruptData));
        }
        if ciphertext_len > MAX_KEYRING_CIPHERTEXT_LEN {
            return Err(CoreError::new(ErrorCode::LimitExceeded));
        }
        let expected_len = MIN_HEADER_LEN
            .checked_add(ciphertext_len)
            .ok_or_else(|| CoreError::new(ErrorCode::LimitExceeded))?;
        if bytes.len() != expected_len {
            return Err(CoreError::new(ErrorCode::CorruptData));
        }

        let kdf = KdfParams::new(m_cost_kib, t_cost, p_cost, salt);
        kdf.validate(policy)?;
        Self::validate_parts(
            &keyring_nonce,
            &bytes[MIN_HEADER_LEN..],
            ErrorCode::CorruptData,
        )?;

        Ok(Self {
            kdf,
            keyring_nonce,
            keyring_ciphertext: bytes[MIN_HEADER_LEN..].to_vec(),
        })
    }

    /// Encode the header.
    #[must_use]
    pub fn encode(&self) -> Vec<u8> {
        let mut bytes = Vec::with_capacity(MIN_HEADER_LEN + self.keyring_ciphertext.len());
        bytes.extend_from_slice(&self.aad_prefix());
        bytes.extend_from_slice(&self.keyring_nonce);
        let ciphertext_len = u32::try_from(self.keyring_ciphertext.len())
            .expect("validated ciphertext length fits in u32");
        bytes.extend_from_slice(&ciphertext_len.to_be_bytes());
        bytes.extend_from_slice(&self.keyring_ciphertext);
        bytes
    }

    /// The fixed AAD prefix for the keyring envelope: magic through salt.
    ///
    /// The header must use exactly these bytes as the AEAD associated data so that
    /// changing the algorithm, parameters, salt or version invalidates the
    /// keyring wrap.
    #[must_use]
    pub fn aad_prefix(&self) -> [u8; AAD_PREFIX_LEN] {
        Self::aad_prefix_for(&self.kdf)
    }

    /// Build the AAD prefix for a KDF parameter set before a header exists.
    ///
    /// This is used by vault creation, where the keyring must be sealed under
    /// the exact header prefix before [`VaultHeader::new`] can be called.
    #[must_use]
    pub fn aad_prefix_for(kdf: &KdfParams) -> [u8; AAD_PREFIX_LEN] {
        let mut prefix = [0u8; AAD_PREFIX_LEN];
        prefix[0..4].copy_from_slice(&MAGIC);
        prefix[4..6].copy_from_slice(&FORMAT_VERSION.to_be_bytes());
        prefix[6] = KdfAlgorithm::Argon2id.as_u8();
        prefix[7] = 0;
        prefix[8..12].copy_from_slice(&kdf.m_cost_kib().to_be_bytes());
        prefix[12..16].copy_from_slice(&kdf.t_cost().to_be_bytes());
        prefix[16] = kdf.p_cost();
        prefix[32..48].copy_from_slice(kdf.salt());
        prefix
    }

    /// The Argon2id parameters and salt.
    #[must_use]
    pub const fn kdf_params(&self) -> &KdfParams {
        &self.kdf
    }

    /// The keyring nonce.
    #[must_use]
    pub const fn keyring_nonce(&self) -> &[u8; NONCE_LEN] {
        &self.keyring_nonce
    }

    /// The opaque keyring ciphertext, including its authentication tag.
    #[must_use]
    pub fn keyring_ciphertext(&self) -> &[u8] {
        &self.keyring_ciphertext
    }

    fn validate_parts(
        nonce: &[u8; NONCE_LEN],
        ciphertext: &[u8],
        invalid_kind: ErrorCode,
    ) -> Result<()> {
        if nonce.iter().all(|byte| *byte == 0) || ciphertext.is_empty() {
            return Err(CoreError::new(invalid_kind));
        }
        if ciphertext.len() > MAX_KEYRING_CIPHERTEXT_LEN {
            return Err(CoreError::new(ErrorCode::LimitExceeded));
        }
        Ok(())
    }
}

impl fmt::Debug for VaultHeader {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("VaultHeader")
            .field("format_version", &FORMAT_VERSION)
            .field("kdf", &self.kdf)
            .field("keyring_nonce_len", &NONCE_LEN)
            .field("keyring_ciphertext_len", &self.keyring_ciphertext.len())
            .finish()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::kdf::{DEFAULT_ITERATIONS, DEFAULT_MEMORY_KIB, DEFAULT_PARALLELISM};

    fn params() -> KdfParams {
        KdfParams::new(
            DEFAULT_MEMORY_KIB,
            DEFAULT_ITERATIONS,
            DEFAULT_PARALLELISM,
            [0x11u8; SALT_LEN],
        )
    }

    fn header() -> VaultHeader {
        VaultHeader::new(params(), [0x42u8; NONCE_LEN], vec![0xABu8; 64]).expect("valid header")
    }

    #[test]
    fn header_round_trips() {
        let encoded = header().encode();
        let decoded = VaultHeader::decode(&encoded).expect("decode");
        assert_eq!(decoded.kdf_params(), &params());
        assert_eq!(decoded.keyring_nonce(), &[0x42u8; NONCE_LEN]);
        assert_eq!(decoded.keyring_ciphertext(), &[0xABu8; 64]);
        assert_eq!(encoded.len(), MIN_HEADER_LEN + 64);
    }

    #[test]
    fn aad_prefix_binds_the_header() {
        let prefix = header().aad_prefix();
        assert_eq!(prefix.len(), AAD_PREFIX_LEN);
        assert_eq!(&prefix[0..4], &MAGIC);
        assert_eq!(&prefix[4..6], &FORMAT_VERSION.to_be_bytes());
        assert_eq!(prefix[6], KdfAlgorithm::Argon2id.as_u8());
        assert_eq!(&prefix[8..12], &DEFAULT_MEMORY_KIB.to_be_bytes());
        assert_eq!(&prefix[12..16], &DEFAULT_ITERATIONS.to_be_bytes());
        assert_eq!(prefix[16], DEFAULT_PARALLELISM);
        assert_eq!(&prefix[32..48], &[0x11u8; SALT_LEN]);
    }

    #[test]
    fn rejects_short_and_oversized_headers() {
        assert_eq!(
            VaultHeader::decode(&[0u8; MIN_HEADER_LEN - 1])
                .unwrap_err()
                .code(),
            ErrorCode::CorruptData
        );
        assert_eq!(
            VaultHeader::decode(&vec![0u8; MAX_HEADER_LEN + 1])
                .unwrap_err()
                .code(),
            ErrorCode::LimitExceeded
        );
    }

    #[test]
    fn rejects_bad_magic() {
        let mut encoded = header().encode();
        encoded[0] = b'X';
        assert_eq!(
            VaultHeader::decode(&encoded).unwrap_err().code(),
            ErrorCode::CorruptData
        );
    }

    #[test]
    fn rejects_unknown_version() {
        let mut encoded = header().encode();
        encoded[4..6].copy_from_slice(&2u16.to_be_bytes());
        assert_eq!(
            VaultHeader::decode(&encoded).unwrap_err().code(),
            ErrorCode::UnsupportedVersion
        );
    }

    #[test]
    fn rejects_unknown_kdf() {
        let mut encoded = header().encode();
        encoded[6] = 2;
        assert_eq!(
            VaultHeader::decode(&encoded).unwrap_err().code(),
            ErrorCode::UnsupportedVersion
        );
    }

    #[test]
    fn rejects_nonzero_reserved_bytes() {
        let mut encoded = header().encode();
        encoded[7] = 1;
        assert_eq!(
            VaultHeader::decode(&encoded).unwrap_err().code(),
            ErrorCode::CorruptData
        );

        let mut encoded = header().encode();
        encoded[17] = 1;
        assert_eq!(
            VaultHeader::decode(&encoded).unwrap_err().code(),
            ErrorCode::CorruptData
        );
    }

    #[test]
    fn rejects_zero_nonce() {
        let mut encoded = header().encode();
        encoded[48..72].fill(0);
        assert_eq!(
            VaultHeader::decode(&encoded).unwrap_err().code(),
            ErrorCode::CorruptData
        );
    }

    #[test]
    fn rejects_empty_ciphertext() {
        let mut encoded = header().encode();
        encoded[72..76].copy_from_slice(&0u32.to_be_bytes());
        encoded.truncate(MIN_HEADER_LEN);
        assert_eq!(
            VaultHeader::decode(&encoded).unwrap_err().code(),
            ErrorCode::CorruptData
        );
    }

    #[test]
    fn rejects_trailing_bytes() {
        let mut encoded = header().encode();
        encoded.push(0);
        assert_eq!(
            VaultHeader::decode(&encoded).unwrap_err().code(),
            ErrorCode::CorruptData
        );
    }

    #[test]
    fn rejects_weak_parameters() {
        let mut encoded = header().encode();
        encoded[8..12].copy_from_slice(&1024u32.to_be_bytes());
        assert_eq!(
            VaultHeader::decode(&encoded).unwrap_err().code(),
            ErrorCode::PolicyRejected
        );
    }

    #[test]
    fn rejects_empty_or_zero_nonce_on_construction() {
        assert_eq!(
            VaultHeader::new(params(), [0u8; NONCE_LEN], vec![1])
                .unwrap_err()
                .code(),
            ErrorCode::InvalidArgument
        );
        assert_eq!(
            VaultHeader::new(params(), [1u8; NONCE_LEN], Vec::new())
                .unwrap_err()
                .code(),
            ErrorCode::InvalidArgument
        );
    }

    #[test]
    fn rejects_oversized_ciphertext_on_construction() {
        assert_eq!(
            VaultHeader::new(
                params(),
                [1u8; NONCE_LEN],
                vec![0u8; MAX_KEYRING_CIPHERTEXT_LEN + 1]
            )
            .unwrap_err()
            .code(),
            ErrorCode::LimitExceeded
        );
    }

    #[test]
    fn rejects_zero_salt_on_construction() {
        let params = KdfParams::new(
            DEFAULT_MEMORY_KIB,
            DEFAULT_ITERATIONS,
            DEFAULT_PARALLELISM,
            [0u8; SALT_LEN],
        );
        assert_eq!(
            VaultHeader::new(params, [1u8; NONCE_LEN], vec![1])
                .unwrap_err()
                .code(),
            ErrorCode::PolicyRejected
        );
    }

    #[test]
    fn debug_does_not_print_ciphertext() {
        let mut ciphertext = b"SUPERSECRETCIPHERTEXT".to_vec();
        ciphertext.resize(64, 0);
        let header =
            VaultHeader::new(params(), [7u8; NONCE_LEN], ciphertext).expect("valid header");
        let rendered = format!("{header:?}");
        assert!(!rendered.contains("SUPERSECRET"));
        assert!(rendered.contains("keyring_ciphertext_len"));
    }
}
