import AppKit

/// Keeps PhoneBridge up to date from the GitHub releases page.
/// Checks when it opens, when the phone connects, and every 4 hours; when a newer release is out it asks once
/// ("Update now?"), then downloads PhoneBridge.dmg, swaps the app in place and reopens it.
@MainActor
final class Updater: ObservableObject {
    static let shared = Updater()
    nonisolated static let repo = "Abhinavchitturi/PhoneBridge"

    struct Release: Equatable {
        let version: String
        let dmg: URL
        let notes: String
    }

    @Published private(set) var available: Release?
    @Published private(set) var busy = false
    var notify: (String, String) -> Void = { _, _ in }
    private var timer: Timer?
    private var lastCheck = Date.distantPast
    /// The version we already asked about this launch, so a "Later" isn't repeated every check.
    private var askedFor: String?

    var current: String { Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0" }

    func start() {
        DispatchQueue.main.asyncAfter(deadline: .now() + 8) { [weak self] in self?.check(manual: false) }
        timer = Timer.scheduledTimer(withTimeInterval: 4 * 3600, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.check(manual: false) }
        }
    }

    /// Asks GitHub for the latest release. `manual` (from the menu) also says when you're already up to date.
    func check(manual: Bool) {
        guard !busy else { return }
        // Automatic checks at most once a minute (GitHub allows 60 an hour).
        guard manual || Date().timeIntervalSince(lastCheck) > 60 else { return }
        lastCheck = Date()
        Task {
            let release = await Self.fetchLatest()
            guard let release else {
                if manual { notify("Couldn't check for updates", "Check your internet connection and try again.") }
                return
            }
            if Self.isNewer(release.version, than: current) {
                available = release
                if manual || askedFor != release.version {
                    askedFor = release.version
                    prompt(release)
                }
            } else {
                available = nil
                if manual { notify("PhoneBridge is up to date", "You have the latest version, \(current).") }
            }
        }
    }

    nonisolated static func fetchLatest() async -> Release? {
        guard let url = URL(string: "https://api.github.com/repos/\(repo)/releases/latest") else { return nil }
        var req = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 20)
        req.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        req.setValue("PhoneBridge-Updater", forHTTPHeaderField: "User-Agent")
        guard let (data, resp) = try? await URLSession.shared.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let tag = json["tag_name"] as? String,
              let assets = json["assets"] as? [[String: Any]] else { return nil }
        let dmg = assets.first { ($0["name"] as? String) == "PhoneBridge.dmg" }
            ?? assets.first { ($0["name"] as? String)?.hasSuffix(".dmg") == true }
        guard let link = dmg?["browser_download_url"] as? String, let dmgURL = URL(string: link) else { return nil }
        let version = tag.hasPrefix("v") ? String(tag.dropFirst()) : tag
        return Release(version: version, dmg: dmgURL, notes: json["body"] as? String ?? "")
    }

    /// "0.19" > "0.18", "0.18.1" > "0.18", "1.0" > "0.99".
    nonisolated static func isNewer(_ a: String, than b: String) -> Bool {
        let x = a.split(separator: ".").map { Int($0) ?? 0 }
        let y = b.split(separator: ".").map { Int($0) ?? 0 }
        for i in 0..<max(x.count, y.count) {
            let l = i < x.count ? x[i] : 0, r = i < y.count ? y[i] : 0
            if l != r { return l > r }
        }
        return false
    }

    private func prompt(_ r: Release) {
        let alert = NSAlert()
        alert.messageText = "A new version of PhoneBridge is available"
        var info = "PhoneBridge \(r.version) is out (you have \(current)). Update now? PhoneBridge closes and reopens by itself in a few seconds."
        let notes = Self.shortNotes(r.notes)
        if !notes.isEmpty { info += "\n\nWhat's new:\n\(notes)" }
        alert.informativeText = info
        alert.addButton(withTitle: "Update Now")
        alert.addButton(withTitle: "Later")
        NSApp.activate(ignoringOtherApps: true)
        if alert.runModal() == .alertFirstButtonReturn { install() }
    }

    /// The first few lines of the release notes, without Markdown clutter.
    nonisolated static func shortNotes(_ body: String) -> String {
        body.split(separator: "\n")
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty && !$0.hasPrefix("#") && !$0.contains("Full Changelog") && !$0.hasPrefix("<!--") }
            .prefix(6)
            .map { $0.replacingOccurrences(of: "**", with: "") }
            .joined(separator: "\n")
    }

    // MARK: Install

    func install() {
        guard let r = available, !busy else { return }
        busy = true
        notify("Updating PhoneBridge", "Downloading version \(r.version)…")
        let dest = Bundle.main.bundleURL
        Task.detached {
            do {
                let staged = try await Self.download(r)
                try await MainActor.run { try Self.relaunch(replacing: dest, with: staged) }
            } catch {
                await MainActor.run {
                    self.busy = false
                    self.notify("Update failed", "\(error.localizedDescription) Opening the download instead.")
                    NSWorkspace.shared.open(URL(string: "https://github.com/\(Self.repo)/releases/latest")!)
                }
            }
        }
    }

    struct UpdateError: LocalizedError {
        let errorDescription: String?
        init(_ s: String) { errorDescription = s }
    }

    /// Downloads the disk image and copies the new app out of it into a staging folder; returns the staged app.
    nonisolated static func download(_ r: Release) async throws -> URL {
        let fm = FileManager.default
        let work = fm.temporaryDirectory.appendingPathComponent("PhoneBridgeUpdate-\(UUID().uuidString)")
        try fm.createDirectory(at: work, withIntermediateDirectories: true)
        let (tmp, resp) = try await URLSession.shared.download(from: r.dmg)
        guard (resp as? HTTPURLResponse)?.statusCode == 200 else { throw UpdateError("The download didn't finish.") }
        let dmg = work.appendingPathComponent("PhoneBridge.dmg")
        try fm.moveItem(at: tmp, to: dmg)

        let mount = work.appendingPathComponent("mnt")
        try fm.createDirectory(at: mount, withIntermediateDirectories: true)
        let attach = AppStreamer.run("/usr/bin/hdiutil", ["attach", dmg.path, "-nobrowse", "-readonly", "-noverify", "-noautoopen", "-mountpoint", mount.path], timeout: 120)
        guard attach.status == 0 else { throw UpdateError("Couldn't open the downloaded disk image.") }
        defer { _ = AppStreamer.run("/usr/bin/hdiutil", ["detach", mount.path, "-force"], timeout: 60) }

        let source = mount.appendingPathComponent("PhoneBridge.app")
        let staged = work.appendingPathComponent("PhoneBridge.app")
        let copy = AppStreamer.run("/usr/bin/ditto", [source.path, staged.path], timeout: 120)
        guard copy.status == 0 else { throw UpdateError("Couldn't copy the new app.") }

        let info = NSDictionary(contentsOf: staged.appendingPathComponent("Contents/Info.plist"))
        guard info?["CFBundleIdentifier"] as? String == Bundle.main.bundleIdentifier,
              info?["CFBundleShortVersionString"] as? String == r.version else {
            throw UpdateError("The download wasn't the expected PhoneBridge \(r.version).")
        }
        guard AppStreamer.run("/usr/bin/codesign", ["--verify", "--deep", staged.path], timeout: 60).status == 0 else {
            throw UpdateError("The new app's signature didn't check out.")
        }
        _ = AppStreamer.run("/usr/bin/xattr", ["-dr", "com.apple.quarantine", staged.path], timeout: 30)
        return staged
    }

    /// Hands off to a tiny shell script that waits for this app to quit, swaps the bundles and opens the new one.
    static func relaunch(replacing dest: URL, with staged: URL) throws {
        guard FileManager.default.isWritableFile(atPath: dest.deletingLastPathComponent().path) else {
            throw UpdateError("PhoneBridge can't write to \(dest.deletingLastPathComponent().path).")
        }
        let work = staged.deletingLastPathComponent()
        guard dest.lastPathComponent.hasSuffix(".app"), staged.lastPathComponent == "PhoneBridge.app",
              work.lastPathComponent.hasPrefix("PhoneBridgeUpdate-") else {
            throw UpdateError("Unexpected install paths.")
        }
        // $1 our pid · $2 the installed app · $3 the new app · $4 the staging folder (only ever PhoneBridgeUpdate-…)
        let script = """
        case "$2" in *.app) ;; *) exit 1 ;; esac
        case "$4" in */PhoneBridgeUpdate-*) ;; *) exit 1 ;; esac
        while /bin/kill -0 "$1" 2>/dev/null; do /bin/sleep 0.2; done
        /bin/rm -rf "$2.old"
        if /bin/mv "$2" "$2.old" && /bin/mv "$3" "$2"; then
          /bin/rm -rf "$2.old"
        else
          [ -d "$2.old" ] && [ ! -d "$2" ] && /bin/mv "$2.old" "$2"
        fi
        /usr/bin/xattr -dr com.apple.quarantine "$2" 2>/dev/null
        /usr/bin/open "$2"
        /bin/rm -rf "$4"
        """
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/bin/sh")
        p.arguments = ["-c", script, "pb-update", String(ProcessInfo.processInfo.processIdentifier),
                       dest.path, staged.path, work.path]
        try p.run()
        NSApp.terminate(nil)
    }
}
