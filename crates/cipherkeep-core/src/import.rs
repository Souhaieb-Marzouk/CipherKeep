//! Bounded, offline importers for common password-manager exports.
//!
//! Parsing is two-phase: a caller receives a fully validated [`ImportResult`]
//! or a redacted error. This module never writes a vault and never logs values.
//! Imported secrets are zeroized when the result is dropped.

use std::collections::BTreeMap;

use serde_json::Value;
use zeroize::Zeroize;

use crate::error::{CoreError, ErrorCode, Result};

/// Maximum accepted import size.
pub const MAX_IMPORT_BYTES: usize = 16 * 1024 * 1024;
/// Maximum accepted item count.
pub const MAX_IMPORT_ITEMS: usize = 5_000;
/// Maximum accepted field length.
pub const MAX_IMPORT_FIELD: usize = 64 * 1024;
/// Maximum accepted CSV columns.
pub const MAX_IMPORT_COLUMNS: usize = 128;

/// Supported import formats.
#[repr(u8)]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ImportFormat {
    /// Bitwarden JSON export.
    BitwardenJson = 1,
    /// Bitwarden CSV export.
    BitwardenCsv = 2,
    /// 1Password CSV export.
    OnePasswordCsv = 3,
    /// KeePass CSV export.
    KeePassCsv = 4,
    /// Generic CSV with aliases.
    GenericCsv = 5,
    /// Proton Pass CSV export.
    ProtonCsv = 6,
    /// 1Password 1PUX JSON payload.
    OnePassword1pux = 7,
    /// KeePass KDBX database.
    KeePassKdbx = 8,
    /// Proton Pass JSON export.
    ProtonJson = 9,
}

impl ImportFormat {
    /// Stable display name.
    #[must_use]
    pub const fn name(self) -> &'static str {
        match self {
            Self::BitwardenJson => "Bitwarden JSON",
            Self::BitwardenCsv => "Bitwarden CSV",
            Self::OnePasswordCsv => "1Password CSV",
            Self::KeePassCsv => "KeePass CSV",
            Self::GenericCsv => "Generic CSV",
            Self::ProtonCsv => "Proton Pass CSV",
            Self::OnePassword1pux => "1Password 1PUX",
            Self::KeePassKdbx => "KeePass KDBX",
            Self::ProtonJson => "Proton Pass JSON",
        }
    }
}

/// What to do when two imported rows share title/username/URL.
#[repr(u8)]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum DuplicatePolicy {
    /// Keep the first and count the rest.
    Skip = 1,
    /// Merge non-empty fields into the first.
    Merge = 2,
}

/// Imported item type.
#[repr(u8)]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ImportKind {
    /// Login with username/password/URL.
    Login = 1,
    /// Secure note.
    Note = 2,
}

/// A validated plaintext import row. Dropped values are zeroized.
pub struct ImportedItem {
    /// Item type.
    pub kind: ImportKind,
    /// Title.
    pub title: String,
    /// Username/account.
    pub username: String,
    /// Password.
    pub password: String,
    /// URL.
    pub url: String,
    /// Notes.
    pub notes: String,
    /// Folder.
    pub folder: String,
    /// Tags.
    pub tags: Vec<String>,
}

impl Drop for ImportedItem {
    fn drop(&mut self) {
        self.title.zeroize();
        self.username.zeroize();
        self.password.zeroize();
        self.url.zeroize();
        self.notes.zeroize();
        self.folder.zeroize();
        for tag in &mut self.tags {
            tag.zeroize();
        }
    }
}

impl core::fmt::Debug for ImportedItem {
    fn fmt(&self, formatter: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        formatter
            .debug_struct("ImportedItem")
            .field("kind", &self.kind)
            .field("has_title", &!self.title.is_empty())
            .field("has_username", &!self.username.is_empty())
            .field("has_password", &!self.password.is_empty())
            .field("has_url", &!self.url.is_empty())
            .field("has_folder", &!self.folder.is_empty())
            .field("tag_count", &self.tags.len())
            .finish_non_exhaustive()
    }
}

/// A non-secret preview/report. Sample values are titles only, never secrets.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ImportReport {
    /// Detected or selected format.
    pub format: ImportFormat,
    /// Number imported.
    pub imported: usize,
    /// Duplicates skipped.
    pub skipped_duplicates: usize,
    /// Rows rejected.
    pub failed: usize,
    /// Up to five sample titles.
    pub sample_titles: Vec<String>,
}

impl ImportReport {
    /// Total rows considered.
    #[must_use]
    pub const fn total(&self) -> usize {
        self.imported + self.skipped_duplicates + self.failed
    }
}

/// A successful import. The caller must persist these items before dropping.
pub struct ImportResult {
    /// Validated items.
    pub items: Vec<ImportedItem>,
    /// Non-secret summary.
    pub report: ImportReport,
}

impl core::fmt::Debug for ImportResult {
    fn fmt(&self, formatter: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        formatter
            .debug_struct("ImportResult")
            .field("items", &self.items.len())
            .field("report", &self.report)
            .finish()
    }
}

/// Detect a supported format from the first bytes.
#[must_use]
pub fn detect_format(bytes: &[u8]) -> Option<ImportFormat> {
    let text = decode_text(bytes).ok()?;
    let trimmed = text.trim_start();
    if trimmed.starts_with('{') || trimmed.starts_with('[') {
        let value: Value = serde_json::from_str(trimmed).ok()?;
        if value.get("accounts").and_then(Value::as_array).is_some() {
            return Some(ImportFormat::OnePassword1pux);
        }
        if value.get("vaults").and_then(Value::as_array).is_some() {
            return Some(ImportFormat::ProtonJson);
        }
        return Some(ImportFormat::BitwardenJson);
    }
    let header = trimmed.lines().next()?.to_ascii_lowercase();
    if header.contains("login_password") || header.contains("login_username") {
        Some(ImportFormat::BitwardenCsv)
    } else if header.contains("login name") || header.contains("web site") {
        Some(ImportFormat::KeePassCsv)
    } else if header.contains("title") && header.contains("url") && header.contains("password") {
        Some(ImportFormat::OnePasswordCsv)
    } else if header.contains("autofillurls") || (header.contains("type") && header.contains("name") && header.contains("totp")) {
        Some(ImportFormat::ProtonCsv)
    } else if header.contains("name") || header.contains("password") {
        Some(ImportFormat::GenericCsv)
    } else {
        None
    }
}

/// Import bytes with automatic format detection.
///
/// # Errors
///
/// Returns a redacted error for empty/oversized input, malformed data or
/// unsupported structure. No partial result is returned.
pub fn import(bytes: &[u8], policy: DuplicatePolicy) -> Result<ImportResult> {
    if bytes.is_empty() {
        return Err(CoreError::new(ErrorCode::InvalidArgument));
    }
    if bytes.len() > MAX_IMPORT_BYTES {
        return Err(CoreError::new(ErrorCode::LimitExceeded));
    }
    let format = detect_format(bytes).ok_or_else(|| CoreError::new(ErrorCode::InvalidArgument))?;
    import_with_format(bytes, format, policy)
}

/// Import bytes with an explicit format.
///
/// # Errors
///
/// Returns a redacted error for empty/oversized input, malformed data or
/// unsupported structure. No partial result is returned.
pub fn import_with_format(
    bytes: &[u8],
    format: ImportFormat,
    policy: DuplicatePolicy,
) -> Result<ImportResult> {
    if bytes.is_empty() {
        return Err(CoreError::new(ErrorCode::InvalidArgument));
    }
    if bytes.len() > MAX_IMPORT_BYTES {
        return Err(CoreError::new(ErrorCode::LimitExceeded));
    }
    let text = decode_text(bytes)?;
    if text.trim().is_empty() {
        return Err(CoreError::new(ErrorCode::InvalidArgument));
    }
    match format {
        ImportFormat::BitwardenJson => import_bitwarden_json(&text, format, policy),
        ImportFormat::BitwardenCsv
        | ImportFormat::OnePasswordCsv
        | ImportFormat::KeePassCsv
        | ImportFormat::GenericCsv => import_csv(&text, format, policy),
        ImportFormat::ProtonCsv => import_proton_csv(&text, policy),
        ImportFormat::OnePassword1pux => import_onepux_json(&text, policy),
        ImportFormat::KeePassKdbx => Err(CoreError::new(ErrorCode::InvalidArgument)),
        ImportFormat::ProtonJson => import_proton_json(&text, policy),
    }
}

fn decode_text(bytes: &[u8]) -> Result<String> {
    let bytes = bytes.strip_prefix(&[0xef, 0xbb, 0xbf]).unwrap_or(bytes);
    if let Ok(text) = core::str::from_utf8(bytes) {
        return Ok(text.to_string());
    }
    let (text, _, had_errors) = encoding_rs::WINDOWS_1252.decode(bytes);
    if had_errors {
        return Err(CoreError::new(ErrorCode::CorruptData));
    }
    Ok(text.into_owned())
}

fn import_bitwarden_json(
    text: &str,
    format: ImportFormat,
    policy: DuplicatePolicy,
) -> Result<ImportResult> {
    let value: Value =
        serde_json::from_str(text).map_err(|_| CoreError::new(ErrorCode::CorruptData))?;
    let items = value
        .get("items")
        .and_then(Value::as_array)
        .ok_or_else(|| CoreError::new(ErrorCode::CorruptData))?;
    if items.len() > MAX_IMPORT_ITEMS {
        return Err(CoreError::new(ErrorCode::LimitExceeded));
    }
    let folders = value
        .get("folders")
        .and_then(Value::as_array)
        .map(|folders| {
            folders
                .iter()
                .filter_map(|folder| {
                    Some((
                        folder.get("id")?.as_str()?.to_string(),
                        folder.get("name")?.as_str()?.to_string(),
                    ))
                })
                .collect::<BTreeMap<_, _>>()
        })
        .unwrap_or_default();
    let mut states = ImportState::new(format);
    for item in items {
        let title = string_field(item, "name");
        let item_type = item.get("type").and_then(Value::as_u64).unwrap_or(0);
        let notes = string_field(item, "notes");
        let folder_id = string_field(item, "folderId");
        let folder = folders.get(&folder_id).cloned().unwrap_or_default();
        if item_type == 1 {
            let login = item.get("login");
            let username = login.map_or_else(String::new, |login| string_field(login, "username"));
            let password = login.map_or_else(String::new, |login| string_field(login, "password"));
            let url = login
                .and_then(|login| login.get("uris"))
                .and_then(Value::as_array)
                .and_then(|uris| uris.first())
                .map_or_else(String::new, |uri| string_field(uri, "uri"));
            states.push(
                ImportedItem {
                    kind: ImportKind::Login,
                    title,
                    username,
                    password,
                    url,
                    notes,
                    folder,
                    tags: Vec::new(),
                },
                policy,
            )?;
        } else if item_type == 2 {
            states.push(
                ImportedItem {
                    kind: ImportKind::Note,
                    title,
                    username: String::new(),
                    password: String::new(),
                    url: String::new(),
                    notes,
                    folder,
                    tags: Vec::new(),
                },
                policy,
            )?;
        } else {
            states.failed += 1;
        }
    }
    states.finish()
}




fn import_proton_json(text: &str, policy: DuplicatePolicy) -> Result<ImportResult> {
    let value: Value =
        serde_json::from_str(text).map_err(|_| CoreError::new(ErrorCode::CorruptData))?;
    let vaults = value
        .get("vaults")
        .and_then(Value::as_array)
        .ok_or_else(|| CoreError::new(ErrorCode::CorruptData))?;
    let mut states = ImportState::new(ImportFormat::ProtonJson);
    for vault in vaults {
        let folder = string_field(vault, "name");
        let items = vault
            .get("items")
            .and_then(Value::as_array)
            .cloned()
            .unwrap_or_default();
        if items.len() > MAX_IMPORT_ITEMS {
            return Err(CoreError::new(ErrorCode::LimitExceeded));
        }
        for wrapper in &items {
            let data = wrapper.get("data").unwrap_or(wrapper);
            let metadata = data.get("metadata").unwrap_or(data);
            let title = string_field(metadata, "name");
            if title.trim().is_empty() {
                states.failed += 1;
                continue;
            }
            let mut notes = string_field(metadata, "note");
            let content = data.get("content");
            if let Some(content) = content {
                if let Some(text) = content.as_str() {
                    if !text.trim().is_empty() {
                        if !notes.is_empty() { notes.push('\n'); }
                        notes.push_str(text);
                    }
                }
            }
            let kind_name = string_field(data, "type").to_ascii_lowercase();
            let content_value = content.cloned().unwrap_or(Value::Null);
            let content = &content_value;
            if kind_name == "note" {
                states.push(
                    ImportedItem {
                        kind: ImportKind::Note,
                        title,
                        username: String::new(),
                        password: String::new(),
                        url: String::new(),
                        notes,
                        folder: folder.clone(),
                        tags: Vec::new(),
                    },
                    policy,
                )?;
                continue;
            }
            let username = string_field(content, "username");
            let password = string_field(content, "password");
            let url = content
                .get("urls")
                .and_then(Value::as_array)
                .and_then(|urls| urls.first())
                .and_then(Value::as_str)
                .unwrap_or_default()
                .to_string();
            let totp = string_field(content, "totpUri");
            if !totp.trim().is_empty() {
                if !notes.is_empty() { notes.push('\n'); }
                notes.push_str("TOTP: ");
                notes.push_str(totp.trim());
            }
            if kind_name == "login" && username.is_empty() && password.is_empty() && notes.is_empty() {
                states.failed += 1;
                continue;
            }
            states.push(
                ImportedItem {
                    kind: ImportKind::Login,
                    title,
                    username,
                    password,
                    url,
                    notes,
                    folder: folder.clone(),
                    tags: Vec::new(),
                },
                policy,
            )?;
        }
    }
    states.finish()
}
fn import_onepux_json(text: &str, policy: DuplicatePolicy) -> Result<ImportResult> {
    let value: Value =
        serde_json::from_str(text).map_err(|_| CoreError::new(ErrorCode::CorruptData))?;
    let accounts = value
        .get("accounts")
        .and_then(Value::as_array)
        .ok_or_else(|| CoreError::new(ErrorCode::CorruptData))?;
    let mut states = ImportState::new(ImportFormat::OnePassword1pux);
    for account in accounts {
        let vaults = account
            .get("vaults")
            .and_then(Value::as_array)
            .ok_or_else(|| CoreError::new(ErrorCode::CorruptData))?;
        for vault in vaults {
            let folder = string_field(vault, "name");
            let items = vault
                .get("items")
                .and_then(Value::as_array)
                .cloned()
                .unwrap_or_default();
            if items.len() > MAX_IMPORT_ITEMS {
                return Err(CoreError::new(ErrorCode::LimitExceeded));
            }
            for wrapper in &items {
                let item = wrapper.get("item").unwrap_or(wrapper);
                let title = string_field(item, "title");
                if title.trim().is_empty() {
                    states.failed += 1;
                    continue;
                }
                let category = string_field(item, "categoryUuid");
                let details = item.get("details");
                let notes = details.map_or_else(String::new, |d| string_field(d, "notes"));
                let mut username = String::new();
                let mut password = String::new();
                if let Some(fields) = details
                    .and_then(|d| d.get("loginFields"))
                    .and_then(Value::as_array)
                {
                    for field in fields {
                        let designation = string_field(field, "designation").to_ascii_lowercase();
                        let value = string_field(field, "value");
                        if designation.contains("username") || designation == "email" {
                            username = value;
                        } else if designation.contains("password") {
                            password = value;
                        }
                    }
                }
                let url = item
                    .get("urls")
                    .and_then(Value::as_array)
                    .and_then(|urls| urls.first())
                    .map_or_else(String::new, |u| string_field(u, "url"));
                let kind = if category.starts_with("003") {
                    ImportKind::Note
                } else {
                    ImportKind::Login
                };
                if kind == ImportKind::Login && password.is_empty() && username.is_empty() && notes.is_empty() {
                    states.failed += 1;
                    continue;
                }
                states.push(
                    ImportedItem {
                        kind,
                        title,
                        username,
                        password,
                        url,
                        notes,
                        folder: folder.clone(),
                        tags: Vec::new(),
                    },
                    policy,
                )?;
            }
        }
    }
    states.finish()
}
fn import_proton_csv(text: &str, policy: DuplicatePolicy) -> Result<ImportResult> {
    let mut reader = csv::ReaderBuilder::new()
        .flexible(true)
        .from_reader(text.as_bytes());
    let headers = reader
        .headers()
        .map_err(|_| CoreError::new(ErrorCode::CorruptData))?
        .clone();
    if headers.len() > MAX_IMPORT_COLUMNS {
        return Err(CoreError::new(ErrorCode::LimitExceeded));
    }
    let indices = HeaderMap::new(&headers);
    let mut states = ImportState::new(ImportFormat::ProtonCsv);
    for record in reader.records() {
        let record = record.map_err(|_| CoreError::new(ErrorCode::CorruptData))?;
        if record.len() > MAX_IMPORT_COLUMNS {
            return Err(CoreError::new(ErrorCode::LimitExceeded));
        }
        let kind = indices.get(&record, &["type"]).to_ascii_lowercase();
        let title = indices.get(&record, &["name", "title"]);
        if title.trim().is_empty() {
            states.failed += 1;
            continue;
        }
        let mut notes = indices.get(&record, &["note", "notes"]);
        let totp = indices.get(&record, &["totp"]);
        if !totp.trim().is_empty() {
            if !notes.is_empty() {
                notes.push('\n');
            }
            notes.push_str("TOTP: ");
            notes.push_str(totp.trim());
        }
        if kind == "note" {
            states.push(
                ImportedItem {
                    kind: ImportKind::Note,
                    title,
                    username: String::new(),
                    password: String::new(),
                    url: String::new(),
                    notes,
                    folder: indices.get(&record, &["vault", "folder"]),
                    tags: Vec::new(),
                },
                policy,
            )?;
            continue;
        }
        if !kind.is_empty() && kind != "login" {
            states.failed += 1;
            continue;
        }
        let username = {
            let value = indices.get(&record, &["username", "email"]);
            if value.trim().is_empty() {
                indices.get(&record, &["email"])
            } else {
                value
            }
        };
        states.push(
            ImportedItem {
                kind: ImportKind::Login,
                title,
                username,
                password: indices.get(&record, &["password"]),
                url: indices.get(&record, &["url", "website"]),
                notes,
                folder: indices.get(&record, &["vault", "folder"]),
                tags: Vec::new(),
            },
            policy,
        )?;
    }
    states.finish()
}

fn import_csv(text: &str, format: ImportFormat, policy: DuplicatePolicy) -> Result<ImportResult> {
    let mut reader = csv::ReaderBuilder::new()
        .flexible(true)
        .from_reader(text.as_bytes());
    let headers = reader
        .headers()
        .map_err(|_| CoreError::new(ErrorCode::CorruptData))?
        .clone();
    if headers.len() > MAX_IMPORT_COLUMNS {
        return Err(CoreError::new(ErrorCode::LimitExceeded));
    }
    let indices = HeaderMap::new(&headers);
    let mut states = ImportState::new(format);
    for record in reader.records() {
        let record = record.map_err(|_| CoreError::new(ErrorCode::CorruptData))?;
        if record.len() > MAX_IMPORT_COLUMNS {
            return Err(CoreError::new(ErrorCode::LimitExceeded));
        }
        let title = indices.get(&record, &["title", "name", "account"]);
        if title.trim().is_empty() {
            states.failed += 1;
            continue;
        }
        let username = indices.get(
            &record,
            &["username", "login_username", "login", "loginname", "user"],
        );
        let password = indices.get(&record, &["password", "login_password", "pass"]);
        let url = indices.get(&record, &["url", "website", "web_site", "uri"]);
        let notes = indices.get(&record, &["notes", "note", "comments"]);
        let folder = indices.get(&record, &["folder", "group", "collection"]);
        let tags = indices
            .get(&record, &["tags", "tag"])
            .split(',')
            .map(str::trim)
            .filter(|tag| !tag.is_empty())
            .map(ToString::to_string)
            .collect();
        states.push(
            ImportedItem {
                kind: ImportKind::Login,
                title,
                username,
                password,
                url,
                notes,
                folder,
                tags,
            },
            policy,
        )?;
    }
    states.finish()
}

struct ImportState {
    format: ImportFormat,
    items: Vec<ImportedItem>,
    seen: BTreeMap<String, usize>,
    skipped_duplicates: usize,
    failed: usize,
}

impl ImportState {
    fn new(format: ImportFormat) -> Self {
        Self {
            format,
            items: Vec::new(),
            seen: BTreeMap::new(),
            skipped_duplicates: 0,
            failed: 0,
        }
    }

    fn push(&mut self, item: ImportedItem, policy: DuplicatePolicy) -> Result<()> {
        if self.items.len() >= MAX_IMPORT_ITEMS {
            return Err(CoreError::new(ErrorCode::LimitExceeded));
        }
        for value in [
            &item.title,
            &item.username,
            &item.password,
            &item.url,
            &item.notes,
            &item.folder,
        ] {
            if value.len() > MAX_IMPORT_FIELD {
                return Err(CoreError::new(ErrorCode::LimitExceeded));
            }
        }
        let key = format!(
            "{}\u{1f}{}\u{1f}{}",
            item.title.trim().to_lowercase(),
            item.username.trim().to_lowercase(),
            item.url.trim().to_lowercase()
        );
        if let Some(index) = self.seen.get(&key).copied() {
            self.skipped_duplicates += 1;
            if policy == DuplicatePolicy::Merge {
                let existing = &mut self.items[index];
                merge(&mut existing.username, &item.username);
                merge(&mut existing.password, &item.password);
                merge(&mut existing.url, &item.url);
                merge(&mut existing.notes, &item.notes);
                merge(&mut existing.folder, &item.folder);
            }
            return Ok(());
        }
        self.seen.insert(key, self.items.len());
        self.items.push(item);
        Ok(())
    }

    fn finish(self) -> Result<ImportResult> {
        if self.items.is_empty() && self.failed == 0 {
            return Err(CoreError::new(ErrorCode::CorruptData));
        }
        let imported = self.items.len();
        let sample_titles = self
            .items
            .iter()
            .take(5)
            .map(|item| item.title.clone())
            .collect();
        Ok(ImportResult {
            items: self.items,
            report: ImportReport {
                format: self.format,
                imported,
                skipped_duplicates: self.skipped_duplicates,
                failed: self.failed,
                sample_titles,
            },
        })
    }
}

fn merge(target: &mut String, incoming: &str) {
    if target.is_empty() {
        target.push_str(incoming);
    }
}

fn string_field(value: &Value, key: &str) -> String {
    value
        .get(key)
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_string()
}

struct HeaderMap {
    indices: BTreeMap<String, usize>,
}

impl HeaderMap {
    fn new(headers: &csv::StringRecord) -> Self {
        let mut indices = BTreeMap::new();
        for (index, header) in headers.iter().enumerate() {
            let normalized = header.trim().to_ascii_lowercase().replace([' ', '-'], "_");
            indices.entry(normalized).or_insert(index);
        }
        Self { indices }
    }

    fn get(&self, record: &csv::StringRecord, aliases: &[&str]) -> String {
        for alias in aliases {
            for (header, index) in &self.indices {
                if header == alias || header.replace('_', "") == alias.replace('_', "") {
                    if let Some(value) = record.get(*index) {
                        return value.trim().to_string();
                    }
                }
            }
        }
        String::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const BITWARDEN: &str = r#"{
      "folders":[{"id":"f1","name":"Work"}],
      "items":[
        {"type":1,"name":"GitHub","notes":"deploy","folderId":"f1",
         "login":{"username":"alice","password":"secret-github","uris":[{"uri":"https://github.com"}]}},
        {"type":2,"name":"Recovery","notes":"offline note"},
        {"type":3,"name":"Unsupported"}
      ]
    }"#;

    #[test]
    fn proton_json_imports_login_note() {
        let json = r#"{"encrypted":false,"vaults":[{"name":"Personal","items":[
          {"data":{"type":"login","metadata":{"name":"GitHub","note":"hello"},
            "content":{"username":"alice","password":"secret","urls":["https://github.com"],"totpUri":""}}},
          {"data":{"type":"note","metadata":{"name":"Recovery","note":"offline"},"content":""}}
        ]}]}"#;
        let result = import(json.as_bytes(), DuplicatePolicy::Skip).expect("import");
        assert_eq!(result.report.format, ImportFormat::ProtonJson);
        assert_eq!(result.report.imported, 2);
        assert_eq!(result.items[0].username, "alice");
        assert_eq!(result.items[0].folder, "Personal");
        assert_eq!(result.items[1].kind, ImportKind::Note);
    }

    #[test]
    fn onepux_imports_login_and_note() {
        let json = r#"{"accounts":[{"vaults":[{"name":"Personal","items":[
          {"item":{"title":"GitHub","categoryUuid":"001",
            "details":{"loginFields":[
              {"designation":"username","value":"alice"},
              {"designation":"password","value":"secret"}],
              "notes":"hello"},"urls":[{"url":"https://github.com"}]}},
          {"item":{"title":"Recovery","categoryUuid":"003","details":{"notes":"offline"}}}
        ]}]}]}"#;
        let result = import(json.as_bytes(), DuplicatePolicy::Skip).expect("import");
        assert_eq!(result.report.format, ImportFormat::OnePassword1pux);
        assert_eq!(result.report.imported, 2);
        assert_eq!(result.items[0].username, "alice");
        assert_eq!(result.items[0].folder, "Personal");
        assert_eq!(result.items[1].kind, ImportKind::Note);
    }

    #[test]
    fn proton_csv_imports_login_and_skips_card() {
        let csv = "type,name,url,email,username,password,note,totp,vault\n\
login,Proton Login,https://proton.me,alice@example.com,,secret,hello,otpauth://totp/Proton,Personal\n\
creditCard,My Card,,,,,,,Personal\n";
        let result = import(csv.as_bytes(), DuplicatePolicy::Skip).expect("import");
        assert_eq!(result.report.format, ImportFormat::ProtonCsv);
        assert_eq!(result.report.imported, 1);
        assert_eq!(result.report.failed, 1);
        let item = &result.items[0];
        assert_eq!(item.kind, ImportKind::Login);
        assert_eq!(item.username, "alice@example.com");
        assert_eq!(item.folder, "Personal");
        assert!(item.notes.contains("TOTP: otpauth://totp/Proton"));
    }

    #[test]
    fn bitwarden_json_imports_login_and_note() {
        let result = import(BITWARDEN.as_bytes(), DuplicatePolicy::Skip).expect("import");
        assert_eq!(result.report.format, ImportFormat::BitwardenJson);
        assert_eq!(result.items.len(), 2);
        assert_eq!(result.report.failed, 1);
        assert_eq!(result.items[0].folder, "Work");
        assert_eq!(result.items[0].url, "https://github.com");
        assert_eq!(result.items[1].kind, ImportKind::Note);
    }

    #[test]
    fn csv_formats_map_aliases_and_tags() {
        let csv = "Title,Url,Username,Password,Folder,Tags,Notes\nExample,https://example.test,alice,pw,Work,\"a,b\",note\n";
        let result = import_with_format(
            csv.as_bytes(),
            ImportFormat::OnePasswordCsv,
            DuplicatePolicy::Skip,
        )
        .expect("import");
        assert_eq!(result.items.len(), 1);
        assert_eq!(result.items[0].tags, vec!["a", "b"]);
        assert_eq!(result.items[0].folder, "Work");
    }

    #[test]
    fn duplicates_skip_and_merge() {
        let csv = "name,username,password,url\nSame,alice,one,https://x.test\nSame,alice,two,https://x.test\n";
        let skipped = import_with_format(
            csv.as_bytes(),
            ImportFormat::GenericCsv,
            DuplicatePolicy::Skip,
        )
        .expect("import");
        assert_eq!(skipped.items.len(), 1);
        assert_eq!(skipped.report.skipped_duplicates, 1);
        let merged = import_with_format(
            csv.as_bytes(),
            ImportFormat::GenericCsv,
            DuplicatePolicy::Merge,
        )
        .expect("import");
        assert_eq!(merged.items.len(), 1);
        assert_eq!(merged.items[0].password, "one");
        assert_eq!(merged.report.skipped_duplicates, 1);
    }

    #[test]
    fn malformed_and_oversized_inputs_fail_without_partial_results() {
        assert!(import(b"{not json", DuplicatePolicy::Skip).is_err());
        let oversized = vec![b'a'; MAX_IMPORT_BYTES + 1];
        assert_eq!(
            import(&oversized, DuplicatePolicy::Skip)
                .unwrap_err()
                .code(),
            ErrorCode::LimitExceeded
        );
    }

    #[test]
    fn report_never_contains_secret_values() {
        let result = import(BITWARDEN.as_bytes(), DuplicatePolicy::Skip).expect("import");
        let rendered = format!("{:?}", result.report);
        assert!(!rendered.contains("secret-github"));
        assert!(!rendered.contains("alice"));
        assert!(!rendered.contains("offline note"));
    }

    #[test]
    fn arbitrary_bytes_do_not_panic() {
        for seed in 0u32..512 {
            let mut bytes = vec![0u8; 64];
            for (index, byte) in bytes.iter_mut().enumerate() {
                *byte = ((seed.wrapping_mul(17).wrapping_add(index as u32)) & 0xff) as u8;
            }
            let _ = import(&bytes, DuplicatePolicy::Skip);
        }
    }
}
