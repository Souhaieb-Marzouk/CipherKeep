//! Known-answer vector for the pinned Argon2id parameters.
//!
//! The expected value was produced by `argon2-cffi` 25.1.0 / libargon2, not by
//! the Rust implementation under test. Parameters: Argon2id v=19, m=65536 KiB,
//! t=3, p=4, 16-byte salt, 32-byte output.

use cipherkeep_core::{derive_master_key, KdfParams, SecretBytes};

const PASSWORD: &[u8] = b"correct horse battery staple";
const SALT: [u8; 16] = [
    0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f,
];
const EXPECTED_KEY_HEX: &str = "853b272a44db1421c02962669a55eb0994f3cab385ed1c4c79253eee19bab49e";

fn to_hex(bytes: &[u8]) -> String {
    let mut output = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        use core::fmt::Write;
        write!(&mut output, "{byte:02x}").expect("writing to a String cannot fail");
    }
    output
}

#[test]
fn pinned_argon2id_parameters_match_independent_vector() {
    let params = KdfParams::new(65_536, 3, 4, SALT);
    let password = SecretBytes::from_slice(PASSWORD);
    let key = derive_master_key(&password, &params).expect("derivation should succeed");
    assert_eq!(key.len(), 32);
    assert_eq!(to_hex(key.expose_secret()), EXPECTED_KEY_HEX);
}
