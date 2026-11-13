//! WebAuthn PRF hardware-key wrapping.
//!
//! The authenticator returns a high-entropy PRF output. CipherKeep derives a
//! wrapping key with HKDF and seals a second copy of the keyring. The master
//! password and recovery shares remain independent fallbacks.

use hkdf::Hkdf;
use sha2::Sha256;
use zeroize::Zeroizing;

use crate::envelope::Envelope;
use crate::error::{CoreError, ErrorCode, Result};
use crate::header::AAD_PREFIX_LEN;
use crate::keyring::Keyring;
use crate::keys::{random_array, MasterKey, KEY_LEN};
use crate::secret::SecretBytes;

const DOMAIN: &[u8] = b"CipherKeep/webauthn-prf-v1";

/// Seal a keyring copy under a WebAuthn PRF output.
///
/// # Errors
///
/// Returns an error for a malformed output, RNG failure or sealing failure.
pub fn seal_keyring(
    keyring: &Keyring,
    prf_output: &[u8],
    header_prefix: &[u8; AAD_PREFIX_LEN],
) -> Result<Vec<u8>> {
    if prf_output.len() < 32 {
        return Err(CoreError::new(ErrorCode::InvalidArgument));
    }
    let wrapping = derive_wrapping_key(prf_output, header_prefix)?;
    let nonce = random_array::<24>()?;
    let master = MasterKey::from_secret_bytes(SecretBytes::from_slice(&wrapping[..]))?;
    let envelope = keyring.seal(&master, header_prefix, &nonce)?;
    Ok(envelope.encode())
}

/// Open a keyring copy sealed under a WebAuthn PRF output.
///
/// # Errors
///
/// Returns [`ErrorCode::AuthenticationFailed`] for a wrong PRF, wrong header or
/// tampered envelope.
pub fn open_keyring(
    encoded: &[u8],
    prf_output: &[u8],
    header_prefix: &[u8; AAD_PREFIX_LEN],
) -> Result<Keyring> {
    if prf_output.len() < 32 {
        return Err(CoreError::new(ErrorCode::InvalidArgument));
    }
    let wrapping = derive_wrapping_key(prf_output, header_prefix)?;
    let master = MasterKey::from_secret_bytes(SecretBytes::from_slice(&wrapping[..]))?;
    Keyring::open_encoded(&master, header_prefix, &envelope_nonce(encoded)?, encoded)
        .map_err(|_| CoreError::new(ErrorCode::AuthenticationFailed))
}

/// Derive the PRF wrapping master key.
///
/// # Errors
///
/// Rejects a malformed output or HKDF failure.
pub fn derive_master(prf_output: &[u8], header_prefix: &[u8; AAD_PREFIX_LEN]) -> Result<MasterKey> {
    if prf_output.len() < 32 {
        return Err(CoreError::new(ErrorCode::InvalidArgument));
    }
    let wrapping = derive_wrapping_key(prf_output, header_prefix)?;
    MasterKey::from_secret_bytes(SecretBytes::from_slice(&wrapping[..]))
}

fn derive_wrapping_key(
    prf_output: &[u8],
    header_prefix: &[u8; AAD_PREFIX_LEN],
) -> Result<Zeroizing<[u8; KEY_LEN]>> {
    let hkdf = Hkdf::<Sha256>::new(Some(header_prefix), prf_output);
    let mut key = Zeroizing::new([0u8; KEY_LEN]);
    hkdf.expand(DOMAIN, key.as_mut())
        .map_err(|_| CoreError::new(ErrorCode::Internal))?;
    Ok(key)
}

fn envelope_nonce(encoded: &[u8]) -> Result<[u8; 24]> {
    let envelope =
        Envelope::decode(encoded).map_err(|_| CoreError::new(ErrorCode::AuthenticationFailed))?;
    Ok(*envelope.nonce())
}

#[cfg(test)]
mod tests {
    use super::{open_keyring, seal_keyring};
    use crate::header::AAD_PREFIX_LEN;
    use crate::keyring::Keyring;

    fn prefix() -> [u8; AAD_PREFIX_LEN] {
        [7u8; AAD_PREFIX_LEN]
    }

    #[test]
    fn seals_and_opens_keyring_copy() {
        let keyring = Keyring::generate().expect("keyring");
        let prf = [9u8; 32];
        let sealed = seal_keyring(&keyring, &prf, &prefix()).expect("seal");
        let opened = open_keyring(&sealed, &prf, &prefix()).expect("open");
        assert_eq!(opened.first_vault_id(), keyring.first_vault_id());
    }

    #[test]
    fn wrong_prf_and_prefix_fail() {
        let keyring = Keyring::generate().expect("keyring");
        let sealed = seal_keyring(&keyring, &[9u8; 32], &prefix()).expect("seal");
        assert!(open_keyring(&sealed, &[8u8; 32], &prefix()).is_err());
        assert!(open_keyring(&sealed, &[9u8; 32], &[6u8; AAD_PREFIX_LEN]).is_err());
    }

    #[test]
    fn short_prf_is_rejected() {
        let keyring = Keyring::generate().expect("keyring");
        assert!(seal_keyring(&keyring, &[0u8; 16], &prefix()).is_err());
    }
}
