import AppKit
import ApplicationServices

/// Turns the phone's trackpad/keyboard/clicker messages ("input") into real mouse and keyboard events.
/// Posting events needs Accessibility permission.
@MainActor
final class InputControl {
    var notify: (String, String) -> Void = { _, _ in }
    private var warned = false

    private static let keyCodes: [String: CGKeyCode] = [
        "return": 36, "tab": 48, "space": 49, "backspace": 51, "esc": 53,
        "left": 123, "right": 124, "down": 125, "up": 126, "b": 11,
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
            let dy = Int32((m["dy"] as? Double ?? 0).rounded())
            let dx = Int32((m["dx"] as? Double ?? 0).rounded())
            CGEvent(scrollWheelEvent2Source: nil, units: .pixel, wheelCount: 2, wheel1: dy, wheel2: dx, wheel3: 0)?
                .post(tap: .cghidEventTap)
        case "type":
            if let text = m["text"] as? String { type(text) }
        case "key":
            if let name = m["key"] as? String, let code = Self.keyCodes[name] { press(code) }
        default:
            break
        }
    }

    private var cursor: CGPoint { CGEvent(source: nil)?.location ?? .zero }

    private func move(dx: Double, dy: Double) {
        var p = cursor
        p.x += dx
        p.y += dy
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

    private func press(_ code: CGKeyCode) {
        CGEvent(keyboardEventSource: nil, virtualKey: code, keyDown: true)?.post(tap: .cghidEventTap)
        CGEvent(keyboardEventSource: nil, virtualKey: code, keyDown: false)?.post(tap: .cghidEventTap)
    }
}
