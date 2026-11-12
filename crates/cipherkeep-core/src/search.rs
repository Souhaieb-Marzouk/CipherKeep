//! In-memory metadata search.
//!
//! The index contains titles, usernames, URLs, folder names and note text only.
//! Passwords, TOTP seeds, SSH private keys and card secrets are never passed to
//! this module. Indexed strings are zeroized on removal and clear.

use std::collections::{BTreeMap, BTreeSet};
use std::time::Instant;

use unicode_normalization::char::is_combining_mark;
use unicode_normalization::UnicodeNormalization;
use zeroize::Zeroize;

use crate::error::{CoreError, ErrorCode, Result};
use crate::keys::ItemId;

/// Maximum number of results returned by one query.
pub const MAX_SEARCH_RESULTS: usize = 50;

/// Metadata available to the search index.
pub struct SearchDocument {
    id: ItemId,
    title: String,
    username: String,
    url: String,
    folder: String,
    notes: String,
    field_tokens: [Vec<String>; 5],
}

impl SearchDocument {
    /// Build a metadata document. Secrets must not be supplied.
    #[must_use]
    pub fn new(
        id: ItemId,
        title: impl Into<String>,
        username: impl Into<String>,
        url: impl Into<String>,
        folder: impl Into<String>,
        notes: impl Into<String>,
    ) -> Self {
        let title = title.into();
        let username = username.into();
        let url = url.into();
        let folder = folder.into();
        let notes = notes.into();
        let field_tokens = [
            tokenize(&title),
            tokenize(&username),
            tokenize(&url),
            tokenize(&folder),
            tokenize(&notes),
        ];
        Self {
            id,
            title,
            username,
            url,
            folder,
            notes,
            field_tokens,
        }
    }

    /// Item id.
    #[must_use]
    pub const fn id(&self) -> ItemId {
        self.id
    }

    /// Title metadata.
    #[must_use]
    pub fn title(&self) -> &str {
        &self.title
    }

    /// Username metadata.
    #[must_use]
    pub fn username(&self) -> &str {
        &self.username
    }

    /// URL metadata.
    #[must_use]
    pub fn url(&self) -> &str {
        &self.url
    }

    /// Folder metadata.
    #[must_use]
    pub fn folder(&self) -> &str {
        &self.folder
    }

    /// Note metadata. Snippets never include this field.
    #[must_use]
    pub fn notes(&self) -> &str {
        &self.notes
    }

    fn field_tokens(&self) -> &[Vec<String>; 5] {
        &self.field_tokens
    }

    fn tokens(&self) -> Vec<String> {
        let mut tokens = BTreeSet::new();
        for field in &self.field_tokens {
            for token in field {
                tokens.insert(token.clone());
            }
        }
        tokens.into_iter().collect()
    }
}

impl Drop for SearchDocument {
    fn drop(&mut self) {
        self.title.zeroize();
        self.username.zeroize();
        self.url.zeroize();
        self.folder.zeroize();
        self.notes.zeroize();
        for field in &mut self.field_tokens {
            for token in field {
                token.zeroize();
            }
        }
    }
}

impl core::fmt::Debug for SearchDocument {
    fn fmt(&self, formatter: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        formatter
            .debug_struct("SearchDocument")
            .field("id", &self.id)
            .finish_non_exhaustive()
    }
}

/// A redacted search result.
pub struct SearchHit {
    id: ItemId,
    score: u16,
    snippet: String,
}

impl SearchHit {
    /// Item id.
    #[must_use]
    pub const fn id(&self) -> ItemId {
        self.id
    }

    /// Relative score. Higher is more relevant.
    #[must_use]
    pub const fn score(&self) -> u16 {
        self.score
    }

    /// Short redacted metadata snippet.
    #[must_use]
    pub fn snippet(&self) -> &str {
        &self.snippet
    }
}

impl core::fmt::Debug for SearchHit {
    fn fmt(&self, formatter: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        formatter
            .debug_struct("SearchHit")
            .field("id", &self.id)
            .field("score", &self.score)
            .finish_non_exhaustive()
    }
}

/// In-memory inverted index. Dropping or clearing it zeroizes indexed strings.
#[derive(Default)]
pub struct SearchIndex {
    documents: BTreeMap<ItemId, SearchDocument>,
    tokens: BTreeMap<ItemId, Vec<String>>,
    postings: BTreeMap<String, BTreeSet<ItemId>>,
}

impl SearchIndex {
    /// Create an empty index.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    /// Insert or replace a document.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::InvalidArgument`] if the title is empty.
    pub fn insert(&mut self, document: SearchDocument) -> Result<()> {
        if document.title().trim().is_empty() {
            return Err(CoreError::new(ErrorCode::InvalidArgument));
        }
        self.remove(document.id());
        let id = document.id();
        let tokens = document.tokens();
        for token in &tokens {
            self.postings.entry(token.clone()).or_default().insert(id);
        }
        self.tokens.insert(id, tokens);
        self.documents.insert(id, document);
        Ok(())
    }

    /// Remove a document and its postings.
    pub fn remove(&mut self, id: ItemId) -> bool {
        if let Some(tokens) = self.tokens.remove(&id) {
            for mut token in tokens {
                if let Some(items) = self.postings.get_mut(&token) {
                    items.remove(&id);
                    if items.is_empty() {
                        let mut key = self.postings.remove(&token).unwrap_or_default();
                        key.clear();
                    }
                }
                token.zeroize();
            }
        }
        self.documents.remove(&id).is_some()
    }

    /// Clear and zeroize the index.
    pub fn clear(&mut self) {
        self.documents.clear();
        for (_, mut tokens) in std::mem::take(&mut self.tokens) {
            for token in &mut tokens {
                token.zeroize();
            }
        }
        for (mut token, items) in std::mem::take(&mut self.postings) {
            token.zeroize();
            drop(items);
        }
    }

    /// Number of indexed documents.
    #[must_use]
    pub fn len(&self) -> usize {
        self.documents.len()
    }

    /// Whether the index is empty.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.documents.is_empty()
    }

    /// Search with prefix matching and case/diacritic folding.
    ///
    /// Results contain only ids, scores and redacted title/username snippets.
    #[must_use]
    pub fn search(&self, query: &str, limit: usize) -> Vec<SearchHit> {
        let query_tokens = tokenize(query);
        if query_tokens.is_empty() || limit == 0 {
            return Vec::new();
        }
        let mut candidates = BTreeSet::new();
        for token in &query_tokens {
            for (indexed, ids) in &self.postings {
                if indexed.starts_with(token) {
                    candidates.extend(ids.iter().copied());
                }
            }
        }
        let mut hits = Vec::new();
        for id in candidates {
            let Some(document) = self.documents.get(&id) else {
                continue;
            };
            let mut score = 0u16;
            let mut all_match = true;
            for query_token in &query_tokens {
                let mut token_score = 0u16;
                for (field, weight) in document.field_tokens().iter().zip([5u16, 4, 2, 2, 1]) {
                    if field
                        .iter()
                        .any(|candidate| candidate.starts_with(query_token))
                    {
                        token_score = token_score.saturating_add(weight);
                    }
                }
                if token_score == 0 {
                    all_match = false;
                    break;
                }
                score = score.saturating_add(token_score);
            }
            if !all_match {
                continue;
            }
            let snippet = if document.username().is_empty() {
                document.title().to_string()
            } else {
                format!("{} — {}", document.title(), document.username())
            };
            hits.push(SearchHit { id, score, snippet });
        }
        hits.sort_by(|left, right| {
            right
                .score
                .cmp(&left.score)
                .then_with(|| left.id.cmp(&right.id))
        });
        hits.truncate(limit.min(MAX_SEARCH_RESULTS));
        hits
    }
}

impl Drop for SearchIndex {
    fn drop(&mut self) {
        self.clear();
    }
}

impl core::fmt::Debug for SearchIndex {
    fn fmt(&self, formatter: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        formatter
            .debug_struct("SearchIndex")
            .field("documents", &self.documents.len())
            .finish_non_exhaustive()
    }
}

fn tokenize(value: &str) -> Vec<String> {
    let folded: String = value
        .nfkd()
        .filter(|character| !is_combining_mark(*character))
        .flat_map(char::to_lowercase)
        .collect();
    folded
        .split(|character: char| !character.is_alphanumeric())
        .filter(|token| !token.is_empty())
        .map(ToString::to_string)
        .collect()
}

/// Measure the p95 query latency for a 2,000-document index.
///
/// The benchmark is exposed for tests and release validation; callers do not
/// need it in production.
#[must_use]
pub fn measure_p95(index: &SearchIndex, queries: &[&str]) -> std::time::Duration {
    if queries.is_empty() {
        return std::time::Duration::ZERO;
    }
    let mut timings = Vec::with_capacity(queries.len());
    for query in queries {
        let started = Instant::now();
        let _ = index.search(query, MAX_SEARCH_RESULTS);
        timings.push(started.elapsed());
    }
    timings.sort_unstable();
    let rank = (timings.len() * 95).div_ceil(100).max(1);
    timings[rank - 1]
}

#[cfg(test)]
mod tests {
    use super::*;

    fn id(byte: u8) -> ItemId {
        ItemId::from_bytes([byte; 16])
    }

    fn document(byte: u8, title: &str, username: &str, url: &str, notes: &str) -> SearchDocument {
        SearchDocument::new(id(byte), title, username, url, "Work", notes)
    }

    #[test]
    fn finds_title_username_url_and_note_metadata() {
        let mut index = SearchIndex::new();
        index
            .insert(document(
                1,
                "GitHub",
                "alice",
                "https://github.com",
                "deploy key",
            ))
            .unwrap();
        assert_eq!(index.search("github", 10).len(), 1);
        assert_eq!(index.search("ali", 10).len(), 1);
        assert_eq!(index.search("com", 10).len(), 1);
        assert_eq!(index.search("deploy", 10).len(), 1);
        assert!(index.search("hunter2", 10).is_empty());
    }

    #[test]
    fn folds_case_and_diacritics_and_matches_prefixes() {
        let mut index = SearchIndex::new();
        index
            .insert(document(
                1,
                "Café Électrique",
                "josé",
                "https://example.test",
                "",
            ))
            .unwrap();
        assert_eq!(index.search("CAFE", 10).len(), 1);
        assert_eq!(index.search("elect", 10).len(), 1);
        assert_eq!(index.search("jose", 10).len(), 1);
        assert_eq!(index.search("caf", 10).len(), 1);
    }

    #[test]
    fn snippets_never_include_note_or_secret_fields() {
        let mut index = SearchIndex::new();
        index
            .insert(document(
                1,
                "Bank",
                "alice",
                "https://bank.test",
                "recovery code 1234",
            ))
            .unwrap();
        let hit = &index.search("bank", 10)[0];
        assert_eq!(hit.snippet(), "Bank — alice");
        assert!(!hit.snippet().contains("1234"));
    }

    #[test]
    fn remove_and_clear_drop_documents() {
        let mut index = SearchIndex::new();
        index.insert(document(1, "One", "", "", "")).unwrap();
        index.insert(document(2, "Two", "", "", "")).unwrap();
        assert_eq!(index.len(), 2);
        assert!(index.remove(id(1)));
        assert_eq!(index.search("one", 10).len(), 0);
        index.clear();
        assert!(index.is_empty());
        assert!(index.search("two", 10).is_empty());
    }

    #[test]
    fn empty_query_and_empty_title_are_rejected() {
        let mut index = SearchIndex::new();
        assert!(index
            .insert(SearchDocument::new(id(1), " ", "", "", "", ""))
            .is_err());
        index.insert(document(1, "One", "", "", "")).unwrap();
        assert!(index.search("", 10).is_empty());
        assert!(index.search("one", 0).is_empty());
    }

    #[test]
    fn p95_for_two_thousand_documents_is_under_fifty_ms() {
        let mut index = SearchIndex::new();
        for value in 0..2_000u32 {
            let mut bytes = [0u8; 16];
            bytes[..4].copy_from_slice(&value.to_le_bytes());
            index
                .insert(SearchDocument::new(
                    ItemId::from_bytes(bytes),
                    format!("Service {value}"),
                    format!("user{value}"),
                    format!("https://service{value}.example"),
                    "Personal",
                    "notes for this service",
                ))
                .unwrap();
        }
        let queries = [
            "service",
            "user1",
            "example",
            "notes",
            "personal",
            "service 1999",
        ];
        let p95 = measure_p95(&index, &queries);
        assert!(p95 < std::time::Duration::from_millis(50), "p95={p95:?}");
    }
}
