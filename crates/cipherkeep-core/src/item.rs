//! Item schema and CRDT merge for all six item types.
//!
//! Fields are encrypted [`Envelope`] values. The CRDT never decrypts them:
//! merge compares `(Lamport counter, actor)` and, only for a true same-actor
//! tie, the encrypted record bytes. A conflict is surfaced as an explicit
//! [`FieldConflict`] rather than silently overwriting either side.
//!
//! Deletion is sticky: a tombstone is never resurrected by a concurrent edit,
//! even if the edit carries a higher counter. This protects against stale
//! devices restoring a deleted password.

use core::fmt;
use std::collections::{BTreeMap, BTreeSet};

use crate::crdt::{ActorId, LamportClock};
use crate::envelope::Envelope;
use crate::error::{CoreError, ErrorCode, Result};
use crate::keys::ItemId;

/// Current item schema version.
pub const ITEM_SCHEMA_VERSION: u16 = 1;

/// Title field, available on every item type.
pub const FIELD_TITLE: u16 = 1;
/// Notes field, available on every item type.
pub const FIELD_NOTES: u16 = 2;
/// Login username.
pub const FIELD_USERNAME: u16 = 10;
/// Login password.
pub const FIELD_PASSWORD: u16 = 11;
/// Login website URL.
pub const FIELD_URL: u16 = 12;
/// TOTP secret URI.
pub const FIELD_TOTP: u16 = 13;
/// Payment card number.
pub const FIELD_CARD_NUMBER: u16 = 20;
/// Payment card expiry.
pub const FIELD_CARD_EXPIRY: u16 = 21;
/// Payment card CVV.
pub const FIELD_CARD_CVV: u16 = 22;
/// Payment cardholder name.
pub const FIELD_CARDHOLDER: u16 = 23;
/// Identity full name.
pub const FIELD_IDENTITY_NAME: u16 = 30;
/// Identity email address.
pub const FIELD_IDENTITY_EMAIL: u16 = 31;
/// Identity phone number.
pub const FIELD_IDENTITY_PHONE: u16 = 32;
/// Identity postal address.
pub const FIELD_IDENTITY_ADDRESS: u16 = 33;
/// SSH public key.
pub const FIELD_SSH_PUBLIC_KEY: u16 = 40;
/// SSH private key.
pub const FIELD_SSH_PRIVATE_KEY: u16 = 41;
/// Email alias address.
pub const FIELD_ALIAS_ADDRESS: u16 = 50;
/// Alias provider identifier.
pub const FIELD_ALIAS_PROVIDER: u16 = 51;
/// Provider-side alias identifier.
pub const FIELD_ALIAS_PROVIDER_ID: u16 = 52;
/// Alias enabled flag.
pub const FIELD_ALIAS_ENABLED: u16 = 53;

/// First field id reserved for custom fields.
pub const CUSTOM_FIELD_ID_BASE: u16 = 0x8000;

/// The six supported item types.
#[repr(u8)]
#[derive(Clone, Copy, Debug, Eq, Ord, PartialEq, PartialOrd)]
pub enum ItemType {
    /// Website or app login.
    Login = 1,
    /// Standalone TOTP entry.
    Totp = 2,
    /// Secure note.
    Note = 3,
    /// Payment card.
    Card = 4,
    /// Identity record.
    Identity = 5,
    /// SSH key pair.
    SshKey = 6,
    /// Email alias created through a user-controlled provider.
    Alias = 7,
}

impl ItemType {
    /// Decode a stable numeric type.
    #[must_use]
    pub const fn from_u8(value: u8) -> Option<Self> {
        match value {
            1 => Some(Self::Login),
            2 => Some(Self::Totp),
            3 => Some(Self::Note),
            4 => Some(Self::Card),
            5 => Some(Self::Identity),
            6 => Some(Self::SshKey),
            7 => Some(Self::Alias),
            _ => None,
        }
    }

    /// Stable rank used for deterministic type-conflict resolution.
    #[must_use]
    pub const fn rank(self) -> u8 {
        self as u8
    }

    /// Whether a field id is allowed on this item type.
    #[must_use]
    pub const fn allows_field(self, field_id: u16) -> bool {
        if field_id >= CUSTOM_FIELD_ID_BASE {
            return true;
        }
        let common = field_id == FIELD_TITLE || field_id == FIELD_NOTES;
        if common {
            return true;
        }
        match self {
            Self::Login => {
                field_id == FIELD_USERNAME
                    || field_id == FIELD_PASSWORD
                    || field_id == FIELD_URL
                    || field_id == FIELD_TOTP
            }
            Self::Totp => field_id == FIELD_TOTP,
            Self::Note => false,
            Self::Card => {
                field_id == FIELD_CARD_NUMBER
                    || field_id == FIELD_CARD_EXPIRY
                    || field_id == FIELD_CARD_CVV
                    || field_id == FIELD_CARDHOLDER
            }
            Self::Identity => {
                field_id == FIELD_IDENTITY_NAME
                    || field_id == FIELD_IDENTITY_EMAIL
                    || field_id == FIELD_IDENTITY_PHONE
                    || field_id == FIELD_IDENTITY_ADDRESS
            }
            Self::SshKey => field_id == FIELD_SSH_PUBLIC_KEY || field_id == FIELD_SSH_PRIVATE_KEY,
            Self::Alias => {
                field_id == FIELD_ALIAS_ADDRESS
                    || field_id == FIELD_ALIAS_PROVIDER
                    || field_id == FIELD_ALIAS_PROVIDER_ID
                    || field_id == FIELD_ALIAS_ENABLED
            }
        }
    }
}

/// Semantic kind of a field value.
#[repr(u8)]
#[derive(Clone, Copy, Debug, Eq, Ord, PartialEq, PartialOrd)]
pub enum FieldKind {
    /// Plain text.
    Text = 1,
    /// Secret text; UI must mask by default.
    Secret = 2,
    /// URL.
    Url = 3,
    /// TOTP seed/URI.
    Totp = 4,
    /// Multi-line note.
    Note = 5,
    /// Card number.
    CardNumber = 6,
    /// Card expiry.
    CardExpiry = 7,
    /// Card CVV.
    CardCvv = 8,
    /// Cardholder name.
    Cardholder = 9,
    /// Person name.
    PersonName = 10,
    /// Email address.
    Email = 11,
    /// Phone number.
    Phone = 12,
    /// Postal address.
    Address = 13,
    /// SSH public key.
    SshPublicKey = 14,
    /// SSH private key.
    SshPrivateKey = 15,
    /// Custom field.
    Custom = 16,
}

impl FieldKind {
    /// Decode a stable numeric kind.
    #[must_use]
    pub const fn from_u8(value: u8) -> Option<Self> {
        match value {
            1 => Some(Self::Text),
            2 => Some(Self::Secret),
            3 => Some(Self::Url),
            4 => Some(Self::Totp),
            5 => Some(Self::Note),
            6 => Some(Self::CardNumber),
            7 => Some(Self::CardExpiry),
            8 => Some(Self::CardCvv),
            9 => Some(Self::Cardholder),
            10 => Some(Self::PersonName),
            11 => Some(Self::Email),
            12 => Some(Self::Phone),
            13 => Some(Self::Address),
            14 => Some(Self::SshPublicKey),
            15 => Some(Self::SshPrivateKey),
            16 => Some(Self::Custom),
            _ => None,
        }
    }
}

/// The expected kind for a standard field id, or `Custom` for a custom id.
#[must_use]
pub const fn expected_kind(field_id: u16) -> Option<FieldKind> {
    match field_id {
        FIELD_TITLE => Some(FieldKind::Text),
        FIELD_NOTES => Some(FieldKind::Note),
        FIELD_USERNAME => Some(FieldKind::Text),
        FIELD_PASSWORD => Some(FieldKind::Secret),
        FIELD_URL => Some(FieldKind::Url),
        FIELD_TOTP => Some(FieldKind::Totp),
        FIELD_CARD_NUMBER => Some(FieldKind::CardNumber),
        FIELD_CARD_EXPIRY => Some(FieldKind::CardExpiry),
        FIELD_CARD_CVV => Some(FieldKind::CardCvv),
        FIELD_CARDHOLDER => Some(FieldKind::Cardholder),
        FIELD_IDENTITY_NAME => Some(FieldKind::PersonName),
        FIELD_IDENTITY_EMAIL => Some(FieldKind::Email),
        FIELD_IDENTITY_PHONE => Some(FieldKind::Phone),
        FIELD_IDENTITY_ADDRESS => Some(FieldKind::Address),
        FIELD_SSH_PUBLIC_KEY => Some(FieldKind::SshPublicKey),
        FIELD_SSH_PRIVATE_KEY => Some(FieldKind::SshPrivateKey),
        FIELD_ALIAS_ADDRESS => Some(FieldKind::Email),
        FIELD_ALIAS_PROVIDER | FIELD_ALIAS_PROVIDER_ID | FIELD_ALIAS_ENABLED => {
            Some(FieldKind::Text)
        }
        _ if field_id >= CUSTOM_FIELD_ID_BASE => Some(FieldKind::Custom),
        _ => None,
    }
}

/// A field value with its Lamport clock and tombstone state.
#[derive(Clone, Eq, PartialEq)]
pub struct FieldRecord {
    field_id: u16,
    kind: FieldKind,
    clock: LamportClock,
    tombstone: bool,
    value: Option<Envelope>,
}

impl FieldRecord {
    /// Create a live field record.
    #[must_use]
    pub fn new(field_id: u16, kind: FieldKind, value: Envelope, clock: LamportClock) -> Self {
        Self {
            field_id,
            kind,
            clock,
            tombstone: false,
            value: Some(value),
        }
    }

    /// Create a tombstone for a deleted field.
    #[must_use]
    pub fn tombstone(field_id: u16, kind: FieldKind, clock: LamportClock) -> Self {
        Self {
            field_id,
            kind,
            clock,
            tombstone: true,
            value: None,
        }
    }

    /// The field id.
    #[must_use]
    pub const fn field_id(&self) -> u16 {
        self.field_id
    }

    /// The semantic kind.
    #[must_use]
    pub const fn kind(&self) -> FieldKind {
        self.kind
    }

    /// The Lamport clock.
    #[must_use]
    pub const fn clock(&self) -> LamportClock {
        self.clock
    }

    /// Whether the field is deleted.
    #[must_use]
    pub const fn is_tombstone(&self) -> bool {
        self.tombstone
    }

    /// The encrypted value, absent for a tombstone.
    #[must_use]
    pub fn value(&self) -> Option<&Envelope> {
        self.value.as_ref()
    }

    /// Reconstruct a field record from serialized sync data.
    #[must_use]
    pub const fn from_parts(
        field_id: u16,
        kind: FieldKind,
        value: Option<Envelope>,
        tombstone: bool,
        clock: LamportClock,
    ) -> Self {
        Self {
            field_id,
            kind,
            value,
            tombstone,
            clock,
        }
    }

    fn tie_key(&self) -> (u8, bool, Vec<u8>) {
        (
            self.kind as u8,
            self.tombstone,
            self.value
                .as_ref()
                .map(Envelope::encode)
                .unwrap_or_default(),
        )
    }

    fn tie_cmp(&self, other: &Self) -> std::cmp::Ordering {
        self.tie_key().cmp(&other.tie_key())
    }
}

impl fmt::Debug for FieldRecord {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("FieldRecord")
            .field("field_id", &self.field_id)
            .field("kind", &self.kind)
            .field("clock", &self.clock)
            .field("tombstone", &self.tombstone)
            .field(
                "value_len",
                &self.value.as_ref().map(Envelope::encode).map(|b| b.len()),
            )
            .finish()
    }
}

/// A same-clock, same-actor field conflict preserved for the user.
#[derive(Clone, Eq, PartialEq)]
pub struct FieldConflict {
    item_id: ItemId,
    left: FieldRecord,
    right: FieldRecord,
}

impl FieldConflict {
    fn new(item_id: ItemId, first: FieldRecord, second: FieldRecord) -> Self {
        if first.tie_cmp(&second).is_le() {
            Self {
                item_id,
                left: first,
                right: second,
            }
        } else {
            Self {
                item_id,
                left: second,
                right: first,
            }
        }
    }

    /// The item this conflict belongs to.
    #[must_use]
    pub const fn item_id(&self) -> ItemId {
        self.item_id
    }

    /// Canonically smaller record.
    #[must_use]
    pub fn left(&self) -> &FieldRecord {
        &self.left
    }

    /// Canonically larger record, which merge keeps as the deterministic winner.
    #[must_use]
    pub fn right(&self) -> &FieldRecord {
        &self.right
    }
}

impl fmt::Debug for FieldConflict {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("FieldConflict")
            .field("item_id", &self.item_id)
            .field("left", &self.left)
            .field("right", &self.right)
            .finish()
    }
}

/// A same-clock item-type conflict.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ItemTypeConflict {
    item_id: ItemId,
    left: ItemType,
    right: ItemType,
    clock: LamportClock,
}

impl ItemTypeConflict {
    fn new(item_id: ItemId, first: ItemType, second: ItemType, clock: LamportClock) -> Self {
        if first.rank() <= second.rank() {
            Self {
                item_id,
                left: first,
                right: second,
                clock,
            }
        } else {
            Self {
                item_id,
                left: second,
                right: first,
                clock,
            }
        }
    }

    /// The affected item.
    #[must_use]
    pub const fn item_id(&self) -> ItemId {
        self.item_id
    }

    /// The lower-ranked type.
    #[must_use]
    pub const fn left(&self) -> ItemType {
        self.left
    }

    /// The higher-ranked type, kept as the deterministic winner.
    #[must_use]
    pub const fn right(&self) -> ItemType {
        self.right
    }

    /// The clock at which the conflict occurred.
    #[must_use]
    pub const fn clock(&self) -> LamportClock {
        self.clock
    }
}

/// A conflict surfaced by a merge.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum ItemConflict {
    /// A field-level conflict.
    Field(FieldConflict),
    /// An item-type conflict.
    ItemType(ItemTypeConflict),
}

impl ItemConflict {
    fn sort_key(&self) -> (u8, u16) {
        match self {
            Self::Field(conflict) => (0, conflict.left.field_id()),
            Self::ItemType(_) => (1, 0),
        }
    }
}

/// The result of merging two items.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct MergeResult {
    /// The deterministically merged item.
    pub item: ItemRecord,
    /// Canonically ordered conflicts; empty when the merge was total.
    pub conflicts: Vec<ItemConflict>,
}

/// A CRDT item record.
#[derive(Clone, Eq, PartialEq)]
pub struct ItemRecord {
    id: ItemId,
    item_type: ItemType,
    schema: u16,
    created_at: u64,
    type_clock: LamportClock,
    deleted: bool,
    deleted_clock: LamportClock,
    fields: BTreeMap<u16, FieldRecord>,
    item_clock: LamportClock,
}

impl ItemRecord {
    /// Create an empty item.
    #[must_use]
    pub fn new(id: ItemId, item_type: ItemType, created_at: u64, actor: ActorId) -> Self {
        let start = LamportClock::new(1, actor);
        Self {
            id,
            item_type,
            schema: ITEM_SCHEMA_VERSION,
            created_at,
            type_clock: start,
            deleted: false,
            deleted_clock: LamportClock::new(0, actor),
            fields: BTreeMap::new(),
            item_clock: start,
        }
    }

    /// The item id.
    #[must_use]
    pub const fn id(&self) -> ItemId {
        self.id
    }

    /// The item type.
    #[must_use]
    pub const fn item_type(&self) -> ItemType {
        self.item_type
    }

    /// The schema version.
    #[must_use]
    pub const fn schema(&self) -> u16 {
        self.schema
    }

    /// Migrate a record from a known older schema, preserving all fields.
    ///
    /// Schema 0 is the legacy pre-registry shape. Migration is additive: field
    /// ids, including custom ids, are retained. Future schemas are refused.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::UnsupportedVersion`] for a future schema and a
    /// validation error if the migrated record is inconsistent.
    pub fn migrate_to_current(mut self, from_schema: u16) -> Result<Self> {
        if from_schema > ITEM_SCHEMA_VERSION {
            return Err(CoreError::new(ErrorCode::UnsupportedVersion));
        }
        if from_schema < ITEM_SCHEMA_VERSION {
            self.schema = ITEM_SCHEMA_VERSION;
        }
        self.validate()?;
        Ok(self)
    }

    /// Creation timestamp supplied by the platform.
    #[must_use]
    pub const fn created_at(&self) -> u64 {
        self.created_at
    }

    /// Whether the item is deleted.
    #[must_use]
    pub const fn is_deleted(&self) -> bool {
        self.deleted
    }

    /// The item-type register clock.
    #[must_use]
    pub const fn type_clock(&self) -> LamportClock {
        self.type_clock
    }

    /// The deletion register clock.
    #[must_use]
    pub const fn deleted_clock(&self) -> LamportClock {
        self.deleted_clock
    }

    /// The highest clock observed for this item.
    #[must_use]
    pub const fn item_clock(&self) -> LamportClock {
        self.item_clock
    }

    /// Number of field records, including tombstones.
    #[must_use]
    pub fn field_count(&self) -> usize {
        self.fields.len()
    }

    /// Look up a field record.
    #[must_use]
    pub fn field(&self, field_id: u16) -> Option<&FieldRecord> {
        self.fields.get(&field_id)
    }

    /// Iterate over field records in field-id order.
    pub fn fields(&self) -> impl Iterator<Item = &FieldRecord> {
        self.fields.values()
    }

    /// Validate the schema and field registry.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::UnsupportedVersion`] for an unknown schema and
    /// [`ErrorCode::CorruptData`] for inconsistent field metadata.
    pub fn validate(&self) -> Result<()> {
        if self.schema != ITEM_SCHEMA_VERSION {
            return Err(CoreError::new(ErrorCode::UnsupportedVersion));
        }
        for (field_id, record) in &self.fields {
            if *field_id != record.field_id {
                return Err(CoreError::new(ErrorCode::CorruptData));
            }
            if !self.item_type.allows_field(*field_id) {
                return Err(CoreError::new(ErrorCode::CorruptData));
            }
            if expected_kind(*field_id) != Some(record.kind) {
                return Err(CoreError::new(ErrorCode::CorruptData));
            }
            if record.clock.counter() == 0 || record.tombstone != record.value.is_none() {
                return Err(CoreError::new(ErrorCode::CorruptData));
            }
            if record.clock > self.item_clock {
                return Err(CoreError::new(ErrorCode::CorruptData));
            }
        }
        if self.type_clock > self.item_clock || self.deleted_clock > self.item_clock {
            return Err(CoreError::new(ErrorCode::CorruptData));
        }
        if self.deleted && self.deleted_clock.counter() == 0 {
            return Err(CoreError::new(ErrorCode::CorruptData));
        }
        Ok(())
    }

    /// Insert a pre-stamped field record. Used by sync and tests.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::CorruptData`] if the record is not valid for this
    /// item type or schema.
    pub fn put_field(&mut self, record: FieldRecord) -> Result<()> {
        if !self.item_type.allows_field(record.field_id)
            || expected_kind(record.field_id) != Some(record.kind)
            || record.clock.counter() == 0
            || record.tombstone != record.value.is_none()
        {
            return Err(CoreError::new(ErrorCode::CorruptData));
        }
        self.item_clock = std::cmp::max(self.item_clock, record.clock);
        self.fields.insert(record.field_id, record);
        Ok(())
    }

    /// Create or replace a field with a fresh local clock.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::InvalidArgument`] for an unknown field/kind or a
    /// clock overflow.
    pub fn set_field(
        &mut self,
        field_id: u16,
        kind: FieldKind,
        value: Envelope,
        actor: ActorId,
    ) -> Result<()> {
        self.check_field(field_id, kind)?;
        let clock = self.next_clock(actor)?;
        self.put_field(FieldRecord::new(field_id, kind, value, clock))
    }

    /// Tombstone a field with a fresh local clock.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::InvalidArgument`] for an unknown field/kind or a
    /// clock overflow.
    pub fn delete_field(&mut self, field_id: u16, kind: FieldKind, actor: ActorId) -> Result<()> {
        self.check_field(field_id, kind)?;
        let clock = self.next_clock(actor)?;
        self.put_field(FieldRecord::tombstone(field_id, kind, clock))
    }

    /// Change the item type if all existing fields remain valid.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::InvalidArgument`] if a field would become invalid
    /// or the clock overflows.
    pub fn set_item_type(&mut self, item_type: ItemType, actor: ActorId) -> Result<()> {
        if self
            .fields
            .keys()
            .any(|field_id| !item_type.allows_field(*field_id))
        {
            return Err(CoreError::new(ErrorCode::InvalidArgument));
        }
        let clock = self.next_clock(actor)?;
        self.item_type = item_type;
        self.type_clock = clock;
        self.item_clock = clock;
        Ok(())
    }

    /// Mark the item deleted. Deletion is sticky.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::LimitExceeded`] on clock overflow.
    pub fn delete(&mut self, actor: ActorId) -> Result<()> {
        if self.deleted {
            return Ok(());
        }
        let clock = self.next_clock(actor)?;
        self.deleted = true;
        self.deleted_clock = clock;
        self.item_clock = clock;
        Ok(())
    }

    /// Merge another replica into this one.
    ///
    /// The merged item is deterministic. A same-clock, same-actor field with
    /// different ciphertext is preserved in [`MergeResult::conflicts`].
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::InvalidArgument`] for different item ids,
    /// [`ErrorCode::UnsupportedVersion`] for different schemas, and
    /// [`ErrorCode::CorruptData`] if a merged record is invalid.
    /// Canonical sync encoding. Contains only sealed envelopes and clocks.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::Internal`] if JSON serialization fails.
    pub fn to_sync_json(&self) -> Result<Vec<u8>> {
        let fields: Vec<serde_json::Value> = self
            .fields
            .values()
            .map(|record| {
                serde_json::json!({
                    "id": record.field_id,
                    "kind": record.kind as u8,
                    "counter": record.clock.counter(),
                    "actor": hex(record.clock.actor().as_bytes()),
                    "tombstone": record.tombstone,
                    "value": record.value.as_ref().map(|envelope| hex(&envelope.encode())),
                })
            })
            .collect();
        let value = serde_json::json!({
            "v": 1,
            "id": hex(self.id.as_bytes()),
            "type": self.item_type as u8,
            "schema": self.schema,
            "created": self.created_at,
            "typeCounter": self.type_clock.counter(),
            "typeActor": hex(self.type_clock.actor().as_bytes()),
            "deleted": self.deleted,
            "deletedCounter": self.deleted_clock.counter(),
            "deletedActor": hex(self.deleted_clock.actor().as_bytes()),
            "itemCounter": self.item_clock.counter(),
            "itemActor": hex(self.item_clock.actor().as_bytes()),
            "fields": fields,
        });
        serde_json::to_vec(&value).map_err(|_| CoreError::new(ErrorCode::Internal))
    }

    /// Decode and validate a canonical sync record.
    ///
    /// # Errors
    ///
    /// Rejects malformed ids, clocks, envelopes and invalid records.
    pub fn from_sync_json(bytes: &[u8]) -> Result<Self> {
        let value: serde_json::Value =
            serde_json::from_slice(bytes).map_err(|_| CoreError::new(ErrorCode::CorruptData))?;
        if value.get("v").and_then(serde_json::Value::as_u64) != Some(1) {
            return Err(CoreError::new(ErrorCode::UnsupportedVersion));
        }
        let id = decode_id(value.get("id"))?;
        let item_type = value
            .get("type")
            .and_then(serde_json::Value::as_u64)
            .and_then(|raw| u8::try_from(raw).ok())
            .and_then(ItemType::from_u8)
            .ok_or_else(|| CoreError::new(ErrorCode::CorruptData))?;
        let schema = value
            .get("schema")
            .and_then(serde_json::Value::as_u64)
            .and_then(|raw| u16::try_from(raw).ok())
            .ok_or_else(|| CoreError::new(ErrorCode::CorruptData))?;
        let created_at = value
            .get("created")
            .and_then(serde_json::Value::as_u64)
            .ok_or_else(|| CoreError::new(ErrorCode::CorruptData))?;
        let type_clock = clock_from(&value, "typeCounter", "typeActor")?;
        let deleted = value
            .get("deleted")
            .and_then(serde_json::Value::as_bool)
            .ok_or_else(|| CoreError::new(ErrorCode::CorruptData))?;
        let deleted_clock = clock_from(&value, "deletedCounter", "deletedActor")?;
        let item_clock = clock_from(&value, "itemCounter", "itemActor")?;
        let mut fields = BTreeMap::new();
        for entry in value
            .get("fields")
            .and_then(serde_json::Value::as_array)
            .ok_or_else(|| CoreError::new(ErrorCode::CorruptData))?
        {
            let field_id = entry
                .get("id")
                .and_then(serde_json::Value::as_u64)
                .and_then(|raw| u16::try_from(raw).ok())
                .ok_or_else(|| CoreError::new(ErrorCode::CorruptData))?;
            let kind = entry
                .get("kind")
                .and_then(serde_json::Value::as_u64)
                .and_then(|raw| u8::try_from(raw).ok())
                .and_then(FieldKind::from_u8)
                .ok_or_else(|| CoreError::new(ErrorCode::CorruptData))?;
            let clock = clock_from(entry, "counter", "actor")?;
            let tombstone = entry
                .get("tombstone")
                .and_then(serde_json::Value::as_bool)
                .ok_or_else(|| CoreError::new(ErrorCode::CorruptData))?;
            let envelope = match entry.get("value").and_then(serde_json::Value::as_str) {
                Some(raw) => Some(Envelope::decode(&decode_hex(raw)?)?),
                None => None,
            };
            fields.insert(
                field_id,
                FieldRecord::from_parts(field_id, kind, envelope, tombstone, clock),
            );
        }
        let record = Self {
            id: ItemId::from_bytes(id),
            item_type,
            schema,
            created_at,
            type_clock,
            deleted,
            deleted_clock,
            fields,
            item_clock,
        };
        record.validate()?;
        Ok(record)
    }

    /// Merge another replica into this one.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::InvalidArgument`] for different item ids,
    /// [`ErrorCode::UnsupportedVersion`] for different schemas, and
    /// [`ErrorCode::CorruptData`] if a merged record is invalid.
    pub fn merge(&self, other: &Self) -> Result<MergeResult> {
        if self.id != other.id {
            return Err(CoreError::new(ErrorCode::InvalidArgument));
        }
        if self.schema != other.schema {
            return Err(CoreError::new(ErrorCode::UnsupportedVersion));
        }

        let mut conflicts = Vec::new();
        let (item_type, type_clock) = match self.type_clock.cmp(&other.type_clock) {
            std::cmp::Ordering::Greater => (self.item_type, self.type_clock),
            std::cmp::Ordering::Less => (other.item_type, other.type_clock),
            std::cmp::Ordering::Equal if self.item_type == other.item_type => {
                (self.item_type, self.type_clock)
            }
            std::cmp::Ordering::Equal => {
                let conflict = ItemTypeConflict::new(
                    self.id,
                    self.item_type,
                    other.item_type,
                    self.type_clock,
                );
                conflicts.push(ItemConflict::ItemType(conflict));
                let winner = if self.item_type.rank() >= other.item_type.rank() {
                    self.item_type
                } else {
                    other.item_type
                };
                (winner, self.type_clock)
            }
        };

        let deleted = self.deleted || other.deleted;
        let deleted_clock = match (self.deleted, other.deleted) {
            (true, true) => std::cmp::max(self.deleted_clock, other.deleted_clock),
            (true, false) => self.deleted_clock,
            (false, true) => other.deleted_clock,
            (false, false) => std::cmp::max(self.deleted_clock, other.deleted_clock),
        };

        let field_ids: BTreeSet<u16> = self
            .fields
            .keys()
            .chain(other.fields.keys())
            .copied()
            .collect();
        let mut fields = BTreeMap::new();
        for field_id in field_ids {
            let (record, conflict) = merge_field(
                self.id,
                self.fields.get(&field_id),
                other.fields.get(&field_id),
            );
            fields.insert(field_id, record);
            if let Some(conflict) = conflict {
                conflicts.push(ItemConflict::Field(conflict));
            }
        }

        let mut item_clock = std::cmp::max(self.item_clock, other.item_clock);
        item_clock = std::cmp::max(item_clock, type_clock);
        item_clock = std::cmp::max(item_clock, deleted_clock);
        for record in fields.values() {
            item_clock = std::cmp::max(item_clock, record.clock);
        }

        let merged = Self {
            id: self.id,
            item_type,
            schema: self.schema,
            created_at: std::cmp::min(self.created_at, other.created_at),
            type_clock,
            deleted,
            deleted_clock,
            fields,
            item_clock,
        };
        merged.validate()?;
        conflicts.sort_by_key(ItemConflict::sort_key);
        Ok(MergeResult {
            item: merged,
            conflicts,
        })
    }

    fn check_field(&self, field_id: u16, kind: FieldKind) -> Result<()> {
        if !self.item_type.allows_field(field_id) || expected_kind(field_id) != Some(kind) {
            return Err(CoreError::new(ErrorCode::InvalidArgument));
        }
        Ok(())
    }

    fn next_clock(&self, actor: ActorId) -> Result<LamportClock> {
        let max = self
            .fields
            .values()
            .map(|record| record.clock.counter())
            .chain([
                self.item_clock.counter(),
                self.type_clock.counter(),
                self.deleted_clock.counter(),
            ])
            .max()
            .unwrap_or(0);
        let counter = max
            .checked_add(1)
            .ok_or_else(|| CoreError::new(ErrorCode::LimitExceeded))?;
        Ok(LamportClock::new(counter, actor))
    }
}

impl fmt::Debug for ItemRecord {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("ItemRecord")
            .field("id", &self.id)
            .field("item_type", &self.item_type)
            .field("schema", &self.schema)
            .field("created_at", &self.created_at)
            .field("deleted", &self.deleted)
            .field("field_count", &self.fields.len())
            .field("item_clock", &self.item_clock)
            .finish()
    }
}

fn merge_field(
    item_id: ItemId,
    local: Option<&FieldRecord>,
    remote: Option<&FieldRecord>,
) -> (FieldRecord, Option<FieldConflict>) {
    match (local, remote) {
        (Some(local), None) => (local.clone(), None),
        (None, Some(remote)) => (remote.clone(), None),
        (None, None) => unreachable!("field id came from at least one item"),
        (Some(local), Some(remote)) => match local.clock.cmp(&remote.clock) {
            std::cmp::Ordering::Greater => (local.clone(), None),
            std::cmp::Ordering::Less => (remote.clone(), None),
            std::cmp::Ordering::Equal if local == remote => (local.clone(), None),
            std::cmp::Ordering::Equal => {
                if local.tombstone != remote.tombstone {
                    return if local.tombstone {
                        (local.clone(), None)
                    } else {
                        (remote.clone(), None)
                    };
                }
                let conflict = FieldConflict::new(item_id, local.clone(), remote.clone());
                let winner = conflict.right().clone();
                (winner, Some(conflict))
            }
        },
    }
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

fn decode_hex(value: &str) -> Result<Vec<u8>> {
    if value.len() % 2 != 0 {
        return Err(CoreError::new(ErrorCode::CorruptData));
    }
    value
        .as_bytes()
        .chunks_exact(2)
        .map(|pair| {
            u8::from_str_radix(
                std::str::from_utf8(pair).map_err(|_| CoreError::new(ErrorCode::CorruptData))?,
                16,
            )
            .map_err(|_| CoreError::new(ErrorCode::CorruptData))
        })
        .collect()
}

fn decode_id(value: Option<&serde_json::Value>) -> Result<[u8; 16]> {
    decode_hex(
        value
            .and_then(serde_json::Value::as_str)
            .ok_or_else(|| CoreError::new(ErrorCode::CorruptData))?,
    )?
    .try_into()
    .map_err(|_| CoreError::new(ErrorCode::CorruptData))
}

fn clock_from(value: &serde_json::Value, counter: &str, actor: &str) -> Result<LamportClock> {
    let counter = value
        .get(counter)
        .and_then(serde_json::Value::as_u64)
        .ok_or_else(|| CoreError::new(ErrorCode::CorruptData))?;
    let actor: [u8; 16] = decode_hex(
        value
            .get(actor)
            .and_then(serde_json::Value::as_str)
            .ok_or_else(|| CoreError::new(ErrorCode::CorruptData))?,
    )?
    .try_into()
    .map_err(|_| CoreError::new(ErrorCode::CorruptData))?;
    Ok(LamportClock::new(counter, ActorId::from_bytes(actor)))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::envelope::{Aad, Envelope};
    use crate::secret::SecretBytes;

    fn actor(byte: u8) -> ActorId {
        ActorId::from_bytes([byte; 16])
    }

    fn item_id() -> ItemId {
        ItemId::from_bytes([9u8; 16])
    }

    fn envelope(field_id: u16, counter: u64, plaintext: &[u8]) -> Envelope {
        let aad = Aad::field(item_id(), field_id, counter, false);
        Envelope::seal(&SecretBytes::from_slice(&[0x42u8; 32]), &aad, plaintext)
            .expect("seal field")
    }

    fn field(
        field_id: u16,
        kind: FieldKind,
        counter: u64,
        actor_byte: u8,
        plaintext: &[u8],
    ) -> FieldRecord {
        FieldRecord::new(
            field_id,
            kind,
            envelope(field_id, counter, plaintext),
            LamportClock::new(counter, actor(actor_byte)),
        )
    }

    fn tombstone(field_id: u16, kind: FieldKind, counter: u64, actor_byte: u8) -> FieldRecord {
        FieldRecord::tombstone(
            field_id,
            kind,
            LamportClock::new(counter, actor(actor_byte)),
        )
    }

    fn base_item(actor_byte: u8) -> ItemRecord {
        ItemRecord::new(item_id(), ItemType::Login, 100, actor(actor_byte))
    }

    #[test]
    fn field_registry_rejects_wrong_type_or_kind() {
        let mut item = base_item(1);
        assert_eq!(
            item.set_field(
                FIELD_CARD_NUMBER,
                FieldKind::CardNumber,
                envelope(FIELD_CARD_NUMBER, 2, b"card"),
                actor(1)
            )
            .unwrap_err()
            .code(),
            ErrorCode::InvalidArgument
        );
        assert_eq!(
            item.set_field(
                FIELD_PASSWORD,
                FieldKind::Text,
                envelope(FIELD_PASSWORD, 2, b"pw"),
                actor(1)
            )
            .unwrap_err()
            .code(),
            ErrorCode::InvalidArgument
        );
        item.put_field(field(FIELD_PASSWORD, FieldKind::Secret, 2, 1, b"pw"))
            .expect("valid field");
        assert!(item.validate().is_ok());
    }

    #[test]
    fn all_six_item_types_validate_their_standard_fields() {
        let cases = [
            (ItemType::Login, FIELD_USERNAME, FieldKind::Text),
            (ItemType::Totp, FIELD_TOTP, FieldKind::Totp),
            (ItemType::Note, FIELD_NOTES, FieldKind::Note),
            (ItemType::Card, FIELD_CARD_NUMBER, FieldKind::CardNumber),
            (
                ItemType::Identity,
                FIELD_IDENTITY_NAME,
                FieldKind::PersonName,
            ),
            (
                ItemType::SshKey,
                FIELD_SSH_PUBLIC_KEY,
                FieldKind::SshPublicKey,
            ),
        ];
        for (item_type, field_id, kind) in cases {
            let mut item = ItemRecord::new(item_id(), item_type, 100, actor(1));
            item.put_field(field(field_id, kind, 2, 1, b"value"))
                .expect("valid field");
            assert!(item.validate().is_ok(), "{item_type:?}");
        }

        let mut note = ItemRecord::new(item_id(), ItemType::Note, 100, actor(1));
        assert_eq!(
            note.put_field(field(FIELD_PASSWORD, FieldKind::Secret, 2, 1, b"pw"))
                .unwrap_err()
                .code(),
            ErrorCode::CorruptData
        );

        let mut login = ItemRecord::new(item_id(), ItemType::Login, 100, actor(1));
        login
            .put_field(field(
                CUSTOM_FIELD_ID_BASE,
                FieldKind::Custom,
                2,
                1,
                b"custom",
            ))
            .expect("custom field");
        assert!(login.validate().is_ok());
    }

    #[test]
    fn local_writes_advance_the_clock() {
        let mut item = base_item(1);
        item.set_field(
            FIELD_USERNAME,
            FieldKind::Text,
            envelope(FIELD_USERNAME, 2, b"alice"),
            actor(1),
        )
        .expect("set");
        let username_clock = item.field(FIELD_USERNAME).expect("field").clock();
        assert_eq!(username_clock.counter(), 2);
        item.delete_field(FIELD_USERNAME, FieldKind::Text, actor(1))
            .expect("delete");
        assert!(item.field(FIELD_USERNAME).expect("field").is_tombstone());
        assert!(item.item_clock().counter() >= 3);
    }

    #[test]
    fn higher_clock_wins_concurrent_edit() {
        let mut local = base_item(1);
        local
            .put_field(field(FIELD_PASSWORD, FieldKind::Secret, 2, 1, b"local"))
            .expect("put");
        let mut remote = base_item(2);
        remote
            .put_field(field(FIELD_PASSWORD, FieldKind::Secret, 3, 2, b"remote"))
            .expect("put");

        let merged = local.merge(&remote).expect("merge");
        assert!(
            merged.item.field(FIELD_PASSWORD).expect("field").value()
                == remote.field(FIELD_PASSWORD).expect("field").value()
        );
        assert!(merged.conflicts.is_empty());
    }

    #[test]
    fn higher_actor_wins_same_counter() {
        let mut local = base_item(1);
        local
            .put_field(field(FIELD_PASSWORD, FieldKind::Secret, 2, 1, b"local"))
            .expect("put");
        let mut remote = base_item(2);
        remote
            .put_field(field(FIELD_PASSWORD, FieldKind::Secret, 2, 2, b"remote"))
            .expect("put");

        let merged = local.merge(&remote).expect("merge");
        assert!(
            merged.item.field(FIELD_PASSWORD).expect("field").value()
                == remote.field(FIELD_PASSWORD).expect("field").value()
        );
        assert!(merged.conflicts.is_empty());
    }

    #[test]
    fn tombstone_wins_at_equal_clock() {
        let mut local = base_item(1);
        local
            .put_field(field(FIELD_PASSWORD, FieldKind::Secret, 2, 1, b"secret"))
            .expect("put");
        let mut remote = base_item(1);
        remote
            .put_field(tombstone(FIELD_PASSWORD, FieldKind::Secret, 2, 1))
            .expect("tombstone");

        let merged = local.merge(&remote).expect("merge");
        assert!(merged
            .item
            .field(FIELD_PASSWORD)
            .expect("field")
            .is_tombstone());
        assert!(merged.conflicts.is_empty());
    }

    #[test]
    fn newer_edit_beats_older_tombstone() {
        let mut local = base_item(1);
        local
            .put_field(tombstone(FIELD_PASSWORD, FieldKind::Secret, 2, 1))
            .expect("tombstone");
        let mut remote = base_item(2);
        remote
            .put_field(field(FIELD_PASSWORD, FieldKind::Secret, 3, 2, b"new"))
            .expect("put");

        let merged = local.merge(&remote).expect("merge");
        assert!(!merged
            .item
            .field(FIELD_PASSWORD)
            .expect("field")
            .is_tombstone());
        assert!(
            merged.item.field(FIELD_PASSWORD).expect("field").value()
                == remote.field(FIELD_PASSWORD).expect("field").value()
        );
    }

    #[test]
    fn same_clock_same_actor_conflict_is_preserved_deterministically() {
        let mut local = base_item(1);
        local
            .put_field(field(FIELD_PASSWORD, FieldKind::Secret, 2, 1, b"first"))
            .expect("put");
        let mut remote = base_item(1);
        remote
            .put_field(field(FIELD_PASSWORD, FieldKind::Secret, 2, 1, b"second"))
            .expect("put");

        let forward = local.merge(&remote).expect("merge");
        let reverse = remote.merge(&local).expect("merge");
        assert_eq!(forward.item, reverse.item);
        assert_eq!(forward.conflicts.len(), 1);
        assert_eq!(reverse.conflicts.len(), 1);
        assert_eq!(forward.conflicts, reverse.conflicts);
        assert!(matches!(forward.conflicts[0], ItemConflict::Field(_)));
    }

    #[test]
    fn deletion_is_sticky_even_against_a_higher_edit() {
        let mut deleted = base_item(1);
        deleted.delete(actor(1)).expect("delete");
        let mut edited = base_item(2);
        edited
            .put_field(field(FIELD_PASSWORD, FieldKind::Secret, 9, 2, b"late"))
            .expect("put");

        let merged = deleted.merge(&edited).expect("merge");
        assert!(merged.item.is_deleted());
    }

    #[test]
    fn merge_is_commutative_associative_and_idempotent() {
        let mut state = 0x1234_5678_9abc_def0u64;
        for history in 0..10_000u32 {
            let first = random_item(&mut state, history);
            let second = random_item(&mut state, history);
            let third = random_item(&mut state, history);

            let forward = first.merge(&second).expect("merge").item;
            let reverse = second.merge(&first).expect("merge").item;
            assert_eq!(forward, reverse, "commutativity at {history}");

            let left = first.merge(&second).expect("merge").item;
            let left = left.merge(&third).expect("merge").item;
            let right = second.merge(&third).expect("merge").item;
            let right = first.merge(&right).expect("merge").item;
            assert_eq!(left, right, "associativity at {history}");

            let same = first.merge(&first).expect("merge").item;
            assert_eq!(same, first, "idempotence at {history}");
        }
    }

    #[test]
    fn sync_json_round_trips_encrypted_record() {
        let mut record = ItemRecord::new(item_id(), ItemType::Login, 10, actor(2));
        record
            .put_field(field(FIELD_TITLE, FieldKind::Text, 1, 2, b"Example"))
            .expect("field");
        let encoded = record.to_sync_json().expect("encode");
        let decoded = ItemRecord::from_sync_json(&encoded).expect("decode");
        assert_eq!(decoded.id(), record.id());
        assert_eq!(decoded.field_count(), 1);
        assert_eq!(
            decoded.field(FIELD_TITLE).expect("title").kind(),
            FieldKind::Text
        );
    }

    #[test]
    fn rejects_different_ids_and_schemas() {
        let first = base_item(1);
        let mut other = ItemRecord::new(
            ItemId::from_bytes([8u8; 16]),
            ItemType::Login,
            100,
            actor(2),
        );
        assert_eq!(
            first.merge(&other).unwrap_err().code(),
            ErrorCode::InvalidArgument
        );

        other = ItemRecord::new(item_id(), ItemType::Login, 100, actor(2));
        other.schema = 2;
        assert_eq!(
            first.merge(&other).unwrap_err().code(),
            ErrorCode::UnsupportedVersion
        );
    }

    fn random_item(state: &mut u64, history: u32) -> ItemRecord {
        let actor_byte = (next(state) % 3 + 1) as u8;
        let mut item = base_item(actor_byte);
        let fields = [
            (FIELD_USERNAME, FieldKind::Text),
            (FIELD_PASSWORD, FieldKind::Secret),
            (FIELD_URL, FieldKind::Url),
        ];
        for (field_id, kind) in fields {
            let counter = (next(state) % 5) + 2;
            let actor_byte = (next(state) % 3 + 1) as u8;
            let record = if next(state) % 3 == 0 {
                tombstone(field_id, kind, counter, actor_byte)
            } else {
                let plaintext = format!("{history}-{field_id}-{counter}");
                field(field_id, kind, counter, actor_byte, plaintext.as_bytes())
            };
            item.put_field(record).expect("random field");
        }
        if next(state) % 7 == 0 {
            item.delete(actor(actor_byte)).expect("delete");
        }
        item
    }

    fn next(state: &mut u64) -> u64 {
        *state = state
            .wrapping_mul(6_364_136_223_846_793_005)
            .wrapping_add(1_442_695_040_888_963_407);
        *state
    }
}
