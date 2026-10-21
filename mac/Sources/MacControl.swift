import AppKit
import ApplicationServices
import AudioToolbox
import CoreAudio
import IOKit.ps

/// Commands the phone can run on the Mac ("mac_cmd"), plus the Mac state the phone shows ("mac_state").
@MainActor
final class MacControl: ObservableObject {
    var send: ([String: Any]) -> Void = { _ in }
    var notify: (String, String) -> Void = { _, _ in }
    /// Sends play/pause/next/prev straight to the Mac's now-playing app; false if that isn't available.
    var mediaCommand: (String) -> Bool = { _ in false }
    var onCall: (Bool) -> Void = { _ in }
    private var lastState: [String: AnyHashable] = [:]
    private var mutedForCall = false
    private var lowAlerted = false
    private var fullAlerted = false
    private var timer: Timer?

    func start() {
        timer = Timer.scheduledTimer(withTimeInterval: 20, repeats: true) { [weak self] _ in
            Task { @MainActor in
                self?.sendState(force: false)
                self?.batteryAlerts()
            }
        }
    }

    func handle(_ m: [String: Any]) {
        NSLog("PhoneBridge: mac_cmd \(m["cmd"] as? String ?? "?")")
        switch m["cmd"] as? String {
        case "lock": lock()
        case "sleep": Self.shell("/usr/bin/pmset", ["sleepnow"])
        case "play_pause": if !mediaCommand("play_pause") { mediaKey(16) }
        case "next": if !mediaCommand("next") { mediaKey(17) }
        case "prev": if !mediaCommand("prev") { mediaKey(18) }
        case "vol_up": changeVolume(by: 0.0625)
        case "vol_down": changeVolume(by: -0.0625)
        case "mute": toggleMute()
        case "find": findMyMac()
        case "find_stop": stopFinding()
        case "open_url":
            if let s = m["url"] as? String, let url = URL(string: s), url.scheme?.hasPrefix("http") == true {
                NSWorkspace.shared.open(url)
            }
        case "run_shortcut":
            if let name = m["name"] as? String { runShortcut(name) }
        case "call_mute_on":
            onCall(true)
            if !muted { setMuted(true); mutedForCall = true }
        case "call_mute_off":
            if mutedForCall { setMuted(false); mutedForCall = false }
            onCall(false)
        case "tab_request":
            sendTabToPhone()
        case "shortcuts_request":
            Task.detached {
                let names = Self.shell("/usr/bin/shortcuts", ["list"]).split(separator: "\n").map(String.init)
                await MainActor.run { self.send(["t": "shortcuts", "names": names]) }
            }
        default: break
        }
        sendState(force: true)
    }

    // MARK: System

    func lock() {
        // login.framework's SACLockScreenImmediate is what the Lock Screen menu item uses.
        if let h = dlopen("/System/Library/PrivateFrameworks/login.framework/Versions/Current/login", RTLD_LAZY),
           let sym = dlsym(h, "SACLockScreenImmediate") {
            typealias Fn = @convention(c) () -> Int32
            _ = unsafeBitCast(sym, to: Fn.self)()
        } else {
            Self.shell("/usr/bin/pmset", ["displaysleepnow"])
        }
    }

    private func runShortcut(_ name: String) {
        Task.detached { _ = Self.shell("/usr/bin/shortcuts", ["run", name]) }
    }

    @Published private(set) var finding = false
    private var findTimer: Timer?
    /// How the Mac's sound was set before "Find my Mac" made it loud, so Stop can put it back.
    private var savedSound: (device: AudioDeviceID, volume: Float, muted: Bool)?

    /// Loud repeating sound for up to 30 s; stoppable from the phone. Volume goes to 100% and is restored on stop.
    private func findMyMac() {
        guard !finding else { return }
        if let dev = outputDevice() { savedSound = (dev, volume, muted) }
        setVolume(1)
        setMuted(false)
        finding = true
        var plays = 0
        NSSound(named: "Sosumi")?.play()
        findTimer = Timer.scheduledTimer(withTimeInterval: 1.2, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                NSSound(named: "Sosumi")?.play()
                plays += 1
                if plays >= 25 { self.stopFinding() }
            }
        }
        notify("Find my Mac", "Your phone is looking for this Mac.")
    }

    func stopFinding() {
        findTimer?.invalidate()
        findTimer = nil
        finding = false
        restoreSound()
        sendState(force: true)
    }

    /// Back to the volume and mute setting from before the search (on the same speakers, if they're still there).
    private func restoreSound() {
        guard let saved = savedSound else { return }
        savedSound = nil
        guard outputDevice() == saved.device else { return } // output changed meanwhile; leave the new device alone
        setVolume(saved.volume)
        setMuted(saved.muted)
    }

    // Media keys are system-defined key events; posting them needs Accessibility permission.
    private func mediaKey(_ key: Int32) {
        guard InputControl.ensureAccessibility(prompt: true) else {
            notify("Allow PhoneBridge to control media",
                   "System Settings → Privacy & Security → Accessibility → turn on PhoneBridge, then try again.")
            return
        }
        for down in [true, false] {
            let flags = NSEvent.ModifierFlags(rawValue: down ? 0xa00 : 0xb00)
            let data1 = Int((key << 16) | ((down ? 0xa : 0xb) << 8))
            NSEvent.otherEvent(with: .systemDefined, location: .zero, modifierFlags: flags, timestamp: 0,
                               windowNumber: 0, context: nil, subtype: 8, data1: data1, data2: -1)?
                .cgEvent?.post(tap: .cghidEventTap)
        }
    }

    // MARK: Volume (default output device)

    private func outputDevice() -> AudioDeviceID? {
        var id = AudioDeviceID(0)
        var size = UInt32(MemoryLayout<AudioDeviceID>.size)
        var addr = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyDefaultOutputDevice,
                                              mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        return AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &addr, 0, nil, &size, &id) == noErr ? id : nil
    }

    private var volumeAddress = AudioObjectPropertyAddress(
        mSelector: kAudioHardwareServiceDeviceProperty_VirtualMainVolume,
        mScope: kAudioDevicePropertyScopeOutput, mElement: kAudioObjectPropertyElementMain)

    private var muteAddress = AudioObjectPropertyAddress(
        mSelector: kAudioDevicePropertyMute, mScope: kAudioDevicePropertyScopeOutput, mElement: kAudioObjectPropertyElementMain)

    var volume: Float {
        guard let dev = outputDevice() else { return 0 }
        var v = Float32(0)
        var size = UInt32(MemoryLayout<Float32>.size)
        AudioObjectGetPropertyData(dev, &volumeAddress, 0, nil, &size, &v)
        return v
    }

    private func setVolume(_ v: Float) {
        guard let dev = outputDevice() else { return }
        var value = Float32(min(max(v, 0), 1))
        AudioObjectSetPropertyData(dev, &volumeAddress, 0, nil, UInt32(MemoryLayout<Float32>.size), &value)
    }

    private func changeVolume(by delta: Float) {
        setMuted(false)
        setVolume(volume + delta)
    }

    var muted: Bool {
        guard let dev = outputDevice() else { return false }
        var m = UInt32(0)
        var size = UInt32(MemoryLayout<UInt32>.size)
        AudioObjectGetPropertyData(dev, &muteAddress, 0, nil, &size, &m)
        return m != 0
    }

    private func setMuted(_ on: Bool) {
        guard let dev = outputDevice() else { return }
        var m = UInt32(on ? 1 : 0)
        AudioObjectSetPropertyData(dev, &muteAddress, 0, nil, UInt32(MemoryLayout<UInt32>.size), &m)
    }

    private func toggleMute() { setMuted(!muted) }

    // MARK: Browser tab → phone

    private static let browsers: [(bundle: String, app: String, chromium: Bool)] = [
        ("com.apple.Safari", "Safari", false),
        ("com.google.Chrome", "Google Chrome", true),
        ("company.thebrowser.Browser", "Arc", true),
        ("com.brave.Browser", "Brave Browser", true),
        ("com.microsoft.edgemac", "Microsoft Edge", true),
        ("com.vivaldi.Vivaldi", "Vivaldi", true),
    ]

    /// Sends the front browser's current tab to the phone. Each browser asks once for permission (Automation).
    func sendTabToPhone() {
        let running = NSWorkspace.shared.runningApplications
        let front = NSWorkspace.shared.frontmostApplication?.bundleIdentifier
        let candidates = Self.browsers.filter { b in running.contains { $0.bundleIdentifier == b.bundle } }
        guard let browser = candidates.first(where: { $0.bundle == front }) ?? candidates.first else {
            notify("No browser tab to send", "Open Safari, Chrome, Arc, Brave, Edge or Vivaldi first.")
            return
        }
        let target = browser.chromium ? "active tab of front window" : "front document"
        let title = browser.chromium ? "title" : "name"
        let source = "tell application \"\(browser.app)\" to return {URL of \(target), \(title) of \(target)}"
        var error: NSDictionary?
        let result = NSAppleScript(source: source)?.executeAndReturnError(&error)
        guard let url = result?.atIndex(1)?.stringValue, url.hasPrefix("http") else {
            notify("Couldn't read the browser tab",
                   "Allow PhoneBridge in System Settings → Privacy & Security → Automation → \(browser.app).")
            return
        }
        send(["t": "open_on_phone", "url": url, "title": result?.atIndex(2)?.stringValue ?? ""])
    }

    // MARK: Mac battery → alert on the phone

    private func batteryAlerts() {
        guard UserDefaults.standard.object(forKey: "macBatteryAlerts") as? Bool ?? true,
              let (level, charging) = Self.battery() else { return }
        if !charging && level <= 20 && !lowAlerted {
            lowAlerted = true
            send(["t": "alert", "title": "Mac battery low", "body": "\(level)% — plug in your MacBook"])
        }
        if charging || level > 25 { lowAlerted = false }
        if charging && level >= 100 && !fullAlerted {
            fullAlerted = true
            send(["t": "alert", "title": "Mac fully charged", "body": "You can unplug your MacBook"])
        }
        if !charging { fullAlerted = false }
    }

    // MARK: State for the phone

    func sendState(force: Bool) {
        var state: [String: AnyHashable] = ["volume": Int((volume * 100).rounded()), "muted": muted, "finding": finding]
        if let (level, charging) = Self.battery() {
            state["battery"] = level
            state["charging"] = charging
        }
        guard force || state != lastState else { return }
        lastState = state
        var msg: [String: Any] = state
        msg["t"] = "mac_state"
        send(msg)
    }

    nonisolated static func battery() -> (Int, Bool)? {
        guard let info = IOPSCopyPowerSourcesInfo()?.takeRetainedValue(),
              let list = IOPSCopyPowerSourcesList(info)?.takeRetainedValue() as? [CFTypeRef] else { return nil }
        for ps in list {
            guard let d = IOPSGetPowerSourceDescription(info, ps)?.takeUnretainedValue() as? [String: Any],
                  let cur = d[kIOPSCurrentCapacityKey] as? Int, let max = d[kIOPSMaxCapacityKey] as? Int, max > 0 else { continue }
            return (cur * 100 / max, d[kIOPSIsChargingKey] as? Bool ?? false)
        }
        return nil
    }

    @discardableResult
    nonisolated static func shell(_ path: String, _ args: [String]) -> String {
        AppStreamer.run(path, args, timeout: 30).output
    }
}
