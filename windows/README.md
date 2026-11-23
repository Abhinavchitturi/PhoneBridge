# PhoneBridge for Windows (preview)

A tray app for Windows 10 (1903 or later) and Windows 11 that pairs with the PhoneBridge Android app (0.23 or later)
over your own network, using the same encrypted protocol as the Mac app. The phone keeps a list of paired computers,
so it can be paired with a Mac and a Windows PC at the same time and switch between them from **Computers**.
No phone-side changes are needed.

## Build

You need the [.NET 10 SDK](https://dotnet.microsoft.com/download). It builds on Windows, macOS or Linux.

```bash
cd windows
./build.sh                 # tests, then out/win-x64/PhoneBridge.exe (one self-contained file, no .NET install needed to run it)
./build.sh win-arm64       # for ARM PCs
```

On Windows you can use PowerShell instead: `.\build.ps1` (or `.\build.ps1 -Rid win-arm64`).

The same steps, run by hand:

```bash
dotnet test tests/PhoneBridge.Core.Tests
dotnet publish src/PhoneBridge -c Release -r win-x64 -o out/win-x64
```

For a quick debug build: `dotnet build PhoneBridge.sln`.

## Use

1. Run `PhoneBridge.exe`. A PhoneBridge icon appears in the tray (grey until the phone is connected).
2. On the phone: PhoneBridge → **Computers → Pair a new computer** (if it's the first computer, it's already waiting).
3. Windows Firewall may ask about PhoneBridge the first time; allow it on private networks.
4. Both screens show the same six-digit code. Check that they match, then press **Pair** on both.

After that it reconnects by itself. Click the tray icon for the menu: status, battery, **Send files to phone…**,
**Ring my phone**, clipboard and notification switches, **Start with Windows**, **Reconnect** and **Forget phone…**.

If the phone is paired with other computers and is using one of them, the menu says so and tells you where to switch
(phone → Computers → Connect). The phone is the one that decides which computer it talks to.

## What works in this version

| Feature | Notes |
|---|---|
| Finding the phone | mDNS (`_phonebridge._tcp`), the last address that worked, the default gateway (when the PC is on the phone's hotspot), and the phone's Tailscale addresses |
| Pairing | Six-digit code on both screens. Waits for the phone's "pair" before showing a code, like the Mac app. Handles "busy" and "unpaired" refusals with a short status line |
| Secrets | The PC's X25519 identity and the paired phone's key are stored in `%APPDATA%\PhoneBridge\secrets.bin`, encrypted with DPAPI for the signed-in Windows user |
| Phone notifications | Shown as Windows toasts with the app's icon. Reply box for messaging apps, the notification's own buttons, and dismissing one here clears it on the phone. Turn off from the menu |
| Clipboard | Both ways, text and images. Content marked by password managers as excluded from clipboard monitoring isn't sent. One-time codes from SMS are copied, kept out of clipboard history, and cleared after a minute |
| Battery | In the menu and the tray tooltip |
| Files | **Send files to phone…** (lands in the phone's Downloads). Files from the phone land in your Downloads folder; the toast opens the folder |
| Calls | Incoming calls and call waiting ring as a toast with **Answer** / **Decline** |
| Find my phone | **Ring my phone** |

Logs: `%LOCALAPPDATA%\PhoneBridge\phonebridge.log`.

## Not yet on Windows

These need Windows-specific work and are left for later:

- **The phone controlling the PC**: media keys, volume, lock, sleep, the media deck, mouse/keyboard from the phone (the phone gets a short "not on Windows yet" note if you try)
- **Phone screen and apps on the PC**: screen casting, ADB/scrcpy app streaming, "Type on phone"
- **Call audio on the PC**, the call panel with timer, and texting back or calling back from a missed call
- The phone window (messages, photos, call log, contacts, device tools), clipboard history, notification history and per-app muting, smart replies, read-aloud
- Shared notes, themes, Presence (Bluetooth proximity), the command-line / Shortcuts server
- **Auto-update**: the Mac and Android apps update from GitHub Releases; the Windows app doesn't yet, and `release.sh` doesn't build or upload it
- An installer, code signing (SmartScreen will warn about an unsigned `.exe`), and Start-menu toast activation after the app has quit (toast buttons work while PhoneBridge is running, which is always while it's in the tray)

## Code

| Path | What's inside |
|---|---|
| `src/PhoneBridge.Core` | Everything that isn't Windows UI, and runs on any OS: X25519 (a port of the phone's `X25519.kt`), session crypto (`Keys.cs`, matching `mac/Sources/Crypto.swift`), framing, mDNS discovery, the link (`PhoneLink.cs`, matching `mac/Sources/PhoneLink.swift`), file transfers |
| `src/PhoneBridge` | The WinForms tray app: menu, pairing dialog, toasts, clipboard, DPAPI, start with Windows |
| `tests/PhoneBridge.Core.Tests` | Crypto checked against fixed vectors from the Mac's CryptoKit code (`tools/vectors.swift`) and RFC 7748; the whole handshake against a fake phone ported from the phone's `Link.kt`; framing, mDNS (including a real phone's answer) and transfers |
| `tools/vectors.swift` | Regenerates the crypto vectors on a Mac: `swift tools/vectors.swift` |
