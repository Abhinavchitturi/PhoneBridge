import AppKit
import AVFoundation
import Intents
import SwiftUI
import Vision

// MARK: - Clipboard history (in memory only: clipboards often hold secrets)

@MainActor
final class ClipboardHistory: ObservableObject {
    struct Item: Identifiable {
        let id = UUID()
        let text: String
        let date: Date
        let fromPhone: Bool
    }

    @Published private(set) var items: [Item] = []
    @Published var search = ""

    var filtered: [Item] {
        let q = search.trimmingCharacters(in: .whitespaces)
        return q.isEmpty ? items : items.filter { $0.text.localizedCaseInsensitiveContains(q) }
    }

    func add(_ text: String, fromPhone: Bool) {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        items.removeAll { $0.text == t }
        items.insert(Item(text: String(t.prefix(20_000)), date: Date(), fromPhone: fromPhone), at: 0)
        if items.count > 100 { items.removeLast(items.count - 100) }
    }

    func clear() { items = [] }

    func remove(_ item: Item) { items.removeAll { $0.id == item.id } }

    func copy(_ item: Item) {
        let pb = NSPasteboard.general
        pb.clearContents()
        pb.setString(item.text, forType: .string) // ClipboardSync then carries it to the phone too
    }
}

// MARK: - Notification rules (Mac-side mute, per app and per keyword)

@MainActor
final class NotifRules: ObservableObject {
    @Published var mutedApps: Set<String> { didSet { UserDefaults.standard.set(Array(mutedApps), forKey: "mutedApps") } }
    @Published var keywords: [String] { didSet { UserDefaults.standard.set(keywords, forKey: "mutedKeywords") } }
    /// Offer three suggested replies as buttons on chat notifications (written on the Mac, nothing is sent anywhere).
    /// A corner pop-up with a reply box for chat messages, instead of the macOS banner.
    @Published var replyPopup: Bool { didSet { UserDefaults.standard.set(replyPopup, forKey: "replyPopup") } }
    @Published var smartReplies: Bool { didSet { UserDefaults.standard.set(smartReplies, forKey: "smartReplies") } }

    init() {
        mutedApps = Set(UserDefaults.standard.stringArray(forKey: "mutedApps") ?? [])
        keywords = UserDefaults.standard.stringArray(forKey: "mutedKeywords") ?? []
        smartReplies = UserDefaults.standard.object(forKey: "smartReplies") as? Bool ?? true
        replyPopup = UserDefaults.standard.object(forKey: "replyPopup") as? Bool ?? true
    }

    /// Muted notifications still land in History; they just don't pop up or make a sound on the Mac.
    func isMuted(_ m: [String: Any]) -> Bool {
        if let app = m["app"] as? String, mutedApps.contains(app) { return true }
        let text = ((m["title"] as? String ?? "") + " " + (m["text"] as? String ?? "")).lowercased()
        return keywords.contains { !$0.isEmpty && text.contains($0.lowercased()) }
    }

    func toggle(_ app: String) {
        if mutedApps.contains(app) { mutedApps.remove(app) } else { mutedApps.insert(app) }
    }

    func addKeyword(_ k: String) {
        let t = k.trimmingCharacters(in: .whitespaces)
        if !t.isEmpty && !keywords.contains(t) { keywords.append(t) }
    }
}

// MARK: - Read messages aloud

@MainActor
final class SpeechReader: ObservableObject {
    @Published var autoRead: Bool { didSet { UserDefaults.standard.set(autoRead, forKey: "readAloud") } }
    private let synth = AVSpeechSynthesizer()

    init() { autoRead = UserDefaults.standard.bool(forKey: "readAloud") }

    func speak(_ text: String) {
        synth.stopSpeaking(at: .immediate)
        let u = AVSpeechUtterance(string: text)
        u.rate = AVSpeechUtteranceDefaultSpeechRate
        synth.speak(u)
    }

    func stop() { synth.stopSpeaking(at: .immediate) }
}

// MARK: - Phone battery: time to full / time left, and alerts

@MainActor
final class PhoneBatteryWatch: ObservableObject {
    @Published var eta: String?
    @Published var alerts: Bool { didSet { UserDefaults.standard.set(alerts, forKey: "batteryAlerts") } }
    var notify: (String, String) -> Void = { _, _ in }

    private var samples: [(date: Date, level: Int)] = []
    private var lastCharging: Bool?
    private var alertedFull = false
    private var alertedLow = false

    init() { alerts = UserDefaults.standard.object(forKey: "batteryAlerts") as? Bool ?? true }

    func update(level: Int, charging: Bool) {
        if lastCharging != charging {
            samples = []
            lastCharging = charging
            alertedFull = false
        }
        if samples.last?.level != level {
            samples.append((Date(), level))
            if samples.count > 14 { samples.removeFirst() }
        }
        eta = estimate(level: level, charging: charging)
        guard alerts else { return }
        if charging && level >= 100 && !alertedFull {
            alertedFull = true
            notify("Phone fully charged", "You can unplug it.")
        }
        if !charging && level <= 15 && !alertedLow {
            alertedLow = true
            notify("Phone battery low", "\(level)% left — time to plug in.")
        }
        if level >= 25 { alertedLow = false }
    }

    private func estimate(level: Int, charging: Bool) -> String? {
        guard let first = samples.first, let last = samples.last, last.date.timeIntervalSince(first.date) >= 120,
              last.level != first.level else { return nil }
        let perMinute = Double(last.level - first.level) / (last.date.timeIntervalSince(first.date) / 60)
        if charging, perMinute > 0, level < 100 {
            return "Full in " + Self.format(minutes: Double(100 - level) / perMinute)
        }
        if !charging, perMinute < 0 {
            return Self.format(minutes: Double(level) / -perMinute) + " left"
        }
        return nil
    }

    private static func format(minutes: Double) -> String {
        let m = Int(minutes.rounded())
        if m < 60 { return "\(max(m, 1)) min" }
        return "\(m / 60) h \(m % 60) min"
    }
}

// MARK: - Mac Focus → phone Do Not Disturb

@MainActor
final class FocusLink: ObservableObject {
    @Published var enabled: Bool {
        didSet {
            UserDefaults.standard.set(enabled, forKey: "focusLink")
            if enabled { start() } else { timer?.invalidate(); timer = nil }
        }
    }
    var setPhoneDnd: (Bool) -> Void = { _ in }
    private var timer: Timer?
    private var last: Bool?

    init() { enabled = UserDefaults.standard.bool(forKey: "focusLink") }

    func resume() { if enabled { start() } }

    private func start() {
        INFocusStatusCenter.default.requestAuthorization { _ in }
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 4, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.poll() }
        }
    }

    private func poll() {
        guard INFocusStatusCenter.default.authorizationStatus == .authorized,
              let focused = INFocusStatusCenter.default.focusStatus.isFocused else { return }
        if last == nil { last = focused; return } // don't flip the phone just because the app started
        guard focused != last else { return }
        last = focused
        setPhoneDnd(focused)
    }
}

// MARK: - Live Text: read the text out of a photo

enum LiveText {
    static func recognize(_ url: URL, done: @escaping (String) -> Void) {
        DispatchQueue.global(qos: .userInitiated).async {
            var text = ""
            let request = VNRecognizeTextRequest { req, _ in
                let lines = (req.results as? [VNRecognizedTextObservation])?.compactMap { $0.topCandidates(1).first?.string } ?? []
                text = lines.joined(separator: "\n")
            }
            request.recognitionLevel = .accurate
            request.usesLanguageCorrection = true
            let handler = VNImageRequestHandler(url: url, options: [:])
            try? handler.perform([request])
            DispatchQueue.main.async { done(text) }
        }
    }
}

// MARK: - Right-click → Services → "Send to Phone"

final class ServicesProvider: NSObject {
    static let shared = ServicesProvider()

    @objc func sendToPhone(_ pboard: NSPasteboard, userData: String?, error: AutoreleasingUnsafeMutablePointer<NSString>) {
        let urls = (pboard.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL]) ?? []
        if !urls.isEmpty {
            Task { @MainActor in Bridge.shared.sendFiles(urls) }
        } else if let s = pboard.string(forType: .string), !s.isEmpty {
            Task { @MainActor in
                Bridge.shared.send(["t": "clip", "text": s])
                Bridge.shared.notifier.info("Sent to phone", String(s.prefix(80)))
            }
        } else {
            error.pointee = "Nothing to send" as NSString
        }
    }
}

// MARK: - Notepad shared with the phone (newest edit wins)

@MainActor
final class SharedNote: ObservableObject {
    @Published var text: String {
        didSet { if !applying { edited() } }
    }
    private(set) var ts: Int64
    var send: ([String: Any]) -> Void = { _ in }
    private var applying = false
    private var debounce: DispatchWorkItem?

    init() {
        text = UserDefaults.standard.string(forKey: "noteText") ?? ""
        ts = Int64(UserDefaults.standard.double(forKey: "noteTs"))
    }

    private func save() {
        UserDefaults.standard.set(text, forKey: "noteText")
        UserDefaults.standard.set(Double(ts), forKey: "noteTs")
    }

    private func edited() {
        ts = Int64(Date().timeIntervalSince1970 * 1000)
        save()
        debounce?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self else { return }
            self.send(["t": "note", "text": self.text, "ts": self.ts])
        }
        debounce = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4, execute: work)
    }

    func receive(_ m: [String: Any]) {
        guard let t = (m["ts"] as? NSNumber)?.int64Value, t > ts, let s = m["text"] as? String else { return }
        applying = true
        text = s
        applying = false
        ts = t
        save()
    }

    /// On connecting: the phone keeps whichever copy is newer, and sends its own back if that's it.
    func hello() { send(["t": "note_hello", "text": text, "ts": ts]) }
}
