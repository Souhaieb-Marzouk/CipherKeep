//! Travel Mode vault visibility.
//!
//! Hiding a vault removes it from the active view and from every repository
//! query. It never deletes the vault; restore requires re-authentication in the
//! platform layer.

use std::collections::BTreeSet;

use crate::keys::VaultId;

/// Set of vault ids hidden by Travel Mode.
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct TravelMode {
    hidden: BTreeSet<[u8; 16]>,
}

impl TravelMode {
    /// Empty Travel Mode.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    /// Hide a vault. Hiding twice is idempotent.
    pub fn hide(&mut self, vault: VaultId) {
        self.hidden.insert(*vault.as_bytes());
    }

    /// Restore a previously hidden vault.
    pub fn restore(&mut self, vault: VaultId) -> bool {
        self.hidden.remove(vault.as_bytes())
    }

    /// Whether a vault is visible to repository queries.
    #[must_use]
    pub fn is_visible(&self, vault: VaultId) -> bool {
        !self.hidden.contains(vault.as_bytes())
    }

    /// Number of hidden vaults.
    #[must_use]
    pub fn hidden_count(&self) -> usize {
        self.hidden.len()
    }

    /// Whether Travel Mode hides anything.
    #[must_use]
    pub fn is_active(&self) -> bool {
        !self.hidden.is_empty()
    }
}

#[cfg(test)]
mod tests {
    use super::TravelMode;
    use crate::keys::VaultId;

    #[test]
    fn hidden_vault_is_filtered_and_restorable() {
        let visible = VaultId::from_bytes([1u8; 16]);
        let hidden = VaultId::from_bytes([2u8; 16]);
        let mut mode = TravelMode::new();
        assert!(mode.is_visible(hidden));
        mode.hide(hidden);
        assert!(!mode.is_visible(hidden));
        assert!(mode.is_visible(visible));
        assert_eq!(mode.hidden_count(), 1);
        assert!(mode.is_active());
        assert!(mode.restore(hidden));
        assert!(mode.is_visible(hidden));
        assert!(!mode.restore(hidden));
    }

    #[test]
    fn hiding_is_idempotent() {
        let vault = VaultId::from_bytes([3u8; 16]);
        let mut mode = TravelMode::new();
        mode.hide(vault);
        mode.hide(vault);
        assert_eq!(mode.hidden_count(), 1);
    }
}
