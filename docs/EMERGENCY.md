# Emergency access

CipherKeep emergency access is an optional dead-man's-switch. It uses Shamir
2-of-3 shares and a cron you deploy. CipherKeep and the relay cannot decrypt the
vault.

## Share distribution

`EmergencySharePlan` splits a 32-byte recovery secret:

| Holder | Share |
|---|---|
| Contact A | share 1 |
| Contact B | share 2 |
| Emergency cron | share 3 |

- Any one share reveals nothing.
- Contact A + cron share reconstructs.
- Contact B + cron share reconstructs.
- The cron share alone cannot reconstruct.

The cron stores only an **opaque encrypted envelope** for each contact. It does
not store a vault key, a master password or a plaintext share.

## Timeline

1. User arms a policy with one or two contacts.
2. Reminder is due at:
   `lastCheckIn + inactivity - reminderLead`.
3. Release is due at:
   `lastCheckIn + inactivity + grace`.
4. A check-in resets the timer and reminder.
5. Extend adds inactivity time.
6. Cancel prevents any release.
7. Reminder and release each fire at most once.

Core tests use a fake clock for reminder, release, check-in, extend, cancel and
share-threshold behavior.

## Cloudflare cron

`workers/emergency-cron` provides:

- create/status/check-in/cancel HTTP API
- hourly cron
- constant-time bearer auth
- opaque release envelopes
- notifier webhook with no vault secret

Deploy with:

```sh
wrangler secret put EMERGENCY_TOKEN
wrangler secret put NOTIFY_TOKEN
wrangler deploy
```

The Worker cannot decrypt even if its KV state and token are compromised. An
attacker who also has a contact share can reconstruct; that is the intended
threshold.

## Threat model and limitations

- The provider or cron can withhold a release; it cannot decrypt alone.
- A compromised contact plus the cron share can recover. Choose contacts
  deliberately.
- Coercion is not solved.
- Contact identity verification is delegated to the notification channel.
- Release notifications contain no vault key or plaintext.
- The vault UI must state these limits plainly.

## Platform UI

Firefox and Android can:

- configure a cron URL and token
- enter one or two contacts
- set inactivity days and grace minutes
- arm the policy
- check in
- cancel

Contact shares are shown once for offline distribution. The contact record is
stored as an encrypted vault item; Android keeps the cron token in Keystore-backed
storage, Firefox in `browser.storage.local` with the documented keychain warning.

## Testing

- Core fake-clock timeline: 4 tests.
- Share plan: cron-only fails, contact+cron succeeds.
- Cloudflare cron integration with fake KV, notifier and scheduled time.
- Manual two-contact dry run remains required.
