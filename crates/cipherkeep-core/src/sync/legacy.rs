//! Migration from legacy platform JSON items into encrypted CRDT records.

use serde_json::Value;

use crate::crdt::{ActorId, LamportClock};
use crate::envelope::{Aad, Envelope};
use crate::error::{CoreError, ErrorCode, Result};
use crate::item::{
    FieldKind, FieldRecord, ItemRecord, ItemType, CUSTOM_FIELD_ID_BASE, FIELD_NOTES,
    FIELD_PASSWORD, FIELD_TITLE, FIELD_TOTP, FIELD_URL, FIELD_USERNAME,
};
use crate::keys::{ItemId, ItemKey};

const LEGACY_FOLDER: u16 = CUSTOM_FIELD_ID_BASE + 1;
const LEGACY_TAGS: u16 = CUSTOM_FIELD_ID_BASE + 2;
const LEGACY_FAVORITE: u16 = CUSTOM_FIELD_ID_BASE + 3;
const LEGACY_AUTOFILL: u16 = CUSTOM_FIELD_ID_BASE + 4;

/// Convert a legacy item JSON payload into an encrypted `ItemRecord`.
///
/// # Errors
///
/// Rejects malformed JSON, missing items, empty titles and encryption failures.
pub fn migrate_legacy_item(
    json: &[u8],
    id: ItemId,
    item_key: &ItemKey,
    actor: ActorId,
    created_at: u64,
    counter_start: u64,
) -> Result<ItemRecord> {
    let value: Value =
        serde_json::from_slice(json).map_err(|_| CoreError::new(ErrorCode::CorruptData))?;
    let title = required(&value, "title")?;
    if title.is_empty() {
        return Err(CoreError::new(ErrorCode::InvalidArgument));
    }
    let item_type = if value
        .get("type")
        .and_then(Value::as_str)
        .is_some_and(|kind| kind == "totp")
        || value
            .get("totpUri")
            .and_then(Value::as_str)
            .is_some_and(|uri| !uri.is_empty())
    {
        ItemType::Totp
    } else {
        ItemType::Login
    };
    let mut record = ItemRecord::new(id, item_type, created_at, actor);
    let mut counter = counter_start.max(1);
    let key = item_key.secret();
    let mut add = |field_id: u16, kind: FieldKind, text: &str| -> Result<()> {
        if text.is_empty() {
            return Ok(());
        }
        let clock = LamportClock::new(counter, actor);
        let envelope = Envelope::seal(
            key,
            &Aad::field(id, field_id, counter, false),
            text.as_bytes(),
        )?;
        record.put_field(FieldRecord::new(field_id, kind, envelope, clock))?;
        counter += 1;
        Ok(())
    };
    add(FIELD_TITLE, FieldKind::Text, &title)?;
    add(FIELD_NOTES, FieldKind::Note, &string(&value, "notes"))?;
    if item_type == ItemType::Login {
        add(FIELD_USERNAME, FieldKind::Text, &string(&value, "username"))?;
        add(
            FIELD_PASSWORD,
            FieldKind::Secret,
            &string(&value, "password"),
        )?;
        add(FIELD_URL, FieldKind::Url, &string(&value, "url"))?;
    }
    add(FIELD_TOTP, FieldKind::Totp, &string(&value, "totpUri"))?;
    add(LEGACY_FOLDER, FieldKind::Custom, &string(&value, "folder"))?;
    let tags = value
        .get("tags")
        .and_then(Value::as_array)
        .map(|tags| {
            tags.iter()
                .filter_map(Value::as_str)
                .collect::<Vec<_>>()
                .join(",")
        })
        .unwrap_or_default();
    add(LEGACY_TAGS, FieldKind::Custom, &tags)?;
    if value
        .get("favorite")
        .and_then(Value::as_bool)
        .unwrap_or(false)
    {
        add(LEGACY_FAVORITE, FieldKind::Custom, "true")?;
    }
    if value
        .get("autofillAllowed")
        .and_then(Value::as_bool)
        .unwrap_or(false)
    {
        add(LEGACY_AUTOFILL, FieldKind::Custom, "true")?;
    }
    Ok(record)
}

fn required(value: &Value, key: &str) -> Result<String> {
    value
        .get(key)
        .and_then(Value::as_str)
        .map(ToString::to_string)
        .ok_or_else(|| CoreError::new(ErrorCode::InvalidArgument))
}

fn string(value: &Value, key: &str) -> String {
    value
        .get(key)
        .and_then(Value::as_str)
        .unwrap_or("")
        .to_string()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::envelope::Aad;
    use crate::item::FIELD_TITLE;

    #[test]
    fn migrates_legacy_json_into_encrypted_fields() {
        let key = ItemKey::generate().expect("key");
        let id = ItemId::from_bytes([1u8; 16]);
        let actor = ActorId::from_bytes([2u8; 16]);
        let json = br#"{"v":1,"type":"login","title":"Example","username":"u","password":"p","url":"https://example.com","folder":"Work","tags":["a","b"],"favorite":true,"autofillAllowed":true}"#;
        let record = migrate_legacy_item(json, id, &key, actor, 100, 1).expect("migrate");
        assert_eq!(record.field_count(), 8);
        assert_eq!(
            record.field(FIELD_TITLE).expect("title").kind(),
            FieldKind::Text
        );
        let encoded = record.to_sync_json().expect("encode");
        let decoded = ItemRecord::from_sync_json(&encoded).expect("decode");
        assert_eq!(decoded.field_count(), record.field_count());
        let envelope = record
            .field(FIELD_PASSWORD)
            .expect("password")
            .value()
            .expect("value");
        let plaintext = envelope
            .open(key.secret(), &Aad::field(id, FIELD_PASSWORD, 3, false))
            .expect("open");
        assert_eq!(plaintext.expose_secret(), b"p");
    }
}
