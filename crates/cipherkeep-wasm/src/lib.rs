//! wasm-bindgen surface for the CipherKeep Firefox extension.
//!
//! This is the primitive binding layer: version, key generation, Argon2id test
//! derivation, field seal/open, item-key wrapping and recovery shares. It uses
//! the same numeric error codes as the Android UniFFI surface.
//!
//! The stateful `VaultSession` API is implemented. The extension can
//! call these primitives but cannot create or unlock a vault.

#![forbid(unsafe_code)]

use getrandom::getrandom;
use serde::Serialize;
use wasm_bindgen::prelude::*;
use zeroize::{Zeroize, Zeroizing};

use cipherkeep_core::{
    combine_2_of_3, derive_master_key, export_backup,
    generate_passphrase as core_generate_passphrase, generate_password as core_generate_password,
    import_backup, migrate_legacy_item as core_migrate_legacy_item, parse_otpauth_uri,
    split_2_of_3, ActorId, CoreError, Envelope, ErrorCode, ItemId, ItemKey, KdfParams,
    PassphrasePolicy, PasswordPolicy, RecoveryShare, SearchDocument, SearchIndex, SecretBytes,
    VaultBlob, VaultId, VaultKey, VaultSession, DEFAULT_ITERATIONS, DEFAULT_MEMORY_KIB,
    DEFAULT_PARALLELISM,
};

#[derive(Serialize)]
struct WasmError {
    code: u16,
    message: &'static str,
}

fn js_error(error: cipherkeep_core::CoreError) -> JsValue {
    let payload = WasmError {
        code: error.numeric_code(),
        message: error.message(),
    };
    serde_wasm_bindgen::to_value(&payload).unwrap_or_else(|_| JsValue::from_str("internal error"))
}

#[derive(Serialize)]
struct WasmGeneratedSecret {
    value: String,
    entropy_bits: f64,
    strength: &'static str,
}

/// Generate a password with the shared core generator.
///
/// # Errors
///
/// Returns a JS `{ code, message }` object for an impossible policy or CSPRNG
/// failure.
#[wasm_bindgen]
pub fn generate_password(
    length: u32,
    lowercase: bool,
    uppercase: bool,
    digits: bool,
    symbols: bool,
    exclude_ambiguous: bool,
) -> Result<JsValue, JsValue> {
    let length = usize::try_from(length).map_err(|_| {
        js_error(cipherkeep_core::CoreError::new(
            cipherkeep_core::ErrorCode::InvalidArgument,
        ))
    })?;
    let generated = core_generate_password(&PasswordPolicy {
        length,
        lowercase,
        uppercase,
        digits,
        symbols,
        exclude_ambiguous,
    })
    .map_err(js_error)?;
    serde_wasm_bindgen::to_value(&WasmGeneratedSecret {
        value: generated.expose_secret().to_string(),
        entropy_bits: generated.entropy_bits(),
        strength: generated.strength().as_str(),
    })
    .map_err(|_| JsValue::from_str("internal error"))
}

/// Generate an EFF-style passphrase with the shared core generator.
///
/// # Errors
///
/// Returns a JS `{ code, message }` object for an invalid policy or CSPRNG
/// failure.
#[wasm_bindgen]
pub fn generate_passphrase(words: u32, separator: &str) -> Result<JsValue, JsValue> {
    let words = usize::try_from(words).map_err(|_| {
        js_error(cipherkeep_core::CoreError::new(
            cipherkeep_core::ErrorCode::InvalidArgument,
        ))
    })?;
    let mut characters = separator.chars();
    let separator = characters.next().ok_or_else(|| {
        js_error(cipherkeep_core::CoreError::new(
            cipherkeep_core::ErrorCode::InvalidArgument,
        ))
    })?;
    if characters.next().is_some() {
        return Err(js_error(cipherkeep_core::CoreError::new(
            cipherkeep_core::ErrorCode::InvalidArgument,
        )));
    }
    let generated =
        core_generate_passphrase(&PassphrasePolicy { words, separator }).map_err(js_error)?;
    serde_wasm_bindgen::to_value(&WasmGeneratedSecret {
        value: generated.expose_secret().to_string(),
        entropy_bits: generated.entropy_bits(),
        strength: generated.strength().as_str(),
    })
    .map_err(|_| JsValue::from_str("internal error"))
}

#[derive(Serialize)]
struct WasmTotpCode {
    code: String,
    seconds_remaining: u64,
}

/// Generate a TOTP code from an otpauth URI.
///
/// # Errors
///
/// Returns a JS `{ code, message }` object for an invalid URI.
#[wasm_bindgen]
pub fn totp_code(uri: &str, unix_secs: u32) -> Result<JsValue, JsValue> {
    let entry = parse_otpauth_uri(uri).map_err(js_error)?;
    let code = entry
        .totp()
        .code_at(u64::from(unix_secs))
        .map_err(js_error)?;
    serde_wasm_bindgen::to_value(&WasmTotpCode {
        code: code.expose_secret().to_string(),
        seconds_remaining: entry.totp().seconds_remaining(u64::from(unix_secs)),
    })
    .map_err(|_| JsValue::from_str("internal error"))
}

/// Encrypt portable item JSON with a separate export password.
///
/// # Errors
///
/// Returns a JS error object for a weak password or crypto failure.
#[wasm_bindgen]
pub fn export_portable(items_json: Vec<u8>, password: &str) -> Result<Vec<u8>, JsValue> {
    export_backup(&items_json, password).map_err(js_error)
}

/// Decrypt a portable export and return item JSON.
///
/// # Errors
///
/// Returns a JS error object for a wrong password, tamper or malformed header.
#[wasm_bindgen]
pub fn import_portable(bytes: Vec<u8>, password: &str) -> Result<Vec<u8>, JsValue> {
    import_backup(&bytes, password)
        .map(|plaintext| plaintext.expose_secret().to_vec())
        .map_err(js_error)
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
/// Returns a JS object `{ code, message }` with code 10 on CSPRNG failure.
pub fn generate_key() -> Result<Vec<u8>, JsValue> {
    let mut key = Zeroizing::new([0u8; 32]);
    getrandom(&mut key[..]).map_err(|_| {
        js_error(cipherkeep_core::CoreError::new(
            cipherkeep_core::ErrorCode::Internal,
        ))
    })?;
    if key.iter().all(|byte| *byte == 0) {
        return Err(js_error(cipherkeep_core::CoreError::new(
            cipherkeep_core::ErrorCode::Internal,
        )));
    }
    Ok(key.to_vec())
}

/// Derive the master key with the pinned default Argon2id parameters.
///
/// # Errors
///
/// Returns a JS error object for a bad salt length or derivation failure.
pub fn derive_master_key_for_test(password: Vec<u8>, salt: Vec<u8>) -> Result<Vec<u8>, JsValue> {
    let mut password = password;
    let mut salt = salt;
    if salt.len() != 16 {
        password.zeroize();
        salt.zeroize();
        return Err(js_error(cipherkeep_core::CoreError::new(
            cipherkeep_core::ErrorCode::InvalidArgument,
        )));
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
    derive_master_key(&secret, &params)
        .map(|key| key.expose_secret().to_vec())
        .map_err(js_error)
}

/// Seal a field with an item key.
///
/// # Errors
///
/// Returns a JS error object for malformed ids/keys or sealing failure.
pub fn seal_field(
    item_key: Vec<u8>,
    item_id: Vec<u8>,
    field_id: u16,
    lamport: u64,
    tombstone: bool,
    plaintext: Vec<u8>,
) -> Result<Vec<u8>, JsValue> {
    let mut item_key = item_key;
    let mut item_id = item_id;
    let mut plaintext = plaintext;
    let id = ItemId::from_bytes(id_from_slice(&item_id).map_err(js_error)?);
    let key = ItemKey::from_secret_bytes(SecretBytes::from_slice(&item_key)).map_err(js_error)?;
    item_key.zeroize();
    item_id.zeroize();
    let result = key.seal_field(id, field_id, lamport, tombstone, &plaintext);
    plaintext.zeroize();
    Ok(result.map_err(js_error)?.encode())
}

/// Open a field with an item key.
///
/// # Errors
///
/// Returns a JS error object with code 3 for a wrong key/context or tampering.
pub fn open_field(
    item_key: Vec<u8>,
    item_id: Vec<u8>,
    field_id: u16,
    lamport: u64,
    tombstone: bool,
    envelope: Vec<u8>,
) -> Result<Vec<u8>, JsValue> {
    let mut item_key = item_key;
    let mut item_id = item_id;
    let id = ItemId::from_bytes(id_from_slice(&item_id).map_err(js_error)?);
    let key = ItemKey::from_secret_bytes(SecretBytes::from_slice(&item_key)).map_err(js_error)?;
    item_key.zeroize();
    item_id.zeroize();
    let envelope = Envelope::decode(&envelope).map_err(js_error)?;
    key.open_field(&envelope, id, field_id, lamport, tombstone)
        .map(|plaintext| plaintext.expose_secret().to_vec())
        .map_err(js_error)
}

/// Migrate legacy plaintext JSON into canonical encrypted sync JSON.
#[wasm_bindgen]
pub fn migrate_legacy_item(
    json: Vec<u8>,
    item_id: Vec<u8>,
    item_key: Vec<u8>,
    actor: Vec<u8>,
    created_at: u64,
    counter_start: u64,
) -> Result<Vec<u8>, JsValue> {
    let mut json = json;
    let mut item_id = item_id;
    let mut item_key = item_key;
    let mut actor = actor;
    let id: [u8; 16] = item_id
        .as_slice()
        .try_into()
        .map_err(|_| js_error(CoreError::new(ErrorCode::InvalidArgument)))?;
    let mut actor: [u8; 16] = actor
        .as_slice()
        .try_into()
        .map_err(|_| js_error(CoreError::new(ErrorCode::InvalidArgument)))?;
    let key = ItemKey::from_secret_bytes(SecretBytes::from_slice(&item_key)).map_err(js_error)?;
    let result = core_migrate_legacy_item(
        &json,
        ItemId::from_bytes(id),
        &key,
        ActorId::from_bytes(actor),
        created_at,
        counter_start,
    )
    .map_err(js_error)?
    .to_sync_json()
    .map_err(js_error)?;
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
/// Returns a JS error object for malformed ids/keys or wrapping failure.
pub fn wrap_item_key(
    vault_key: Vec<u8>,
    item_key: Vec<u8>,
    vault_id: Vec<u8>,
    item_id: Vec<u8>,
    schema: u16,
) -> Result<Vec<u8>, JsValue> {
    let mut vault_key = vault_key;
    let mut item_key = item_key;
    let mut vault_id = vault_id;
    let vault = VaultKey::from_parts(
        VaultId::from_bytes(id_from_slice(&vault_id).map_err(js_error)?),
        SecretBytes::from_slice(&vault_key),
    )
    .map_err(js_error)?;
    let item = ItemKey::from_secret_bytes(SecretBytes::from_slice(&item_key)).map_err(js_error)?;
    vault_key.zeroize();
    item_key.zeroize();
    vault_id.zeroize();
    let item_id = ItemId::from_bytes(id_from_slice(&item_id).map_err(js_error)?);
    vault
        .wrap_item_key(&item, item_id, schema)
        .map(|envelope| envelope.encode())
        .map_err(js_error)
}

/// Unwrap an item key with a vault key.
///
/// # Errors
///
/// Returns a JS error object with code 3 for wrong material/context or tampering.
pub fn unwrap_item_key(
    vault_key: Vec<u8>,
    vault_id: Vec<u8>,
    item_id: Vec<u8>,
    schema: u16,
    envelope: Vec<u8>,
) -> Result<Vec<u8>, JsValue> {
    let mut vault_key = vault_key;
    let mut vault_id = vault_id;
    let vault = VaultKey::from_parts(
        VaultId::from_bytes(id_from_slice(&vault_id).map_err(js_error)?),
        SecretBytes::from_slice(&vault_key),
    )
    .map_err(js_error)?;
    vault_key.zeroize();
    vault_id.zeroize();
    let item_id = ItemId::from_bytes(id_from_slice(&item_id).map_err(js_error)?);
    let envelope = Envelope::decode(&envelope).map_err(js_error)?;
    vault
        .unwrap_item_key(&envelope, item_id, schema)
        .map(|item| item.expose_secret().to_vec())
        .map_err(js_error)
}

/// Split a 32-byte recovery key into three 2-of-3 encoded shares.
///
/// # Errors
///
/// Returns a JS error object for a wrong secret length or CSPRNG failure.
pub fn recovery_split(secret: Vec<u8>) -> Result<Vec<String>, JsValue> {
    let mut secret = secret;
    let result = split_2_of_3(&SecretBytes::from_slice(&secret));
    secret.zeroize();
    let shares = result.map_err(js_error)?;
    Ok(shares
        .iter()
        .map(|share| share.encode().expose_secret().to_string())
        .collect())
}

/// Combine two encoded recovery shares into the 32-byte recovery key.
///
/// # Errors
///
/// Returns a JS error object for malformed, corrupted or duplicate shares.
pub fn recovery_combine(first: String, second: String) -> Result<Vec<u8>, JsValue> {
    let first = RecoveryShare::decode(&first).map_err(js_error)?;
    let second = RecoveryShare::decode(&second).map_err(js_error)?;
    combine_2_of_3(&first, &second)
        .map(|secret| secret.expose_secret().to_vec())
        .map_err(js_error)
}

fn id_from_slice(bytes: &[u8]) -> Result<[u8; 16], cipherkeep_core::CoreError> {
    bytes
        .try_into()
        .map_err(|_| cipherkeep_core::CoreError::new(cipherkeep_core::ErrorCode::InvalidArgument))
}

/// Vault session handle for the Firefox background page.
#[derive(Serialize)]
struct WasmSearchHit {
    id: String,
    score: u16,
    snippet: String,
}

/// In-memory metadata search index for the extension.
#[wasm_bindgen]
pub struct WasmSearchIndex {
    inner: SearchIndex,
}

#[wasm_bindgen]
impl WasmSearchIndex {
    /// Create an empty index.
    #[wasm_bindgen(constructor)]
    #[must_use]
    pub fn new() -> Self {
        Self {
            inner: SearchIndex::new(),
        }
    }

    /// Insert or replace a metadata document.
    ///
    /// # Errors
    ///
    /// Returns a JS error object for an invalid id or empty title.
    pub fn insert(
        &mut self,
        id: Vec<u8>,
        title: String,
        username: String,
        url: String,
        folder: String,
        notes: String,
    ) -> Result<(), JsValue> {
        let item_id = ItemId::from_bytes(id_from_slice(&id).map_err(js_error)?);
        self.inner
            .insert(SearchDocument::new(
                item_id, title, username, url, folder, notes,
            ))
            .map_err(js_error)
    }

    /// Remove a document.
    pub fn remove(&mut self, id: Vec<u8>) -> bool {
        id_from_slice(&id)
            .map(ItemId::from_bytes)
            .map(|item_id| self.inner.remove(item_id))
            .unwrap_or(false)
    }

    /// Clear and zeroize the index.
    pub fn clear(&mut self) {
        self.inner.clear();
    }

    /// Search metadata and return redacted hits.
    ///
    /// # Errors
    ///
    /// Returns a JS error object if serialization fails.
    pub fn search(&self, query: &str, limit: u32) -> Result<JsValue, JsValue> {
        let hits: Vec<WasmSearchHit> = self
            .inner
            .search(query, usize::try_from(limit).unwrap_or(0))
            .into_iter()
            .map(|hit| WasmSearchHit {
                id: hex(hit.id().as_bytes()),
                score: hit.score(),
                snippet: hit.snippet().to_string(),
            })
            .collect();
        serde_wasm_bindgen::to_value(&hits).map_err(|_| JsValue::from_str("internal error"))
    }

    /// Number of indexed documents.
    #[must_use]
    #[allow(clippy::len_without_is_empty)]
    pub fn len(&self) -> u32 {
        u32::try_from(self.inner.len()).unwrap_or(u32::MAX)
    }
}

impl Default for WasmSearchIndex {
    fn default() -> Self {
        Self::new()
    }
}

#[wasm_bindgen]
pub struct WasmVaultSession {
    inner: VaultSession,
    shares: Vec<String>,
}

#[wasm_bindgen]
impl WasmVaultSession {
    /// Create a vault and return the session with its recovery shares.
    ///
    /// # Errors
    ///
    /// Returns a JS error object for a weak password or key-generation failure.
    #[wasm_bindgen]
    pub fn create(password: Vec<u8>) -> Result<WasmVaultSession, JsValue> {
        let mut password = password;
        let secret = SecretBytes::from_slice(&password);
        password.zeroize();
        let created = VaultSession::create(&secret).map_err(js_error)?;
        let shares = created
            .shares
            .iter()
            .map(|share| share.expose_secret().to_string())
            .collect();
        Ok(Self {
            inner: created.session,
            shares,
        })
    }

    /// Open a persisted vault in the locked state.
    ///
    /// Keeps local failed-attempt/backoff state across unlock calls.
    ///
    /// # Errors
    ///
    /// Returns a JS error object for a malformed blob.
    #[wasm_bindgen]
    pub fn open(blob: Vec<u8>) -> Result<WasmVaultSession, JsValue> {
        let vault = VaultBlob::decode(&blob).map_err(js_error)?;
        let inner = VaultSession::open(&vault).map_err(js_error)?;
        Ok(Self {
            inner,
            shares: Vec::new(),
        })
    }

    /// Unlock a persisted vault blob.
    ///
    /// # Errors
    ///
    /// Returns a JS error object for a wrong password, corrupt blob or backoff.
    #[wasm_bindgen]
    pub fn unlock(blob: Vec<u8>, password: Vec<u8>) -> Result<WasmVaultSession, JsValue> {
        let mut password = password;
        let secret = SecretBytes::from_slice(&password);
        password.zeroize();
        let vault = VaultBlob::decode(&blob).map_err(js_error)?;
        let inner = VaultSession::unlock(&vault, &secret, now_secs()).map_err(js_error)?;
        Ok(Self {
            inner,
            shares: Vec::new(),
        })
    }

    /// Recover with two shares and set a new master password.
    ///
    /// # Errors
    ///
    /// Returns a JS error object for bad shares, a weak password or a malformed
    /// blob.
    #[wasm_bindgen]
    pub fn recover(
        blob: Vec<u8>,
        first: String,
        second: String,
        new_password: Vec<u8>,
    ) -> Result<WasmVaultSession, JsValue> {
        let mut new_password = new_password;
        let secret = SecretBytes::from_slice(&new_password);
        new_password.zeroize();
        let vault = VaultBlob::decode(&blob).map_err(js_error)?;
        let first = RecoveryShare::decode(&first).map_err(js_error)?;
        let second = RecoveryShare::decode(&second).map_err(js_error)?;
        let created = VaultSession::recover(&vault, &first, &second, &secret).map_err(js_error)?;
        let shares = created
            .shares
            .iter()
            .map(|share| share.expose_secret().to_string())
            .collect();
        Ok(Self {
            inner: created.session,
            shares,
        })
    }

    /// Encrypt and store an opaque item payload.
    ///
    /// # Errors
    ///
    /// Returns a JS error object for a malformed id, locked session or crypto
    /// failure.
    #[wasm_bindgen]
    pub fn save_item(&mut self, item_id: Vec<u8>, plaintext: Vec<u8>) -> Result<(), JsValue> {
        let id = ItemId::from_bytes(id_from_slice(&item_id).map_err(js_error)?);
        self.inner.save_item(id, &plaintext).map_err(js_error)
    }

    /// Encrypt an item payload for IndexedDB storage without adding it to the
    /// in-memory item map.
    ///
    /// # Errors
    ///
    /// Returns a JS error object for a malformed id, locked session or crypto
    /// failure.
    #[wasm_bindgen]
    pub fn encrypt_item(&self, item_id: Vec<u8>, plaintext: Vec<u8>) -> Result<Vec<u8>, JsValue> {
        let id = ItemId::from_bytes(id_from_slice(&item_id).map_err(js_error)?);
        self.inner.encrypt_item(id, &plaintext).map_err(js_error)
    }

    /// Decrypt an item ciphertext returned by `encrypt_item`.
    ///
    /// # Errors
    ///
    /// Returns a JS error object for a malformed id, locked session or
    /// authentication failure.
    #[wasm_bindgen]
    /// The vault id used for sync namespaces and AAD.
    pub fn vault_id(&self) -> Result<Vec<u8>, JsValue> {
        self.inner
            .vault_id()
            .map(|id| id.as_bytes().to_vec())
            .map_err(js_error)
    }

    pub fn decrypt_item(&self, item_id: Vec<u8>, ciphertext: Vec<u8>) -> Result<Vec<u8>, JsValue> {
        let id = ItemId::from_bytes(id_from_slice(&item_id).map_err(js_error)?);
        self.inner.decrypt_item(id, &ciphertext).map_err(js_error)
    }

    /// Export a v2 sync record with wrapped item key.
    pub fn export_sync_record(
        &self,
        item_id: Vec<u8>,
        ciphertext: Vec<u8>,
        actor: Vec<u8>,
        created_at: u64,
        counter_start: u64,
    ) -> Result<Vec<u8>, JsValue> {
        let id = ItemId::from_bytes(id_from_slice(&item_id).map_err(js_error)?);
        let actor: [u8; 16] = actor.as_slice().try_into()
            .map_err(|_| js_error(CoreError::new(ErrorCode::InvalidArgument)))?;
        self.inner
            .export_sync_record(id, &ciphertext, ActorId::from_bytes(actor), created_at, counter_start)
            .map_err(js_error)
    }

    /// Apply a v2 sync record to a legacy JSON payload.
    pub fn apply_sync_record(&self, sync_json: Vec<u8>) -> Result<Vec<u8>, JsValue> {
        self.inner.apply_sync_record(&sync_json).map_err(js_error)
    }

    /// Migrate an encrypted legacy item into canonical sync JSON.
    #[wasm_bindgen]
    pub fn migrate_item(
        &self,
        item_id: Vec<u8>,
        ciphertext: Vec<u8>,
        actor: Vec<u8>,
        created_at: u64,
        counter_start: u64,
    ) -> Result<Vec<u8>, JsValue> {
        let id = ItemId::from_bytes(id_from_slice(&item_id).map_err(js_error)?);
        let actor: [u8; 16] = actor
            .as_slice()
            .try_into()
            .map_err(|_| js_error(CoreError::new(ErrorCode::InvalidArgument)))?;
        self.inner
            .migrate_item(
                id,
                &ciphertext,
                ActorId::from_bytes(actor),
                created_at,
                counter_start,
            )
            .map_err(js_error)
    }

    /// Decrypt an item payload.
    ///
    /// # Errors
    ///
    /// Returns a JS error object for a missing item, locked session or
    /// authentication failure.
    #[wasm_bindgen]
    pub fn get_item(&self, item_id: Vec<u8>) -> Result<Vec<u8>, JsValue> {
        let id = ItemId::from_bytes(id_from_slice(&item_id).map_err(js_error)?);
        self.inner.get_item(id).map_err(js_error)
    }

    /// List item ids as lowercase hex strings.
    ///
    /// # Errors
    ///
    /// Returns a JS error object when the session is locked.
    #[wasm_bindgen]
    pub fn list_item_ids(&self) -> Result<Vec<String>, JsValue> {
        self.inner
            .list_items()
            .map(|ids| ids.iter().map(|id| hex(id.as_bytes())).collect())
            .map_err(js_error)
    }

    /// Delete an item.
    ///
    /// # Errors
    ///
    /// Returns a JS error object when the session is locked.
    #[wasm_bindgen]
    pub fn delete_item(&mut self, item_id: Vec<u8>) -> Result<(), JsValue> {
        let id = ItemId::from_bytes(id_from_slice(&item_id).map_err(js_error)?);
        self.inner.delete_item(id).map_err(js_error)
    }

    /// Number of stored items.
    ///
    /// # Errors
    ///
    /// Returns a JS error object when the session is locked.
    #[wasm_bindgen]
    pub fn item_count(&self) -> Result<u32, JsValue> {
        self.inner
            .item_count()
            .map(|count| count as u32)
            .map_err(js_error)
    }

    /// Persist this vault's header and recovery envelope.
    #[wasm_bindgen]
    #[must_use]
    pub fn blob(&self) -> Vec<u8> {
        self.inner.blob().encode()
    }

    /// One-time recovery shares; empty after a normal unlock.
    #[wasm_bindgen]
    #[must_use]
    pub fn recovery_shares(&mut self) -> Vec<String> {
        let mut shares = std::mem::take(&mut self.shares);
        for share in &mut shares {
            share.zeroize();
        }
        shares
    }

    /// Lock and drop the master key/keyring.
    #[wasm_bindgen]
    pub fn lock(&mut self) {
        self.inner.lock();
        for share in &mut self.shares {
            share.zeroize();
        }
        self.shares.clear();
    }

    /// Whether the session is locked.
    #[wasm_bindgen]
    #[must_use]
    pub fn is_locked(&self) -> bool {
        self.inner.is_locked()
    }

    /// Consecutive failed unlock attempts.
    #[wasm_bindgen]
    #[must_use]
    pub fn failed_attempts(&self) -> u32 {
        self.inner.failed_attempts()
    }

    /// Seconds until another unlock is allowed.
    #[wasm_bindgen]
    #[must_use]
    pub fn retry_after(&self) -> u32 {
        u32::try_from(self.inner.retry_after(now_secs())).unwrap_or(u32::MAX)
    }

    /// Unlock this handle again after `lock`.
    ///
    /// # Errors
    ///
    /// As `unlock`.
    #[wasm_bindgen]
    pub fn unlock_again(&mut self, password: Vec<u8>) -> Result<(), JsValue> {
        let mut password = password;
        let secret = SecretBytes::from_slice(&password);
        password.zeroize();
        self.inner
            .unlock_again(&secret, now_secs())
            .map_err(js_error)
    }
}

fn hex(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut output = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        output.push(HEX[(byte >> 4) as usize] as char);
        output.push(HEX[(byte & 0x0f) as usize] as char);
    }
    output
}

fn now_secs() -> u64 {
    (js_sys::Date::now() / 1000.0) as u64
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn version_is_exposed() {
        assert_eq!(core_version(), cipherkeep_core::VERSION);
    }
}

#[cfg(all(test, target_arch = "wasm32"))]
mod wasm_tests {
    use super::*;
    use wasm_bindgen_test::*;

    #[wasm_bindgen_test]
    fn wasm_search_returns_redacted_hits() {
        let mut index = WasmSearchIndex::new();
        index
            .insert(
                vec![1u8; 16],
                "GitHub".to_string(),
                "alice".to_string(),
                "https://github.com".to_string(),
                "Work".to_string(),
                "deploy key".to_string(),
            )
            .expect("insert");
        let hits = index.search("github", 10).expect("search");
        assert!(hits.is_array());
        index.clear();
    }

    #[wasm_bindgen_test]
    fn wasm_totp_returns_an_object() {
        let generated = totp_code(
            "otpauth://totp/Example:alice?secret=JBSWY3DPEHPK3PXP&digits=6&period=30",
            59,
        )
        .expect("totp");
        assert!(generated.is_object());
    }

    #[wasm_bindgen_test]
    fn wasm_generator_returns_an_object() {
        let generated = generate_password(20, true, true, true, true, true).expect("generate");
        assert!(generated.is_object());
        let phrase = generate_passphrase(6, "-").expect("passphrase");
        assert!(phrase.is_object());
        assert!(generate_passphrase(6, "--").is_err());
    }

    #[wasm_bindgen_test]
    fn wasm_core_version() {
        assert_eq!(core_version(), cipherkeep_core::VERSION);
    }

    #[wasm_bindgen_test]
    fn wasm_field_round_trip() {
        let key = generate_key().expect("key");
        let id = vec![7u8; 16];
        let envelope =
            seal_field(key.clone(), id.clone(), 10, 4, false, b"secret".to_vec()).expect("seal");
        let plaintext = open_field(key, id, 10, 4, false, envelope).expect("open");
        assert_eq!(plaintext, b"secret");
    }

    #[wasm_bindgen_test]
    fn wasm_session_item_crud_and_lock() {
        let password = b"correct horse battery staple".to_vec();
        let mut session = WasmVaultSession::create(password).expect("create");
        let item_id = vec![7u8; 16];
        session
            .save_item(item_id.clone(), b"payload".to_vec())
            .expect("save");
        assert_eq!(session.item_count().expect("count"), 1);
        assert_eq!(session.get_item(item_id.clone()).expect("get"), b"payload");
        session.delete_item(item_id.clone()).expect("delete");
        assert_eq!(session.item_count().expect("count"), 0);

        session.save_item(item_id, b"x".to_vec()).expect("save");
        session.lock();
        assert!(session.get_item(vec![7u8; 16]).is_err());
        assert!(session.save_item(vec![7u8; 16], b"x".to_vec()).is_err());
    }

    #[wasm_bindgen_test]
    fn wasm_session_create_unlock_and_lock() {
        let password = b"correct horse battery staple".to_vec();
        let mut session = WasmVaultSession::create(password.clone()).expect("create");
        assert!(!session.is_locked());
        assert_eq!(session.recovery_shares().len(), 3);
        let blob = session.blob();
        let mut unlocked = WasmVaultSession::unlock(blob, password).expect("unlock");
        assert!(!unlocked.is_locked());
        unlocked.lock();
        assert!(unlocked.is_locked());
    }

    #[wasm_bindgen_test]
    fn wasm_recovery_round_trip() {
        let secret = vec![0x42u8; 32];
        let shares = recovery_split(secret.clone()).expect("split");
        let recovered = recovery_combine(shares[0].clone(), shares[2].clone()).expect("combine");
        assert_eq!(recovered, secret);
    }
}


/// Merge local and remote canonical ItemRecord JSON strings.
///
/// Returns `{records: [json string], conflicts: [{item, field, kind}]}`.
#[wasm_bindgen]
pub fn merge_sync_records(local: Vec<String>, remote: Vec<String>) -> Result<String, JsValue> {
    use cipherkeep_core::item::ItemConflict;
    use cipherkeep_core::{MergeEngine, MergeOutcome};
    let mut engine = MergeEngine::new();
    let mut conflicts = Vec::new();
    for raw in local {
        let record = cipherkeep_core::item::ItemRecord::from_sync_json(raw.as_bytes())
            .map_err(js_error)?;
        engine.insert_local(record).map_err(js_error)?;
    }
    for raw in remote {
        let record = cipherkeep_core::item::ItemRecord::from_sync_json(raw.as_bytes())
            .map_err(js_error)?;
        let outcome: MergeOutcome = engine.merge_remote(&record).map_err(js_error)?;
        for conflict in outcome.conflicts {
            if let ItemConflict::Field(field) = conflict {
                conflicts.push(serde_json::json!({
                    "item": hex(field.item_id().as_bytes()),
                    "field": field.left().field_id(),
                    "kind": "field",
                }));
            }
        }
    }
    let mut records = Vec::new();
    for record in engine.records_for_sync().map_err(js_error)? {
        records.push(String::from_utf8(record).map_err(|_| js_error(CoreError::new(ErrorCode::Internal)))?);
    }
    serde_json::to_string(&serde_json::json!({ "records": records, "conflicts": conflicts }))
        .map_err(|_| js_error(CoreError::new(ErrorCode::Internal)))
}


/// Merge V2 sync records, preserving each item's wrapped key.
#[wasm_bindgen]
pub fn merge_sync_records_v2(local: Vec<String>, remote: Vec<String>) -> Result<String, JsValue> {
    use cipherkeep_core::item::ItemConflict;
    use cipherkeep_core::MergeEngine;
    use std::collections::BTreeMap;

    fn parse(raw: &str) -> Result<(String, String, Vec<u8>), JsValue> {
        let value: serde_json::Value =
            serde_json::from_str(raw).map_err(|_| js_error(CoreError::new(ErrorCode::CorruptData)))?;
        if value.get("v").and_then(serde_json::Value::as_u64) != Some(2) {
            return Err(js_error(CoreError::new(ErrorCode::UnsupportedVersion)));
        }
        let item = value.get("item").and_then(serde_json::Value::as_str)
            .ok_or_else(|| js_error(CoreError::new(ErrorCode::CorruptData)))?.to_string();
        let key = value.get("key").and_then(serde_json::Value::as_str)
            .ok_or_else(|| js_error(CoreError::new(ErrorCode::CorruptData)))?.to_string();
        let record = value.get("record").ok_or_else(|| js_error(CoreError::new(ErrorCode::CorruptData)))?;
        let bytes = serde_json::to_vec(record)
            .map_err(|_| js_error(CoreError::new(ErrorCode::Internal)))?;
        Ok((item, key, bytes))
    }

    let mut engine = MergeEngine::new();
    let mut keys: BTreeMap<String, String> = BTreeMap::new();
    for raw in local {
        let (item, key, bytes) = parse(&raw)?;
        keys.insert(item, key);
        engine.insert_local(cipherkeep_core::item::ItemRecord::from_sync_json(&bytes).map_err(js_error)?)
            .map_err(js_error)?;
    }
    let mut conflicts = Vec::new();
    for raw in remote {
        let (item, key, bytes) = parse(&raw)?;
        keys.entry(item).or_insert(key);
        let record = cipherkeep_core::item::ItemRecord::from_sync_json(&bytes).map_err(js_error)?;
        let outcome = engine.merge_remote(&record).map_err(js_error)?;
        for conflict in outcome.conflicts {
            if let ItemConflict::Field(field) = conflict {
                conflicts.push(serde_json::json!({
                    "item": hex(field.item_id().as_bytes()),
                    "field": field.left().field_id(),
                    "kind": "field",
                }));
            }
        }
    }
    let mut records = Vec::new();
    for raw in engine.records_for_sync().map_err(js_error)? {
        let record = cipherkeep_core::item::ItemRecord::from_sync_json(&raw).map_err(js_error)?;
        let id = hex(record.id().as_bytes());
        let key = keys.get(&id).ok_or_else(|| js_error(CoreError::new(ErrorCode::CorruptData)))?;
        let value: serde_json::Value = serde_json::from_slice(&raw)
            .map_err(|_| js_error(CoreError::new(ErrorCode::Internal)))?;
        records.push(serde_json::json!({"v":2,"item":id,"key":key,"record":value}).to_string());
    }
    serde_json::to_string(&serde_json::json!({ "records": records, "conflicts": conflicts }))
        .map_err(|_| js_error(CoreError::new(ErrorCode::Internal)))
}
