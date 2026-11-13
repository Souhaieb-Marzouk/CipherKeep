# Travel Mode

Travel Mode hides the vault for a border crossing or a shared device. It is not
plausible deniability.

## Behavior

- The vault is not deleted.
- The decrypted session key is dropped.
- Lists, search, autofill and recents have no vault data to serve.
- The hidden state is persisted across process death.
- Restore requires the master password.
- Scheduled and manual sync refuse while Travel Mode is active.
- The UI shows a banner explaining what is hidden and what is not.

## Honesty

Local storage metadata can still reveal that a vault exists. CipherKeep does not
claim forensic deniability, hidden volumes or coercion resistance.

## Platform notes

### Android
- Flag encrypted with an Android Keystore AES key.
- Vault state becomes `Travel`.
- Session is locked and the database is closed.
- Restore uses the normal master-password unlock path and clears the flag.

### Firefox
- `travelMode` is set in `browser.storage.local`.
- The background locks the session and clears search/autofill state.
- Options show a Travel Mode banner.
- Successful unlock removes the flag.

## Core visibility set

`TravelMode` stores hidden vault ids. Repository code must ask `is_visible`
before returning items, search hits, recents or autofill candidates. The set is
metadata only; restore still requires authentication.

## Testing

- Repository/UI must show no hidden vault items.
- Restart preserves the hidden state.
- Correct password restores; wrong password does not.
- No data is deleted.
- Visible sync continues; a hidden vault does not sync.

Manual autofill/search checks are required on device and in Firefox.
