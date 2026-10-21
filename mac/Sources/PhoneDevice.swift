import AppKit
import MapKit
import SwiftUI

// MARK: - Device tab: battery, remote tools, find my phone, storage

struct DeviceInfo {
    var total: Int64
    var free: Int64
    var images: Int64
    var videos: Int64
    var torch: Bool
    var ringer: String
    var other: Int64 { max(0, total - free - images - videos) }
}

struct PhoneLocation {
    let lat: Double
    let lon: Double
    let accuracy: Double
    let time: Date
}

struct BigFile: Identifiable {
    let id: Int64
    let name: String
    let size: Int64
    let date: Date
    let duration: Int
    let thumb: NSImage?
}

private let bytes: ByteCountFormatter = {
    let f = ByteCountFormatter()
    f.countStyle = .file
    return f
}()

struct DeviceView: View {
    @ObservedObject var store: PhoneStore
    @ObservedObject var bridge: Bridge
    @ObservedObject var battery: PhoneBatteryWatch

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 360), spacing: 14, alignment: .top)], spacing: 14) {
                    batteryCard
                    toolsCard
                }
                findCard
                storageCard
            }
            .padding(.leading, 24).padding(.trailing, 20).padding(.bottom, 24)
        }
    }

    // MARK: Battery

    private var batteryCard: some View {
        let level = bridge.battery ?? 0
        return HStack(spacing: 20) {
            ZStack {
                Circle().stroke(Color.primary.opacity(0.1), lineWidth: 10)
                Circle().trim(from: 0, to: CGFloat(level) / 100)
                    .stroke(level <= 15 && !bridge.charging ? Theme.red : (bridge.charging ? Theme.green : Theme.accent),
                            style: StrokeStyle(lineWidth: 10, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                VStack(spacing: 0) {
                    Text(bridge.battery.map { "\($0)%" } ?? "–").font(.system(size: 26, weight: .bold, design: .rounded))
                    if bridge.charging { Image(systemName: "bolt.fill").font(.caption).foregroundStyle(Theme.green) }
                }
            }
            .frame(width: 100, height: 100)
            VStack(alignment: .leading, spacing: 6) {
                Text("Battery").font(.system(size: 16, weight: .bold, design: .rounded))
                Text(bridge.charging ? "Charging" : "On battery").font(.system(size: 13)).foregroundStyle(.secondary)
                if let eta = battery.eta {
                    Label(eta, systemImage: "clock").font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.accent)
                } else {
                    Text("Time estimate appears after a few minutes").font(.caption).foregroundStyle(.secondary)
                }
                Toggle("Alerts", isOn: $battery.alerts).toggleStyle(.switch).controlSize(.small)
                    .help("Tell me when it's fully charged or running low")
            }
            Spacer(minLength: 0)
        }
        .padding(18)
        .card(22)
    }

    // MARK: Remote tools

    private var toolsCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Remote tools").font(.system(size: 16, weight: .bold, design: .rounded))
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)], spacing: 10) {
                tool("flashlight.on.fill", "Flashlight", on: store.device?.torch == true) {
                    store.send(["t": "torch", "on": !(store.device?.torch == true)])
                }
                tool("bell.and.waves.left.and.right", bridge.phoneRinging ? "Stop ringing" : "Ring phone", on: bridge.phoneRinging) { bridge.toggleRing() }
                tool("moon.fill", "Do Not Disturb", on: bridge.phoneDnd) { bridge.send(["t": "phone_dnd", "on": !bridge.phoneDnd]) }
                tool("lock.fill", "Lock phone", on: false) { store.send(["t": "phone_lock"]) }
                tool("lock.open.fill", "Unlock phone", on: false) { store.unlockPhone() }
            }
            HStack(spacing: 4) {
                ForEach([("normal", "speaker.wave.2.fill", "Ring"), ("vibrate", "iphone.radiowaves.left.and.right", "Vibrate"), ("silent", "speaker.slash.fill", "Silent")], id: \.0) { mode, icon, title in
                    let selected = store.device?.ringer == mode
                    Button { store.send(["t": "ringer", "mode": mode]) } label: {
                        Label(title, systemImage: icon).font(.system(size: 12, weight: .semibold)).lineLimit(1)
                            .frame(maxWidth: .infinity).padding(.vertical, 8)
                            .foregroundStyle(selected ? Color.white : .primary)
                            .background(Capsule().fill(selected ? Theme.accent : .clear))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(3).background(Capsule().fill(Color.primary.opacity(0.08)))
        }
        .padding(18)
        .card(22)
    }

    private func tool(_ icon: String, _ title: String, on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Image(systemName: icon).font(.system(size: 14, weight: .semibold))
                Text(title).font(.system(size: 12.5, weight: .semibold)).lineLimit(1)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 12).padding(.vertical, 11)
            .foregroundStyle(on ? Color.white : .primary)
            .background(RoundedRectangle(cornerRadius: 13, style: .continuous).fill(on ? AnyShapeStyle(Theme.gradient) : AnyShapeStyle(Color.primary.opacity(0.07))))
        }
        .buttonStyle(.plain)
    }

    // MARK: Find my phone

    private var findCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Find my phone").font(.system(size: 16, weight: .bold, design: .rounded))
                Spacer()
                if let loc = store.location {
                    Text("Updated \(relative.localizedString(for: loc.time, relativeTo: Date())) · ±\(Int(loc.accuracy)) m")
                        .font(.caption).foregroundStyle(.secondary)
                }
                pill("Locate", "location.fill", prominent: true) { store.locate() }.disabled(store.locating)
                if let loc = store.location {
                    pill("Open in Maps", "map") {
                        if let url = URL(string: "https://maps.apple.com/?ll=\(loc.lat),\(loc.lon)&q=My%20phone") { NSWorkspace.shared.open(url) }
                    }
                }
            }
            ZStack {
                if let loc = store.location {
                    let c = CLLocationCoordinate2D(latitude: loc.lat, longitude: loc.lon)
                    Map(initialPosition: .region(MKCoordinateRegion(center: c, latitudinalMeters: max(400, loc.accuracy * 6), longitudinalMeters: max(400, loc.accuracy * 6)))) {
                        MapCircle(center: c, radius: max(loc.accuracy, 10)).foregroundStyle(Theme.accent.opacity(0.18)).stroke(Theme.accent, lineWidth: 1)
                        Marker("Your phone", systemImage: "iphone.gen3", coordinate: c).tint(Theme.accent)
                    }
                    .id(loc.time)
                } else {
                    VStack(spacing: 8) {
                        Image(systemName: store.locating ? "location.magnifyingglass" : "location.slash.fill").font(.system(size: 30)).foregroundStyle(.secondary)
                        Text(store.locating ? "Asking your phone…" : (store.locationError ?? "Press Locate to see where your phone is."))
                            .font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center).frame(maxWidth: 360)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Color.primary.opacity(0.04))
                }
            }
            .frame(height: 280)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            if store.location != nil, let e = store.locationError {
                Text(e).font(.caption).foregroundStyle(.orange)
            }
        }
        .padding(18)
        .card(22)
    }

    private func pill(_ title: String, _ icon: String, prominent: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(title, systemImage: icon).font(.system(size: 12.5, weight: .semibold)).fixedSize()
                .padding(.horizontal, 12).padding(.vertical, 6)
                .foregroundStyle(prominent ? Color.white : .primary)
                .background(Capsule().fill(prominent ? Theme.accent : Color.primary.opacity(0.08)))
        }
        .buttonStyle(.plain)
    }

    // MARK: Storage

    private var storageCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Text("Storage").font(.system(size: 16, weight: .bold, design: .rounded))
                Spacer()
                if let d = store.device { Text("\(bytes.string(fromByteCount: d.free)) free of \(bytes.string(fromByteCount: d.total))").font(.caption).foregroundStyle(.secondary) }
            }
            if let d = store.device {
                GeometryReader { g in
                    let total = max(Double(d.total), 1)
                    HStack(spacing: 2) {
                        segment(Theme.accent, g.size.width * Double(d.images) / total)
                        segment(Theme.accent2, g.size.width * Double(d.videos) / total)
                        segment(Color.gray.opacity(0.7), g.size.width * Double(d.other) / total)
                        Spacer(minLength: 0)
                    }
                    .background(Capsule().fill(Color.primary.opacity(0.08)))
                    .clipShape(Capsule())
                }
                .frame(height: 14)
                HStack(spacing: 18) {
                    legend(Theme.accent, "Photos", d.images)
                    legend(Theme.accent2, "Videos", d.videos)
                    legend(Color.gray.opacity(0.7), "Apps & other", d.other)
                    legend(Color.primary.opacity(0.15), "Free", d.free)
                }
            } else {
                ProgressView().frame(maxWidth: .infinity)
            }
            Divider()
            Text("Biggest videos — clear space from here").font(.system(size: 13, weight: .semibold))
            if store.biggest.isEmpty {
                Text("Nothing to show.").font(.callout).foregroundStyle(.secondary)
            } else {
                VStack(spacing: 6) { ForEach(store.biggest) { f in bigRow(f) } }
            }
        }
        .padding(18)
        .card(22)
    }

    private func segment(_ color: Color, _ width: Double) -> some View {
        Rectangle().fill(color).frame(width: max(width, 0))
    }

    private func legend(_ color: Color, _ title: String, _ size: Int64) -> some View {
        HStack(spacing: 6) {
            Circle().fill(color).frame(width: 9, height: 9)
            VStack(alignment: .leading, spacing: 0) {
                Text(title).font(.system(size: 11.5, weight: .semibold))
                Text(bytes.string(fromByteCount: size)).font(.system(size: 11)).foregroundStyle(.secondary)
            }
        }
    }

    private func bigRow(_ f: BigFile) -> some View {
        HStack(spacing: 12) {
            Button { store.viewBig(f) } label: {
                HStack(spacing: 12) {
                    Group {
                        if let t = f.thumb { Image(nsImage: t).resizable().scaledToFill() } else { Color.primary.opacity(0.08) }
                    }
                    .frame(width: 54, height: 54).clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                    .overlay {
                        Image(systemName: "play.fill").font(.system(size: 13)).foregroundStyle(.white)
                            .frame(width: 28, height: 28).background(.black.opacity(0.45), in: Circle())
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(f.name).font(.system(size: 13, weight: .semibold)).lineLimit(1)
                        Text("\(bytes.string(fromByteCount: f.size)) · \(f.duration / 60000):\(String(format: "%02d", f.duration / 1000 % 60)) · \(relative.localizedString(for: f.date, relativeTo: Date()))")
                            .font(.system(size: 11.5)).foregroundStyle(.secondary)
                    }
                    Spacer()
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .help("Play this video")
            Button { store.download([f.id]) } label: { Image(systemName: "arrow.down.circle").frame(width: 32, height: 32).card(10) }
                .buttonStyle(.plain).help("Save to Downloads")
            Button { store.delete(f.id) } label: { Image(systemName: "trash").foregroundStyle(Theme.red).frame(width: 32, height: 32).card(10) }
                .buttonStyle(.plain).help("Delete from phone")
        }
        .padding(8)
        .card(14, fill: Color.primary.opacity(0.03))
    }
}
