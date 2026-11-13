//! Session lifecycle tests.

use cipherkeep_core::{
    validate_master_password, ItemId, RecoveryShare, SecretBytes, VaultBlob, VaultKey, VaultSession,
};

const PASSWORD: &[u8] = b"correct horse battery staple";
const NEW_PASSWORD: &[u8] = b"a different long passphrase";

fn password(bytes: &[u8]) -> SecretBytes {
    SecretBytes::from_slice(bytes)
}

#[test]
fn creates_locks_and_unlocks() {
    let created = VaultSession::create(&password(PASSWORD)).expect("create");
    assert!(!created.session.is_locked());
    let blob = VaultBlob::decode(&created.session.blob().encode()).expect("blob decode");

    let mut session = VaultSession::unlock(&blob, &password(PASSWORD), 0).expect("unlock");
    assert!(!session.is_locked());
    session.lock();
    assert!(session.is_locked());
    session
        .unlock_again(&password(PASSWORD), 1)
        .expect("unlock again");
    assert!(!session.is_locked());
}

#[test]
fn wrong_password_is_generic_and_rate_limited() {
    let created = VaultSession::create(&password(PASSWORD)).expect("create");
    let blob = created.session.blob();
    let mut session = VaultSession::unlock(&blob, &password(PASSWORD), 0).expect("unlock");
    session.lock();

    for attempt in 0..5 {
        let error = session
            .unlock_again(&password(b"wrong password value"), 100 + attempt)
            .expect_err("wrong password");
        assert_eq!(error.numeric_code(), 3);
    }
    assert_eq!(session.failed_attempts(), 5);
    assert!(session.retry_after(100) > 0);

    let limited = session
        .unlock_again(&password(PASSWORD), 101)
        .expect_err("must be delayed");
    assert_eq!(limited.numeric_code(), 8);
    session
        .unlock_again(&password(PASSWORD), 100 + session.retry_after(100))
        .expect("allowed after delay");
    assert!(!session.is_locked());
}

#[test]
fn recovery_with_any_two_shares_sets_a_new_password() {
    let created = VaultSession::create(&password(PASSWORD)).expect("create");
    let blob = created.session.blob();
    let first = RecoveryShare::decode(created.shares[0].expose_secret()).expect("share 0");
    let third = RecoveryShare::decode(created.shares[2].expose_secret()).expect("share 2");

    let recovered =
        VaultSession::recover(&blob, &first, &third, &password(NEW_PASSWORD)).expect("recover");
    let new_blob = recovered.session.blob();

    assert!(VaultSession::unlock(&blob, &password(NEW_PASSWORD), 0).is_err());
    assert!(VaultSession::unlock(&blob, &password(PASSWORD), 0).is_ok());
    assert!(VaultSession::unlock(&new_blob, &password(PASSWORD), 0).is_err());
    assert!(VaultSession::unlock(&new_blob, &password(NEW_PASSWORD), 0).is_ok());
}

#[test]
fn rewrap_replaces_the_header_and_new_blob_opens() {
    let created = VaultSession::create(&password(PASSWORD)).expect("create");
    let old_blob = created.session.blob();
    let mut session = created.session;
    session.rewrap(&password(PASSWORD)).expect("rewrap");
    let new_blob = session.blob();

    assert_ne!(old_blob.encode(), new_blob.encode());
    assert!(VaultSession::unlock(&new_blob, &password(PASSWORD), 0).is_ok());
    // A previously copied blob remains a valid wrapping of the same keyring;
    // revoking it requires rekeying the vault, which is not part of rewrap.
    assert!(VaultSession::unlock(&old_blob, &password(PASSWORD), 0).is_ok());
}

#[test]
fn biometric_master_key_unlocks_the_blob() {
    let created = VaultSession::create(&password(PASSWORD)).expect("create");
    let master = created
        .session
        .master_key_for_biometric()
        .expect("master key");
    assert_eq!(master.len(), 32);
    let blob = created.session.blob();
    let unlocked = VaultSession::unlock_with_master_key(&blob, &master).expect("biometric unlock");
    assert!(!unlocked.is_locked());
}

#[test]
fn items_save_list_get_delete_and_lock() {
    let mut created = VaultSession::create(&password(PASSWORD)).expect("create");
    let item_id = ItemId::from_bytes([7u8; 16]);
    let ciphertext = created
        .session
        .encrypt_item(item_id, b"platform payload")
        .expect("encrypt");
    assert!(!ciphertext
        .windows(16)
        .any(|window| window == b"platform payload"));
    assert_eq!(
        created
            .session
            .decrypt_item(item_id, &ciphertext)
            .expect("decrypt"),
        b"platform payload"
    );
    created
        .session
        .save_item(item_id, b"platform payload")
        .expect("save");
    assert_eq!(created.session.item_count().expect("count"), 1);
    assert_eq!(created.session.list_items().expect("list"), vec![item_id]);
    assert_eq!(
        created.session.get_item(item_id).expect("get"),
        b"platform payload"
    );
    created.session.delete_item(item_id).expect("delete");
    assert_eq!(created.session.item_count().expect("count"), 0);

    created
        .session
        .save_item(item_id, b"payload")
        .expect("save");
    created.session.lock();
    assert_eq!(
        created
            .session
            .get_item(item_id)
            .expect_err("locked")
            .numeric_code(),
        5
    );
    assert_eq!(
        created
            .session
            .save_item(item_id, b"x")
            .expect_err("locked")
            .numeric_code(),
        5
    );
    assert_eq!(
        created
            .session
            .list_items()
            .expect_err("locked")
            .numeric_code(),
        5
    );
}

#[test]
fn weak_passwords_are_rejected() {
    assert_eq!(
        validate_master_password(&password(b"short"))
            .expect_err("short")
            .numeric_code(),
        11
    );
    assert_eq!(
        validate_master_password(&password(b"password1234"))
            .expect_err("common")
            .numeric_code(),
        11
    );
    assert_eq!(
        validate_master_password(&password(b"aaaaaaaaaaaa"))
            .expect_err("repeated")
            .numeric_code(),
        11
    );
}

#[test]
fn blob_debug_does_not_expose_material() {
    let created = VaultSession::create(&password(PASSWORD)).expect("create");
    let debug = format!("{:?}", created.session.blob());
    assert!(debug.contains("VaultBlob"));
    assert!(!debug.contains("correct horse"));
    assert!(!debug.contains("CKS1"));
}

#[test]
fn rotating_vault_key_rewraps_items_and_new_blob_opens() {
    let created = VaultSession::create(&password(PASSWORD)).expect("create");
    let mut session = created.session;
    let item_id = ItemId::from_bytes([7u8; 16]);
    let ciphertext = session
        .encrypt_item(item_id, b"item payload")
        .expect("encrypt");
    let rotated = session
        .rotate_vault_key_with(&[(item_id, ciphertext.clone())])
        .expect("rotate");
    let new_ciphertext = rotated.items[0].1.clone();
    let plaintext = session
        .decrypt_item(item_id, &new_ciphertext)
        .expect("decrypt after rotate");
    assert_eq!(plaintext, b"item payload");

    let blob = VaultBlob::decode(&rotated.blob).expect("blob");
    let unlocked = VaultSession::unlock(&blob, &password(PASSWORD), 0).expect("unlock");
    let decrypted = unlocked
        .decrypt_item(item_id, &new_ciphertext)
        .expect("decrypt reopened");
    assert_eq!(decrypted, b"item payload");
}

#[test]
fn member_join_adopts_existing_vault_key() {
    let key = VaultKey::generate().expect("key");
    let expected_id = key.id();
    let created =
        VaultSession::create_with_vault_key(&password(PASSWORD), key).expect("create shared");
    let blob = created.session.blob();
    let unlocked = VaultSession::unlock(&blob, &password(PASSWORD), 0).expect("unlock");
    assert_eq!(
        unlocked.vault_id().expect("id").as_bytes(),
        expected_id.as_bytes()
    );
}
