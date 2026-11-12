//! Vault session lifecycle: creation, unlock, lock, recovery and rewrap.
//!
//! The session is the layer exposed to Android and Firefox. It owns the
//! header and the in-memory keyring; it never stores the master password.
//! Item CRUD and platform persistence adapters are layered on top in later
//! items.

use core::fmt;
use std::collections::BTreeMap;

use zeroize::Zeroizing;

use crate::crdt::ActorId;
use crate::envelope::Envelope;
use crate::error::{CoreError, ErrorCode, Result};
use crate::header::{VaultHeader, NONCE_LEN};
use crate::item::ITEM_SCHEMA_VERSION;
use crate::kdf::{derive_master_key, KdfParams};
use crate::keyring::Keyring;
use crate::keys::{random_array, ItemId, ItemKey, MasterKey, VaultId, KEY_LEN};
use crate::secret::{SecretBytes, SecretString};
use crate::shamir::{combine_2_of_3, split_2_of_3, RecoveryShare};
use crate::sync::legacy::migrate_legacy_item;

const BLOB_MAGIC: [u8; 4] = *b"CKS1";
const BLOB_VERSION: u16 = 1;
const BLOB_HEADER_LEN: usize = 4 + 2 + 16 + 4;
const MAX_BLOB_LEN: usize = 16 * 1024 * 1024;
const MIN_PASSWORD_LEN: usize = 12;
const FREE_ATTEMPTS: u32 = 5;
const BASE_DELAY_SECS: u64 = 30;
const MAX_DELAY_SECS: u64 = 3_600;

/// Portable, non-secret vault metadata. It contains the authenticated header
/// and the recovery envelope; neither can be opened without a password or two
/// recovery shares.
#[derive(Clone, Eq, PartialEq)]
pub struct VaultBlob {
    vault_id: VaultId,
    header: Vec<u8>,
    recovery_envelope: Vec<u8>,
}

impl VaultBlob {
    fn new(vault_id: VaultId, header: Vec<u8>, recovery_envelope: Vec<u8>) -> Result<Self> {
        if header.is_empty() || recovery_envelope.is_empty() {
            return Err(CoreError::new(ErrorCode::InvalidArgument));
        }
        Ok(Self {
            vault_id,
            header,
            recovery_envelope,
        })
    }

    /// Encode the portable metadata.
    #[must_use]
    pub fn encode(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(
            BLOB_HEADER_LEN + self.header.len() + 4 + self.recovery_envelope.len(),
        );
        out.extend_from_slice(&BLOB_MAGIC);
        out.extend_from_slice(&BLOB_VERSION.to_be_bytes());
        out.extend_from_slice(self.vault_id.as_bytes());
        out.extend_from_slice(&(self.header.len() as u32).to_be_bytes());
        out.extend_from_slice(&self.header);
        out.extend_from_slice(&(self.recovery_envelope.len() as u32).to_be_bytes());
        out.extend_from_slice(&self.recovery_envelope);
        out
    }

    /// Decode portable metadata. No secret is exposed by this path.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::CorruptData`] for malformed framing and
    /// [`ErrorCode::LimitExceeded`] for oversized input.
    pub fn decode(bytes: &[u8]) -> Result<Self> {
        if bytes.len() < BLOB_HEADER_LEN {
            return Err(CoreError::new(ErrorCode::CorruptData));
        }
        if bytes.len() > MAX_BLOB_LEN {
            return Err(CoreError::new(ErrorCode::LimitExceeded));
        }
        if bytes[0..4] != BLOB_MAGIC {
            return Err(CoreError::new(ErrorCode::CorruptData));
        }
        if u16::from_be_bytes([bytes[4], bytes[5]]) != BLOB_VERSION {
            return Err(CoreError::new(ErrorCode::UnsupportedVersion));
        }
        let mut vault_bytes = [0u8; 16];
        vault_bytes.copy_from_slice(&bytes[6..22]);
        let vault_id = VaultId::from_bytes(vault_bytes);
        let header_len = u32::from_be_bytes([bytes[22], bytes[23], bytes[24], bytes[25]]) as usize;
        let header_start = BLOB_HEADER_LEN;
        let header_end = header_start
            .checked_add(header_len)
            .ok_or_else(|| CoreError::new(ErrorCode::LimitExceeded))?;
        if header_len == 0 || header_end + 4 > bytes.len() {
            return Err(CoreError::new(ErrorCode::CorruptData));
        }
        let header = bytes[header_start..header_end].to_vec();
        let recovery_len = u32::from_be_bytes([
            bytes[header_end],
            bytes[header_end + 1],
            bytes[header_end + 2],
            bytes[header_end + 3],
        ]) as usize;
        let recovery_start = header_end + 4;
        let recovery_end = recovery_start
            .checked_add(recovery_len)
            .ok_or_else(|| CoreError::new(ErrorCode::LimitExceeded))?;
        if recovery_len == 0 || recovery_end != bytes.len() {
            return Err(CoreError::new(ErrorCode::CorruptData));
        }
        Self::new(
            vault_id,
            header,
            bytes[recovery_start..recovery_end].to_vec(),
        )
    }

    /// The vault id. Non-secret and used as recovery AAD.
    #[must_use]
    pub const fn vault_id(&self) -> VaultId {
        self.vault_id
    }

    /// The authenticated header bytes.
    #[must_use]
    pub fn header(&self) -> &[u8] {
        &self.header
    }

    /// The recovery envelope bytes.
    #[must_use]
    pub fn recovery_envelope(&self) -> &[u8] {
        &self.recovery_envelope
    }
}

impl fmt::Debug for VaultBlob {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("VaultBlob")
            .field("vault_id", &self.vault_id)
            .field("header_len", &self.header.len())
            .field("recovery_len", &self.recovery_envelope.len())
            .finish()
    }
}

struct Unlocked {
    master: MasterKey,
    keyring: Keyring,
}

/// An in-memory vault session. Dropping it zeroizes the master key and keyring.
pub struct VaultSession {
    header: VaultHeader,
    recovery_envelope: Vec<u8>,
    vault_id: VaultId,
    state: Option<Unlocked>,
    items: BTreeMap<ItemId, Vec<u8>>,
    failed_attempts: u32,
    next_attempt_at: u64,
}

/// A newly created or recovered vault plus its one-time recovery shares.
pub struct CreatedVault {
    /// The unlocked session.
    pub session: VaultSession,
    /// Three 2-of-3 recovery shares, shown one at a time by the UI.
    pub shares: Vec<SecretString>,
}

impl fmt::Debug for VaultSession {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("VaultSession")
            .field("vault_id", &self.vault_id)
            .field("locked", &self.state.is_none())
            .field("failed_attempts", &self.failed_attempts)
            .finish()
    }
}

impl VaultSession {
    /// The active vault id, if a vault is present.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::Internal`] if the keyring has no vault.
    pub fn vault_id(&self) -> Result<VaultId> {
        self.state
            .as_ref()
            .and_then(|unlocked| unlocked.keyring.first_vault())
            .map(crate::keys::VaultKey::id)
            .ok_or_else(|| CoreError::new(ErrorCode::Internal))
    }

    /// Create a vault with the pinned default KDF parameters.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::PolicyRejected`] for a password shorter than 12
    /// bytes or one of the common test passwords, and a redacted internal error
    /// if CSPRNG/key generation fails.
    pub fn create(password: &SecretBytes) -> Result<CreatedVault> {
        validate_master_password(password)?;
        let params = KdfParams::generate()?;
        let master = MasterKey::from_secret_bytes(derive_master_key(password, &params)?)?;
        let keyring = Keyring::generate()?;
        let keyring_nonce = random_array::<NONCE_LEN>()?;
        let prefix = VaultHeader::aad_prefix_for(&params);
        let sealed = keyring.seal(&master, &prefix, &keyring_nonce)?;
        let header = VaultHeader::new(params, keyring_nonce, sealed.encode())?;
        let vault_id = keyring
            .first_vault_id()
            .ok_or_else(|| CoreError::new(ErrorCode::Internal))?;
        let (recovery_envelope, shares) = make_recovery(&keyring, vault_id)?;
        let session = Self {
            header,
            recovery_envelope,
            vault_id,
            state: Some(Unlocked { master, keyring }),
            items: BTreeMap::new(),
            failed_attempts: 0,
            next_attempt_at: 0,
        };
        Ok(CreatedVault { session, shares })
    }

    /// Open portable metadata in the locked state without a password.
    ///
    /// This is the handle a UI keeps across failed unlock attempts so the local
    /// backoff state survives them.
    ///
    /// # Errors
    ///
    /// Returns a redacted [`CoreError`] for a malformed header or blob.
    pub fn open(blob: &VaultBlob) -> Result<Self> {
        Self::from_blob(blob)
    }

    /// Unlock a vault from portable metadata.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::AuthenticationFailed`] for a wrong password or
    /// malformed ciphertext, and [`ErrorCode::LimitExceeded`] while a local
    /// backoff is active.
    pub fn unlock(blob: &VaultBlob, password: &SecretBytes, now: u64) -> Result<Self> {
        let mut session = Self::from_blob(blob)?;
        session.try_unlock(password, now)?;
        Ok(session)
    }

    /// Re-open the same vault with a password after [`VaultSession::lock`].
    ///
    /// # Errors
    ///
    /// As [`VaultSession::unlock`].
    pub fn unlock_again(&mut self, password: &SecretBytes, now: u64) -> Result<()> {
        self.try_unlock(password, now)
    }

    /// Copy the master key for an immediate platform biometric wrap.
    ///
    /// The caller must wrap these bytes in a user-auth-bound Keystore key and
    /// clear the buffer immediately. The session never persists this value.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::Locked`] when the session is locked.
    pub fn master_key_for_biometric(&self) -> Result<SecretBytes> {
        let unlocked = self
            .state
            .as_ref()
            .ok_or_else(|| CoreError::new(ErrorCode::Locked))?;
        Ok(SecretBytes::from_slice(
            unlocked.master.secret().expose_secret(),
        ))
    }

    /// Unlock with a master key previously wrapped by a platform biometric key.
    ///
    /// This bypasses the password KDF by design; the platform must only call it
    /// after a successful BiometricPrompt with the auth-bound Keystore key.
    ///
    /// # Errors
    ///
    /// Returns a redacted [`CoreError`] for a malformed blob/key or a keyring
    /// that does not open.
    pub fn unlock_with_master_key(blob: &VaultBlob, master_key: &SecretBytes) -> Result<Self> {
        let mut session = Self::from_blob(blob)?;
        let master =
            MasterKey::from_secret_bytes(SecretBytes::from_slice(master_key.expose_secret()))?;
        let keyring = Keyring::open_encoded(
            &master,
            &session.header.aad_prefix(),
            session.header.keyring_nonce(),
            session.header.keyring_ciphertext(),
        )?;
        session.state = Some(Unlocked { master, keyring });
        Ok(session)
    }

    /// Encrypt and store an item payload under a fresh per-item key.
    ///
    /// The payload is an opaque platform-defined byte string. The item key is
    /// wrapped by the vault key and the payload is sealed with XChaCha20-Poly1305
    /// using the item id and a fixed item payload slot as AAD.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::Locked`] when locked and a redacted error if key
    /// generation, wrapping or sealing fails.
    pub fn save_item(&mut self, item_id: ItemId, plaintext: &[u8]) -> Result<()> {
        let blob = self.encrypt_item(item_id, plaintext)?;
        self.items.insert(item_id, blob);
        Ok(())
    }

    /// Encrypt an item payload without storing it in this session.
    ///
    /// Platforms use this to write the returned ciphertext into their own
    /// encrypted-at-rest store (Room/SQLCipher or IndexedDB).
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::Locked`] when locked and a redacted error if key
    /// generation, wrapping or sealing fails.
    pub fn encrypt_item(&self, item_id: ItemId, plaintext: &[u8]) -> Result<Vec<u8>> {
        let (wrapped, sealed) = {
            let unlocked = self
                .state
                .as_ref()
                .ok_or_else(|| CoreError::new(ErrorCode::Locked))?;
            let vault = unlocked
                .keyring
                .first_vault()
                .ok_or_else(|| CoreError::new(ErrorCode::Internal))?;
            let item_key = ItemKey::generate()?;
            let wrapped = vault.wrap_item_key(&item_key, item_id, ITEM_SCHEMA_VERSION)?;
            let sealed = item_key.seal_field(item_id, ITEM_PAYLOAD_FIELD, 1, false, plaintext)?;
            (wrapped.encode(), sealed.encode())
        };
        let wrapped_len =
            u16::try_from(wrapped.len()).map_err(|_| CoreError::new(ErrorCode::LimitExceeded))?;
        let mut blob = Vec::with_capacity(3 + wrapped.len() + sealed.len());
        blob.push(ITEM_BLOB_VERSION);
        blob.extend_from_slice(&wrapped_len.to_be_bytes());
        blob.extend_from_slice(&wrapped);
        blob.extend_from_slice(&sealed);
        Ok(blob)
    }

    /// Decrypt an item payload.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::Locked`] when locked, [`ErrorCode::NotFound`] for an
    /// unknown item, and a redacted authentication/corruption error otherwise.
    pub fn get_item(&self, item_id: ItemId) -> Result<Vec<u8>> {
        if self.state.is_none() {
            return Err(CoreError::new(ErrorCode::Locked));
        }
        let blob = self
            .items
            .get(&item_id)
            .ok_or_else(|| CoreError::new(ErrorCode::NotFound))?;
        self.decrypt_item(item_id, blob)
    }

    /// Decrypt an item ciphertext produced by [`VaultSession::encrypt_item`].
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::Locked`] when locked and a redacted
    /// authentication/corruption error otherwise.
    pub fn decrypt_item(&self, item_id: ItemId, blob: &[u8]) -> Result<Vec<u8>> {
        let unlocked = self
            .state
            .as_ref()
            .ok_or_else(|| CoreError::new(ErrorCode::Locked))?;
        let (wrapped, sealed) = decode_item_blob(blob)?;
        let vault = unlocked
            .keyring
            .first_vault()
            .ok_or_else(|| CoreError::new(ErrorCode::Internal))?;
        let item_key = vault.unwrap_item_key(&wrapped, item_id, ITEM_SCHEMA_VERSION)?;
        let plaintext = item_key.open_field(&sealed, item_id, ITEM_PAYLOAD_FIELD, 1, false)?;
        Ok(plaintext.expose_secret().to_vec())
    }

    /// Migrate an existing encrypted item payload into canonical sync JSON.
    ///
    /// The item key is unwrapped internally; no caller-supplied key is needed.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::Locked`] when locked, or a decryption/migration
    /// error for corrupt blobs or invalid payloads.
    pub fn migrate_item(
        &self,
        item_id: ItemId,
        blob: &[u8],
        actor: ActorId,
        created_at: u64,
        counter_start: u64,
    ) -> Result<Vec<u8>> {
        let unlocked = self
            .state
            .as_ref()
            .ok_or_else(|| CoreError::new(ErrorCode::Locked))?;
        let (wrapped, sealed) = decode_item_blob(blob)?;
        let vault = unlocked
            .keyring
            .first_vault()
            .ok_or_else(|| CoreError::new(ErrorCode::Internal))?;
        let item_key = vault.unwrap_item_key(&wrapped, item_id, ITEM_SCHEMA_VERSION)?;
        let plaintext = item_key.open_field(&sealed, item_id, ITEM_PAYLOAD_FIELD, 1, false)?;
        migrate_legacy_item(
            plaintext.expose_secret(),
            item_id,
            &item_key,
            actor,
            created_at,
            counter_start,
        )?
        .to_sync_json()
    }

    /// List stored item ids. Requires an unlocked session.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::Locked`] when locked.
    pub fn list_items(&self) -> Result<Vec<ItemId>> {
        if self.state.is_none() {
            return Err(CoreError::new(ErrorCode::Locked));
        }
        Ok(self.items.keys().copied().collect())
    }

    /// Delete an item.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::Locked`] when locked.
    pub fn delete_item(&mut self, item_id: ItemId) -> Result<()> {
        if self.state.is_none() {
            return Err(CoreError::new(ErrorCode::Locked));
        }
        self.items.remove(&item_id);
        Ok(())
    }

    /// Number of stored items. Requires an unlocked session.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::Locked`] when locked.
    pub fn item_count(&self) -> Result<usize> {
        if self.state.is_none() {
            return Err(CoreError::new(ErrorCode::Locked));
        }
        Ok(self.items.len())
    }

    /// Lock the session, clearing the master key and keyring.
    pub fn lock(&mut self) {
        self.state = None;
    }

    /// Whether the vault is locked.
    #[must_use]
    pub const fn is_locked(&self) -> bool {
        self.state.is_none()
    }

    /// Seconds until the next unlock attempt is allowed.
    #[must_use]
    pub const fn retry_after(&self, now: u64) -> u64 {
        self.next_attempt_at.saturating_sub(now)
    }

    /// Number of consecutive failed unlock attempts.
    #[must_use]
    pub const fn failed_attempts(&self) -> u32 {
        self.failed_attempts
    }

    /// Export the portable metadata for persistence.
    #[must_use]
    pub fn blob(&self) -> VaultBlob {
        VaultBlob {
            vault_id: self.vault_id,
            header: self.header.encode(),
            recovery_envelope: self.recovery_envelope.clone(),
        }
    }

    /// Rewrap the same keyring under freshly generated KDF parameters.
    ///
    /// The recovery envelope is unchanged, so previously issued recovery shares
    /// remain valid. This is not a revocation or password-change primitive: a
    /// copied old header still opens the same keyring with the old password.
    /// The API is intentionally not exposed through the platform bindings.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::Locked`] if the session is locked and
    /// [`ErrorCode::AuthenticationFailed`] if `password` does not open the
    /// current header.
    pub fn rewrap(&mut self, password: &SecretBytes) -> Result<()> {
        let mut unlocked = self
            .state
            .take()
            .ok_or_else(|| CoreError::new(ErrorCode::Locked))?;
        let current =
            MasterKey::from_secret_bytes(derive_master_key(password, self.header.kdf_params())?)?;
        Keyring::open_encoded(
            &current,
            &self.header.aad_prefix(),
            self.header.keyring_nonce(),
            self.header.keyring_ciphertext(),
        )?;

        let params = KdfParams::generate()?;
        let master = MasterKey::from_secret_bytes(derive_master_key(password, &params)?)?;
        let nonce = random_array::<NONCE_LEN>()?;
        let prefix = VaultHeader::aad_prefix_for(&params);
        let sealed = unlocked.keyring.seal(&master, &prefix, &nonce)?;
        self.header = VaultHeader::new(params, nonce, sealed.encode())?;
        unlocked.master = master;
        self.state = Some(unlocked);
        Ok(())
    }

    /// Recover the keyring with any two shares and set a new master password.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::AuthenticationFailed`] for wrong/duplicate shares,
    /// [`ErrorCode::PolicyRejected`] for a weak new password, or a redacted
    /// internal error.
    pub fn recover(
        blob: &VaultBlob,
        first: &RecoveryShare,
        second: &RecoveryShare,
        new_password: &SecretBytes,
    ) -> Result<CreatedVault> {
        validate_master_password(new_password)?;
        let recovery_key = combine_2_of_3(first, second)?;
        let recovery_env = Envelope::decode(blob.recovery_envelope())?;
        let keyring = Keyring::open_recovery(&recovery_key, blob.vault_id(), &recovery_env)?;

        let params = KdfParams::generate()?;
        let master = MasterKey::from_secret_bytes(derive_master_key(new_password, &params)?)?;
        let nonce = random_array::<NONCE_LEN>()?;
        let prefix = VaultHeader::aad_prefix_for(&params);
        let sealed = keyring.seal(&master, &prefix, &nonce)?;
        let header = VaultHeader::new(params, nonce, sealed.encode())?;
        let (recovery_envelope, shares) = make_recovery(&keyring, blob.vault_id())?;
        let session = Self {
            header,
            recovery_envelope,
            vault_id: blob.vault_id(),
            state: Some(Unlocked { master, keyring }),
            items: BTreeMap::new(),
            failed_attempts: 0,
            next_attempt_at: 0,
        };
        Ok(CreatedVault { session, shares })
    }

    fn from_blob(blob: &VaultBlob) -> Result<Self> {
        let header = VaultHeader::decode(blob.header())?;
        Ok(Self {
            header,
            recovery_envelope: blob.recovery_envelope().to_vec(),
            vault_id: blob.vault_id(),
            state: None,
            items: BTreeMap::new(),
            failed_attempts: 0,
            next_attempt_at: 0,
        })
    }

    fn try_unlock(&mut self, password: &SecretBytes, now: u64) -> Result<()> {
        if now < self.next_attempt_at {
            return Err(CoreError::new(ErrorCode::LimitExceeded));
        }
        let derived = derive_master_key(password, self.header.kdf_params())?;
        let master = MasterKey::from_secret_bytes(derived)?;
        let opened = Keyring::open_encoded(
            &master,
            &self.header.aad_prefix(),
            self.header.keyring_nonce(),
            self.header.keyring_ciphertext(),
        );
        match opened {
            Ok(keyring) => {
                self.state = Some(Unlocked { master, keyring });
                self.failed_attempts = 0;
                self.next_attempt_at = 0;
                Ok(())
            }
            Err(error) => {
                self.record_failure(now);
                Err(error)
            }
        }
    }

    fn record_failure(&mut self, now: u64) {
        self.failed_attempts = self.failed_attempts.saturating_add(1);
        if self.failed_attempts >= FREE_ATTEMPTS {
            let exponent = u32::min(self.failed_attempts - FREE_ATTEMPTS, 7);
            let delay = BASE_DELAY_SECS
                .saturating_mul(1u64 << exponent)
                .min(MAX_DELAY_SECS);
            self.next_attempt_at = now.saturating_add(delay);
        }
    }
}

/// Validate the minimum master-password gate.
///
/// This is deliberately a small, deterministic gate, not a dictionary strength
/// meter. The UI shows a strength indicator before submission.
///
/// # Errors
///
/// Returns [`ErrorCode::PolicyRejected`] for too-short or common passwords.
pub fn validate_master_password(password: &SecretBytes) -> Result<()> {
    let bytes = password.expose_secret();
    if bytes.len() < MIN_PASSWORD_LEN {
        return Err(CoreError::new(ErrorCode::PolicyRejected));
    }
    const COMMON: [&[u8]; 16] = [
        b"password",
        b"password1234",
        b"correcthorsebatterystaple",
        b"qwertyuiopasdf",
        b"letmein12345",
        b"123456789012",
        b"1234567890123",
        b"qwerty123456",
        b"qwertyuiop123",
        b"admin1234567",
        b"welcome12345",
        b"letmein123456",
        b"iloveyou1234",
        b"monkey123456",
        b"dragon123456",
        b"football1234",
    ];
    if COMMON.iter().any(|candidate| {
        candidate.len() == bytes.len()
            && candidate
                .iter()
                .zip(bytes)
                .all(|(left, right)| left.eq_ignore_ascii_case(right))
    }) {
        return Err(CoreError::new(ErrorCode::PolicyRejected));
    }
    let lower: Vec<u8> = bytes.iter().map(u8::to_ascii_lowercase).collect();
    const SEQUENCES: [&[u8]; 5] = [b"123456", b"abcdef", b"qwerty", b"asdfgh", b"zxcvbn"];
    if SEQUENCES.iter().any(|sequence| {
        lower
            .windows(sequence.len())
            .any(|window| window == *sequence)
    }) {
        return Err(CoreError::new(ErrorCode::PolicyRejected));
    }
    if bytes.iter().all(|byte| *byte == bytes[0]) {
        return Err(CoreError::new(ErrorCode::PolicyRejected));
    }
    if bytes
        .iter()
        .copied()
        .collect::<std::collections::BTreeSet<_>>()
        .len()
        < 5
    {
        return Err(CoreError::new(ErrorCode::PolicyRejected));
    }
    Ok(())
}

fn make_recovery(keyring: &Keyring, vault_id: VaultId) -> Result<(Vec<u8>, Vec<SecretString>)> {
    let recovery_bytes = Zeroizing::new(random_array::<KEY_LEN>()?);
    let recovery_secret = SecretBytes::from_slice(&recovery_bytes[..]);
    let shares = split_2_of_3(&recovery_secret)?;
    let envelope = keyring.seal_recovery(&recovery_secret, vault_id)?;
    let encoded = shares.iter().map(RecoveryShare::encode).collect();
    Ok((envelope.encode(), encoded))
}

const ITEM_BLOB_VERSION: u8 = 1;
const ITEM_PAYLOAD_FIELD: u16 = 0;

fn decode_item_blob(blob: &[u8]) -> Result<(crate::Envelope, crate::Envelope)> {
    if blob.len() < 3 || blob[0] != ITEM_BLOB_VERSION {
        return Err(CoreError::new(ErrorCode::CorruptData));
    }
    let wrapped_len = u16::from_be_bytes([blob[1], blob[2]]) as usize;
    let wrapped_start: usize = 3;
    let wrapped_end = wrapped_start
        .checked_add(wrapped_len)
        .ok_or_else(|| CoreError::new(ErrorCode::LimitExceeded))?;
    if wrapped_len == 0 || wrapped_end >= blob.len() {
        return Err(CoreError::new(ErrorCode::CorruptData));
    }
    let wrapped = crate::Envelope::decode(&blob[wrapped_start..wrapped_end])?;
    let sealed = crate::Envelope::decode(&blob[wrapped_end..])?;
    Ok((wrapped, sealed))
}
