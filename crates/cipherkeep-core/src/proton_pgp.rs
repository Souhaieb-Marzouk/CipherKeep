//! Proton Pass PGP-encrypted export support.
//!
//! Proton's encrypted ZIP contains `data.pgp`: an ASCII-armored OpenPGP
//! message encrypted with a passphrase. The decrypted payload is the same
//! JSON document used by the unencrypted Proton JSON export.
//!
//! Decryption itself lives in [`crate::openpgp`], which implements only the
//! passphrase-based subset of OpenPGP that Proton exports use.

use crate::CoreError;
use crate::ErrorCode;

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
    crate::openpgp::decrypt_symmetric_message(bytes, passphrase)
}
