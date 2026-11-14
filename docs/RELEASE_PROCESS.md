# Release process

## Prerequisites

Protected `release` environment secrets:

```text
CIPHERKEEP_KEYSTORE_BASE64
CIPHERKEEP_KEYSTORE_PASSWORD
CIPHERKEEP_KEY_ALIAS
CIPHERKEEP_KEY_PASSWORD
CIPHERKEEP_CERT_SHA256
AMO_API_KEY
AMO_API_SECRET
MINISIGN_SECRET_KEY_BASE64
MINISIGN_PASSWORD (only if the key has one)
```

Offline:

- production Android keystore and passwords
- Minisign private key
- backups of both

## Protection

The release workflow must be protected by:

- a tag ruleset for `v*.*.*` that restricts creation/update/deletion
- the `release` environment with required reviewers
- no self-review
- deployment branches limited to `main`
- environment secrets only

A local `workflow_dispatch` also requires the same environment approval.

## Run

1. Confirm `docs/RELEASE_CHECKLIST.md`.
2. Merge the reviewed release notes to `docs/release-notes/<version>.md`.
3. Dispatch `release` with:
   - `version`: `0.1.0`
   - `tag`: `v0.1.0`
   - `release_notes`: the reviewed notes
4. Approve the `release` environment.
5. The workflow:
   - builds signed APK/AAB and signed XPI
   - writes `checksums.txt`
   - signs it with Minisign
   - creates a draft release
   - generates SLSA provenance
   - uploads every artifact to the draft
6. Download the draft assets and run `docs/VERIFY.md`.
7. Install the APK and XPI on clean devices.
8. Confirm `updates.json` points at the signed XPI and hash.
9. **Publish manually** in GitHub Releases.

The agent must not create, push, tag, publish or release.

## Post-release

- Verify the public URL and assets.
- Download and verify checksums and Minisign signature.
- Confirm the XPI update is offered.
- Confirm the APK certificate matches `CIPHERKEEP_CERT_SHA256`.
- Record the release in the local delivery ledger.

## Rollback

### Broken but not compromised

1. Do not delete the release.
2. Mark it as a pre-release or add a warning to the release notes.
3. Publish a fixed patch version.
4. Update `firefox-updates.json` to the fixed version.
5. Keep the old artifact for reproducibility.

### Compromised signing key

1. Stop releases.
2. Revoke the AMO API key and rotate CI secrets.
3. Generate a new Android signing key and Minisign key.
4. Publish new public keys/fingerprints.
5. Publish a security advisory.
6. Yank affected assets and update `updates.json`.
7. Treat releases signed by the old key as untrusted.

### Compromised relay or alias provider

1. Rotate the affected token.
2. Disable the integration.
3. Publish an advisory.
4. Re-enable only after the provider confirms containment.

## Rollback tabletop

The process is tested by walking through:

| Scenario | Action | Result |
|---|---|---|
| Bad APK, good key | yank asset, publish patch | users can still verify old checksums |
| Bad XPI, good key | update updates.json, publish patch | no auto-update to bad build |
| Leaked Minisign key | new key, new public key, advisory | old signatures remain verifiable but untrusted |
| Leaked AMO key | revoke, new key, audit signed versions | no new AMO signatures |
| Leaked Android key | new package identity/migration | old key untrusted |

## First release status

The first official release is prepared by the protected workflow, but it is not
published automatically:

- the production Android keystore is supplied only as an environment secret
- AMO credentials are environment secrets
- the maintainer must approve the protected environment
- the maintainer must review the draft and publish it manually
