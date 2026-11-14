# Android passkey provider

Status: service skeleton only.

## What exists

- `CipherKeepCredentialProvider` extends CredentialProviderService.
- Manifest registration with:
  - `BIND_CREDENTIAL_PROVIDER_SERVICE`
  - `androidx.credentials.provider.CredentialProviderService` intent filter.
- Create/get/clear callbacks return empty responses so Android can fall back to
  another provider.

The service is registered on-device and a settings switch enables/disables the
component, removing it from the system picker. It does **not** yet create or
assert passkeys.

## Required before enabling

1. Passkey record storage:
   - credential id
   - RP id/origin
   - username/user handle
   - P-256 key pair in hardware-backed Keystore
   - per-item wrapping and SQLCipher metadata
2. Create ceremony:
   - verify requesting package/origin
   - consent UI showing exact origin
   - biometric/device-credential gate
   - return attestation policy and credential id
3. Get ceremony:
   - origin/package binding
   - explicit consent
   - sign assertion with the Keystore key
4. Disable switch that removes the provider from the system picker.
5. Test relying party instrumentation and Chrome manual test.
6. Log-capture test proving no key material appears.

## Security requirements

- Never assert silently.
- Refuse wrong origin/package.
- Show the origin in consent UI.
- Private keys stay in Keystore and are never logged.
- Attestation policy must be documented before enabling.


## Observed test RP run (2026-11-14)

- RP: `https://souhaieb-marzouk.github.io/cipherkeep-passkey/`
- Asset links: verified by Google Digital Asset Links API for
  `dev.cipherkeep.app` and the debug SHA-256.
- Chrome opened the page; RP ID showed `souhaieb-marzouk.github.io`.
- Tapping **Register passkey** opened Android's system passkey UI for that RP.
- The picker offered only Google Password Manager. CipherKeep was absent,
  consistent with its provider returning empty create/get responses.
- No CipherKeep credential-provider error appeared in logcat.
- Flow cancelled; no passkey was created.


## Implementation added 2026-11-14

- Passkey metadata resource (`credential_provider.xml`).
- P-256 Keystore keys and `PasskeyStore` records.
- `PasskeyCreateActivity`:
  - origin/RP verification
  - device-credential/BiometricPrompt gate
  - clientDataJSON
  - authenticatorData
  - COSE P-256 key
  - CBOR `none` attestation
  - registration response
- `PasskeyGetActivity`:
  - origin/RP verification
  - device-credential/BiometricPrompt gate
  - authenticatorData/sign counter
  - ECDSA DER assertion
- `CipherKeepCredentialProvider` returns create/get entries.

## Remaining system blocker

Android's `credential_service` is still:

```text
com.google.android.gms/.auth.api.credentials.credman.service.PasswordAndPasskeyService
```

CipherKeep is registered but is not the selected credential service. Changing
`settings secure credential_service` requires `WRITE_SECURE_SETTINGS`, which adb
shell does not have on this OPPO. The user must select CipherKeep in Android's
passkey/credential-provider settings.

The available test devices cannot complete this:
- OPPO CPH2173 / ColorOS exposes no third-party passkey-provider selector.
- Armor 9E runs Android 10, below the Android 14 requirement for
  `CredentialProviderService`.

The implementation is therefore build-verified but not hardware-verified
end-to-end.


## Security audit 2026-11-14

Fixed:
- Get assertions now reject a credential absent from `allowCredentials`.
- `onClearCredentialStateRequest` deletes stored records and Keystore aliases.
- Create requests are type-checked before returning a passkey entry.
- P-256 x/y coordinates are left-padded to 32 bytes.

Open:
- The test page still performs client-only WebAuthn; no server-side
  attestation/assertion verifier is deployed, despite the agreed server-side
  verification level.
- The provider trusts `CallingAppInfo.getOrigin()` and does not independently
  fetch Digital Asset Links for the caller. Android supplies the origin, but a
  provider-side association check would be defense in depth.
- Keystore keys are not created with `setUserAuthenticationRequired(true)`;
  user verification is enforced by the consent activities, not cryptographically
  bound to the key.
- No Android 14 device with third-party passkey-provider selection was available
  for an end-to-end test.


## Passkey hardening 2026-11-14

- New passkey keys are generated with:
  - `setUserAuthenticationRequired(true)`
  - per-use validity (`-1`)
- Assertions use a `BiometricPrompt.CryptoObject` tied to the ECDSA
  `Signature`; the key cannot sign without a fresh device credential.
- Origins are checked against an explicit browser allowlist for `https://`
  origins, or against the caller signing-certificate hash for
  `android:apk-key-hash:` origins.
- Existing passkeys created before this change are not retroactively
  auth-bound; remove and re-enroll them.
- The provider still does not fetch Digital Asset Links itself; Android's
  `CallingAppInfo` origin plus the browser allowlist is the current boundary.
