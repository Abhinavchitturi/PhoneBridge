# PhoneBridge demo video: recording plan

**Format:** 16:9, 1080p MP4, about 70 s, no voice-over, captions on screen, Midnight theme.
**Look:** the Mac screen with the phone mirrored beside it (scrcpy), recorded in one go so both stay in sync. It opens with a 4 s phone-camera clip of the real phone and laptop.

---

## 1. Before recording (once)

**Phone**
- [x] App v0.17, restricted settings, all permissions and unrestricted battery are already done (checked over adb), so the video skips setup.
- [x] Theme is Midnight.
- [ ] PhoneBridge → Notifications on Mac → turn off "All apps" and select only **Messages** and **WhatsApp**. Personal notifications then stay off the video.
- [ ] Clear all notifications, and set Media volume low.
- [ ] Spotify: have a song queued (an instrumental keeps the title neutral). YouTube: have a video open in the background.

**Mac**
- [ ] Build and run PhoneBridge from `main`, and pair.
- [ ] Hide desktop icons (or move them into a folder), and use a plain wallpaper.
- [ ] Quit Slack, Mail, Messages and anything else that pops up banners. Do **not** turn on a Focus mode, because that also hides PhoneBridge's banners.
- [ ] Open Terminal with a big font (⌘+ a few times) and a file ready to send, e.g. `~/Desktop/Demo.pdf`.
- [ ] Open TextEdit with an empty document. That's where the login code gets pasted (never a real site).
- [ ] Turn on "Show Mouse Clicks" in the ⌘⇧5 Options menu.

**Helper:** one person with a second phone to send texts and a code, and to make one call. Save them as a contact with a neutral name, e.g. "Alex".

---

## 2. Every recording session

```bash
./demo/prep.sh
```
This shows taps and opens the mirror window on the **left** of the MacBook screen. (Samsung ignores the clean-status-bar step, so clear the phone's notifications by hand.) Put PhoneBridge's Mac window on the right. Banners and the chat pop-up appear top-right, so they never cover the phone.

Hide the Dock with ⌥⌘D. Then press ⌘⇧5 → **Record Selected Portion** and drag a box from the top-left corner to **1440 × 810** (full width, top of the screen, which is exactly 16:9). The box keeps the menu bar and banners and leaves out the bottom strip. ⌘⇧5 remembers the box for the next take.

Afterwards, `./demo/prep.sh off` puts the phone back to normal.

Record **each scene as its own take**. Retakes are cheap, and iMovie joins them.

---

## 3. Shot list (~70 s)

| # | Time | Scene | What you do | Caption |
|---|------|-------|-------------|---------|
| 0 | 0–4 s | **Real clip** (phone camera, shot from above) | The phone on the desk next to the open MacBook. A text arrives, the phone lights up, and the banner appears on the Mac. | My Android phone, built into my Mac |
| 1 | 4–9 s | Connect | Quit and reopen PhoneBridge on the Mac. The discovery orb shows, then it connects. | Finds your phone on Wi-Fi. Encrypted, no cloud, no account. |
| 2 | 9–15 s | Notification | Alex sends a WhatsApp message. The banner appears on the Mac and clears by itself. | Every notification, on your Mac |
| 3 | 15–25 s | Text + reply | Alex texts "Lunch at 1?". Reply "Sounds good 👍" in the Mac chat pop-up, and the reply shows on the phone mirror. | Reply to texts without picking up your phone |
| 4 | 25–31 s | Login code | Alex texts "Your verification code is 482913". On the Mac, press ⌘V in TextEdit. | Login codes go straight to your clipboard |
| 5 | 31–42 s | Call | Alex calls. Answer on the Mac, let the call card's timer run for 3 s, then press End. | Answer calls from your Mac |
| 6 | 42–53 s | Player | Spotify plays on the phone. On the Mac: seek, shuffle, skip. Switch to YouTube: −10 s, speed. | Control your phone's music and videos |
| 7 | 53–62 s | Files + clipboard | In Terminal: `pb send ~/Desktop/Demo.pdf`, and it lands on the phone. Then copy a line on the Mac and paste it on the phone. | Send files and share your clipboard |
| 8 | 62–65 s | Closer | `pb ring`: the phone rings on the mirror. `pb ring off`. | Find your phone from the terminal |
| 9 | 65–70 s | End card | Black background with the PhoneBridge icon. | PhoneBridge · open source · github.com/Abhinavchitturi/PhoneBridge |

Optional extras (cut them if it runs long): typing on the Mac into the phone with the PhoneBridge keyboard, Find my phone, or a quick look at the Settings window.

---

## 4. Editing (iMovie)

1. Make a new movie. Drop in clips 0 → 9 and trim each one to the moment something happens. Dead air kills muted videos.
2. Add the captions with **Titles → "Lower Third"** or **"Standard"**, in white. Keep each caption on screen for the whole scene.
3. Music is optional. LinkedIn starts videos muted, so the video has to make sense without sound. If you add music, keep it quiet and use iMovie's built-in tracks (royalty-free).
4. Export with **File → Share → File**, 1080p, High quality.

## 5. Before posting

- [ ] Watch it once with the sound off. Does every scene make sense?
- [ ] Pause on each frame: no real names, numbers, codes or personal notifications visible.
- [ ] The post text: one line on what it is, why you built it, and the GitHub link. Add the video natively (not as a YouTube link), so it autoplays.
