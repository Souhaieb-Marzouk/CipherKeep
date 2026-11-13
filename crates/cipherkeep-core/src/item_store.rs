//! Plaintext item validation and the platform-independent CRUD store contract.
//!
//! This module deliberately works before encryption: platform code validates a
//! draft, then seals each accepted field with the item key. Validation errors
//! carry a field id and a reason code, never the field value.

use core::fmt;
use std::collections::BTreeMap;

use crate::crdt::ActorId;
use crate::error::{CoreError, ErrorCode, Result};
use crate::item::{
    expected_kind, FieldKind, ItemRecord, ItemType, FIELD_ALIAS_ADDRESS, FIELD_CARDHOLDER,
    FIELD_CARD_CVV, FIELD_CARD_EXPIRY, FIELD_CARD_NUMBER, FIELD_IDENTITY_NAME, FIELD_NOTES,
    FIELD_PASSWORD, FIELD_SSH_PRIVATE_KEY, FIELD_TITLE, FIELD_TOTP,
};
use crate::keys::{ItemId, ItemKey};
use crate::secret::SecretBytes;

type ValidationResult<T> = std::result::Result<T, ValidationIssue>;

/// Maximum number of fields on one item.
pub const MAX_ITEM_FIELDS: usize = 64;
/// Maximum title length in bytes.
pub const MAX_TITLE_LEN: usize = 512;
/// Maximum note length in bytes.
pub const MAX_NOTE_LEN: usize = 64 * 1024;
/// Maximum URL length in bytes.
pub const MAX_URL_LEN: usize = 2_048;
/// Maximum ordinary field length in bytes.
pub const MAX_FIELD_LEN: usize = 64 * 1024;

/// A field-specific validation reason.
#[repr(u16)]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ValidationReason {
    /// Field id is not known and is not a custom id.
    UnknownField = 1,
    /// Field is not valid for the selected item type.
    FieldNotAllowed = 2,
    /// A required field is absent.
    MissingRequiredField = 3,
    /// A required field is empty.
    EmptyField = 4,
    /// The value exceeds its size limit.
    TooLong = 5,
    /// A control character is not allowed in this field.
    ControlCharacter = 6,
    /// A URL must use http or https and contain no whitespace.
    InvalidUrl = 7,
    /// An email address is malformed.
    InvalidEmail = 8,
    /// A phone number contains unsupported characters.
    InvalidPhone = 9,
    /// A card number has the wrong length or Luhn check digit.
    InvalidCardNumber = 10,
    /// A card expiry is not MM/YY or MM/YYYY.
    InvalidCardExpiry = 11,
    /// A CVV is not three or four digits.
    InvalidCvv = 12,
    /// A TOTP value is not an otpauth URI or base32 secret.
    InvalidTotp = 13,
    /// An SSH key does not have the expected marker.
    InvalidSshKey = 14,
}

impl ValidationReason {
    /// Stable code for tests and platform mapping.
    #[must_use]
    pub const fn code(self) -> u16 {
        self as u16
    }
}

/// A field-specific validation failure. Debug output never contains the value.
#[derive(Clone, Copy, Eq, PartialEq)]
pub struct ValidationIssue {
    field_id: u16,
    reason: ValidationReason,
}

impl ValidationIssue {
    /// The field that failed, or zero for an item-level issue.
    #[must_use]
    pub const fn field_id(self) -> u16 {
        self.field_id
    }

    /// The redacted reason.
    #[must_use]
    pub const fn reason(self) -> ValidationReason {
        self.reason
    }

    /// Stable field+reason code for regression tests.
    #[must_use]
    pub const fn code(self) -> u32 {
        ((self.field_id as u32) << 16) | self.reason.code() as u32
    }
}

impl fmt::Debug for ValidationIssue {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("ValidationIssue")
            .field("field_id", &self.field_id)
            .field("reason", &self.reason)
            .finish()
    }
}

impl fmt::Display for ValidationIssue {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            formatter,
            "field {} rejected ({:?})",
            self.field_id, self.reason
        )
    }
}

/// A plaintext draft. Values are zeroized when the draft is dropped.
pub struct ItemDraft {
    item_type: ItemType,
    fields: BTreeMap<u16, SecretBytes>,
}

impl ItemDraft {
    /// Create an empty draft for one item type.
    #[must_use]
    pub fn new(item_type: ItemType) -> Self {
        Self {
            item_type,
            fields: BTreeMap::new(),
        }
    }

    /// The selected item type.
    #[must_use]
    pub const fn item_type(&self) -> ItemType {
        self.item_type
    }

    /// Set a field after type, length and semantic validation.
    ///
    /// # Errors
    ///
    /// Returns a redacted [`ValidationIssue`] for an unknown/disallowed field,
    /// an oversized value or invalid content.
    pub fn set(&mut self, field_id: u16, value: &[u8]) -> ValidationResult<()> {
        self.check_field(field_id)?;
        if self.fields.len() >= MAX_ITEM_FIELDS && !self.fields.contains_key(&field_id) {
            return Err(issue(field_id, ValidationReason::TooLong));
        }
        let kind = expected_kind(field_id)
            .ok_or_else(|| issue(field_id, ValidationReason::UnknownField))?;
        validate_value(field_id, kind, value)?;
        self.fields.insert(field_id, SecretBytes::from_slice(value));
        Ok(())
    }

    /// Delete a field from the draft.
    pub fn remove(&mut self, field_id: u16) {
        self.fields.remove(&field_id);
    }

    /// Validate required fields and every stored value.
    ///
    /// # Errors
    ///
    /// Returns the first redacted [`ValidationIssue`].
    pub fn validate(&self) -> ValidationResult<()> {
        for field_id in required_fields(self.item_type) {
            match self.fields.get(field_id) {
                None => return Err(issue(*field_id, ValidationReason::MissingRequiredField)),
                Some(value) if value.is_empty() => {
                    return Err(issue(*field_id, ValidationReason::EmptyField));
                }
                Some(_) => {}
            }
        }
        for (field_id, value) in &self.fields {
            let kind = expected_kind(*field_id)
                .ok_or_else(|| issue(*field_id, ValidationReason::UnknownField))?;
            validate_value(*field_id, kind, value.expose_secret())?;
        }
        Ok(())
    }

    /// Number of fields in the draft.
    #[must_use]
    pub fn field_count(&self) -> usize {
        self.fields.len()
    }

    /// Read a field without copying it.
    #[must_use]
    pub fn field(&self, field_id: u16) -> Option<&SecretBytes> {
        self.fields.get(&field_id)
    }

    fn check_field(&self, field_id: u16) -> ValidationResult<()> {
        if expected_kind(field_id).is_none() {
            return Err(issue(field_id, ValidationReason::UnknownField));
        }
        if !self.item_type.allows_field(field_id) {
            return Err(issue(field_id, ValidationReason::FieldNotAllowed));
        }
        Ok(())
    }
}

fn issue(field_id: u16, reason: ValidationReason) -> ValidationIssue {
    ValidationIssue { field_id, reason }
}

fn required_fields(item_type: ItemType) -> &'static [u16] {
    match item_type {
        ItemType::Login => &[FIELD_TITLE, FIELD_PASSWORD],
        ItemType::Totp => &[FIELD_TITLE, FIELD_TOTP],
        ItemType::Note => &[FIELD_TITLE, FIELD_NOTES],
        ItemType::Card => &[
            FIELD_TITLE,
            FIELD_CARD_NUMBER,
            FIELD_CARD_EXPIRY,
            FIELD_CARD_CVV,
            FIELD_CARDHOLDER,
        ],
        ItemType::Identity => &[FIELD_TITLE, FIELD_IDENTITY_NAME],
        ItemType::SshKey => &[FIELD_TITLE, FIELD_SSH_PRIVATE_KEY],
        ItemType::Alias => &[FIELD_TITLE, FIELD_ALIAS_ADDRESS],
    }
}

fn validate_value(field_id: u16, kind: FieldKind, value: &[u8]) -> ValidationResult<()> {
    let max = match kind {
        FieldKind::Secret => MAX_FIELD_LEN,
        FieldKind::Note => MAX_NOTE_LEN,
        FieldKind::Url => MAX_URL_LEN,
        _ => MAX_FIELD_LEN,
    };
    if value.len() > max {
        return Err(issue(field_id, ValidationReason::TooLong));
    }
    if kind == FieldKind::Secret {
        return Ok(());
    }
    let text = core::str::from_utf8(value)
        .map_err(|_| issue(field_id, ValidationReason::ControlCharacter))?;
    if text
        .chars()
        .any(|character| character.is_control() && !matches!(character, '\n' | '\r' | '\t'))
    {
        return Err(issue(field_id, ValidationReason::ControlCharacter));
    }
    if text.is_empty() {
        return Err(issue(field_id, ValidationReason::EmptyField));
    }
    match kind {
        FieldKind::Url => validate_url(field_id, text),
        FieldKind::Email => validate_email(field_id, text),
        FieldKind::Phone => validate_phone(field_id, text),
        FieldKind::CardNumber => validate_card_number(field_id, text),
        FieldKind::CardExpiry => validate_expiry(field_id, text),
        FieldKind::CardCvv => validate_cvv(field_id, text),
        FieldKind::Totp => validate_totp(field_id, text),
        FieldKind::SshPublicKey | FieldKind::SshPrivateKey => validate_ssh(field_id, text),
        FieldKind::Text
        | FieldKind::Secret
        | FieldKind::Note
        | FieldKind::Cardholder
        | FieldKind::PersonName
        | FieldKind::Address
        | FieldKind::Custom => Ok(()),
    }
}

fn validate_url(field_id: u16, text: &str) -> ValidationResult<()> {
    if text.len() > MAX_URL_LEN
        || text.chars().any(char::is_whitespace)
        || !(text.starts_with("https://") || text.starts_with("http://"))
        || text.len() <= "http://".len()
    {
        return Err(issue(field_id, ValidationReason::InvalidUrl));
    }
    Ok(())
}

fn validate_email(field_id: u16, text: &str) -> ValidationResult<()> {
    let mut parts = text.split('@');
    let local = parts.next().unwrap_or_default();
    let domain = parts.next().unwrap_or_default();
    if local.is_empty()
        || domain.is_empty()
        || parts.next().is_some()
        || domain.starts_with('.')
        || domain.ends_with('.')
        || !domain.contains('.')
        || text.chars().any(char::is_whitespace)
    {
        return Err(issue(field_id, ValidationReason::InvalidEmail));
    }
    Ok(())
}

fn validate_phone(field_id: u16, text: &str) -> ValidationResult<()> {
    if !text
        .chars()
        .all(|character| character.is_ascii_digit() || "+-() ".contains(character))
        || text.chars().filter(char::is_ascii_digit).count() < 7
    {
        return Err(issue(field_id, ValidationReason::InvalidPhone));
    }
    Ok(())
}

fn validate_card_number(field_id: u16, text: &str) -> ValidationResult<()> {
    let digits: Vec<u32> = text
        .chars()
        .filter(|character| !matches!(character, ' ' | '-'))
        .map(|character| character.to_digit(10))
        .collect::<Option<Vec<_>>>()
        .ok_or_else(|| issue(field_id, ValidationReason::InvalidCardNumber))?;
    if !(12..=19).contains(&digits.len()) || !luhn(&digits) {
        return Err(issue(field_id, ValidationReason::InvalidCardNumber));
    }
    Ok(())
}

fn luhn(digits: &[u32]) -> bool {
    let mut sum = 0u32;
    let parity = digits.len() % 2;
    for (index, digit) in digits.iter().enumerate() {
        let mut value = *digit;
        if index % 2 == parity {
            value *= 2;
            if value > 9 {
                value -= 9;
            }
        }
        sum = sum.saturating_add(value);
    }
    sum % 10 == 0
}

fn validate_expiry(field_id: u16, text: &str) -> ValidationResult<()> {
    let parts: Vec<&str> = text.split('/').collect();
    if parts.len() != 2 {
        return Err(issue(field_id, ValidationReason::InvalidCardExpiry));
    }
    let month = parts[0].parse::<u8>().ok();
    let year = parts[1];
    if !matches!(month, Some(1..=12))
        || !(year.len() == 2 || year.len() == 4)
        || !year.chars().all(|character| character.is_ascii_digit())
    {
        return Err(issue(field_id, ValidationReason::InvalidCardExpiry));
    }
    Ok(())
}

fn validate_cvv(field_id: u16, text: &str) -> ValidationResult<()> {
    if !(text.len() == 3 || text.len() == 4)
        || !text.chars().all(|character| character.is_ascii_digit())
    {
        return Err(issue(field_id, ValidationReason::InvalidCvv));
    }
    Ok(())
}

fn validate_totp(field_id: u16, text: &str) -> ValidationResult<()> {
    if let Some(secret) = text.strip_prefix("otpauth://totp/") {
        return if secret.is_empty() {
            Err(issue(field_id, ValidationReason::InvalidTotp))
        } else {
            Ok(())
        };
    }
    let cleaned = text.trim_end_matches('=').to_ascii_uppercase();
    if cleaned.len() < 16
        || !cleaned
            .chars()
            .all(|character| character.is_ascii_uppercase() || ('2'..='7').contains(&character))
    {
        return Err(issue(field_id, ValidationReason::InvalidTotp));
    }
    Ok(())
}

fn validate_ssh(field_id: u16, text: &str) -> ValidationResult<()> {
    let valid = text.starts_with("ssh-")
        || text.starts_with("ecdsa-")
        || text.starts_with("sk-")
        || text.contains("PRIVATE KEY");
    if !valid {
        return Err(issue(field_id, ValidationReason::InvalidSshKey));
    }
    Ok(())
}

/// Platform-independent CRUD store for encrypted item records.
pub trait ItemStore {
    /// Insert a new record.
    ///
    /// # Errors
    ///
    /// Returns an error if the record is invalid or already exists.
    fn create(&mut self, record: ItemRecord) -> Result<()>;

    /// Read a record by id.
    fn get(&self, id: ItemId) -> Option<&ItemRecord>;

    /// Replace an existing record.
    ///
    /// # Errors
    ///
    /// Returns an error if the record is invalid or absent.
    fn update(&mut self, record: ItemRecord) -> Result<()>;

    /// Apply a sticky item tombstone.
    ///
    /// # Errors
    ///
    /// Returns an error if the record is absent.
    fn soft_delete(&mut self, id: ItemId, actor: ActorId) -> Result<()>;

    /// Remove the record and its wrapped ciphertext. The caller must discard
    /// the corresponding item key.
    ///
    /// # Errors
    ///
    /// Returns an error if the record is absent.
    fn hard_delete(&mut self, id: ItemId) -> Result<()>;

    /// List live and tombstoned item ids in deterministic order.
    fn list(&self) -> Vec<ItemId>;
}

/// A deterministic in-memory store used by core tests and future platforms.
#[derive(Default)]
pub struct MemoryItemStore {
    records: BTreeMap<ItemId, ItemRecord>,
    keys: BTreeMap<ItemId, ItemKey>,
}

impl MemoryItemStore {
    /// Create an empty store.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    /// Store a record together with its item key so hard delete can discard
    /// the key material as well as the ciphertext.
    ///
    /// # Errors
    ///
    /// Returns an error if the record is invalid or already exists.
    pub fn store_with_key(&mut self, record: ItemRecord, key: ItemKey) -> Result<()> {
        let id = record.id();
        self.create(record)?;
        self.keys.insert(id, key);
        Ok(())
    }

    /// Whether an item key is retained for the id.
    #[must_use]
    pub fn has_key(&self, id: ItemId) -> bool {
        self.keys.contains_key(&id)
    }
}

impl ItemStore for MemoryItemStore {
    fn create(&mut self, record: ItemRecord) -> Result<()> {
        record.validate()?;
        if self.records.contains_key(&record.id()) {
            return Err(CoreError::new(ErrorCode::Conflict));
        }
        self.records.insert(record.id(), record);
        Ok(())
    }

    fn get(&self, id: ItemId) -> Option<&ItemRecord> {
        self.records.get(&id)
    }

    fn update(&mut self, record: ItemRecord) -> Result<()> {
        record.validate()?;
        if !self.records.contains_key(&record.id()) {
            return Err(CoreError::new(ErrorCode::NotFound));
        }
        self.records.insert(record.id(), record);
        Ok(())
    }

    fn soft_delete(&mut self, id: ItemId, actor: ActorId) -> Result<()> {
        let record = self
            .records
            .get_mut(&id)
            .ok_or_else(|| CoreError::new(ErrorCode::NotFound))?;
        record.delete(actor)
    }

    fn hard_delete(&mut self, id: ItemId) -> Result<()> {
        self.keys.remove(&id);
        self.records
            .remove(&id)
            .map(|_| ())
            .ok_or_else(|| CoreError::new(ErrorCode::NotFound))
    }

    fn list(&self) -> Vec<ItemId> {
        self.records.keys().copied().collect()
    }
}

/// Validate a draft and return it, or a redacted issue.
///
/// # Errors
///
/// Returns the first field-specific [`ValidationIssue`].
pub fn validate_draft(draft: &ItemDraft) -> ValidationResult<()> {
    draft.validate()
}

/// A migration helper that refuses unknown future schemas before touching data.
///
/// # Errors
///
/// Returns [`ErrorCode::UnsupportedVersion`] for a schema newer than this build.
pub fn migrate_record(mut record: ItemRecord, from_schema: u16) -> Result<ItemRecord> {
    if from_schema > crate::item::ITEM_SCHEMA_VERSION {
        return Err(CoreError::new(ErrorCode::UnsupportedVersion));
    }
    // v0 is the pre-field-registry shape. The registry is additive and keeps
    // unknown custom fields, so no field is dropped during this migration.
    if from_schema < crate::item::ITEM_SCHEMA_VERSION {
        record = record.migrate_to_current(from_schema)?;
    }
    Ok(record)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crdt::{ActorId, LamportClock};
    use crate::item::{
        FieldRecord, FIELD_CARDHOLDER, FIELD_CARD_CVV, FIELD_CARD_EXPIRY, FIELD_CARD_NUMBER,
        FIELD_IDENTITY_NAME, FIELD_PASSWORD, FIELD_SSH_PRIVATE_KEY, FIELD_TOTP,
    };
    use crate::keys::ItemKey;

    fn draft(item_type: ItemType) -> ItemDraft {
        ItemDraft::new(item_type)
    }

    fn valid_login() -> ItemDraft {
        let mut item = draft(ItemType::Login);
        item.set(FIELD_TITLE, b"Example").unwrap();
        item.set(FIELD_PASSWORD, b"secret-value").unwrap();
        item
    }

    #[test]
    fn all_types_validate_with_required_fields() {
        let mut login = valid_login();
        login.set(crate::item::FIELD_USERNAME, b"user").unwrap();
        login.validate().unwrap();

        let mut totp = draft(ItemType::Totp);
        totp.set(FIELD_TITLE, b"TOTP").unwrap();
        totp.set(FIELD_TOTP, b"JBSWY3DPEHPK3PXP").unwrap();
        totp.validate().unwrap();

        let mut note = draft(ItemType::Note);
        note.set(FIELD_TITLE, b"Note").unwrap();
        note.set(FIELD_NOTES, b"line one\nline two").unwrap();
        note.validate().unwrap();

        let mut card = draft(ItemType::Card);
        card.set(FIELD_TITLE, b"Card").unwrap();
        card.set(FIELD_CARD_NUMBER, b"4242424242424242").unwrap();
        card.set(FIELD_CARD_EXPIRY, b"12/30").unwrap();
        card.set(FIELD_CARD_CVV, b"123").unwrap();
        card.set(FIELD_CARDHOLDER, b"Test User").unwrap();
        card.validate().unwrap();

        let mut identity = draft(ItemType::Identity);
        identity.set(FIELD_TITLE, b"Identity").unwrap();
        identity.set(FIELD_IDENTITY_NAME, b"Test User").unwrap();
        identity.validate().unwrap();

        let mut ssh = draft(ItemType::SshKey);
        ssh.set(FIELD_TITLE, b"SSH").unwrap();
        ssh.set(FIELD_SSH_PRIVATE_KEY, b"-----BEGIN PRIVATE KEY-----\nabc")
            .unwrap();
        ssh.validate().unwrap();

        let mut alias = draft(ItemType::Alias);
        alias.set(FIELD_TITLE, b"Alias").unwrap();
        alias
            .set(FIELD_ALIAS_ADDRESS, b"site-abc@alias.test")
            .unwrap();
        alias.validate().unwrap();
    }

    #[test]
    fn invalid_values_are_field_specific() {
        let mut login = valid_login();
        assert_eq!(
            login
                .set(crate::item::FIELD_URL, b"not a url")
                .unwrap_err()
                .reason(),
            ValidationReason::InvalidUrl
        );
        assert_eq!(
            login.set(FIELD_TITLE, b"bad\0title").unwrap_err().reason(),
            ValidationReason::ControlCharacter
        );

        let mut card = draft(ItemType::Card);
        card.set(FIELD_TITLE, b"Card").unwrap();
        assert_eq!(
            card.set(FIELD_CARD_NUMBER, b"4242424242424241")
                .unwrap_err()
                .reason(),
            ValidationReason::InvalidCardNumber
        );
        assert_eq!(
            card.set(FIELD_CARD_EXPIRY, b"13/30").unwrap_err().reason(),
            ValidationReason::InvalidCardExpiry
        );

        let mut totp = draft(ItemType::Totp);
        totp.set(FIELD_TITLE, b"TOTP").unwrap();
        assert_eq!(
            totp.set(FIELD_TOTP, b"short").unwrap_err().reason(),
            ValidationReason::InvalidTotp
        );
    }

    fn record(item_type: ItemType, field_id: u16, kind: FieldKind, value: &[u8]) -> ItemRecord {
        let id = ItemId::from_bytes([9u8; 16]);
        let actor = ActorId::from_bytes([3u8; 16]);
        let key = ItemKey::generate().unwrap();
        let envelope = key.seal_field(id, field_id, 1, false, value).unwrap();
        let mut record = ItemRecord::new(id, item_type, 1, actor);
        record
            .put_field(FieldRecord::new(
                field_id,
                kind,
                envelope,
                LamportClock::new(1, actor),
            ))
            .unwrap();
        record
    }

    #[test]
    fn memory_store_crud_and_soft_delete() {
        let actor = ActorId::from_bytes([3u8; 16]);
        let item = record(
            ItemType::Login,
            FIELD_PASSWORD,
            FieldKind::Secret,
            b"secret",
        );
        let id = item.id();
        let mut store = MemoryItemStore::new();
        store.create(item).unwrap();
        assert!(store.get(id).is_some());
        assert_eq!(store.list(), vec![id]);

        assert!(store
            .create(record(
                ItemType::Login,
                FIELD_PASSWORD,
                FieldKind::Secret,
                b"x"
            ))
            .is_err());
        store.soft_delete(id, actor).unwrap();
        assert!(store.get(id).unwrap().is_deleted());
        store.hard_delete(id).unwrap();
        assert!(store.get(id).is_none());
        assert!(!store.has_key(id));
        assert_eq!(store.list(), Vec::<ItemId>::new());
    }

    #[test]
    fn hard_delete_discards_the_item_key() {
        let id = ItemId::from_bytes([6u8; 16]);
        let actor = ActorId::from_bytes([6u8; 16]);
        let key = ItemKey::generate().unwrap();
        let envelope = key
            .seal_field(id, FIELD_PASSWORD, 1, false, b"secret")
            .unwrap();
        let mut item = ItemRecord::new(id, ItemType::Login, 1, actor);
        item.put_field(FieldRecord::new(
            FIELD_PASSWORD,
            FieldKind::Secret,
            envelope,
            LamportClock::new(1, actor),
        ))
        .unwrap();
        let mut store = MemoryItemStore::new();
        store.store_with_key(item, key).unwrap();
        assert!(store.has_key(id));
        store.hard_delete(id).unwrap();
        assert!(!store.has_key(id));
    }

    #[test]
    fn migration_preserves_custom_fields() {
        let actor = ActorId::from_bytes([4u8; 16]);
        let id = ItemId::from_bytes([8u8; 16]);
        let key = ItemKey::generate().unwrap();
        let envelope = key
            .seal_field(id, crate::item::CUSTOM_FIELD_ID_BASE, 1, false, b"custom")
            .unwrap();
        let mut item = ItemRecord::new(id, ItemType::Login, 1, actor);
        item.put_field(FieldRecord::new(
            crate::item::CUSTOM_FIELD_ID_BASE,
            FieldKind::Custom,
            envelope,
            LamportClock::new(1, actor),
        ))
        .unwrap();
        let migrated = item.migrate_to_current(0).unwrap();
        assert_eq!(migrated.schema(), crate::item::ITEM_SCHEMA_VERSION);
        assert!(migrated.field(crate::item::CUSTOM_FIELD_ID_BASE).is_some());
    }

    #[test]
    fn future_schema_is_refused() {
        let item = ItemRecord::new(
            ItemId::from_bytes([1u8; 16]),
            ItemType::Note,
            1,
            ActorId::from_bytes([1u8; 16]),
        );
        assert_eq!(
            item.migrate_to_current(crate::item::ITEM_SCHEMA_VERSION + 1)
                .unwrap_err()
                .code(),
            ErrorCode::UnsupportedVersion
        );
    }

    #[test]
    fn arbitrary_bytes_never_panic_in_validation() {
        for seed in 0u32..512 {
            let mut value = [0u8; 8];
            for (index, byte) in value.iter_mut().enumerate() {
                *byte = ((seed.wrapping_mul(31).wrapping_add(index as u32)) & 0xff) as u8;
            }
            let mut draft = draft(ItemType::Login);
            let _ = draft.set(FIELD_TITLE, &value);
            let _ = draft.set(crate::item::FIELD_URL, &value);
            let _ = draft.validate();
        }
    }
}
