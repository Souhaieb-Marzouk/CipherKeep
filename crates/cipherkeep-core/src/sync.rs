//! Storage-agnostic sync contract and opaque, padded batch envelope.
//!
//! Adapters see only this type: vault/device ids, a version vector, item ids,
//! blob versions, size buckets and already-sealed blobs. No item fields,
//! titles, URLs or plaintext cross the adapter boundary.

pub mod local;

use std::collections::{BTreeMap, BTreeSet};

use serde_json::{json, Value};
use zeroize::Zeroizing;

use crate::envelope::{Aad, Envelope};
use crate::error::{CoreError, ErrorCode, Result};
use crate::keys::{DeviceId, ItemId, VaultId};
use crate::secret::SecretBytes;

/// Blob size bucket in bytes.
pub const SYNC_PAD_BLOCK: usize = 4_096;
/// Maximum serialized batch size.
pub const MAX_SYNC_BATCH_BYTES: usize = 64 * 1024 * 1024;
/// Maximum blobs in a batch.
pub const MAX_SYNC_BLOBS: usize = 10_000;
/// Maximum version-vector entries.
pub const MAX_SYNC_DEVICES: usize = 1_024;

/// Adapter capabilities.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct SyncCapabilities {
    /// Adapter supports conditional writes.
    pub conditional_put: bool,
    /// Adapter supports deletion.
    pub delete: bool,
    /// Adapter can list batches after a version.
    pub list: bool,
}

/// A device's monotonic sync counter.
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct SyncVersionVector {
    entries: BTreeMap<[u8; 16], u64>,
}

impl SyncVersionVector {
    /// Insert/replace a device counter.
    pub fn set(&mut self, device: DeviceId, version: u64) {
        self.entries.insert(*device.as_bytes(), version);
    }

    /// Counter for a device, defaulting to zero.
    #[must_use]
    pub fn get(&self, device: DeviceId) -> u64 {
        self.entries.get(device.as_bytes()).copied().unwrap_or(0)
    }

    /// Reject a batch that does not advance at least one counter.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::Conflict`] when every counter is equal or older.
    pub fn require_newer(&self, incoming: &Self) -> Result<()> {
        let newer = incoming
            .entries
            .iter()
            .any(|(device, version)| self.entries.get(device).copied().unwrap_or(0) < *version);
        if newer {
            Ok(())
        } else {
            Err(CoreError::new(ErrorCode::Conflict))
        }
    }

    fn encode(&self) -> Vec<Value> {
        self.entries
            .iter()
            .map(|(device, version)| json!({ "device": hex(device), "version": version }))
            .collect()
    }
}

/// Public manifest for one sealed blob. Contains no item metadata.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SyncManifest {
    /// Item id.
    pub item_id: [u8; 16],
    /// Device-local blob version.
    pub blob_version: u64,
    /// Padded plaintext size bucket.
    pub size_bucket: u32,
    /// Sealed envelope bytes.
    pub ciphertext: Vec<u8>,
}

/// One sync batch.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SyncBatch {
    /// Vault id.
    pub vault_id: [u8; 16],
    /// Producing device id.
    pub device_id: [u8; 16],
    /// Version vector.
    pub versions: SyncVersionVector,
    /// Blobs in deterministic item-id order.
    pub manifests: Vec<SyncManifest>,
}

impl SyncBatch {
    /// Pad plaintext to a deterministic 4 KiB bucket, seal it and add it.
    ///
    /// # Errors
    ///
    /// Rejects oversize plaintext, invalid versions and duplicate items.
    pub fn seal_blob(
        &mut self,
        key: &SecretBytes,
        item_id: ItemId,
        blob_version: u64,
        plaintext: &[u8],
    ) -> Result<()> {
        if blob_version == 0 {
            return Err(CoreError::new(ErrorCode::InvalidArgument));
        }
        if self
            .manifests
            .iter()
            .any(|manifest| manifest.item_id == *item_id.as_bytes())
        {
            return Err(CoreError::new(ErrorCode::Conflict));
        }
        let bucket = padded_len(plaintext.len())?;
        let mut padded = Zeroizing::new(vec![0u8; bucket]);
        padded[8..8 + plaintext.len()].copy_from_slice(plaintext);
        padded[4..8].copy_from_slice(
            &u32::try_from(plaintext.len())
                .map_err(|_| CoreError::new(ErrorCode::LimitExceeded))?
                .to_be_bytes(),
        );
        let aad = Aad::sync(
            VaultId::from_bytes(self.vault_id),
            DeviceId::from_bytes(self.device_id),
            blob_version,
        );
        let envelope = Envelope::seal(key, &aad, &padded)?;
        self.manifests.push(SyncManifest {
            item_id: *item_id.as_bytes(),
            blob_version,
            size_bucket: u32::try_from(bucket)
                .map_err(|_| CoreError::new(ErrorCode::LimitExceeded))?,
            ciphertext: envelope.encode(),
        });
        self.manifests.sort_by_key(|manifest| manifest.item_id);
        Ok(())
    }

    /// Open and unpad one manifest.
    ///
    /// # Errors
    ///
    /// Fails closed on wrong AAD/key, tampering or malformed padding.
    pub fn open_blob(&self, key: &SecretBytes, manifest: &SyncManifest) -> Result<SecretBytes> {
        if manifest.size_bucket as usize != padded_len_for_bucket(manifest.size_bucket)? {
            return Err(CoreError::new(ErrorCode::CorruptData));
        }
        let envelope = Envelope::decode(&manifest.ciphertext)?;
        let aad = Aad::sync(
            VaultId::from_bytes(self.vault_id),
            DeviceId::from_bytes(self.device_id),
            manifest.blob_version,
        );
        let plaintext = envelope.open(key, &aad)?;
        let padded = plaintext.expose_secret();
        if padded.len() < 8 {
            return Err(CoreError::new(ErrorCode::CorruptData));
        }
        let length = u32::from_be_bytes(
            padded[4..8]
                .try_into()
                .map_err(|_| CoreError::new(ErrorCode::CorruptData))?,
        ) as usize;
        if length > padded.len() - 8 || padded.len() != manifest.size_bucket as usize {
            return Err(CoreError::new(ErrorCode::CorruptData));
        }
        Ok(SecretBytes::from_slice(&padded[8..8 + length]))
    }

    /// Deterministic JSON encoding for adapters.
    ///
    /// # Errors
    ///
    /// Rejects duplicate items and batches above the size limit.
    pub fn encode(&self) -> Result<Vec<u8>> {
        self.validate()?;
        let value = json!({
            "v": 1,
            "vault": hex(&self.vault_id),
            "device": hex(&self.device_id),
            "versions": self.versions.encode(),
            "manifests": self.manifests.iter().map(|manifest| json!({
                "item": hex(&manifest.item_id),
                "version": manifest.blob_version,
                "bucket": manifest.size_bucket,
                "ciphertext": hex(&manifest.ciphertext),
            })).collect::<Vec<_>>(),
        });
        let bytes = serde_json::to_vec(&value).map_err(|_| CoreError::new(ErrorCode::Internal))?;
        if bytes.len() > MAX_SYNC_BATCH_BYTES {
            return Err(CoreError::new(ErrorCode::LimitExceeded));
        }
        Ok(bytes)
    }

    /// Decode and validate an adapter batch.
    ///
    /// # Errors
    ///
    /// Rejects malformed, oversize or duplicate batches.
    pub fn decode(bytes: &[u8]) -> Result<Self> {
        if bytes.len() > MAX_SYNC_BATCH_BYTES {
            return Err(CoreError::new(ErrorCode::LimitExceeded));
        }
        let value: Value =
            serde_json::from_slice(bytes).map_err(|_| CoreError::new(ErrorCode::CorruptData))?;
        let vault_id = decode_id(value.get("vault"))?;
        let device_id = decode_id(value.get("device"))?;
        let mut versions = SyncVersionVector::default();
        for entry in value
            .get("versions")
            .and_then(Value::as_array)
            .ok_or_else(|| CoreError::new(ErrorCode::CorruptData))?
        {
            let device = decode_id(entry.get("device"))?;
            let version = entry
                .get("version")
                .and_then(Value::as_u64)
                .ok_or_else(|| CoreError::new(ErrorCode::CorruptData))?;
            versions.set(DeviceId::from_bytes(device), version);
        }
        if versions.entries.len() > MAX_SYNC_DEVICES {
            return Err(CoreError::new(ErrorCode::LimitExceeded));
        }
        let mut manifests = Vec::new();
        for entry in value
            .get("manifests")
            .and_then(Value::as_array)
            .ok_or_else(|| CoreError::new(ErrorCode::CorruptData))?
        {
            let item_id = decode_id(entry.get("item"))?;
            let blob_version = entry
                .get("version")
                .and_then(Value::as_u64)
                .ok_or_else(|| CoreError::new(ErrorCode::CorruptData))?;
            let size_bucket = entry
                .get("bucket")
                .and_then(Value::as_u64)
                .and_then(|value| u32::try_from(value).ok())
                .ok_or_else(|| CoreError::new(ErrorCode::CorruptData))?;
            let ciphertext = decode_hex(
                entry
                    .get("ciphertext")
                    .and_then(Value::as_str)
                    .ok_or_else(|| CoreError::new(ErrorCode::CorruptData))?,
            )?;
            manifests.push(SyncManifest {
                item_id,
                blob_version,
                size_bucket,
                ciphertext,
            });
        }
        let batch = Self {
            vault_id,
            device_id,
            versions,
            manifests,
        };
        batch.validate()?;
        Ok(batch)
    }

    fn validate(&self) -> Result<()> {
        if self.manifests.len() > MAX_SYNC_BLOBS {
            return Err(CoreError::new(ErrorCode::LimitExceeded));
        }
        let mut seen = BTreeSet::new();
        for manifest in &self.manifests {
            if manifest.blob_version == 0 || !seen.insert(manifest.item_id) {
                return Err(CoreError::new(ErrorCode::CorruptData));
            }
            if padded_len_for_bucket(manifest.size_bucket)? != manifest.size_bucket as usize {
                return Err(CoreError::new(ErrorCode::CorruptData));
            }
            if manifest.ciphertext.len() > MAX_SYNC_BATCH_BYTES {
                return Err(CoreError::new(ErrorCode::LimitExceeded));
            }
        }
        Ok(())
    }
}

/// Adapter contract. Implementations only transport opaque [`SyncBatch`] values.
pub trait SyncAdapter {
    /// Stable adapter identifier.
    fn adapter_id(&self) -> &str;
    /// Adapter capabilities.
    fn capabilities(&self) -> SyncCapabilities;
    /// Pull batches newer than `since`.
    fn pull(&self, since: &SyncVersionVector) -> Result<Vec<SyncBatch>>;
    /// Push one batch. Must be idempotent for an identical version.
    fn push(&mut self, batch: &SyncBatch) -> Result<()>;
}

/// In-memory adapter used by contract tests.
#[derive(Default)]
pub struct MemorySyncAdapter {
    batches: Vec<SyncBatch>,
}

impl MemorySyncAdapter {
    /// Create an empty adapter.
    #[must_use]
    pub const fn new() -> Self {
        Self {
            batches: Vec::new(),
        }
    }
}

impl SyncAdapter for MemorySyncAdapter {
    fn adapter_id(&self) -> &str {
        "memory"
    }

    fn capabilities(&self) -> SyncCapabilities {
        SyncCapabilities {
            conditional_put: true,
            delete: true,
            list: true,
        }
    }

    fn pull(&self, since: &SyncVersionVector) -> Result<Vec<SyncBatch>> {
        Ok(self
            .batches
            .iter()
            .filter(|batch| since.require_newer(&batch.versions).is_ok())
            .cloned()
            .collect())
    }

    fn push(&mut self, batch: &SyncBatch) -> Result<()> {
        batch.validate()?;
        if !self.batches.iter().any(|existing| {
            existing.vault_id == batch.vault_id
                && existing.device_id == batch.device_id
                && existing.versions == batch.versions
        }) {
            self.batches.push(batch.clone());
        }
        Ok(())
    }
}

fn padded_len(length: usize) -> Result<usize> {
    if length == 0 || length > MAX_SYNC_BATCH_BYTES {
        return Err(CoreError::new(ErrorCode::LimitExceeded));
    }
    let total = length
        .checked_add(8)
        .ok_or_else(|| CoreError::new(ErrorCode::LimitExceeded))?;
    let blocks = total.div_ceil(SYNC_PAD_BLOCK);
    blocks
        .checked_mul(SYNC_PAD_BLOCK)
        .ok_or_else(|| CoreError::new(ErrorCode::LimitExceeded))
}

fn padded_len_for_bucket(bucket: u32) -> Result<usize> {
    let bucket = bucket as usize;
    if bucket == 0 || bucket % SYNC_PAD_BLOCK != 0 || bucket > MAX_SYNC_BATCH_BYTES {
        return Err(CoreError::new(ErrorCode::CorruptData));
    }
    Ok(bucket)
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

fn decode_id(value: Option<&Value>) -> Result<[u8; 16]> {
    let bytes = decode_hex(
        value
            .and_then(Value::as_str)
            .ok_or_else(|| CoreError::new(ErrorCode::CorruptData))?,
    )?;
    bytes
        .try_into()
        .map_err(|_| CoreError::new(ErrorCode::CorruptData))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn key() -> SecretBytes {
        SecretBytes::from_slice(&[7u8; 32])
    }

    fn batch() -> SyncBatch {
        SyncBatch {
            vault_id: [1u8; 16],
            device_id: [2u8; 16],
            versions: SyncVersionVector::default(),
            manifests: Vec::new(),
        }
    }

    #[test]
    fn batch_round_trip_and_no_plaintext() {
        let mut batch = batch();
        batch.versions.set(DeviceId::from_bytes([2u8; 16]), 4);
        batch
            .seal_blob(&key(), ItemId::from_bytes([3u8; 16]), 4, b"secret title")
            .expect("seal");
        let encoded = batch.encode().expect("encode");
        let text = std::str::from_utf8(&encoded).expect("json");
        assert!(!text.contains("secret title"));
        assert!(!text.contains("password"));
        let decoded = SyncBatch::decode(&encoded).expect("decode");
        assert_eq!(decoded.manifests.len(), 1);
        let opened = decoded
            .open_blob(&key(), &decoded.manifests[0])
            .expect("open");
        assert_eq!(opened.expose_secret(), b"secret title");
    }

    #[test]
    fn padding_is_deterministic_and_bucketed() {
        let mut batch = batch();
        batch
            .seal_blob(&key(), ItemId::from_bytes([1u8; 16]), 1, b"a")
            .expect("seal");
        assert_eq!(batch.manifests[0].size_bucket, 4096);
        assert!(batch.manifests[0].ciphertext.len() > 4096);
    }

    #[test]
    fn tampering_and_wrong_aad_fail() {
        let mut batch = batch();
        batch
            .seal_blob(&key(), ItemId::from_bytes([1u8; 16]), 1, b"secret")
            .expect("seal");
        let mut encoded = batch.encode().expect("encode");
        let index = encoded.len() / 2;
        encoded[index] ^= 1;
        let tampered = SyncBatch::decode(&encoded).expect("json still valid");
        assert!(tampered.open_blob(&key(), &tampered.manifests[0]).is_err());
        let mut other = batch.clone();
        other.device_id = [9u8; 16];
        assert!(other.open_blob(&key(), &other.manifests[0]).is_err());
    }

    #[test]
    fn stale_version_is_rejected() {
        let mut current = SyncVersionVector::default();
        current.set(DeviceId::from_bytes([2u8; 16]), 4);
        let mut older = SyncVersionVector::default();
        older.set(DeviceId::from_bytes([2u8; 16]), 4);
        assert!(current.require_newer(&older).is_err());
    }

    #[test]
    fn memory_adapter_contract() {
        let mut adapter = MemorySyncAdapter::new();
        let mut batch = batch();
        batch.versions.set(DeviceId::from_bytes([2u8; 16]), 1);
        batch
            .seal_blob(&key(), ItemId::from_bytes([1u8; 16]), 1, b"x")
            .expect("seal");
        adapter.push(&batch).expect("push");
        let pulled = adapter.pull(&SyncVersionVector::default()).expect("pull");
        assert_eq!(pulled.len(), 1);
        assert_eq!(adapter.adapter_id(), "memory");
        assert!(adapter.capabilities().conditional_put);
    }
}
