//! Offline queue and bounded retry scheduling for sync batches.

use std::collections::VecDeque;

use super::SyncBatch;
use crate::error::{CoreError, ErrorCode, Result};

/// Retry policy.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct RetryPolicy {
    /// Maximum attempts per batch.
    pub max_attempts: u32,
    /// Base delay in milliseconds.
    pub base_delay_ms: u64,
    /// Delay cap in milliseconds.
    pub max_delay_ms: u64,
}

impl Default for RetryPolicy {
    fn default() -> Self {
        Self {
            max_attempts: 5,
            base_delay_ms: 1_000,
            max_delay_ms: 60_000,
        }
    }
}

/// FIFO offline queue with exponential backoff.
pub struct SyncScheduler {
    policy: RetryPolicy,
    queue: VecDeque<SyncBatch>,
    attempts: u32,
    online: bool,
    pending_delay_ms: Option<u64>,
}

impl SyncScheduler {
    /// Create a scheduler.
    #[must_use]
    pub fn new(policy: RetryPolicy) -> Self {
        Self {
            policy,
            queue: VecDeque::new(),
            attempts: 0,
            online: true,
            pending_delay_ms: None,
        }
    }

    /// Queue a batch. Duplicate versions are ignored.
    pub fn enqueue(&mut self, batch: SyncBatch) {
        let duplicate = self.queue.iter().any(|queued| {
            queued.vault_id == batch.vault_id
                && queued.device_id == batch.device_id
                && queued.versions == batch.versions
        });
        if !duplicate {
            self.queue.push_back(batch);
        }
    }

    /// Set connectivity.
    pub fn set_online(&mut self, online: bool) {
        self.online = online;
        if !online {
            self.pending_delay_ms = None;
        }
    }

    /// Next batch eligible to send, if online and no backoff is pending.
    pub fn next_batch(&mut self) -> Option<&SyncBatch> {
        if !self.online || self.pending_delay_ms.is_some() {
            return None;
        }
        self.queue.front()
    }

    /// Mark the head batch as delivered.
    pub fn success(&mut self) {
        self.queue.pop_front();
        self.attempts = 0;
        self.pending_delay_ms = None;
    }

    /// Record a failure and return the retry delay.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::LimitExceeded`] after `max_attempts`.
    pub fn failure(&mut self) -> Result<u64> {
        self.attempts = self.attempts.saturating_add(1);
        if self.attempts >= self.policy.max_attempts {
            return Err(CoreError::new(ErrorCode::LimitExceeded));
        }
        let exponent = self.attempts.saturating_sub(1).min(20);
        let delay = self
            .policy
            .base_delay_ms
            .saturating_mul(1u64 << exponent)
            .min(self.policy.max_delay_ms);
        self.pending_delay_ms = Some(delay);
        Ok(delay)
    }

    /// Clear the backoff after the delay has elapsed.
    pub fn ready(&mut self) {
        self.pending_delay_ms = None;
    }

    /// Number of queued batches.
    #[must_use]
    pub fn len(&self) -> usize {
        self.queue.len()
    }

    /// Whether the queue is empty.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.queue.is_empty()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::sync::SyncVersionVector;

    fn batch() -> SyncBatch {
        SyncBatch {
            vault_id: [1u8; 16],
            device_id: [2u8; 16],
            versions: SyncVersionVector::default(),
            manifests: Vec::new(),
        }
    }

    #[test]
    fn queues_and_retries_with_cap() {
        let mut scheduler = SyncScheduler::new(RetryPolicy {
            max_attempts: 3,
            base_delay_ms: 100,
            max_delay_ms: 250,
        });
        scheduler.enqueue(batch());
        assert_eq!(scheduler.len(), 1);
        assert!(scheduler.next_batch().is_some());
        assert_eq!(scheduler.failure().expect("delay"), 100);
        assert!(scheduler.next_batch().is_none());
        scheduler.ready();
        assert_eq!(scheduler.failure().expect("delay"), 200);
        scheduler.ready();
        assert!(scheduler.failure().is_err());
    }

    #[test]
    fn offline_queue_does_not_send() {
        let mut scheduler = SyncScheduler::new(RetryPolicy::default());
        scheduler.enqueue(batch());
        scheduler.set_online(false);
        assert!(scheduler.next_batch().is_none());
        scheduler.set_online(true);
        assert!(scheduler.next_batch().is_some());
        scheduler.success();
        assert!(scheduler.is_empty());
    }
}
