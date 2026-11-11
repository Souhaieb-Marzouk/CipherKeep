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

The project is built for two places where people need their credentials most:

- **Android** — a mobile vault for everyday use.
- **Firefox** — a browser companion for quick access while you work.

> **Project status:** early development. There is no public signed release yet, and the code has not been independently audited. Do not use current development builds for real credentials.

---

## Features

### Available in development builds

- **Local vault** — create a vault protected by a master password.
- **Recovery shares** — three shares are created at setup; any two can restore access if the master password is lost.
- **Lock and unlock** — lock the vault manually, or let the inactivity timer lock it for you.
- **Android biometric unlock** — use an enrolled fingerprint on supported devices, with the master password as fallback.
- **Password items** — store a title, username, and password for each login.
- **Sensitive clipboard** — copied passwords are marked sensitive where supported and are cleared automatically after a short timeout.
- **Encrypted local storage** — vault data is stored encrypted on the device.
- **Firefox popup** — create, unlock, lock, and manage items from the extension popup.
- **Update checks** — check GitHub Releases manually for a newer version; optional daily checks can be enabled. Nothing is installed silently.

### Planned

- Browser autofill for Firefox.
- Android autofill support.
- One-time password (TOTP) support.
- Password generator.
- Secure import and export.
- Richer item types, including secure notes.
- Optional encrypted multi-device sync.
- Signed Android and Firefox releases.
- Independent security review before a stable release.

Features and availability may change during development.

---

## How to use CipherKeep

CipherKeep is not yet available as a public download. The first-use flow will be:

### Android

1. Install the signed APK from the official release page.
2. Create a vault with a strong master password.
3. Save the three recovery shares separately and offline.
4. Add your logins from the vault screen.
5. Unlock with your master password or enrolled biometrics.

### Firefox

1. Install CipherKeep from the official Firefox add-on listing.
2. Create a vault or unlock an existing one from the popup.
3. Add and review your logins in the popup.
4. Lock the vault when you are finished.

### Good to know

- Your master password is never stored.
- Recovery requires any two of your three shares. Lose the device and fewer than two shares, and the vault cannot be recovered.
- The vault locks after a period of inactivity.
- CipherKeep never asks for your master password by email, chat, or support message.

---

## Privacy and security

- **Local-first:** no CipherKeep account or hosted vault.
- **No tracking:** no analytics, advertising, telemetry, or crash-reporting SDKs.
- **No subscription:** all features are intended to remain free.
- **Minimal network access:** the app contacts GitHub only when you request an update check, or when the optional daily check is enabled.
- **Encrypted at rest:** vault data is encrypted before it is written to local storage.
- **Open source:** the code is available for review under AGPL-3.0.

CipherKeep cannot protect you from a compromised operating system, a keylogger, or a malicious browser extension. As with any password manager, use a strong, unique master password and keep your recovery shares safe.

For security reports, please read [SECURITY.md](SECURITY.md). Do not disclose vulnerabilities in a public issue.

---

## Support the project

CipherKeep is free and has no paid tier. If you find it useful and would like to support its development, you can use either link below. Support is entirely optional and does not unlock features.

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
