# Password health dashboard

All metrics are computed locally from decrypted items. No password, hash or score
is sent anywhere.

## Metrics

- **Weak** — entropy estimate below 50 bits.
- **Reused** — identical password appears on more than one item.
- **Old** — not updated within 90 days.
- **Breached** — only when the user explicitly ran an opt-in HIBP scan.
- **Missing TOTP** — login has no TOTP seed.

Each item gets an explanatory score (100 minus 15 per reason) and reason codes;
the score is not a substitute for reading the reasons.

## Data handling

- Results are session-only.
- Android recomputes from the in-memory item list and clears on lock.
- Firefox recomputes in the options page; no result is persisted.
- Breached counts are zero unless an opt-in HIBP scan was run in this session.
  Android keeps a session-only map cleared on lock; Firefox keeps it in the
  options page and discards it when the page closes.
- No network call is made by the dashboard.

## Platform UI

- Android: settings dashboard with metric counts, filter buttons and per-item
  reasons/scores.
- Firefox: options dashboard with metric counts and a session-only status line.

## Tests

Core unit tests cover entropy, reuse counting, age, breach opt-in behavior and
missing TOTP. Manual screen-reader checks remain.
