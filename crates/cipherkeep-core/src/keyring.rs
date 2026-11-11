//! Keyring encoding and master-key wrapping.
//!
//! The keyring is a versioned, canonical container for vault keys. It exists
//! only in plaintext inside the process; at rest it is always wrapped by a
//! `HKDF(master_key, keyring, header_prefix)` subkey with the full header
//! prefix as AAD.
//!
//! Wire format, version 1, big-endian:
//!
//! ```text
//! offset  size  field
//! 0       4     magic "CKK1"
//! 4       2     format version (1)
//! 6       2     vault count (1..=16)
//! 8       ...   entries: vault_id (16) || vault_key (32)
//! ```

use core::fmt;

use zeroize::Zeroize;

use crate::envelope::{Aad, Envelope, ENVELOPE_NONCE_LEN};
use crate::error::{CoreError, ErrorCode, Result};
use crate::header::AAD_PREFIX_LEN;
use crate::keys::{derive_subkey, MasterKey, VaultId, VaultKey, DOMAIN_KEYRING, KEY_LEN};
use crate::secret::SecretBytes;

/// Maximum number of vaults in one keyring.
pub const MAX_VAULTS: usize = 16;

const KEYRING_MAGIC: [u8; 4] = *b"CKK1";
const KEYRING_VERSION: u16 = 1;
const KEYRING_HEADER_LEN: usize = 8;
const KEYRING_ENTRY_LEN: usize = 16 + KEY_LEN;
const MAX_KEYRING_LEN: usize = KEYRING_HEADER_LEN + MAX_VAULTS * KEYRING_ENTRY_LEN;

/// A collection of vault keys protected by the master key.
pub struct Keyring {
    vaults: Vec<VaultKey>,
}

impl Keyring {
    /// Generate a keyring with one fresh vault.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::Internal`] if the OS CSPRNG fails.
    pub fn generate() -> Result<Self> {
        Self::from_vaults(vec![VaultKey::generate()?])
    }

    /// Construct a keyring from existing vault keys.
    ///
    /// # Errors
    ///
    /// * [`ErrorCode::InvalidArgument`] for an empty list, a duplicate vault id,
    ///   or a malformed vault key.
    /// * [`ErrorCode::LimitExceeded`] above [`MAX_VAULTS`] entries.
    pub fn from_vaults(vaults: Vec<VaultKey>) -> Result<Self> {
        if vaults.is_empty() {
            return Err(CoreError::new(ErrorCode::InvalidArgument));
        }
        if vaults.len() > MAX_VAULTS {
            return Err(CoreError::new(ErrorCode::LimitExceeded));
        }
        for (index, vault) in vaults.iter().enumerate() {
            if vaults
                .iter()
                .skip(index + 1)
                .any(|other| other.id() == vault.id())
            {
                return Err(CoreError::new(ErrorCode::InvalidArgument));
            }
        }
        Ok(Self { vaults })
    }

    /// Number of vaults.
    #[must_use]
    pub fn len(&self) -> usize {
        self.vaults.len()
    }

    /// Whether the keyring has no vaults. A valid keyring is never empty.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.vaults.is_empty()
    }

    /// Look up a vault by id.
    #[must_use]
    pub fn vault(&self, id: VaultId) -> Option<&VaultKey> {
        self.vaults.iter().find(|vault| vault.id() == id)
    }

    /// The first vault id. A valid keyring always has one.
    #[must_use]
    pub fn first_vault_id(&self) -> Option<VaultId> {
        self.vaults.first().map(VaultKey::id)
    }

    /// The first vault key. A valid keyring always has one.
    #[must_use]
    pub fn first_vault(&self) -> Option<&VaultKey> {
        self.vaults.first()
    }

    /// Wrap this keyring under the master key.
    ///
    /// `keyring_nonce` must be the nonce stored in the vault header. It is
    /// written into the envelope and compared on open, so the header and
    /// envelope cannot disagree silently.
    ///
    /// # Errors
    ///
    /// Returns a redacted [`CoreError`] if derivation, encoding or sealing
    /// fails.
    pub fn seal(
        &self,
        master_key: &MasterKey,
        header_prefix: &[u8; AAD_PREFIX_LEN],
        keyring_nonce: &[u8; ENVELOPE_NONCE_LEN],
    ) -> Result<Envelope> {
        let wrapping_key = derive_subkey(master_key.secret(), DOMAIN_KEYRING, header_prefix)?;
        let plaintext = self.encode();
        Envelope::seal_with_nonce(
            &wrapping_key,
            &Aad::keyring(header_prefix)?,
            plaintext.expose_secret(),
            *keyring_nonce,
        )
    }

    /// Open a keyring previously sealed under the master key.
    ///
    /// # Errors
    ///
    /// * [`ErrorCode::AuthenticationFailed`] for a wrong master key, wrong
    ///   header prefix, mismatched header/envelope nonce or tampered envelope.
    /// * [`ErrorCode::CorruptData`] for a malformed keyring payload.
    pub fn open(
        master_key: &MasterKey,
        header_prefix: &[u8; AAD_PREFIX_LEN],
        keyring_nonce: &[u8; ENVELOPE_NONCE_LEN],
        envelope: &Envelope,
    ) -> Result<Self> {
        if envelope.nonce() != keyring_nonce {
            return Err(CoreError::authentication_failed());
        }
        let wrapping_key = derive_subkey(master_key.secret(), DOMAIN_KEYRING, header_prefix)?;
        let plaintext = envelope.open(&wrapping_key, &Aad::keyring(header_prefix)?)?;
        Self::decode(plaintext.expose_secret())
    }

    /// Open an encoded keyring envelope, mapping framing corruption to the same
    /// generic authentication failure as a wrong master password.
    ///
    /// Use this at the unlock boundary. It prevents an attacker from using the
    /// difference between "malformed keyring" and "wrong password" as an oracle.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::AuthenticationFailed`] for malformed framing,
    /// wrong key material, a wrong header prefix, a mismatched nonce,
    /// tampering, or a malformed decrypted keyring.
    pub fn open_encoded(
        master_key: &MasterKey,
        header_prefix: &[u8; AAD_PREFIX_LEN],
        keyring_nonce: &[u8; ENVELOPE_NONCE_LEN],
        encoded_envelope: &[u8],
    ) -> Result<Self> {
        let envelope =
            Envelope::decode(encoded_envelope).map_err(|_| CoreError::authentication_failed())?;
        Self::open(master_key, header_prefix, keyring_nonce, &envelope)
            .map_err(|_| CoreError::authentication_failed())
    }

    /// Seal a copy of this keyring under a recovery key.
    ///
    /// The recovery key is the 32-byte secret split by
    /// [`crate::shamir::split_2_of_3`]. This is an additional wrapping path;
    /// it does not replace the master-password unlock.
    ///
    /// # Errors
    ///
    /// Returns a redacted [`CoreError`] if the recovery key is malformed or
    /// sealing fails.
    pub fn seal_recovery(&self, recovery_key: &SecretBytes, vault_id: VaultId) -> Result<Envelope> {
        let plaintext = self.encode();
        Envelope::seal(
            recovery_key,
            &Aad::recovery(vault_id),
            plaintext.expose_secret(),
        )
    }

    /// Open a keyring copy sealed under a recovery key.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::AuthenticationFailed`] for a wrong recovery key,
    /// wrong vault id or tampered envelope, and [`ErrorCode::CorruptData`] for
    /// a malformed decrypted keyring.
    pub fn open_recovery(
        recovery_key: &SecretBytes,
        vault_id: VaultId,
        envelope: &Envelope,
    ) -> Result<Self> {
        let plaintext = envelope.open(recovery_key, &Aad::recovery(vault_id))?;
        Self::decode(plaintext.expose_secret())
    }

    fn encode(&self) -> SecretBytes {
        let mut bytes =
            Vec::with_capacity(KEYRING_HEADER_LEN + self.vaults.len() * KEYRING_ENTRY_LEN);
        bytes.extend_from_slice(&KEYRING_MAGIC);
        bytes.extend_from_slice(&KEYRING_VERSION.to_be_bytes());
        let count = u16::try_from(self.vaults.len()).expect("validated vault count fits in u16");
        bytes.extend_from_slice(&count.to_be_bytes());
        for vault in &self.vaults {
            bytes.extend_from_slice(vault.id().as_bytes());
            bytes.extend_from_slice(vault.secret().expose_secret());
        }
        let secret = SecretBytes::from_slice(&bytes);
        bytes.zeroize();
        secret
    }

    fn decode(bytes: &[u8]) -> Result<Self> {
        if bytes.len() < KEYRING_HEADER_LEN {
            return Err(CoreError::new(ErrorCode::CorruptData));
        }
        if bytes.len() > MAX_KEYRING_LEN {
            return Err(CoreError::new(ErrorCode::LimitExceeded));
        }
        if bytes[0..4] != KEYRING_MAGIC {
            return Err(CoreError::new(ErrorCode::CorruptData));
        }
        let version = u16::from_be_bytes([bytes[4], bytes[5]]);
        if version != KEYRING_VERSION {
            return Err(CoreError::new(ErrorCode::UnsupportedVersion));
        }
        let count = u16::from_be_bytes([bytes[6], bytes[7]]) as usize;
        if count == 0 {
            return Err(CoreError::new(ErrorCode::CorruptData));
        }
        if count > MAX_VAULTS {
            return Err(CoreError::new(ErrorCode::LimitExceeded));
        }
        let expected_len = KEYRING_HEADER_LEN
            .checked_add(count * KEYRING_ENTRY_LEN)
            .ok_or_else(|| CoreError::new(ErrorCode::LimitExceeded))?;
        if bytes.len() != expected_len {
            return Err(CoreError::new(ErrorCode::CorruptData));
        }

        let mut vaults = Vec::with_capacity(count);
        for index in 0..count {
            let offset = KEYRING_HEADER_LEN + index * KEYRING_ENTRY_LEN;
            let mut id = [0u8; 16];
            id.copy_from_slice(&bytes[offset..offset + 16]);
            let key = SecretBytes::from_slice(&bytes[offset + 16..offset + KEYRING_ENTRY_LEN]);
            vaults.push(VaultKey::from_parts(VaultId::from_bytes(id), key)?);
        }
        Self::from_vaults(vaults)
    }
}

impl fmt::Debug for Keyring {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("Keyring")
            .field("vault_count", &self.vaults.len())
            .finish()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::keys::VaultKey;

    fn master(byte: u8) -> MasterKey {
        MasterKey::from_secret_bytes(SecretBytes::from_slice(&[byte; KEY_LEN]))
            .expect("valid master key")
    }

    fn header_prefix() -> [u8; AAD_PREFIX_LEN] {
        [0x11u8; AAD_PREFIX_LEN]
    }

    fn keyring_nonce() -> [u8; ENVELOPE_NONCE_LEN] {
        [0x33u8; ENVELOPE_NONCE_LEN]
    }

    #[test]
    fn generate_has_one_vault() {
        let keyring = Keyring::generate().expect("keyring");
        assert_eq!(keyring.len(), 1);
        assert!(!keyring.is_empty());
        assert!(keyring.vault(keyring.vaults[0].id()).is_some());
    }

    #[test]
    fn encode_decode_round_trips() {
        let keyring = Keyring::generate().expect("keyring");
        let encoded = keyring.encode();
        let decoded = Keyring::decode(encoded.expose_secret()).expect("decode");
        assert_eq!(decoded.len(), 1);
        assert_eq!(decoded.vaults[0].id(), keyring.vaults[0].id());
        assert!(decoded.vaults[0].secret() == keyring.vaults[0].secret());
    }

    #[test]
    fn seals_and_opens_under_master_key() {
        let keyring = Keyring::generate().expect("keyring");
        let envelope = keyring
            .seal(&master(1), &header_prefix(), &keyring_nonce())
            .expect("seal");
        let opened =
            Keyring::open(&master(1), &header_prefix(), &keyring_nonce(), &envelope).expect("open");
        assert_eq!(opened.vaults[0].id(), keyring.vaults[0].id());
    }

    #[test]
    fn wrong_master_key_and_header_fail_with_the_same_generic_error() {
        let keyring = Keyring::generate().expect("keyring");
        let envelope = keyring
            .seal(&master(1), &header_prefix(), &keyring_nonce())
            .expect("seal");

        assert_eq!(
            Keyring::open(&master(2), &header_prefix(), &keyring_nonce(), &envelope)
                .unwrap_err()
                .code(),
            ErrorCode::AuthenticationFailed
        );
        assert_eq!(
            Keyring::open(
                &master(1),
                &[0x12u8; AAD_PREFIX_LEN],
                &keyring_nonce(),
                &envelope
            )
            .unwrap_err()
            .code(),
            ErrorCode::AuthenticationFailed
        );
        assert_eq!(
            Keyring::open(
                &master(1),
                &header_prefix(),
                &[0x44u8; ENVELOPE_NONCE_LEN],
                &envelope
            )
            .unwrap_err()
            .code(),
            ErrorCode::AuthenticationFailed
        );
    }

    #[test]
    fn open_encoded_hides_wrong_password_from_corrupt_framing() {
        let keyring = Keyring::generate().expect("keyring");
        let envelope = keyring
            .seal(&master(1), &header_prefix(), &keyring_nonce())
            .expect("seal");
        let encoded = envelope.encode();

        assert!(
            Keyring::open_encoded(&master(1), &header_prefix(), &keyring_nonce(), &encoded).is_ok()
        );
        assert_eq!(
            Keyring::open_encoded(&master(2), &header_prefix(), &keyring_nonce(), &encoded)
                .unwrap_err()
                .code(),
            ErrorCode::AuthenticationFailed
        );

        let mut tampered = encoded.clone();
        tampered[30] ^= 1;
        assert_eq!(
            Keyring::open_encoded(&master(1), &header_prefix(), &keyring_nonce(), &tampered)
                .unwrap_err()
                .code(),
            ErrorCode::AuthenticationFailed
        );

        let mut truncated = encoded;
        truncated.pop();
        assert_eq!(
            Keyring::open_encoded(&master(1), &header_prefix(), &keyring_nonce(), &truncated)
                .unwrap_err()
                .code(),
            ErrorCode::AuthenticationFailed
        );
    }

    #[test]
    fn recovery_wrap_round_trips_and_is_bound_to_key_and_vault() {
        let keyring = Keyring::generate().expect("keyring");
        let recovery_key = SecretBytes::from_slice(&[0x55u8; KEY_LEN]);
        let vault_id = VaultId::from_bytes([7u8; 16]);
        let envelope = keyring
            .seal_recovery(&recovery_key, vault_id)
            .expect("seal recovery");

        let opened =
            Keyring::open_recovery(&recovery_key, vault_id, &envelope).expect("open recovery");
        assert_eq!(opened.vaults[0].id(), keyring.vaults[0].id());

        assert_eq!(
            Keyring::open_recovery(
                &SecretBytes::from_slice(&[0x56u8; KEY_LEN]),
                vault_id,
                &envelope
            )
            .unwrap_err()
            .code(),
            ErrorCode::AuthenticationFailed
        );
        assert_eq!(
            Keyring::open_recovery(&recovery_key, VaultId::from_bytes([8u8; 16]), &envelope)
                .unwrap_err()
                .code(),
            ErrorCode::AuthenticationFailed
        );
    }

    #[test]
    fn duplicate_vault_ids_are_rejected() {
        let id = VaultId::from_bytes([1u8; 16]);
        let first =
            VaultKey::from_parts(id, SecretBytes::from_slice(&[1u8; KEY_LEN])).expect("vault key");
        let second =
            VaultKey::from_parts(id, SecretBytes::from_slice(&[2u8; KEY_LEN])).expect("vault key");
        assert_eq!(
            Keyring::from_vaults(vec![first, second])
                .unwrap_err()
                .code(),
            ErrorCode::InvalidArgument
        );
    }

    #[test]
    fn too_many_vaults_are_rejected() {
        let mut vaults = Vec::new();
        for index in 0..=MAX_VAULTS {
            vaults.push(
                VaultKey::from_parts(
                    VaultId::from_bytes([index as u8; 16]),
                    SecretBytes::from_slice(&[index as u8; KEY_LEN]),
                )
                .expect("vault key"),
            );
        }
        assert_eq!(
            Keyring::from_vaults(vaults).unwrap_err().code(),
            ErrorCode::LimitExceeded
        );
    }

    #[test]
    fn decode_rejects_bad_magic_version_and_length() {
        let keyring = Keyring::generate().expect("keyring");
        let encoded = keyring.encode();
        let original = encoded.expose_secret();

        let mut bad_magic = original.to_vec();
        bad_magic[0] = b'X';
        assert_eq!(
            Keyring::decode(&bad_magic).unwrap_err().code(),
            ErrorCode::CorruptData
        );

        let mut bad_version = original.to_vec();
        bad_version[4..6].copy_from_slice(&2u16.to_be_bytes());
        assert_eq!(
            Keyring::decode(&bad_version).unwrap_err().code(),
            ErrorCode::UnsupportedVersion
        );

        let mut short = original.to_vec();
        short.pop();
        assert_eq!(
            Keyring::decode(&short).unwrap_err().code(),
            ErrorCode::CorruptData
        );
    }
}
