//! Versioned encrypted portable export/backup.
//!
//! The export password is independent from the vault master password. The
//! header binds magic, version, KDF parameters and salt through AAD; the body is
//! XChaCha20-Poly1305 ciphertext. Import validates everything before returning
//! plaintext and never writes a vault itself.

use getrandom::getrandom;
use zeroize::Zeroizing;

use crate::envelope::{Aad, Envelope};
use crate::error::{CoreError, ErrorCode, Result};
use crate::kdf::{
    derive_master_key, KdfParams, DEFAULT_ITERATIONS, DEFAULT_MEMORY_KIB, DEFAULT_PARALLELISM,
    SALT_LEN,
};
use crate::secret::SecretBytes;

/// Export file magic.
pub const EXPORT_MAGIC: [u8; 8] = *b"CKEXP1\0\0";
/// Current export format version.
pub const EXPORT_VERSION: u16 = 2;
/// Fixed header length before the encrypted envelope.
pub const EXPORT_HEADER_LEN: usize = 8 + 2 + 4 + 4 + 1 + 16;
/// Maximum accepted export size.
pub const MAX_EXPORT_BYTES: usize = 24 * 1024 * 1024;
/// Minimum separate export-password length.
pub const MIN_EXPORT_PASSWORD_LEN: usize = 12;
/// Plaintext is padded to this block size before encryption.
pub const EXPORT_PAD_BLOCK: usize = 4 * 1024;

/// Encrypt portable item JSON under a separate export password.
///
/// # Errors
///
/// Returns a redacted error for a short password, oversized input or entropy
/// failure.
pub fn export_backup(items_json: &[u8], export_password: &str) -> Result<Vec<u8>> {
    if export_password.len() < MIN_EXPORT_PASSWORD_LEN || export_password.len() > 1_024 {
        return Err(CoreError::new(ErrorCode::PolicyRejected));
    }
    if items_json.is_empty() || items_json.len() > MAX_EXPORT_BYTES {
        return Err(CoreError::new(ErrorCode::LimitExceeded));
    }
    let mut salt = [0u8; SALT_LEN];
    getrandom(&mut salt).map_err(|_| CoreError::new(ErrorCode::Internal))?;
    if salt.iter().all(|byte| *byte == 0) {
        return Err(CoreError::new(ErrorCode::Internal));
    }
    let params = KdfParams::new(
        DEFAULT_MEMORY_KIB,
        DEFAULT_ITERATIONS,
        DEFAULT_PARALLELISM,
        salt,
    );
    let password = SecretBytes::from_slice(export_password.as_bytes());
    let key = derive_master_key(&password, &params)?;
    let header = encode_header(&params);
    let aad = Aad::export(&header);
    let length =
        u32::try_from(items_json.len()).map_err(|_| CoreError::new(ErrorCode::LimitExceeded))?;
    let unpadded = 4usize
        .checked_add(items_json.len())
        .ok_or_else(|| CoreError::new(ErrorCode::LimitExceeded))?;
    let padded_len = unpadded
        .checked_add(EXPORT_PAD_BLOCK - 1)
        .ok_or_else(|| CoreError::new(ErrorCode::LimitExceeded))?
        / EXPORT_PAD_BLOCK
        * EXPORT_PAD_BLOCK;
    let mut padded = Zeroizing::new(vec![0u8; padded_len]);
    padded[..4].copy_from_slice(&length.to_be_bytes());
    padded[4..4 + items_json.len()].copy_from_slice(items_json);
    let plaintext = padded;
    let envelope = Envelope::seal(&key, &aad, &plaintext)?;
    let mut output = header;
    output.extend_from_slice(&envelope.encode());
    Ok(output)
}

/// Decrypt a portable export, returning canonical item JSON bytes.
///
/// # Errors
///
/// Returns [`ErrorCode::UnsupportedVersion`] for a future format,
/// [`ErrorCode::CorruptData`] for malformed/tampered data and
/// [`ErrorCode::AuthenticationFailed`] for a wrong password.
pub fn import_backup(export_bytes: &[u8], export_password: &str) -> Result<SecretBytes> {
    if export_bytes.len() < EXPORT_HEADER_LEN || export_bytes.len() > MAX_EXPORT_BYTES {
        return Err(CoreError::new(ErrorCode::CorruptData));
    }
    if export_password.len() < MIN_EXPORT_PASSWORD_LEN || export_password.len() > 1_024 {
        return Err(CoreError::new(ErrorCode::PolicyRejected));
    }
    let header = &export_bytes[..EXPORT_HEADER_LEN];
    let params = decode_header(header)?;
    let envelope = Envelope::decode(&export_bytes[EXPORT_HEADER_LEN..])?;
    let password = SecretBytes::from_slice(export_password.as_bytes());
    let key = derive_master_key(&password, &params)?;
    let aad = Aad::export(header);
    let plaintext = envelope.open(&key, &aad)?;
    if plaintext.len() < 4 {
        return Err(CoreError::new(ErrorCode::CorruptData));
    }
    let length = u32::from_be_bytes([
        plaintext.expose_secret()[0],
        plaintext.expose_secret()[1],
        plaintext.expose_secret()[2],
        plaintext.expose_secret()[3],
    ]) as usize;
    if length > plaintext.len() - 4 {
        return Err(CoreError::new(ErrorCode::CorruptData));
    }
    Ok(SecretBytes::from_slice(
        &plaintext.expose_secret()[4..4 + length],
    ))
}

fn encode_header(params: &KdfParams) -> Vec<u8> {
    let mut header = Vec::with_capacity(EXPORT_HEADER_LEN);
    header.extend_from_slice(&EXPORT_MAGIC);
    header.extend_from_slice(&EXPORT_VERSION.to_be_bytes());
    header.extend_from_slice(&params.m_cost_kib().to_be_bytes());
    header.extend_from_slice(&params.t_cost().to_be_bytes());
    header.push(params.p_cost());
    header.extend_from_slice(params.salt());
    header
}

fn decode_header(header: &[u8]) -> Result<KdfParams> {
    if header.len() != EXPORT_HEADER_LEN || header[..8] != EXPORT_MAGIC {
        return Err(CoreError::new(ErrorCode::CorruptData));
    }
    let version = u16::from_be_bytes([header[8], header[9]]);
    if version != EXPORT_VERSION {
        return Err(CoreError::new(ErrorCode::UnsupportedVersion));
    }
    let m_cost = u32::from_be_bytes([header[10], header[11], header[12], header[13]]);
    let t_cost = u32::from_be_bytes([header[14], header[15], header[16], header[17]]);
    let p_cost = header[18];
    let mut salt = [0u8; SALT_LEN];
    salt.copy_from_slice(&header[19..EXPORT_HEADER_LEN]);
    Ok(KdfParams::new(m_cost, t_cost, p_cost, salt))
}

#[cfg(test)]
mod tests {
    use super::*;

    const ITEMS: &[u8] =
        br#"{"items":[{"title":"Example","username":"alice","password":"secret"}]}"#;

    #[test]
    fn export_round_trip_returns_items() {
        let bytes = export_backup(ITEMS, "correct horse battery staple").expect("export");
        assert_eq!(&bytes[..8], &EXPORT_MAGIC);
        assert!(bytes.len() > EXPORT_HEADER_LEN);
        let plaintext = import_backup(&bytes, "correct horse battery staple").expect("import");
        assert_eq!(plaintext.expose_secret(), ITEMS);
    }

    #[test]
    fn wrong_password_and_tamper_fail_closed() {
        let bytes = export_backup(ITEMS, "correct horse battery staple").expect("export");
        assert_eq!(
            import_backup(&bytes, "wrong horse battery staple")
                .unwrap_err()
                .code(),
            ErrorCode::AuthenticationFailed
        );
        let mut tampered = bytes.clone();
        let last = tampered.len() - 1;
        tampered[last] ^= 0x01;
        assert!(import_backup(&tampered, "correct horse battery staple").is_err());
        let mut header_tamper = bytes;
        header_tamper[12] ^= 0x01;
        assert!(import_backup(&header_tamper, "correct horse battery staple").is_err());
    }

    #[test]
    fn short_password_and_empty_payload_are_refused() {
        assert_eq!(
            export_backup(ITEMS, "short").unwrap_err().code(),
            ErrorCode::PolicyRejected
        );
        assert!(export_backup(b"", "correct horse battery staple").is_err());
    }

    #[test]
    fn future_version_is_refused_before_decryption() {
        let mut bytes = export_backup(ITEMS, "correct horse battery staple").expect("export");
        bytes[9] = 3;
        assert_eq!(
            import_backup(&bytes, "correct horse battery staple")
                .unwrap_err()
                .code(),
            ErrorCode::UnsupportedVersion
        );
    }

    #[test]
    fn arbitrary_bytes_do_not_panic() {
        for seed in 0u32..256 {
            let mut bytes = vec![0u8; 64];
            for (index, byte) in bytes.iter_mut().enumerate() {
                *byte = ((seed.wrapping_mul(13).wrapping_add(index as u32)) & 0xff) as u8;
            }
            let _ = import_backup(&bytes, "correct horse battery staple");
        }
    }
}
