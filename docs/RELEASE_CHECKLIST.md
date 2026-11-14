# Release checklist

Use this before publishing any signed build.

## Documentation

- [ ] `node scripts/check-docs.mjs` passes.
- [ ] No placeholder tokens in release-facing docs; the checker enforces this.
- [ ] All relative Markdown links resolve.
- [ ] `docs/VERIFY.md` contains the current Minisign public key and key ID.
- [ ] `docs/VERIFY.md` contains the current APK certificate fingerprint.
- [ ] `docs/PRIVACY.md` matches the network-call inventory.
- [ ] `SECURITY.md` supported versions and residual risks match the release.
- [ ] Threat-model review sign-off date/version updated.

Current Minisign key ID:

```text
9D0D1B35C1077A18
```

Check placeholders:

```bash
node scripts/check-docs.mjs
```

## Build and integrity

- [ ] APK/AAB built and signed locally with the release keystore.
- [ ] XPI signed locally through AMO unlisted.
- [ ] `checksums.txt` covers every artifact.
- [ ] `checksums.txt.minisig` verifies with `release/minisign.pub`.
- [ ] SLSA provenance generated and verified.
- [ ] `update_hash` matches the signed XPI.
- [ ] `SHA256SUMS` published.
- [ ] Vendor hash matches `vendor.sha256`.
- [ ] Reproducible Firefox dist/XPI check passes.
- [ ] Android unsigned artifact comparison recorded.

## Security

- [ ] No secret appears in logs, artifacts or release notes.
- [ ] APK is non-debuggable.
- [ ] Release certificate matches `CIPHERKEEP_CERT_SHA256`.
- [ ] AMO signature present.
- [ ] Minisign private key remains offline.
- [ ] Environment reviewers approved the run.
- [ ] Residual risks in `SECURITY.md` are still accurate.

## Manual verification

- [ ] Non-technical tester completes `docs/VERIFY.md`.
- [ ] Signed XPI installs in Firefox.
- [ ] APK installs and launches.
- [ ] Newer version is offered through `updates.json`.
- [ ] Tampered artifact fails verification.
- [ ] Release assets download and hash correctly.
