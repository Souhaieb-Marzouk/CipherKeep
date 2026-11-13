# Accessibility

CipherKeep aims to be usable with a screen reader, keyboard, large text and
high contrast. This page states what is implemented and what has not been
verified.

## Android

Implemented:

- Compose semantic labels on primary actions and fields
- content descriptions for passkey origin and consent text
- scalable `sp` text
- Material 3 touch targets
- device-credential prompt for passkey ceremonies
- system font scaling and dark theme

Known limitations:

- No full TalkBack walkthrough has been recorded.
- The passkey consent screen is a plain programmatic layout, not a reviewed
  Compose screen.
- Some icon-only controls may lack unique labels.
- No switch-access or voice-access test has been run.
- No formal contrast audit of every custom color pair exists.

## Firefox

Implemented:

- keyboard-operable popup and options controls
- labelled form fields
- visible focus styles where the browser supplies them
- text zoom through Firefox settings
- no fixed-height text containers in the options page

Known limitations:

- No NVDA/JAWS/Orca walkthrough has been recorded.
- Password strength and health charts may be hard to read without vision.
- The QR code is not described beyond its text equivalent.
- Some status messages are visual only and may not be announced.
- No formal WCAG 2.2 audit has been completed.

## Reporting an accessibility problem

Open a GitHub issue with:

- platform and version
- assistive technology and version
- the screen or flow
- what happened and what was expected

Do not include real credentials or vault contents.
