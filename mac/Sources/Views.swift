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
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                header
                if !bridge.connected {
                    Text("Open PhoneBridge on your phone. The phone and Mac must be on the same Wi-Fi (or the Mac on the phone's hotspot). When both show a 6-digit code, check they match and tap Pair on both.")
                        .font(.callout).foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if let np = bridge.nowPlaying { NowPlayingCard(np: np, bridge: bridge) }
                actions
                section("Lock when my phone walks away", "lock") { lockSection }
                section("Phone apps & screen", "apps.iphone") { appsSection }
                section("Phone as microphone", "mic") { micSection }
                section("Screenshots", "camera.viewfinder") {
                    Toggle("Send new Mac screenshots to the phone", isOn: $screenshots.enabled)
                    caption("Phone screenshots always arrive here automatically (copied, and saved in Downloads).")
                }
                section("Away from home", "globe") {
                    caption("Install Tailscale (free) on the Mac and the phone and sign in with the same account. PhoneBridge then reconnects over it whenever you're not on the same Wi-Fi — college, office, anywhere.")
                    Button("Get Tailscale") { NSWorkspace.shared.open(URL(string: "https://tailscale.com/download")!) }
                }
                Divider()
                HStack {
                    Toggle("Open at login", isOn: $ui.openAtLogin)
                    Spacer()
                    Button("Forget phone") { bridge.forget() }
                }
                caption("Closing this window keeps PhoneBridge running in the menu bar. Drop files on the menu bar icon to send them to your phone.")
            }
            .padding(20)
        }
        .frame(width: 500, height: 720)
        .onAppear { ui.openWindow = openWindow }
        .sheet(isPresented: $ui.showSetup) { StreamingSetupView(streamer: streamer, done: { ui.showSetup = false }) }
    }

    private var header: some View {
        HStack(spacing: 14) {
            Image(nsImage: NSApp.applicationIconImage).resizable().frame(width: 64, height: 64)
            VStack(alignment: .leading, spacing: 4) {
                Text("PhoneBridge").font(.title2.bold())
                HStack(spacing: 6) {
                    Circle().fill(bridge.connected ? Color.green : Color.orange).frame(width: 9, height: 9)
                    Text(bridge.status).foregroundStyle(.secondary)
                }
                if bridge.connected, let level = bridge.battery {
                    Text("Phone battery \(level)%\(bridge.charging ? " · charging" : "")")
                        .font(.callout).foregroundStyle(.secondary)
                }
            }
        }
    }

    private var actions: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Button { openWindow(id: "phone") } label: { Label("Messages · Photos · Calls", systemImage: "iphone") }
                    .buttonStyle(.borderedProminent)
                Button { openWindow(id: "type") } label: { Label("Type on phone", systemImage: "keyboard") }
            }
            HStack(spacing: 8) {
                Button(bridge.phoneRinging ? "Stop ringing" : "Ring phone") { bridge.toggleRing() }
                Button("Send file…") { bridge.pickFile() }
                Button("Send browser tab") { bridge.mac.sendTabToPhone() }
                Button("SMS…") { bridge.composeSMS() }
            }
        }
        .disabled(!bridge.connected)
    }

    @ViewBuilder private var lockSection: some View {
        Toggle(isOn: $presence.enabled) {
            Text(presence.status).foregroundStyle(.secondary)
        }
        if presence.enabled {
            Picker("Lock when phone is", selection: $presence.sensitivity) {
                ForEach(Presence.Sensitivity.allCases) { Text($0.label).tag($0) }
            }
            caption("Locks after 20 s without keyboard/mouse use while the phone is away; wakes the screen when it's back (unlock with Touch ID). Needs “Nearby devices” allowed on the phone.")
        }
    }

    @ViewBuilder private var appsSection: some View {
        HStack(spacing: 10) {
            Button("Open apps…") { bridge.phone.tab = .apps; openWindow(id: "phone") }.buttonStyle(.borderedProminent)
            Button("Show phone screen") { bridge.mirror() }
        }
        .disabled(!bridge.connected)
        switch streamer.status {
        case .ready:
            caption("Session running — Developer options are on until you close the last phone window. Clipboard syncs automatically.")
        case .connecting:
            caption("Connecting to your phone…")
        case .idle:
            caption("Apps open in their own windows while your phone stays free. PhoneBridge switches Developer options on only while a phone window is open (phone on Wi-Fi). On a hotspot it shows the phone's screen instead.")
        case .notPaired:
            caption("Right now apps open by showing the phone's screen. For apps in their own windows while your phone stays free, do the one-time setup (on Wi-Fi). Afterwards Developer options stay off except while you use a phone app here.")
            Button("One-time setup…") { ui.showSetup = true }
        case .missingTools:
            caption("Needs adb and scrcpy: run  brew install --cask android-platform-tools  and  brew install scrcpy")
        }
    }

    @ViewBuilder private var micSection: some View {
        HStack(spacing: 6) {
            Circle().fill(mic.active ? Color.red : Color.secondary.opacity(0.4)).frame(width: 8, height: 8)
            Text(mic.status).font(.callout)
        }
        caption("Start it from the phone (🎙 Mic to Mac). It plays into the free BlackHole virtual device — install once with  brew install blackhole-2ch  in Terminal, then pick “BlackHole 2ch” as the microphone in Zoom, Meet or Teams.")
    }

    private func section<Content: View>(_ title: String, _ icon: String, @ViewBuilder content: () -> Content) -> some View {
        GroupBox {
            VStack(alignment: .leading, spacing: 8) {
                Label(title, systemImage: icon).font(.headline)
                content()
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(6)
        }
    }

    private func caption(_ s: String) -> some View {
        Text(s).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
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
            .clipShape(RoundedRectangle(cornerRadius: 8))
            VStack(alignment: .leading, spacing: 2) {
                Text(np.title).font(.headline).lineLimit(1)
                Text(np.artist.isEmpty ? np.app : "\(np.artist) · \(np.app)").font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer()
            Button { bridge.mediaCommand("prev") } label: { Image(systemName: "backward.fill") }.buttonStyle(.borderless)
            Button { bridge.mediaCommand("play_pause") } label: {
                Image(systemName: np.playing ? "pause.fill" : "play.fill").font(.title2)
            }.buttonStyle(.borderless)
            Button { bridge.mediaCommand("next") } label: { Image(systemName: "forward.fill") }.buttonStyle(.borderless)
        }
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 12).fill(.quaternary.opacity(0.5)))
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
