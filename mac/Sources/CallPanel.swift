import AppKit
import SwiftUI

/// Floating call card (top-right, above full-screen apps). Incoming: Answer / Decline. Once a call is on —
/// whoever started it, phone or Mac — it stays until the call ends, with the time talked and End. Used instead of a notification so the buttons are always visible.
@MainActor
final class CallPanel: ObservableObject {
    /// All the call rules live in CallLogic (see Tests/call-logic-check.swift).
    @Published private var logic = CallLogic()
    @Published var muted = false
    @Published var speaker = false
    @Published var held = false

    var name: String? { logic.name }
    var number: String? { logic.number }
    var detail: String? { logic.detail }
    /// Calls you started from the Mac: "Calling…" until the phone reports the call is on.
    var outgoing: Bool { logic.outgoing }
    /// A call is on (answered or dialled). Shows the in-call card.
    var active: Bool { logic.active }
    /// The other side is on the line — only then does the timer run.
    var answered: Bool { logic.answered }
    var startedAt: Date? { logic.startedAt }
    /// A second call, ringing while you're on one.
    var waiting: WaitingCaller? { logic.waiting }
    var heldCaller: String? { logic.heldCaller }

    var onAnswer: () -> Void = {}
    var onDecline: () -> Void = {}
    /// Answer / decline the second, waiting call (the phone keeps the call you're on).
    var onAnswerWaiting: () -> Void = {}
    var onDeclineWaiting: () -> Void = {}
    /// "mute" / "speaker" / "hold" — the phone does the work and reports back with the real state.
    var onControl: (String) -> Void = { _ in }
    /// (name, number, seconds) when a call that was picked up has finished.
    var onEnded: (String?, String?, Int) -> Void = { _, _, _ in }

    private var panel: NSPanel?
    private var ringTimer: Timer?

    /// The phone sends "call" reports (the call's state, who's on it, whether the other side picked up, a waiting call).
    func update(_ m: [String: Any]) {
        let hadWaiting = logic.waiting != nil
        switch logic.update(m) {
        case .none:
            return
        case .hide:
            hide()
        case .ended(let name, let number, let seconds):
            hide()
            onEnded(name, number, seconds)
        case .show:
            if logic.active {
                ringTimer?.invalidate()
                ringTimer = nil
                show(ring: false)
                if logic.waiting != nil && !hadWaiting { NSSound(named: "Glass")?.play() }
            } else {
                show()
            }
        }
    }

    /// Dialled from the Mac: show who we're calling, without ringing.
    func showOutgoing(name: String?, number: String) {
        hide()
        logic.startOutgoing(name: name, number: number)
        show(ring: false)
        // If the phone never reports the call (e.g. it failed to dial), don't leave the card up forever.
        DispatchQueue.main.asyncAfter(deadline: .now() + 45) { [weak self] in
            if self?.logic.outgoing == true, self?.logic.active == false { self?.hide() }
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
        let size = active ? NSSize(width: 360, height: logic.waiting != nil ? 232 : 168) : NSSize(width: 360, height: 92)
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
        logic = CallLogic()
        muted = false
        speaker = false
        held = false
    }

    func answer() { onAnswer(); hide() }
    func decline() { onDecline(); hide() }
    /// End an ongoing call. The card goes away when the phone reports the call is over.
    func hangUp() { onDecline() }

    func answerWaiting() {
        logic.willAnswerWaiting()
        onAnswerWaiting()
    }

    func declineWaiting() {
        onDeclineWaiting()
        logic.waiting = nil // the phone's next report confirms it
        show(ring: false)
    }
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
        .themedSurface(22)
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
        if let onHold = model.heldCaller { return "\(onHold) on hold" }
        if let number = model.number, number != title { return number }
        return "On call"
    }

    private static func clock(_ seconds: TimeInterval) -> String {
        let s = max(0, Int(seconds))
        return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String(format: "%02d:%02d", s / 60, s % 60)
    }

    var body: some View {
        VStack(spacing: 14) {
            if let w = model.waiting { waitingRow(w) }
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
            // Only End: Android lets an app answer, decline and end calls, but mute, speaker and hold belong to the phone's
            // own call screen (checked on a Galaxy M36, Android 16), and PhoneBridge doesn't use accessibility to press them.
            HStack(spacing: 10) {
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
        .frame(width: 360, height: model.waiting != nil ? 232 : 168)
        .themedSurface(24)
        .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).strokeBorder(.white.opacity(0.08)))
    }

    /// A second call ringing: its own Answer / Decline, so the call you're on is never touched by accident.
    private func waitingRow(_ w: WaitingCaller) -> some View {
        let who = w.name ?? w.number ?? "Someone"
        return HStack(spacing: 10) {
            Image(systemName: "phone.arrow.down.left").font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.accent)
            VStack(alignment: .leading, spacing: 1) {
                Text(who).font(.system(size: 13, weight: .semibold)).lineLimit(1)
                Text("Call waiting").font(.system(size: 11)).foregroundStyle(.secondary)
            }
            Spacer(minLength: 4)
            Button { model.declineWaiting() } label: {
                Image(systemName: "phone.down.fill").font(.system(size: 13, weight: .semibold)).foregroundStyle(.white)
                    .frame(width: 34, height: 34).background(Circle().fill(Theme.red))
            }.buttonStyle(.plain).help("Decline the waiting call")
            Button { model.answerWaiting() } label: {
                Image(systemName: "phone.fill").font(.system(size: 13, weight: .semibold)).foregroundStyle(.white)
                    .frame(width: 34, height: 34).background(Circle().fill(Theme.green))
            }.buttonStyle(.plain).help("Answer the waiting call (the current call is put on hold)")
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color.primary.opacity(0.08)))
        .padding(.top, 12)
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
