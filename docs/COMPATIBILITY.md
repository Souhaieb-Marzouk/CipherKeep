# Autofill compatibility dashboard

Public dashboard:

```text
https://souhaieb-marzouk.github.io/CipherKeep/
```

The URL becomes active after the `compat-dashboard` workflow deploys the
dashboard to GitHub Pages.

## What is published

- fixture ID
- pattern: plain, shadow, SPA, hidden, iframe
- expected result: fill or refuse
- pass/fail
- whether fields were filled
- run version, recipe version, timestamp and browser

No real credentials, user paths, vault data, environment variables or tokens
are published.

## History

Each run writes:

```text
firefox/tests/compat/results.json
firefox/tests/compat/history/<timestamp>.json
```

The dashboard generates:

```text
index.html          latest run
history.html        all retained runs
run-<n>.html        per-run page
latest.json         latest structured result
history.json        retained structured results
```

`history.json` is capped by repository retention rather than by the dashboard.

## Data schema

`firefox/tests/compat/run.schema.json`:

```json
{
  "schema": "cipherkeep-compat-v1",
  "version": "0.1.0",
  "recipeVersion": "1",
  "startedAt": "2026-11-14T00:00:00.000Z",
  "browser": "chromium",
  "results": [
    { "id": "plain-login", "status": "pass", "filled": true, "submitted": false, "hasPassword": true }
  ]
}
```

## CI

`.github/workflows/compat-dashboard.yml` runs:

- weekly on a schedule
- manually
- when a release is published

It installs Chromium, runs the fixtures, builds the static dashboard, validates
the schema, checks for secret-like published values and deploys to Pages.

## Security

- no third-party scripts
- inline CSS only
- CSP meta:
  ```text
  default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'
  ```
- output is HTML-escaped
- no analytics
- static pages work offline after load

## Accessibility

- `lang="en"`
- table caption and row/column scopes
- text status in addition to color
- keyboard-operable links
- no JavaScript dependency

A full axe run is not part of the workflow yet; the checker enforces the basic
structural requirements.
