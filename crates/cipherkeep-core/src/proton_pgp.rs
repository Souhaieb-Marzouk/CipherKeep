//! Proton Pass PGP-encrypted export support.
//!
//! Proton's encrypted ZIP contains `data.pgp`: an ASCII-armored OpenPGP
//! message encrypted with a passphrase. The decrypted payload is the same
//! JSON document used by the unencrypted Proton JSON export.

use crate::{CoreError, ErrorCode};
use pgp::{Deserializable, Message};

fn corrupt() -> CoreError {
    CoreError::new(ErrorCode::CorruptData)
}

/// Decrypt a Proton `data.pgp` payload with its export passphrase.
///
/// # Errors
///
/// Returns a redacted error for wrong passphrases, corrupt armor or a message
/// that does not contain literal data.
pub fn decrypt_proton_pgp(bytes: &[u8], passphrase: &str) -> crate::Result<Vec<u8>> {
    if bytes.is_empty() || passphrase.is_empty() {
        return Err(corrupt());
    }
    let message = Message::from_armor_single(bytes)
        .map(|(message, _)| message)
        .or_else(|_| Message::from_bytes(bytes))
        .map_err(|_| corrupt())?;
    let decrypted = message
        .decrypt_with_password(|| passphrase.to_string())
        .map_err(|_| corrupt())?;
    decrypted
        .get_content()
        .map_err(|_| corrupt())?
        .ok_or_else(corrupt)
}
