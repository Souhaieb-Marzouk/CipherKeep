//! XChaCha20-Poly1305 envelopes and domain-separated associated data.
//!
//! Every secret is sealed with an explicit [`Aad`] value. The AAD is
//! self-describing: it contains a fixed domain string, a purpose byte, a
//! length-prefixed context, and only then the context bytes. This prevents
//! ambiguous concatenation and makes cross-purpose or cross-record ciphertext
//! swaps fail authentication.
//!
//! Envelope wire format:
//!
//! ```text
//! offset  size  field
//! 0       1     version (1)
//! 1       1     suite (1 = XChaCha20-Poly1305)
//! 2       24    nonce
//! 26      4     ciphertext length (u32 BE, includes 16-byte tag)
//! 30      N     ciphertext || tag
//! ```

use core::fmt;

use chacha20poly1305::aead::{Aead, Payload};
use chacha20poly1305::{KeyInit, XChaCha20Poly1305, XNonce};
use getrandom::getrandom;
use zeroize::Zeroizing;

use crate::error::{CoreError, ErrorCode, Result};
use crate::header::AAD_PREFIX_LEN;
use crate::keys::{DeviceId, ItemId, VaultId, KEY_LEN};
use crate::secret::SecretBytes;

/// Current envelope format version.
pub const ENVELOPE_VERSION: u8 = 1;
/// Suite id for XChaCha20-Poly1305.
pub const SUITE_XCHACHA20_POLY1305: u8 = 1;
/// Envelope nonce length in bytes.
pub const ENVELOPE_NONCE_LEN: usize = 24;
/// Poly1305 authentication tag length in bytes.
pub const TAG_LEN: usize = 16;
/// Minimum encoded envelope length, excluding ciphertext.
pub const MIN_ENVELOPE_LEN: usize = 30;
/// Maximum accepted ciphertext length, including the tag (1 MiB).
pub const MAX_ENVELOPE_CIPHERTEXT_LEN: usize = 1_048_576;
/// Domain string prefixed to every AAD.
pub const AAD_DOMAIN: &[u8] = b"CKV1-AAD";
/// Maximum WebAuthn credential id accepted in AAD.
pub const MAX_WEBAUTHN_CREDENTIAL_ID_LEN: usize = 1024;

/// The purpose of an envelope.
///
/// Purpose bytes are stable and must never be reused for a different
/// construction.
#[repr(u8)]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Purpose {
    /// Keyring wrapped under the master-key subkey.
    Keyring = 1,
    /// Item key wrapped under a vault-key subkey.
    ItemKey = 2,
    /// Item field value.
    Field = 3,
    /// Vault key wrapped to a recipient X25519 public key.
    Sharing = 4,
    /// Recovery blob.
    Recovery = 5,
    /// WebAuthn PRF-wrapped keyring copy.
    WebAuthn = 6,
    /// Sync blob.
    Sync = 7,
    /// Encrypted portable export.
    Export = 8,
}

impl Purpose {
    /// Stable numeric purpose id.
    #[must_use]
    pub const fn as_u8(self) -> u8 {
        self as u8
    }
}

/// Domain-separated associated data for an envelope.
#[derive(Clone, Eq, PartialEq)]
pub struct Aad {
    purpose: Purpose,
    context: Vec<u8>,
}

impl Aad {
    /// AAD for wrapping a keyring under the master key.
    ///
    /// `header_prefix` must be exactly [`AAD_PREFIX_LEN`] bytes, as returned by
    /// the vault header.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::InvalidArgument`] for a wrong-length prefix.
    pub fn keyring(header_prefix: &[u8]) -> Result<Self> {
        if header_prefix.len() != AAD_PREFIX_LEN {
            return Err(CoreError::new(ErrorCode::InvalidArgument));
        }
        Ok(Self {
            purpose: Purpose::Keyring,
            context: header_prefix.to_vec(),
        })
    }

    /// AAD for an item-key wrap.
    #[must_use]
    pub fn item_key(vault_id: VaultId, item_id: ItemId, schema: u16) -> Self {
        let mut context = Vec::with_capacity(34);
        context.extend_from_slice(vault_id.as_bytes());
        context.extend_from_slice(item_id.as_bytes());
        context.extend_from_slice(&schema.to_be_bytes());
        Self {
            purpose: Purpose::ItemKey,
            context,
        }
    }

    /// AAD for a field envelope.
    #[must_use]
    pub fn field(item_id: ItemId, field_id: u16, lamport: u64, tombstone: bool) -> Self {
        let mut context = Vec::with_capacity(27);
        context.extend_from_slice(item_id.as_bytes());
        context.extend_from_slice(&field_id.to_be_bytes());
        context.extend_from_slice(&lamport.to_be_bytes());
        context.push(u8::from(tombstone));
        Self {
            purpose: Purpose::Field,
            context,
        }
    }

    /// AAD for a vault-key sharing wrap.
    #[must_use]
    pub fn sharing(vault_id: VaultId, recipient_public_key: &[u8; 32]) -> Self {
        let mut context = Vec::with_capacity(48);
        context.extend_from_slice(vault_id.as_bytes());
        context.extend_from_slice(recipient_public_key);
        Self {
            purpose: Purpose::Sharing,
            context,
        }
    }

    /// AAD for a recovery blob.
    #[must_use]
    pub fn recovery(vault_id: VaultId) -> Self {
        Self {
            purpose: Purpose::Recovery,
            context: vault_id.as_bytes().to_vec(),
        }
    }

    /// AAD for a WebAuthn PRF-wrapped keyring copy.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::InvalidArgument`] for an empty credential id and
    /// [`ErrorCode::LimitExceeded`] above 1024 bytes.
    pub fn webauthn(credential_id: &[u8]) -> Result<Self> {
        if credential_id.is_empty() {
            return Err(CoreError::new(ErrorCode::InvalidArgument));
        }
        if credential_id.len() > MAX_WEBAUTHN_CREDENTIAL_ID_LEN {
            return Err(CoreError::new(ErrorCode::LimitExceeded));
        }
        Ok(Self {
            purpose: Purpose::WebAuthn,
            context: credential_id.to_vec(),
        })
    }

    /// AAD for an encrypted portable export.
    #[must_use]
    pub fn export(header: &[u8]) -> Self {
        Self {
            purpose: Purpose::Export,
            context: header.to_vec(),
        }
    }

    /// AAD for a sync blob.
    #[must_use]
    pub fn sync(vault_id: VaultId, device_id: DeviceId, blob_version: u64) -> Self {
        let mut context = Vec::with_capacity(40);
        context.extend_from_slice(vault_id.as_bytes());
        context.extend_from_slice(device_id.as_bytes());
        context.extend_from_slice(&blob_version.to_be_bytes());
        Self {
            purpose: Purpose::Sync,
            context,
        }
    }

    /// The purpose this AAD binds.
    #[must_use]
    pub const fn purpose(&self) -> Purpose {
        self.purpose
    }

    /// Encode the AAD deterministically.
    #[must_use]
    pub fn encode(&self) -> Vec<u8> {
        let mut bytes = Vec::with_capacity(AAD_DOMAIN.len() + 1 + 4 + self.context.len());
        bytes.extend_from_slice(AAD_DOMAIN);
        bytes.push(self.purpose.as_u8());
        let context_len = u32::try_from(self.context.len()).expect("AAD context fits in u32");
        bytes.extend_from_slice(&context_len.to_be_bytes());
        bytes.extend_from_slice(&self.context);
        bytes
    }
}

impl fmt::Debug for Aad {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("Aad")
            .field("purpose", &self.purpose)
            .field("context_len", &self.context.len())
            .finish()
    }
}

/// An authenticated ciphertext envelope.
#[derive(Clone, Eq, PartialEq)]
pub struct Envelope {
    version: u8,
    suite: u8,
    nonce: [u8; ENVELOPE_NONCE_LEN],
    ciphertext: Vec<u8>,
}

impl Envelope {
    /// Seal `plaintext` under `key` with the given AAD.
    ///
    /// `key` must be exactly [`KEY_LEN`] bytes. The nonce is generated from the
    /// OS CSPRNG for every call.
    ///
    /// # Errors
    ///
    /// * [`ErrorCode::InvalidArgument`] for a wrong key length.
    /// * [`ErrorCode::LimitExceeded`] if the plaintext is too large.
    /// * [`ErrorCode::Internal`] for CSPRNG or AEAD implementation failure.
    pub fn seal(key: &SecretBytes, aad: &Aad, plaintext: &[u8]) -> Result<Self> {
        let mut nonce = [0u8; ENVELOPE_NONCE_LEN];
        getrandom(&mut nonce).map_err(|_| CoreError::new(ErrorCode::Internal))?;
        if nonce.iter().all(|byte| *byte == 0) {
            return Err(CoreError::new(ErrorCode::Internal));
        }
        Self::seal_with_nonce(key, aad, plaintext, nonce)
    }

    pub(crate) fn seal_with_nonce(
        key: &SecretBytes,
        aad: &Aad,
        plaintext: &[u8],
        nonce: [u8; ENVELOPE_NONCE_LEN],
    ) -> Result<Self> {
        if key.len() != KEY_LEN {
            return Err(CoreError::new(ErrorCode::InvalidArgument));
        }
        if plaintext.len() > MAX_ENVELOPE_CIPHERTEXT_LEN - TAG_LEN {
            return Err(CoreError::new(ErrorCode::LimitExceeded));
        }
        if nonce.iter().all(|byte| *byte == 0) {
            return Err(CoreError::new(ErrorCode::InvalidArgument));
        }

        let cipher = XChaCha20Poly1305::new_from_slice(key.expose_secret())
            .map_err(|_| CoreError::new(ErrorCode::Internal))?;
        let aad_bytes = aad.encode();
        let ciphertext = cipher
            .encrypt(
                XNonce::from_slice(&nonce),
                Payload {
                    msg: plaintext,
                    aad: &aad_bytes,
                },
            )
            .map_err(|_| CoreError::new(ErrorCode::Internal))?;
        if ciphertext.len() > MAX_ENVELOPE_CIPHERTEXT_LEN {
            return Err(CoreError::new(ErrorCode::LimitExceeded));
        }

        Ok(Self {
            version: ENVELOPE_VERSION,
            suite: SUITE_XCHACHA20_POLY1305,
            nonce,
            ciphertext,
        })
    }

    /// Open the envelope and return the plaintext as a zeroizing buffer.
    ///
    /// # Errors
    ///
    /// * [`ErrorCode::InvalidArgument`] for a wrong key length.
    /// * [`ErrorCode::UnsupportedVersion`] for an unknown version or suite.
    /// * [`ErrorCode::CorruptData`] for malformed framing or a short tag.
    /// * [`ErrorCode::AuthenticationFailed`] for a wrong key, wrong AAD or any
    ///   tampering. The error deliberately does not distinguish these cases.
    pub fn open(&self, key: &SecretBytes, aad: &Aad) -> Result<SecretBytes> {
        if key.len() != KEY_LEN {
            return Err(CoreError::new(ErrorCode::InvalidArgument));
        }
        self.validate()?;

        let cipher = XChaCha20Poly1305::new_from_slice(key.expose_secret())
            .map_err(|_| CoreError::new(ErrorCode::Internal))?;
        let aad_bytes = aad.encode();
        let plaintext = Zeroizing::new(
            cipher
                .decrypt(
                    XNonce::from_slice(&self.nonce),
                    Payload {
                        msg: &self.ciphertext,
                        aad: &aad_bytes,
                    },
                )
                .map_err(|_| CoreError::authentication_failed())?,
        );
        Ok(SecretBytes::from_slice(&plaintext[..]))
    }

    /// Encode the envelope for storage or transport.
    #[must_use]
    pub fn encode(&self) -> Vec<u8> {
        let mut bytes = Vec::with_capacity(MIN_ENVELOPE_LEN + self.ciphertext.len());
        bytes.push(self.version);
        bytes.push(self.suite);
        bytes.extend_from_slice(&self.nonce);
        let ciphertext_len =
            u32::try_from(self.ciphertext.len()).expect("validated ciphertext fits in u32");
        bytes.extend_from_slice(&ciphertext_len.to_be_bytes());
        bytes.extend_from_slice(&self.ciphertext);
        bytes
    }

    /// Decode an envelope from storage or transport.
    ///
    /// # Errors
    ///
    /// Returns a redacted [`CoreError`] for truncation, trailing bytes, an
    /// unknown version/suite, an empty/short tag, or an oversized ciphertext.
    pub fn decode(bytes: &[u8]) -> Result<Self> {
        if bytes.len() < MIN_ENVELOPE_LEN + TAG_LEN {
            return Err(CoreError::new(ErrorCode::CorruptData));
        }
        let version = bytes[0];
        let suite = bytes[1];
        if version != ENVELOPE_VERSION || suite != SUITE_XCHACHA20_POLY1305 {
            return Err(CoreError::new(ErrorCode::UnsupportedVersion));
        }

        let mut nonce = [0u8; ENVELOPE_NONCE_LEN];
        nonce.copy_from_slice(&bytes[2..26]);
        if nonce.iter().all(|byte| *byte == 0) {
            return Err(CoreError::new(ErrorCode::CorruptData));
        }

        let ciphertext_len =
            u32::from_be_bytes([bytes[26], bytes[27], bytes[28], bytes[29]]) as usize;
        if ciphertext_len < TAG_LEN {
            return Err(CoreError::new(ErrorCode::CorruptData));
        }
        if ciphertext_len > MAX_ENVELOPE_CIPHERTEXT_LEN {
            return Err(CoreError::new(ErrorCode::LimitExceeded));
        }
        let expected_len = MIN_ENVELOPE_LEN
            .checked_add(ciphertext_len)
            .ok_or_else(|| CoreError::new(ErrorCode::LimitExceeded))?;
        if bytes.len() != expected_len {
            return Err(CoreError::new(ErrorCode::CorruptData));
        }

        Ok(Self {
            version,
            suite,
            nonce,
            ciphertext: bytes[MIN_ENVELOPE_LEN..].to_vec(),
        })
    }

    /// The envelope format version.
    #[must_use]
    pub const fn version(&self) -> u8 {
        self.version
    }

    /// The encryption suite id.
    #[must_use]
    pub const fn suite(&self) -> u8 {
        self.suite
    }

    /// The nonce.
    #[must_use]
    pub const fn nonce(&self) -> &[u8; ENVELOPE_NONCE_LEN] {
        &self.nonce
    }

    /// The ciphertext, including the Poly1305 tag.
    #[must_use]
    pub fn ciphertext(&self) -> &[u8] {
        &self.ciphertext
    }

    fn validate(&self) -> Result<()> {
        if self.version != ENVELOPE_VERSION || self.suite != SUITE_XCHACHA20_POLY1305 {
            return Err(CoreError::new(ErrorCode::UnsupportedVersion));
        }
        if self.nonce.iter().all(|byte| *byte == 0) {
            return Err(CoreError::new(ErrorCode::CorruptData));
        }
        if self.ciphertext.len() < TAG_LEN {
            return Err(CoreError::new(ErrorCode::CorruptData));
        }
        if self.ciphertext.len() > MAX_ENVELOPE_CIPHERTEXT_LEN {
            return Err(CoreError::new(ErrorCode::LimitExceeded));
        }
        Ok(())
    }
}

impl fmt::Debug for Envelope {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("Envelope")
            .field("version", &self.version)
            .field("suite", &self.suite)
            .field("nonce_len", &ENVELOPE_NONCE_LEN)
            .field("ciphertext_len", &self.ciphertext.len())
            .finish()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn key(byte: u8) -> SecretBytes {
        SecretBytes::from_slice(&[byte; KEY_LEN])
    }

    fn field_aad() -> Aad {
        Aad::field(ItemId::from_bytes([2u8; 16]), 10, 7, false)
    }

    #[test]
    fn keyring_aad_rejects_wrong_length() {
        assert_eq!(
            Aad::keyring(&[0u8; AAD_PREFIX_LEN - 1]).unwrap_err().code(),
            ErrorCode::InvalidArgument
        );
        assert!(Aad::keyring(&[0u8; AAD_PREFIX_LEN]).is_ok());
    }

    #[test]
    fn webauthn_aad_rejects_empty_and_oversized_ids() {
        assert_eq!(
            Aad::webauthn(&[]).unwrap_err().code(),
            ErrorCode::InvalidArgument
        );
        assert_eq!(
            Aad::webauthn(&vec![0u8; MAX_WEBAUTHN_CREDENTIAL_ID_LEN + 1])
                .unwrap_err()
                .code(),
            ErrorCode::LimitExceeded
        );
    }

    #[test]
    fn aad_binds_purpose_and_context() {
        let first = field_aad().encode();
        let other_field = Aad::field(ItemId::from_bytes([2u8; 16]), 11, 7, false).encode();
        let other_purpose = Aad::recovery(VaultId::from_bytes([2u8; 16])).encode();
        assert_ne!(first, other_field);
        assert_ne!(first, other_purpose);
        assert!(first.starts_with(AAD_DOMAIN));
        assert_eq!(first[AAD_DOMAIN.len()], Purpose::Field.as_u8());
    }

    #[test]
    fn purpose_ids_are_stable() {
        assert_eq!(Purpose::Keyring.as_u8(), 1);
        assert_eq!(Purpose::ItemKey.as_u8(), 2);
        assert_eq!(Purpose::Field.as_u8(), 3);
        assert_eq!(Purpose::Sharing.as_u8(), 4);
        assert_eq!(Purpose::Recovery.as_u8(), 5);
        assert_eq!(Purpose::WebAuthn.as_u8(), 6);
        assert_eq!(Purpose::Sync.as_u8(), 7);
        assert_eq!(Purpose::Export.as_u8(), 8);
    }

    fn to_hex(bytes: &[u8]) -> String {
        use core::fmt::Write;
        let mut output = String::with_capacity(bytes.len() * 2);
        for byte in bytes {
            write!(&mut output, "{byte:02x}").expect("String write");
        }
        output
    }

    #[test]
    fn xchacha20_poly1305_known_answer() {
        // Independently generated with PyNaCl/libsodium. AAD is exactly
        // `Aad::field(ItemId([2;16]), 10, 7, false)`; key is 32 x 0x42;
        // nonce is 24 x 0x24.
        const EXPECTED_CIPHERTEXT: &str = concat!(
            "ce30b81200042dc8d1585a482efc68b946d1e8d50e3f2cde4900a5250a22ebe044",
            "fee4a105a86ba95f6d947c599cb73d13d69173eb23f9ad"
        );
        let key = key(0x42);
        let aad = field_aad();
        let nonce = [0x24u8; ENVELOPE_NONCE_LEN];
        let plaintext = b"known answer test for XChaCha20-Poly1305";

        let envelope =
            Envelope::seal_with_nonce(&key, &aad, plaintext, nonce).expect("deterministic seal");
        assert_eq!(to_hex(envelope.ciphertext()), EXPECTED_CIPHERTEXT);
        assert_eq!(envelope.nonce(), &nonce);

        let opened = envelope.open(&key, &aad).expect("open");
        assert_eq!(opened.expose_secret(), plaintext);
    }

    #[test]
    fn all_aad_purposes_are_distinct() {
        use std::collections::HashSet;

        let aads = [
            Aad::keyring(&[0x11u8; AAD_PREFIX_LEN]).expect("keyring aad"),
            Aad::item_key(
                VaultId::from_bytes([1u8; 16]),
                ItemId::from_bytes([2u8; 16]),
                1,
            ),
            field_aad(),
            Aad::sharing(VaultId::from_bytes([1u8; 16]), &[0x22u8; 32]),
            Aad::recovery(VaultId::from_bytes([1u8; 16])),
            Aad::webauthn(b"credential-id").expect("webauthn aad"),
            Aad::sync(
                VaultId::from_bytes([1u8; 16]),
                DeviceId::from_bytes([3u8; 16]),
                9,
            ),
        ];
        let mut seen = HashSet::new();
        for aad in aads {
            assert!(seen.insert(aad.encode()));
        }
        assert_eq!(seen.len(), 7);
    }

    #[test]
    fn envelope_round_trips() {
        let key = key(0x42);
        let aad = field_aad();
        let envelope = Envelope::seal(&key, &aad, b"secret value").expect("seal");
        assert_eq!(envelope.version(), ENVELOPE_VERSION);
        assert_eq!(envelope.suite(), SUITE_XCHACHA20_POLY1305);
        assert_eq!(envelope.ciphertext().len(), b"secret value".len() + TAG_LEN);

        let decoded = Envelope::decode(&envelope.encode()).expect("decode");
        let plaintext = decoded.open(&key, &aad).expect("open");
        assert_eq!(plaintext.expose_secret(), b"secret value");
    }

    #[test]
    fn wrong_key_and_wrong_aad_fail() {
        let key_material = key(0x42);
        let aad = field_aad();
        let envelope = Envelope::seal(&key_material, &aad, b"secret value").expect("seal");

        assert_eq!(
            envelope.open(&key(0x43), &aad).unwrap_err().code(),
            ErrorCode::AuthenticationFailed
        );
        assert_eq!(
            envelope
                .open(
                    &key_material,
                    &Aad::item_key(
                        VaultId::from_bytes([1u8; 16]),
                        ItemId::from_bytes([2u8; 16]),
                        1
                    )
                )
                .unwrap_err()
                .code(),
            ErrorCode::AuthenticationFailed
        );
    }

    #[test]
    fn every_envelope_byte_is_tamper_evident() {
        let key = key(0x42);
        let aad = field_aad();
        let encoded = Envelope::seal(&key, &aad, b"secret value")
            .expect("seal")
            .encode();

        for index in 0..encoded.len() {
            let mut tampered = encoded.clone();
            tampered[index] ^= 0x01;
            let result = Envelope::decode(&tampered).and_then(|envelope| envelope.open(&key, &aad));
            assert!(result.is_err(), "byte {index} was not tamper-evident");
        }
    }

    #[test]
    fn rejects_short_key_and_oversized_plaintext() {
        let aad = field_aad();
        assert_eq!(
            Envelope::seal(&SecretBytes::from_slice(&[0u8; KEY_LEN - 1]), &aad, b"x")
                .unwrap_err()
                .code(),
            ErrorCode::InvalidArgument
        );
        assert_eq!(
            Envelope::seal(
                &key(1),
                &aad,
                &vec![0u8; MAX_ENVELOPE_CIPHERTEXT_LEN - TAG_LEN + 1]
            )
            .unwrap_err()
            .code(),
            ErrorCode::LimitExceeded
        );
    }

    #[test]
    fn rejects_bad_version_suite_and_framing() {
        let encoded = Envelope::seal(&key(1), &field_aad(), b"x")
            .expect("seal")
            .encode();

        let mut bad_version = encoded.clone();
        bad_version[0] = 2;
        assert_eq!(
            Envelope::decode(&bad_version).unwrap_err().code(),
            ErrorCode::UnsupportedVersion
        );

        let mut bad_suite = encoded.clone();
        bad_suite[1] = 2;
        assert_eq!(
            Envelope::decode(&bad_suite).unwrap_err().code(),
            ErrorCode::UnsupportedVersion
        );

        let mut short = encoded.clone();
        short.pop();
        assert_eq!(
            Envelope::decode(&short).unwrap_err().code(),
            ErrorCode::CorruptData
        );

        let mut trailing = encoded;
        trailing.push(0);
        assert_eq!(
            Envelope::decode(&trailing).unwrap_err().code(),
            ErrorCode::CorruptData
        );
    }
}
