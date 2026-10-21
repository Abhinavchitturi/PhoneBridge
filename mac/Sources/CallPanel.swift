import AppKit
import SwiftUI

/// Floating call card (top-right, above full-screen apps). Incoming: Answer / Decline. Once a call is on —
/// whoever started it, phone or Mac — it stays until the call ends, with the time talked and Mute / Speaker /
/// Hold / End. Used instead of a notification so the buttons are always visible.
@MainActor
final class CallPanel: ObservableObject {
    @Published var name: String?
    @Published var number: String?
    @Published var detail: String?
    /// Calls you started from the Mac: "Calling…" until the phone reports the call is on.
    @Published var outgoing = false
    @Published var connected = false
    /// A call is on (answered or dialled). Shows the in-call card with its timer.
    @Published var active = false
    /// The other side has picked up — only then does the timer run.
    @Published var answered = false
    @Published var startedAt: Date?
    @Published var muted = false
    @Published var speaker = false
    @Published var held = false

    var onAnswer: () -> Void = {}
    var onDecline: () -> Void = {}
    /// "mute" / "speaker" / "hold" — the phone does the work and reports back with the real state.
    var onControl: (String) -> Void = { _ in }
    /// (name, number, seconds) when a call that was picked up has finished.
    var onEnded: (String?, String?, Int) -> Void = { _, _, _ in }
    private var lastCaller: (name: String?, number: String?)?

    private var panel: NSPanel?
    private var ringTimer: Timer?

    private func finish() {
        defer { lastCaller = nil }
        guard answered, let who = lastCaller, let start = startedAt else { return }
        onEnded(name ?? who.name, number ?? who.number, Int(Date().timeIntervalSince(start)))
    }

    /// Phone sends "call" from two sources (dialer notification and phone-state broadcast); merge them.
    func update(_ m: [String: Any]) {
        let state = m["state"] as? String ?? "idle"
        if state == "idle" {
            finish()
            hide()
            return
        }
        if let n = m["name"] as? String, !n.isEmpty { name = n }
        if let n = m["number"] as? String, !n.isEmpty { number = n }
        if let d = m["detail"] as? String, !d.isEmpty { detail = d }
        if state == "active" {
            if !active { beginActive() }
            noteConnected(m)
            return
        }
        // Ringing: ignore while we're already on a call or dialling one.
        if outgoing || active { return }
        lastCaller = (name, number)
        show()
    }

    /// Incoming calls you answered are connected at once; for calls you made, wait for the phone to say the other side picked up.
    private func beginActive() {
        let wasRinging = lastCaller != nil && !outgoing
        active = true
        connected = true
        answered = wasRinging
        startedAt = wasRinging ? Date() : nil
        ringTimer?.invalidate()
        ringTimer = nil
        lastCaller = (name ?? lastCaller?.name, number ?? lastCaller?.number)
        show(ring: false)
    }

    private func noteConnected(_ m: [String: Any]) {
        guard m["connected"] as? Bool == true else { return }
        let since = (m["since"] as? NSNumber).map { Date(timeIntervalSince1970: $0.doubleValue / 1000) }
        if !answered {
            answered = true
            startedAt = since ?? Date()
        } else if let since, let s = startedAt, abs(since.timeIntervalSince(s)) > 1, since < s.addingTimeInterval(5) {
            startedAt = since // the dialer's own start time is the more exact one
        }
    }

    /// Dialled from the Mac: show who we're calling, without ringing.
    func showOutgoing(name: String?, number: String) {
        hide()
        outgoing = true
        connected = false
        self.name = name
        self.number = number
        lastCaller = (name, number)
        show(ring: false)
        // If the phone never reports the call (e.g. it failed to dial), don't leave the card up forever.
        DispatchQueue.main.asyncAfter(deadline: .now() + 45) { [weak self] in
            if self?.outgoing == true, self?.active == false { self?.hide() }
        }
    }

    /// The phone's real mute / speaker / hold state after a button press.
    func setControls(_ m: [String: Any]) {
        if let v = m["muted"] as? Bool { muted = v }
        if let v = m["speaker"] as? Bool { speaker = v }
        if let v = m["held"] as? Bool { held = v }
    }

    func control(_ action: String) {
        onControl(action)
        switch action { // show the change at once; the phone's report corrects it if it didn't work
        case "mute": muted.toggle()
        case "speaker": speaker.toggle()
        case "hold": held.toggle()
        default: break
        }
    }

    private func show(ring: Bool = true) {
        let size = active ? NSSize(width: 360, height: 168) : NSSize(width: 360, height: 92)
        if panel == nil {
            let p = NSPanel(contentRect: NSRect(origin: .zero, size: size),
                            styleMask: [.nonactivatingPanel, .borderless], backing: .buffered, defer: false)
            p.level = .statusBar
            p.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
            p.isOpaque = false
            p.backgroundColor = .clear
            p.hasShadow = true
            p.isMovableByWindowBackground = true
            p.contentView = NSHostingView(rootView: CallCard(model: self))
            if let screen = NSScreen.main?.visibleFrame {
                p.setFrameOrigin(NSPoint(x: screen.maxX - size.width - 14, y: screen.maxY - size.height - 10))
            }
            panel = p
        } else if let p = panel, p.frame.size != size {
            // Grow or shrink downwards, keeping the top-right corner where it is.
            p.setFrame(NSRect(x: p.frame.maxX - size.width, y: p.frame.maxY - size.height, width: size.width, height: size.height), display: true)
        }
        panel?.orderFrontRegardless()
        guard ring, ringTimer == nil else { return }
        NSSound(named: "Glass")?.play()
        ringTimer = Timer.scheduledTimer(withTimeInterval: 2.2, repeats: true) { _ in NSSound(named: "Glass")?.play() }
    }

    func hide() {
        ringTimer?.invalidate()
        ringTimer = nil
        panel?.orderOut(nil)
        panel = nil // next call gets a fresh, correctly sized card
        name = nil
        number = nil
        detail = nil
        outgoing = false
        connected = false
        active = false
        answered = false
        startedAt = nil
        muted = false
        speaker = false
        held = false
    }

    func answer() { onAnswer(); hide() }
    func decline() { onDecline(); hide() }
    /// End an ongoing call. The card goes away when the phone reports the call is over.
    func hangUp() { onDecline() }
}

private struct CallCard: View {
    @ObservedObject var model: CallPanel

    var body: some View {
        if model.active { ActiveCallCard(model: model) } else { RingingCard(model: model) }
    }
}

// MARK: Incoming / dialling

private struct RingingCard: View {
    @ObservedObject var model: CallPanel

    private var title: String { model.name ?? model.number ?? "Unknown caller" }
    private var subtitle: String {
        let what = model.outgoing ? "Calling…" : "Incoming call"
        if let number = model.number, number != title { return "\(what) · \(number)" }
        return what
    }

    var body: some View {
        HStack(spacing: 12) {
            Avatar(name: title, size: 46)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.headline).lineLimit(1)
                Text(subtitle).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 4)
            roundButton("phone.down.fill", Theme.red, model.outgoing ? "Hang up" : "Decline") { model.decline() }
            if !model.outgoing {
                roundButton("phone.fill", Theme.green, "Answer") { model.answer() }
            }
        }
        .padding(.horizontal, 16)
        .frame(width: 360, height: 92)
        .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(.regularMaterial))
        .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(.white.opacity(0.08)))
    }
}

// MARK: On a call

private struct ActiveCallCard: View {
    @ObservedObject var model: CallPanel

    private var title: String { model.name ?? model.number ?? "On call" }
    private var subtitle: String {
        if model.held { return "On hold" }
        if !model.answered { return model.detail.flatMap { $0.isEmpty ? nil : $0 } ?? "Calling…" }
        if let number = model.number, number != title { return number }
        return "On call"
    }

    private static func clock(_ seconds: TimeInterval) -> String {
        let s = max(0, Int(seconds))
        return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String(format: "%02d:%02d", s / 60, s % 60)
    }

    var body: some View {
        VStack(spacing: 14) {
            HStack(spacing: 12) {
                Avatar(name: title, size: 44)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.system(size: 15, weight: .semibold)).lineLimit(1)
                    Text(subtitle).font(.system(size: 12)).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer(minLength: 4)
                if model.answered {
                    HStack(spacing: 6) {
                        Circle().fill(model.held ? Color.orange : Theme.green).frame(width: 8, height: 8)
                        TimelineView(.periodic(from: .now, by: 1)) { context in
                            Text(Self.clock(context.date.timeIntervalSince(model.startedAt ?? context.date)))
                                .font(.system(size: 17, weight: .semibold, design: .rounded).monospacedDigit())
                        }
                    }
                } else {
                    HStack(spacing: 6) {
                        Image(systemName: "phone.arrow.up.right").font(.system(size: 12, weight: .semibold)).foregroundStyle(Theme.accent)
                        Text("Ringing…").font(.system(size: 15, weight: .semibold, design: .rounded)).foregroundStyle(.secondary)
                    }
                }
            }
            HStack(spacing: 10) {
                control("mic.slash.fill", "Mute", on: model.muted) { model.control("mute") }
                control("speaker.wave.3.fill", "Speaker", on: model.speaker) { model.control("speaker") }
                control("pause.fill", "Hold", on: model.held) { model.control("hold") }
                Button { model.hangUp() } label: {
                    VStack(spacing: 4) {
                        Image(systemName: "phone.down.fill").font(.system(size: 16, weight: .semibold)).foregroundStyle(.white)
                            .frame(width: 44, height: 40).background(Capsule().fill(Theme.red))
                        Text("End").font(.system(size: 10.5, weight: .semibold)).foregroundStyle(.secondary)
                    }
                }
                .buttonStyle(.plain)
                .help("End the call")
            }
        }
        .padding(.horizontal, 16)
        .frame(width: 360, height: 168)
        .background(RoundedRectangle(cornerRadius: 24, style: .continuous).fill(.regularMaterial))
        .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).strokeBorder(.white.opacity(0.08)))
    }

    private func control(_ icon: String, _ title: String, on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: icon).font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(on ? Color.white : .primary)
                    .frame(width: 44, height: 40)
                    .background(Capsule().fill(on ? AnyShapeStyle(Theme.accent) : AnyShapeStyle(Color.primary.opacity(0.1))))
                Text(title).font(.system(size: 10.5, weight: .semibold)).foregroundStyle(.secondary)
            }
        }
        .buttonStyle(.plain)
    }
}

private func roundButton(_ symbol: String, _ color: Color, _ help: String, action: @escaping () -> Void) -> some View {
    Button(action: action) {
        Image(systemName: symbol)
            .font(.system(size: 17, weight: .semibold))
            .foregroundStyle(.white)
            .frame(width: 42, height: 42)
            .background(Circle().fill(color))
    }
    .buttonStyle(.plain)
    .help(help)
}
