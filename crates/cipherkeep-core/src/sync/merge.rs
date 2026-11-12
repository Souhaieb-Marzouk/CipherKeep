//! Cross-device CRDT merge and conflict reporting.

use std::collections::BTreeMap;

use crate::error::{CoreError, ErrorCode, Result};
use crate::item::{FieldConflict, FieldRecord, ItemConflict, ItemRecord};

/// Outcome of merging one remote item.
#[derive(Clone, Debug)]
pub struct MergeOutcome {
    /// Deterministically merged record.
    pub record: ItemRecord,
    /// Conflicts preserved for UI resolution.
    pub conflicts: Vec<ItemConflict>,
    /// Whether the local record changed.
    pub changed: bool,
}

/// Per-vault item merge engine.
#[derive(Default)]
pub struct MergeEngine {
    records: BTreeMap<[u8; 16], ItemRecord>,
    conflicts: BTreeMap<([u8; 16], u16), FieldConflict>,
}

/// Which side of a field conflict to keep.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ConflictChoice {
    /// Keep the canonical left value.
    Left,
    /// Keep the canonical right value.
    Right,
}

impl MergeEngine {
    /// Create an empty engine.
    #[must_use]
    pub const fn new() -> Self {
        Self {
            records: BTreeMap::new(),
            conflicts: BTreeMap::new(),
        }
    }

    /// Insert a local record without generating conflicts.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::Conflict`] if the item already exists.
    pub fn insert_local(&mut self, record: ItemRecord) -> Result<()> {
        if self.records.contains_key(record.id().as_bytes()) {
            return Err(CoreError::new(ErrorCode::Conflict));
        }
        self.records.insert(*record.id().as_bytes(), record);
        Ok(())
    }

    /// Merge a remote record. Missing local records are inserted.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::CorruptData`] if the merged record is invalid.
    pub fn merge_remote(&mut self, remote: &ItemRecord) -> Result<MergeOutcome> {
        let key = *remote.id().as_bytes();
        let Some(local) = self.records.get(&key) else {
            self.records.insert(key, remote.clone());
            return Ok(MergeOutcome {
                record: remote.clone(),
                conflicts: Vec::new(),
                changed: true,
            });
        };
        let result = local.merge(remote)?;
        let changed = result.item != *local;
        if changed {
            self.records.insert(key, result.item.clone());
        }
        for conflict in &result.conflicts {
            if let ItemConflict::Field(field) = conflict {
                self.conflicts
                    .insert((key, field.left().field_id()), field.clone());
            }
        }
        Ok(MergeOutcome {
            record: result.item,
            conflicts: result.conflicts,
            changed,
        })
    }

    /// Resolve one field conflict by keeping a side.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::NotFound`] when no such conflict exists.
    pub fn resolve_field(
        &mut self,
        item: &[u8; 16],
        field_id: u16,
        choice: ConflictChoice,
    ) -> Result<ItemRecord> {
        let conflict = self
            .conflicts
            .remove(&(*item, field_id))
            .ok_or_else(|| CoreError::new(ErrorCode::NotFound))?;
        let record = match choice {
            ConflictChoice::Left => conflict.left().clone(),
            ConflictChoice::Right => conflict.right().clone(),
        };
        let entry = self
            .records
            .get_mut(item)
            .ok_or_else(|| CoreError::new(ErrorCode::NotFound))?;
        entry.put_field(record)?;
        Ok(entry.clone())
    }

    /// Resolve one field conflict by choosing the left or right record.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::NotFound`] if the conflict does not exist.
    pub fn resolve_field_conflict(
        local: &ItemRecord,
        remote: &ItemRecord,
        field_id: u16,
        keep_right: bool,
    ) -> Result<ItemRecord> {
        let result = local.merge(remote)?;
        let mut merged = result.item;
        let conflict = result
            .conflicts
            .iter()
            .find_map(|conflict| match conflict {
                ItemConflict::Field(field) if field.left().field_id() == field_id => Some(field),
                _ => None,
            })
            .ok_or_else(|| CoreError::new(ErrorCode::NotFound))?;
        let chosen: FieldRecord = if keep_right {
            conflict.right().clone()
        } else {
            conflict.left().clone()
        };
        merged.put_field(chosen)?;
        Ok(merged)
    }

    /// Canonical sync JSON for every tracked record.
    ///
    /// # Errors
    ///
    /// Returns the first serialization error.
    pub fn records_for_sync(&self) -> Result<Vec<Vec<u8>>> {
        self.records
            .values()
            .map(ItemRecord::to_sync_json)
            .collect()
    }

    /// Number of unresolved field conflicts.
    #[must_use]
    pub fn conflict_count(&self) -> usize {
        self.conflicts.len()
    }

    /// Current record for an item.
    #[must_use]
    pub fn get(&self, item: &[u8; 16]) -> Option<&ItemRecord> {
        self.records.get(item)
    }

    /// Number of tracked records.
    #[must_use]
    pub fn len(&self) -> usize {
        self.records.len()
    }

    /// Whether no records are tracked.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.records.is_empty()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crdt::ActorId;
    use crate::item::{ItemRecord, ItemType};

    #[test]
    fn inserts_and_merges_remote_records() {
        let id = crate::keys::ItemId::from_bytes([1u8; 16]);
        let local = ItemRecord::new(id, ItemType::Login, 10, ActorId::from_bytes([2u8; 16]));
        let remote = ItemRecord::new(id, ItemType::Login, 10, ActorId::from_bytes([3u8; 16]));
        let mut engine = MergeEngine::new();
        engine.insert_local(local).expect("local");
        let outcome = engine.merge_remote(&remote).expect("merge");
        assert_eq!(engine.len(), 1);
        assert!(outcome.changed);
    }

    #[test]
    fn missing_remote_is_inserted() {
        let record = ItemRecord::new(
            crate::keys::ItemId::from_bytes([4u8; 16]),
            ItemType::Login,
            10,
            ActorId::from_bytes([5u8; 16]),
        );
        let mut engine = MergeEngine::new();
        let outcome = engine.merge_remote(&record).expect("merge");
        assert!(outcome.changed);
        assert_eq!(engine.len(), 1);
    }
}
