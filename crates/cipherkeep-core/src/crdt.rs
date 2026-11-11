//! CRDT primitives: actor ids, Lamport clocks and version vectors.
//!
//! Every field write is tagged with a Lamport clock and the actor that made it.
//! The pair `(counter, actor)` gives a deterministic total order for concurrent
//! writes, and tombstones use the same ordering with a tie rule in `item.rs`.

use core::fmt;
use std::collections::BTreeMap;

use getrandom::getrandom;

use crate::error::{CoreError, ErrorCode, Result};

/// A stable per-device actor identifier.
#[derive(Clone, Copy, Eq, Hash, Ord, PartialEq, PartialOrd)]
pub struct ActorId([u8; 16]);

impl ActorId {
    /// Generate a random actor id.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::Internal`] if the OS CSPRNG fails.
    pub fn generate() -> Result<Self> {
        let mut bytes = [0u8; 16];
        getrandom(&mut bytes).map_err(|_| CoreError::new(ErrorCode::Internal))?;
        if bytes.iter().all(|byte| *byte == 0) {
            return Err(CoreError::new(ErrorCode::Internal));
        }
        Ok(Self(bytes))
    }

    /// Construct an actor id from stable bytes.
    #[must_use]
    pub const fn from_bytes(bytes: [u8; 16]) -> Self {
        Self(bytes)
    }

    /// The stable bytes.
    #[must_use]
    pub const fn as_bytes(&self) -> &[u8; 16] {
        &self.0
    }
}

impl fmt::Debug for ActorId {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str("ActorId(")?;
        for byte in self.0 {
            write!(formatter, "{byte:02x}")?;
        }
        formatter.write_str(")")
    }
}

/// A Lamport clock stamped with the actor that produced the write.
#[derive(Clone, Copy, Debug, Eq, Ord, PartialEq, PartialOrd)]
pub struct LamportClock {
    counter: u64,
    actor: ActorId,
}

impl LamportClock {
    /// Construct a clock.
    #[must_use]
    pub const fn new(counter: u64, actor: ActorId) -> Self {
        Self { counter, actor }
    }

    /// The logical counter.
    #[must_use]
    pub const fn counter(self) -> u64 {
        self.counter
    }

    /// The actor that produced the write.
    #[must_use]
    pub const fn actor(self) -> ActorId {
        self.actor
    }
}

/// A per-actor version vector used by sync and tombstone retention.
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct VersionVector {
    entries: BTreeMap<ActorId, u64>,
}

impl VersionVector {
    /// Create an empty vector.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    /// Observe a clock, retaining the maximum counter for its actor.
    pub fn observe(&mut self, clock: LamportClock) {
        let entry = self.entries.entry(clock.actor()).or_insert(0);
        if clock.counter() > *entry {
            *entry = clock.counter();
        }
    }

    /// The highest counter observed for an actor, or zero.
    #[must_use]
    pub fn get(&self, actor: &ActorId) -> u64 {
        self.entries.get(actor).copied().unwrap_or(0)
    }

    /// Merge another vector into this one.
    pub fn merge(&mut self, other: &Self) {
        for (actor, counter) in &other.entries {
            let entry = self.entries.entry(*actor).or_insert(0);
            if *counter > *entry {
                *entry = *counter;
            }
        }
    }

    /// Whether this vector covers every entry in `other`.
    #[must_use]
    pub fn covers(&self, other: &Self) -> bool {
        other
            .entries
            .iter()
            .all(|(actor, counter)| self.get(actor) >= *counter)
    }

    /// Iterate over `(actor, counter)` pairs.
    pub fn iter(&self) -> impl Iterator<Item = (&ActorId, &u64)> {
        self.entries.iter()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn actor(byte: u8) -> ActorId {
        ActorId::from_bytes([byte; 16])
    }

    #[test]
    fn actor_ids_round_trip() {
        let actor = actor(7);
        assert_eq!(actor.as_bytes(), &[7u8; 16]);
        let generated = ActorId::generate().expect("actor");
        assert!(generated.as_bytes().iter().any(|byte| *byte != 0));
    }

    #[test]
    fn clocks_order_by_counter_then_actor() {
        let low = LamportClock::new(1, actor(2));
        let high = LamportClock::new(2, actor(1));
        let tie_low = LamportClock::new(1, actor(1));
        assert!(high > low);
        assert!(low > tie_low);
    }

    #[test]
    fn version_vectors_merge_and_cover() {
        let mut first = VersionVector::new();
        first.observe(LamportClock::new(3, actor(1)));
        first.observe(LamportClock::new(2, actor(2)));

        let mut second = VersionVector::new();
        second.observe(LamportClock::new(1, actor(1)));
        second.observe(LamportClock::new(4, actor(2)));
        second.observe(LamportClock::new(1, actor(3)));

        assert!(!first.covers(&second));
        first.merge(&second);
        assert!(first.covers(&second));
        assert_eq!(first.get(&actor(1)), 3);
        assert_eq!(first.get(&actor(2)), 4);
        assert_eq!(first.get(&actor(3)), 1);
    }
}
