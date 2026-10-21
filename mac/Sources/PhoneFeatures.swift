import AppKit
import SwiftUI

// MARK: - ⌘K command palette: search everything on the phone, run quick actions

struct PaletteItem: Identifiable {
    let id: String
    let icon: String
    let title: String
    let subtitle: String
    let group: String
    let run: () -> Void
}

/// A text field that grabs focus at once and reports ↑ ↓ ⏎ ⎋ (SwiftUI's own focus handling isn't available here).
struct PaletteField: NSViewRepresentable {
    @Binding var text: String
    let placeholder: String
    var onMove: (Int) -> Void
    var onSubmit: () -> Void
    var onCancel: () -> Void

    func makeNSView(context: Context) -> NSTextField {
        let f = NSTextField()
        f.isBordered = false
        f.drawsBackground = false
        f.focusRingType = .none
        f.font = .systemFont(ofSize: 20, weight: .medium)
        f.placeholderString = placeholder
        f.delegate = context.coordinator
        DispatchQueue.main.async { f.window?.makeFirstResponder(f) }
        return f
    }

    func updateNSView(_ f: NSTextField, context: Context) {
        if f.stringValue != text { f.stringValue = text }
        context.coordinator.parent = self
    }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, NSTextFieldDelegate {
        var parent: PaletteField
        init(_ p: PaletteField) { parent = p }

        func controlTextDidChange(_ n: Notification) {
            if let f = n.object as? NSTextField { parent.text = f.stringValue }
        }

        func control(_ control: NSControl, textView: NSTextView, doCommandBy sel: Selector) -> Bool {
            switch sel {
            case #selector(NSResponder.moveUp(_:)): parent.onMove(-1)
            case #selector(NSResponder.moveDown(_:)): parent.onMove(1)
            case #selector(NSResponder.insertNewline(_:)): parent.onSubmit()
            case #selector(NSResponder.cancelOperation(_:)): parent.onCancel()
            default: return false
            }
            return true
        }
    }
}

struct CommandPalette: View {
    @ObservedObject var store: PhoneStore
    @ObservedObject var bridge: Bridge
    @ObservedObject var clips: ClipboardHistory

    private func matches(_ s: String, _ q: String) -> Bool { q.isEmpty || s.localizedCaseInsensitiveContains(q) }

    private var items: [PaletteItem] {
        let q = store.paletteQuery.trimmingCharacters(in: .whitespaces)
        var out: [PaletteItem] = []

        func action(_ id: String, _ icon: String, _ title: String, _ sub: String = "", _ run: @escaping () -> Void) {
            if matches(title, q) || matches(sub, q) { out.append(PaletteItem(id: "a-" + id, icon: icon, title: title, subtitle: sub, group: "Actions", run: run)) }
        }
        let digits = q.filter { $0.isNumber || $0 == "+" }
        if digits.count >= 3, digits.count == q.filter({ !$0.isWhitespace && $0 != "-" }).count {
            out.append(PaletteItem(id: "dial", icon: "phone.fill", title: "Call \(q)", subtitle: "From your phone", group: "Actions") { store.dial(q) })
        }
        action("ring", "bell.and.waves.left.and.right", bridge.phoneRinging ? "Stop ringing my phone" : "Ring my phone", "Find it nearby") { bridge.toggleRing() }
        action("dnd", "moon.fill", bridge.phoneDnd ? "Turn Do Not Disturb off" : "Turn Do Not Disturb on", "On your phone") {
            bridge.send(["t": "phone_dnd", "on": !bridge.phoneDnd])
        }
        action("send", "square.and.arrow.up", "Send a file to phone…", "Pick files on this Mac") { bridge.pickFile() }
        action("mirror", "rectangle.on.rectangle", "Show phone screen", "Mirror it in a window") { bridge.mirror() }
        action("reconnect", "arrow.triangle.2.circlepath", "Reconnect to phone", "Search for it again") { bridge.reconnect() }
        action("find", "location.fill", "Find my phone", "Show it on a map") { store.tab = .device; store.locate() }
        action("torch", "flashlight.on.fill", store.device?.torch == true ? "Turn phone flashlight off" : "Turn phone flashlight on", "Remote flashlight") {
            store.send(["t": "torch", "on": !(store.device?.torch == true)])
        }
        action("lockphone", "lock.fill", "Lock my phone", "Screen off, locked") { store.send(["t": "phone_lock"]) }
        action("unlockphone", "lock.open.fill", "Unlock my phone", "Wake it and enter your PIN") { store.unlockPhone() }
        action("slides", "play.rectangle.fill", "Start photo slideshow", "Full screen, every few seconds") {
            store.tab = .photos
            store.startSlideshow()
        }
        for (tab, title, icon) in [(PhoneStore.Tab.messages, "Messages", "bubble.left.and.bubble.right.fill"),
                                   (.photos, "Photos & Videos", "photo.on.rectangle.angled"), (.calls, "Calls", "phone.fill"),
                                   (.apps, "Phone Apps", "square.grid.2x2.fill"), (.clipboard, "Clipboard history", "doc.on.clipboard.fill"),
                                   (.device, "Device & storage", "iphone.gen3"),
                                   (.settings, "Settings", "gearshape.fill")] {
            action("go-\(title)", icon, "Go to \(title)") { store.tab = tab }
        }
        guard !q.isEmpty else { return out }

        for t in store.threads where matches(t.title, q) || matches(t.snippet, q) {
            out.append(PaletteItem(id: "t-\(t.id)", icon: "bubble.left.fill", title: t.title, subtitle: t.snippet, group: "Messages") {
                store.tab = .messages
                store.selectedThread = t.id
            })
            if out.filter({ $0.group == "Messages" }).count >= 4 { break }
        }
        for c in store.contacts where c.name.localizedCaseInsensitiveContains(q) || (!digits.isEmpty && c.number.filter(\.isNumber).contains(digits)) {
            out.append(PaletteItem(id: "c-\(c.id)", icon: "person.fill", title: c.name, subtitle: "Call \(c.number)", group: "Contacts") { store.dial(c.number) })
            if out.filter({ $0.group == "Contacts" }).count >= 5 { break }
        }
        for a in bridge.apps where a.name.localizedCaseInsensitiveContains(q) {
            out.append(PaletteItem(id: "app-\(a.id)", icon: "square.grid.2x2.fill", title: a.name, subtitle: "Open on the Mac", group: "Apps") { bridge.openApp(a) })
            if out.filter({ $0.group == "Apps" }).count >= 5 { break }
        }
        for p in store.photos where p.name.localizedCaseInsensitiveContains(q) {
            out.append(PaletteItem(id: "p-\(p.id)", icon: p.isVideo ? "film" : "photo", title: p.name, subtitle: p.isVideo ? "Video" : "Photo", group: "Photos") {
                store.tab = .photos
                store.view(p)
            })
            if out.filter({ $0.group == "Photos" }).count >= 3 { break }
        }
        for item in clips.items where item.text.localizedCaseInsensitiveContains(q) {
            out.append(PaletteItem(id: "clip-\(item.id)", icon: "doc.on.clipboard", title: String(item.text.prefix(80)).replacingOccurrences(of: "\n", with: " "),
                                   subtitle: "Copy again", group: "Clipboard") { clips.copy(item) })
            if out.filter({ $0.group == "Clipboard" }).count >= 3 { break }
        }
        return out
    }

    private func close() {
        store.paletteOpen = false
        store.paletteQuery = ""
    }

    private func run(_ item: PaletteItem) {
        close()
        item.run()
    }

    var body: some View {
        let list = items
        let index = min(store.paletteIndex, max(list.count - 1, 0))
        return ZStack(alignment: .top) {
            Color.black.opacity(0.4).ignoresSafeArea().onTapGesture { close() }
            VStack(spacing: 0) {
                HStack(spacing: 12) {
                    Image(systemName: "magnifyingglass").font(.system(size: 18, weight: .medium)).foregroundStyle(.secondary)
                    PaletteField(text: $store.paletteQuery, placeholder: "Search your phone, or type a command…",
                                 onMove: { d in store.paletteIndex = min(max(index + d, 0), max(list.count - 1, 0)) },
                                 onSubmit: { if let item = list[safe: index] { run(item) } },
                                 onCancel: { close() })
                        .frame(height: 28)
                    Text("esc").font(.system(size: 11, weight: .semibold)).foregroundStyle(.secondary)
                        .padding(.horizontal, 7).padding(.vertical, 3).card(7)
                }
                .padding(.horizontal, 18).padding(.vertical, 14)
                Divider()
                if list.isEmpty {
                    Text("No matches").foregroundStyle(.secondary).frame(maxWidth: .infinity).padding(.vertical, 30)
                } else {
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 2) {
                            ForEach(Array(list.enumerated()), id: \.element.id) { i, item in
                                if i == 0 || list[i - 1].group != item.group {
                                    Text(item.group.uppercased()).font(.system(size: 10.5, weight: .semibold)).tracking(1).foregroundStyle(.secondary)
                                        .padding(.horizontal, 12).padding(.top, i == 0 ? 4 : 10).padding(.bottom, 3)
                                }
                                row(item, selected: i == index)
                            }
                        }
                        .padding(8)
                    }
                    .frame(maxHeight: 380)
                }
            }
            .frame(width: 600)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(Theme.stroke))
            .shadow(color: .black.opacity(0.35), radius: 30, y: 14)
            .padding(.top, 80)
        }
    }

    private func row(_ item: PaletteItem, selected: Bool) -> some View {
        Button { run(item) } label: {
            HStack(spacing: 12) {
                Image(systemName: item.icon).font(.system(size: 14, weight: .semibold)).foregroundStyle(selected ? Color.white : Theme.accent)
                    .frame(width: 32, height: 32)
                    .background(RoundedRectangle(cornerRadius: 9, style: .continuous).fill(selected ? Theme.accent : Theme.accent.opacity(0.14)))
                VStack(alignment: .leading, spacing: 1) {
                    Text(item.title).font(.system(size: 14, weight: .semibold)).lineLimit(1)
                    if !item.subtitle.isEmpty { Text(item.subtitle).font(.system(size: 12)).foregroundStyle(.secondary).lineLimit(1) }
                }
                Spacer()
                if selected { Image(systemName: "return").font(.system(size: 12)).foregroundStyle(.secondary) }
            }
            .padding(.horizontal, 10).padding(.vertical, 6)
            .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(selected ? Theme.accent.opacity(0.16) : .clear))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

extension Array {
    subscript(safe i: Int) -> Element? { indices.contains(i) ? self[i] : nil }
}

// MARK: - Clipboard history

struct ClipboardView: View {
    @ObservedObject var clips: ClipboardHistory
    @ObservedObject var store: PhoneStore
    @ObservedObject var note: SharedNote

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            history
            notepad.frame(width: 340)
        }
        .padding(.leading, 24).padding(.trailing, 20).padding(.bottom, 18)
    }

    private var notepad: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Image(systemName: "note.text").font(.system(size: 14, weight: .semibold)).foregroundStyle(.white)
                    .frame(width: 30, height: 30).background(RoundedRectangle(cornerRadius: 9, style: .continuous).fill(Theme.gradient))
                VStack(alignment: .leading, spacing: 0) {
                    Text("Shared notepad").font(.system(size: 16, weight: .bold, design: .rounded))
                    Text("Same note on the phone — edit either").font(.system(size: 11)).foregroundStyle(.secondary)
                }
                Spacer()
            }
            TextEditor(text: $note.text)
                .font(.system(size: 14)).scrollContentBackground(.hidden)
                .padding(8).card(14, fill: Color.primary.opacity(0.04))
            HStack(spacing: 8) {
                Button { note.text += (note.text.isEmpty || note.text.hasSuffix("\n") ? "" : "\n") + "☐ " } label: {
                    Label("To-do", systemImage: "checklist").font(.system(size: 12, weight: .semibold)).padding(.horizontal, 12).padding(.vertical, 6).card(14)
                }
                .buttonStyle(.plain)
                Button {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(note.text, forType: .string)
                } label: {
                    Label("Copy all", systemImage: "doc.on.doc").font(.system(size: 12, weight: .semibold)).padding(.horizontal, 12).padding(.vertical, 6).card(14)
                }
                .buttonStyle(.plain)
                Spacer()
                Text("\(note.text.count) chars").font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(14)
        .card(22)
        .frame(maxHeight: .infinity)
    }

    private static let time: DateFormatter = {
        let f = DateFormatter()
        f.dateStyle = .none
        f.timeStyle = .short
        return f
    }()

    private var history: some View {
        VStack(spacing: 12) {
            HStack(spacing: 12) {
                SearchField(placeholder: "Search clipboard", text: $clips.search).frame(maxWidth: 420)
                Spacer()
                Text("In memory only").font(.caption).foregroundStyle(.secondary)
                Button { clips.clear() } label: {
                    Label("Clear", systemImage: "trash").font(.system(size: 12.5, weight: .semibold))
                        .padding(.horizontal, 14).padding(.vertical, 9).card(14)
                }
                .buttonStyle(.plain)
            }
            if clips.filtered.isEmpty {
                EmptyState(icon: "doc.on.clipboard.fill", title: "Nothing copied yet",
                           subtitle: "Everything you copy on the Mac, or send from the phone, shows up here. Click one to copy it again.")
            } else {
                ScrollView {
                    LazyVStack(spacing: 8) { ForEach(clips.filtered) { item in row(item) } }
                }
            }
        }
    }

    private func row(_ item: ClipboardHistory.Item) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: item.fromPhone ? "iphone.gen3" : "desktopcomputer")
                .font(.system(size: 14)).foregroundStyle(item.fromPhone ? Theme.accent2 : Theme.accent)
                .frame(width: 34, height: 34)
                .background(Circle().fill((item.fromPhone ? Theme.accent2 : Theme.accent).opacity(0.14)))
            VStack(alignment: .leading, spacing: 4) {
                Text(item.text).font(.system(size: 13.5)).lineLimit(4).textSelection(.enabled)
                Text("\(item.fromPhone ? "From phone" : "From Mac") · \(Self.time.string(from: item.date))")
                    .font(.system(size: 11)).foregroundStyle(.secondary)
            }
            Spacer(minLength: 8)
            Button { clips.copy(item); store.notify("Copied", "Also on your phone's clipboard") } label: {
                Image(systemName: "doc.on.doc").font(.system(size: 13, weight: .semibold)).frame(width: 32, height: 32).card(10)
            }
            .buttonStyle(.plain).help("Copy again")
            Button { clips.remove(item) } label: {
                Image(systemName: "xmark").font(.system(size: 11, weight: .bold)).foregroundStyle(.secondary).frame(width: 32, height: 32).card(10)
            }
            .buttonStyle(.plain).help("Remove")
        }
        .padding(12)
        .card(16)
    }
}

// MARK: - Settings

struct SettingsView: View {
    @ObservedObject var bridge: Bridge
    @ObservedObject var store: PhoneStore
    @ObservedObject var rules: NotifRules
    @ObservedObject var speech: SpeechReader
    @ObservedObject var battery: PhoneBatteryWatch
    @ObservedObject var focus: FocusLink
    @ObservedObject var history: NotificationHistory
    @ObservedObject var guardian: IntruderGuard
    @ObservedObject var rulesInput = RulesInput.shared

    private var seenApps: [String] {
        Array(Set(history.items.map(\.app).filter { !$0.isEmpty }).union(rules.mutedApps)).sorted()
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                section("Notifications", "bell.badge.fill", "Muted apps and words still land in History — they just don't pop up or make a sound on the Mac.") {
                    if seenApps.isEmpty {
                        Text("Apps appear here once they've sent you a notification.").font(.callout).foregroundStyle(.secondary)
                    } else {
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 200), spacing: 8)], alignment: .leading, spacing: 8) {
                            ForEach(seenApps, id: \.self) { app in
                                Toggle(isOn: Binding(get: { rules.mutedApps.contains(app) }, set: { _ in rules.toggle(app) })) {
                                    Text("Mute \(app)").lineLimit(1)
                                }
                                .toggleStyle(.switch).controlSize(.small)
                            }
                        }
                    }
                    Toggle("Show a reply box in the corner for chat messages", isOn: $rules.replyPopup)
                    Text("WhatsApp, Instagram and other chat apps pop up in the top-right with a text box ready to type in. It replaces the macOS banner for those apps and goes away by itself after a few seconds unless you're using it.")
                        .font(.caption).foregroundStyle(.secondary)
                    Toggle("Offer 3 suggested replies on chat notifications", isOn: $rules.smartReplies)
                    Text("Written on this Mac by Apple's on-device model (needs Apple Intelligence). Nothing is sent anywhere. Chat notifications may appear a second or two later while the replies are written.")
                        .font(.caption).foregroundStyle(.secondary)
                    Divider().padding(.vertical, 4)
                    Text("Mute anything containing").font(.system(size: 12.5, weight: .semibold))
                    HStack(spacing: 8) {
                        ForEach(rules.keywords, id: \.self) { k in
                            Button { rules.keywords.removeAll { $0 == k } } label: {
                                HStack(spacing: 4) { Text(k); Image(systemName: "xmark").font(.system(size: 9, weight: .bold)) }
                                    .font(.system(size: 12, weight: .medium)).padding(.horizontal, 10).padding(.vertical, 5)
                                    .background(Capsule().fill(Theme.accent.opacity(0.15)))
                            }
                            .buttonStyle(.plain)
                        }
                        TextField("Add a word, e.g. offer, sale", text: $rulesInput.keyword)
                            .textFieldStyle(.plain).padding(.horizontal, 12).padding(.vertical, 6).card(14).frame(maxWidth: 260)
                            .onSubmit { rules.addKeyword(rulesInput.keyword); rulesInput.keyword = "" }
                    }
                }
                section("Messages", "bubble.left.and.bubble.right.fill", "") {
                    Toggle("Read new messages aloud", isOn: $speech.autoRead)
                    Text("Messages from chat apps are spoken as they arrive. You can also right-click any message and choose Read aloud.")
                        .font(.caption).foregroundStyle(.secondary)
                    Divider().padding(.vertical, 4)
                    Text("Quick replies").font(.system(size: 12.5, weight: .semibold))
                    ForEach(store.quickReplies, id: \.self) { r in
                        HStack {
                            Text(r).lineLimit(1)
                            Spacer()
                            Button { store.quickReplies.removeAll { $0 == r } } label: { Image(systemName: "minus.circle.fill").foregroundStyle(.secondary) }
                                .buttonStyle(.plain)
                        }
                    }
                    HStack {
                        TextField("New quick reply", text: $store.draftQuickReply).textFieldStyle(.plain)
                            .padding(.horizontal, 12).padding(.vertical, 7).card(14)
                            .onSubmit { addReply() }
                        Button("Add") { addReply() }.disabled(store.draftQuickReply.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                }
                section("Intruder guard", "lock.shield.fill", "Know when someone opens your Mac while you're away, and lock it from your phone.") {
                    GuardSettingsView(guardian: guardian, bridge: bridge)
                }
                section("Missed calls", "phone.arrow.down.left", "When you miss or decline a call, the phone texts the caller back by itself — even with the Mac off. Each number is texted at most once every 4 hours.") {
                    Toggle("Text back when I miss a call", isOn: Binding(get: { store.autoReplyOn }, set: { store.autoReplyOn = $0; store.saveAutoReply() }))
                    TextEditor(text: $store.autoReplyText).font(.system(size: 13.5)).scrollContentBackground(.hidden)
                        .frame(height: 64).padding(6).card(12, fill: Color.primary.opacity(0.04))
                    HStack {
                        Text("{name} becomes the contact's first name.").font(.caption).foregroundStyle(.secondary)
                        Spacer()
                        Button("Save message") { store.saveAutoReply() }
                    }
                    Toggle("Only saved contacts", isOn: Binding(get: { store.autoReplyContactsOnly }, set: { store.autoReplyContactsOnly = $0; store.saveAutoReply() }))
                    Toggle("Also when I decline", isOn: Binding(get: { store.autoReplyDeclined }, set: { store.autoReplyDeclined = $0; store.saveAutoReply() }))
                }
                section("Phone", "iphone.gen3", "") {
                    Toggle("Battery alerts (fully charged, running low)", isOn: $battery.alerts)
                    Toggle("When a Focus is on for the Mac, turn on Do Not Disturb on the phone", isOn: $focus.enabled)
                    Text("Needs permission to read your Focus status the first time.").font(.caption).foregroundStyle(.secondary)
                }
                section("Right-click menu", "contextualmenu.and.cursorarrow", "") {
                    Text("Select files or text anywhere on the Mac → right-click → Services → Send to Phone. If it doesn't show yet, tap the button below (or log out and back in once).")
                        .font(.callout).foregroundStyle(.secondary)
                    Button("Refresh the Services menu") { NSUpdateDynamicServices() }
                }
            }
            .padding(.leading, 24).padding(.trailing, 20).padding(.bottom, 24)
            .frame(maxWidth: 760, alignment: .leading)
        }
    }

    private func addReply() {
        let t = store.draftQuickReply.trimmingCharacters(in: .whitespaces)
        guard !t.isEmpty, !store.quickReplies.contains(t) else { return }
        store.quickReplies.append(t)
        store.draftQuickReply = ""
    }

    private func section<C: View>(_ title: String, _ icon: String, _ note: String, @ViewBuilder content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) {
                Image(systemName: icon).font(.system(size: 14, weight: .semibold)).foregroundStyle(.white)
                    .frame(width: 30, height: 30).background(RoundedRectangle(cornerRadius: 9, style: .continuous).fill(Theme.gradient))
                Text(title).font(.system(size: 16, weight: .bold, design: .rounded))
            }
            if !note.isEmpty { Text(note).font(.caption).foregroundStyle(.secondary) }
            content()
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .card(20)
    }
}

/// Text being typed into Settings fields (kept here because this toolchain has no @State).
@MainActor
final class RulesInput: ObservableObject {
    static let shared = RulesInput()
    @Published var keyword = ""
}

// MARK: - Slideshow

struct SlideshowView: View {
    @ObservedObject var store: PhoneStore

    var body: some View {
        let list = store.slidePhotos
        let photo = list.isEmpty ? nil : list[store.slideIndex % list.count]
        return ZStack {
            Color.black
            if let photo {
                slide(photo).id(photo.id).transition(.opacity)
            }
            VStack {
                HStack {
                    Button { store.stopSlideshow() } label: {
                        Image(systemName: "xmark").font(.system(size: 13, weight: .bold)).foregroundStyle(.white)
                            .frame(width: 36, height: 36).background(.ultraThinMaterial, in: Circle())
                    }
                    .buttonStyle(.plain).keyboardShortcut(.cancelAction)
                    Spacer()
                    if let photo { Text(photo.name).font(.caption).foregroundStyle(.white.opacity(0.7)) }
                    Spacer()
                    arrow("chevron.left", .leftArrow) { store.stepSlide(-1) }
                    arrow("chevron.right", .rightArrow) { store.stepSlide(1) }
                }
                .padding(16)
                Spacer()
            }
        }
        .animation(.easeInOut(duration: 0.8), value: store.slideIndex)
    }

    @ViewBuilder private func slide(_ photo: PhonePhoto) -> some View {
        if let url = store.downloaded[photo.id], let img = store.image(at: url) {
            Image(nsImage: img).resizable().scaledToFit().padding(24)
        } else {
            ZStack {
                if let thumb = photo.thumb { Image(nsImage: thumb).resizable().scaledToFit().blur(radius: 10).opacity(0.6).padding(24) }
                ProgressView().controlSize(.large)
            }
        }
    }

    private func arrow(_ symbol: String, _ key: KeyEquivalent, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol).font(.system(size: 13, weight: .bold)).foregroundStyle(.white)
                .frame(width: 36, height: 36).background(.ultraThinMaterial, in: Circle())
        }
        .buttonStyle(.plain)
        .keyboardShortcut(key, modifiers: [])
    }
}
