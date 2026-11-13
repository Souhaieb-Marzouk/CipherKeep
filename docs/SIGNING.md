# Android release signing

## Threat model

The signing keystore is the app's long-term update identity. It is never stored
in the repository, never printed, and only available to the protected `release`
environment.

## Generate the keystore

Generate offline, on a trusted machine:

```bash
keytool -genkeypair \
  -keystore cipherkeep-release.jks \
  -alias cipherkeep \
  -keyalg EC \
  -groupname secp256r1 \
  -sigalg SHA256withECDSA \
  -validity 10000 \
  -storetype PKCS12 \
  -storepass 'USE-A-LONG-RANDOM-PASSWORD' \
  -keypass 'USE-A-LONG-RANDOM-PASSWORD' \
  -dname 'CN=CipherKeep, O=CipherKeep, C=TN'
```

Use a password manager and a long random password. Do not use a debug key.

Verify:

```bash
keytool -list -v -keystore cipherkeep-release.jks -alias cipherkeep
```

Record the SHA-256 fingerprint. This is the published release identity.

## Back up offline

Store at least two encrypted offline copies of the keystore and its passwords:

- separate encrypted disks or hardware tokens
- separate physical locations
- no copy in cloud sync, GitHub, CI artifacts, or the repository

Test a restore from backup before the first release.

## Encode for CI

```bash
base64 -w0 cipherkeep-release.jks > cipherkeep-release.jks.b64
```

Do not commit `cipherkeep-release.jks.b64`.

Add these GitHub Actions **environment secrets** to the `release` environment:

```text
CIPHERKEEP_KEYSTORE_BASE64        base64 keystore bytes
CIPHERKEEP_KEYSTORE_PASSWORD      keystore password
CIPHERKEEP_KEY_ALIAS              cipherkeep
CIPHERKEEP_KEY_PASSWORD           key password
CIPHERKEEP_CERT_SHA256            published certificate SHA-256
```

`CIPHERKEEP_CERT_SHA256` is compared by the workflow. A changed certificate
fails the release instead of silently shipping a different identity.

## Protect the environment

In GitHub:

```text
Settings → Environments → release
```

Configure:

- required reviewers
- restrict deployment branches to `main`
- no self-review
- environment secrets only

The workflow is `workflow_dispatch` only. It has no PR trigger and no write
permissions.

## Run the release build

GitHub Actions → `build-android` → Run workflow:

```text
version: 0.1.0
```

The workflow:

1. decodes the keystore into `$RUNNER_TEMP`
2. builds `:app:assembleRelease` and `:app:bundleRelease`
3. checks R8 and resource shrinking ran
4. rejects `application-debuggable`
5. runs `apksigner verify --print-certs`
6. compares the certificate to `CIPHERKEEP_CERT_SHA256`
7. verifies the AAB with `jarsigner -verify -strict`
8. writes `SHA256SUMS` and `certificate-sha256.txt`
9. uploads APK/AAB and checksums
10. deletes the decoded keystore

## Versioning

`versionName` must be semver:

```text
MAJOR.MINOR.PATCH
```

`versionCode` is derived:

```text
MAJOR * 10000 + MINOR * 100 + PATCH
```

Examples:

```text
0.1.0 → 100
0.1.1 → 101
1.4.2 → 10402
```

Major must be ≤ 2100, minor and patch ≤ 99.

## Verify locally

```bash
apksigner verify --verbose --print-certs app-release.apk
jarsigner -verify -strict app-release.aab
sha256sum app-release.apk app-release.aab
```

Install and launch:

```bash
adb install -r app-release.apk
adb shell am start -n dev.cipherkeep.app/.MainActivity
```

## Rotation

Rotate only with a planned migration.

1. Generate a new keystore and alias.
2. Build a migration release signed with the old key that trusts the new key.
3. Publish the new certificate fingerprint.
4. Switch CI environment secrets to the new keystore.
5. Keep the old key available for one migration window.
6. Revoke the old key after the migration window.

Android does not allow an APK update signed by an unrelated key. A key change
requires a new package or a documented migration path.

## Tested rotation dry run

Use a throwaway keystore, never the production key:

```bash
keytool -genkeypair -keystore throwaway.jks -alias throwaway \
  -keyalg EC -groupname secp256r1 -validity 30 \
  -storepass throwaway-pass -keypass throwaway-pass \
  -dname 'CN=Throwaway'

base64 -w0 throwaway.jks > throwaway.jks.b64
```

Run the workflow with a temporary copy of the environment secrets pointing at
the throwaway key. Confirm:

- the build is signed
- `apksigner` reports the throwaway certificate
- the certificate check fails when `CIPHERKEEP_CERT_SHA256` is the production one
- no secret appears in logs

Delete the throwaway key and its base64 afterwards.

## Compromise response

If the private key or password leaks:

1. treat every release signed by it as untrusted
2. rotate to a new key and publish the new fingerprint
3. publish a security advisory
4. request removal or warning for affected releases
5. rotate CI tokens and environment secrets
6. audit workflow runs for unsigned or unexpected artifacts

There is no way to revoke an Android signing key remotely. Rotation and a new
package identity may be required.

## Checks

Before every release:

- [ ] `CIPHERKEEP_CERT_SHA256` matches the recorded fingerprint
- [ ] APK is non-debuggable
- [ ] R8 mapping file was generated
- [ ] resource shrinking ran
- [ ] `apksigner verify` passes
- [ ] `jarsigner -verify -strict` passes
- [ ] SHA-256 checksums published
- [ ] install/launch smoke test passed
- [ ] keystore removed from the runner
- [ ] no secret in logs or artifacts
