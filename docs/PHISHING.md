# Phishing and homograph detection

CipherKeep analyzes autofill origins entirely on-device. No origin, URL or
lookup is sent to a third party.

## Analyzer

`crates/cipherkeep-core/src/phishing.rs`:

- parses the URL and host
- decodes IDNA/punycode
- detects Unicode script mixing
- maps Cyrillic/Greek confusables to a Latin skeleton
- compares labels with a bundled brand list
- detects one-edit typosquats

Reasons:

- `Punycode`
- `MixedScript`
- `ConfusableBrand`
- `LookalikeBrand`

Any reason sets `requires_confirmation`.

## Current integration

### Android autofill
Before building datasets, `CipherKeepAutofillService` calls
`phishingCheck("https://<webDomain>")`.

If confirmation is required:

- no dataset is offered
- a toast explains that the suspicious domain was blocked

If suspicious, the current fill is aborted and a review screen opens. The user
can allow that origin explicitly; the override is stored locally and the next
autofill attempt is permitted.

### Firefox autofill
`autofill-request` calls the WASM `phishing_check` before returning credentials.

If confirmation is required:

```text
Suspicious origin blocked: <decoded host>
```

No credential token is created. Options contain an explicit “Allow origin”
action for a reviewed host; overrides are stored locally.

## Limitations and next work

- Android confirmation is two-step: review screen, then retry autofill. Firefox
  confirmation is an explicit options action, not an inline overlay.
- Overrides persist locally; both platforms have a clear-overrides action.
- Warning accessibility has not been tested with a screen reader.
- The brand list is small and must be versioned with the app.
- False positives are possible for legitimate mixed-script or punycode domains.
- No network lookup is used.

## Tests

Core corpus: plain brand, Cyrillic lookalike, mixed script, punycode, legitimate
IDN and typosquat. Android and Firefox builds pass; manual autofill warning
flows remain.
