# Release process

## Prerequisites

Local, outside the repository:

- production Android keystore and passwords
- AMO API key and secret
- Minisign private key and password
- offline backups of all signing material

There is no protected release environment and no release workflow.

## Protection

- keep the keystore, AMO credentials and Minisign key outside the repository
- restrict who can create `v*.*.*` tags and publish releases
- require review before publishing a release
- never commit signed artifacts

## Run

1. Confirm `docs/RELEASE_CHECKLIST.md`.
2. Add the reviewed release notes at `docs/release-notes/<version>.md`.
3. Build and sign the APK/AAB locally.
4. Build and AMO-sign the XPI locally.
5. Write and Minisign `checksums.txt`.
6. Create a draft GitHub release manually.
7. Attach the APK, AAB, XPI and checksums.
8. Download the draft assets and run `docs/VERIFY.md`.
9. Install the APK and XPI on clean devices.
10. **Publish manually** in GitHub Releases.

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

## Current release status

Releases are built and signed locally. The maintainer reviews the artifacts and
publishes the GitHub release manually. No GitHub Actions workflow signs or
publishes releases.
