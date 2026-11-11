//! Shared auto-lock policy.
//!
//! The policy is intentionally clock-injected so both platforms and their tests
//! can use a monotonic fake clock. It contains no secrets and no platform code.

/// Default inactivity timeout in seconds.
pub const DEFAULT_TIMEOUT_SECS: u64 = 60;
/// Seconds before the deadline at which the UI may warn the user.
pub const WARN_BEFORE_SECS: u64 = 5;

/// Auto-lock policy state.
#[derive(Debug, Clone, Copy, Eq, PartialEq)]
pub struct LockPolicy {
    timeout_secs: u64,
    last_activity: u64,
    editing: bool,
    locked: bool,
}

impl Default for LockPolicy {
    fn default() -> Self {
        Self::new(DEFAULT_TIMEOUT_SECS, 0)
    }
}

impl LockPolicy {
    /// Create an unlocked policy with a timeout and a starting clock value.
    #[must_use]
    pub const fn new(timeout_secs: u64, now: u64) -> Self {
        Self {
            timeout_secs,
            last_activity: now,
            editing: false,
            locked: false,
        }
    }

    /// Record user activity at a monotonic clock value.
    pub fn record_activity(&mut self, now: u64) {
        self.last_activity = now;
    }

    /// Mark whether the user is editing a secret. Editing suppresses an
    /// automatic expiry so a long form entry is not interrupted.
    pub fn set_editing(&mut self, editing: bool) {
        self.editing = editing;
    }

    /// Lock immediately.
    pub fn lock(&mut self) {
        self.locked = true;
    }

    /// Whether the vault is currently locked.
    #[must_use]
    pub const fn is_locked(&self) -> bool {
        self.locked
    }

    /// Whether the inactivity deadline has passed, ignoring editing.
    #[must_use]
    pub const fn deadline_passed(&self, now: u64) -> bool {
        now.saturating_sub(self.last_activity) >= self.timeout_secs
    }

    /// Whether the session should lock now. Editing suppresses the automatic
    /// path; an explicit [`LockPolicy::lock`] still applies.
    #[must_use]
    pub const fn should_lock(&self, now: u64) -> bool {
        !self.locked && !self.editing && self.deadline_passed(now)
    }

    /// Whether the UI should warn that the session is about to lock.
    #[must_use]
    pub const fn should_warn(&self, now: u64) -> bool {
        !self.locked
            && !self.editing
            && !self.deadline_passed(now)
            && now.saturating_sub(self.last_activity)
                >= self.timeout_secs.saturating_sub(WARN_BEFORE_SECS)
    }

    /// Seconds remaining before automatic lock, or zero once passed.
    #[must_use]
    pub const fn remaining(&self, now: u64) -> u64 {
        self.timeout_secs
            .saturating_sub(now.saturating_sub(self.last_activity))
    }

    /// The configured timeout.
    #[must_use]
    pub const fn timeout_secs(&self) -> u64 {
        self.timeout_secs
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn locks_after_the_timeout() {
        let mut policy = LockPolicy::new(60, 100);
        assert!(!policy.should_lock(159));
        assert!(policy.should_lock(160));
        policy.lock();
        assert!(policy.is_locked());
    }

    #[test]
    fn activity_extends_the_deadline() {
        let mut policy = LockPolicy::new(60, 0);
        policy.record_activity(50);
        assert!(!policy.should_lock(109));
        assert!(policy.should_lock(110));
    }

    #[test]
    fn editing_suppresses_automatic_lock_and_warning() {
        let mut policy = LockPolicy::new(60, 0);
        policy.set_editing(true);
        assert!(!policy.should_lock(600));
        assert!(!policy.should_warn(600));
        assert!(policy.deadline_passed(600));
    }

    #[test]
    fn warns_near_the_deadline() {
        let policy = LockPolicy::new(60, 0);
        assert!(!policy.should_warn(54));
        assert!(policy.should_warn(55));
        assert!(policy.should_warn(59));
        assert!(!policy.should_warn(60));
    }

    #[test]
    fn remaining_is_saturating() {
        let policy = LockPolicy::new(60, 10);
        assert_eq!(policy.remaining(10), 60);
        assert_eq!(policy.remaining(40), 30);
        assert_eq!(policy.remaining(500), 0);
    }
}
