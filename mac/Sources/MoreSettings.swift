import SwiftUI
import AppKit

/// The longer settings that used to sit on the main window: walk-away lock, phone apps, microphone, screenshots,
/// Tailscale, start at login. They live in Settings now so the main window stays clean.
struct MoreSettingsView: View {
    @ObservedObject var bridge: Bridge
    @ObservedObject var streamer: AppStreamer
    @ObservedObject private var presence = Bridge.shared.presence
    @ObservedObject private var mic = Bridge.shared.mic
    @ObservedObject private var screenshots = Bridge.shared.screenshots
    @ObservedObject private var ui = UIState.shared
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        VStack(spacing: 16) {
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
            section("This Mac", "laptopcomputer") {
                Toggle("Open at login", isOn: $ui.openAtLogin)
                Button("Forget phone") { bridge.forget() }
            }
        }
        .buttonStyle(PillButtonStyle())
        .sheet(isPresented: $ui.showSetup) { StreamingSetupView(streamer: streamer, done: { ui.showSetup = false }) }
    }

    @ViewBuilder private var lockSection: some View {
        Toggle(isOn: $presence.enabled) {
            Text(presence.status).foregroundStyle(.secondary)
        }
        if presence.enabled {
            Picker("Lock when phone is", selection: $presence.sensitivity) {
                ForEach(Presence.Sensitivity.allCases) { Text($0.label).tag($0) }
            }
            caption("Locks after 20 s without keyboard/mouse use while the phone is away; wakes the screen when it's back. Needs “Nearby devices” allowed on the phone.")
        }
    }

    @ViewBuilder private var appsSection: some View {
        HStack(spacing: 10) {
            Button("Open apps…") { bridge.phone.tab = .apps; openWindow(id: "phone") }.buttonStyle(PillButtonStyle(prominent: true))
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
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                Image(systemName: icon).font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.accent)
                    .frame(width: 30, height: 30)
                    .background(RoundedRectangle(cornerRadius: 9, style: .continuous).fill(Theme.accent.opacity(0.14)))
                Text(title).font(.system(size: 15, weight: .semibold))
            }
            content()
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .card(20)
    }

    private func caption(_ s: String) -> some View {
        Text(s).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
    }
}
