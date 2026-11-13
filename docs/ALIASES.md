# Email aliases

CipherKeep can store and manage per-site email aliases through a provider you
control. It does not run an alias service, proxy mail, or require a subscription.

## Supported providers

### Cloudflare Email Worker

`workers/email-alias` provides:

- catch-all Email Routing handler
- KV-backed alias records
- create/list/disable/delete HTTP API
- constant-time bearer token check
- rejection of unknown and disabled aliases
- forwarding only to the configured `FORWARD_TO`

Deploy it yourself and give CipherKeep:

- HTTPS Worker URL
- 32+ character bearer token

### SimpleLogin

Self-hosted SimpleLogin uses:

- base URL of your instance
- API key

The API key is sent only to your SimpleLogin instance. CipherKeep does not
bundle a SimpleLogin account and does not proxy API traffic.

## Vault metadata

Alias metadata is stored as an `ItemType::Alias` CRDT record with fields:

| Field | Meaning |
|---|---|
| title | user-facing label |
| `FIELD_ALIAS_ADDRESS` | full alias address |
| `FIELD_ALIAS_PROVIDER` | `cloudflare` or `simplelogin` |
| `FIELD_ALIAS_PROVIDER_ID` | provider-side id, when returned |
| `FIELD_ALIAS_ENABLED` | current enabled state |

Provider credentials are **not** stored in the vault. They live in platform
secure storage:

- Android: Keystore-encrypted preference
- Firefox: `browser.storage.local`, documented as not an OS keychain

## No provider configured

The alias screen remains usable and explains:

> Alias creation needs a provider you control. CipherKeep does not create or
> proxy aliases.

Copy and open-provider actions are disabled until a provider is configured.

## Threat model

- The provider sees sender/recipient metadata and message content.
- A compromised provider can read forwarded mail.
- The vault is unaffected; alias metadata remains encrypted at rest.
- A stolen provider token can create/disable aliases but cannot decrypt the
  vault.
- Prefer a revocable, least-privilege provider token.
- Rotate the token after exposure.

## Tests

- Core fake provider: create/disable/delete/list, validation and missing-alias
  behavior.
- Cloudflare fixture is an integration target; deploy with a test KV namespace
  and test domain.
- SimpleLogin adapter is tested against a mock instance.
