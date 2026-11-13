//! Emergency-access policy and dead-man's-switch timeline.
//!
//! The cron service stores only opaque encrypted share envelopes and policy
//! timestamps. It never receives a vault key or a plaintext recovery share.

use crate::error::{CoreError, ErrorCode, Result};
use crate::secret::SecretBytes;
use crate::shamir::{split_2_of_3, RecoveryShare};

/// Maximum contacts supported by the policy.
pub const MAX_CONTACTS: usize = 2;

/// Trusted contact metadata. No secret material belongs here.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct EmergencyContact {
    /// Stable local identifier.
    pub id: String,
    /// Display label.
    pub label: String,
    /// Contact address used by the cron notifier.
    pub address: String,
}

/// Opaque encrypted share envelope for one contact.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SealedShare {
    /// Contact id this envelope belongs to.
    pub contact_id: String,
    /// Ciphertext only; the cron cannot open it.
    pub ciphertext: Vec<u8>,
}

/// Policy lifecycle.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum EmergencyState {
    /// Timer is running.
    Armed,
    /// User cancelled or recovered normally.
    Cancelled,
    /// Share was released.
    Released,
}

/// Action the cron should take at a tick.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum EmergencyAction {
    /// Nothing due.
    None,
    /// Send a reminder; contains no secret.
    Reminder {
        /// Unix seconds when the release would happen.
        deadline: u64,
    },
    /// Release opaque envelopes to contacts.
    Release {
        /// Unix seconds of release.
        released_at: u64,
        /// Opaque envelopes.
        shares: Vec<SealedShare>,
    },
}

/// Shamir distribution for emergency release.
///
/// Two shares go to the contacts and one goes to the cron. The cron releases
/// only its own share; a contact must combine it with the contact share.
pub struct EmergencySharePlan {
    contact_a: RecoveryShare,
    contact_b: RecoveryShare,
    cron: RecoveryShare,
}

impl EmergencySharePlan {
    /// Split a recovery secret into two contact shares and one cron share.
    ///
    /// # Errors
    ///
    /// Returns an error when Shamir splitting fails.
    pub fn new(secret: &SecretBytes) -> Result<Self> {
        let [contact_a, contact_b, cron] = split_2_of_3(secret)?;
        Ok(Self {
            contact_a,
            contact_b,
            cron,
        })
    }

    /// Contact A's share.
    #[must_use]
    pub const fn contact_a(&self) -> &RecoveryShare {
        &self.contact_a
    }

    /// Contact B's share.
    #[must_use]
    pub const fn contact_b(&self) -> &RecoveryShare {
        &self.contact_b
    }

    /// Cron-held share. Releasing it alone is insufficient.
    #[must_use]
    pub const fn cron_share(&self) -> &RecoveryShare {
        &self.cron
    }
}

/// User-defined emergency policy.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct EmergencyPolicy {
    contacts: Vec<EmergencyContact>,
    shares: Vec<SealedShare>,
    inactivity_secs: u64,
    reminder_lead_secs: u64,
    grace_secs: u64,
    last_check_in: u64,
    reminder_sent: bool,
    state: EmergencyState,
}

impl EmergencyPolicy {
    /// Create an armed policy.
    ///
    /// # Errors
    ///
    /// Rejects empty/oversized contact lists and zero inactivity periods.
    pub fn new(
        mut contacts: Vec<EmergencyContact>,
        shares: Vec<SealedShare>,
        inactivity_secs: u64,
        reminder_lead_secs: u64,
        grace_secs: u64,
        now: u64,
    ) -> Result<Self> {
        if contacts.is_empty()
            || contacts.len() > MAX_CONTACTS
            || inactivity_secs == 0
            || reminder_lead_secs >= inactivity_secs
        {
            return Err(CoreError::new(ErrorCode::InvalidArgument));
        }
        contacts.sort_by(|left, right| left.id.cmp(&right.id));
        if contacts.iter().any(|contact| {
            contact.id.is_empty()
                || contact.label.is_empty()
                || contact.address.is_empty()
                || contact.id.len() > 64
                || contact.label.len() > 128
                || contact.address.len() > 320
                || !contact.address.contains('@')
                || contact.address.chars().any(char::is_whitespace)
        }) || contacts.windows(2).any(|pair| pair[0].id == pair[1].id)
        {
            return Err(CoreError::new(ErrorCode::InvalidArgument));
        }
        if shares.len() != contacts.len()
            || shares
                .iter()
                .any(|share| share.ciphertext.is_empty() || share.contact_id.is_empty())
        {
            return Err(CoreError::new(ErrorCode::InvalidArgument));
        }
        Ok(Self {
            contacts,
            shares,
            inactivity_secs,
            reminder_lead_secs,
            grace_secs,
            last_check_in: now,
            reminder_sent: false,
            state: EmergencyState::Armed,
        })
    }

    /// Configured contacts, sorted by id.
    #[must_use]
    pub fn contacts(&self) -> &[EmergencyContact] {
        &self.contacts
    }

    /// Number of opaque envelopes held for release.
    #[must_use]
    pub fn share_count(&self) -> usize {
        self.shares.len()
    }

    /// Current lifecycle state.
    #[must_use]
    pub const fn state(&self) -> EmergencyState {
        self.state
    }

    /// Unix seconds at which the release becomes due.
    #[must_use]
    pub const fn deadline(&self) -> u64 {
        self.last_check_in
            .saturating_add(self.inactivity_secs)
            .saturating_add(self.grace_secs)
    }

    /// Record a user check-in; resets reminders and the timer.
    ///
    /// # Errors
    ///
    /// A cancelled or released policy cannot be checked in.
    pub fn check_in(&mut self, now: u64) -> Result<()> {
        if self.state != EmergencyState::Armed {
            return Err(CoreError::new(ErrorCode::InvalidArgument));
        }
        self.last_check_in = now;
        self.reminder_sent = false;
        Ok(())
    }

    /// Extend the inactivity period.
    ///
    /// # Errors
    ///
    /// Rejects zero extensions and non-armed policies.
    pub fn extend(&mut self, extra_secs: u64) -> Result<()> {
        if extra_secs == 0 || self.state != EmergencyState::Armed {
            return Err(CoreError::new(ErrorCode::InvalidArgument));
        }
        self.inactivity_secs = self.inactivity_secs.saturating_add(extra_secs);
        self.reminder_sent = false;
        Ok(())
    }

    /// Cancel release permanently.
    pub fn cancel(&mut self) {
        self.state = EmergencyState::Cancelled;
    }

    /// Evaluate one cron tick. Reminder and release fire at most once.
    ///
    /// # Errors
    ///
    /// Returns an error for a policy whose state is inconsistent.
    pub fn tick(&mut self, now: u64) -> Result<EmergencyAction> {
        if self.state != EmergencyState::Armed {
            return Ok(EmergencyAction::None);
        }
        let reminder_at = self
            .last_check_in
            .saturating_add(self.inactivity_secs)
            .saturating_sub(self.reminder_lead_secs);
        if now >= self.deadline() {
            self.state = EmergencyState::Released;
            return Ok(EmergencyAction::Release {
                released_at: now,
                shares: self.shares.clone(),
            });
        }
        if now >= reminder_at && !self.reminder_sent {
            self.reminder_sent = true;
            return Ok(EmergencyAction::Reminder {
                deadline: self.deadline(),
            });
        }
        Ok(EmergencyAction::None)
    }
}

#[cfg(test)]
mod tests {
    use super::{EmergencyAction, EmergencyContact, EmergencyPolicy, EmergencyState, SealedShare};

    fn policy(now: u64) -> EmergencyPolicy {
        EmergencyPolicy::new(
            vec![EmergencyContact {
                id: "c1".to_string(),
                label: "Alice".to_string(),
                address: "alice@example.test".to_string(),
            }],
            vec![SealedShare {
                contact_id: "c1".to_string(),
                ciphertext: vec![1, 2, 3],
            }],
            10 * 86_400,
            86_400,
            3_600,
            now,
        )
        .expect("policy")
    }

    #[test]
    fn reminder_then_release_on_fake_clock() {
        let mut policy = policy(1_000);
        assert_eq!(policy.tick(1_000).expect("tick"), EmergencyAction::None);
        let reminder = 1_000 + 10 * 86_400 - 86_400;
        assert!(matches!(
            policy.tick(reminder).expect("tick"),
            EmergencyAction::Reminder { .. }
        ));
        assert_eq!(
            policy.tick(reminder + 1).expect("tick"),
            EmergencyAction::None
        );
        let release = policy.deadline();
        assert!(matches!(
            policy.tick(release).expect("tick"),
            EmergencyAction::Release { .. }
        ));
        assert_eq!(policy.state(), EmergencyState::Released);
    }

    #[test]
    fn check_in_resets_timer_and_reminder() {
        let mut policy = policy(1_000);
        let reminder = 1_000 + 10 * 86_400 - 86_400;
        assert!(matches!(
            policy.tick(reminder).expect("tick"),
            EmergencyAction::Reminder { .. }
        ));
        policy.check_in(reminder + 10).expect("check in");
        assert_eq!(
            policy.tick(reminder + 11).expect("tick"),
            EmergencyAction::None
        );
        assert!(policy.deadline() > reminder);
    }

    #[test]
    fn extend_and_cancel_prevent_release() {
        let mut policy = policy(0);
        policy.extend(86_400).expect("extend");
        assert!(policy.deadline() > 10 * 86_400);
        policy.cancel();
        assert_eq!(policy.tick(u64::MAX).expect("tick"), EmergencyAction::None);
        assert!(policy.check_in(1).is_err());
    }

    #[test]
    fn rejects_duplicate_and_malformed_contacts() {
        let base = EmergencyContact {
            id: "c1".to_string(),
            label: "Alice".to_string(),
            address: "alice@example.test".to_string(),
        };
        let shares = vec![SealedShare {
            contact_id: "c1".to_string(),
            ciphertext: vec![1],
        }];
        assert!(super::EmergencyPolicy::new(
            vec![base.clone(), base],
            shares.clone(),
            100,
            10,
            10,
            0,
        )
        .is_err());
        let mut bad = super::EmergencyContact {
            id: "c2".to_string(),
            label: "Bob".to_string(),
            address: "not-an-address".to_string(),
        };
        bad.address = "not-an-address".to_string();
        assert!(super::EmergencyPolicy::new(vec![bad], shares, 100, 10, 10, 0).is_err());
    }

    #[test]
    fn cron_share_alone_cannot_reconstruct() {
        let secret = crate::secret::SecretBytes::from_slice(&[7u8; 32]);
        let plan = super::EmergencySharePlan::new(&secret).expect("plan");
        let recovered =
            crate::shamir::combine_2_of_3(plan.contact_a(), plan.cron_share()).expect("combine");
        assert_eq!(recovered.expose_secret(), secret.expose_secret());
        assert!(crate::shamir::combine_2_of_3(plan.cron_share(), plan.cron_share()).is_err());
        assert!(crate::shamir::combine_2_of_3(plan.cron_share(), plan.contact_b()).is_ok());
    }

    #[test]
    fn release_contains_only_opaque_envelopes() {
        let mut policy = policy(0);
        let action = policy.tick(policy.deadline()).expect("tick");
        match action {
            EmergencyAction::Release { shares, .. } => {
                assert_eq!(shares.len(), 1);
                assert_eq!(shares[0].ciphertext, vec![1, 2, 3]);
            }
            _ => panic!("expected release"),
        }
    }
}
