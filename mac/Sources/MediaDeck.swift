import AppKit

/// Detailed controls for whatever the Mac is playing, for the phone's "player" screen: Spotify (through its AppleScript
/// interface) and YouTube in Brave / Chrome / Safari (through JavaScript run in the video's tab). Only runs while the
/// phone has that screen open ("deck_on", kept alive by the phone), and sends "deck_state" about once a second.
final class MediaDeck: @unchecked Sendable {
    var send: ([String: Any]) -> Void = { _ in }
    /// The app name from the now-playing watcher ("Spotify", "Brave Browser", …).
    var currentApp: () -> String = { "" }
    /// The track/video title from the now-playing watcher, to find the right browser tab.
    var currentTitle: () -> String = { "" }

    private let queue = DispatchQueue(label: "phonebridge.mediadeck")
    private var timer: DispatchSourceTimer?
    private var lastPing = Date.distantPast
    private var busy = false
    /// The YouTube tab being controlled ("windowId|tabIndex"), chosen from the last state (the one that's playing).
    private var target = ""

    // MARK: Phone → Mac

    func on() {
        queue.async {
            self.lastPing = Date()
            if self.timer == nil {
                let t = DispatchSource.makeTimerSource(queue: self.queue)
                t.schedule(deadline: .now(), repeating: 1.0)
                t.setEventHandler { [weak self] in self?.tick() }
                t.resume()
                self.timer = t
            }
        }
    }

    func off() {
        queue.async {
            self.timer?.cancel()
            self.timer = nil
            self.target = ""
        }
    }

    func perform(_ action: String, value: Any?) {
        queue.async {
            let kind = self.kind()
            let state: [String: Any]
            switch kind {
            case .spotify: state = self.spotify(action: action, value: value)
            case .music: state = self.music(action: action, value: value)
            case .browser(let app): state = self.youtube(app: app, action: action, value: value)
            case .none: state = ["kind": "none"]
            }
            self.emit(state)
        }
    }

    // MARK: Polling

    private func tick() {
        guard Date().timeIntervalSince(lastPing) < 25 else { off(); return } // phone left the screen / went away
        guard !busy else { return }
        busy = true
        defer { busy = false }
        switch kind() {
        case .spotify: emit(spotify(action: nil, value: nil))
        case .music: emit(music(action: nil, value: nil))
        case .browser(let app): emit(youtube(app: app, action: nil, value: nil))
        case .none: emit(["kind": "none"])
        }
    }

    private func emit(_ state: [String: Any]) {
        var m = state
        m["t"] = "deck_state"
        send(m)
    }

    private enum Kind { case spotify, music, browser(String), none }

    private func kind() -> Kind {
        let app = currentApp()
        if app.contains("Spotify") { return .spotify }
        if app == "Music" { return .music }
        if app.contains("Brave") { return .browser("Brave Browser") }
        if app.contains("Chrome") { return .browser("Google Chrome") }
        if app.contains("Safari") { return .browser("Safari") }
        return .none
    }

    // MARK: AppleScript

    /// Runs osascript with a script and arguments; nil if it failed (stderr in `error`).
    private func osascript(_ script: String, _ args: [String] = [], error stderr: inout String) -> String? {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/osascript")
        p.arguments = ["-e", script] + args
        let out = Pipe(), err = Pipe()
        p.standardOutput = out
        p.standardError = err
        do { try p.run() } catch { stderr = "\(error)"; return nil }
        let killer = DispatchWorkItem { if p.isRunning { p.terminate() } }
        DispatchQueue.global().asyncAfter(deadline: .now() + 6, execute: killer)
        let o = out.fileHandleForReading.readDataToEndOfFile()
        let e = err.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()
        killer.cancel()
        stderr = String(data: e, encoding: .utf8) ?? ""
        guard p.terminationStatus == 0 else { return nil }
        return String(data: o, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func appleEventError(_ e: String) -> String {
        if e.contains("-1743") || e.contains("not allowed") { return "perm" }
        return "failed"
    }

    // MARK: Spotify

    private func spotify(action: String?, value: Any?) -> [String: Any] {
        guard NSRunningApplication.runningApplications(withBundleIdentifier: "com.spotify.client").first != nil else {
            return ["kind": "none"]
        }
        var set = ""
        switch action {
        case "toggle": set = "playpause"
        case "seek": if let s = (value as? NSNumber)?.intValue { set = "set player position to \(max(0, s))" }
        case "shuffle": if let b = value as? Bool { set = "set shuffling to \(b)" }
        case "repeat": if let b = value as? Bool { set = "set repeating to \(b)" }
        default: break
        }
        let script = """
            tell application "Spotify"
                \(set)
                if player state is stopped then return "stopped"
                return (player state as text) & "|" & (player position as integer) & "|" & (duration of current track) & "|" & shuffling & "|" & repeating
            end tell
            """
        var err = ""
        guard let out = osascript(script, error: &err) else { return ["kind": "spotify", "error": appleEventError(err)] }
        let f = out.split(separator: "|").map(String.init)
        guard f.count == 5 else { return ["kind": "spotify", "playing": false, "pos": 0, "dur": 0] }
        return ["kind": "spotify", "playing": f[0] == "playing", "pos": Double(f[1]) ?? 0, "dur": (Double(f[2]) ?? 0) / 1000,
                "shuffle": f[3] == "true", "repeat": f[4] == "true"]
    }

    // MARK: Apple Music

    private func music(action: String?, value: Any?) -> [String: Any] {
        guard NSRunningApplication.runningApplications(withBundleIdentifier: "com.apple.Music").first != nil else {
            return ["kind": "none"]
        }
        var set = ""
        switch action {
        case "toggle": set = "playpause"
        case "seek": if let s = (value as? NSNumber)?.intValue { set = "set player position to \(max(0, s))" }
        case "shuffle": if let b = value as? Bool { set = "set shuffle enabled to \(b)" }
        case "repeat": if let r = value as? String, ["off", "one", "all"].contains(r) { set = "set song repeat to \(r)" }
        default: break
        }
        let script = """
            tell application "Music"
                \(set)
                if player state is stopped then return "stopped"
                return (player state as text) & "|" & (player position as integer) & "|" & (duration of current track as integer) & "|" & shuffle enabled & "|" & song repeat
            end tell
            """
        var err = ""
        guard let out = osascript(script, error: &err) else { return ["kind": "music", "error": appleEventError(err)] }
        let f = out.split(separator: "|").map(String.init)
        guard f.count == 5 else { return ["kind": "music", "playing": false, "pos": 0, "dur": 0] }
        return ["kind": "music", "playing": f[0] == "playing", "pos": Double(f[1]) ?? 0, "dur": Double(f[2]) ?? 0,
                "shuffle": f[3] == "true", "repeat": f[4]]
    }

    // MARK: Web video (YouTube and anything with a plain <video>)

    /// Runs in the video's tab. Does the action (if any) and returns the player's state as JSON.
    private static let js = """
        (function(a, v) {
          var vs = [].slice.call(document.querySelectorAll('video'));
          if (!vs.length) return '';
          var playing = vs.filter(function(x) { return !x.paused && x.readyState > 2; });
          var vd = (playing.length ? playing : vs).sort(function(x, y) { return y.clientWidth * y.clientHeight - x.clientWidth * x.clientHeight; })[0];
          var p = document.getElementById('movie_player');
          var yt = !!p && location.hostname.indexOf('youtube.com') >= 0 && location.pathname.indexOf('/watch') == 0;
          var sb = yt ? document.querySelector('.ytp-subtitles-button') : null;
          if (a == 'toggle') { vd.paused ? vd.play() : vd.pause(); }
          else if (a == 'seek') { vd.currentTime = v; }
          else if (a == 'skip') { vd.currentTime = Math.max(0, Math.min(vd.duration || 1e9, vd.currentTime + v)); }
          else if (a == 'speed') { vd.playbackRate = v; }
          else if (a == 'subs' && yt) { if (p.toggleSubtitles) p.toggleSubtitles(); else if (sb) sb.click(); }
          else if (a == 'quality' && yt) { p.setPlaybackQualityRange(v, v); p.setPlaybackQuality(v); }
          var hasSubs = !!sb && getComputedStyle(sb).display != 'none';
          var subsOn = hasSubs && sb.getAttribute('aria-pressed') == 'true';
          var url = yt ? location.origin + '/watch?v=' + new URLSearchParams(location.search).get('v') : location.href;
          return JSON.stringify({site: yt ? 'youtube' : 'web', playing: !vd.paused, pos: vd.currentTime,
            dur: isFinite(vd.duration) ? vd.duration : 0, speed: vd.playbackRate, subs: subsOn, hasSubs: hasSubs,
            quality: yt && p.getPlaybackQuality ? p.getPlaybackQuality() : '',
            qualities: yt && p.getAvailableQualityLevels ? p.getAvailableQualityLevels() : [],
            title: document.title.replace(/ - YouTube$/, ''), url: url});
        })
        """

    private func jsCall(_ action: String, _ value: Any?) -> String {
        var v = "0"
        if let s = value as? String { v = "'" + s.filter { $0.isLetter || $0.isNumber } + "'" }
        else if let n = value as? NSNumber { v = "\(n.doubleValue)" }
        return "\(Self.js)('\(action)', \(v))"
    }

    private func youtube(app: String, action: String?, value: Any?) -> [String: Any] {
        let tabs = "windows", count = "count of tabs of w", getTab = "tab i of w"
        let titleProp = app == "Safari" ? "name" : "title"
        let run = app == "Safari" ? "do JavaScript (js) in t" : "execute t javascript (js)"
        // The tab named in `target` gets the action; every YouTube tab reports its state.
        let script = """
            on run argv
                set stateJS to item 1 of argv
                set actionJS to item 2 of argv
                set wanted to item 3 of argv
                set wantedTitle to item 4 of argv
                set out to ""
                tell application "\(app)"
                    repeat with w in \(tabs)
                        set wi to id of w
                        repeat with i from 1 to (\(count))
                            set t to \(getTab)
                            set u to URL of t
                            set nm to \(titleProp) of t
                            if u contains "youtube.com/watch" or (wantedTitle is not "" and nm contains wantedTitle) or wanted is (wi as text) & "|" & i then
                                set js to stateJS
                                if wanted is (wi as text) & "|" & i then set js to actionJS
                                try
                                    set r to \(run)
                                    set out to out & wi & "|" & i & "|" & r & linefeed
                                on error msg
                                    set out to out & "ERR|" & msg & linefeed
                                end try
                            end if
                        end repeat
                    end repeat
                end tell
                return out
            end run
            """
        let actionJS = action.map { jsCall($0, value) } ?? jsCall("state", nil)
        var err = ""
        guard let out = osascript(script, [jsCall("state", nil), actionJS, target, String(currentTitle().prefix(40))], error: &err) else {
            return ["kind": "youtube", "error": appleEventError(err)]
        }
        var best: [String: Any]?
        var bestKey = ""
        for line in out.split(separator: "\n") {
            let parts = line.split(separator: "|", maxSplits: 2, omittingEmptySubsequences: false).map(String.init)
            if parts.first == "ERR" {
                if best == nil, line.contains("JavaScript") { return ["kind": "youtube", "error": "js_off", "browser": app] }
                continue
            }
            guard parts.count == 3, let data = parts[2].data(using: .utf8),
                  let m = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { continue }
            // Prefer the tab we're already controlling, then one that's playing.
            let key = "\(parts[0])|\(parts[1])"
            if best == nil || key == target || (m["playing"] as? Bool == true && best?["playing"] as? Bool != true && bestKey != target) {
                best = m
                bestKey = key
            }
        }
        guard var state = best else { return ["kind": "none"] }
        target = bestKey
        state["kind"] = state.removeValue(forKey: "site") as? String ?? "web"
        return state
    }
}
