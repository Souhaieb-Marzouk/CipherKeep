# Firefox AMO unlisted release

## Identity

```text
addon id:       cipherkeep@cipherkeep.dev
channel:        unlisted
min Firefox:    142.0
updates:        AMO unlisted channel (no gecko.update_url)
```

The AMO signature is the trust anchor. A self-hosted XPI that is not signed by
AMO will not install in release Firefox.

## Create AMO API credentials

1. Sign in to https://addons.mozilla.org/.
2. Open the developer hub API keys page.
3. Generate a key and secret for this project.
4. Store them only as GitHub environment secrets:

```text
AMO_API_KEY
AMO_API_SECRET
```

Use the protected `release` environment with required reviewers. Do not put the
credentials in the repository, an artifact, or a workflow log.

## Build and sign manually

There is no GitHub release workflow. Build locally:

```bash
cd firefox
npm run build
node -e 'const fs=require("fs");const p="dist/manifest.json";const m=JSON.parse(fs.readFileSync(p));m.version=process.argv[1];fs.writeFileSync(p,JSON.stringify(m,null,2)+"\n")' <version>
npx web-ext sign --source-dir dist --artifacts-dir ../release-artifacts/<version> --channel unlisted
```

Set the AMO API key and secret in your local environment or pass
`--api-key`/`--api-secret`.

The signed XPI is downloaded into the artifacts directory. Rename it to
`cipherkeep-<version>.xpi`, compute SHA-256, and attach it to the release
manually. Do not commit signed artifacts.

## updates.json

Format:

```json
{
  "addons": {
    "cipherkeep@cipherkeep.dev": {
      "updates": [
        {
          "version": "0.1.0",
          "update_link": "https://github.com/Souhaieb-Marzouk/CipherKeep/releases/download/v0.1.0/cipherkeep-0.1.0.xpi",
          "update_hash": "sha256:<actual-lowercase-hex>"
        }
      ]
    }
  }
}
```

Rules:

- `update_link` must point at the signed `.xpi` asset.
- `update_hash` must be `sha256:` plus the hash of that exact file.
- no placeholders
- exactly one update entry per release
- version must match the XPI manifest

## Publish

Attach to the GitHub release:

```text
cipherkeep-<version>.xpi
firefox-updates.json
SHA256SUMS
```

AMO-managed unlisted add-ons update through AMO, not through a
`gecko.update_url`. The generated `updates.json` / `firefox-updates.json` files
are retained for self-hosted or manually distributed builds and for artifact
verification; they are not used by the AMO-signed XPI.

Do not add `update_url` back to the manifest for an AMO build: `web-ext lint`
rejects it.

## Install the signed XPI

1. Download `cipherkeep-<version>.xpi` from the release.
2. Open Firefox.
3. Go to `about:addons`.
4. Gear menu → **Install Add-on From File…**
5. Select the XPI.

Firefox verifies the AMO signature. If it reports a corrupt or unsigned add-on,
do not bypass the warning; rebuild through AMO.

For temporary development only:

```text
about:debugging → This Firefox → Load Temporary Add-on
```

Temporary add-ons are not signed and do not test updates.

## Test an update

1. Install `0.1.0`.
2. Build and sign `0.1.1`.
3. Publish the new XPI through AMO.
4. Restart Firefox.
5. Check `about:addons` for the update.

A hash mismatch or a missing signature fails the update instead of installing a
corrupt artifact.

## Verification

```bash
sha256sum cipherkeep-0.1.0.xpi
node scripts/verify-updates.mjs \
  --updates updates.json \
  --xpi cipherkeep-0.1.0.xpi \
  --version 0.1.0
```

`verify-updates.mjs` fails on:

- missing addon entry
- more than one update
- version mismatch
- non-HTTPS or non-XPI link
- malformed `update_hash`
- hash mismatch
- placeholder text

## Rotation

If the AMO API secret leaks:

1. revoke the key in AMO.
2. generate a new key/secret.
3. update the `release` environment secrets.
4. audit recent signed versions.
5. publish a security notice if an unauthorized XPI was signed.

The add-on ID must stay `cipherkeep@cipherkeep.dev`; changing it creates a new
add-on and breaks updates.
