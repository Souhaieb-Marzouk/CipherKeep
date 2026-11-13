# Breach check (HIBP Pwned Passwords)

Optional, off by default. CipherKeep uses the Pwned Passwords range API with
k-anonymity.

## What leaves the device

- SHA-1 of a password, computed locally.
- Only the first **5 hex characters** — for example `5BAA6`.
- A fixed user agent and the `Add-Padding` header.

Never sent:

- password
- full SHA-1 hash
- username
- vault id
- item id
- origin

The provider sees the caller's IP address, as with any HTTPS request. There is
no background check and no automatic scan.

## Flow

1. User explicitly enables breach checking.
2. User starts a per-password or batch scan.
3. Batch scan requires a second confirmation.
4. Client requests `https://api.pwnedpasswords.com/range/<prefix>`.
5. Response is parsed locally; only matching suffix counts are used.
6. Range responses are cached in memory only.
7. Disabling opt-in clears the Android cache; Firefox's page-local cache is
   discarded when the page closes.

Redirects are rejected. TLS is required. Response bodies are bounded at 4 MiB.

## Limits

- HIBP reports known breach presence, not current safety.
- A zero result does not prove a password is strong.
- Rate limiting is local and best-effort.
- The provider can correlate prefixes with the caller's IP.
- Batch testing a whole vault reveals how many passwords you have to the
  provider.

## Tests

- Known `password` SHA-1 prefix/suffix fixture.
- Unknown suffix returns zero.
- Captured request URL is exactly 5 prefix characters.
- Malformed lines ignored.
- Provider error handling.
- Opt-in off by default.
