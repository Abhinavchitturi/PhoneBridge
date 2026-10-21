# PhoneBridge — Overview

PhoneBridge connects a MacBook (macOS 27) to an Android phone (developed on a Samsung Galaxy phone, Android 16), like Microsoft's Phone Link or Apple's Continuity but for Mac + Android. It has two apps that talk to each other over Wi-Fi or the phone's hotspot:

- **Mac app** — a menu-bar app written in Swift (`mac/`)
- **Android app** — a companion app written in Kotlin (`android/`)

## Features

### Calls and messages
- Incoming calls show a floating card on the Mac with the contact's name: Answer, Decline, Mute, Hold, Speaker, End, and a talk timer.
- A card stays in the corner for the whole call (time talked starts when the other side answers); a "Call ended" notification offers **Text back** and **Call back**.
- Start calls from the Mac. The voice stays on the phone.
- Read and send SMS from the Mac; view the call log.
- One-time verification codes from SMS are copied to the Mac clipboard.
- Missed-call auto-reply.

### Notifications
- Phone notifications appear as native Mac notifications.
- Reply inline; dismissing on one side dismisses on the other. Chat apps (WhatsApp, Instagram, …) pop up in the top-right corner with a text box ready to type in, because macOS hides the reply box of its own banners.
- Notification history, kept encrypted on the Mac.
- Choose which apps are forwarded; mute by app or keyword on the Mac; read messages aloud.

### Clipboard, files, screenshots
- Mac clipboard goes to the phone automatically (text and images). Phone clipboard comes over via a quick-settings tile or Share → Send to Mac.
- Clipboard history (kept in memory only).
- Right-click anything on the Mac → Services → **Send to Phone**.
- Send files both ways (phone files land in the Mac's Downloads; drag files onto the menu-bar icon to send to the phone).
- New screenshots on either device are sent to the other.
- "Scan to Mac" pastes a phone image at the Mac's cursor.

### Photos and phone data
- Browse the phone's whole photo/video library on the Mac, grouped by day and album; play videos, download, delete, drag out to Finder, copy the text out of a photo, or run a slideshow.
- Device tab: battery (with time to full and low/full alerts), storage and largest videos, flashlight, ringer, lock and unlock the phone, Find my phone on a map.
- ⌘K command palette to search everything on the phone.

### Phone apps and screen on the Mac
- Phone apps run in their own Mac windows via scrcpy over Wireless debugging (each on a hidden screen, so the phone stays free; debugging is on only during a session).
- Screen casting: the phone's screen streamed to the Mac as H.264 video.
- Phone microphone can feed the BlackHole virtual audio device, so Zoom/Meet can use it as a mic.

### Phone as a remote for the Mac
- Trackpad, keyboard and slide clicker.
- Run Mac commands (media controls, lock, etc.).
- Shared notepad (newest edit wins); type on the Mac into the phone; hand off browser tabs from the Mac to the phone.

### Media
- Now-playing on each device shows on the other, with album art, title, and play/pause/skip.
- Mac music pauses automatically when a call starts and resumes afterwards.

### The phone app
- A bottom bar with **Home** (status, setup, quick actions), **Guard** (today's intruder alerts), **Code** (the rolling unlock code) and **Notes** (the shared notepad).

### Automations (each can be switched off)
- Walk away with the phone and the Mac locks; come back and the screen wakes (Bluetooth LE beacon; unlock is still via Touch ID).
- Low phone battery alerts the Mac.
- A call mutes the Mac.
- A Focus on the Mac can turn on Do Not Disturb on the phone.

### Intruder guard (off by default)
- When the Mac wakes, unlocks or starts, it takes a few photos. They are stored only on the Mac and deleted at the end of each day; the phone shows them while you look and keeps nothing.
- If your phone isn't near, you get an alert with the photo and **It's me**, **Code** and **Lock Mac** buttons — over the paired link, or when the Mac is elsewhere through an encrypted relay (a public ntfy server by default, or your own).
- The Mac can lock itself after sleep, restart or startup (every time, or only when your phone isn't near). The lock screen opens with **It's me** from the phone, a rolling authenticator code from the phone (works offline; also works in Google/Microsoft Authenticator), or a one-time recovery code.
- Quitting PhoneBridge or weakening the guard needs the code. It deters and alerts; it can't stop someone who force-powers-off the Mac or boots into Recovery, so also use FileVault and Find My.

### Security
- Pairing shows a matching 6-digit code on both devices.
- After pairing, all traffic is end-to-end encrypted (X25519 key exchange + AES-256-GCM).
- Fresh keys for every connection (forward secrecy). Secrets are stored in the Mac Keychain and Android Keystore.
- No PhoneBridge servers and no account. The only internet traffic is the optional guard relay, and its payloads are encrypted and signed with a key only your two devices share.
- Not independently audited; see `SECURITY.md` and the Security and privacy section of the README.

## How it connects
The phone listens on TCP port 47800 and advertises itself via Bonjour (`_phonebridge._tcp`). The Mac tries Bonjour, then the last address that worked, then the default gateway (the phone, when the Mac is on its hotspot). Reaching the phone over Tailscale is also supported. The README has diagrams of the architecture, the pairing handshake and the guard flow.

## Project layout
| Folder | Contents |
|---|---|
| `mac/` | Menu-bar app (builds without Xcode via `mac/build.sh`) |
| `android/` | Companion app (`./gradlew assembleDebug`) |
| `README.md`, `SECURITY.md`, `LICENSE` | Setup guide and diagrams, how to report security problems, MIT license |

## Notes
- Built-in Mac Bluetooth call audio is refused on macOS 27, so call voice stays on the phone.
- Setup: build and install the APK, then allow "restricted settings" for the app (Settings → Apps → PhoneBridge → ⋮ → Allow restricted settings) so Android lets it have SMS, call log and notification access; grant the permissions, exempt it from battery limits, then pair with the 6-digit code. Full steps are in the README.
