import AppKit
import Foundation

struct PhoneApp: Identifiable {
    let id: String // package name
    let name: String
    let icon: NSImage?
}

/// Phone apps in their own Mac windows (each on a hidden screen on the phone, so the phone stays free),
/// via scrcpy over Wireless debugging. Debugging is only on during a session: the phone switches
/// Developer options on when you open an app here and off again when the last window closes.
@MainActor
final class AppStreamer: ObservableObject {
    enum Status: Equatable {
        case missingTools
        case notPaired
        case idle            // paired; debugging is off until you open an app
        case connecting
        case ready(String)   // adb serial; a session is running
    }

    @Published private(set) var status: Status = .notPaired
    @Published var pairMessage: String?
    @Published var pairing = false

    nonisolated static let adb = "/opt/homebrew/bin/adb"
    nonisolated static let scrcpy = "/opt/homebrew/bin/scrcpy"
    nonisolated private static let phonePackage = "app.phonebridge"

    /// The last session window closed — the phone should switch debugging off.
    var onIdle: () -> Void = {}
    /// One-time setup finished (permission granted) — the phone can switch Developer options off.
    var onSetupDone: () -> Void = {}

    private var clipboardDaemon: Process?
    private var windows: [String: Process] = [:]
    private var connectAttempt = 0

    var serial: String? {
        if case .ready(let s) = status { return s }
        return nil
    }

    var isPaired: Bool { UserDefaults.standard.bool(forKey: "adbPaired") }

    func start() {
        guard FileManager.default.isExecutableFile(atPath: Self.adb),
              FileManager.default.isExecutableFile(atPath: Self.scrcpy) else {
            status = .missingTools
            return
        }
        status = isPaired ? .idle : .notPaired
    }

    // MARK: Connection

    /// The phone has just switched Wireless debugging on: find it (mDNS) and connect, retrying for a while.
    func connect(timeout: TimeInterval = 20, completion: @escaping (String?) -> Void) {
        if let serial { return completion(serial) }
        guard status != .missingTools else { return completion(nil) }
        status = .connecting
        connectAttempt += 1
        let attempt = connectAttempt
        let deadline = Date().addingTimeInterval(timeout)
        func poll() {
            Task.detached {
                let serial = Self.findOrConnectDevice()
                await MainActor.run {
                    guard attempt == self.connectAttempt else { return }
                    if let serial {
                        self.status = .ready(serial)
                        self.startClipboardDaemon(serial)
                        completion(serial)
                    } else if Date() < deadline {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { poll() }
                    } else {
                        self.status = self.isPaired ? .idle : .notPaired
                        completion(nil)
                    }
                }
            }
        }
        poll()
    }

    /// Already-connected wireless device, or connect to one advertised over mDNS.
    nonisolated private static func findOrConnectDevice() -> String? {
        if let s = connectedDevice() { return s }
        let services = run(adb, ["mdns", "services"]).output
        for line in services.split(separator: "\n") where line.contains("_adb-tls-connect._tcp") {
            guard let hostPort = line.split(whereSeparator: { $0 == "\t" || $0 == " " }).last else { continue }
            _ = run(adb, ["connect", String(hostPort)], timeout: 8)
        }
        return connectedDevice()
    }

    nonisolated private static func connectedDevice() -> String? {
        let out = run(adb, ["devices"]).output
        for line in out.split(separator: "\n").dropFirst() {
            let parts = line.split(separator: "\t")
            if parts.count == 2, parts[1] == "device" { return String(parts[0]) }
        }
        return nil
    }

    /// One-time setup: pair, then let PhoneBridge on the phone switch Developer options / Wireless debugging itself.
    func pair(hostPort: String, code: String) {
        let hp = hostPort.trimmingCharacters(in: .whitespaces)
        let cd = code.trimmingCharacters(in: .whitespaces)
        guard !hp.isEmpty, !cd.isEmpty else {
            pairMessage = "Enter both the IP address & port and the pairing code."
            return
        }
        pairing = true
        pairMessage = "Pairing…"
        Task.detached {
            let result = Self.run(Self.adb, ["pair", hp, cd], timeout: 20)
            await MainActor.run {
                guard result.output.contains("Successfully paired") else {
                    self.pairing = false
                    let detail = result.output.trimmingCharacters(in: .whitespacesAndNewlines)
                    self.pairMessage = "Pairing failed. Check the code hasn't expired and try again.\n\(detail)"
                    return
                }
                UserDefaults.standard.set(true, forKey: "adbPaired")
                self.pairMessage = "Paired! Finishing setup…"
                self.connect(timeout: 20) { serial in
                    guard let serial else {
                        self.pairing = false
                        self.pairMessage = "Paired, but couldn't connect. Keep Wireless debugging on and press Pair again with a new code."
                        return
                    }
                    Task.detached {
                        let grant = Self.run(Self.adb, ["-s", serial, "shell", "pm", "grant", Self.phonePackage,
                                                        "android.permission.WRITE_SECURE_SETTINGS"])
                        await MainActor.run {
                            self.pairing = false
                            if grant.status == 0 {
                                self.pairMessage = "All set. PhoneBridge will switch Developer options on only while you use a phone app here — and has switched them off now."
                                self.endSession()
                                self.onSetupDone()
                            } else {
                                self.pairMessage = "Paired, but the phone refused the permission:\n\(grant.output)"
                            }
                        }
                    }
                }
            }
        }
    }

    // MARK: Windows

    func open(_ app: PhoneApp) {
        guard let serial else { return }
        if let p = windows[app.id], p.isRunning {
            NSRunningApplication(processIdentifier: p.processIdentifier)?.activate()
            return
        }
        windows[app.id] = launch(["-s", serial, "--new-display=1080x2340/440", "--start-app=\(app.id)",
                                  "--window-title=\(app.name)", "--window-height=860"]) { [weak self] in
            Task { @MainActor in self?.windowClosed() }
        }
    }

    func mirror(title: String) {
        guard let serial else { return }
        if let p = windows["__mirror"], p.isRunning {
            NSRunningApplication(processIdentifier: p.processIdentifier)?.activate()
            return
        }
        windows["__mirror"] = launch(["-s", serial, "--window-title=\(title)", "--window-height=860"]) { [weak self] in
            Task { @MainActor in self?.windowClosed() }
        }
    }

    private func windowClosed() {
        windows = windows.filter { $0.value.isRunning }
        if windows.isEmpty, serial != nil { endSession() ; onIdle() }
    }

    /// Ends the session on the Mac side: no more adb connection or clipboard helper.
    private func endSession() {
        stopClipboardDaemon()
        Task.detached { _ = Self.run(Self.adb, ["disconnect"]) }
        status = isPaired ? .idle : .notPaired
    }

    // scrcpy with no video/audio/window still runs its control channel, which syncs the phone
    // clipboard to the Mac whenever it changes — automatic phone → Mac clipboard during a session.
    private func startClipboardDaemon(_ serial: String) {
        guard clipboardDaemon?.isRunning != true else { return }
        clipboardDaemon = launch(["-s", serial, "--no-video", "--no-audio", "--no-window"]) { [weak self] in
            Task { @MainActor in self?.clipboardDaemon = nil }
        }
    }

    private func stopClipboardDaemon() {
        clipboardDaemon?.terminate()
        clipboardDaemon = nil
    }

    func stopAll() {
        stopClipboardDaemon()
        windows.values.forEach { $0.terminate() }
    }

    private func launch(_ args: [String], onExit: (() -> Void)? = nil) -> Process? {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: Self.scrcpy)
        p.arguments = args
        var env = ProcessInfo.processInfo.environment
        env["PATH"] = "/opt/homebrew/bin:/usr/bin:/bin"
        env["ADB"] = Self.adb
        p.environment = env
        p.standardOutput = FileHandle.nullDevice
        p.standardError = FileHandle.nullDevice
        if let onExit { p.terminationHandler = { _ in onExit() } }
        do {
            try p.run()
            return p
        } catch {
            NSLog("PhoneBridge: scrcpy failed to start: \(error)")
            return nil
        }
    }

    // MARK: Process helper

    nonisolated static func run(_ path: String, _ args: [String], timeout: TimeInterval = 10) -> (status: Int32, output: String) {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: path)
        p.arguments = args
        let pipe = Pipe()
        p.standardOutput = pipe
        p.standardError = pipe
        do { try p.run() } catch { return (-1, "\(error)") }
        let deadline = Date().addingTimeInterval(timeout)
        while p.isRunning && Date() < deadline { usleep(50_000) }
        if p.isRunning {
            p.terminate()
            p.waitUntilExit()
        }
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        return (p.terminationStatus, String(data: data, encoding: .utf8) ?? "")
    }
}
