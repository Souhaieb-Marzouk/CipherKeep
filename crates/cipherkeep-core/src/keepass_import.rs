//! KeePass KDBX import.
//!
//! KDBX parsing is delegated to the `keepass` crate. Only password-protected
//! databases are accepted here; keyfiles can be added later without changing
//! the platform contract.

use crate::import::{DuplicatePolicy, ImportFormat, ImportKind, ImportResult, ImportedItem};
use crate::{CoreError, ErrorCode};

fn map_error() -> CoreError {
    CoreError::new(ErrorCode::CorruptData)
}

/// Import a KDBX3/KDBX4 database using its password.
///
/// # Errors
///
/// Returns a redacted error for wrong passwords, corrupt databases, unsupported
/// KDBX features or a database with no usable entries.
pub fn import_kdbx(bytes: &[u8], password: &str, policy: DuplicatePolicy) -> crate::Result<ImportResult> {
    if bytes.is_empty() || !bytes.starts_with(b"\x03\xd9\xa2\x9a") {
        return Err(map_error());
    }
    let mut cursor = std::io::Cursor::new(bytes);
    let key = keepass::DatabaseKey::new().with_password(password);
    let database = keepass::Database::open(&mut cursor, key).map_err(|_| map_error())?;

    let mut items = Vec::new();
    walk_group(&database.root, "", &mut items, policy)?;
    if items.is_empty() {
        return Err(map_error());
    }
    let imported = items.len();
    Ok(ImportResult {
        items,
        report: crate::import::ImportReport {
            format: ImportFormat::KeePassKdbx,
            imported,
            skipped_duplicates: 0,
            failed: 0,
            sample_titles: Vec::new(),
        },
    })
}

fn walk_group(
    group: &keepass::db::Group,
    parent: &str,
    items: &mut Vec<ImportedItem>,
    policy: DuplicatePolicy,
) -> crate::Result<()> {
    let folder = if parent.is_empty() {
        group.name.clone()
    } else {
        format!("{parent}/{}", group.name)
    };
    for entry in &group.entries {
        let title = entry.get_title().unwrap_or_default().to_string();
        if title.trim().is_empty() {
            continue;
        }
        let username = entry.get_username().unwrap_or_default().to_string();
        let password = entry.get_password().unwrap_or_default().to_string();
        let url = entry.get_url().unwrap_or_default().to_string();
        let notes = entry.get("Notes").unwrap_or_default().to_string();
        items.push(ImportedItem {
            kind: ImportKind::Login,
            title,
            username,
            password,
            url,
            notes,
            folder: folder.clone(),
            tags: entry.tags.clone(),
        });
    }
    for child in &group.groups {
        walk_group(child, &folder, items, policy)?;
    }
    let _ = policy;
    Ok(())
}
