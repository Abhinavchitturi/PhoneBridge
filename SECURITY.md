# Security

PhoneBridge handles private data (messages, notifications, photos, clipboard), so security reports are taken seriously.

## Reporting a problem

Please **don't** open a public issue for a security problem. Use GitHub's private reporting instead:
**Security → Report a vulnerability** on this repository's page. Include what you found, how to reproduce it, and which version or commit you tested. I'll reply as soon as I can and credit you if you like.

## What to look at

- The link protocol and keys: `mac/Sources/Crypto.swift`, `mac/Sources/PhoneLink.swift`, `android/app/src/main/java/app/phonebridge/Crypto.kt`, `Link.kt`.
- The intruder guard and its relay: `mac/Sources/Guard*.swift`, `android/app/src/main/java/app/phonebridge/Guard*.kt`. Unit tests check that the Mac and the phone derive identical keys, codes and signatures.

## Known limits

- Not independently audited.
- A compromised phone or Mac defeats any of this.
- The intruder guard deters and alerts; it can't stop someone who force-powers-off the Mac or boots into Recovery. Use FileVault and Find My too.
- The optional guard relay (ntfy) can see that messages exist, their size and timing, and the IP addresses involved, but not their contents.
