//! UniFFI surface for the CipherKeep Android application.
//!
//! This is the primitive binding layer. It exposes version, key generation,
//! KDF, envelope, item-key wrapping and recovery-share operations. The
//! stateful `VaultSession` API is implemented alongside the session and storage
//! contracts exist.
//!
//! Secrets cross the boundary as `Vec<u8>`/`String` only for immediate use.
//! The Rust side copies them into zeroizing types and wipes the incoming
//! buffers; the Kotlin caller remains responsible for clearing its own arrays.

#![forbid(unsafe_code)]

use getrandom::getrandom;
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};
use zeroize::{Zeroize, Zeroizing};

use cipherkeep_core::{
    combine_2_of_3, derive_master_key, export_backup,
    generate_passphrase as core_generate_passphrase, generate_password as core_generate_password,
    import_backup, migrate_legacy_item as core_migrate_legacy_item, parse_otpauth_uri,
    split_2_of_3, ActorId, Envelope, ErrorCode, ItemId, ItemKey, KdfParams, PassphrasePolicy,
    PasswordPolicy, RecoveryShare, SearchDocument, SearchIndex, SecretBytes, VaultBlob, VaultId,
    VaultKey, VaultSession, DEFAULT_ITERATIONS, DEFAULT_MEMORY_KIB, DEFAULT_PARALLELISM,
};

uniffi::setup_scaffolding!();

/// A flat, payload-free FFI error.
///
/// The variant names mirror [`ErrorCode`] one-to-one. Kotlin maps them to the
/// stable numeric codes in `ErrorMapping.kt`; the Rust table test keeps both
/// sides honest.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum FfiError {
    /// Caller input was malformed or outside an allowed range.
    #[error("invalid input")]
    InvalidArgument,
    /// A format or schema version is newer than this build supports.
    #[error("unsupported format version")]
    UnsupportedVersion,
    /// The vault could not be opened. Deliberately generic.
    #[error("vault could not be opened")]
    AuthenticationFailed,
    /// Data is malformed or fails an integrity check.
    #[error("data is malformed")]
    CorruptData,
    /// The operation requires an unlocked vault.
    #[error("vault is locked")]
    Locked,
    /// The requested record does not exist.
    #[error("item not found")]
    NotFound,
    /// A concurrent change requires resolution.
    #[error("concurrent change requires resolution")]
    Conflict,
    /// A resource limit was exceeded.
    #[error("resource limit exceeded")]
    LimitExceeded,
    /// Platform storage failed.
    #[error("storage operation failed")]
    Storage,
    /// Internal invariant failure.
    #[error("internal error")]
    Internal,
    /// Parameters fail the security policy.
    #[error("security policy rejected the parameters")]
    PolicyRejected,
}

impl FfiError {
    /// Stable numeric code, matching [`ErrorCode`].
    #[must_use]
    pub const fn numeric_code(&self) -> u16 {
        match self {
            Self::InvalidArgument => 1,
            Self::UnsupportedVersion => 2,
            Self::AuthenticationFailed => 3,
            Self::CorruptData => 4,
            Self::Locked => 5,
            Self::NotFound => 6,
            Self::Conflict => 7,
            Self::LimitExceeded => 8,
            Self::Storage => 9,
            Self::Internal => 10,
            Self::PolicyRejected => 11,
        }
    }
}

impl From<cipherkeep_core::CoreError> for FfiError {
    fn from(error: cipherkeep_core::CoreError) -> Self {
        match error.code() {
            ErrorCode::InvalidArgument => Self::InvalidArgument,
            ErrorCode::UnsupportedVersion => Self::UnsupportedVersion,
            ErrorCode::AuthenticationFailed => Self::AuthenticationFailed,
            ErrorCode::CorruptData => Self::CorruptData,
            ErrorCode::Locked => Self::Locked,
            ErrorCode::NotFound => Self::NotFound,
            ErrorCode::Conflict => Self::Conflict,
            ErrorCode::LimitExceeded => Self::LimitExceeded,
            ErrorCode::Storage => Self::Storage,
            ErrorCode::Internal => Self::Internal,
            ErrorCode::PolicyRejected => Self::PolicyRejected,
            _ => Self::Internal,
        }
    }
}

/// Password generator options exposed to Kotlin.
#[derive(Clone, Copy, Debug, uniffi::Record)]
pub struct FfiPasswordPolicy {
    /// Total character count.
    pub length: u32,
    /// Include lowercase letters.
    pub lowercase: bool,
    /// Include uppercase letters.
    pub uppercase: bool,
    /// Include digits.
    pub digits: bool,
    /// Include symbols.
    pub symbols: bool,
    /// Remove I, l, 1, O, 0 and o.
    pub exclude_ambiguous: bool,
}

/// Passphrase generator options exposed to Kotlin.
#[derive(Clone, Debug, uniffi::Record)]
pub struct FfiPassphrasePolicy {
    /// Number of words.
    pub words: u32,
    /// Single-character separator.
    pub separator: String,
}

/// A generated secret and its honest entropy estimate.
#[derive(Clone, Debug, uniffi::Record)]
pub struct FfiGeneratedSecret {
    /// Generated value. Kotlin must copy it only for immediate use.
    pub value: String,
    /// Entropy estimate in bits.
    pub entropy_bits: f64,
    /// Plain-language strength label.
    pub strength: String,
}

/// Generate a password with the shared core generator.
///
/// # Errors
///
/// Returns [`FfiError::PolicyRejected`] for an impossible policy or
/// [`FfiError::Internal`] if the OS CSPRNG fails.
#[uniffi::export]
pub fn generate_password(policy: FfiPasswordPolicy) -> Result<FfiGeneratedSecret, FfiError> {
    let length = usize::try_from(policy.length).map_err(|_| FfiError::InvalidArgument)?;
    let generated = core_generate_password(&PasswordPolicy {
        length,
        lowercase: policy.lowercase,
        uppercase: policy.uppercase,
        digits: policy.digits,
        symbols: policy.symbols,
        exclude_ambiguous: policy.exclude_ambiguous,
    })?;
    Ok(FfiGeneratedSecret {
        value: generated.expose_secret().to_string(),
        entropy_bits: generated.entropy_bits(),
        strength: generated.strength().as_str().to_string(),
    })
}

/// Generate an EFF-style passphrase with the shared core generator.
///
/// # Errors
///
/// Returns [`FfiError::PolicyRejected`] for an invalid policy or
/// [`FfiError::Internal`] if the OS CSPRNG fails.
#[uniffi::export]
pub fn generate_passphrase(policy: FfiPassphrasePolicy) -> Result<FfiGeneratedSecret, FfiError> {
    let words = usize::try_from(policy.words).map_err(|_| FfiError::InvalidArgument)?;
    let mut characters = policy.separator.chars();
    let separator = characters.next().ok_or(FfiError::InvalidArgument)?;
    if characters.next().is_some() {
        return Err(FfiError::InvalidArgument);
    }
    let generated = core_generate_passphrase(&PassphrasePolicy { words, separator })?;
    Ok(FfiGeneratedSecret {
        value: generated.expose_secret().to_string(),
        entropy_bits: generated.entropy_bits(),
        strength: generated.strength().as_str().to_string(),
    })
}

/// Metadata supplied to the search index. Never include secrets.
#[derive(Clone, Debug, uniffi::Record)]
pub struct FfiSearchDocument {
    /// Item id.
    pub id: Vec<u8>,
    /// Title.
    pub title: String,
    /// Username.
    pub username: String,
    /// URL.
    pub url: String,
    /// Folder name.
    pub folder: String,
    /// Notes.
    pub notes: String,
}

/// A redacted search result.
#[derive(Clone, Debug, uniffi::Record)]
pub struct FfiSearchHit {
    /// Item id.
    pub id: Vec<u8>,
    /// Relative score.
    pub score: u16,
    /// Redacted metadata snippet.
    pub snippet: String,
}

/// In-memory metadata index. Clear it on lock.
#[derive(uniffi::Object)]
pub struct FfiSearchIndex {
    inner: Mutex<SearchIndex>,
}

#[uniffi::export]
impl FfiSearchIndex {
    /// Create an empty index.
    #[uniffi::constructor]
    #[must_use]
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            inner: Mutex::new(SearchIndex::new()),
        })
    }

    /// Insert or replace a metadata document.
    ///
    /// # Errors
    ///
    /// Returns a redacted error for an invalid id or empty title.
    pub fn insert(&self, document: FfiSearchDocument) -> Result<(), FfiError> {
        let id = ItemId::from_bytes(id_from_slice(&document.id)?);
        let document = SearchDocument::new(
            id,
            document.title,
            document.username,
            document.url,
            document.folder,
            document.notes,
        );
        self.lock().insert(document).map_err(FfiError::from)
    }

    /// Remove a document.
    pub fn remove(&self, id: Vec<u8>) -> bool {
        id_from_slice(&id)
            .map(ItemId::from_bytes)
            .map(|id| self.lock().remove(id))
            .unwrap_or(false)
    }

    /// Clear and zeroize the index.
    pub fn clear(&self) {
        self.lock().clear();
    }

    /// Search metadata and return redacted hits.
    #[must_use]
    pub fn search(&self, query: String, limit: u32) -> Vec<FfiSearchHit> {
        self.lock()
            .search(&query, usize::try_from(limit).unwrap_or(0))
            .into_iter()
            .map(|hit| FfiSearchHit {
                id: hit.id().as_bytes().to_vec(),
                score: hit.score(),
                snippet: hit.snippet().to_string(),
            })
            .collect()
    }

    /// Number of indexed documents.
    #[must_use]
    #[allow(clippy::len_without_is_empty)]
    pub fn len(&self) -> u32 {
        u32::try_from(self.lock().len()).unwrap_or(u32::MAX)
    }
}

impl FfiSearchIndex {
    fn lock(&self) -> std::sync::MutexGuard<'_, SearchIndex> {
        self.inner
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
    }
}

/// Encrypt portable item JSON with a separate export password.
///
/// # Errors
///
/// Returns a redacted error for a weak password, oversized input or crypto
/// failure.
#[uniffi::export]
pub fn export_portable(items_json: Vec<u8>, password: String) -> Result<Vec<u8>, FfiError> {
    export_backup(&items_json, &password).map_err(FfiError::from)
}

/// Decrypt a portable export and return item JSON.
///
/// # Errors
///
/// Returns a redacted error for a wrong password, tamper or malformed header.
#[uniffi::export]
pub fn import_portable(bytes: Vec<u8>, password: String) -> Result<Vec<u8>, FfiError> {
    import_backup(&bytes, &password)
        .map(|plaintext| plaintext.expose_secret().to_vec())
        .map_err(FfiError::from)
}

/// TOTP code and the seconds until the next step.
#[derive(Clone, Debug, uniffi::Record)]
pub struct FfiTotpCode {
    /// Current code.
    pub code: String,
    /// Seconds until rollover.
    pub seconds_remaining: u64,
}

/// Generate a TOTP code from an otpauth URI.
///
/// # Errors
///
/// Returns [`FfiError::InvalidArgument`] for an invalid URI or timestamp.
#[uniffi::export]
pub fn totp_code(uri: String, unix_secs: u64) -> Result<FfiTotpCode, FfiError> {
    let entry = parse_otpauth_uri(&uri)?;
    let code = entry.totp().code_at(unix_secs)?;
    Ok(FfiTotpCode {
        code: code.expose_secret().to_string(),
        seconds_remaining: entry.totp().seconds_remaining(unix_secs),
    })
}

/// Return the shared core version.
#[must_use]
pub fn core_version() -> String {
    cipherkeep_core::VERSION.to_string()
}

/// Generate a random 256-bit key.
///
/// # Errors
///
/// Returns [`FfiError::Internal`] if the OS CSPRNG fails.
pub fn generate_key() -> Result<Vec<u8>, FfiError> {
    let mut key = Zeroizing::new([0u8; 32]);
    getrandom(&mut key[..]).map_err(|_| FfiError::Internal)?;
    if key.iter().all(|byte| *byte == 0) {
        return Err(FfiError::Internal);
    }
    Ok(key.to_vec())
}

/// Derive the master key with the pinned default Argon2id parameters.
///
/// The 16-byte salt must come from [`generate_key`]-style CSPRNG output or a
/// stored vault header. This function exists for binding tests and vector
/// parity; production session code uses the core directly.
///
/// # Errors
///
/// Returns a redacted [`FfiError`] for a bad salt length or derivation failure.
pub fn derive_master_key_for_test(password: Vec<u8>, salt: Vec<u8>) -> Result<Vec<u8>, FfiError> {
    let mut password = password;
    let mut salt = salt;
    if salt.len() != 16 {
        password.zeroize();
        salt.zeroize();
        return Err(FfiError::InvalidArgument);
    }
    let mut salt_array = [0u8; 16];
    salt_array.copy_from_slice(&salt);
    let params = KdfParams::new(
        DEFAULT_MEMORY_KIB,
        DEFAULT_ITERATIONS,
        DEFAULT_PARALLELISM,
        salt_array,
    );
    let secret = SecretBytes::from_slice(&password);
    password.zeroize();
    salt.zeroize();
    let key = derive_master_key(&secret, &params)?;
    Ok(key.expose_secret().to_vec())
}

/// Seal a field with an item key.
///
/// # Errors
///
/// Returns a redacted [`FfiError`] for malformed ids/keys or sealing failure.
pub fn seal_field(
    item_key: Vec<u8>,
    item_id: Vec<u8>,
    field_id: u16,
    lamport: u64,
    tombstone: bool,
    plaintext: Vec<u8>,
) -> Result<Vec<u8>, FfiError> {
    let mut item_key = item_key;
    let mut item_id = item_id;
    let mut plaintext = plaintext;
    let id = ItemId::from_bytes(id_from_slice(&item_id)?);
    let key = ItemKey::from_secret_bytes(SecretBytes::from_slice(&item_key))?;
    item_key.zeroize();
    item_id.zeroize();
    let result = key.seal_field(id, field_id, lamport, tombstone, &plaintext);
    plaintext.zeroize();
    Ok(result?.encode())
}

/// Open a field with an item key.
///
/// # Errors
///
/// Returns [`FfiError::AuthenticationFailed`] for a wrong key, wrong context or
/// tampered envelope.
pub fn open_field(
    item_key: Vec<u8>,
    item_id: Vec<u8>,
    field_id: u16,
    lamport: u64,
    tombstone: bool,
    envelope: Vec<u8>,
) -> Result<Vec<u8>, FfiError> {
    let mut item_key = item_key;
    let mut item_id = item_id;
    let id = ItemId::from_bytes(id_from_slice(&item_id)?);
    let key = ItemKey::from_secret_bytes(SecretBytes::from_slice(&item_key))?;
    item_key.zeroize();
    item_id.zeroize();
    let envelope = Envelope::decode(&envelope)?;
    let plaintext = key.open_field(&envelope, id, field_id, lamport, tombstone)?;
    Ok(plaintext.expose_secret().to_vec())
}

/// Migrate a legacy plaintext JSON item into canonical encrypted sync JSON.
///
/// # Errors
///
/// Returns a redacted error for malformed ids/keys/JSON or migration failure.
pub fn migrate_legacy_item(
    json: Vec<u8>,
    item_id: Vec<u8>,
    item_key: Vec<u8>,
    actor: Vec<u8>,
    created_at: u64,
    counter_start: u64,
) -> Result<Vec<u8>, FfiError> {
    let mut json = json;
    let mut item_id = item_id;
    let mut item_key = item_key;
    let mut actor = actor;
    let id = ItemId::from_bytes(id_from_slice(&item_id)?);
    let key = ItemKey::from_secret_bytes(SecretBytes::from_slice(&item_key))?;
    let mut actor: [u8; 16] = actor
        .as_slice()
        .try_into()
        .map_err(|_| FfiError::InvalidArgument)?;
    let result = core_migrate_legacy_item(
        &json,
        id,
        &key,
        ActorId::from_bytes(actor),
        created_at,
        counter_start,
    )?
    .to_sync_json()?;
    json.zeroize();
    item_id.zeroize();
    item_key.zeroize();
    actor.zeroize();
    Ok(result)
}

/// Wrap an item key under a vault key.
///
/// # Errors
///
/// Returns a redacted [`FfiError`] for malformed ids/keys or wrapping failure.
pub fn wrap_item_key(
    vault_key: Vec<u8>,
    item_key: Vec<u8>,
    vault_id: Vec<u8>,
    item_id: Vec<u8>,
    schema: u16,
) -> Result<Vec<u8>, FfiError> {
    let mut vault_key = vault_key;
    let mut item_key = item_key;
    let mut vault_id = vault_id;
    let vault = VaultKey::from_parts(
        VaultId::from_bytes(id_from_slice(&vault_id)?),
        SecretBytes::from_slice(&vault_key),
    )?;
    let item = ItemKey::from_secret_bytes(SecretBytes::from_slice(&item_key))?;
    vault_key.zeroize();
    item_key.zeroize();
    vault_id.zeroize();
    let item_id = ItemId::from_bytes(id_from_slice(&item_id)?);
    let envelope = vault.wrap_item_key(&item, item_id, schema)?;
    Ok(envelope.encode())
}

/// Unwrap an item key with a vault key.
///
/// # Errors
///
/// Returns [`FfiError::AuthenticationFailed`] for wrong material/context or a
/// tampered envelope.
pub fn unwrap_item_key(
    vault_key: Vec<u8>,
    vault_id: Vec<u8>,
    item_id: Vec<u8>,
    schema: u16,
    envelope: Vec<u8>,
) -> Result<Vec<u8>, FfiError> {
    let mut vault_key = vault_key;
    let mut vault_id = vault_id;
    let vault = VaultKey::from_parts(
        VaultId::from_bytes(id_from_slice(&vault_id)?),
        SecretBytes::from_slice(&vault_key),
    )?;
    vault_key.zeroize();
    vault_id.zeroize();
    let item_id = ItemId::from_bytes(id_from_slice(&item_id)?);
    let envelope = Envelope::decode(&envelope)?;
    let item = vault.unwrap_item_key(&envelope, item_id, schema)?;
    Ok(item.expose_secret().to_vec())
}

/// Split a 32-byte recovery key into three 2-of-3 encoded shares.
///
/// # Errors
///
/// Returns a redacted [`FfiError`] for a wrong secret length or CSPRNG failure.
pub fn recovery_split(secret: Vec<u8>) -> Result<Vec<String>, FfiError> {
    let mut secret = secret;
    let result = split_2_of_3(&SecretBytes::from_slice(&secret));
    secret.zeroize();
    let shares = result?;
    Ok(shares
        .iter()
        .map(|share| share.encode().expose_secret().to_string())
        .collect())
}

/// Combine two encoded recovery shares into the 32-byte recovery key.
///
/// # Errors
///
/// Returns a redacted [`FfiError`] for malformed, corrupted or duplicate shares.
pub fn recovery_combine(first: String, second: String) -> Result<Vec<u8>, FfiError> {
    let first = RecoveryShare::decode(&first)?;
    let second = RecoveryShare::decode(&second)?;
    let secret = combine_2_of_3(&first, &second)?;
    Ok(secret.expose_secret().to_vec())
}

fn id_from_slice(bytes: &[u8]) -> Result<[u8; 16], FfiError> {
    bytes.try_into().map_err(|_| FfiError::InvalidArgument)
}

/// A vault session handle for Kotlin.
///
/// The object owns the session and any one-time recovery shares returned by
/// `create`/`recover`. Callers persist `blob()` and clear password/recovery
/// arrays after use.
#[derive(uniffi::Object)]
pub struct FfiVaultSession {
    state: Mutex<SessionState>,
}

struct SessionState {
    session: VaultSession,
    shares: Vec<String>,
}

#[uniffi::export]
impl FfiVaultSession {
    /// Create a new vault and return the session with its recovery shares.
    ///
    /// # Errors
    ///
    /// Returns a redacted [`FfiError`] for a weak password or key-generation
    /// failure.
    #[uniffi::constructor]
    pub fn create(password: Vec<u8>) -> Result<Arc<Self>, FfiError> {
        let mut password = password;
        let secret = SecretBytes::from_slice(&password);
        password.zeroize();
        let created = VaultSession::create(&secret)?;
        let shares = created
            .shares
            .iter()
            .map(|share| share.expose_secret().to_string())
            .collect();
        Ok(Arc::new(Self {
            state: Mutex::new(SessionState {
                session: created.session,
                shares,
            }),
        }))
    }

    /// Open a persisted vault in the locked state.
    ///
    /// This keeps local failed-attempt/backoff state across unlock calls.
    ///
    /// # Errors
    ///
    /// Returns a redacted [`FfiError`] for a malformed blob.
    #[uniffi::constructor]
    pub fn open(blob: Vec<u8>) -> Result<Arc<Self>, FfiError> {
        let vault = VaultBlob::decode(&blob)?;
        let session = VaultSession::open(&vault)?;
        Ok(Arc::new(Self {
            state: Mutex::new(SessionState {
                session,
                shares: Vec::new(),
            }),
        }))
    }

    /// Unlock a persisted vault blob.
    ///
    /// # Errors
    ///
    /// Returns a redacted [`FfiError`] for a wrong password, corrupt blob or
    /// active local backoff.
    #[uniffi::constructor]
    pub fn unlock(blob: Vec<u8>, password: Vec<u8>) -> Result<Arc<Self>, FfiError> {
        let mut password = password;
        let secret = SecretBytes::from_slice(&password);
        password.zeroize();
        let vault = VaultBlob::decode(&blob)?;
        let session = VaultSession::unlock(&vault, &secret, now_secs())?;
        Ok(Arc::new(Self {
            state: Mutex::new(SessionState {
                session,
                shares: Vec::new(),
            }),
        }))
    }

    /// Recover with any two shares and set a new master password.
    ///
    /// # Errors
    ///
    /// Returns a redacted [`FfiError`] for bad shares, a weak password or a
    /// malformed blob.
    #[uniffi::constructor]
    pub fn recover(
        blob: Vec<u8>,
        first: String,
        second: String,
        new_password: Vec<u8>,
    ) -> Result<Arc<Self>, FfiError> {
        let mut new_password = new_password;
        let secret = SecretBytes::from_slice(&new_password);
        new_password.zeroize();
        let vault = VaultBlob::decode(&blob)?;
        let first_share = RecoveryShare::decode(&first)?;
        let second_share = RecoveryShare::decode(&second)?;
        let created = VaultSession::recover(&vault, &first_share, &second_share, &secret)?;
        let shares = created
            .shares
            .iter()
            .map(|share| share.expose_secret().to_string())
            .collect();
        Ok(Arc::new(Self {
            state: Mutex::new(SessionState {
                session: created.session,
                shares,
            }),
        }))
    }

    /// Persist this vault's header and recovery envelope.
    #[must_use]
    pub fn blob(&self) -> Vec<u8> {
        self.with_state(|state| state.session.blob().encode())
    }

    /// One-time recovery shares, newest first. Empty after a normal unlock.
    #[must_use]
    pub fn recovery_shares(&self) -> Vec<String> {
        self.with_state_mut(|state| std::mem::take(&mut state.shares))
    }

    /// Lock and drop the master key/keyring.
    pub fn lock(&self) {
        self.with_state_mut(|state| {
            state.session.lock();
            for share in &mut state.shares {
                share.zeroize();
            }
            state.shares.clear();
        });
    }

    /// Whether the session is locked.
    #[must_use]
    pub fn is_locked(&self) -> bool {
        self.with_state(|state| state.session.is_locked())
    }

    /// Consecutive failed unlock attempts.
    #[must_use]
    pub fn failed_attempts(&self) -> u32 {
        self.with_state(|state| state.session.failed_attempts())
    }

    /// Seconds until another unlock is allowed.
    #[must_use]
    pub fn retry_after(&self) -> u64 {
        self.with_state(|state| state.session.retry_after(now_secs()))
    }

    /// Unlock this handle again after [`FfiVaultSession::lock`].
    ///
    /// # Errors
    ///
    /// As [`FfiVaultSession::unlock`].
    pub fn unlock_again(&self, password: Vec<u8>) -> Result<(), FfiError> {
        let mut password = password;
        let secret = SecretBytes::from_slice(&password);
        password.zeroize();
        self.with_state_mut(|state| {
            state
                .session
                .unlock_again(&secret, now_secs())
                .map_err(FfiError::from)
        })
    }

    /// Encrypt and store an opaque item payload.
    ///
    /// # Errors
    ///
    /// Returns a redacted [`FfiError`] for a malformed id, a locked session or
    /// cryptographic failure.
    pub fn save_item(&self, item_id: Vec<u8>, plaintext: Vec<u8>) -> Result<(), FfiError> {
        let id = ItemId::from_bytes(id_from_slice(&item_id)?);
        self.with_state_mut(|state| {
            state
                .session
                .save_item(id, &plaintext)
                .map_err(FfiError::from)
        })
    }

    /// Encrypt an item payload for platform storage without keeping it in the session map.
    ///
    /// # Errors
    ///
    /// Returns a redacted [`FfiError`] for a malformed id, locked session or
    /// cryptographic failure.
    pub fn encrypt_item(&self, item_id: Vec<u8>, plaintext: Vec<u8>) -> Result<Vec<u8>, FfiError> {
        let id = ItemId::from_bytes(id_from_slice(&item_id)?);
        self.with_state(|state| {
            state
                .session
                .encrypt_item(id, &plaintext)
                .map_err(FfiError::from)
        })
    }

    /// Decrypt an item payload previously returned by `encryptItem`.
    ///
    /// # Errors
    ///
    /// Returns a redacted [`FfiError`] for a malformed id, locked session or
    /// authentication failure.
    pub fn decrypt_item(&self, item_id: Vec<u8>, ciphertext: Vec<u8>) -> Result<Vec<u8>, FfiError> {
        let id = ItemId::from_bytes(id_from_slice(&item_id)?);
        self.with_state(|state| {
            state
                .session
                .decrypt_item(id, &ciphertext)
                .map_err(FfiError::from)
        })
    }

    /// Export a v2 sync record with wrapped item key.
    pub fn export_sync_record(
        &self,
        item_id: Vec<u8>,
        ciphertext: Vec<u8>,
        actor: Vec<u8>,
        created_at: u64,
        counter_start: u64,
    ) -> Result<Vec<u8>, FfiError> {
        let id = ItemId::from_bytes(id_from_slice(&item_id)?);
        let actor: [u8; 16] = actor
            .as_slice()
            .try_into()
            .map_err(|_| FfiError::InvalidArgument)?;
        self.with_state(|state| {
            state
                .session
                .export_sync_record(id, &ciphertext, ActorId::from_bytes(actor), created_at, counter_start)
                .map_err(FfiError::from)
        })
    }

    /// Apply a v2 sync record to a legacy JSON payload.
    pub fn apply_sync_record(&self, sync_json: Vec<u8>) -> Result<Vec<u8>, FfiError> {
        self.with_state(|state| {
            state
                .session
                .apply_sync_record(&sync_json)
                .map_err(FfiError::from)
        })
    }

    /// Migrate an encrypted legacy item into canonical sync JSON.
    pub fn migrate_item(
        &self,
        item_id: Vec<u8>,
        ciphertext: Vec<u8>,
        actor: Vec<u8>,
        created_at: u64,
        counter_start: u64,
    ) -> Result<Vec<u8>, FfiError> {
        let id = ItemId::from_bytes(id_from_slice(&item_id)?);
        let actor: [u8; 16] = actor
            .as_slice()
            .try_into()
            .map_err(|_| FfiError::InvalidArgument)?;
        self.with_state(|state| {
            state
                .session
                .migrate_item(
                    id,
                    &ciphertext,
                    ActorId::from_bytes(actor),
                    created_at,
                    counter_start,
                )
                .map_err(FfiError::from)
        })
    }

    /// Decrypt an item payload.
    ///
    /// # Errors
    ///
    /// Returns a redacted [`FfiError`] for a missing item, locked session or
    /// authentication failure.
    pub fn get_item(&self, item_id: Vec<u8>) -> Result<Vec<u8>, FfiError> {
        let id = ItemId::from_bytes(id_from_slice(&item_id)?);
        self.with_state(|state| state.session.get_item(id).map_err(FfiError::from))
    }

    /// List stored item ids.
    ///
    /// # Errors
    ///
    /// Returns a redacted [`FfiError`] when the session is locked.
    pub fn list_items(&self) -> Result<Vec<Vec<u8>>, FfiError> {
        self.with_state(|state| {
            state
                .session
                .list_items()
                .map(|ids| ids.iter().map(|id| id.as_bytes().to_vec()).collect())
                .map_err(FfiError::from)
        })
    }

    /// Delete an item.
    ///
    /// # Errors
    ///
    /// Returns a redacted [`FfiError`] when the session is locked.
    pub fn delete_item(&self, item_id: Vec<u8>) -> Result<(), FfiError> {
        let id = ItemId::from_bytes(id_from_slice(&item_id)?);
        self.with_state_mut(|state| state.session.delete_item(id).map_err(FfiError::from))
    }

    /// Number of stored items.
    ///
    /// # Errors
    ///
    /// Returns a redacted [`FfiError`] when the session is locked.
    pub fn item_count(&self) -> Result<u64, FfiError> {
        self.with_state(|state| {
            state
                .session
                .item_count()
                .map(|count| count as u64)
                .map_err(FfiError::from)
        })
    }

    /// Copy the master key for an immediate biometric Keystore wrap.
    ///
    /// # Errors
    ///
    /// Returns a redacted [`FfiError`] when the session is locked.
    pub fn biometric_master_key(&self) -> Result<Vec<u8>, FfiError> {
        self.with_state(|state| {
            state
                .session
                .master_key_for_biometric()
                .map(|key| key.expose_secret().to_vec())
                .map_err(FfiError::from)
        })
    }

    /// Unlock with a master key unwrapped by BiometricPrompt.
    ///
    /// # Errors
    ///
    /// Returns a redacted [`FfiError`] for a malformed blob/key or a keyring
    /// that does not open.
    #[uniffi::constructor]
    pub fn unlock_with_master_key(
        blob: Vec<u8>,
        master_key: Vec<u8>,
    ) -> Result<Arc<Self>, FfiError> {
        let mut master_key = master_key;
        let secret = SecretBytes::from_slice(&master_key);
        master_key.zeroize();
        let vault = VaultBlob::decode(&blob)?;
        let session = VaultSession::unlock_with_master_key(&vault, &secret)?;
        Ok(Arc::new(Self {
            state: Mutex::new(SessionState {
                session,
                shares: Vec::new(),
            }),
        }))
    }
}

impl FfiVaultSession {
    fn with_state<T>(&self, operation: impl FnOnce(&SessionState) -> T) -> T {
        let state = self
            .state
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        operation(&state)
    }

    fn with_state_mut<T>(&self, operation: impl FnOnce(&mut SessionState) -> T) -> T {
        let mut state = self
            .state
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        operation(&mut state)
    }
}

fn now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs())
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use cipherkeep_core::Aad;

    #[test]
    fn search_crosses_the_ffi_boundary() {
        let index = FfiSearchIndex::new();
        index
            .insert(FfiSearchDocument {
                id: vec![1u8; 16],
                title: "GitHub".to_string(),
                username: "alice".to_string(),
                url: "https://github.com".to_string(),
                folder: "Work".to_string(),
                notes: "deploy key".to_string(),
            })
            .expect("insert");
        let hits = index.search("github".to_string(), 10);
        assert_eq!(hits.len(), 1);
        assert_eq!(hits[0].id, vec![1u8; 16]);
        assert!(!hits[0].snippet.contains("deploy"));
        index.clear();
        assert!(index.search("github".to_string(), 10).is_empty());
    }

    #[test]
    fn totp_crosses_the_ffi_boundary() {
        let code = totp_code(
            "otpauth://totp/Example:alice?secret=JBSWY3DPEHPK3PXP&digits=6&period=30".to_string(),
            59,
        )
        .expect("totp");
        assert_eq!(code.code.len(), 6);
        assert!(code
            .code
            .chars()
            .all(|character| character.is_ascii_digit()));
        assert_eq!(code.seconds_remaining, 1);
    }

    #[test]
    fn generator_crosses_the_ffi_boundary() {
        let generated = generate_password(FfiPasswordPolicy {
            length: 20,
            lowercase: true,
            uppercase: true,
            digits: true,
            symbols: true,
            exclude_ambiguous: true,
        })
        .expect("generate");
        assert_eq!(generated.value.len(), 20);
        assert!(generated
            .value
            .chars()
            .any(|character| character.is_ascii_lowercase()));
        assert!(generated
            .value
            .chars()
            .any(|character| character.is_ascii_uppercase()));
        assert!(generated
            .value
            .chars()
            .any(|character| character.is_ascii_digit()));
        assert!(generated.entropy_bits > 100.0);

        let phrase = generate_passphrase(FfiPassphrasePolicy {
            words: 6,
            separator: "-".to_string(),
        })
        .expect("passphrase");
        assert_eq!(phrase.value.split('-').count(), 6);
        assert_eq!(phrase.strength, "Fair");
    }

    #[test]
    fn generator_rejects_an_invalid_separator() {
        let error = generate_passphrase(FfiPassphrasePolicy {
            words: 6,
            separator: "--".to_string(),
        })
        .unwrap_err();
        assert_eq!(
            error.numeric_code(),
            ErrorCode::InvalidArgument.numeric_code()
        );
    }

    #[test]
    fn error_codes_match_core_codes() {
        let cases = [
            (FfiError::InvalidArgument, ErrorCode::InvalidArgument),
            (FfiError::UnsupportedVersion, ErrorCode::UnsupportedVersion),
            (
                FfiError::AuthenticationFailed,
                ErrorCode::AuthenticationFailed,
            ),
            (FfiError::CorruptData, ErrorCode::CorruptData),
            (FfiError::Locked, ErrorCode::Locked),
            (FfiError::NotFound, ErrorCode::NotFound),
            (FfiError::Conflict, ErrorCode::Conflict),
            (FfiError::LimitExceeded, ErrorCode::LimitExceeded),
            (FfiError::Storage, ErrorCode::Storage),
            (FfiError::Internal, ErrorCode::Internal),
            (FfiError::PolicyRejected, ErrorCode::PolicyRejected),
        ];
        for (ffi, core) in cases {
            assert_eq!(ffi.numeric_code(), core.numeric_code());
        }
    }

    #[test]
    fn version_is_exposed() {
        assert_eq!(core_version(), cipherkeep_core::VERSION);
    }

    #[test]
    fn generated_keys_are_32_bytes() {
        let key = generate_key().expect("key");
        assert_eq!(key.len(), 32);
        assert!(key.iter().any(|byte| *byte != 0));
    }

    #[test]
    fn field_round_trip_over_ffi_shape() {
        let key = generate_key().expect("key");
        let id = vec![7u8; 16];
        let envelope =
            seal_field(key.clone(), id.clone(), 10, 4, false, b"secret".to_vec()).expect("seal");
        let plaintext = open_field(key, id, 10, 4, false, envelope).expect("open");
        assert_eq!(plaintext, b"secret");
    }

    #[test]
    fn item_key_wrap_round_trip_over_ffi_shape() {
        let vault_key = generate_key().expect("vault key");
        let item_key = generate_key().expect("item key");
        let vault_id = vec![1u8; 16];
        let item_id = vec![2u8; 16];
        let envelope = wrap_item_key(
            vault_key.clone(),
            item_key.clone(),
            vault_id.clone(),
            item_id.clone(),
            1,
        )
        .expect("wrap");
        let unwrapped = unwrap_item_key(vault_key, vault_id, item_id, 1, envelope).expect("unwrap");
        assert_eq!(unwrapped, item_key);
    }

    #[test]
    fn recovery_round_trip_over_ffi_shape() {
        let secret = vec![0x42u8; 32];
        let shares = recovery_split(secret.clone()).expect("split");
        let recovered = recovery_combine(shares[0].clone(), shares[2].clone()).expect("combine");
        assert_eq!(recovered, secret);
    }

    #[test]
    fn ffi_session_create_and_unlock() {
        let password = b"correct horse battery staple".to_vec();
        let session = FfiVaultSession::create(password.clone()).expect("create");
        assert!(!session.is_locked());
        let shares = session.recovery_shares();
        assert_eq!(shares.len(), 3);
        assert!(shares.iter().all(|share| !share.is_empty()));
        assert_ne!(shares[0], shares[1]);
        let blob = session.blob();
        let unlocked = FfiVaultSession::unlock(blob, password).expect("unlock");
        assert!(!unlocked.is_locked());
        unlocked.lock();
        assert!(unlocked.is_locked());
    }

    #[test]
    fn ffi_session_item_crud() {
        let password = b"correct horse battery staple".to_vec();
        let session = FfiVaultSession::create(password).expect("create");
        let item_id = vec![7u8; 16];
        session
            .save_item(item_id.clone(), b"payload".to_vec())
            .expect("save");
        assert_eq!(session.item_count().expect("count"), 1);
        assert_eq!(session.list_items().expect("list"), vec![item_id.clone()]);
        assert_eq!(session.get_item(item_id.clone()).expect("get"), b"payload");
        session.delete_item(item_id).expect("delete");
        assert_eq!(session.item_count().expect("count"), 0);
    }

    #[test]
    fn core_aad_still_bound() {
        // Keep the core AAD import exercised so the FFI crate compiles against
        // the exact public surface it depends on.
        let aad = Aad::field(ItemId::from_bytes([1u8; 16]), 10, 4, false);
        assert!(!aad.encode().is_empty());
    }
}
