//! Key identifiers, key hierarchy types and HKDF domain separation.
//!
//! The hierarchy is:
//!
//! ```text
//! master key (Argon2id)
//!   └── keyring (wrapped under a keyring subkey)
//!         ├── vault key (random 256-bit)
//!         │     └── item key (random 256-bit, wrapped under a vault subkey)
//!         │           └── field envelopes (XChaCha20-Poly1305)
//!         └── future identity / recovery / WebAuthn keys
//! ```
//!
//! Every derived subkey is 32 bytes and is bound to a domain string and a
//! context. The context is also included in the envelope AAD, so a ciphertext
//! cannot be moved between vaults, items, fields or versions.

use core::fmt;

use getrandom::getrandom;
use hkdf::Hkdf;
use sha2::Sha256;
use zeroize::Zeroizing;

use crate::envelope::{Aad, Envelope};
use crate::error::{CoreError, ErrorCode, Result};
use crate::secret::SecretBytes;

/// Length of every symmetric key in this module, in bytes.
pub const KEY_LEN: usize = 32;

/// HKDF domain for wrapping the keyring with the master key.
pub const DOMAIN_KEYRING: &[u8] = b"cipherkeep/v1/keyring";
/// HKDF domain for wrapping an item key with a vault key.
pub const DOMAIN_ITEM_KEY_WRAP: &[u8] = b"cipherkeep/v1/item-key-wrap";
/// HKDF domain for X25519 sharing wraps.
pub const DOMAIN_SHARING: &[u8] = b"cipherkeep/v1/sharing";
/// HKDF domain for recovery wrapping keys.
pub const DOMAIN_RECOVERY: &[u8] = b"cipherkeep/v1/recovery";
/// HKDF domain for WebAuthn PRF wrapping keys.
pub const DOMAIN_WEBAUTHN: &[u8] = b"cipherkeep/v1/webauthn";
/// HKDF domain for sync-level subkeys.
pub const DOMAIN_SYNC: &[u8] = b"cipherkeep/v1/sync";

pub(crate) fn random_array<const N: usize>() -> Result<[u8; N]> {
    let mut bytes = [0u8; N];
    getrandom(&mut bytes).map_err(|_| CoreError::new(ErrorCode::Internal))?;
    if bytes.iter().all(|byte| *byte == 0) {
        return Err(CoreError::new(ErrorCode::Internal));
    }
    Ok(bytes)
}

fn item_wrap_context(vault_id: VaultId, item_id: ItemId) -> [u8; 32] {
    let mut context = [0u8; 32];
    context[..16].copy_from_slice(vault_id.as_bytes());
    context[16..].copy_from_slice(item_id.as_bytes());
    context
}

/// Opaque 128-bit vault identifier.
#[derive(Clone, Copy, Debug, Eq, Hash, Ord, PartialEq, PartialOrd)]
pub struct VaultId([u8; 16]);

impl VaultId {
    /// Generate a random vault id.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::Internal`] if the OS CSPRNG fails.
    pub fn generate() -> Result<Self> {
        Ok(Self(random_array()?))
    }

    /// Construct an id from its stable bytes.
    #[must_use]
    pub const fn from_bytes(bytes: [u8; 16]) -> Self {
        Self(bytes)
    }

    /// The stable bytes of the id.
    #[must_use]
    pub const fn as_bytes(&self) -> &[u8; 16] {
        &self.0
    }
}

/// Opaque 128-bit item identifier.
#[derive(Clone, Copy, Debug, Eq, Hash, Ord, PartialEq, PartialOrd)]
pub struct ItemId([u8; 16]);

impl ItemId {
    /// Generate a random item id.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::Internal`] if the OS CSPRNG fails.
    pub fn generate() -> Result<Self> {
        Ok(Self(random_array()?))
    }

    /// Construct an id from its stable bytes.
    #[must_use]
    pub const fn from_bytes(bytes: [u8; 16]) -> Self {
        Self(bytes)
    }

    /// The stable bytes of the id.
    #[must_use]
    pub const fn as_bytes(&self) -> &[u8; 16] {
        &self.0
    }
}

/// Opaque 128-bit device identifier used by sync.
#[derive(Clone, Copy, Debug, Eq, Hash, Ord, PartialEq, PartialOrd)]
pub struct DeviceId([u8; 16]);

impl DeviceId {
    /// Generate a random device id.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::Internal`] if the OS CSPRNG fails.
    pub fn generate() -> Result<Self> {
        Ok(Self(random_array()?))
    }

    /// Construct an id from its stable bytes.
    #[must_use]
    pub const fn from_bytes(bytes: [u8; 16]) -> Self {
        Self(bytes)
    }

    /// The stable bytes of the id.
    #[must_use]
    pub const fn as_bytes(&self) -> &[u8; 16] {
        &self.0
    }
}

/// A 256-bit master key produced by Argon2id.
pub struct MasterKey {
    key: SecretBytes,
}

impl MasterKey {
    /// Wrap an existing 32-byte key.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::InvalidArgument`] unless the input is exactly
    /// [`KEY_LEN`] bytes. The caller's buffer is moved and zeroized on drop.
    pub fn from_secret_bytes(key: SecretBytes) -> Result<Self> {
        if key.len() != KEY_LEN {
            return Err(CoreError::new(ErrorCode::InvalidArgument));
        }
        Ok(Self { key })
    }

    pub(crate) fn secret(&self) -> &SecretBytes {
        &self.key
    }
}

impl fmt::Debug for MasterKey {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("MasterKey")
            .field("len", &self.key.len())
            .field("contents", &"<redacted>")
            .finish()
    }
}

/// A random 256-bit vault key with a stable vault id.
pub struct VaultKey {
    id: VaultId,
    key: SecretBytes,
}

impl VaultKey {
    /// Generate a new random vault key and id.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::Internal`] if the OS CSPRNG fails.
    pub fn generate() -> Result<Self> {
        let id = VaultId::generate()?;
        let bytes = Zeroizing::new(random_array::<KEY_LEN>()?);
        Ok(Self {
            id,
            key: SecretBytes::from_slice(&bytes[..]),
        })
    }

    /// Construct a vault key from an existing id and 32-byte key.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::InvalidArgument`] unless the key is exactly
    /// [`KEY_LEN`] bytes.
    pub fn from_parts(id: VaultId, key: SecretBytes) -> Result<Self> {
        if key.len() != KEY_LEN {
            return Err(CoreError::new(ErrorCode::InvalidArgument));
        }
        Ok(Self { id, key })
    }

    /// The vault id.
    #[must_use]
    pub const fn id(&self) -> VaultId {
        self.id
    }

    /// Wrap an item key under this vault key.
    ///
    /// The wrapping key is `HKDF(vault_key, item-key-wrap, vault_id)`, so the
    /// same vault key is never used directly as an AEAD key.
    ///
    /// # Errors
    ///
    /// Returns a redacted [`CoreError`] if derivation or sealing fails.
    pub fn wrap_item_key(
        &self,
        item_key: &ItemKey,
        item_id: ItemId,
        schema: u16,
    ) -> Result<Envelope> {
        let wrapping_key = derive_subkey(
            &self.key,
            DOMAIN_ITEM_KEY_WRAP,
            &item_wrap_context(self.id, item_id),
        )?;
        Envelope::seal(
            &wrapping_key,
            &Aad::item_key(self.id, item_id, schema),
            item_key.secret().expose_secret(),
        )
    }

    /// Unwrap an item key previously wrapped by this vault.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::AuthenticationFailed`] for a wrong vault, wrong
    /// item id, wrong schema or tampered envelope.
    pub fn unwrap_item_key(
        &self,
        envelope: &Envelope,
        item_id: ItemId,
        schema: u16,
    ) -> Result<ItemKey> {
        let wrapping_key = derive_subkey(
            &self.key,
            DOMAIN_ITEM_KEY_WRAP,
            &item_wrap_context(self.id, item_id),
        )?;
        let bytes = envelope.open(&wrapping_key, &Aad::item_key(self.id, item_id, schema))?;
        ItemKey::from_secret_bytes(bytes)
    }

    pub(crate) fn secret(&self) -> &SecretBytes {
        &self.key
    }
}

impl fmt::Debug for VaultKey {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("VaultKey")
            .field("id", &self.id)
            .field("len", &self.key.len())
            .field("contents", &"<redacted>")
            .finish()
    }
}

/// A random 256-bit per-item key.
pub struct ItemKey {
    key: SecretBytes,
}

impl ItemKey {
    /// Generate a new random item key.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::Internal`] if the OS CSPRNG fails.
    pub fn generate() -> Result<Self> {
        let bytes = Zeroizing::new(random_array::<KEY_LEN>()?);
        Ok(Self {
            key: SecretBytes::from_slice(&bytes[..]),
        })
    }

    /// Wrap an existing 32-byte key.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::InvalidArgument`] unless the input is exactly
    /// [`KEY_LEN`] bytes.
    pub fn from_secret_bytes(key: SecretBytes) -> Result<Self> {
        if key.len() != KEY_LEN {
            return Err(CoreError::new(ErrorCode::InvalidArgument));
        }
        Ok(Self { key })
    }

    /// Encrypt a field value under this item key.
    ///
    /// # Errors
    ///
    /// Returns a redacted [`CoreError`] if sealing fails.
    pub fn seal_field(
        &self,
        item_id: ItemId,
        field_id: u16,
        lamport: u64,
        tombstone: bool,
        plaintext: &[u8],
    ) -> Result<Envelope> {
        Envelope::seal(
            &self.key,
            &Aad::field(item_id, field_id, lamport, tombstone),
            plaintext,
        )
    }

    /// Decrypt a field value previously sealed by this item key.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::AuthenticationFailed`] for a wrong item, field,
    /// clock, tombstone flag or tampered envelope.
    pub fn open_field(
        &self,
        envelope: &Envelope,
        item_id: ItemId,
        field_id: u16,
        lamport: u64,
        tombstone: bool,
    ) -> Result<SecretBytes> {
        envelope.open(
            &self.key,
            &Aad::field(item_id, field_id, lamport, tombstone),
        )
    }

    /// Expose the item key bytes for an immediate platform operation.
    ///
    /// This exists for the FFI boundary. Callers must use the bytes
    /// immediately and clear their own buffers; do not retain or log them.
    #[must_use]
    pub fn expose_secret(&self) -> &[u8] {
        self.key.expose_secret()
    }

    pub(crate) fn secret(&self) -> &SecretBytes {
        &self.key
    }
}

impl fmt::Debug for ItemKey {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("ItemKey")
            .field("len", &self.key.len())
            .field("contents", &"<redacted>")
            .finish()
    }
}

/// Derive a 256-bit subkey with HKDF-SHA-256.
///
/// `domain` is the HKDF `info`; `context` is the HKDF salt. Both must be
/// distinct per purpose. The output is zeroized on drop by [`SecretBytes`].
///
/// # Errors
///
/// Returns [`ErrorCode::Internal`] if the underlying HKDF fails.
pub fn derive_subkey(
    input_key: &SecretBytes,
    domain: &[u8],
    context: &[u8],
) -> Result<SecretBytes> {
    let hkdf = Hkdf::<Sha256>::new(Some(context), input_key.expose_secret());
    let mut output = Zeroizing::new([0u8; KEY_LEN]);
    hkdf.expand(domain, &mut output[..])
        .map_err(|_| CoreError::new(ErrorCode::Internal))?;
    Ok(SecretBytes::from_slice(&output[..]))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn vault_key(seed: u8) -> VaultKey {
        VaultKey::from_parts(
            VaultId::from_bytes([seed; 16]),
            SecretBytes::from_slice(&[seed; KEY_LEN]),
        )
        .expect("valid vault key")
    }

    #[test]
    fn ids_round_trip() {
        let id = VaultId::from_bytes([9u8; 16]);
        assert_eq!(id.as_bytes(), &[9u8; 16]);
        let item = ItemId::from_bytes([8u8; 16]);
        assert_eq!(item.as_bytes(), &[8u8; 16]);
        let device = DeviceId::from_bytes([7u8; 16]);
        assert_eq!(device.as_bytes(), &[7u8; 16]);
    }

    #[test]
    fn key_lengths_are_enforced() {
        assert_eq!(
            MasterKey::from_secret_bytes(SecretBytes::from_slice(&[0u8; 31]))
                .unwrap_err()
                .code(),
            ErrorCode::InvalidArgument
        );
        assert_eq!(
            ItemKey::from_secret_bytes(SecretBytes::from_slice(&[0u8; 31]))
                .unwrap_err()
                .code(),
            ErrorCode::InvalidArgument
        );
        assert_eq!(
            VaultKey::from_parts(
                VaultId::from_bytes([1u8; 16]),
                SecretBytes::from_slice(&[0u8; 31])
            )
            .unwrap_err()
            .code(),
            ErrorCode::InvalidArgument
        );
    }

    #[test]
    fn generated_keys_are_unique() {
        use std::collections::HashSet;

        let mut seen = HashSet::new();
        for _ in 0..10_000 {
            let key = ItemKey::generate().expect("key generation");
            assert!(seen.insert(key.secret().expose_secret().to_vec()));
        }
    }

    #[test]
    fn vault_wraps_and_unwraps_item_key() {
        let vault = vault_key(1);
        let item = ItemKey::generate().expect("item key");
        let item_id = ItemId::from_bytes([2u8; 16]);
        let envelope = vault.wrap_item_key(&item, item_id, 1).expect("wrap");
        let unwrapped = vault
            .unwrap_item_key(&envelope, item_id, 1)
            .expect("unwrap");
        assert!(unwrapped.secret() == item.secret());
    }

    #[test]
    fn item_key_wrap_is_bound_to_vault_item_and_schema() {
        let vault = vault_key(1);
        let other_vault = vault_key(3);
        let item = ItemKey::generate().expect("item key");
        let item_id = ItemId::from_bytes([2u8; 16]);
        let envelope = vault.wrap_item_key(&item, item_id, 1).expect("wrap");

        for result in [
            other_vault.unwrap_item_key(&envelope, item_id, 1),
            vault.unwrap_item_key(&envelope, ItemId::from_bytes([4u8; 16]), 1),
            vault.unwrap_item_key(&envelope, item_id, 2),
        ] {
            assert_eq!(result.unwrap_err().code(), ErrorCode::AuthenticationFailed);
        }
    }

    #[test]
    fn field_envelopes_are_bound_to_item_field_clock_and_tombstone() {
        let item = ItemKey::generate().expect("item key");
        let item_id = ItemId::from_bytes([2u8; 16]);
        let envelope = item
            .seal_field(item_id, 10, 7, false, b"secret value")
            .expect("seal");

        let plaintext = item
            .open_field(&envelope, item_id, 10, 7, false)
            .expect("open");
        assert_eq!(plaintext.expose_secret(), b"secret value");

        for result in [
            item.open_field(&envelope, ItemId::from_bytes([3u8; 16]), 10, 7, false),
            item.open_field(&envelope, item_id, 11, 7, false),
            item.open_field(&envelope, item_id, 10, 8, false),
            item.open_field(&envelope, item_id, 10, 7, true),
        ] {
            assert_eq!(result.unwrap_err().code(), ErrorCode::AuthenticationFailed);
        }
    }

    #[test]
    fn hkdf_domains_and_contexts_are_separated() {
        let key = SecretBytes::from_slice(&[0x42u8; KEY_LEN]);
        let first = derive_subkey(&key, DOMAIN_KEYRING, b"context-a").expect("derive");
        let second = derive_subkey(&key, DOMAIN_ITEM_KEY_WRAP, b"context-a").expect("derive");
        let third = derive_subkey(&key, DOMAIN_KEYRING, b"context-b").expect("derive");
        assert!(first != second);
        assert!(first != third);
        assert!(second != third);
    }

    #[test]
    fn key_debug_is_redacted() {
        let vault = VaultKey::from_parts(
            VaultId::from_bytes([1u8; 16]),
            SecretBytes::from_slice(&[0xAB; KEY_LEN]),
        )
        .expect("vault key");
        let item = ItemKey::from_secret_bytes(SecretBytes::from_slice(&[0xCD; KEY_LEN]))
            .expect("item key");
        let master = MasterKey::from_secret_bytes(SecretBytes::from_slice(&[0xEF; KEY_LEN]))
            .expect("master key");
        for rendered in [
            format!("{vault:?}"),
            format!("{item:?}"),
            format!("{master:?}"),
        ] {
            assert!(!rendered.contains("171"));
            assert!(!rendered.contains("205"));
            assert!(!rendered.contains("239"));
            assert!(rendered.contains("<redacted>"));
        }
    }
}
