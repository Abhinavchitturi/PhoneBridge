# Security

PhoneBridge handles private data (messages, notifications, photos, clipboard), so security reports are taken seriously.

## Reporting a problem

Please **don't** open a public issue for a security problem. Use GitHub's private reporting instead:
**Security → Report a vulnerability** on this repository's page. Include what you found, how to reproduce it, and which version or commit you tested. I'll reply as soon as I can and credit you if you like.

## What to look at

- The link protocol and keys: `mac/Sources/Crypto.swift`, `mac/Sources/PhoneLink.swift`, `android/app/src/main/java/app/phonebridge/Crypto.kt`, `Link.kt`.
- The walk-away lock: `mac/Sources/Presence.swift` and the phone's beacon in `android/app/src/main/java/app/phonebridge/Automations.kt`.
- The phone's typing keyboard (`TypingKeyboard` in `Extras.kt`), which only inserts text the paired Mac sends.

## Known limits

- Not independently audited.
- A compromised phone or Mac defeats any of this.
- The walk-away lock is a convenience: it won't stop someone who reaches the Mac before it locks or boots into Recovery. Use FileVault and Find My too.
