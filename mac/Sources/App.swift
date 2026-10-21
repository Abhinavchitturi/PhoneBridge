import AppKit
import ServiceManagement
import SwiftUI

@main
struct PhoneBridgeApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @ObservedObject private var bridge = Bridge.shared

    var body: some Scene {
        Window("PhoneBridge", id: "main") {
            MainView(bridge: bridge, streamer: bridge.streamer)
        }
        .windowResizability(.contentSize)

        Window("Phone", id: "phone") {
            PhoneView(store: bridge.phone, bridge: bridge)
        }
        .defaultSize(width: 1340, height: 780)

        Window("Phone Apps", id: "apps") {
            AppsView(bridge: bridge, streamer: bridge.streamer)
        }
        .defaultSize(width: 620, height: 520)

        Window("Type on Phone", id: "type") {
            TypeOnPhoneView(bridge: bridge)
        }
        .windowResizability(.contentSize)
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        let event = NSAppleEventManager.shared().currentAppleEvent
        let launchedAtLogin = event?.eventID == kAEOpenApplication &&
            event?.paramDescriptor(forKeyword: keyAEPropData)?.enumCodeValue == keyAELaunchedAsLogInItem
        Transfers.wipePhotoCache()
        NSApp.servicesProvider = ServicesProvider.shared
        NSUpdateDynamicServices()
        // A menu-bar app must never be quietly reaped by macOS (automatic/sudden termination).
        ProcessInfo.processInfo.disableAutomaticTermination("PhoneBridge keeps the phone link alive")
        ProcessInfo.processInfo.disableSuddenTermination()
        // While a window is open the app shows in the Dock and ⌘-Tab; with none it's menu-bar only.
        NotificationCenter.default.addObserver(forName: NSWindow.didBecomeKeyNotification, object: nil, queue: .main) { [weak self] n in
            self?.updateActivationPolicy(closing: nil)
        }
        NotificationCenter.default.addObserver(forName: NSWindow.willCloseNotification, object: nil, queue: .main) { [weak self] n in
            self?.updateActivationPolicy(closing: n.object as? NSWindow)
        }
        MainActor.assumeIsolated {
            Bridge.shared.start()
            UIState.shared.enableLoginItemByDefault()
        }
        // At login, start quietly in the menu bar; when opened by hand, show the window.
        if launchedAtLogin {
            DispatchQueue.main.async { NSApp.windows.filter { $0.isVisible && $0.level == .normal }.forEach { $0.close() } }
        } else {
            NSApp.activate(ignoringOtherApps: true)
        }
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }

    /// With the guard on, quitting asks for the code. Shutdown, restart and logout (which carry a quit reason) always go through.
    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        MainActor.assumeIsolated {
            let guardian = Bridge.shared.guardian
            guard guardian.requiresCodeToQuit else { return .terminateNow }
            if NSAppleEventManager.shared().currentAppleEvent?.attributeDescriptor(forKeyword: AEKeyword(kAEQuitReason)) != nil { return .terminateNow }
            return guardian.confirmQuit() ? .terminateNow : .terminateCancel
        }
    }

    private func updateActivationPolicy(closing: NSWindow?) {
        DispatchQueue.main.async {
            if MainActor.assumeIsolated({ Bridge.shared.guardian.lockShowing }) { return } // the lock screen needs a regular app
            let hasWindow = NSApp.windows.contains {
                $0 !== closing && $0.isVisible && $0.level == .normal && !($0 is NSPanel) && $0.canBecomeMain
            }
            let want: NSApplication.ActivationPolicy = hasWindow ? .regular : .accessory
            guard NSApp.activationPolicy() != want else { return }
            NSApp.setActivationPolicy(want)
            if want == .regular { NSApp.activate(ignoringOtherApps: true) }
        }
    }

    // Opening the app again (Finder, Spotlight, Launchpad) brings the window back.
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows: Bool) -> Bool {
        MainActor.assumeIsolated { UIState.shared.show("main") }
        return true
    }

    func applicationWillTerminate(_ notification: Notification) {
        MainActor.assumeIsolated {
            Bridge.shared.streamer.stopAll()
            Bridge.shared.mac.stopFinding() // never leave the Mac stuck at full volume
        }
        Transfers.wipePhotoCache()
    }
}

// MARK: - State

/// View state kept outside the views: this toolchain (no Xcode) lacks the plugin behind SwiftUI's @State.
@MainActor
final class UIState: ObservableObject {
    static let shared = UIState()
    @Published var showSetup = false
    @Published var hostPort = ""
    @Published var code = ""
    @Published var search = ""
    @Published var phoneText = ""
    @Published var replyDrafts: [String: String] = [:]
    var openWindow: OpenWindowAction?

    @Published var openAtLogin = SMAppService.mainApp.status == .enabled {
        didSet {
            guard openAtLogin != (SMAppService.mainApp.status == .enabled) else { return }
            do {
                if openAtLogin { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
                UserDefaults.standard.set(!openAtLogin, forKey: "loginItemOptOut")
            } catch {
                NSLog("PhoneBridge: login item change failed: \(error)")
                openAtLogin = SMAppService.mainApp.status == .enabled
            }
        }
    }

    /// Start at login unless the user switched it off.
    func enableLoginItemByDefault() {
        if !UserDefaults.standard.bool(forKey: "loginItemOptOut") && SMAppService.mainApp.status != .enabled {
            try? SMAppService.mainApp.register()
        }
        openAtLogin = SMAppService.mainApp.status == .enabled
    }

    /// Opens one of the app's windows (captured from the main window, so it works from the menu bar too).
    func show(_ id: String) {
        openWindow?(id: id)
        NSApp.activate(ignoringOtherApps: true)
    }
}

struct NowPlaying {
    let title: String
    let artist: String
    let app: String
    let playing: Bool
    let art: NSImage?
}

/// App state + message routing between the phone link and the Mac features.
@MainActor
final class Bridge: ObservableObject {
    static let shared = Bridge()

    @Published var status = "Looking for your phone…"
    @Published var connected = false
    @Published var battery: Int?
    @Published var charging = false
    @Published var phoneRinging = false
    @Published var phoneName = "Phone"
    @Published var apps: [PhoneApp] = []
    @Published var nowPlaying: NowPlaying?
    @Published var phoneDnd = false
    @Published var reconnecting = false

    let link = PhoneLink()
    let notifier = Notifier()
    let history = NotificationHistory()
    let clipboard = ClipboardSync()
    let transfers = Transfers()
    let streamer = AppStreamer()
    let callPanel = CallPanel()
    let mac = MacControl()
    let macMedia = MacMedia()
    let phone = PhoneStore()
    let input = InputControl()
    let presence = Presence()
    let screenshots = ScreenshotWatcher()
    let mic = PhoneMic()
    let statusBar = StatusBar()
    let cast = CastWindow()
    let clipHistory = ClipboardHistory()
    let rules = NotifRules()
    let speech = SpeechReader()
    let batteryWatch = PhoneBatteryWatch()
    let focusLink = FocusLink()
    let note = SharedNote()
    let chatPopup = ChatPopup()
    let guardian = IntruderGuard()
    private var typeDebounce: DispatchWorkItem?

    func start() {
        link.onState = { state in Task { @MainActor in self.update(state) } }
        // Video frames are decoded right on the network queue; everything else goes to the main actor.
        let decoder = cast.decoder
        link.onMessage = { [link] msg in
            if msg["t"] as? String == "v" {
                if let b64 = msg["d"] as? String, let data = Data(base64Encoded: b64) { decoder.decode(data) }
                // Ack every frame so the phone never runs ahead of what we're showing.
                if let seq = msg["s"] as? Int { link.send(["t": "va", "s": seq]) }
                return
            }
            Task { @MainActor in self.handle(msg) }
        }
        cast.send = { [link] in link.send($0) }
        link.onStranger = { [notifier] name in
            notifier.info("Blocked a pairing attempt", "“\(name)” tried to connect. PhoneBridge only talks to your paired phone — use Forget phone first if you're switching phones.")
        }
        streamer.onIdle = { [link] in link.send(["t": "debug_off"]) }
        streamer.onSetupDone = { [link] in link.send(["t": "debug_off"]) }
        cast.notify = { [notifier] title, body in notifier.info(title, body) }
        link.askPairing = { code, name, decide in Task { @MainActor in self.askPairing(code, name, decide) } }
        notifier.send = { [link] in link.send($0) }
        notifier.onDismiss = { [history] key in Task { @MainActor in history.markRemoved(key) } }
        mac.send = { [link] in link.send($0) }
        mac.notify = { [notifier] title, body in notifier.info(title, body) }
        mac.mediaCommand = { [macMedia] in macMedia.command($0) }
        mac.onCall = { [macMedia] on in if on { macMedia.pauseForCall() } else { macMedia.resumeAfterCall() } }
        macMedia.send = { [link] in link.send($0) }
        phone.send = { [link] in link.send($0) }
        phone.onSendFiles = { [weak self] in self?.sendFiles($0) }
        phone.notify = { [notifier] title, body in notifier.info(title, body) }
        phone.onDial = { [callPanel] name, number in callPanel.showOutgoing(name: name, number: number) }
        input.notify = { [notifier] title, body in notifier.info(title, body) }
        presence.lock = { [mac] in mac.lock() }
        callPanel.onAnswer = { [link] in link.send(["t": "call_answer"]) }
        callPanel.onDecline = { [link] in link.send(["t": "call_end"]) }
        guardian.sendLink = { [link] in link.send($0) }
        guardian.isConnected = { [weak self] in self?.connected ?? false }
        guardian.phoneNear = { [weak self] in (self?.connected ?? false) || (self?.presence.seenRecently() ?? false) }
        guardian.notify = { [notifier] title, body in notifier.info(title, body) }
        chatPopup.onReply = { [link] id, text in link.send(["t": "reply", "id": id, "text": text]) }
        chatPopup.isBusy = { [callPanel] in callPanel.active || callPanel.name != nil || callPanel.outgoing }
        callPanel.onControl = { [link] action in link.send(["t": "call_ctl", "action": action]) }
        clipboard.send = { [link] text in link.send(["t": "clip", "text": text]) }
        clipboard.sendImage = { [link] png in link.send(["t": "clip_image", "png": png.base64EncodedString()]) }
        screenshots.onScreenshot = { [weak self] url in self?.sendFiles([url], kind: "screenshot", quiet: true) }
        transfers.onReceived = { url, kind in Task { @MainActor in self.received(url, kind: kind) } }
        transfers.onProgress = { kind, got, total in
            guard let k = kind, k.hasPrefix("photo:"), let id = Int64(k.dropFirst(6)) else { return }
            Task { @MainActor in self.phone.photoProgress(id: id, got: got, total: total) }
        }

        clipboard.onText = { [clipHistory] text, fromPhone in Task { @MainActor in clipHistory.add(text, fromPhone: fromPhone) } }
        phone.speak = { [speech] in speech.speak($0) }
        batteryWatch.notify = { [notifier] title, body in notifier.info(title, body) }
        focusLink.setPhoneDnd = { [link] on in link.send(["t": "phone_dnd", "on": on]) }
        callPanel.onEnded = { [notifier] name, number, seconds in
            guard let number else { return }
            let who = name ?? number
            let len = seconds >= 60 ? "\(seconds / 60) min \(seconds % 60) s" : "\(seconds) s"
            notifier.callEnded(title: "Call with \(who) ended", body: len, number: number)
        }
        notifier.onCallBack = { [phone] number in Task { @MainActor in phone.dial(number) } }
        notifier.onTextBack = { number in Task { @MainActor in self.openMessages(with: number) } }
        focusLink.resume()
        note.send = { [link] in link.send($0) }

        notifier.start()
        clipboard.start()
        mac.start()
        macMedia.start()
        presence.start()
        screenshots.start()
        statusBar.start(bridge: self)
        link.start()
        streamer.start()
        guardian.start()
    }

    func send(_ msg: [String: Any]) { link.send(msg) }

    /// Opens the Messages tab on the conversation with [number] (or the compose box if there isn't one yet).
    func openMessages(with number: String) {
        UIState.shared.show("phone")
        phone.tab = .messages
        let digits = String(number.filter(\.isNumber).suffix(10))
        if let t = phone.threads.first(where: { $0.address.filter(\.isNumber).hasSuffix(digits) }) {
            phone.selectedThread = t.id
        } else {
            composeSMS()
        }
    }

    func toggleRing() {
        send(["t": phoneRinging ? "ring_stop" : "ring"])
        phoneRinging.toggle() // optimistic; the phone confirms with ring_state
    }

    func requestApps() {
        if connected { send(["t": "apps_request"]) }
    }

    func mediaCommand(_ cmd: String) { send(["t": "media_cmd", "cmd": cmd]) }

    /// Open a phone app on the Mac. Preferred: its own window on a hidden screen (phone stays free) —
    /// the phone switches Developer options on just for this. Fallback: casting the phone's screen.
    func openApp(_ app: PhoneApp) {
        startSession(open: { [streamer] in streamer.open(app) },
                     fallback: { [cast] in cast.open(app: app.id, title: app.name) }, label: app.name)
    }

    func mirror() {
        let name = phoneName
        startSession(open: { [streamer] in streamer.mirror(title: name) },
                     fallback: { [cast] in cast.open(app: nil, title: name) }, label: name)
    }

    private var pendingOpen: (() -> Void)?
    private var pendingFallback: (() -> Void)?

    private func startSession(open: @escaping () -> Void, fallback: @escaping () -> Void, label: String) {
        if streamer.serial != nil { return open() } // session already running
        guard streamer.isPaired, streamer.status != .missingTools, connected else { return fallback() }
        pendingOpen = open
        pendingFallback = fallback
        if streamer.status == .connecting { return } // already on its way; open this one when ready
        notifier.info("Opening \(label)…", "Switching on wireless debugging on your phone")
        send(["t": "debug_on"])
        // If the phone never answers, don't leave you waiting.
        DispatchQueue.main.asyncAfter(deadline: .now() + 25) { [weak self] in
            guard let self, self.pendingOpen != nil, self.streamer.serial == nil else { return }
            self.runFallback("Couldn't reach the phone over Wi-Fi — showing its screen instead.")
        }
    }

    private func handleDebugState(_ m: [String: Any]) {
        guard pendingOpen != nil else { return }
        guard m["on"] as? Bool == true else {
            switch m["reason"] as? String {
            case "no_wifi": runFallback("Your phone isn't on Wi-Fi (e.g. it's sharing a hotspot), so showing its screen instead.")
            case "no_permission": runFallback("Finish the one-time setup in the PhoneBridge window to open apps separately.")
            default: runFallback(nil)
            }
            return
        }
        streamer.connect { [weak self] serial in
            guard let self else { return }
            if serial != nil {
                self.pendingOpen?()
                self.pendingOpen = nil
                self.pendingFallback = nil
            } else {
                self.send(["t": "debug_off"])
                self.runFallback("Wireless debugging didn't connect — showing the phone's screen instead.")
            }
        }
    }

    private func runFallback(_ why: String?) {
        if let why { notifier.info("Phone apps", why) }
        pendingFallback?()
        pendingOpen = nil
        pendingFallback = nil
    }

    /// Mirrors the "Type on Phone" text into the phone's focused text field (debounced).
    func typeOnPhone(_ text: String) {
        typeDebounce?.cancel()
        let work = DispatchWorkItem { [link] in link.send(["t": "phone_set_text", "text": text]) }
        typeDebounce = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.15, execute: work)
    }

    private func update(_ state: PhoneLink.State) {
        switch state {
        case .searching:
            connected = false
            phoneRinging = false
            nowPlaying = nil
            callPanel.hide()
            status = "Looking for your phone…"
        case .pairing(let name):
            connected = false
            status = "Pairing with \(name)…"
        case .connected(let name):
            connected = true
            phoneName = name
            status = "Connected to \(name)"
            mac.sendState(force: true)
            macMedia.resend()
            note.hello()
        }
    }

    private func handle(_ m: [String: Any]) {
        switch m["t"] as? String {
        case "notif":
            let muted = rules.isMuted(m)
            if !muted {
                let canReply = m["reply"] as? Bool == true
                let latest = m["text"] as? String ?? ""
                let sender = m["title"] as? String ?? ""
                let earlier = history.items.filter { $0.app == (m["app"] as? String ?? "") && $0.title == sender }
                    .prefix(5).reversed().map { "\(sender): \($0.text)" }
                if rules.replyPopup, canReply {
                    // Show the reply box straight away; the suggested replies fill in a moment later.
                    chatPopup.show(m)
                    if rules.smartReplies, !latest.isEmpty {
                        let id = m["id"] as? String
                        Task { @MainActor in
                            let suggestions = await SmartReply.suggest(sender: sender, latest: latest, earlier: Array(earlier))
                            self.chatPopup.setSuggestions(for: id, suggestions)
                        }
                    }
                } else if rules.smartReplies, canReply, !latest.isEmpty {
                    // Earlier lines from the same chat give the suggestions some context.
                    let sender = m["title"] as? String ?? ""
                    let earlier = history.items.filter { $0.app == (m["app"] as? String ?? "") && $0.title == sender }
                        .prefix(5).reversed().map { "\(sender): \($0.text)" }
                    Task { @MainActor in
                        let suggestions = await SmartReply.suggest(sender: sender, latest: latest, earlier: Array(earlier))
                        self.notifier.show(m, suggestions: suggestions)
                    }
                } else {
                    notifier.show(m)
                }
            }
            history.add(m)
            if !muted, speech.autoRead, m["reply"] as? Bool == true {
                speech.speak([m["title"] as? String, m["text"] as? String].compactMap { $0 }.joined(separator: ". "))
            }
        case "notif_removed":
            if let id = m["id"] as? String {
                notifier.remove(id)
                chatPopup.remove(id)
                history.markRemoved(id)
            }
        case "phone_dnd": phoneDnd = m["on"] as? Bool ?? false
        case "note": note.receive(m)
        case "cast_state": cast.handleState(m)
        case "cast_diag": cast.handleDiag(m)
        case "debug_state": handleDebugState(m)
        case "call":
            callPanel.update(m)
            if m["state"] as? String == "idle", !phone.calls.isEmpty { phone.refresh(.calls) }
        case "guard_cmd":
            guardian.handleCommand(m["c"] as? String ?? "", eventId: m["e"] as? String)
        case "call_ctl_state":
            callPanel.setControls(m)
            if let note = m["note"] as? String, !note.isEmpty { notifier.info("Call controls", note) }
        case "mac_cmd": mac.handle(m)
        case "input": input.handle(m)
        case "otp":
            if let code = m["code"] as? String {
                clipboard.setFromPhone(code)
                notifier.info("Code \(code) copied", "From \(m["from"] as? String ?? "SMS") — just paste it")
            }
        case "alert":
            notifier.info(m["title"] as? String ?? "PhoneBridge", m["body"] as? String ?? "")
        case "sms_threads", "sms_thread", "sms_new", "photos", "albums", "photos_deleted", "call_log", "contacts",
             "device", "torch_state", "biggest", "loc", "autoreply":
            phone.handle(m)
        case "clip": if let t = m["text"] as? String { clipboard.setFromPhone(t) }
        case "clip_image":
            if let b64 = m["png"] as? String, let png = Data(base64Encoded: b64) {
                clipboard.setImageFromPhone(png)
                notifier.info("Image copied from phone", "Paste it anywhere")
            }
        case "shared_text":
            if let t = m["text"] as? String {
                clipboard.setFromPhone(t)
                notifier.info("Copied from phone", t)
            }
        case "media":
            nowPlaying = (m["title"] as? String).map { title in
                NowPlaying(title: title, artist: m["artist"] as? String ?? "", app: m["app"] as? String ?? "",
                           playing: m["playing"] as? Bool ?? false,
                           art: (m["art"] as? String).flatMap { Data(base64Encoded: $0) }.flatMap { NSImage(data: $0) })
            }
        case "mic_state": mic.handleState(m["on"] as? Bool ?? false)
        case "mic_audio": if let pcm = m["pcm"] as? String { mic.handleAudio(pcm) }
        case "battery":
            battery = m["level"] as? Int
            charging = m["charging"] as? Bool ?? false
            if let level = battery { batteryWatch.update(level: level, charging: charging) }
        case "info":
            if let n = m["name"] as? String { phoneName = n }
            if let addrs = m["addrs"] as? [String] { link.setRemoteHosts(addrs) }
        case "ring_state":
            phoneRinging = m["ringing"] as? Bool ?? false
        case "apps":
            apps = (m["apps"] as? [[String: Any]] ?? []).compactMap { a in
                guard let pkg = a["pkg"] as? String, let name = a["name"] as? String else { return nil }
                let icon = (a["icon"] as? String).flatMap { Data(base64Encoded: $0) }.flatMap { NSImage(data: $0) }
                return PhoneApp(id: pkg, name: name, icon: icon)
            }
        case "sms_result":
            let ok = m["ok"] as? Bool ?? false
            notifier.info(ok ? "SMS sent" : "SMS failed", m["to"] as? String ?? "")
        case "file_start", "file_chunk", "file_end": transfers.handle(m)
        default: break
        }
    }

    /// A file finished arriving from the phone; what happens depends on why it was sent.
    private func received(_ url: URL, kind: String?) {
        switch kind {
        case let k? where k.hasPrefix("photo:"):
            if let id = Int64(k.dropFirst(6)) { phone.photoReceived(id: id, url: url) }
        case "screenshot":
            if let data = try? Data(contentsOf: url) { clipboard.setImageFromPhone(data) }
            notifier.info("Screenshot from phone", "Copied — paste it anywhere. Saved in Downloads.", revealing: url)
        case "scan":
            let pasted = Paster.pasteImage(url, clipboard: clipboard)
            notifier.info("Scan received", pasted ? "Pasted at your cursor (also in Downloads)" : "Copied — press ⌘V to paste",
                          revealing: url)
        default:
            notifier.info("Received from phone", url.lastPathComponent, revealing: url)
        }
    }

    private func askPairing(_ code: String, _ name: String, _ decide: @escaping (Bool) -> Void) {
        NSApp.activate(ignoringOtherApps: true)
        let alert = NSAlert()
        alert.messageText = "Pair with \(name)?"
        alert.informativeText = "Check that your phone shows the same code:\n\n\(code)"
        alert.addButton(withTitle: "Pair")
        alert.addButton(withTitle: "Cancel")
        decide(alert.runModal() == .alertFirstButtonReturn)
    }

    func pickFile() {
        NSApp.activate(ignoringOtherApps: true)
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        guard panel.runModal() == .OK else { return }
        sendFiles(panel.urls)
    }

    func sendFiles(_ urls: [URL], kind: String? = nil, quiet: Bool = false) {
        guard connected else {
            if !quiet { notifier.info("Phone not connected", "Open PhoneBridge on your phone first.") }
            return
        }
        for url in urls {
            transfers.send(url, via: link, kind: kind) { ok in
                Task { @MainActor in
                    if !quiet || !ok { self.notifier.info(ok ? "Sent to phone" : "Sending failed", url.lastPathComponent) }
                }
            }
        }
    }

    func composeSMS() {
        NSApp.activate(ignoringOtherApps: true)
        let alert = NSAlert()
        alert.messageText = "Send SMS from your phone"
        let to = NSTextField(frame: NSRect(x: 0, y: 64, width: 280, height: 24))
        to.placeholderString = "Phone number"
        let body = NSTextField(frame: NSRect(x: 0, y: 0, width: 280, height: 56))
        body.placeholderString = "Message"
        let box = NSView(frame: NSRect(x: 0, y: 0, width: 280, height: 88))
        box.addSubview(to)
        box.addSubview(body)
        alert.accessoryView = box
        alert.addButton(withTitle: "Send")
        alert.addButton(withTitle: "Cancel")
        alert.window.initialFirstResponder = to
        guard alert.runModal() == .alertFirstButtonReturn else { return }
        let number = to.stringValue.trimmingCharacters(in: .whitespaces)
        guard !number.isEmpty, !body.stringValue.isEmpty else { return }
        link.send(["t": "sms_send", "to": number, "text": body.stringValue])
    }

    func forget() {
        link.forget()
    }

    /// Reconnect button: search for the phone again right now instead of waiting for the next retry.
    func reconnect() {
        reconnecting = true
        link.reconnect()
        DispatchQueue.main.asyncAfter(deadline: .now() + 4) { self.reconnecting = false }
    }
}
