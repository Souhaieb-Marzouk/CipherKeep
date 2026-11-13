# Privacy statement

CipherKeep is local-first. There is no CipherKeep account, no advertising, no
analytics, no telemetry and no crash reporting.

## What never leaves the device

- master password
- vault key, item keys and keyring plaintext
- decrypted item titles, usernames, passwords, notes and URLs
- recovery shares in plaintext
- family vault key
- passkey private keys and PRF output
- WebAuthn credential private keys

Sync sends encrypted batches. The relay cannot decrypt them.

## Network calls

| Feature | Default | Destination | What is sent | What is received |
|---|---|---|---|---|
| Update check | Manual; optional daily | GitHub API/releases | repository name, current version | release metadata |
| Sync | Off until configured | `cipherkeep-relay.marzouk-souhaieb.workers.dev` | opaque encrypted batch, device ID, auth token | remote batch metadata |
| Emergency access | Off until configured | `workers/emergency-cron` | policy, contact labels, recovery shares, auth token | release status |
| Email aliases | Off until configured | SimpleLogin or Addy.io API | alias request, provider token | new alias address |
| HIBP check | User-triggered | `api.pwnedpasswords.com` | first 5 hex chars of SHA-1 password hash | suffix list with counts |
| AMO signature/update | Firefox-managed | AMO / GitHub releases | add-on ID/version | signed XPI/update metadata |
| Family invite | Manual | none | QR/text fingerprint only | none |

## Details

### Update checks

The Android/Firefox release check contacts GitHub. It sends the repository and
current version. Nothing about the vault is included.

### Sync

Sync uploads an encrypted batch plus a device identifier and an auth token.
The relay stores ciphertext and manifests. It does not receive the vault key or
any plaintext item.

### Emergency access

The emergency worker stores a policy, contact labels and recovery shares. Each
share is below the 2-of-3 threshold; the worker cannot reconstruct the vault
from one share. The feature is off unless configured.

### Email aliases

Alias requests go directly to the provider selected by the user. The provider
sees the requested alias and the provider token. The token is stored locally;
in Firefox it is in `browser.storage.local`, not an OS keychain.

### HIBP

Only the first five characters of the SHA-1 hash are sent. The endpoint returns
matching suffixes and counts. CipherKeep compares suffixes locally and does not
send the password or full hash.

### Phishing, health and Travel Mode

These run locally:

- phishing analysis uses the current origin and local overrides
- password health uses local item data
- Travel Mode uses a local decoy vault

### Family sharing

Family invites use QR/text fingerprints. No family vault key or plaintext is
sent to a CipherKeep server.

## Logs

Application logs must not contain passwords, keys, PRF output, tokens or
decrypted item data. Android autofill and passkey code must not log form values
or assertion material.

## Third-party retention

- GitHub retains release and workflow metadata under its policies.
- Cloudflare retains worker request metadata under its policies.
- Alias providers and HIBP retain requests under their policies.
- AMO retains submitted XPI metadata under Mozilla’s policies.

CipherKeep cannot control those third-party retention periods.

## Changes

This statement must be updated whenever a new network call is added.
