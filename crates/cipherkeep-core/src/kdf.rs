//! Argon2id key derivation and parameter policy.
//!
//! The master key is derived from the master password with Argon2id, version
//! 0x13, using a per-vault random salt. Parameters are stored in the vault
//! header and are validated against a floor on every read; they may only
//! increase.
//!
//! # Deliberate limitations
//!
//! The pinned `argon2` crate does not expose mid-derivation progress or
//! cancellation. Platform layers must run this function on a worker thread and
//! discard the result if the user cancels; the temporary output buffer is
//! zeroized on every exit path. Do not claim cooperative cancellation inside
//! Argon2.

use argon2::{Algorithm, Argon2, Params, Version};
use getrandom::getrandom;
use zeroize::Zeroizing;

use crate::error::{CoreError, ErrorCode, Result};
use crate::secret::SecretBytes;

/// Length of the derived master key in bytes.
pub const MASTER_KEY_LEN: usize = 32;

/// Length of the Argon2id salt in bytes.
pub const SALT_LEN: usize = 16;

/// Default memory cost in KiB (64 MiB).
pub const DEFAULT_MEMORY_KIB: u32 = 64 * 1024;

/// Default iteration count.
pub const DEFAULT_ITERATIONS: u32 = 3;

/// Default parallelism.
pub const DEFAULT_PARALLELISM: u8 = 4;

/// Maximum memory cost accepted from a vault header, in KiB (256 MiB).
pub const MAX_MEMORY_KIB: u32 = 256 * 1024;

/// Maximum iteration count accepted from a vault header.
pub const MAX_ITERATIONS: u32 = 10;

/// Maximum parallelism accepted from a vault header.
pub const MAX_PARALLELISM: u8 = 16;

/// Supported key-derivation algorithms.
///
/// Only Argon2id is defined today. The numeric value is part of the vault
/// header wire format and must not be reused.
#[repr(u8)]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum KdfAlgorithm {
    /// Argon2id, version 0x13.
    Argon2id = 1,
}

impl KdfAlgorithm {
    /// The stable numeric value stored in the header.
    #[must_use]
    pub const fn as_u8(self) -> u8 {
        self as u8
    }
}

impl TryFrom<u8> for KdfAlgorithm {
    type Error = CoreError;

    fn try_from(value: u8) -> Result<Self> {
        match value {
            1 => Ok(Self::Argon2id),
            _ => Err(CoreError::new(ErrorCode::UnsupportedVersion)),
        }
    }
}

/// Argon2id parameters, including the per-vault salt.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct KdfParams {
    m_cost_kib: u32,
    t_cost: u32,
    p_cost: u8,
    salt: [u8; SALT_LEN],
}

impl KdfParams {
    /// Construct a parameter set.
    ///
    /// This does not validate the values; call [`KdfParams::validate`] before
    /// deriving a key or accepting the parameters from storage.
    #[must_use]
    pub const fn new(m_cost_kib: u32, t_cost: u32, p_cost: u8, salt: [u8; SALT_LEN]) -> Self {
        Self {
            m_cost_kib,
            t_cost,
            p_cost,
            salt,
        }
    }

    /// Generate the default parameters with a fresh OS-random salt.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::Internal`] if the operating system CSPRNG fails.
    pub fn generate() -> Result<Self> {
        let mut salt = [0u8; SALT_LEN];
        getrandom(&mut salt).map_err(|_| CoreError::new(ErrorCode::Internal))?;
        if salt.iter().all(|byte| *byte == 0) {
            return Err(CoreError::new(ErrorCode::Internal));
        }
        Ok(Self::new(
            DEFAULT_MEMORY_KIB,
            DEFAULT_ITERATIONS,
            DEFAULT_PARALLELISM,
            salt,
        ))
    }

    /// Memory cost in KiB.
    #[must_use]
    pub const fn m_cost_kib(&self) -> u32 {
        self.m_cost_kib
    }

    /// Iteration count.
    #[must_use]
    pub const fn t_cost(&self) -> u32 {
        self.t_cost
    }

    /// Parallelism.
    #[must_use]
    pub const fn p_cost(&self) -> u8 {
        self.p_cost
    }

    /// Per-vault salt.
    #[must_use]
    pub const fn salt(&self) -> &[u8; SALT_LEN] {
        &self.salt
    }

    /// Validate the parameters against a policy floor and ceiling.
    ///
    /// # Errors
    ///
    /// * [`ErrorCode::PolicyRejected`] if a value is below the floor, above the
    ///   ceiling, or the salt is all zero.
    /// * [`ErrorCode::InvalidArgument`] if the values cannot form a valid
    ///   Argon2id parameter set.
    pub fn validate(&self, policy: &KdfPolicy) -> Result<()> {
        if self.p_cost == 0 || self.t_cost == 0 || self.m_cost_kib == 0 {
            return Err(CoreError::new(ErrorCode::InvalidArgument));
        }
        if self.m_cost_kib < 8 * u32::from(self.p_cost) {
            return Err(CoreError::new(ErrorCode::InvalidArgument));
        }
        if self.salt.iter().all(|byte| *byte == 0) {
            return Err(CoreError::new(ErrorCode::PolicyRejected));
        }
        if self.m_cost_kib < policy.min_memory_kib
            || self.m_cost_kib > policy.max_memory_kib
            || self.t_cost < policy.min_iterations
            || self.t_cost > policy.max_iterations
            || self.p_cost < policy.min_parallelism
            || self.p_cost > policy.max_parallelism
        {
            return Err(CoreError::new(ErrorCode::PolicyRejected));
        }
        Ok(())
    }
}

/// Security policy for Argon2id parameters.
///
/// The floor is what this build accepts from storage. Raising a floor requires
/// a vault-format migration path; lowering it requires a security review.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct KdfPolicy {
    /// Minimum accepted memory cost in KiB.
    pub min_memory_kib: u32,
    /// Minimum accepted iteration count.
    pub min_iterations: u32,
    /// Minimum accepted parallelism.
    pub min_parallelism: u8,
    /// Maximum accepted memory cost in KiB.
    pub max_memory_kib: u32,
    /// Maximum accepted iteration count.
    pub max_iterations: u32,
    /// Maximum accepted parallelism.
    pub max_parallelism: u8,
}

impl Default for KdfPolicy {
    fn default() -> Self {
        Self {
            min_memory_kib: DEFAULT_MEMORY_KIB,
            min_iterations: DEFAULT_ITERATIONS,
            min_parallelism: DEFAULT_PARALLELISM,
            max_memory_kib: MAX_MEMORY_KIB,
            max_iterations: MAX_ITERATIONS,
            max_parallelism: MAX_PARALLELISM,
        }
    }
}

impl KdfPolicy {
    /// Validate a parameter set against this policy.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::PolicyRejected`] or [`ErrorCode::InvalidArgument`]
    /// as described by [`KdfParams::validate`].
    pub fn validate(&self, params: &KdfParams) -> Result<()> {
        params.validate(self)
    }
}

/// Derive the 256-bit master key with the default security policy.
///
/// # Errors
///
/// Returns a redacted [`CoreError`] if the parameters fail policy, cannot be
/// represented by Argon2, or the underlying implementation fails.
pub fn derive_master_key(password: &SecretBytes, params: &KdfParams) -> Result<SecretBytes> {
    derive_master_key_with_policy(password, params, &KdfPolicy::default())
}

/// Derive the 256-bit master key with an explicit policy.
///
/// This exists for tests and for controlled migrations. Production callers
/// should use [`derive_master_key`].
///
/// # Errors
///
/// Returns a redacted [`CoreError`] if the parameters fail policy, cannot be
/// represented by Argon2, or the underlying implementation fails.
pub fn derive_master_key_with_policy(
    password: &SecretBytes,
    params: &KdfParams,
    policy: &KdfPolicy,
) -> Result<SecretBytes> {
    params.validate(policy)?;

    let argon_params = Params::new(
        params.m_cost_kib,
        params.t_cost,
        u32::from(params.p_cost),
        Some(MASTER_KEY_LEN),
    )
    .map_err(|_| CoreError::new(ErrorCode::InvalidArgument))?;
    let argon2 = Argon2::new(Algorithm::Argon2id, Version::V0x13, argon_params);

    let mut output = Zeroizing::new([0u8; MASTER_KEY_LEN]);
    argon2
        .hash_password_into(password.expose_secret(), params.salt(), &mut output[..])
        .map_err(|_| CoreError::new(ErrorCode::Internal))?;
    Ok(SecretBytes::from_slice(&output[..]))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fast_policy() -> KdfPolicy {
        KdfPolicy {
            min_memory_kib: 8,
            min_iterations: 1,
            min_parallelism: 1,
            max_memory_kib: 2048,
            max_iterations: 2,
            max_parallelism: 2,
        }
    }

    #[test]
    fn algorithm_ids_are_stable() {
        assert_eq!(KdfAlgorithm::Argon2id.as_u8(), 1);
        assert_eq!(KdfAlgorithm::try_from(1), Ok(KdfAlgorithm::Argon2id));
        assert_eq!(
            KdfAlgorithm::try_from(2).unwrap_err().code(),
            ErrorCode::UnsupportedVersion
        );
    }

    #[test]
    fn weak_parameters_are_rejected_by_default_policy() {
        let params = KdfParams::new(1024, 1, 1, [7u8; SALT_LEN]);
        assert_eq!(
            params.validate(&KdfPolicy::default()).unwrap_err().code(),
            ErrorCode::PolicyRejected
        );
    }

    #[test]
    fn zero_salt_is_rejected() {
        let params = KdfParams::new(
            DEFAULT_MEMORY_KIB,
            DEFAULT_ITERATIONS,
            DEFAULT_PARALLELISM,
            [0u8; SALT_LEN],
        );
        assert_eq!(
            params.validate(&KdfPolicy::default()).unwrap_err().code(),
            ErrorCode::PolicyRejected
        );
    }

    #[test]
    fn impossible_parameters_are_invalid_arguments() {
        let params = KdfParams::new(1024, 1, 0, [7u8; SALT_LEN]);
        assert_eq!(
            params.validate(&fast_policy()).unwrap_err().code(),
            ErrorCode::InvalidArgument
        );

        let params = KdfParams::new(7, 1, 1, [7u8; SALT_LEN]);
        assert_eq!(
            params.validate(&fast_policy()).unwrap_err().code(),
            ErrorCode::InvalidArgument
        );
    }

    #[test]
    fn generation_uses_default_parameters_and_a_nonzero_salt() {
        let params = KdfParams::generate().expect("OS CSPRNG should be available");
        assert_eq!(params.m_cost_kib(), DEFAULT_MEMORY_KIB);
        assert_eq!(params.t_cost(), DEFAULT_ITERATIONS);
        assert_eq!(params.p_cost(), DEFAULT_PARALLELISM);
        assert!(params.salt().iter().any(|byte| *byte != 0));
        params
            .validate(&KdfPolicy::default())
            .expect("generated parameters must satisfy the default policy");
    }

    #[test]
    fn same_password_and_salt_is_deterministic() {
        let policy = fast_policy();
        let params = KdfParams::new(1024, 1, 1, [7u8; SALT_LEN]);
        let password = SecretBytes::from_slice(b"correct horse battery staple");
        let first = derive_master_key_with_policy(&password, &params, &policy).expect("derivation");
        let second =
            derive_master_key_with_policy(&password, &params, &policy).expect("derivation");
        assert!(first == second);
        assert_eq!(first.len(), MASTER_KEY_LEN);
    }

    #[test]
    fn different_salts_produce_different_keys() {
        let policy = fast_policy();
        let password = SecretBytes::from_slice(b"correct horse battery staple");
        let first = derive_master_key_with_policy(
            &password,
            &KdfParams::new(1024, 1, 1, [7u8; SALT_LEN]),
            &policy,
        )
        .expect("derivation");
        let second = derive_master_key_with_policy(
            &password,
            &KdfParams::new(1024, 1, 1, [8u8; SALT_LEN]),
            &policy,
        )
        .expect("derivation");
        assert!(first != second);
    }

    #[test]
    fn different_passwords_produce_different_keys() {
        let policy = fast_policy();
        let params = KdfParams::new(1024, 1, 1, [7u8; SALT_LEN]);
        let first = derive_master_key_with_policy(
            &SecretBytes::from_slice(b"password one"),
            &params,
            &policy,
        )
        .expect("derivation");
        let second = derive_master_key_with_policy(
            &SecretBytes::from_slice(b"password two"),
            &params,
            &policy,
        )
        .expect("derivation");
        assert!(first != second);
    }

    #[test]
    fn derivation_rejects_weak_parameters_before_doing_work() {
        let password = SecretBytes::from_slice(b"password");
        let params = KdfParams::new(1024, 1, 1, [7u8; SALT_LEN]);
        assert_eq!(
            derive_master_key(&password, &params).unwrap_err().code(),
            ErrorCode::PolicyRejected
        );
    }
}
