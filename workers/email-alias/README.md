# CipherKeep email alias provider

This Worker is an optional, user-deployed alias control plane. CipherKeep does
not operate it and never receives mail sent to your aliases.

## What it does

- Catch-all Email Routing worker receives mail for `*@DOMAIN`.
- Looks up the recipient in KV.
- Rejects unknown aliases and disabled aliases.
- Forwards enabled aliases to `FORWARD_TO`.

The HTTP API creates and manages aliases:

```text
GET    /aliases
POST   /aliases                     {"label":"Example"}
POST   /aliases/{address}/disable
POST   /aliases/{address}/delete
```

Every request needs:

```text
Authorization: Bearer <ALIAS_TOKEN>
```

`ALIAS_TOKEN` must be at least 32 characters and is stored as a Worker secret.
The Worker compares it in constant time.

## Deploy

1. Create a KV namespace and put its id in `wrangler.toml`.
2. Set `DOMAIN` and `FORWARD_TO`.
3. Set the token:
   ```sh
   wrangler secret put ALIAS_TOKEN
   ```
4. Deploy:
   ```sh
   wrangler deploy
   ```
5. Enable Email Routing for `DOMAIN` and point the catch-all route at this
   Worker.

## Security properties

- The Worker can read forwarded mail. That is the provider trust boundary.
- The token authorizes the whole alias namespace for this deployment.
- The Worker stores only address, label, provider id, enabled state and time.
- CipherKeep stores only alias metadata in the vault, never the provider token
  in plaintext on Android.
- Rotate the token if it is exposed.

## No provider configured

The app remains usable. It explains that alias creation needs a provider and
offers copy/open-provider actions only when a provider is configured.
