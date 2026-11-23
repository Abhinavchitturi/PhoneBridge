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

1. Run `PhoneBridge.exe`. The PhoneBridge window opens on "Looking for nearby phones", and an icon appears in the
   notification area (grey until the phone is connected).
2. On the phone: PhoneBridge → **Computers → Pair a new computer** (if it's the first computer, it's already waiting).
3. Windows Firewall may ask about PhoneBridge the first time; allow it on private networks.
4. The window shows "Found <your phone>" and a six-digit code. Check it matches the phone, then press **Pair** on both.

After that it reconnects by itself. Click the tray icon to open the window; right-click it for a short menu.
Closing the window keeps PhoneBridge running in the notification area, like the Mac app in the menu bar.

### Design

The window uses the Mac app's design language: the same 500 × 720 dark window in the default Midnight theme, the
serif title, the animated orb while it searches, the bridge hero with your phone's name and battery, round-icon action
tiles, capsule buttons, and settings shown in place. The page is `src/PhoneBridge/ui/index.html`, a port of the
SwiftUI views (`MainView`, `DiscoveryView`, `ActionTile`, `PillButtonStyle`). It's drawn by WebView2, which comes with
Windows 11 and current Windows 10. Without WebView2, PhoneBridge falls back to a plain tray menu and pairing dialog.
Apple's fonts and SF Symbols can't be shipped, so Windows uses Segoe UI and Georgia, and simple line icons drawn to
match. To look at the design without Windows, open `ui/index.html?demo=connected` (or `searching`, `refused`,
`pairing`, `settings`) in a browser.

If the phone is paired with other computers and is using one of them, the menu says so and tells you where to switch
(phone → Computers → Connect). The phone is the one that decides which computer it talks to.

## What's in it

Everything the Mac app does, except where Windows has no equivalent (noted below).

| Feature | Notes |
|---|---|
| Finding the phone | mDNS (`_phonebridge._tcp`), the last address that worked, the default gateway (when the PC is on the phone's hotspot), and the phone's Tailscale addresses |
| Pairing | Six-digit code on both screens, shown in the window. Waits for the phone's "pair" before showing a code, like the Mac app. Handles "busy" and "unpaired" refusals with a short status line |
| Secrets | The PC's identity and the paired phone's key in `%APPDATA%\PhoneBridge\secrets.bin`, and notification history in `history.bin`, encrypted with DPAPI for the signed-in Windows user |
| **Phone window** | Messages (threads, send, new message, quick replies), Photos & Videos (albums, grid by day, viewer, slideshow, download, copy, delete, copy text from a photo with Windows OCR), Calls (dialer, contacts, recents), Phone Apps, Clipboard history & shared notepad, Device (battery with time estimate, flashlight, ringer, Do Not Disturb, Find my phone on a map, storage and biggest videos), Notification History, Settings; the live notifications panel and Ctrl+K search |
| Notifications | Windows toasts with reply box, the app's buttons, and dismiss-syncs-to-phone; or the **chat reply pop-up** in the corner for chat apps, with your quick replies as one-tap buttons. (The Mac's AI-suggested replies use Apple's on-device model; Windows has nothing equivalent built in, so they're left out.) Mute per app or keyword; read new messages aloud |
| Calls | A floating call card like the Mac's: Answer / Decline, the time talked, Mute / Speaker / Hold / End, call waiting, and Call back / Text back after a call. Calls you dial here show "Calling…" |
| **The phone controls the PC** | Lock, sleep, volume, mute, play/pause/next/prev, the phone's player screen (seek, shuffle, repeat) for whatever Windows' media controls see (Spotify, browsers, Media Player…), Find my PC, ±10 s / Esc / full screen for the video in front, send the browser tab to the phone, PC battery alerts, and shortcuts (files you put in `%APPDATA%\PhoneBridge\Shortcuts`) |
| Trackpad & keyboard from the phone | Mac shortcuts are translated: ⌘C → Ctrl+C, ⌘ held + Tab → Alt+Tab, ⌘Q → Alt+F4; three-finger swipes do back/forward, Task View and show desktop |
| Phone apps & screen | Apps open in their own windows through scrcpy over Wireless debugging (`winget install Genymobile.scrcpy`, then the one-time setup in Settings); without it, the phone's screen is shown in a window (H.264 decoded by WebCodecs) and you can type into it. Type on phone |
| Clipboard | Both ways, text and images. Content marked by password managers as excluded from clipboard monitoring isn't sent. One-time codes from SMS are copied, kept out of clipboard history, and cleared after a minute |
| Files | Send files (menu, drag onto the window, or right-click → **Send to → PhoneBridge** in Explorer); files from the phone land in Downloads; new PC screenshots go to the phone |
| Lock when the phone walks away | The phone's Bluetooth beacon; locks after 20 s idle when the phone is away, wakes the screen when it's back |
| Phone as microphone | Plays the phone's mic into the free [VB-CABLE](https://vb-audio.com/Cable/) virtual device; pick "CABLE Output" as the microphone in Zoom, Meet or Teams |
| Do Not Disturb | When Windows holds notifications back (Do Not Disturb, presenting, full-screen), the phone's Do Not Disturb turns on too |
| Themes | All six themes, with their drawings and animated banners; switching here switches the phone, and the other way round |
| **Updates** | Checks GitHub releases when it opens, when the phone connects and every 4 hours. When a newer release has `PhoneBridge-Windows.exe` it asks "Update now?", downloads it, checks it against the SHA-256 GitHub lists for that file, swaps it in and restarts |

Command line: `PhoneBridge.exe file1 file2` sends files, `PhoneBridge.exe --text "hello"` puts text on the phone's clipboard
(a running PhoneBridge picks the request up). Logs: `%LOCALAPPDATA%\PhoneBridge\phonebridge.log`.

## Releases

`release.sh` (on the Mac) builds `release/PhoneBridge-Windows.exe` next to the `.dmg` and `.apk` when the .NET 10 SDK is
installed, and checks that its version (`<Version>` in `src/PhoneBridge/PhoneBridge.csproj`) matches the Mac and Android
versions. Upload it with the others; the updater looks for exactly that file name:

```bash
gh release create v<version> release/PhoneBridge.dmg release/PhoneBridge.apk release/PhoneBridge-Windows.exe --title "PhoneBridge <version>" --notes "..."
```

## Not on Windows (yet)

- **Call audio on the PC**: the Mac app doesn't have it either (macOS refuses Bluetooth call audio); both apps have call control
- An installer and code signing: SmartScreen warns about an unsigned `.exe` the first time
- Clicking an old notification in Action Center after PhoneBridge has quit does nothing (buttons work while it runs, which is always while it's in the tray)
- Drag a photo out of the phone window into Explorer (use Download or Copy)

## Code

| Path | What's inside |
|---|---|
| `src/PhoneBridge.Core` | Everything that isn't Windows UI, and runs on any OS: X25519 (a port of the phone's `X25519.kt`), session crypto (`Keys.cs`, matching `mac/Sources/Crypto.swift`), framing, mDNS discovery, the link (`PhoneLink.cs`), file transfers, call rules (`CallLogic.cs`), history / clipboard / notes / battery (`Features.cs`), release parsing (`Releases.cs`) |
| `src/PhoneBridge` | The tray app. Windows hosting the pages: `MainWindow.cs`, `PhoneWindow.cs`, `CallCard.cs`, `ChatPopup.cs`, `CastWindow.cs`. The phone's remote control: `Control/`. Glue: `TrayApp*.cs`. Plus `Streaming.cs` (scrcpy), `Updater.cs`, `Services.cs` (speech, OCR, Do Not Disturb) |
| `src/PhoneBridge/ui` | The pages, drawn like the Mac app: `index.html` (main window), `phone.html` + `phone.js` (phone window), `call.html`, `chat.html`, `cast.html`, with `common.css` / `common.js` (design tokens, components, icons) and `themes.js` |
| `tests/PhoneBridge.Core.Tests` | Crypto against fixed vectors from the Mac's CryptoKit code (`tools/vectors.swift`) and RFC 7748; the whole handshake against a fake phone ported from `Link.kt`; the Mac's call-rule checks; framing, mDNS (including a real phone's answer), transfers, history, notes, battery, releases |
| `tools/inline-ui.py` | Opens any page in a browser without Windows: `python3 tools/inline-ui.py phone.html photos batman > preview/p.html` |
