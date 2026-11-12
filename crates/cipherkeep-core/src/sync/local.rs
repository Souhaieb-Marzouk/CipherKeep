//! Local-folder sync adapter with atomic writes and path/symlink guards.

use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use super::{SyncAdapter, SyncBatch, SyncCapabilities, SyncVersionVector, MAX_SYNC_BATCH_BYTES};
use crate::error::{CoreError, ErrorCode, Result};

/// Folder-backed adapter for one vault.
pub struct LocalFolderAdapter {
    root: PathBuf,
    vault: [u8; 16],
}

impl LocalFolderAdapter {
    /// Create an adapter rooted at `root` for `vault`.
    ///
    /// # Errors
    ///
    /// Rejects a root/vault path that is a symlink or not a directory.
    pub fn new(root: impl AsRef<Path>, vault: [u8; 16]) -> Result<Self> {
        let root = root.as_ref().to_path_buf();
        if root.exists() {
            ensure_directory(&root)?;
        } else {
            fs::create_dir_all(&root).map_err(|_| CoreError::new(ErrorCode::Storage))?;
        }
        let adapter = Self { root, vault };
        adapter.vault_dir()?;
        Ok(adapter)
    }

    fn vault_dir(&self) -> Result<PathBuf> {
        let directory = self.root.join(hex(&self.vault));
        if directory.exists() {
            ensure_directory(&directory)?;
        } else {
            fs::create_dir(&directory).map_err(|_| CoreError::new(ErrorCode::Storage))?;
        }
        Ok(directory)
    }

    fn manifest_path(&self) -> Result<PathBuf> {
        Ok(self.vault_dir()?.join("manifest.json"))
    }

    fn blob_path(&self, item: &[u8; 16], version: u64) -> Result<PathBuf> {
        Ok(self
            .vault_dir()?
            .join(format!("{}-{version}.blob", hex(item))))
    }
}

impl SyncAdapter for LocalFolderAdapter {
    fn adapter_id(&self) -> &str {
        "local-folder"
    }

    fn capabilities(&self) -> SyncCapabilities {
        SyncCapabilities {
            conditional_put: true,
            delete: true,
            list: false,
        }
    }

    fn pull(&self, since: &SyncVersionVector) -> Result<Vec<SyncBatch>> {
        let path = self.manifest_path()?;
        if !path.exists() {
            return Ok(Vec::new());
        }
        let bytes = read_bounded(&path)?;
        let batch = SyncBatch::decode(&bytes)?;
        if batch.vault_id != self.vault {
            return Err(CoreError::new(ErrorCode::CorruptData));
        }
        if since.require_newer(&batch.versions).is_err() {
            return Ok(Vec::new());
        }
        Ok(vec![batch])
    }

    fn push(&mut self, batch: &SyncBatch) -> Result<()> {
        if batch.vault_id != self.vault {
            return Err(CoreError::new(ErrorCode::InvalidArgument));
        }
        batch.validate()?;
        let manifest = self.manifest_path()?;
        if manifest.exists() {
            let existing = SyncBatch::decode(&read_bounded(&manifest)?)?;
            existing.versions.require_newer(&batch.versions)?;
        }
        for item in &batch.manifests {
            let path = self.blob_path(&item.item_id, item.blob_version)?;
            write_atomic(&path, &item.ciphertext)?;
        }
        write_atomic(&manifest, &batch.encode()?)?;
        Ok(())
    }
}

fn read_bounded(path: &Path) -> Result<Vec<u8>> {
    let metadata = fs::symlink_metadata(path).map_err(|_| CoreError::new(ErrorCode::Storage))?;
    if metadata.file_type().is_symlink() || !metadata.is_file() {
        return Err(CoreError::new(ErrorCode::PolicyRejected));
    }
    if metadata.len() > MAX_SYNC_BATCH_BYTES as u64 {
        return Err(CoreError::new(ErrorCode::LimitExceeded));
    }
    fs::read(path).map_err(|_| CoreError::new(ErrorCode::Storage))
}

fn ensure_directory(path: &Path) -> Result<()> {
    let metadata = fs::symlink_metadata(path).map_err(|_| CoreError::new(ErrorCode::Storage))?;
    if metadata.file_type().is_symlink() || !metadata.is_dir() {
        return Err(CoreError::new(ErrorCode::PolicyRejected));
    }
    Ok(())
}

fn write_atomic(path: &Path, data: &[u8]) -> Result<()> {
    if let Ok(metadata) = fs::symlink_metadata(path) {
        if metadata.file_type().is_symlink() {
            return Err(CoreError::new(ErrorCode::PolicyRejected));
        }
    }
    let suffix = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|_| CoreError::new(ErrorCode::Internal))?
        .as_nanos();
    let temp = path.with_extension(format!("tmp-{suffix}"));
    let mut file = OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(&temp)
        .map_err(|_| CoreError::new(ErrorCode::Storage))?;
    file.write_all(data)
        .and_then(|()| file.sync_all())
        .map_err(|_| CoreError::new(ErrorCode::Storage))?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(&temp, fs::Permissions::from_mode(0o600))
            .map_err(|_| CoreError::new(ErrorCode::Storage))?;
    }
    if path.exists() {
        fs::remove_file(path).map_err(|_| CoreError::new(ErrorCode::Storage))?;
    }
    fs::rename(&temp, path).map_err(|_| CoreError::new(ErrorCode::Storage))?;
    Ok(())
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::keys::ItemId;
    use crate::secret::SecretBytes;
    use crate::sync::SyncVersionVector;
    use std::collections::BTreeSet;

    fn temp_dir() -> PathBuf {
        std::env::temp_dir().join(format!(
            "cipherkeep-sync-{}",
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .expect("clock")
                .as_nanos()
        ))
    }

    fn batch(device: [u8; 16], version: u64) -> SyncBatch {
        let mut batch = SyncBatch {
            vault_id: [1u8; 16],
            device_id: device,
            versions: SyncVersionVector::default(),
            manifests: Vec::new(),
        };
        batch
            .versions
            .set(crate::keys::DeviceId::from_bytes(device), version);
        batch
            .seal_blob(
                &SecretBytes::from_slice(&[7u8; 32]),
                ItemId::from_bytes([version as u8; 16]),
                version,
                b"secret",
            )
            .expect("seal");
        batch
    }

    #[test]
    fn writes_atomically_and_round_trips() {
        let root = temp_dir();
        let mut adapter = LocalFolderAdapter::new(&root, [1u8; 16]).expect("adapter");
        let batch = batch([2u8; 16], 1);
        adapter.push(&batch).expect("push");
        let pulled = adapter.pull(&SyncVersionVector::default()).expect("pull");
        assert_eq!(pulled.len(), 1);
        assert_eq!(pulled[0].manifests.len(), 1);
        let raw = fs::read_dir(root.join(hex(&[1u8; 16]))).expect("dir");
        let mut files = BTreeSet::new();
        for entry in raw {
            files.insert(
                entry
                    .expect("entry")
                    .file_name()
                    .into_string()
                    .expect("name"),
            );
        }
        assert!(files.contains("manifest.json"));
        assert!(files.iter().any(|name| name.ends_with(".blob")));
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn stale_manifest_is_not_overwritten() {
        let root = temp_dir();
        let mut adapter = LocalFolderAdapter::new(&root, [1u8; 16]).expect("adapter");
        adapter.push(&batch([2u8; 16], 2)).expect("push");
        assert!(adapter.push(&batch([2u8; 16], 2)).is_err());
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn symlink_manifest_is_rejected() {
        #[cfg(unix)]
        {
            use std::os::unix::fs::symlink;
            let root = temp_dir();
            let adapter = LocalFolderAdapter::new(&root, [1u8; 16]).expect("adapter");
            let target = root.join("outside.json");
            fs::write(&target, b"{}").expect("target");
            let manifest = adapter.manifest_path().expect("path");
            let _ = fs::remove_file(&manifest);
            symlink(&target, &manifest).expect("symlink");
            assert!(adapter.pull(&SyncVersionVector::default()).is_err());
            let _ = fs::remove_dir_all(root);
        }
    }
}
