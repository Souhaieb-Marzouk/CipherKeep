<div align="center">

<h1>CipherKeep</h1>

<p><strong>Your passwords, kept private on your devices.</strong></p>

<p>A local-first password manager for Android and Firefox.</p>

<p>
<a href="https://ko-fi.com/cyberpulseacademy">
<img src="https://img.shields.io/badge/Ko--fi-Support%20this%20project-FF5E5B?style=for-the-badge&logo=ko-fi&logoColor=white" alt="Support on Ko-fi">
</a>
<a href="https://www.paypal.com/donate/?hosted_button_id=KNUSPQTVR9WCU">
<img src="https://img.shields.io/badge/PayPal-Donate-00457C?style=for-the-badge&logo=paypal&logoColor=white" alt="Donate with PayPal">
</a>
</p>

</div>

---

## About CipherKeep

CipherKeep is a free, local-first password manager. Your vault stays on your own devices, protected by one master password. There is no CipherKeep account, no subscription, no advertising, and no analytics.

- **Android 8.0+** — mobile vault, autofill, biometric unlock and optional passkeys.
- **Firefox 142+** — browser companion for quick access while you work.

> **Project status:** 1.0.0 official release. The code has not been independently audited. Keep tested backups and do not make it the only copy of irreplaceable credentials.

---

## Features

### Vault and account

- **Local vault** — create a vault protected by one master password.
- **Recovery shares** — three shares are created at setup; any two restore access if the master password is lost.
- **No account** — there is no CipherKeep login, subscription or hosted vault.
- **Manual lock and auto-lock** — lock the vault yourself, or let the inactivity timer do it.
- **Encrypted local storage** — vault data is encrypted before it is written to disk.

### Items and everyday use

- **Item types** — logins, TOTP entries, secure notes, payment cards, identities and SSH keys.
- **Search, folders, tags and favorites** — organise and find items quickly.
- **Password generator** — random passwords and passphrases.
- **TOTP codes** — store and generate time-based one-time passwords.
- **Import and export** — encrypted backup export and restore, plus CSV/Bitwarden import.
- **Sensitive clipboard** — copied secrets are cleared automatically after a short timeout and marked sensitive where the platform supports it.

### Android

- **Biometric unlock** — fingerprint/device credential with master-password fallback.
- **Autofill** — fill saved logins after unlocking. Autofill is opt-in per item and warned on suspicious origins.
- **Phishing warnings** — local analysis of the origin before filling.
- **Passkey provider** — experimental. Requires Android 14+ and may not be offered by every OEM. The passkey provider does not replace the master password or recovery shares.
- **WebAuthn PRF unlock** — experimental hardware-key unlock with password fallback.

### Firefox

- **Popup vault** — create, unlock, lock and manage items.
- **Options page** — settings, import/export, sync, aliases, emergency access, Travel Mode and diagnostics.
- **AMO-signed XPI** — install the signed release from GitHub, or use the unlisted AMO channel.

### Optional network features

These are off unless you configure or trigger them:

- **Encrypted sync** — optional opaque relay sync with manual conflict resolution.
- **Email aliases** — optional SimpleLogin or Addy.io alias creation.
- **Emergency access** — optional cron-delivered recovery shares.
- **HIBP checks** — opt-in k-anonymity prefix checks for breached passwords.
- **Update checks** — manual or optional daily GitHub release checks.
- **Family sharing** — manual QR/text invitations; no family server.

### Local-only features

- **Password health** — weakness, reuse, age and breach flags in a local dashboard.
- **Travel Mode** — a local decoy vault for border or coercion situations. It is not plausible deniability.
- **Phishing analysis** — local lookalike and mixed-script detection.

### Planned

- Firefox browser autofill.
- Independent security audit.
- Accessibility review with screen-reader users.
- Store distribution.

---

## Install and use

Official signed releases are published through GitHub Releases.

```text
https://github.com/Souhaieb-Marzouk/CipherKeep/releases
```

### Android

1. Download the signed APK from the release.
2. Verify the release using [docs/VERIFY.md](docs/VERIFY.md).
3. Install it. Android may ask you to allow installation from your browser or file manager.
4. Create a vault with a strong master password.
5. Save the three recovery shares separately and offline.
6. Enable autofill in Android settings if you want it.

### Firefox

1. Download `cipherkeep-<version>.xpi` from the release.
2. Open `about:addons`.
3. Use the gear menu → **Install Add-on From File…**.
4. Select the XPI.

Unlisted AMO builds update through AMO. The XPI is not a temporary `about:debugging` add-on.

### Recovery and backups

- Recovery needs any two of your three shares.
- Losing the device and fewer than two shares means the vault cannot be recovered.
- Export an encrypted backup regularly and store it offline.
- The backup password is separate from the master password. Losing it makes the backup unreadable.

### Sync

Sync is optional. Use a test vault until you are comfortable with conflict handling. Conflicts are shown in the UI and resolved per field.

### Travel Mode

Travel Mode hides the real vault behind a local decoy. It does not make the real vault deniable under forensic examination.

### Passkeys and hardware unlock

These are experimental:

- Android passkeys require Android 14+ and an OEM that exposes third-party passkey providers.
- WebAuthn PRF hardware unlock depends on authenticator support.
- Password and recovery shares remain the fallback.

---

## Good to know

- Your master password is never stored.
- CipherKeep never asks for your master password by email, chat or support message.
- The vault locks after inactivity; unlock again before copying secrets or using autofill.
- Optional providers receive only what their feature needs. See [docs/PRIVACY.md](docs/PRIVACY.md).
- A compromised operating system, keylogger or malicious browser extension can still capture what you type or see.

---

## Privacy and security

- **Local-first:** no CipherKeep account or hosted vault.
- **No tracking:** no analytics, advertising, telemetry or crash-reporting SDKs.
- **No subscription:** all features are intended to remain free.
- **Minimal network access:** no network call is made unless you enable or trigger an optional feature.
- **Encrypted at rest:** vault data is encrypted before it is written to local storage.
- **Open source:** AGPL-3.0.

CipherKeep cannot protect you from a compromised device, a keylogger or a malicious extension. Use a strong, unique master password and keep recovery shares and backups safe.

For security reports, read [SECURITY.md](SECURITY.md). Do not disclose vulnerabilities in a public issue.

Verification and signing:

- [docs/VERIFY.md](docs/VERIFY.md)
- [docs/SIGNING.md](docs/SIGNING.md)
- [docs/REPRODUCIBLE.md](docs/REPRODUCIBLE.md)
- [docs/PRIVACY.md](docs/PRIVACY.md)
- [docs/ACCESSIBILITY.md](docs/ACCESSIBILITY.md)

---

## Support the project

CipherKeep is free and has no paid tier. Support is optional and does not unlock features.

<div align="center">

<a href="https://ko-fi.com/cyberpulseacademy">
<img src="https://img.shields.io/badge/Ko--fi-Support%20this%20project-FF5E5B?style=for-the-badge&logo=ko-fi&logoColor=white" alt="Support on Ko-fi">
</a>
<a href="https://www.paypal.com/donate/?hosted_button_id=KNUSPQTVR9WCU">
<img src="https://img.shields.io/badge/PayPal-Donate-00457C?style=for-the-badge&logo=paypal&logoColor=white" alt="Donate with PayPal">
</a>

</div>

---

## License

CipherKeep is licensed under the [GNU Affero General Public License v3.0](LICENSE).
