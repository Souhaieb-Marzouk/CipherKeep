# Family vaults

Family sharing wraps a vault key to each member's X25519 public key.

## Key wrapping

For every member:

1. Generate an ephemeral X25519 key.
2. Compute `X25519(ephemeral_secret, member_public)`.
3. Derive a 32-byte key with HKDF-SHA-256 using the vault id as salt and
   `CipherKeep/fam1 || member_id` as info.
4. Encrypt the 32-byte vault key with XChaCha20-Poly1305.
5. Bind vault id, member id and role as AAD.
6. Store only the ephemeral public key, nonce and ciphertext.

The member unwraps locally with their static X25519 secret.

## Roles

- Owner — invite, revoke, change roles
- Editor — read/write
- Viewer — read-only

Changing a role re-wraps that member's vault key under the new role AAD; the old
envelope is not reusable.

Role checks exist in core; UI must show them, but cryptography is the real
control.

## Revocation

Removing a member:

1. Removes their member and wrapped-key record.
2. Generates a fresh vault key.
3. Re-wraps every remaining member.
4. Increments the rotation epoch.
5. Records an audit event.

A removed member cannot unwrap the new key. Ciphertext they already had remains
readable to them; this is stated in the UI and docs.

## Audit

Events: invited, role changed, revoked. Events contain ids, roles and epoch only
— no key material, fingerprint secret or plaintext.

## Fingerprints

A member fingerprint is the first 8 bytes of SHA-256(public_key), grouped for
out-of-band comparison. Verify fingerprints out of band before inviting.

## Platform rotation API

`VaultSession::rotate_vault_key_with(items)`:

- takes every platform item `(id, ciphertext)`
- unwraps each item key under the old vault key
- rewraps under a fresh vault key
- reseals the keyring under the master key
- returns the new vault blob plus every rekeyed item ciphertext

FFI `rotateFamilyKey(itemsJson)` exposes the same operation. A rotation test
reopens the returned blob with the master password and decrypts the rekeyed item.
Android `SessionController.rotateFamilyKey` persists the new vault blob and
every rekeyed item ciphertext. Android settings now expose owner identity,
invite envelope creation, member roles, revoke-with-rotation confirmation and the
residual-access warning. Firefox has a family settings view: owner identity, invite envelope, member list,
and rotate/revoke action. It persists the new vault blob and rekeyed item
ciphertexts in extension storage. Android and Firefox can change roles by
re-wrapping the current vault key. Android and Firefox have member join screens: they unwrap the owner envelope
with the member secret and create a local vault with the same vault ID/key, then
sync pulls items. Joining replaces the local vault and warns the user.

## Rotation journal

Before rotation, Android writes a local journal containing the old vault blob
and every item ciphertext (already encrypted). Firefox stores the same snapshot
in `browser.storage.local`. Both platforms restore the snapshot on the next
startup if rotation did not complete, then delete the journal only after the
new blob and all rekeyed items are durably written.

## Current status

Core wrapping, rotation, roles and audit are implemented with three-member
simulation tests. FFI and WASM expose portable identity generation, wrap and unwrap operations
for platform clients. Kotlin bindings and all JNI ABIs are rebuilt. Android/Firefox member-management UI and
QR/text invite flow remain to be wired.
