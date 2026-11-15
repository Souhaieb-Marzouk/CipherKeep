//! Fixture tests for Proton `data.pgp` decryption.
//!
//! The fixtures are small Proton-shaped JSON exports encrypted with
//! `correct horse` by GnuPG 2.4.9 and OpenPGP.js 6, so both the direct
//! string-to-key key path and the encrypted-session-key path that Proton uses
//! are exercised, together with armored and binary framing, every supported
//! string-to-key mode and both compression algorithms.

use cipherkeep_core::error::ErrorCode;
use cipherkeep_core::proton_pgp::decrypt_proton_pgp;
use cipherkeep_core::{import, DuplicatePolicy};

const PASSPHRASE: &str = "correct horse";

fn fixture(name: &str) -> Vec<u8> {
    let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("tests")
        .join("fixtures")
        .join("openpgp")
        .join(name);
    std::fs::read(&path).unwrap_or_else(|error| panic!("read {}: {error}", path.display()))
}

fn expected() -> Vec<u8> {
    fixture("payload.json")
}

#[test]
fn decrypts_every_supported_fixture() {
    let cases = [
        "gpg-aes256.asc",
        "gpg-aes256-nocomp.asc",
        "gpg-aes128.asc",
        "gpg-s2k-simple.asc",
        "gpg-s2k-salted.asc",
        "gpg-sha1.asc",
        "gpg-binary.pgp",
        "openpgpjs-armored.asc",
        "openpgpjs-binary.pgp",
        "openpgpjs-zip.asc",
    ];
    for case in cases {
        let plaintext = decrypt_proton_pgp(&fixture(case), PASSPHRASE)
            .unwrap_or_else(|error| panic!("{case}: {error}"));
        assert_eq!(plaintext, expected(), "{case} decrypted to different bytes");
    }
}

#[test]
fn decrypted_payload_imports_as_proton_json() {
    let plaintext = decrypt_proton_pgp(&fixture("openpgpjs-armored.asc"), PASSPHRASE)
        .expect("decrypt Proton-shaped fixture");
    let result = import(&plaintext, DuplicatePolicy::Skip).expect("import decrypted payload");
    assert_eq!(result.report.imported, 1);
    assert_eq!(result.report.failed, 0);
    let item = result.items.first().expect("one item");
    assert_eq!(item.title, "GitHub");
    assert_eq!(item.username, "me@example.com");
    assert_eq!(item.password, "s3cret");
}

#[test]
fn wrong_passphrase_is_rejected_for_every_fixture() {
    let cases = [
        "gpg-aes256.asc",
        "gpg-s2k-simple.asc",
        "openpgpjs-armored.asc",
        "openpgpjs-binary.pgp",
    ];
    for case in cases {
        let error = decrypt_proton_pgp(&fixture(case), "wrong horse").expect_err(case);
        assert_eq!(error.code(), ErrorCode::CorruptData, "{case}");
    }
}

#[test]
fn tampered_ciphertext_fails_the_modification_detection_code() {
    let error = decrypt_proton_pgp(&fixture("tampered.pgp"), PASSPHRASE)
        .expect_err("tampered ciphertext must not decrypt");
    assert_eq!(error.code(), ErrorCode::CorruptData);
}

#[test]
fn truncated_and_malformed_input_is_rejected() {
    let armored = fixture("gpg-aes256.asc");
    for length in [0usize, 1, 16, 64, 128] {
        let truncated = &armored[..length.min(armored.len())];
        assert!(decrypt_proton_pgp(truncated, PASSPHRASE).is_err());
    }
    assert!(decrypt_proton_pgp(b"not an OpenPGP message", PASSPHRASE).is_err());
    assert!(decrypt_proton_pgp(&fixture("gpg-aes256.asc"), "").is_err());
    assert!(decrypt_proton_pgp(&[], PASSPHRASE).is_err());
}

#[test]
fn armor_without_a_checksum_trailer_still_decrypts() {
    let armored = String::from_utf8(fixture("openpgpjs-armored.asc")).expect("armor is ASCII");
    let without_checksum: String = armored
        .lines()
        .filter(|line| !line.starts_with('='))
        .collect::<Vec<_>>()
        .join("\n");
    assert_eq!(
        decrypt_proton_pgp(without_checksum.as_bytes(), PASSPHRASE).expect("decrypt"),
        expected()
    );
}
