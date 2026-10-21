import AppKit
import SwiftUI

// MARK: - Design language for the Phone window

enum Theme {
    static let accent = Color(red: 0.43, green: 0.36, blue: 0.98)
    static let accent2 = Color(red: 0.93, green: 0.36, blue: 0.70)
    static let green = Color(red: 0.20, green: 0.80, blue: 0.52)
    static let red = Color(red: 0.97, green: 0.34, blue: 0.40)
    static let gradient = LinearGradient(colors: [accent, accent2], startPoint: .topLeading, endPoint: .bottomTrailing)
    static let card = Color.primary.opacity(0.055)
    static let stroke = Color.primary.opacity(0.09)
}

extension View {
    /// Soft rounded surface with a hairline border.
    func card(_ radius: CGFloat = 18, fill: Color = Theme.card) -> some View {
        self.background(RoundedRectangle(cornerRadius: radius, style: .continuous).fill(fill))
            .overlay(RoundedRectangle(cornerRadius: radius, style: .continuous).strokeBorder(Theme.stroke))
    }
}

/// Window background: base colour with two soft colour glows.
struct Backdrop: View {
    var body: some View {
        ZStack {
            Color(nsColor: .windowBackgroundColor)
            RadialGradient(colors: [Theme.accent.opacity(0.20), .clear], center: .topLeading, startRadius: 0, endRadius: 560)
            RadialGradient(colors: [Theme.accent2.opacity(0.13), .clear], center: .bottomTrailing, startRadius: 0, endRadius: 620)
        }
        .ignoresSafeArea()
    }
}

/// Lets the content run under a transparent title bar (traffic lights float over the nav rail).
struct WindowChrome: NSViewRepresentable {
    func makeNSView(context: Context) -> NSView {
        let v = NSView()
        DispatchQueue.main.async {
            guard let w = v.window else { return }
            w.titlebarAppearsTransparent = true
            w.titleVisibility = .hidden
            w.styleMask.insert(.fullSizeContentView)
            w.isMovableByWindowBackground = true
        }
        return v
    }
    func updateNSView(_ nsView: NSView, context: Context) {}
}

/// Round avatar with initials on a colour picked from the name.
struct Avatar: View {
    let name: String
    var size: CGFloat = 44

    private static let palettes: [[Color]] = [
        [Color(red: 0.43, green: 0.36, blue: 0.98), Color(red: 0.62, green: 0.50, blue: 1.0)],
        [Color(red: 0.93, green: 0.36, blue: 0.70), Color(red: 1.0, green: 0.55, blue: 0.50)],
        [Color(red: 0.12, green: 0.70, blue: 0.62), Color(red: 0.30, green: 0.85, blue: 0.55)],
        [Color(red: 0.98, green: 0.60, blue: 0.20), Color(red: 1.0, green: 0.78, blue: 0.30)],
        [Color(red: 0.20, green: 0.55, blue: 0.95), Color(red: 0.35, green: 0.78, blue: 1.0)],
        [Color(red: 0.55, green: 0.40, blue: 0.85), Color(red: 0.85, green: 0.45, blue: 0.85)],
    ]

    private var initials: String {
        let letters = name.split(separator: " ").prefix(2).compactMap { $0.first }.map(String.init).joined().uppercased()
        return letters.first?.isLetter == true ? letters : ""
    }

    var body: some View {
        let seed = name.unicodeScalars.reduce(0) { ($0 &* 31 &+ Int($1.value)) & 0xFFFF }
        ZStack {
            Circle().fill(LinearGradient(colors: Self.palettes[seed % Self.palettes.count], startPoint: .topLeading, endPoint: .bottomTrailing))
            if initials.isEmpty {
                Image(systemName: "person.fill").font(.system(size: size * 0.42)).foregroundStyle(.white.opacity(0.9))
            } else {
                Text(initials).font(.system(size: size * 0.38, weight: .semibold, design: .rounded)).foregroundStyle(.white)
            }
        }
        .frame(width: size, height: size)
    }
}

struct EmptyState: View {
    let icon: String
    let title: String
    var subtitle: String = ""

    var body: some View {
        VStack(spacing: 12) {
            ZStack {
                Circle().fill(Theme.gradient.opacity(0.18)).frame(width: 84, height: 84)
                Image(systemName: icon).font(.system(size: 32, weight: .medium)).foregroundStyle(Theme.gradient)
            }
            Text(title).font(.system(size: 17, weight: .semibold, design: .rounded))
            if !subtitle.isEmpty {
                Text(subtitle).font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center).frame(maxWidth: 300)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

struct SearchField: View {
    let placeholder: String
    @Binding var text: String

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
            TextField(placeholder, text: $text).textFieldStyle(.plain)
            if !text.isEmpty {
                Button { text = "" } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary) }.buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 14).padding(.vertical, 9)
        .card(14)
    }
}

// MARK: - Shell: nav rail · top bar · content · notification panel

struct PhoneView: View {
    @ObservedObject var store: PhoneStore
    @ObservedObject var bridge: Bridge
    @ObservedObject private var history = Bridge.shared.history
    @ObservedObject private var batteryWatch = Bridge.shared.batteryWatch

    private let tabs: [(PhoneStore.Tab, String, String)] = [
        (.messages, "Messages", "bubble.left.and.bubble.right.fill"),
        (.photos, "Photos", "photo.on.rectangle.angled"),
        (.calls, "Calls", "phone.fill"),
        (.apps, "Apps", "square.grid.2x2.fill"),
        (.clipboard, "Clipboard", "doc.on.clipboard.fill"),
        (.device, "Device", "iphone.gen3.radiowaves.left.and.right"),
    ]

    private var title: String {
        switch store.tab {
        case .messages: "Messages"
        case .photos: "Photos & Videos"
        case .calls: "Calls"
        case .apps: "Phone Apps"
        case .clipboard: "Clipboard & Notes"
        case .device: "Device & Find My Phone"
        case .settings: "Settings"
        case .notifications: "Notification History"
        }
    }

    var body: some View {
        HStack(spacing: 0) {
            rail
            Rectangle().fill(Theme.stroke).frame(width: 1)
            VStack(spacing: 0) {
                topBar
                content
                    .overlay {
                        if let id = store.viewing, let photo = store.photo(for: id) {
                            PhotoViewer(store: store, photo: photo).transition(.opacity)
                        }
                    }
                    .animation(.easeOut(duration: 0.18), value: store.viewing)
            }
            if store.showPanel {
                Rectangle().fill(Theme.stroke).frame(width: 1)
                NotificationPanel(bridge: bridge, store: store, history: history)
                    .frame(width: 320)
                    .transition(.move(edge: .trailing).combined(with: .opacity))
            }
        }
        .background(Backdrop())
        .background(WindowChrome())
        .overlay {
            if store.paletteOpen { CommandPalette(store: store, bridge: bridge, clips: bridge.clipHistory).transition(.opacity) }
        }
        .background {
            Button("") { store.paletteOpen.toggle() }.keyboardShortcut("k", modifiers: .command).opacity(0).frame(width: 0, height: 0)
        }
        .tint(Theme.accent)
        .animation(.easeOut(duration: 0.2), value: store.showPanel)
        .frame(minWidth: 1020, minHeight: 640)
        .onAppear { store.refresh(store.tab) }
        .onChange(of: store.tab) { _, tab in store.refresh(tab) }
        .onChange(of: bridge.connected) { _, up in if up { store.refresh(store.tab) } }
    }

    // MARK: Rail

    private var rail: some View {
        VStack(spacing: 6) {
            ZStack {
                RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Theme.gradient)
                Image(systemName: "iphone.gen3.radiowaves.left.and.right").font(.system(size: 18, weight: .semibold)).foregroundStyle(.white)
            }
            .frame(width: 44, height: 44)
            .shadow(color: Theme.accent.opacity(0.5), radius: 10, y: 4)
            .padding(.bottom, 14)
            ForEach(tabs, id: \.0) { tab, title, icon in navItem(tab, title, icon) }
            Spacer()
            navItem(.notifications, "History", "clock.arrow.circlepath")
            navItem(.settings, "Settings", "gearshape.fill")
        }
        .padding(.top, 46).padding(.bottom, 14).padding(.horizontal, 10)
        .frame(width: 92)
        .background(.ultraThinMaterial)
    }

    private func navItem(_ tab: PhoneStore.Tab, _ title: String, _ icon: String) -> some View {
        let selected = store.tab == tab
        let unread = tab == .messages ? store.threads.filter { store.isUnread($0) }.count : 0
        return Button { store.tab = tab } label: {
            VStack(spacing: 5) {
                Image(systemName: icon).font(.system(size: 20, weight: .semibold))
                Text(title).font(.system(size: 10.5, weight: .semibold)).lineLimit(1).minimumScaleFactor(0.75)
            }
            .padding(.horizontal, 4)
            .foregroundStyle(selected ? Color.white : Color.secondary)
            .frame(width: 70, height: 56)
            .background {
                if selected {
                    RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Theme.gradient)
                        .shadow(color: Theme.accent.opacity(0.45), radius: 10, y: 4)
                }
            }
            .overlay(alignment: .topTrailing) {
                if unread > 0 {
                    Text("\(min(unread, 99))").font(.system(size: 10, weight: .bold)).foregroundStyle(.white)
                        .padding(.horizontal, 5).padding(.vertical, 2)
                        .background(Capsule().fill(Theme.red)).offset(x: -2, y: 4)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    // MARK: Top bar

    private var topBar: some View {
        HStack(spacing: 12) {
            Text(title).font(.system(size: 26, weight: .bold, design: .rounded))
                .lineLimit(1).minimumScaleFactor(0.55).layoutPriority(-1)
            Spacer(minLength: 8)
            phoneChip
            quick("magnifyingglass", "Search & commands (⌘K)", on: store.paletteOpen) { store.paletteOpen.toggle() }
            quick("arrow.triangle.2.circlepath", bridge.connected ? "Reconnect to phone" : "Find phone again", on: bridge.reconnecting) {
                bridge.reconnect()
            }
            HStack(spacing: 8) {
                quick(bridge.phoneDnd ? "moon.fill" : "moon", "Do Not Disturb", on: bridge.phoneDnd) {
                    bridge.send(["t": "phone_dnd", "on": !bridge.phoneDnd])
                }
                quick("bell.and.waves.left.and.right", bridge.phoneRinging ? "Stop ringing" : "Ring phone", on: bridge.phoneRinging) {
                    bridge.toggleRing()
                }
                quick("square.and.arrow.up", "Send a file to the phone") { bridge.pickFile() }
                quick("rectangle.on.rectangle", "Show phone screen") { bridge.mirror() }
            }
            .disabled(!bridge.connected)
            Rectangle().fill(Theme.stroke).frame(width: 1, height: 24)
            quick("bell.fill", store.showPanel ? "Hide notifications" : "Show notifications", on: store.showPanel) {
                store.showPanel.toggle()
            }
            .overlay(alignment: .topTrailing) {
                if !store.showPanel && !history.feed.isEmpty {
                    Text("\(min(history.feed.count, 99))").font(.system(size: 9.5, weight: .bold)).foregroundStyle(.white)
                        .padding(.horizontal, 5).padding(.vertical, 1.5)
                        .background(Capsule().fill(Theme.red)).offset(x: 5, y: -4)
                }
            }
        }
        .padding(.leading, 26).padding(.trailing, 18).padding(.top, 16).padding(.bottom, 12)
    }

    private var phoneChip: some View {
        HStack(spacing: 10) {
            ZStack {
                RoundedRectangle(cornerRadius: 8, style: .continuous).fill(Theme.gradient)
                Image(systemName: "iphone.gen3").font(.system(size: 13)).foregroundStyle(.white)
            }
            .frame(width: 26, height: 34)
            VStack(alignment: .leading, spacing: 2) {
                Text(bridge.phoneName).font(.system(size: 13, weight: .semibold)).lineLimit(1)
                HStack(spacing: 5) {
                    Circle().fill(bridge.connected ? Theme.green : Color.orange).frame(width: 6, height: 6)
                    Text(bridge.connected ? "Connected" : "Offline").lineLimit(1)
                    if let level = bridge.battery {
                        Image(systemName: bridge.charging ? "battery.100percent.bolt" : "battery.75percent")
                        Text("\(level)%").lineLimit(1)
                        if let eta = bridge.batteryWatch.eta { Text("· \(eta)").lineLimit(1) }
                    }
                }
                .font(.system(size: 11)).foregroundStyle(.secondary)
            }
        }
        .fixedSize()
        .padding(.horizontal, 12).padding(.vertical, 6)
        .card(16)
    }

    private func quick(_ icon: String, _ help: String, on: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon).font(.system(size: 14, weight: .semibold))
                .frame(width: 38, height: 38)
                .foregroundStyle(on ? Color.white : Color.primary)
                .background { Circle().fill(on ? Theme.accent : Theme.card) }
                .overlay(Circle().strokeBorder(Theme.stroke))
        }
        .buttonStyle(.plain)
        .help(help)
    }

    @ViewBuilder private var content: some View {
        if !bridge.connected {
            EmptyState(icon: "iphone.slash", title: "Phone not connected", subtitle: "Open PhoneBridge on your phone — it reconnects by itself.")
        } else {
            switch store.tab {
            case .messages: MessagesView(store: store)
            case .photos: PhotosView(store: store)
            case .calls: CallsView(store: store)
            case .apps: PhoneAppsView(bridge: bridge, streamer: bridge.streamer, store: store)
            case .clipboard: ClipboardView(clips: bridge.clipHistory, store: store, note: bridge.note)
            case .device: DeviceView(store: store, bridge: bridge, battery: bridge.batteryWatch)
            case .settings:
                SettingsView(bridge: bridge, store: store, rules: bridge.rules, speech: bridge.speech, battery: bridge.batteryWatch,
                             focus: bridge.focusLink, history: bridge.history, guardian: bridge.guardian)
            case .notifications: HistoryView(history: bridge.history)
            }
        }
    }
}

// MARK: - Live notifications panel

struct NotificationPanel: View {
    @ObservedObject var bridge: Bridge
    @ObservedObject var store: PhoneStore
    @ObservedObject var history: NotificationHistory
    @ObservedObject private var ui = UIState.shared

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                Text("Notifications").font(.system(size: 17, weight: .bold, design: .rounded))
                if !history.feed.isEmpty {
                    Text("\(history.feed.count)").font(.system(size: 11, weight: .bold)).foregroundStyle(.white)
                        .padding(.horizontal, 7).padding(.vertical, 2).background(Capsule().fill(Theme.gradient))
                }
                Spacer()
                Menu {
                    Button("Clear all") { clearAll() }
                    Button("Notification history") { store.tab = .notifications }
                } label: { Image(systemName: "ellipsis.circle").font(.system(size: 16)) }
                .menuStyle(.borderlessButton).fixedSize()
            }
            .padding(.horizontal, 16).padding(.top, 18).padding(.bottom, 12)

            if history.feed.isEmpty {
                EmptyState(icon: "bell.slash.fill", title: "All caught up", subtitle: "New phone notifications show up here.")
            } else {
                ScrollView {
                    LazyVStack(spacing: 10) {
                        ForEach(history.feed) { item in NotificationCard(item: item, bridge: bridge, ui: ui) }
                    }
                    .padding(.horizontal, 14).padding(.bottom, 14)
                }
            }
        }
        .background(Color.primary.opacity(0.03))
    }

    private func clearAll() {
        for item in history.feed {
            if let key = item.key { bridge.send(["t": "dismiss", "id": key]) }
        }
        history.feed.compactMap(\.key).forEach(history.markRemoved)
    }
}

struct NotificationCard: View {
    let item: NotificationHistory.Item
    @ObservedObject var bridge: Bridge
    @ObservedObject var ui: UIState

    private var draft: Binding<String> {
        Binding(get: { ui.replyDrafts[item.key ?? ""] ?? "" }, set: { ui.replyDrafts[item.key ?? ""] = $0 })
    }

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Avatar(name: item.app, size: 32)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(item.app).font(.system(size: 11.5, weight: .semibold)).foregroundStyle(.secondary)
                    Text(item.date, style: .time).font(.system(size: 11)).foregroundStyle(.tertiary)
                    Spacer()
                    Button {
                        if let key = item.key {
                            bridge.send(["t": "dismiss", "id": key])
                            bridge.history.markRemoved(key)
                        }
                    } label: { Image(systemName: "xmark").font(.system(size: 9.5, weight: .bold)).foregroundStyle(.secondary) }
                    .buttonStyle(.plain)
                }
                if !item.title.isEmpty { Text(item.title).font(.system(size: 13.5, weight: .semibold)).lineLimit(1) }
                Text(item.text).font(.system(size: 12.5)).foregroundStyle(.primary.opacity(0.85)).lineLimit(4).textSelection(.enabled)
                if item.canReply, let key = item.key {
                    HStack(spacing: 6) {
                        TextField("Reply", text: draft).textFieldStyle(.plain).onSubmit { send(key) }
                        Button { send(key) } label: {
                            Image(systemName: "arrow.up.circle.fill").font(.system(size: 20)).foregroundStyle(Theme.gradient)
                        }
                        .buttonStyle(.plain)
                        .disabled(draft.wrappedValue.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                    .padding(.leading, 12).padding(.trailing, 4).padding(.vertical, 4)
                    .card(16, fill: Color.primary.opacity(0.06))
                    .padding(.top, 5)
                }
            }
        }
        .padding(12)
        .card(18)
    }

    private func send(_ key: String) {
        let text = draft.wrappedValue.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else { return }
        bridge.send(["t": "reply", "id": key, "text": text])
        ui.replyDrafts[key] = nil
    }
}

// MARK: - Messages

struct MessagesView: View {
    @ObservedObject var store: PhoneStore

    private static let stampFormat: DateFormatter = {
        let f = DateFormatter()
        f.dateStyle = .medium
        f.timeStyle = .short
        f.doesRelativeDateFormatting = true
        return f
    }()

    private var threads: [SmsThread] {
        let q = store.threadSearch.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return store.threads }
        return store.threads.filter { $0.title.localizedCaseInsensitiveContains(q) || $0.snippet.localizedCaseInsensitiveContains(q) }
    }

    var body: some View {
        HStack(spacing: 14) {
            VStack(spacing: 10) {
                SearchField(placeholder: "Search messages", text: $store.threadSearch)
                ScrollView {
                    LazyVStack(spacing: 4) { ForEach(threads) { t in threadRow(t) } }
                }
            }
            .frame(width: 320)
            chat.card(22)
        }
        .padding(.leading, 20).padding(.trailing, 18).padding(.bottom, 18)
    }

    private func threadRow(_ t: SmsThread) -> some View {
        let selected = store.selectedThread == t.id
        let unread = store.isUnread(t)
        return Button { store.selectedThread = t.id; store.markRead(t) } label: {
            HStack(spacing: 12) {
                Avatar(name: t.title, size: 46)
                VStack(alignment: .leading, spacing: 3) {
                    HStack {
                        Text(t.title).font(.system(size: 14, weight: unread ? .bold : .semibold)).lineLimit(1)
                        Spacer(minLength: 4)
                        Text(relative.localizedString(for: t.date, relativeTo: Date())).font(.system(size: 11)).foregroundStyle(.secondary)
                    }
                    HStack(spacing: 6) {
                        Text(t.snippet).font(.system(size: 12.5)).foregroundStyle(unread ? .primary : .secondary).lineLimit(2)
                        Spacer(minLength: 0)
                        if unread { Circle().fill(Theme.gradient).frame(width: 9, height: 9) }
                    }
                }
            }
            .padding(10)
            .background {
                if selected {
                    RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Theme.accent.opacity(0.16))
                }
            }
            .overlay { if selected { RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(Theme.accent.opacity(0.4)) } }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder private var chat: some View {
        if let thread = store.currentThread {
            VStack(spacing: 0) {
                HStack(spacing: 12) {
                    Avatar(name: thread.title, size: 40)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(thread.title).font(.system(size: 16, weight: .bold, design: .rounded))
                        if thread.name != nil { Text(thread.address).font(.caption).foregroundStyle(.secondary) }
                    }
                    Spacer()
                    Button { store.dial(thread.address) } label: {
                        Image(systemName: "phone.fill").font(.system(size: 14, weight: .semibold)).foregroundStyle(.white)
                            .frame(width: 38, height: 38).background(Circle().fill(Theme.green))
                    }
                    .buttonStyle(.plain)
                    .help("Call")
                }
                .padding(.horizontal, 18).padding(.vertical, 12)
                Divider().opacity(0.5)
                messageList
                quickReplyChips
                composer
            }
        } else {
            EmptyState(icon: "bubble.left.and.bubble.right.fill", title: "Pick a conversation", subtitle: "Your phone's messages, with the same people and history.")
        }
    }

    private var messageList: some View {
        let ordered = Array(store.messages.reversed())
        return ScrollView {
            LazyVStack(spacing: 4) {
                ForEach(Array(ordered.enumerated()), id: \.element.id) { i, msg in
                    if i == 0 || msg.date.timeIntervalSince(ordered[i - 1].date) > 1800 {
                        Text(Self.stampFormat.string(from: msg.date)).font(.system(size: 11, weight: .medium)).foregroundStyle(.secondary)
                            .padding(.vertical, 10)
                    }
                    bubble(msg)
                }
            }
            .padding(.horizontal, 18).padding(.vertical, 12)
        }
        .defaultScrollAnchor(.bottom)
    }

    private func bubble(_ msg: SmsMessage) -> some View {
        HStack {
            if msg.mine { Spacer(minLength: 90) }
            Text(msg.body)
                .font(.system(size: 14))
                .textSelection(.enabled)
                .contextMenu {
                    Button("Read aloud") { store.speak(msg.body) }
                    Button("Copy") {
                        NSPasteboard.general.clearContents()
                        NSPasteboard.general.setString(msg.body, forType: .string)
                    }
                }
                .foregroundStyle(msg.mine ? Color.white : Color.primary)
                .padding(.horizontal, 14).padding(.vertical, 9)
                .background { bubbleShape(msg.mine) }
            if !msg.mine { Spacer(minLength: 90) }
        }
    }

    @ViewBuilder private func bubbleShape(_ mine: Bool) -> some View {
        let shape = UnevenRoundedRectangle(cornerRadii: RectangleCornerRadii(topLeading: 20, bottomLeading: mine ? 20 : 6,
                                                                              bottomTrailing: mine ? 6 : 20, topTrailing: 20), style: .continuous)
        if mine { shape.fill(Theme.gradient) } else { shape.fill(Color.primary.opacity(0.09)) }
    }

    private var quickReplyChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(store.quickReplies, id: \.self) { r in
                    Button { store.draft = r } label: {
                        Text(r).font(.system(size: 12, weight: .medium)).lineLimit(1)
                            .padding(.horizontal, 12).padding(.vertical, 6)
                            .background(Capsule().fill(Theme.accent.opacity(0.14)))
                    }
                    .buttonStyle(.plain)
                    .contextMenu { Button("Remove", role: .destructive) { store.quickReplies.removeAll { $0 == r } } }
                }
                Button {
                    let t = store.draft.trimmingCharacters(in: .whitespaces)
                    if !t.isEmpty && !store.quickReplies.contains(t) { store.quickReplies.append(t) }
                } label: {
                    Image(systemName: "plus").font(.system(size: 11, weight: .bold)).frame(width: 28, height: 28).card(14)
                }
                .buttonStyle(.plain)
                .help("Save what you've typed as a quick reply")
            }
            .padding(.horizontal, 16)
        }
        .padding(.top, 6)
    }

    private var composer: some View {
        let empty = store.draft.trimmingCharacters(in: .whitespaces).isEmpty
        return HStack(spacing: 10) {
            TextField("Message", text: $store.draft, axis: .vertical)
                .textFieldStyle(.plain).font(.system(size: 14)).lineLimit(1...5)
                .onSubmit { store.sendDraft() }
            Button { store.sendDraft() } label: {
                Image(systemName: "arrow.up").font(.system(size: 14, weight: .bold)).foregroundStyle(.white)
                    .frame(width: 34, height: 34)
                    .background { Circle().fill(empty ? AnyShapeStyle(Color.primary.opacity(0.2)) : AnyShapeStyle(Theme.gradient)) }
            }
            .buttonStyle(.plain)
            .disabled(empty)
        }
        .padding(.leading, 18).padding(.trailing, 6).padding(.vertical, 6)
        .card(24, fill: Color.primary.opacity(0.06))
        .padding(14)
    }
}

// MARK: - Calls

struct CallsView: View {
    @ObservedObject var store: PhoneStore

    private let keys: [[String]] = [["1", "2", "3"], ["4", "5", "6"], ["7", "8", "9"], ["*", "0", "#"]]

    private var matches: [Contact] {
        let q = store.contactSearch.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return store.contacts }
        let digits = q.filter(\.isNumber)
        return store.contacts.filter {
            $0.name.localizedCaseInsensitiveContains(q) || (!digits.isEmpty && $0.number.filter(\.isNumber).contains(digits))
        }
    }

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            VStack(spacing: 14) {
                dialer
                contacts
            }
            .frame(width: 330)
            recents
        }
        .padding(.leading, 20).padding(.trailing, 18).padding(.bottom, 18)
    }

    // MARK: Dialer

    private var dialer: some View {
        VStack(spacing: 12) {
            HStack {
                TextField("Enter a number", text: $store.dialNumber)
                    .textFieldStyle(.plain).font(.system(size: 24, weight: .semibold, design: .rounded))
                    .onSubmit { store.dial(store.dialNumber) }
                if !store.dialNumber.isEmpty {
                    Button { store.dialNumber.removeLast() } label: { Image(systemName: "delete.left.fill").font(.system(size: 18)).foregroundStyle(.secondary) }
                        .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 6)
            VStack(spacing: 8) {
                ForEach(keys, id: \.self) { row in
                    HStack(spacing: 8) {
                        ForEach(row, id: \.self) { k in
                            Button { store.dialNumber += k } label: {
                                Text(k).font(.system(size: 20, weight: .medium, design: .rounded))
                                    .frame(maxWidth: .infinity).frame(height: 44)
                                    .card(14, fill: Color.primary.opacity(0.06))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
            Button { store.dial(store.dialNumber) } label: {
                Label("Call", systemImage: "phone.fill").font(.system(size: 15, weight: .semibold)).foregroundStyle(.white)
                    .frame(maxWidth: .infinity).frame(height: 44)
                    .background(Capsule().fill(Theme.green))
                    .opacity(store.dialNumber.isEmpty ? 0.5 : 1)
            }
            .buttonStyle(.plain)
            .disabled(store.dialNumber.isEmpty)
        }
        .padding(14)
        .card(22)
    }

    private var contacts: some View {
        VStack(spacing: 8) {
            SearchField(placeholder: "Search contacts", text: $store.contactSearch)
            ScrollView {
                LazyVStack(spacing: 2) {
                    ForEach(matches) { c in
                        Button { store.dial(c.number) } label: {
                            HStack(spacing: 10) {
                                Avatar(name: c.name, size: 34)
                                VStack(alignment: .leading, spacing: 1) {
                                    Text(c.name).font(.system(size: 13.5, weight: .semibold)).lineLimit(1)
                                    Text(c.number).font(.system(size: 11.5)).foregroundStyle(.secondary)
                                }
                                Spacer()
                                Image(systemName: "phone.fill").font(.system(size: 12)).foregroundStyle(Theme.green)
                            }
                            .padding(.horizontal, 8).padding(.vertical, 5)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
        .padding(10)
        .card(22)
        .frame(maxHeight: .infinity)
    }

    // MARK: Recents

    private var recents: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Recent").font(.system(size: 16, weight: .bold, design: .rounded)).padding(.horizontal, 18).padding(.top, 16).padding(.bottom, 8)
            if store.calls.isEmpty {
                EmptyState(icon: "phone.badge.waveform.fill", title: "No calls yet")
            } else {
                ScrollView {
                    LazyVStack(spacing: 2) {
                        ForEach(store.calls) { call in callRow(call) }
                    }
                    .padding(.horizontal, 10).padding(.bottom, 10)
                }
            }
        }
        .card(22)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func callRow(_ call: CallEntry) -> some View {
        let bad = call.type == "missed" || call.type == "rejected"
        return Button { store.dial(call.number) } label: {
            HStack(spacing: 12) {
                ZStack {
                    Circle().fill((bad ? Theme.red : call.type == "out" ? Theme.accent : Theme.green).opacity(0.16)).frame(width: 40, height: 40)
                    Image(systemName: icon(call.type)).font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(bad ? Theme.red : call.type == "out" ? Theme.accent : Theme.green)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(call.name ?? call.number).font(.system(size: 14, weight: .semibold)).foregroundStyle(bad ? Theme.red : .primary).lineLimit(1)
                    Text(detail(call)).font(.system(size: 11.5)).foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: "phone.fill").font(.system(size: 13)).foregroundStyle(.secondary)
            }
            .padding(.horizontal, 8).padding(.vertical, 7)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func icon(_ type: String) -> String {
        switch type {
        case "in": "phone.arrow.down.left"
        case "out": "phone.arrow.up.right"
        case "missed", "rejected": "phone.down.fill"
        default: "phone"
        }
    }

    private func detail(_ c: CallEntry) -> String {
        var parts = [relative.localizedString(for: c.date, relativeTo: Date())]
        if c.name != nil { parts.insert(c.number, at: 0) }
        if c.duration > 0 { parts.append("\(c.duration / 60)m \(c.duration % 60)s") }
        return parts.joined(separator: " · ")
    }
}

// MARK: - Apps on the phone

struct PhoneAppsView: View {
    @ObservedObject var bridge: Bridge
    @ObservedObject var streamer: AppStreamer
    @ObservedObject var store: PhoneStore
    @ObservedObject private var ui = UIState.shared

    private var filtered: [PhoneApp] {
        ui.search.isEmpty ? bridge.apps : bridge.apps.filter { $0.name.localizedCaseInsensitiveContains(ui.search) }
    }

    var body: some View {
        VStack(spacing: 14) {
            HStack(spacing: 12) {
                SearchField(placeholder: "Search \(bridge.apps.count) apps", text: $ui.search).frame(maxWidth: 360)
                Button { bridge.requestApps() } label: {
                    Image(systemName: "arrow.clockwise").font(.system(size: 13, weight: .semibold))
                        .frame(width: 36, height: 36).card(12)
                }
                .buttonStyle(.plain).help("Refresh")
                Spacer()
            }
            if streamer.serial == nil {
                HStack(spacing: 12) {
                    Image(systemName: "sparkles").font(.title3).foregroundStyle(Theme.gradient)
                    Text("Click an app to open it in its own window here. Your phone asks to start casting — tap Start (pick “One app” or “Entire screen”) and keep it unlocked. Control it with your mouse and keyboard.")
                        .font(.callout).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                }
                .padding(14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .card(18, fill: Theme.accent.opacity(0.10))
            }
            if bridge.apps.isEmpty {
                EmptyState(icon: "square.grid.2x2.fill", title: "Loading apps…", subtitle: "Fetching the app list from your phone.")
            } else {
                ScrollView {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 112, maximum: 150), spacing: 12)], spacing: 12) {
                        ForEach(filtered) { app in tile(app) }
                    }
                    .padding(.bottom, 8)
                }
            }
        }
        .padding(.leading, 24).padding(.trailing, 20).padding(.bottom, 18)
        .onAppear { if bridge.apps.isEmpty { bridge.requestApps() } }
    }

    private func tile(_ app: PhoneApp) -> some View {
        let hovering = store.hoverKey == app.id
        return Button { bridge.openApp(app) } label: {
            VStack(spacing: 10) {
                if let icon = app.icon {
                    Image(nsImage: icon).resizable().frame(width: 58, height: 58)
                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                } else {
                    Avatar(name: app.name, size: 58)
                }
                Text(app.name).font(.system(size: 12.5, weight: .medium)).lineLimit(1)
            }
            .frame(maxWidth: .infinity).padding(.vertical, 16).padding(.horizontal, 8)
            .card(20, fill: hovering ? Theme.accent.opacity(0.14) : Theme.card)
            .overlay { if hovering { RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(Theme.accent.opacity(0.5)) } }
            .scaleEffect(hovering ? 1.04 : 1)
            .shadow(color: .black.opacity(hovering ? 0.25 : 0), radius: 10, y: 5)
            .animation(.easeOut(duration: 0.14), value: hovering)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { inside in
            if inside { store.hoverKey = app.id } else if store.hoverKey == app.id { store.hoverKey = nil }
        }
        .help("Open \(app.name) on the Mac")
    }
}

// MARK: - Notification history

struct HistoryView: View {
    @ObservedObject var history: NotificationHistory

    private static let time: DateFormatter = {
        let f = DateFormatter()
        f.dateStyle = .short
        f.timeStyle = .short
        f.doesRelativeDateFormatting = true
        return f
    }()

    var body: some View {
        VStack(spacing: 12) {
            HStack(spacing: 12) {
                SearchField(placeholder: "Search notifications", text: $history.search).frame(maxWidth: 420)
                Spacer()
                Button { history.clear() } label: {
                    Label("Clear all", systemImage: "trash").font(.system(size: 12.5, weight: .semibold))
                        .padding(.horizontal, 14).padding(.vertical, 9).card(14)
                }
                .buttonStyle(.plain)
            }
            if history.filtered.isEmpty {
                EmptyState(icon: "clock.arrow.circlepath", title: "Nothing here yet", subtitle: "Every notification from your phone is kept here, encrypted on this Mac.")
            } else {
                ScrollView {
                    LazyVStack(spacing: 8) {
                        ForEach(history.filtered) { item in row(item) }
                    }
                }
            }
        }
        .padding(.leading, 24).padding(.trailing, 20).padding(.bottom, 18)
    }

    private func row(_ item: NotificationHistory.Item) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Avatar(name: item.app, size: 38)
            VStack(alignment: .leading, spacing: 3) {
                HStack {
                    Text(item.app).font(.system(size: 12, weight: .semibold)).foregroundStyle(.secondary)
                    Spacer()
                    Text(Self.time.string(from: item.date)).font(.system(size: 11.5)).foregroundStyle(.tertiary)
                }
                if !item.title.isEmpty { Text(item.title).font(.system(size: 14, weight: .semibold)) }
                Text(item.text).font(.system(size: 13)).foregroundStyle(.primary.opacity(0.85)).textSelection(.enabled)
            }
        }
        .padding(14)
        .card(18)
    }
}
