import AppKit
import ApplicationServices

/// Turns the phone's trackpad/keyboard/clicker messages ("input") into real mouse and keyboard events.
/// Posting events needs Accessibility permission.
@MainActor
final class InputControl {
    var notify: (String, String) -> Void = { _, _ in }
    private var warned = false

    /// US-ANSI virtual key codes, by the names the phone's keyboards send.
    static let keyCodes: [String: CGKeyCode] = [
        "return": 36, "tab": 48, "space": 49, "backspace": 51, "esc": 53, "delete": 117,
        "left": 123, "right": 124, "down": 125, "up": 126,
        "home": 115, "end": 119, "pageup": 116, "pagedown": 121,
        "a": 0, "s": 1, "d": 2, "f": 3, "h": 4, "g": 5, "z": 6, "x": 7, "c": 8, "v": 9, "b": 11, "q": 12,
        "w": 13, "e": 14, "r": 15, "y": 16, "t": 17, "o": 31, "u": 32, "i": 34, "p": 35, "l": 37, "j": 38,
        "k": 40, "n": 45, "m": 46,
        "1": 18, "2": 19, "3": 20, "4": 21, "5": 23, "6": 22, "7": 26, "8": 28, "9": 25, "0": 29,
        "=": 24, "-": 27, "]": 30, "[": 33, "'": 39, ";": 41, "\\": 42, ",": 43, "/": 44, ".": 47, "`": 50,
        "f1": 122, "f2": 120, "f3": 99, "f4": 118, "f5": 96, "f6": 97, "f7": 98, "f8": 100,
        "f9": 101, "f10": 109, "f11": 103, "f12": 111,
    ]

    private static let modifierFlags: [String: CGEventFlags] = [
        "cmd": .maskCommand, "shift": .maskShift, "opt": .maskAlternate, "ctrl": .maskControl, "fn": .maskSecondaryFn,
    ]

    nonisolated(unsafe) private static var prompted = false

    /// True if PhoneBridge may post input events. Shows macOS's permission prompt at most once per launch,
    /// however many features ask (trackpad, media keys, paste…).
    nonisolated static func ensureAccessibility(prompt: Bool) -> Bool {
        if AXIsProcessTrusted() { return true }
        guard prompt, !prompted else { return false }
        prompted = true
        return AXIsProcessTrustedWithOptions([kAXTrustedCheckOptionPrompt.takeUnretainedValue(): true] as CFDictionary)
    }

    func handle(_ m: [String: Any]) {
        guard Self.ensureAccessibility(prompt: !warned) else {
            if !warned {
                warned = true
                notify("Allow PhoneBridge to control the Mac",
                       "System Settings → Privacy & Security → Accessibility → turn on PhoneBridge.")
            }
            return
        }
        switch m["kind"] as? String {
        case "move":
            move(dx: m["dx"] as? Double ?? 0, dy: m["dy"] as? Double ?? 0)
        case "click":
            click(right: m["button"] as? String == "right")
        case "scroll":
            // The phone sends how far the fingers moved; that is "natural" scrolling, so flip it if the Mac has that off.
            let flip = TrackpadPrefs.naturalScrolling ? 1.0 : -1.0
            let dy = Int32(((m["dy"] as? Double ?? 0) * flip).rounded())
            let dx = Int32(((m["dx"] as? Double ?? 0) * flip).rounded())
            CGEvent(scrollWheelEvent2Source: nil, units: .pixel, wheelCount: 2, wheel1: dy, wheel2: dx, wheel3: 0)?
                .post(tap: .cghidEventTap)
        case "type":
            if let text = m["text"] as? String { type(text) }
        case "key":
            let mods = (m["mods"] as? [String] ?? []).compactMap { Self.modifierFlags[$0] }
            if let name = (m["key"] as? String)?.lowercased(), let code = Self.keyCodes[name] {
                press(code, flags: CGEventFlags(mods))
            }
        case "mod":
            // A modifier held down on the phone's keyboard (⌘ held while tapping Tab, like a real keyboard).
            if let name = m["key"] as? String { setModifier(name, down: m["down"] as? Bool ?? false) }
        case "gesture":
            gesture(m["dir"] as? String ?? "")
        case "action":
            action(m["id"] as? String ?? "")
        case "zoom":
            // ⌘+ / ⌘− : zooms browsers, Finder, Mail, Preview and most other apps.
            press(m["dir"] as? String == "out" ? 27 : 24, flags: .maskCommand)
        default:
            break
        }
    }

    private var cursor: CGPoint { CGEvent(source: nil)?.location ?? .zero }

    private func move(dx: Double, dy: Double) {
        var p = cursor
        let speed = TrackpadPrefs.speedFactor
        p.x += dx * speed
        p.y += dy * speed
        // Keep the pointer on some screen (CG coordinates: origin top-left of the main display).
        let bounds = NSScreen.screens.reduce(CGRect.null) { r, s in
            let f = s.frame
            let mainH = NSScreen.screens.first?.frame.height ?? f.height
            return r.union(CGRect(x: f.minX, y: mainH - f.maxY, width: f.width, height: f.height))
        }
        p.x = min(max(p.x, bounds.minX), bounds.maxX - 1)
        p.y = min(max(p.y, bounds.minY), bounds.maxY - 1)
        CGEvent(mouseEventSource: nil, mouseType: .mouseMoved, mouseCursorPosition: p, mouseButton: .left)?
            .post(tap: .cghidEventTap)
    }

    private func click(right: Bool) {
        let p = cursor
        let (down, up, button): (CGEventType, CGEventType, CGMouseButton) =
            right ? (.rightMouseDown, .rightMouseUp, .right) : (.leftMouseDown, .leftMouseUp, .left)
        CGEvent(mouseEventSource: nil, mouseType: down, mouseCursorPosition: p, mouseButton: button)?.post(tap: .cghidEventTap)
        CGEvent(mouseEventSource: nil, mouseType: up, mouseCursorPosition: p, mouseButton: button)?.post(tap: .cghidEventTap)
    }

    // MARK: Held modifiers

    private static let modifierKeys: [String: CGKeyCode] = ["cmd": 55, "shift": 56, "opt": 58, "ctrl": 59]
    /// Modifiers the phone is holding down right now; added to every key pressed meanwhile.
    nonisolated(unsafe) private static var held: [String: CGEventFlags] = [:]
    nonisolated(unsafe) private static var heldFlags: CGEventFlags = []
    private var releaseTimer: Timer?

    private func setModifier(_ name: String, down: Bool) {
        guard let code = Self.modifierKeys[name], let flag = Self.modifierFlags[name] else { return }
        if down { Self.held[name] = flag } else if Self.held.removeValue(forKey: name) == nil { return }
        Self.heldFlags = Self.held.values.reduce(CGEventFlags()) { $0.union($1) }
        let e = CGEvent(keyboardEventSource: nil, virtualKey: code, keyDown: down)
        e?.type = .flagsChanged
        e?.flags = Self.heldFlags
        e?.post(tap: .cghidEventTap)
        // Never leave a modifier stuck down if the phone goes quiet (screen off, Wi-Fi drop).
        releaseTimer?.invalidate()
        releaseTimer = Self.held.isEmpty ? nil : Timer.scheduledTimer(withTimeInterval: 30, repeats: false) { [weak self] _ in
            Task { @MainActor in self?.releaseModifiers() }
        }
    }

    /// Lets go of anything the phone is holding (called on disconnect and by the safety timer).
    func releaseModifiers() {
        for name in Array(Self.held.keys) { setModifier(name, down: false) }
    }

    private func type(_ text: String) {
        // Unicode key events carry up to ~20 UTF-16 units each.
        var units = Array(text.utf16)
        while !units.isEmpty {
            let chunk = Array(units.prefix(16))
            units.removeFirst(chunk.count)
            for down in [true, false] {
                let e = CGEvent(keyboardEventSource: nil, virtualKey: 0, keyDown: down)
                e?.keyboardSetUnicodeString(stringLength: chunk.count, unicodeString: chunk)
                e?.post(tap: .cghidEventTap)
            }
        }
    }

    private func press(_ code: CGKeyCode, flags: CGEventFlags = []) { Self.press(code, flags: flags) }

    /// Presses one key (with modifiers) in the Mac's front app. Needs Accessibility, like the rest of this class.
    static func press(_ code: CGKeyCode, flags: CGEventFlags = []) {
        var flags = flags.union(heldFlags)
        // Arrow and navigation keys carry the fn flag on a real keyboard; Mission Control's ⌃-arrow shortcuts expect it.
        if (115...126).contains(code) && !flags.isEmpty { flags.insert(.maskSecondaryFn) }
        for down in [true, false] {
            let e = CGEvent(keyboardEventSource: nil, virtualKey: code, keyDown: down)
            if !flags.isEmpty { e?.flags = flags }
            e?.post(tap: .cghidEventTap)
        }
    }

    static func press(_ name: String, flags: CGEventFlags = []) {
        if let code = keyCodes[name] { press(code, flags: flags) }
    }

    /// A three-finger swipe on the phone does what the same swipe does on this Mac's trackpad
    /// (System Settings → Trackpad → More Gestures).
    private func gesture(_ dir: String) {
        switch dir {
        case "left", "right":
            if TrackpadPrefs.threeFingerSwipesPages {
                // "Swipe between pages": fingers left = forward, right = back.
                press(dir == "left" ? 30 : 33, flags: .maskCommand)   // ⌘] / ⌘[
            } else {
                // "Swipe between full-screen apps": fingers left = the space to the right.
                press(dir == "left" ? 124 : 123, flags: .maskControl)
            }
        case "up": missionControl(nil)          // Mission Control
        case "down": missionControl("2")        // App Exposé
        default: break
        }
    }

    /// A trackpad shortcut button (or a swipe set to one) on the phone: the Mac's own keys for it.
    private func action(_ id: String) {
        switch id {
        case "prev_tab": press(48, flags: [.maskControl, .maskShift])  // ⌃⇧Tab
        case "next_tab": press(48, flags: .maskControl)                // ⌃Tab
        case "mission_control": missionControl(nil)
        case "app_expose": missionControl("2")
        case "show_desktop": missionControl("1")
        case "prev_space": press(123, flags: .maskControl)             // ⌃←
        case "next_space": press(124, flags: .maskControl)             // ⌃→
        case "back": press(33, flags: .maskCommand)                    // ⌘[
        case "forward": press(30, flags: .maskCommand)                 // ⌘]
        case "spotlight": press(49, flags: .maskCommand)               // ⌘Space
        default: break
        }
    }

    private func missionControl(_ arg: String?) {
        let app = URL(fileURLWithPath: "/System/Applications/Mission Control.app")
        let config = NSWorkspace.OpenConfiguration()
        config.activates = false
        if let arg { config.arguments = [arg] }
        NSWorkspace.shared.openApplication(at: app, configuration: config)
    }
}

/// The Mac's own trackpad settings, read fresh (they're cheap) so changes in System Settings apply at once.
enum TrackpadPrefs {
    private static let domains = ["com.apple.AppleMultitouchTrackpad", "com.apple.driver.AppleBluetoothMultitouch.trackpad"]

    private static func int(_ key: String) -> Int? {
        for d in domains {
            if let v = CFPreferencesCopyAppValue(key as CFString, d as CFString) as? Int { return v }
        }
        return nil
    }

    /// System Settings → Trackpad → Scroll & Zoom → Natural scrolling (on unless turned off).
    static var naturalScrolling: Bool {
        CFPreferencesCopyAppValue("com.apple.swipescrolldirection" as CFString, kCFPreferencesAnyApplication) as? Bool ?? true
    }

    /// Tracking speed (0…3, default 1) as a multiplier on the phone's pointer movement.
    static var speedFactor: Double {
        let scale = CFPreferencesCopyAppValue("com.apple.trackpad.scaling" as CFString, kCFPreferencesAnyApplication) as? Double ?? 1
        return 0.6 + 0.4 * min(max(scale, 0), 3)
    }

    /// Three-finger horizontal swipe set to "Swipe between pages" (1) rather than full-screen apps (2).
    static var threeFingerSwipesPages: Bool { int("TrackpadThreeFingerHorizSwipeGesture") == 1 }
}
