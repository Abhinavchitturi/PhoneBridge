import AppKit

/// What's playing on the Mac → the phone ("mac_media"), and play/pause/next/prev from the phone.
/// MediaRemote only answers Apple-signed processes now, so the watcher (Helpers/NowPlaying.m) runs inside /usr/bin/perl.
final class MacMedia: @unchecked Sendable {
    var send: ([String: Any]) -> Void = { _ in }
    private let queue = DispatchQueue(label: "phonebridge.macmedia")
    private var process: Process?
    private var input: FileHandle?
    private var buffer = Data()
    private var last: [String: Any] = [:]   // latest state, art included, for resending on reconnect
    private var restarts = 0

    private static let loader = """
        use DynaLoader;
        my $lib = DynaLoader::dl_load_file($ARGV[0], 0) or die DynaLoader::dl_error();
        my $sym = DynaLoader::dl_find_symbol($lib, "pb_nowplaying_run") or die "symbol missing";
        DynaLoader::dl_install_xsub("main::run", $sym);
        run();
        """

    func start() { queue.async { self.launch() } }

    /// True when the command went to the Mac's now-playing app (otherwise fall back to media keys).
    func command(_ cmd: String) -> Bool {
        queue.sync {
            guard let input, process?.isRunning == true else {
                NSLog("PhoneBridge: media \(cmd): now-playing helper not running, using media keys")
                return false
            }
            NSLog("PhoneBridge: media \(cmd) → now-playing helper")
            input.write(Data((cmd + "\n").utf8))
            return true
        }
    }

    private var pausedForCall = false

    /// A call started: pause whatever the Mac is playing (and remember, so it can resume afterwards).
    func pauseForCall() {
        guard queue.sync(execute: { last["playing"] as? Bool == true }) else { return }
        if command("pause") { queue.sync { pausedForCall = true } }
    }

    /// The call ended: resume, but only what we paused and only if it's still paused.
    func resumeAfterCall() {
        let resume = queue.sync {
            defer { pausedForCall = false }
            return pausedForCall && last["playing"] as? Bool == false
        }
        if resume { _ = command("play") }
    }

    /// The app behind the current now-playing track ("Spotify", "Brave Browser", …).
    var currentApp: String { queue.sync { last["app"] as? String ?? "" } }

    var currentTitle: String { queue.sync { last["title"] as? String ?? "" } }

    func resend() {
        queue.async { self.send(self.message(self.last)) }
    }

    private func launch() {
        guard let dylib = Bundle.main.url(forResource: "NowPlaying", withExtension: "dylib") else {
            NSLog("PhoneBridge: NowPlaying.dylib missing from the app bundle")
            return
        }
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/perl")
        p.arguments = ["-e", Self.loader, dylib.path]
        let out = Pipe(), inPipe = Pipe(), err = Pipe()
        p.standardOutput = out
        p.standardInput = inPipe
        p.standardError = err
        err.fileHandleForReading.readabilityHandler = { h in
            if let s = String(data: h.availableData, encoding: .utf8), !s.isEmpty { NSLog("PhoneBridge: now-playing helper: \(s)") }
        }
        out.fileHandleForReading.readabilityHandler = { [weak self] h in
            let data = h.availableData
            self?.queue.async { self?.received(data) }
        }
        p.terminationHandler = { [weak self] p in
            NSLog("PhoneBridge: now-playing helper exited (\(p.terminationStatus))")
            out.fileHandleForReading.readabilityHandler = nil
            err.fileHandleForReading.readabilityHandler = nil
            self?.queue.async { self?.restart() }
        }
        do { try p.run() } catch {
            NSLog("PhoneBridge: now-playing helper failed to start: \(error)")
            return
        }
        NSLog("PhoneBridge: now-playing helper started (pid \(p.processIdentifier))")
        process = p
        input = inPipe.fileHandleForWriting
        buffer = Data()
    }

    private func restart() {
        process = nil
        input = nil
        restarts += 1
        guard restarts <= 5 else { return } // keeps crashing (e.g. a future macOS closed this door): give up quietly
        queue.asyncAfter(deadline: .now() + Double(restarts * 3)) { self.launch() }
    }

    private func received(_ data: Data) {
        buffer.append(data)
        while let nl = buffer.firstIndex(of: 0x0A) {
            let line = buffer[buffer.startIndex..<nl]
            buffer.removeSubrange(buffer.startIndex...nl)
            guard var m = (try? JSONSerialization.jsonObject(with: line)) as? [String: Any] else { continue }
            restarts = 0
            if m["art"] == nil, m["title"] as? String == last["title"] as? String { m["art"] = last["art"] }
            if let pid = m.removeValue(forKey: "pid") as? Int { m["app"] = Self.appName(pid) }
            let isNew = last["title"] as? String != m["title"] as? String || last["art"] as? String != m["art"] as? String
            last = m
            // Art only when the track (or its artwork) changes — it's the big part.
            var msg = message(m)
            if !isNew { msg.removeValue(forKey: "art") }
            NSLog("PhoneBridge: Mac now playing → phone: \(m["title"] as? String ?? "nothing") playing=\(m["playing"] as? Bool ?? false)")
            send(msg)
        }
    }

    private func message(_ m: [String: Any]) -> [String: Any] {
        var msg = m
        msg["t"] = "mac_media"
        return msg
    }

    private static func appName(_ pid: Int) -> String {
        guard pid > 0 else { return "Mac" }
        if let app = NSRunningApplication(processIdentifier: pid_t(pid)), let name = app.localizedName {
            return name.replacingOccurrences(of: " Helper", with: "")
        }
        return "Mac"
    }
}
