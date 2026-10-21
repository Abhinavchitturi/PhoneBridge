# PhoneBridge

Phone Link–style connection between a Mac and an Android phone.
Developed and tested on a MacBook Air (macOS 27) with a Samsung Galaxy phone (One UI 8, Android 16).

| Folder | What |
|---|---|
| `mac/` | Menu bar app (Swift, builds without Xcode) → `mac/build/PhoneBridge.app` |
| `android/` | Companion app (Kotlin) → `android/app/build/outputs/apk/debug/app-debug.apk` |

## Features (v0.1)
- 🔔 Phone notifications on the Mac — reply inline, dismissing on one side dismisses on the other
- 📞 Incoming calls with contact name — **Answer** / **Decline** from the Mac (voice stays on the phone)
- 📋 Clipboard: Mac → phone automatic; phone → Mac with the **Clipboard → Mac** quick-settings tile or **Share → Send to Mac**
- 📁 Files both ways (phone: Share → Send to Mac, lands in Mac `~/Downloads`; Mac: menu → Send file, lands in phone Downloads)
- 🎵 Now playing both ways: the phone's music on the Mac, and the Mac's music (art, title, play/pause/skip) on the phone
- 💬 Send SMS from the Mac, 🔊 ring your phone, 🔋 battery in the menu
- 🛡️ Intruder guard (off by default, Settings → Intruder guard): takes a few photos when the Mac wakes, unlocks or starts (kept only on the Mac and deleted at the end of each day; the phone just shows them and stores nothing), and — if your phone isn't near — sends them to your phone with **It's me** / **Lock Mac** buttons, over the paired link or (when the Mac is elsewhere) through an end-to-end encrypted ntfy relay. The Mac can also lock itself after sleep, restart or startup (every time, or only when your phone isn't near); the phone alert then has **It's me** (opens the lock) and **Code** buttons. **Lock Mac** puts a full-screen lock on the Mac that only opens with a rolling authenticator code from the phone (works offline) or a one-time recovery code. It deters and alerts; it cannot stop someone who holds the power button or boots into Recovery, so also use FileVault and Find My.
- 🔒 Pairing with a 6-digit code check; everything after is end-to-end encrypted (X25519 + AES-256-GCM)

Coming next: phone apps in Mac windows (needs Wi-Fi router + wireless debugging).

## Build
```bash
mac/build.sh
cd android && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug
```

## Install on the phone (one time)
1. Phone: **Settings → Security and privacy → Auto Blocker → Off** (only while installing).
2. Get `app-debug.apk` onto the phone (USB cable + `adb install`, or send the file over) and install it.
3. Turn Auto Blocker back on if you like — the installed app keeps working.

## Set up on the phone
Open **PhoneBridge** and do the three buttons:
1. Allow calls, contacts, SMS, notifications.
2. Allow notification access. If the switch is greyed out: **Settings → Apps → PhoneBridge → ⋮ (top right) → Allow restricted settings**, then try again.
3. Let it run in the background.

Also: **Settings → Battery → Background usage limits → Never sleeping apps → add PhoneBridge** (Samsung otherwise kills it).
Optional: pull down quick settings → ✏️ edit → add the **Clipboard → Mac** tile.

## Pair
1. Open `mac/build/PhoneBridge.app` (drag it to /Applications to keep it). Allow **notifications** and **local network** when macOS asks.
2. Mac and phone must be on the same network (phone hotspot works too).
3. Both show a 6-digit code — if they match, tap **Pair** on both.

## How it connects
The phone listens on TCP port 47800 and advertises itself via Bonjour (`_phonebridge._tcp`).
The Mac tries, in turn: Bonjour, the last address that worked, and the default gateway (= the phone when the Mac is on its hotspot).

## License

MIT — see [LICENSE](LICENSE).
