# Security Policy

CipherKeep is a password manager. Security reports are treated as the
highest-priority work.

**Status:** pre-release. No build is approved for production credentials.

## Supported versions

| Version | Supported | Notes |
|---|---|---|
| `0.1.x` | Development only | No public signed release yet |
| older | No | Pre-release development builds |

The supported-version table is updated when the first signed beta is published.

## Reporting a vulnerability

**Do not open a public issue, discussion, or pull request for a security
problem.**

Use GitHub private vulnerability reporting:

<https://github.com/Souhaieb-Marzouk/CipherKeep/security/advisories/new>

If that form is unavailable, open a minimal public issue asking the maintainer
to enable a private channel **without describing the vulnerability**.

Include:

- affected artifact and version or commit
- platform and OS version
- impact and preconditions
- reproduction steps or proof-of-concept, using test data only
- whether the issue is already public
- a suggested fix, if available

### Response targets

| Stage | Target |
|---|---|
| Acknowledgement | 3 business days |
| Initial triage and severity | 7 business days |
| Fix or mitigation plan | 30 days for High/Critical |
| Coordinated disclosure | By agreement, default 90 days after triage |

There is no bug bounty. Good-faith research that avoids data destruction and
does not target other users will not be threatened with legal action. This is a
statement of intent, not a legal safe-harbor policy.

## Threat model

### Assets

- master password
- vault key and per-item keys
- decrypted item plaintext
- recovery shares
- family vault keys
- passkey private keys
- alias provider tokens
- sync and emergency relay tokens
- signing keys and release artifacts

### Adversaries

- network attacker
- malicious website or phishing page
- malicious Android app
- attacker with a stolen unlocked device
- attacker with device storage access
- compromised relay, alias provider or HIBP endpoint
- compromised CI or release account
- supply-chain attacker

### Trust boundaries

- The vault is encrypted before it reaches storage.
- The relay sees opaque batches, not plaintext.
- Alias providers see the alias request and provider token.
- HIBP sees only a SHA-1 prefix when the user opts in.
- GitHub sees release artifacts and workflow metadata.
- AMO sees the submitted XPI and signs it.
- Android supplies `CallingAppInfo` for passkey requests.

## Security properties and evidence

| Property | Evidence |
|---|---|
| Argon2id password derivation | `crates/cipherkeep-core/src/kdf.rs`, core tests |
| XChaCha20-Poly1305 vault/item encryption | core tests, AAD-bound headers |
| Key zeroization | `zeroize` usage, core tests |
| Recovery is 2-of-3 Shamir | core recovery tests |
| Passkeys cannot assert silently | `PasskeyGetActivity` device-credential gate, origin checks |
| Family role changes rewrap keys | `role_change_rewraps_and_unwraps` test |
| Rotation is crash-recoverable | Android `RotationJournal`, Firefox `rotationJournal` |
| HIBP uses k-anonymity | `HibpClient`, prefix-only requests |
| Phishing warnings fail closed on unknown origins | phishing analyzer tests |
| Release artifacts are signed and checksummed | `build-android.yml`, `build-firefox.yml`, `release.yml` |
| Release identity can be verified offline | `release/minisign.pub`, `docs/VERIFY.md` |

## Residual risks

These are known and must not be described as solved:

- **No independent audit.** The code has not been externally audited.
- **P4-009 passkeys are not hardware-verified.** The provider builds and is
  installed, but no Android 14 device with third-party provider selection was
  available. The test RP is client-only; no server-side WebAuthn verifier is
  deployed.
- **Passkey keys are now cryptographically bound to per-use user
  authentication**, with assertions completed through a
  `BiometricPrompt.CryptoObject`. Existing passkeys created before this
  change are not retroactively bound.
- **The provider still does not fetch Digital Asset Links itself.** It uses
  Android's `CallingAppInfo` plus a browser package allowlist and, for
  app-origin credentials, the caller signing-certificate hash.
- **Emergency cron shares are base64 recovery shares**, not encrypted to the
  contact. One share remains below the 2-of-3 threshold.
- **Some OEM clipboard services strip the `IS_SENSITIVE` extra.** CipherKeep
  still clears its own clip using a label/ownership fallback, but the
  “sensitive” marker is not guaranteed on every Android build. This was
  observed on the OPPO CPH2173 test device.
- **Travel Mode is not a forensic control.** Its local flag is not
  integrity-protected.
- **Firefox provider/cron tokens and WebAuthn metadata live in
  `browser.storage.local`**, not an OS keychain.
- **Phishing overrides are host-scoped plaintext metadata.**
- **HIBP scans reveal password count and hash prefixes** to the HIBP endpoint.
- **Rotation journals can roll back a completed rotation** if journal deletion
  fails after both stores commit.
- **No public signed release exists yet.** The signing and integrity pipelines
  are implemented but have not produced a public beta.
- **No reproducible Android unsigned-artifact diff in CI.** Firefox is diffed;
  Android is documented and manually compared.

## Privacy

See [docs/PRIVACY.md](docs/PRIVACY.md) for the network-call inventory and what
never leaves the device.

## Accessibility

See [docs/ACCESSIBILITY.md](docs/ACCESSIBILITY.md) for known accessibility
support and limitations.

## Threat-model review sign-off

```text
Version:   0.1.0 pre-release
Date:      2026-11-14
Reviewer:  CipherKeep maintainer
Scope:     core, Android, Firefox, workers, release pipeline
Result:    accepted for pre-release; residual risks above
Next:      independent review before stable release
```

## Non-security bugs

Use GitHub Issues with a minimal reproduction and no real credentials.
