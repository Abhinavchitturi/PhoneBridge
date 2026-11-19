# PhoneBridge — Overview

PhoneBridge connects a MacBook (macOS 27) to an Android phone (developed on a Samsung Galaxy phone, Android 16), like Microsoft's Phone Link or Apple's Continuity but for Mac + Android. It has two apps that talk to each other over Wi-Fi or the phone's hotspot:

- **Mac app** — a menu-bar app written in Swift (`mac/`)
- **Android app** — a companion app written in Kotlin (`android/`)

## Features

### Calls and messages
- Incoming calls show a floating card on the Mac with the contact's name: Answer, Decline, Mute, Speaker, End, and a talk timer.
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
- Device tab: battery (with time to full and low/full alerts), storage and largest videos, flashlight, ringer, Find my phone on a map.
- ⌘K command palette to search everything on the phone.

### Phone apps and screen on the Mac
- Phone apps run in their own Mac windows via scrcpy over Wireless debugging (each on a hidden screen, so the phone stays free; debugging is on only during a session).
- Screen casting: the phone's screen streamed to the Mac as H.264 video.
- Phone microphone can feed the BlackHole virtual audio device, so Zoom/Meet can use it as a mic.

### Phone as a remote for the Mac
- Trackpad, keyboard and slide clicker.
- Run Mac commands (media controls, lock, etc.).
- Shared notepad (newest edit wins); type on the Mac into the phone (through the PhoneBridge keyboard, which you select on the phone first); hand off browser tabs from the Mac to the phone.

### Media
- Now-playing on each device shows on the other, with album art, title, and play/pause/skip.
- Mac music pauses automatically when a call starts and resumes afterwards.

### Look and themes
- Minimal, dark, Apple-style, with a floating dock on both the phone and the Mac.
- Six themes (Midnight, Batman, Spider-Man, Iron Man, Thor, Matrix), each with its own artwork and animation (bats flapping across, a spider on its thread, a pulsing arc reactor, lightning, falling code) plus a short burst when you pick it. The theme is shared: pick it on the phone and the Mac (and its menu bar icon) switch too, and the other way round. The home screen has an animated banner per theme (Spider-Man swinging across between buildings, Iron Man flying with a reactor beam, Thor catching his hammer, the Bat-signal and the Batmobile, a Midnight bridge with data packets, Matrix code rain). Buttons puff out a small themed effect and tick on press, changing tabs plays a themed flourish, and picking a theme, opening the app and connecting each play that theme's character sequence (Thor drops in with lightning, looks up and leaps off; Spider-Man swings in, lands, looks up and swings away; Iron Man lands, looks up, fires missiles and flies off; Batman brings the signal, bats and the Batmobile; Matrix rains code; Midnight draws a constellation bridge). The long settings live in Settings. Phone: Themes tab. Mac: Settings → Appearance.

### The phone app
- A floating dock with **Home** (status, setup, quick actions), **Notes** (the shared notepad) and **Themes**.

### Automations (each can be switched off)
- Walk away with the phone and the Mac locks; come back and the screen wakes (Bluetooth LE beacon).
- Low phone battery alerts the Mac.
- A call mutes the Mac.
- A Focus on the Mac can turn on Do Not Disturb on the phone.

### Security
- Pairing shows a matching 6-digit code on both devices.
- After pairing, all traffic is end-to-end encrypted (X25519 key exchange + AES-256-GCM).
- Fresh keys for every connection (forward secrecy). Secrets are stored in the Mac Keychain and Android Keystore.
- No PhoneBridge servers and no account, and nothing goes over the internet.
- Not independently audited; see `SECURITY.md` and the Security and privacy section of the README.

## How it connects
The phone listens on TCP port 47800 and advertises itself via Bonjour (`_phonebridge._tcp`). The Mac tries Bonjour, then the last address that worked, then the default gateway (the phone, when the Mac is on its hotspot). Reaching the phone over Tailscale is also supported. The README has diagrams of the architecture and the pairing handshake.

## Project layout
| Folder | Contents |
|---|---|
| `mac/` | Menu-bar app (builds without Xcode via `mac/build.sh`) |
| `android/` | Companion app (`./gradlew assembleRelease`) |
| `PhoneBridge.apk` | Prebuilt phone app (release, stable signing key) |
| `README.md`, `SECURITY.md`, `LICENSE` | Setup guide and diagrams, how to report security problems, MIT license |

## Notes
- Built-in Mac Bluetooth call audio is refused on macOS 27, so call voice stays on the phone.
- Setup: build and install the APK, then allow "restricted settings" for the app (Settings → Apps → PhoneBridge → ⋮ → Allow restricted settings) so Android lets it have SMS, call log and notification access; grant the permissions, exempt it from battery limits, then pair with the 6-digit code. Full steps are in the README.
