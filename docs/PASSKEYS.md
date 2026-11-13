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
