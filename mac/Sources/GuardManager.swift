import AppKit
import CryptoKit
import SwiftUI

struct GuardEvent: Identifiable, Codable {
    var id: String
    var date: Date
    var reason: String
    var files: [String]
    var ownerNear: Bool
    var sent: Bool
    var status: String // "", "confirmed" (you said it was you), "locked", "quiet"
}

/// When the Mac asks for the unlock code by itself.
enum RequireMode: String, CaseIterable, Identifiable {
    case off, away, always
    var id: String { rawValue }
    var title: String {
        switch self {
        case .off: "Never (only when I press Lock Mac on my phone)"
        case .away: "When my phone isn't near (recommended)"
        case .always: "Every time"
        }
    }
}

/// Intruder guard: photos when the Mac wakes / unlocks / starts, an alert to the owner's phone (over the paired link, or over
/// the internet through a relay when the phone isn't nearby), and a lock screen the owner can trigger — opened only with the
/// authenticator code from the phone (works offline) or the recovery code.
@MainActor
final class IntruderGuard: ObservableObject {
    @Published var enabled: Bool { didSet { UserDefaults.standard.set(enabled, forKey: "guardOn") } }
    /// Skip the alert (still keep the photos) when the phone is connected or nearby — that's almost certainly you.
    @Published var onlyWhenAway: Bool { didSet { UserDefaults.standard.set(onlyWhenAway, forKey: "guardOnlyAway") } }
    /// Ask for the code after the Mac wakes from sleep, restarts or starts up.
    @Published var requireMode: RequireMode { didSet { UserDefaults.standard.set(requireMode.rawValue, forKey: "guardRequire") } }
    @Published var server: String { didSet { UserDefaults.standard.set(server, forKey: "guardServer"); if enabled { startRelay() } } }
    @Published private(set) var events: [GuardEvent] = []
    @Published private(set) var capturing = false
    @Published private(set) var locked = false

    var sendLink: ([String: Any]) -> Void = { _ in }
    var isConnected: () -> Bool = { false }
    var phoneNear: () -> Bool = { false }
    var notify: (String, String) -> Void = { _, _ in }

    private let camera = GuardCamera()
    private let lockScreen = GuardLockController()
    private var lastCapture = Date.distantPast
    private var quietUntil = Date.distantPast
    private var cmdTask: Task<Void, Never>?
    private var resendTimer: Timer?
    private var purgeTimer: Timer?
    /// When the Mac last went away (slept, display off, or screen locked); nil while it's in use.
    private var awaySince: Date?
    /// True when the lock came from "ask after sleep/startup" (the phone coming near may lift it), false when you pressed Lock Mac.
    private var lockIsAuto = false
    /// Alerts raised while an automatic lock is up: "It's me" on any of them lifts the lock.
    private var lockEventIds = Set<String>()
    private var warnedCamera = false

    init() {
        let d = UserDefaults.standard
        enabled = d.bool(forKey: "guardOn")
        onlyWhenAway = d.object(forKey: "guardOnlyAway") as? Bool ?? true
        server = d.string(forKey: "guardServer") ?? "https://ntfy.sh"
        requireMode = RequireMode(rawValue: d.string(forKey: "guardRequire") ?? "") ?? .away
        locked = Keychain.get("guardLocked") != nil
    }

    var lockShowing: Bool { lockScreen.showing }
    private var key: Data? { Keychain.get("guardKey") }
    var isSetUp: Bool { enabled && key != nil }
    var requiresCodeToQuit: Bool { isSetUp }

    private var folder: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("PhoneBridge/Guard", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        return dir
    }

    func photoURL(_ name: String) -> URL { folder.appendingPathComponent(name) }
    func revealFolder() { NSWorkspace.shared.open(folder) }

    // MARK: Start

    func start() {
        loadEvents()
        trimEvents()
        purgeTimer = Timer.scheduledTimer(withTimeInterval: 600, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.purgeOldDays() }
        }
        lockScreen.model.verify = { [weak self] in self?.verify($0) ?? false }
        // Touch ID or the code: the owner proved it's them, so photos that were waiting aren't taken.
        lockScreen.model.onUnlocked = { [weak self] in
            self?.pendingPhoto?.cancel()
            self?.pendingPhoto = nil
            self?.releaseLock()
        }
        // "Away" = the Mac asleep, the display asleep, or the screen locked. Coming back from any of them after a while is
        // someone opening the Mac: photos, plus the code if that's how it's set up.
        let ws = NSWorkspace.shared.notificationCenter
        let dc = DistributedNotificationCenter.default()
        for name in [NSWorkspace.willSleepNotification, NSWorkspace.screensDidSleepNotification] {
            ws.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in Task { @MainActor in self?.markAway() } }
        }
        dc.addObserver(forName: NSNotification.Name("com.apple.screenIsLocked"), object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.markAway() }
        }
        ws.addObserver(forName: NSWorkspace.didWakeNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.opened("Mac woke from sleep") }
        }
        ws.addObserver(forName: NSWorkspace.screensDidWakeNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.opened("Screen woke up") }
        }
        dc.addObserver(forName: NSNotification.Name("com.apple.screenIsUnlocked"), object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.opened("Mac unlocked") }
        }
        if isSetUp {
            startRelay()
            startResendTimer()
            if locked {
                // Locked before a restart or quit: stay locked.
                lockIsAuto = (Keychain.get("guardLocked")?.first == 2)
                lockScreen.present(test: false)
                watchForPhone()
            } else if ProcessInfo.processInfo.systemUptime < 600 {
                // Only a real startup counts. Reopening or updating PhoneBridge doesn't photograph, lock or ask for anything.
                autoLock()
                triggerAfterGrace("Mac started and logged in")
            }
        } else if locked {
            Keychain.delete("guardLocked") // no key means no way to unlock: never lock in that state
            locked = false
        }
    }

    private func markAway() {
        if awaySince == nil { awaySince = Date() }
    }

    /// The Mac just came back from being away: photograph, and lock if it was away long enough (a quick lid flick isn't worth a code).
    private func opened(_ reason: String) {
        purgeOldDays()
        let awayFor = Date().timeIntervalSince(awaySince ?? Date())
        awaySince = nil
        // "Every time" means every time (only ignoring a bounce of a few seconds); the other mode skips short lid flicks.
        if awayFor >= (requireMode == .always ? 3 : 20) { autoLock() }
        triggerAfterGrace(reason)
    }

    private var pendingPhoto: Task<Void, Never>?

    /// While the lock screen is asking for Touch ID / the code, wait a little before photographing: unlocking
    /// in that time cancels the photos. Without a lock screen, photograph at once.
    private func triggerAfterGrace(_ reason: String) {
        guard locked, lockScreen.showing else { return trigger(reason) }
        guard pendingPhoto == nil else { return }
        pendingPhoto = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 12_000_000_000)
            guard !Task.isCancelled else { return }
            pendingPhoto = nil
            trigger(reason)
        }
    }

    // MARK: Enable / disable

    /// Sets the guard up (needs the phone connected, to hand it the key). Returns the recovery code to show once, or nil.
    func enable() -> String? {
        guard isConnected() else {
            notify("Connect your phone first", "The guard shares a secret key with your phone, so it must be connected while you turn this on.")
            return nil
        }
        let k = GuardCrypto.newKey()
        let recovery = GuardCrypto.newRecoveryCode()
        let salt = GuardCrypto.newKey().prefix(16)
        Keychain.set(k, "guardKey")
        Keychain.set(Data(salt) + GuardCrypto.recoveryHash(recovery, salt: Data(salt)), "guardRecovery")
        sendLink(["t": "guard_setup", "key": k.base64EncodedString(), "server": server])
        enabled = true
        startRelay()
        startResendTimer()
        return recovery
    }

    /// Turning it off needs the code, so someone sitting at the Mac can't just switch it off.
    func disable() -> Bool {
        guard !isSetUp || askCode("Turn off the guard") else { return false }
        sendLink(["t": "guard_off"])
        cmdTask?.cancel()
        resendTimer?.invalidate()
        Keychain.delete("guardKey")
        Keychain.delete("guardRecovery")
        Keychain.delete("guardLocked")
        enabled = false
        locked = false
        lockScreen.dismiss()
        return true
    }

    // MARK: Codes

    /// The link behind the authenticator QR code. Only shown after entering a code (see GuardSettingsView).
    func authenticatorURL() -> String? { key.map { GuardCrypto.authenticatorURL($0) } }

    func verify(_ raw: String) -> Bool {
        guard let key else { return false }
        let code = raw.trimmingCharacters(in: .whitespaces)
        if code.count == 6, code.allSatisfy(\.isNumber), GuardCrypto.verifyTOTP(code, seed: GuardCrypto.totpSeed(key)) { return true }
        if let blob = Keychain.get("guardRecovery"), blob.count > 16 {
            let salt = blob.prefix(16)
            return GuardCrypto.recoveryHash(code, salt: Data(salt)) == blob.dropFirst(16)
        }
        return false
    }

    /// A small dialog asking for the unlock code. Returns true if it was right.
    func askCode(_ title: String) -> Bool {
        NSApp.activate(ignoringOtherApps: true)
        let alert = NSAlert()
        alert.messageText = title
        alert.informativeText = "Enter the code from the PhoneBridge app on your phone, or your recovery code."
        let field = NSSecureTextField(frame: NSRect(x: 0, y: 0, width: 240, height: 24))
        alert.accessoryView = field
        alert.addButton(withTitle: "Continue")
        alert.addButton(withTitle: "Cancel")
        alert.window.initialFirstResponder = field
        guard alert.runModal() == .alertFirstButtonReturn else { return false }
        return verify(field.stringValue)
    }

    /// Asked by the app before it quits. System shutdown/restart/logout always goes through.
    func confirmQuit() -> Bool {
        if lockScreen.showing { return false }
        return askCode("Quit PhoneBridge")
    }

    // MARK: Photos

    func testAlert() { trigger("Test alert", force: true) }

    func testLockScreen() { lockScreen.present(test: true) }

    private func trigger(_ reason: String, force: Bool = false) {
        guard (isSetUp || force), key != nil, !capturing else { return }
        if !force, Date().timeIntervalSince(lastCapture) < 20 { return }
        lastCapture = Date()
        capturing = true
        GuardCamera.authorize { [weak self] ok in
            guard let self else { return }
            guard ok else {
                self.capturing = false
                if !self.warnedCamera {
                    self.warnedCamera = true
                    self.notify("Allow the camera", "System Settings → Privacy & Security → Camera → turn on PhoneBridge, so the guard can take photos.")
                }
                self.finish(reason, photos: [], force: force)
                return
            }
            self.camera.snap(count: 3, spacing: 1.5) { photos in self.finish(reason, photos: photos, force: force) }
        }
    }

    private func finish(_ reason: String, photos: [Data], force: Bool) {
        capturing = false
        let id = UUID().uuidString
        var files: [String] = []
        for (i, p) in photos.enumerated() {
            let name = "\(id)-\(i).jpg"
            try? p.write(to: photoURL(name), options: .atomic)
            files.append(name)
        }
        if locked && lockIsAuto { lockEventIds.insert(id) }
        events.insert(GuardEvent(id: id, date: Date(), reason: reason, files: files, ownerNear: false, sent: false, status: ""), at: 0)
        trimEvents()
        saveEvents()
        Task { @MainActor in
            // Locked and waiting for you ("every time" mode): always send the photo, right away.
            let alwaysAsk = locked && lockIsAuto && requireMode == .always
            var near = false
            if !force && !alwaysAsk && onlyWhenAway {
                // Give the phone a few seconds to reconnect after a wake before deciding nobody's around.
                for _ in 0..<8 {
                    if phoneNear() { near = true; break }
                    try? await Task.sleep(nanoseconds: 1_000_000_000)
                }
            }
            update(id) { $0.ownerNear = near }
            if !force && near { return }
            if !force && !locked && Date() < quietUntil { update(id) { $0.status = "quiet" }; return }
            await deliver(id)
        }
    }

    // MARK: Alerts

    /// Sends an alert's photos to the phone. With [resend] the phone asked for them again (it doesn't keep pictures), so
    /// nothing new is announced; if they've been deleted since, the phone is told so.
    private func deliver(_ id: String, resend: Bool = false) async {
        guard let key else { return }
        let ev = events.first(where: { $0.id == id })
        if ev == nil && !resend { return }
        let photos = ev?.files.compactMap { try? Data(contentsOf: photoURL($0)) } ?? []
        var bundle: [String: Any] = ["id": id, "reason": ev?.reason ?? "", "time": Int((ev?.date ?? Date()).timeIntervalSince1970 * 1000),
                                     "host": Host.current().localizedName ?? "Mac", "locked": locked, "photos": photos.map { $0.base64EncodedString() }]
        if resend { bundle["resend"] = true }
        if isConnected() {
            var msg = bundle
            msg["t"] = "guard_alert"
            sendLink(msg)
            if !resend { update(id) { $0.sent = true } }
            return
        }
        guard let json = try? JSONSerialization.data(withJSONObject: bundle), let sealed = try? GuardCrypto.seal(json, key: key) else { return }
        do {
            try await GuardRelay(server: server).publish(topic: GuardCrypto.topic(key, "topic-out"), body: sealed, filename: "guard.bin")
            if !resend { update(id) { $0.sent = true } }
        } catch {
            // No internet yet: the timer below tries again (for new alerts).
        }
    }

    private func startResendTimer() {
        resendTimer?.invalidate()
        resendTimer = Timer.scheduledTimer(withTimeInterval: 60, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                for ev in self.events where !ev.sent && !ev.ownerNear && ev.status.isEmpty && Date().timeIntervalSince(ev.date) < 86_400 {
                    await self.deliver(ev.id)
                }
            }
        }
    }

    // MARK: Commands from the phone

    func handleCommand(_ c: String, eventId: String?) {
        switch c {
        case "safe":
            if let eventId { update(eventId) { $0.status = "confirmed" } }
            quietUntil = Date().addingTimeInterval(600)
            // Confirming it's you opens an automatic lock (a lock you set with "Lock Mac" needs the code).
            if locked && lockIsAuto && (eventId == nil || lockEventIds.contains(eventId!)) { releaseLock() }
        case "lock":
            if let eventId { update(eventId) { $0.status = "locked" } }
            engageLock(auto: false)
        case "photos":
            if let eventId { Task { @MainActor in await deliver(eventId, resend: true) } }
        case "unlock":
            releaseLock()
        default:
            break
        }
    }

    private func engageLock(auto: Bool = false) {
        guard isSetUp else { return }
        if !auto { lockEventIds = [] }
        lockIsAuto = auto
        Keychain.set(Data([auto ? 2 : 1]), "guardLocked")
        locked = true
        lockScreen.present(test: false)
    }

    private func releaseLock() {
        lockIsAuto = false
        lockEventIds = []
        Keychain.delete("guardLocked")
        locked = false
        lockScreen.dismiss()
    }

    /// After sleep, restart or startup: lock straight away (so there's no open window of access), then — in the
    /// "when my phone isn't near" mode — lift it again as soon as the phone is found nearby.
    private func autoLock() {
        guard isSetUp, requireMode != .off, !lockScreen.showing else { return }
        engageLock(auto: true)
        watchForPhone()
    }

    private func watchForPhone() {
        if requireMode == .always, lockIsAuto {
            lockScreen.model.hint = "Waiting for you to tap “It's me” on your phone — or enter the code."
            return
        }
        guard requireMode == .away, lockIsAuto else {
            lockScreen.model.hint = ""
            return
        }
        lockScreen.model.hint = "Looking for your phone…"
        Task { @MainActor in
            for _ in 0..<30 { // about 15 seconds
                try? await Task.sleep(nanoseconds: 500_000_000)
                guard locked, lockIsAuto, lockScreen.showing else { return }
                if phoneNear() {
                    releaseLock()
                    return
                }
            }
            if locked, lockIsAuto { lockScreen.model.hint = "Phone not found — enter the code from its Code tab." }
        }
    }

    // MARK: Relay (commands that arrive over the internet)

    private func startRelay() {
        cmdTask?.cancel()
        guard let key else { return }
        let topic = GuardCrypto.topic(key, "topic-cmd")
        let relay = GuardRelay(server: server)
        cmdTask = Task { [weak self] in
            var delay: UInt64 = 5
            while !Task.isCancelled {
                let since = UserDefaults.standard.object(forKey: "guardSince") as? Int
                do {
                    try await relay.subscribe(topic: topic, since: since.map { String($0 + 1) } ?? "12h") { message, time in
                        Task { @MainActor in self?.handleRelay(message, time: time) }
                    }
                    delay = 5
                } catch {
                    // offline or the server hiccuped: retry
                }
                try? await Task.sleep(nanoseconds: delay * 1_000_000_000)
                delay = min(delay * 2, 60)
            }
        }
    }

    /// Message = base64url(JSON) "." base64url(HMAC). JSON: {c: command, e: event id, ts: ms, n: nonce}.
    private func handleRelay(_ message: String, time: Int) {
        UserDefaults.standard.set(time, forKey: "guardSince")
        guard let key else { return }
        let parts = message.split(separator: ".").map(String.init)
        guard parts.count == 2, let json = GuardCrypto.fromB64url(parts[0]), let mac = GuardCrypto.fromB64url(parts[1]),
              mac == GuardCrypto.sign(json, key: key),
              let o = try? JSONSerialization.jsonObject(with: json) as? [String: Any],
              let c = o["c"] as? String, let ts = o["ts"] as? Double, let n = o["n"] as? String else { return }
        guard abs(Date().timeIntervalSince1970 * 1000 - ts) < 12 * 3600 * 1000 else { return } // relay keeps messages ~12 h
        var seen = UserDefaults.standard.stringArray(forKey: "guardNonces") ?? []
        guard !seen.contains(n) else { return } // replay
        seen.append(n)
        UserDefaults.standard.set(Array(seen.suffix(100)), forKey: "guardNonces")
        handleCommand(c, eventId: o["e"] as? String)
    }

    // MARK: Event log

    private var logURL: URL { folder.appendingPathComponent("events.json") }

    private func loadEvents() {
        guard let data = try? Data(contentsOf: logURL) else { return }
        let dec = JSONDecoder()
        dec.dateDecodingStrategy = .secondsSince1970
        events = (try? dec.decode([GuardEvent].self, from: data)) ?? []
    }

    private func saveEvents() {
        let enc = JSONEncoder()
        enc.dateEncodingStrategy = .secondsSince1970
        if let data = try? enc.encode(events) { try? data.write(to: logURL, options: .atomic) }
    }

    private func update(_ id: String, _ change: (inout GuardEvent) -> Void) {
        guard let i = events.firstIndex(where: { $0.id == id }) else { return }
        change(&events[i])
        saveEvents()
    }

    /// Photos and events are kept for the current day only: everything from before today's midnight is deleted.
    private func trimEvents() {
        let cutoff = Calendar.current.startOfDay(for: Date())
        let old = events.enumerated().filter { $0.offset >= 40 || $0.element.date < cutoff }.map(\.element)
        for ev in old { ev.files.forEach { try? FileManager.default.removeItem(at: photoURL($0)) } }
        let drop = Set(old.map(\.id))
        events.removeAll { drop.contains($0.id) }
        // Any stray photo files from before today (e.g. an interrupted save).
        let known = Set(events.flatMap(\.files))
        for url in (try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: [.contentModificationDateKey])) ?? [] where url.pathExtension == "jpg" {
            let modified = (try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
            if !known.contains(url.lastPathComponent) && modified < cutoff { try? FileManager.default.removeItem(at: url) }
        }
        if !old.isEmpty { saveEvents() }
    }

    /// Runs at start, on every wake, and every 10 minutes, so yesterday's photos are gone soon after midnight.
    func purgeOldDays() { trimEvents() }
}
