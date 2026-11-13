//! Provider-independent email alias metadata and adapter contract.
//!
//! Alias creation happens at a provider the user controls. CipherKeep stores
//! only the alias mapping in the vault; it never proxies mail.

use std::collections::BTreeMap;

use crate::error::{CoreError, ErrorCode, Result};

/// Provider family that owns an alias.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum AliasProviderKind {
    /// User-deployed Cloudflare Email Worker catch-all.
    Cloudflare,
    /// Self-hosted SimpleLogin instance.
    SimpleLogin,
}

impl AliasProviderKind {
    /// Stable identifier stored in the vault.
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Cloudflare => "cloudflare",
            Self::SimpleLogin => "simplelogin",
        }
    }

    /// Decode a stored provider identifier.
    #[must_use]
    pub fn parse(value: &str) -> Option<Self> {
        match value {
            "cloudflare" => Some(Self::Cloudflare),
            "simplelogin" => Some(Self::SimpleLogin),
            _ => None,
        }
    }
}

/// Vault-side alias mapping. Contains no provider credential.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct AliasRecord {
    /// Full alias address.
    pub address: String,
    /// Provider family.
    pub provider: AliasProviderKind,
    /// Opaque provider-side identifier, when the provider returns one.
    pub provider_id: String,
    /// User label, for example the site name.
    pub label: String,
    /// Whether the provider currently accepts mail for this alias.
    pub enabled: bool,
}

impl AliasRecord {
    /// Validate an alias before it is written to the vault.
    ///
    /// # Errors
    ///
    /// Rejects empty labels, malformed addresses and oversized values.
    pub fn validate(&self) -> Result<()> {
        if self.label.trim().is_empty()
            || self.label.len() > 128
            || self.address.len() > 320
            || self.provider_id.len() > 256
        {
            return Err(CoreError::new(ErrorCode::InvalidArgument));
        }
        let (local, domain) = self
            .address
            .split_once('@')
            .ok_or_else(|| CoreError::new(ErrorCode::InvalidArgument))?;
        if local.is_empty()
            || domain.is_empty()
            || domain.contains('@')
            || !domain.contains('.')
            || self.address.chars().any(char::is_whitespace)
        {
            return Err(CoreError::new(ErrorCode::InvalidArgument));
        }
        Ok(())
    }
}

/// Provider operations needed by the alias UI.
///
/// Implementations must never log or persist the provider credential outside
/// platform secure storage.
pub trait AliasProvider {
    /// Provider family.
    fn kind(&self) -> AliasProviderKind;

    /// Create a new alias.
    ///
    /// # Errors
    ///
    /// Returns a provider or validation error.
    fn create(&mut self, label: &str) -> Result<AliasRecord>;

    /// Disable an alias without deleting its provider-side record.
    ///
    /// # Errors
    ///
    /// Returns a provider or validation error.
    fn disable(&mut self, address: &str) -> Result<()>;

    /// Permanently delete an alias at the provider.
    ///
    /// # Errors
    ///
    /// Returns a provider or validation error.
    fn delete(&mut self, address: &str) -> Result<()>;

    /// List aliases currently known to the provider.
    ///
    /// # Errors
    ///
    /// Returns a provider or validation error.
    fn list(&self) -> Result<Vec<AliasRecord>>;
}

/// Deterministic in-memory provider used by tests and the no-network fixture.
#[derive(Debug, Default)]
pub struct FakeAliasProvider {
    aliases: BTreeMap<String, AliasRecord>,
    next_id: u64,
}

impl FakeAliasProvider {
    /// Create an empty fake provider.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    #[cfg(test)]
    fn find(&self, address: &str) -> Result<&AliasRecord> {
        self.aliases
            .get(address)
            .ok_or_else(|| CoreError::new(ErrorCode::NotFound))
    }
}

impl AliasProvider for FakeAliasProvider {
    fn kind(&self) -> AliasProviderKind {
        AliasProviderKind::Cloudflare
    }

    fn create(&mut self, label: &str) -> Result<AliasRecord> {
        if label.trim().is_empty() || label.len() > 128 {
            return Err(CoreError::new(ErrorCode::InvalidArgument));
        }
        self.next_id += 1;
        let slug: String = label
            .chars()
            .filter(|value| value.is_ascii_alphanumeric())
            .take(24)
            .collect::<String>()
            .to_ascii_lowercase();
        let slug = if slug.is_empty() {
            "alias".to_string()
        } else {
            slug
        };
        let address = format!("{slug}-{}@alias.test", self.next_id);
        let record = AliasRecord {
            address: address.clone(),
            provider: self.kind(),
            provider_id: format!("fake-{}", self.next_id),
            label: label.trim().to_string(),
            enabled: true,
        };
        record.validate()?;
        self.aliases.insert(address, record.clone());
        Ok(record)
    }

    fn disable(&mut self, address: &str) -> Result<()> {
        let record = self
            .aliases
            .get_mut(address)
            .ok_or_else(|| CoreError::new(ErrorCode::NotFound))?;
        record.enabled = false;
        Ok(())
    }

    fn delete(&mut self, address: &str) -> Result<()> {
        self.aliases
            .remove(address)
            .map(|_| ())
            .ok_or_else(|| CoreError::new(ErrorCode::NotFound))
    }

    fn list(&self) -> Result<Vec<AliasRecord>> {
        Ok(self.aliases.values().cloned().collect())
    }
}

#[cfg(test)]
mod tests {
    use super::{AliasProvider, FakeAliasProvider};

    #[test]
    fn create_disable_delete_round_trip() {
        let mut provider = FakeAliasProvider::new();
        let alias = provider.create("Example Site").expect("create");
        assert_eq!(alias.address, "examplesite-1@alias.test");
        assert!(provider.find(&alias.address).expect("find").enabled);
        provider.disable(&alias.address).expect("disable");
        assert!(!provider.find(&alias.address).expect("find").enabled);
        assert_eq!(provider.list().expect("list").len(), 1);
        provider.delete(&alias.address).expect("delete");
        assert!(provider.list().expect("list").is_empty());
    }

    #[test]
    fn rejects_invalid_labels_and_missing_aliases() {
        let mut provider = FakeAliasProvider::new();
        assert!(provider.create(" ").is_err());
        assert!(provider.disable("missing@alias.test").is_err());
        assert!(provider.delete("missing@alias.test").is_err());
    }

    #[test]
    fn validates_address_shape() {
        let record = super::AliasRecord {
            address: "not-an-address".to_string(),
            provider: super::AliasProviderKind::SimpleLogin,
            provider_id: "1".to_string(),
            label: "site".to_string(),
            enabled: true,
        };
        assert!(record.validate().is_err());
    }
}
