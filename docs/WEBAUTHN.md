# WebAuthn PRF hardware unlock

Optional hardware-backed unlock using the WebAuthn PRF extension. The master
password and recovery shares remain independent fallbacks.

## Core

`crates/cipherkeep-core/src/webauthn.rs`:

- requires a 32+ byte PRF output
- HKDF-SHA-256 with the vault header prefix as salt
- domain `CipherKeep/webauthn-prf-v1`
- seals a second keyring copy with XChaCha20-Poly1305
- opens with the same PRF and header prefix
- wrong PRF, wrong header or tampering fails generically

Tests: seal/open, wrong PRF, wrong header prefix, short PRF rejection.

## Session/FFI

- `VaultSession::webauthn_seal(prf_output)`
- `FfiVaultSession.webauthn_seal(prf_output)`

The master-password keyring is untouched. Removing a hardware credential simply
discards this additional wrapping path.

## Platform status

Firefox now has a capability-gated PRF enrollment form: it creates a credential
with the PRF extension, requests `prf.results.first`, and stores only the sealed
keyring copy, credential id and salt. Firefox unlock now requests an assertion with the stored credential id and PRF
salt, calls WASM `webauthn_open`, and keeps password/recovery fallbacks.
Android uses CredentialManager PRF enrollment and assertion, stores the credential
id/salt/sealed keyring in Keystore-backed prefs, and exposes enroll/unlock/remove
in settings. Unsupported devices show a clear message. Unsupported devices show a clear message and remain
password-usable.

## Security notes

- PRF output is high-entropy and domain-separated; never log it.
- User presence is required by the authenticator.
- A cloned authenticator is outside the threat model.
- Do not make the authenticator the sole recovery path without an explicit
  warned opt-in.
- Recovery shares remain valid.
