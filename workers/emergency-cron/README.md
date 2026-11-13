# CipherKeep emergency cron

User-deployed Cloudflare Worker for the dead-man's-switch. It stores only
timestamps, contact addresses and opaque encrypted share envelopes.

## API

```text
POST /policies                 create an armed policy
GET  /policies/{id}            status
POST /policies/{id}/checkin    reset the inactivity timer
POST /policies/{id}/cancel     cancel permanently
```

All requests require `Authorization: Bearer <EMERGENCY_TOKEN>` with a 32+
character token. The Worker compares it in constant time.

## Hourly cron

- Reminder at `lastCheckIn + inactivity - reminderLead`.
- Release at `lastCheckIn + inactivity + grace`.
- Reminder and release are each sent at most once.
- Release posts each contact's opaque share to `NOTIFY_WEBHOOK`.
- Notification payloads contain no vault key or plaintext.

The Worker cannot decrypt the vault even with all stored state. Release still
requires the contact's own share and the released envelope.

## Deploy

```sh
wrangler secret put EMERGENCY_TOKEN
wrangler secret put NOTIFY_TOKEN
wrangler deploy
```

Set `NOTIFY_WEBHOOK` to your notifier (email relay, ntfy, etc.). Leave it empty
for a dry run.

## Limitations

- Coercion is not solved.
- Contact identity verification happens in the notification channel.
- The Worker is a reminder/release courier, not a trust anchor.
