import AppKit
import SwiftUI

/// A panel that can take keyboard focus (to type a reply) without pulling the whole app to the front.
final class KeyablePanel: NSPanel {
    override var canBecomeKey: Bool { true }
}

/// Corner pop-up for chat messages (WhatsApp, Instagram, …) with a ready reply box and suggested replies.
/// macOS keeps the reply field of its own banners hidden until you click Reply, so this shows one outright.
@MainActor
final class ChatPopup: ObservableObject {
    @Published var id: String?
    @Published var app = ""
    @Published var title = ""
    @Published var text = ""
    @Published var icon: NSImage?
    @Published var suggestions: [String] = []
    @Published var draft = ""
    @Published var sent = false

    /// (notification id, text) → the phone sends it through the chat app's own reply.
    var onReply: (String, String) -> Void = { _, _ in }
    /// True while a call card is up, so the two don't overlap.
    var isBusy: () -> Bool = { false }

    private var panel: KeyablePanel?
    private var hosting: NSHostingView<PopupView>?
    private var timer: Timer?
    private let width: CGFloat = 380

    func show(_ m: [String: Any]) {
        let newId = m["id"] as? String
        let sameChat = newId != nil && newId == id // chat apps update one notification as a conversation grows
        id = newId
        app = m["app"] as? String ?? ""
        title = m["title"] as? String ?? ""
        text = m["text"] as? String ?? ""
        icon = (m["icon"] as? String).flatMap { Data(base64Encoded: $0) }.flatMap { NSImage(data: $0) }
        suggestions = []
        sent = false
        if !sameChat { draft = "" }
        present()
        NSSound(named: "Tink")?.play()
        restartTimer()
    }

    func setSuggestions(for notificationId: String?, _ list: [String]) {
        guard id == notificationId, !list.isEmpty else { return }
        suggestions = list
        resizeSoon()
    }

    func send(_ raw: String) {
        let t = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty, let id else { return }
        onReply(id, t)
        draft = ""
        sent = true
        resizeSoon()
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 1.4, repeats: false) { [weak self] _ in Task { @MainActor in self?.hide() } }
    }

    /// The phone dropped that notification (you replied or read it there).
    func remove(_ notificationId: String) {
        if id == notificationId { hide() }
    }

    func hide() {
        timer?.invalidate()
        timer = nil
        panel?.orderOut(nil)
        id = nil
        draft = ""
        suggestions = []
        sent = false
    }

    // MARK: Panel

    private func present() {
        if panel == nil {
            let p = KeyablePanel(contentRect: NSRect(x: 0, y: 0, width: width, height: 160),
                                 styleMask: [.nonactivatingPanel, .borderless], backing: .buffered, defer: false)
            p.level = .statusBar
            p.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
            p.isOpaque = false
            p.backgroundColor = .clear
            p.hasShadow = true
            p.isMovableByWindowBackground = true
            p.becomesKeyOnlyIfNeeded = true
            let h = NSHostingView(rootView: PopupView(model: self))
            p.contentView = h
            hosting = h
            panel = p
            positionTopRight(size: h.fittingSize)
        }
        let reappearing = panel?.isVisible == false
        resize()
        if reappearing, let h = hosting { positionTopRight(size: h.fittingSize) } // start from the top again, not where it last ended up
        panel?.orderFrontRegardless()
    }

    private func positionTopRight(size: NSSize) {
        // Same spot as macOS's own banners: right at the top edge of the screen, not under the menu bar.
        guard let p = panel, let screen = NSScreen.main?.frame else { return }
        let drop: CGFloat = isBusy() ? 190 : 0
        p.setFrame(NSRect(x: screen.maxX - size.width - 14, y: screen.maxY - size.height - 12 - drop, width: size.width, height: size.height), display: true)
    }

    private func resizeSoon() {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { [weak self] in self?.resize() }
    }

    private func resize() {
        guard let p = panel, let h = hosting else { return }
        let size = h.fittingSize
        guard size.height > 20, p.frame.size != size else { return }
        // Grow downwards from the top-right corner.
        p.setFrame(NSRect(x: p.frame.maxX - size.width, y: p.frame.maxY - size.height, width: size.width, height: size.height), display: true)
    }

    /// Goes away after a while, unless you're using it.
    private func restartTimer() {
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 16, repeats: false) { [weak self] _ in
            Task { @MainActor in
                guard let self, let p = self.panel else { return }
                let hovering = p.frame.contains(NSEvent.mouseLocation)
                if hovering || p.isKeyWindow || !self.draft.isEmpty { self.restartTimer() } else { self.hide() }
            }
        }
    }
}

struct PopupView: View {
    @ObservedObject var model: ChatPopup

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 10) {
                Group {
                    if let icon = model.icon {
                        Image(nsImage: icon).resizable().scaledToFit().frame(width: 36, height: 36).clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
                    } else {
                        Avatar(name: model.app, size: 36)
                    }
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(model.app.uppercased()).font(.system(size: 10, weight: .semibold)).tracking(0.6).foregroundStyle(.secondary)
                    Text(model.title).font(.system(size: 14, weight: .semibold)).lineLimit(1)
                    Text(model.text).font(.system(size: 13)).lineLimit(4).fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 4)
                Button { model.hide() } label: {
                    Image(systemName: "xmark").font(.system(size: 10, weight: .bold)).foregroundStyle(.secondary)
                        .frame(width: 22, height: 22).background(Circle().fill(Color.primary.opacity(0.08)))
                }
                .buttonStyle(.plain)
            }
            if model.sent {
                Label("Sent", systemImage: "checkmark.circle.fill").font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.green)
                    .frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 6)
            } else {
                if !model.suggestions.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            ForEach(model.suggestions, id: \.self) { s in
                                Button { model.send(s) } label: {
                                    Text(s).font(.system(size: 12, weight: .medium)).lineLimit(1)
                                        .padding(.horizontal, 11).padding(.vertical, 6)
                                        .background(Capsule().fill(Theme.accent.opacity(0.16)))
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                }
                HStack(spacing: 8) {
                    TextField("Reply…", text: $model.draft).textFieldStyle(.plain).font(.system(size: 13.5))
                        .onSubmit { model.send(model.draft) }
                    Button { model.send(model.draft) } label: {
                        Image(systemName: "arrow.up").font(.system(size: 12, weight: .bold)).foregroundStyle(.white)
                            .frame(width: 28, height: 28)
                            .background(Circle().fill(model.draft.trimmingCharacters(in: .whitespaces).isEmpty ? AnyShapeStyle(Color.primary.opacity(0.2)) : AnyShapeStyle(Theme.gradient)))
                    }
                    .buttonStyle(.plain)
                }
                .padding(.leading, 14).padding(.trailing, 4).padding(.vertical, 4)
                .card(18, fill: Color.primary.opacity(0.07))
            }
        }
        .padding(14)
        .frame(width: 380)
        .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(.regularMaterial))
        .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(.white.opacity(0.08)))
    }
}
