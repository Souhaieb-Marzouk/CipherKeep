//! Local password health metrics.
//!
//! All scoring is local. Breach data is optional and must come from an explicit
//! HIBP scan; no network call is made here.

use std::collections::BTreeMap;

/// One item's local health input.
#[derive(Clone, Debug)]
pub struct HealthInput {
    /// Stable item identifier.
    pub item_id: String,
    /// Plaintext password, held only for the calculation.
    pub password: String,
    /// Last update time in Unix seconds.
    pub updated_at: u64,
    /// Whether the item has a TOTP seed.
    pub has_totp: bool,
    /// Breach count from an opt-in scan, if one was run.
    pub breached: Option<u64>,
}

/// Explanatory health reason.
#[derive(Clone, Copy, Debug, Eq, Ord, PartialEq, PartialOrd)]
pub enum HealthReason {
    /// Short or low-diversity password.
    Weak,
    /// Password appears on more than one item.
    Reused,
    /// Older than the stale threshold.
    Old,
    /// Present in a known breach (opt-in HIBP only).
    Breached,
    /// Login has no TOTP.
    MissingTotp,
}

/// Per-item result. Contains no password or hash.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ItemHealth {
    /// Item identifier.
    pub item_id: String,
    /// Reasons in deterministic order.
    pub reasons: Vec<HealthReason>,
    /// 0-100 explanatory score; lower is worse.
    pub score: u8,
}

/// Vault summary.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct HealthSummary {
    /// Per-item results.
    pub items: Vec<ItemHealth>,
    /// Count of weak passwords.
    pub weak: usize,
    /// Count of reused passwords.
    pub reused: usize,
    /// Count of stale passwords.
    pub old: usize,
    /// Count of breached passwords, only from explicit scan data.
    pub breached: usize,
    /// Count of logins without TOTP.
    pub missing_totp: usize,
}

/// Analyze a vault on a caller-provided clock.
#[must_use]
pub fn analyze(items: &[HealthInput], now: u64, stale_secs: u64) -> HealthSummary {
    let mut counts: BTreeMap<&str, usize> = BTreeMap::new();
    for item in items {
        *counts.entry(item.password.as_str()).or_default() += 1;
    }
    let mut summary = HealthSummary {
        items: Vec::with_capacity(items.len()),
        weak: 0,
        reused: 0,
        old: 0,
        breached: 0,
        missing_totp: 0,
    };
    for item in items {
        let mut reasons = Vec::new();
        if entropy_bits(&item.password) < 50 {
            reasons.push(HealthReason::Weak);
            summary.weak += 1;
        }
        if counts.get(item.password.as_str()).copied().unwrap_or(0) > 1 {
            reasons.push(HealthReason::Reused);
            summary.reused += 1;
        }
        if now.saturating_sub(item.updated_at) > stale_secs {
            reasons.push(HealthReason::Old);
            summary.old += 1;
        }
        if item.breached.is_some_and(|count| count > 0) {
            reasons.push(HealthReason::Breached);
            summary.breached += 1;
        }
        if !item.has_totp {
            reasons.push(HealthReason::MissingTotp);
            summary.missing_totp += 1;
        }
        reasons.sort_unstable();
        reasons.dedup();
        let penalty = reasons.len().saturating_mul(15).min(100) as u8;
        summary.items.push(ItemHealth {
            item_id: item.item_id.clone(),
            reasons,
            score: 100u8.saturating_sub(penalty),
        });
    }
    summary
}

/// Rough Shannon-style entropy estimate in bits.
#[must_use]
pub fn entropy_bits(password: &str) -> u32 {
    if password.is_empty() {
        return 0;
    }
    let mut classes = 0u32;
    if password.chars().any(|c| c.is_ascii_lowercase()) {
        classes += 26;
    }
    if password.chars().any(|c| c.is_ascii_uppercase()) {
        classes += 26;
    }
    if password.chars().any(|c| c.is_ascii_digit()) {
        classes += 10;
    }
    if password.chars().any(|c| !c.is_ascii_alphanumeric()) {
        classes += 33;
    }
    if classes == 0 {
        return 0;
    }
    let length = password.chars().count().min(256) as f64;
    (length * f64::from(classes).log2()).round() as u32
}

#[cfg(test)]
mod tests {
    use super::{analyze, entropy_bits, HealthInput, HealthReason};

    fn input(id: &str, password: &str, updated: u64) -> HealthInput {
        HealthInput {
            item_id: id.to_string(),
            password: password.to_string(),
            updated_at: updated,
            has_totp: true,
            breached: None,
        }
    }

    #[test]
    fn weak_and_strong_entropy() {
        assert!(entropy_bits("abc") < 50);
        assert!(entropy_bits("Correct-Horse-Battery-Staple-2026!") >= 50);
    }

    #[test]
    fn reuse_counts_duplicates() {
        let now = 1_000_000;
        let report = analyze(
            &[
                input("a", "same-password-value", now),
                input("b", "same-password-value", now),
                input("c", "unique-password-value", now),
            ],
            now,
            90 * 86_400,
        );
        assert_eq!(report.reused, 2);
        assert!(report.items[0].reasons.contains(&HealthReason::Reused));
        assert!(!report.items[2].reasons.contains(&HealthReason::Reused));
    }

    #[test]
    fn age_and_breach_and_totp_reasons() {
        let now = 100 * 86_400;
        let mut old = input("a", "Correct-Horse-Battery-Staple-2026!", 0);
        old.breached = Some(5);
        old.has_totp = false;
        let report = analyze(&[old], now, 90 * 86_400);
        assert!(report.items[0].reasons.contains(&HealthReason::Old));
        assert!(report.items[0].reasons.contains(&HealthReason::Breached));
        assert!(report.items[0].reasons.contains(&HealthReason::MissingTotp));
        assert_eq!(report.breached, 1);
    }

    #[test]
    fn no_breach_reason_without_opt_in_data() {
        let report = analyze(
            &[input("a", "unique-password-value", 100)],
            100,
            90 * 86_400,
        );
        assert_eq!(report.breached, 0);
        assert!(!report.items[0].reasons.contains(&HealthReason::Breached));
    }
}
