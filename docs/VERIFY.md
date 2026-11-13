# Verify a CipherKeep release

## Published trust material

Minisign public key:

```text
untrusted comment: minisign public key 9D0D1B35C1077A18
RWQYegfBNNsNnW/1yINAJwzQBB7pwFQLpTDuMch2EqEQ2O7NrGXdsfDA
```

Key ID:

```text
9D0D1B35C1077A18
```

The private key is offline and must never be committed. Only
`release/minisign.pub` belongs in the repository.

For CI, store the private key as an environment secret:

```bash
base64 -w0 cipherkeep-minisign.key
```

```text
MINISIGN_SECRET_KEY_BASE64
```

If the key has a password, add `MINISIGN_PASSWORD` as a separate secret.
The workflow decodes the key into `$RUNNER_TEMP`, signs, verifies with the
published public key, and deletes the temporary key.

## APK certificate fingerprint

The production signing certificate is not published yet because no signed public
beta exists. Before the first beta, record its SHA-256 here and in
`CIPHERKEEP_CERT_SHA256`.

Until then, verify any provided APK with:

```bash
apksigner verify --print-certs app-release.apk
```

A release whose certificate does not match the published fingerprint must be
rejected. Do not trust a fingerprint from an unsigned or debug APK.

## Files in a release

```text
checksums.txt
checksums.txt.minisig
provenance.intoto.jsonl
cipherkeep-<version>.xpi
app-release.apk / .aab
firefox-updates.json
```

`checksums.txt` covers every release artifact. The Minisign signature is
detached and signs `checksums.txt`, not each file directly.

## Linux / macOS

```bash
minisign -V -p release/minisign.pub \
  -m checksums.txt \
  -x checksums.txt.minisig

sha256sum --check --strict checksums.txt

slsa-verifier verify-artifact \
  --provenance provenance.intoto.jsonl \
  --source-uri github.com/Souhaieb-Marzouk/CipherKeep \
  --artifact-path cipherkeep-0.1.0.xpi
```

Or run:

```bash
./scripts/verify-release.sh /path/to/release-assets
```

## Windows

```powershell
minisign -V -p release\minisign.pub `
  -m checksums.txt `
  -x checksums.txt.minisig

.\scripts\verify-release.ps1 -Directory C:\path\to\release-assets
```

`verify-release.ps1`:

- rejects malformed checksum lines
- rejects a missing artifact
- recomputes every SHA-256
- fails on a checksum mismatch
- verifies the detached Minisign signature
- fails if `minisign` is unavailable

## SLSA provenance

The release workflow calls the SHA-pinned SLSA generator:

```text
slsa-framework/slsa-github-generator/.github/workflows/generator_generic_slsa3.yml
```

It binds:

- artifact hashes
- repository
- commit
- workflow
- builder identity

Verify with:

```bash
slsa-verifier verify-artifact \
  --provenance provenance.intoto.jsonl \
  --source-uri github.com/Souhaieb-Marzouk/CipherKeep \
  --artifact-path <artifact>
```

## Tamper test

Any of these must fail:

1. edit one byte of an artifact
2. edit one hash in `checksums.txt`
3. replace `checksums.txt.minisig`
4. use a different public key
5. remove an artifact listed in `checksums.txt`

The release workflow fails closed:

- missing Minisign secret: no release
- checksum generation failure: no release
- signature verification failure: no release
- provenance generation failure: no publish
- missing checksums at publish: no publish

## Minisign key handling

The private key is stored offline in:

```text
CipherKeep-local-backup/local-only/cipherkeep-minisign.key
```

Back it up encrypted, in at least two locations. It is not in Git.

If it is lost, existing signatures remain verifiable but new releases cannot be
signed with the same identity. Publish a new public key and key ID.

If it is compromised:

1. stop releases
2. remove the affected public key from the trusted release notes
3. generate a new key offline
4. publish the new public key and key ID
5. re-sign the current release checksums
6. publish a security advisory

## Release flow

1. Build signed APK/AAB through `build-android`.
2. Build signed XPI through `build-firefox`.
3. Run `release-integrity` with:
   - version
   - tag
   - the workflow run ID containing both `cipherkeep-*` artifacts
4. The workflow:
   - downloads every artifact
   - writes `checksums.txt`
   - signs it with Minisign
   - creates a draft release
   - generates SLSA provenance
   - uploads all assets
   - publishes the release

No artifact is published if any integrity step fails.
