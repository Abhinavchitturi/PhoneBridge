import AppKit
import CoreImage.CIFilterBuiltins
import SwiftUI

struct GuardSettingsView: View {
    @ObservedObject var guardian: IntruderGuard
    @ObservedObject var bridge: Bridge

    private static let time: DateFormatter = {
        let f = DateFormatter()
        f.dateStyle = .short
        f.timeStyle = .short
        return f
    }()

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Toggle("Turn on the guard", isOn: Binding(get: { guardian.enabled }, set: { toggle($0) }))
            Text("When the Mac wakes, unlocks or starts, it takes a few photos (the camera light comes on). If your phone isn't near, it sends you the photos with buttons to say “It's me” or lock the Mac.")
                .font(.caption).foregroundStyle(.secondary)
            if guardian.enabled {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Ask for the unlock code after sleep, restart or startup").font(.system(size: 12.5, weight: .semibold))
                    Picker("", selection: Binding(get: { guardian.requireMode }, set: { setMode($0) })) {
                        ForEach(RequireMode.allCases) { Text($0.title).tag($0) }
                    }
                    .labelsHidden().pickerStyle(.radioGroup)
                    Text("The Mac locks the moment it wakes or starts. In the recommended mode it unlocks by itself when your phone is found nearby; otherwise it waits for the code from your phone's Code tab (or an authenticator app).")
                        .font(.caption).foregroundStyle(.secondary)
                }
                Toggle("Only alert me when my phone isn't near the Mac", isOn: $guardian.onlyWhenAway)
                Text("Photos are always kept in the private folder below, even when no alert is sent. They're deleted at the end of each day. The photos exist only on this Mac; your phone just shows them while you look and keeps nothing.").font(.caption).foregroundStyle(.secondary)
                HStack(spacing: 8) {
                    pill("Test alert", "camera.fill") { guardian.testAlert() }.disabled(guardian.capturing)
                    pill("Test lock screen (12 s)", "lock.fill") { guardian.testLockScreen() }
                    pill("Open photo folder", "folder") { guardian.revealFolder() }
                }
                HStack(spacing: 8) {
                    pill("Use Google / Microsoft Authenticator…", "qrcode") { showAuthenticatorQR() }
                }
                Text("The PhoneBridge app on your phone already shows the unlock code. This adds the same codes to an authenticator app too, which can back up to the cloud.")
                    .font(.caption).foregroundStyle(.secondary)
                HStack {
                    Text("Relay server").font(.system(size: 12.5, weight: .semibold))
                    TextField("https://ntfy.sh", text: $guardian.server).textFieldStyle(.plain)
                        .padding(.horizontal, 10).padding(.vertical, 6).card(10)
                }
                Text("Lets alerts and lock commands travel over the internet when your phone isn't on the same network. Everything is encrypted; the server only sees scrambled data. Leave it as is unless you run your own ntfy server.")
                    .font(.caption).foregroundStyle(.secondary)
                if GuardCamera.status == .denied || GuardCamera.status == .restricted {
                    Label("Camera access is off. Turn it on in System Settings → Privacy & Security → Camera.", systemImage: "exclamationmark.triangle.fill")
                        .font(.caption).foregroundStyle(.orange)
                }
                if !guardian.events.isEmpty {
                    Divider().padding(.vertical, 2)
                    Text("Recent").font(.system(size: 12.5, weight: .semibold))
                    ForEach(guardian.events.prefix(6)) { ev in row(ev) }
                }
            }
        }
    }

    private func row(_ ev: GuardEvent) -> some View {
        HStack(spacing: 10) {
            Group {
                if let f = ev.files.first, let img = NSImage(contentsOf: guardian.photoURL(f)) {
                    Image(nsImage: img).resizable().scaledToFill()
                } else {
                    ZStack { Color.primary.opacity(0.08); Image(systemName: "camera.slash").foregroundStyle(.secondary) }
                }
            }
            .frame(width: 54, height: 40).clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
            VStack(alignment: .leading, spacing: 1) {
                Text(ev.reason).font(.system(size: 12.5, weight: .semibold))
                Text("\(Self.time.string(from: ev.date)) · \(status(ev))").font(.system(size: 11)).foregroundStyle(.secondary)
            }
            Spacer()
        }
    }

    private func status(_ ev: GuardEvent) -> String {
        switch ev.status {
        case "confirmed": return "you said it was you"
        case "locked": return "you locked the Mac"
        case "quiet": return "quiet period after “It's me”"
        default: break
        }
        if ev.ownerNear { return "phone was near, no alert" }
        return ev.sent ? "alert sent" : "not sent yet"
    }

    private func pill(_ title: String, _ icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(title, systemImage: icon).font(.system(size: 12, weight: .semibold)).fixedSize()
                .padding(.horizontal, 12).padding(.vertical, 6).background(Capsule().fill(Color.primary.opacity(0.08)))
        }
        .buttonStyle(.plain)
    }

    /// Making the Mac easier to open needs the code, so a stranger can't weaken it.
    private func setMode(_ new: RequireMode) {
        let order: [RequireMode] = [.off, .away, .always]
        let weaker = (order.firstIndex(of: new) ?? 0) < (order.firstIndex(of: guardian.requireMode) ?? 0)
        if weaker && !guardian.askCode("Change the lock setting") { return }
        guardian.requireMode = new
    }

    private func toggle(_ on: Bool) {
        if on {
            if let code = guardian.enable() {
                showRecovery(code)
                guardian.testAlert()
            }
        } else {
            _ = guardian.disable()
        }
    }

    /// Shows a QR code to scan into Google or Microsoft Authenticator. Needs a code first, so it can't be shown by a stranger at the Mac.
    private func showAuthenticatorQR() {
        guard guardian.askCode("Show the authenticator QR code"), let link = guardian.authenticatorURL() else { return }
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(link.utf8)
        guard let qr = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)) else { return }
        let rep = NSCIImageRep(ciImage: qr)
        let image = NSImage(size: rep.size)
        image.addRepresentation(rep)
        let view = NSImageView(frame: NSRect(x: 0, y: 0, width: 260, height: 260))
        view.image = image
        view.imageScaling = .scaleProportionallyUpOrDown
        let alert = NSAlert()
        alert.messageText = "Add to an authenticator app"
        alert.informativeText = "In Google Authenticator or Microsoft Authenticator choose Add account → Scan a QR code, and scan this. The codes will match the ones in PhoneBridge. Don't screenshot or share it."
        alert.accessoryView = view
        alert.addButton(withTitle: "Done")
        alert.runModal()
    }

    /// Shown once. Not copied to the clipboard on purpose, since the clipboard is shared with the phone.
    private func showRecovery(_ code: String) {
        NSApp.activate(ignoringOtherApps: true)
        let alert = NSAlert()
        alert.messageText = "Your spare key (recovery code)"
        alert.informativeText = "You won't normally type this. Your everyday unlock code is the 6-digit code in the PhoneBridge app on your phone. This spare opens the Mac's lock screen only if your phone is lost, dead or wiped. It is shown once and isn't saved anywhere readable, so write it on paper and keep it away from this Mac."
        let field = NSTextField(labelWithString: code)
        field.font = .monospacedSystemFont(ofSize: 22, weight: .semibold)
        field.isSelectable = true
        field.frame = NSRect(x: 0, y: 0, width: 320, height: 30)
        alert.accessoryView = field
        alert.addButton(withTitle: "I've written it down")
        alert.runModal()
    }
}
