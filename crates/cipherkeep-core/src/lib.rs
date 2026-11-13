//! CipherKeep cryptographic core.
//!
//! This crate is deliberately platform-agnostic: it performs no file or network
//! I/O, contains no Android or WASM bindings, and is compiled unchanged into
//! both the Android application and the Firefox extension. Platform crates own
//! storage, OS integration and UI.
//!
//! # Security invariants established here
//!
//! * Secret byte buffers are zeroized on drop and on explicit [`Zeroize`].
//! * Secret types do not implement `Clone`, `Display` or transparent `Debug`.
//! * Equality of secrets is constant-time for equal-length inputs.
//! * Errors carry only stable codes and static, redacted messages. They never
//!   carry caller data, ciphertext, plaintext or key material.
//! * The crate forbids `unsafe` code.
//!
//! The crate currently provides the error contract, secret-memory types,
//! Argon2id key derivation, the vault header, XChaCha20-Poly1305 envelopes,
//! keyring wrapping, Shamir recovery shares and the CRDT item model.
//!
//! [`Zeroize`]: zeroize::Zeroize

#![forbid(unsafe_code)]
#![deny(missing_docs)]

pub mod alias;
pub mod crdt;
pub mod emergency;
pub mod envelope;
pub mod error;
pub mod export;
pub mod generator;
pub mod header;
pub mod health;
pub mod hibp;
pub mod import;
pub mod item;
pub mod item_store;
pub mod kdf;
pub mod keyring;
pub mod keys;
pub mod lock;
pub mod phishing;
pub mod recipes;
pub mod search;
pub mod secret;
pub mod session;
pub mod shamir;
pub mod sharing;
pub mod sync;
pub mod totp;
pub mod travel;

pub use crdt::{ActorId, LamportClock, VersionVector};
pub use envelope::{
    Aad, Envelope, Purpose, ENVELOPE_VERSION, MAX_ENVELOPE_CIPHERTEXT_LEN,
    SUITE_XCHACHA20_POLY1305, TAG_LEN,
};
pub use error::{CoreError, ErrorCode, Result};
pub use export::{
    export_backup, import_backup, EXPORT_HEADER_LEN, EXPORT_MAGIC, EXPORT_VERSION,
    MAX_EXPORT_BYTES, MIN_EXPORT_PASSWORD_LEN,
};
pub use generator::{
    generate_passphrase, generate_password, wordlist_is_valid, wordlist_len, GeneratedSecret,
    PassphrasePolicy, PasswordPolicy, StrengthLabel, EFF_WORDLIST_LEN, MAX_PASSPHRASE_WORDS,
    MAX_PASSWORD_LEN, MIN_PASSPHRASE_WORDS, MIN_PASSWORD_LEN,
};
pub use header::{
    VaultHeader, AAD_PREFIX_LEN, MAX_KEYRING_CIPHERTEXT_LEN, MIN_HEADER_LEN, NONCE_LEN,
};
pub use import::{
    detect_format, import, import_with_format, DuplicatePolicy, ImportFormat, ImportKind,
    ImportReport, ImportResult, ImportedItem, MAX_IMPORT_BYTES, MAX_IMPORT_COLUMNS,
    MAX_IMPORT_FIELD, MAX_IMPORT_ITEMS,
};
pub use item::{
    expected_kind, FieldConflict, FieldKind, FieldRecord, ItemConflict, ItemRecord, ItemType,
    ItemTypeConflict, MergeResult, CUSTOM_FIELD_ID_BASE, FIELD_CARDHOLDER, FIELD_CARD_CVV,
    FIELD_CARD_EXPIRY, FIELD_CARD_NUMBER, FIELD_IDENTITY_ADDRESS, FIELD_IDENTITY_EMAIL,
    FIELD_IDENTITY_NAME, FIELD_IDENTITY_PHONE, FIELD_NOTES, FIELD_PASSWORD, FIELD_SSH_PRIVATE_KEY,
    FIELD_SSH_PUBLIC_KEY, FIELD_TITLE, FIELD_TOTP, FIELD_URL, FIELD_USERNAME, ITEM_SCHEMA_VERSION,
};
pub use item_store::{
    migrate_record, validate_draft, ItemDraft, ItemStore, MemoryItemStore, ValidationIssue,
    ValidationReason, MAX_FIELD_LEN, MAX_ITEM_FIELDS, MAX_NOTE_LEN, MAX_TITLE_LEN,
};
pub use kdf::{
    derive_master_key, derive_master_key_with_policy, KdfAlgorithm, KdfParams, KdfPolicy,
    DEFAULT_ITERATIONS, DEFAULT_MEMORY_KIB, DEFAULT_PARALLELISM, MASTER_KEY_LEN, SALT_LEN,
};
pub use keyring::{Keyring, MAX_VAULTS};
pub use keys::{derive_subkey, DeviceId, ItemId, ItemKey, MasterKey, VaultId, VaultKey, KEY_LEN};
pub use lock::{LockPolicy, DEFAULT_TIMEOUT_SECS, WARN_BEFORE_SECS};
pub use recipes::{
    verify_signed_registry, Recipe, RecipeRegistry, MAX_ORIGIN_LEN, MAX_RECIPES, MAX_SELECTOR_LEN,
};
pub use search::{measure_p95, SearchDocument, SearchHit, SearchIndex, MAX_SEARCH_RESULTS};
pub use secret::{SecretBytes, SecretString};
pub use session::{validate_master_password, CreatedVault, VaultBlob, VaultSession};
pub use shamir::{
    combine_2_of_3, split_2_of_3, RecoveryShare, RECOVERY_SECRET_LEN, SHARE_COUNT, THRESHOLD,
};
pub use sync::{
    legacy::migrate_legacy_item,
    local::LocalFolderAdapter,
    merge::{ConflictChoice, MergeEngine, MergeOutcome},
    scheduler::{RetryPolicy, SyncScheduler},
    MemorySyncAdapter, SyncAdapter, SyncBatch, SyncCapabilities, SyncManifest, SyncVersionVector,
    MAX_SYNC_BATCH_BYTES, MAX_SYNC_BLOBS, MAX_SYNC_DEVICES, SYNC_PAD_BLOCK,
};
pub use totp::{
    parse_otpauth_uri, Totp, TotpAlgorithm, TotpEntry, DEFAULT_PERIOD, MAX_PERIOD, MAX_SECRET_LEN,
    MIN_PERIOD, MIN_SECRET_LEN,
};

/// The version of the core crate.
///
/// This is the Cargo package version, not the vault format version.
pub const VERSION: &str = env!("CARGO_PKG_VERSION");
