import AppKit
import SwiftUI

// MARK: - Main window

struct MainView: View {
    @ObservedObject var bridge: Bridge
    @ObservedObject var streamer: AppStreamer
    @ObservedObject private var presence = Bridge.shared.presence
    @ObservedObject private var mic = Bridge.shared.mic
    @ObservedObject private var screenshots = Bridge.shared.screenshots
    @ObservedObject private var ui = UIState.shared
    @ObservedObject private var themes = ThemeStore.shared
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        ZStack {
            if bridge.connected {
                if ui.showSettings { settingsPane } else { mainContent }
            } else {
                Backdrop()
                DiscoveryView(bridge: bridge).frame(width: 500, height: 720)
            }
        }
        .coordinateSpace(name: "fx-main")
        .environment(\.fxSpace, "main")
        .overlay { FxOverlay(space: "main") }
        .onAppear { if bridge.connected { FxCenter.shared.fireOpenOnce(space: "main") } }   // never while still searching
        .frame(width: 500, height: 720)
        .onAppear { ui.openWindow = openWindow }
    }

    private var mainContent: some View {
        ScrollView(showsIndicators: false) {
            VStack(spacing: 16) {
                brandHeader
                ThemeHero(theme: themes.current)
                    .overlay(alignment: .bottom) {
                        LinearGradient(colors: [.clear, Color.black.opacity(0.6)], startPoint: .top, endPoint: .bottom)
                            .frame(height: 96).clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
                    }
                    .overlay(alignment: .bottomLeading) {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(bridge.phoneName).font(.system(size: 22, weight: .regular, design: .serif))
                            Text(heroLine).font(.system(size: 12)).foregroundStyle(.white.opacity(0.7))
                        }
                        .padding(18)
                    }
                if let np = bridge.nowPlaying { NowPlayingCard(np: np, bridge: bridge) }
                actionGrid
                Button { withAnimation(.easeOut(duration: 0.2)) { ui.showSettings = true }; bridge.phone.refresh(.settings) } label: {
                    Label("All settings", systemImage: "slider.horizontal.3").frame(maxWidth: .infinity)
                }
                caption("Closing this window keeps PhoneBridge running in the menu bar. Drop files on the menu bar icon to send them to your phone.")
            }
            .padding(20)
            .buttonStyle(PillButtonStyle())
        }
        .frame(width: 500, height: 720)
        .background(themes.current.bg)
        .preferredColorScheme(.dark)
        .tint(Theme.accent)
        .id(themes.current.id)
        .overlay { ThemeBurstView() }
        .onAppear { ui.openWindow = openWindow }
    }

    /// Settings shown right here in the main window (no second window to open or lose).
    private var settingsPane: some View {
        VStack(spacing: 0) {
            HStack {
                Button { withAnimation(.easeOut(duration: 0.2)) { ui.showSettings = false } } label: {
                    Label("Back", systemImage: "chevron.left")
                }
                .buttonStyle(PillButtonStyle())
                Spacer()
                Text("Settings").font(.system(size: 17, weight: .semibold))
                Spacer()
                Button { openWindow(id: "phone") } label: { Label("Phone", systemImage: "iphone.gen3") }
                    .buttonStyle(PillButtonStyle())
            }
            .padding(.horizontal, 20).padding(.top, 16).padding(.bottom, 8)
            SettingsView(bridge: bridge, store: bridge.phone, rules: bridge.rules, speech: bridge.speech, battery: bridge.batteryWatch,
                         focus: bridge.focusLink, history: bridge.history)
        }
        .frame(width: 500, height: 720)
        .background(themes.current.bg)
        .preferredColorScheme(.dark)
        .tint(Theme.accent)
        .id(themes.current.id)
    }

    private func caption(_ s: String) -> some View {
        Text(s).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
    }

    private var heroLine: String {
        var parts = ["Connected"]
        if let level = bridge.battery { parts.append("\(level)%\(bridge.charging ? " · charging" : "")") }
        return parts.joined(separator: " · ")
    }

    private var brandHeader: some View {
        VStack(spacing: 10) {
            ThemeGlyph(theme: themes.current).frame(width: 34, height: 34)
                .frame(width: 68, height: 68)
                .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(Color.white.opacity(0.05)))
                .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(Color.white.opacity(0.12)))
            Text("PhoneBridge").font(.system(size: 28, weight: .regular, design: .serif))
            HStack(spacing: 6) {
                Circle().fill(bridge.connected ? Theme.green : Color.orange).frame(width: 7, height: 7)
                Text(bridge.status).font(.system(size: 12.5)).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 8)
    }

    private var actionGrid: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: 3), spacing: 10) {
            ActionTile(icon: "iphone.gen3", title: "Phone", primary: true) { openWindow(id: "phone") }
            ActionTile(icon: "keyboard", title: "Type") { openWindow(id: "type") }
            ActionTile(icon: "bell.and.waves.left.and.right", title: bridge.phoneRinging ? "Stop ring" : "Ring phone", active: bridge.phoneRinging) { bridge.toggleRing() }
            ActionTile(icon: "square.and.arrow.up", title: "Send file") { bridge.pickFile() }
            ActionTile(icon: "safari", title: "Browser tab") { bridge.mac.sendTabToPhone() }
            ActionTile(icon: "message", title: "SMS") { bridge.composeSMS() }
        }
    }
}

struct NowPlayingCard: View {
    let np: NowPlaying
    @ObservedObject var bridge: Bridge

    var body: some View {
        HStack(spacing: 12) {
            Group {
                if let art = np.art { Image(nsImage: art).resizable().scaledToFill() }
                else { Image(systemName: "music.note").font(.title).frame(maxWidth: .infinity, maxHeight: .infinity).background(.quaternary) }
            }
            .frame(width: 54, height: 54)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            VStack(alignment: .leading, spacing: 2) {
                Text(np.title).font(.system(size: 14.5, weight: .semibold)).lineLimit(1)
                Text(np.artist.isEmpty ? np.app : "\(np.artist) · \(np.app)").font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer()
            Button { bridge.mediaCommand("prev") } label: { Image(systemName: "backward.fill") }.buttonStyle(.plain)
            Button { bridge.mediaCommand("play_pause") } label: {
                Image(systemName: np.playing ? "pause.fill" : "play.fill").font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.onAccent)
                    .frame(width: 38, height: 38).background(Circle().fill(Theme.accent))
            }.buttonStyle(.plain)
            Button { bridge.mediaCommand("next") } label: { Image(systemName: "forward.fill") }.buttonStyle(.plain)
        }
        .padding(12)
        .card(18)
    }
}

// MARK: - Type on phone

struct TypeOnPhoneView: View {
    @ObservedObject var bridge: Bridge
    @ObservedObject private var ui = UIState.shared

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Tap a text box on your phone, then type here — it appears in that box.")
                .font(.callout).foregroundStyle(.secondary)
            TextEditor(text: $ui.phoneText)
                .font(.body)
                .frame(minHeight: 160)
                .overlay(RoundedRectangle(cornerRadius: 6).strokeBorder(.quaternary))
                .onChange(of: ui.phoneText) { _, text in bridge.typeOnPhone(text) }
            HStack {
                Text("Needs “Type from Mac” switched on in PhoneBridge on the phone.").font(.caption).foregroundStyle(.secondary)
                Spacer()
                Button("Clear") { ui.phoneText = "" }
                Button("Send / Enter") {
                    bridge.send(["t": "phone_enter"])
                    ui.phoneText = ""
                }
                .buttonStyle(.borderedProminent)
                .keyboardShortcut(.return, modifiers: .command)
            }
        }
        .padding(16)
        .frame(width: 460)
        .disabled(!bridge.connected)
    }
}

// MARK: - One-time Wireless debugging pairing

struct StreamingSetupView: View {
    @ObservedObject var streamer: AppStreamer
    let done: () -> Void
    @ObservedObject private var ui = UIState.shared

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Set up phone apps on your Mac").font(.title3.bold())
            VStack(alignment: .leading, spacing: 8) {
                step(1, "Connect the phone to Wi-Fi (the same network as this Mac). It won't work while the phone is sharing a hotspot.")
                step(2, "Phone: Settings → About phone → Software information → tap Build number 7 times.")
                step(3, "Phone: Settings → Developer options → turn on Wireless debugging. If it won't turn on, switch off Auto Blocker (Settings → Security and privacy).")
                step(4, "Tap Wireless debugging → Pair device with pairing code, and enter what it shows below.")
                step(5, "That's it — PhoneBridge then switches Developer options off, and on again only while you use a phone app on the Mac.")
            }
            .font(.callout)
            .fixedSize(horizontal: false, vertical: true)

            Grid(alignment: .leading, horizontalSpacing: 10, verticalSpacing: 8) {
                GridRow {
                    Text("IP address & port")
                    TextField("192.168.1.23:37199", text: $ui.hostPort).textFieldStyle(.roundedBorder)
                }
                GridRow {
                    Text("Pairing code")
                    TextField("123456", text: $ui.code).textFieldStyle(.roundedBorder)
                }
            }

            if let msg = streamer.pairMessage {
                Text(msg).font(.callout).foregroundStyle(.secondary).textSelection(.enabled)
                    .fixedSize(horizontal: false, vertical: true)
            }

            HStack {
                if case .ready = streamer.status {
                    Label("Connected", systemImage: "checkmark.circle.fill").foregroundStyle(.green)
                }
                Spacer()
                Button("Close") { done() }
                Button("Pair") { streamer.pair(hostPort: ui.hostPort, code: ui.code) }
                    .buttonStyle(.borderedProminent)
                    .disabled(streamer.pairing)
                    .keyboardShortcut(.defaultAction)
            }
        }
        .padding(22)
        .frame(width: 480)
    }

    private func step(_ n: Int, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Text("\(n)").font(.callout.bold()).frame(width: 18, height: 18)
                .background(Circle().fill(Color.accentColor.opacity(0.2)))
            Text(text)
        }
    }
}

// MARK: - Apps grid

struct AppsView: View {
    @ObservedObject var bridge: Bridge
    @ObservedObject var streamer: AppStreamer
    @ObservedObject private var ui = UIState.shared

    private var filtered: [PhoneApp] {
        ui.search.isEmpty ? bridge.apps : bridge.apps.filter { $0.name.localizedCaseInsensitiveContains(ui.search) }
    }

    var body: some View {
        Group {
            if streamer.serial == nil {
                ContentUnavailableView("Phone apps aren't set up yet", systemImage: "apps.iphone",
                                       description: Text("Open the PhoneBridge window and choose Set up… under phone apps."))
            } else if bridge.apps.isEmpty {
                ProgressView("Loading apps from your phone…")
            } else {
                ScrollView {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 92), spacing: 8)], spacing: 14) {
                        ForEach(filtered) { app in
                            Button { streamer.open(app) } label: {
                                VStack(spacing: 6) {
                                    if let icon = app.icon {
                                        Image(nsImage: icon).resizable().frame(width: 52, height: 52)
                                    } else {
                                        RoundedRectangle(cornerRadius: 12).fill(.quaternary).frame(width: 52, height: 52)
                                    }
                                    Text(app.name).font(.caption).lineLimit(2).multilineTextAlignment(.center)
                                        .frame(height: 30, alignment: .top)
                                }
                                .frame(width: 88)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(16)
                }
            }
        }
        .searchable(text: $ui.search, placement: .toolbar, prompt: "Search apps")
        .frame(minWidth: 420, minHeight: 360)
        .onAppear { bridge.requestApps() }
    }
}

// MARK: - Menu bar menu

struct MenuContent: View {
    @ObservedObject var bridge: Bridge
    @ObservedObject var streamer: AppStreamer

    var body: some View {
        Text(bridge.status)
        if bridge.connected, let level = bridge.battery {
            Text("Battery \(level)%\(bridge.charging ? " — charging" : "")")
        }
        if let np = bridge.nowPlaying {
            Divider()
            Text("\(np.playing ? "▶︎" : "⏸") \(np.title)")
            Button(np.playing ? "Pause" : "Play") { bridge.mediaCommand("play_pause") }
            Button("Next track") { bridge.mediaCommand("next") }
        }
        Divider()
        Button("Open PhoneBridge") { UIState.shared.show("main") }
        Button("Messages, Photos & Calls…") { UIState.shared.show("phone") }
            .disabled(!bridge.connected)
        Button("Type on phone…") { UIState.shared.show("type") }
            .disabled(!bridge.connected)
        Button("Send browser tab to phone") { bridge.mac.sendTabToPhone() }
            .disabled(!bridge.connected)

        Button("Show phone screen") { bridge.mirror() }
            .disabled(!bridge.connected)
        Divider()
        Button(bridge.phoneRinging ? "Stop ringing" : "Ring phone") { bridge.toggleRing() }
            .disabled(!bridge.connected)
        Button("Send file to phone…") { bridge.pickFile() }
            .disabled(!bridge.connected)
        Button("Send SMS…") { bridge.composeSMS() }
            .disabled(!bridge.connected)
        Divider()
        Button("Quit PhoneBridge") { NSApp.terminate(nil) }
            .keyboardShortcut("q")
    }
}

// MARK: - Notification history (tab in the Phone window)
