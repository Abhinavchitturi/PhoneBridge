# PhoneBridge — Overview

PhoneBridge connects a MacBook (macOS 27) to an Android phone (developed on a Samsung Galaxy phone, Android 16), like Microsoft's Phone Link or Apple's Continuity but for Mac + Android. It has two apps that talk to each other over Wi-Fi or the phone's hotspot:

- **Mac app** — a menu-bar app written in Swift (`mac/`)
- **Android app** — a companion app written in Kotlin (`android/`)

## Features

### Calls and messages
- Incoming calls show a floating card on the Mac with the contact's name: Answer, Decline, Mute, Hold, Speaker, End, and a talk timer.
- Start calls from the Mac. The voice stays on the phone.
- Read and send SMS from the Mac; view the call log.
- One-time verification codes from SMS are copied to the Mac clipboard.
- Missed-call auto-reply.

### Notifications
- Phone notifications appear as native Mac notifications.
- Reply inline; dismissing on one side dismisses on the other.
- Choose which apps are forwarded; mute by app or keyword on the Mac; read messages aloud.

### Clipboard, files, screenshots
- Mac clipboard goes to the phone automatically (text and images). Phone clipboard comes over via a quick-settings tile or Share → Send to Mac.
- Clipboard history.
- Send files both ways (phone files land in the Mac's Downloads; drag files onto the menu-bar icon to send to the phone).
- New screenshots on either device are sent to the other.
- "Scan to Mac" pastes a phone image at the Mac's cursor.

### Photos and phone data
- Browse the phone's whole photo/video library on the Mac, grouped by day and album; download, delete, or drag out to Finder.
- Device tab: battery, storage, largest videos, flashlight, ringer, lock, Find my phone.
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

### Automations (each can be switched off)
- Walk away with the phone and the Mac locks; come back and the screen wakes (Bluetooth LE beacon; unlock is still via Touch ID).
- Low phone battery alerts the Mac.
- A call mutes the Mac.

### Security
- Pairing shows a matching 6-digit code on both devices.
- After pairing, all traffic is end-to-end encrypted (X25519 key exchange + AES-256-GCM).
- Secrets are stored in the Mac Keychain and Android Keystore.

## How it connects
The phone listens on TCP port 47800 and advertises itself via Bonjour (`_phonebridge._tcp`). The Mac tries Bonjour, then the last address that worked, then the default gateway (the phone, when the Mac is on its hotspot). Reaching the phone over Tailscale is also supported.

## Project layout
| Folder | Contents |
|---|---|
| `mac/` | Menu-bar app (builds without Xcode via `mac/build.sh`) |
| `android/` | Companion app (`./gradlew assembleDebug`) |
| `CallTest/` | Earlier Bluetooth calling experiment (no longer needed) |
| `PhoneBridge.apk` | Built Android app |

## Notes
- Built-in Mac Bluetooth call audio is refused on macOS 27, so call voice stays on the phone.
- Setup: install the APK, grant calls/contacts/SMS/notification permissions, allow notification access, exempt from battery limits, then pair with the 6-digit code.
