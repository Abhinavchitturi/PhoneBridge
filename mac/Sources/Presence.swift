import AppKit
import CoreBluetooth
import CryptoKit
import IOKit.pwr_mgt

/// Watches the phone's Bluetooth LE beacon. When it fades (you walked away) and the Mac has been idle
/// for a bit, lock the Mac; when it comes back, wake the screen (macOS doesn't let apps unlock — use Touch ID).
/// Beacon layout matches android/.../Automations.kt (Presence).
@MainActor
final class Presence: NSObject, ObservableObject {
    enum Sensitivity: Int, CaseIterable, Identifiable {
        case near = -65, medium = -75, far = -85
        var id: Int { rawValue }
        var label: String {
            switch self {
            case .near: "Close (a few steps)"
            case .medium: "Medium (other side of the room)"
            case .far: "Far (another room)"
            }
        }
    }

    static let service = CBUUID(string: "6a1b2c3d-5e6f-4a7b-8c9d-50484f4e4542")

    @Published var enabled = UserDefaults.standard.object(forKey: "lockWhenAway") as? Bool ?? true {
        didSet { UserDefaults.standard.set(enabled, forKey: "lockWhenAway") }
    }
    @Published var sensitivity = Sensitivity(rawValue: UserDefaults.standard.integer(forKey: "awaySensitivity")) ?? .medium {
        didSet { UserDefaults.standard.set(sensitivity.rawValue, forKey: "awaySensitivity") }
    }
    @Published private(set) var status = "Waiting for your phone's beacon…"
    @Published private(set) var nearby = false

    var lock: () -> Void = {}

    private var central: CBCentralManager?
    private var smoothed: Double?
    private var lastSeen: Date?
    private var weakSince: Date?
    private var armed = false // only after we've actually seen the phone, so a missing beacon never locks
    private var lockedByUs = false
    private var timer: Timer?

    /// True if the phone's beacon was heard in the last few seconds (fresher than `nearby`, which lags after a sleep).
    func seenRecently(_ seconds: TimeInterval = 6) -> Bool {
        guard let seen = lastSeen else { return false }
        return Date().timeIntervalSince(seen) < seconds
    }

    func start() {
        central = CBCentralManager(delegate: self, queue: .main)
        timer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.evaluate() }
        }
    }

    /// First 6 bytes of SHA-256(paired phone's public key) — the phone puts this in its beacon.
    private var expectedID: Data? {
        guard let pub = Keys.trustedPhone else { return nil }
        return Data(SHA256.hash(data: pub)).prefix(6)
    }

    fileprivate func sighting(rssi: Double, manufacturerData: Data?) {
        guard let id = expectedID, let md = manufacturerData, md.count >= 8, md.dropFirst(2).prefix(6) == id,
              rssi < 0 else { return }
        smoothed = smoothed.map { $0 * 0.7 + rssi * 0.3 } ?? rssi
        lastSeen = Date()
        armed = true
        evaluate()
    }

    private func evaluate() {
        guard let lastSeen, let rssi = smoothed else { return }
        let gone = Date().timeIntervalSince(lastSeen) > 25
        let weak = rssi < Double(sensitivity.rawValue)
        if weak { weakSince = weakSince ?? Date() } else { weakSince = nil }
        let away = gone || (weakSince.map { Date().timeIntervalSince($0) > 10 } ?? false)
        nearby = !away
        status = away ? "Phone away" : "Phone nearby (signal \(Int(rssi)) dBm)"

        if away, enabled, armed, !lockedByUs, Self.idleSeconds() > 20 {
            lockedByUs = true
            lock()
        } else if !away, lockedByUs, rssi > Double(sensitivity.rawValue) + 5 {
            lockedByUs = false
            Self.wakeDisplay()
        }
    }

    /// Seconds since the last keyboard/mouse input — don't lock someone who's actively using the Mac.
    private static func idleSeconds() -> Double {
        CGEventSource.secondsSinceLastEventType(.hidSystemState, eventType: CGEventType(rawValue: ~0)!)
    }

    private static func wakeDisplay() {
        var id: IOPMAssertionID = 0
        IOPMAssertionDeclareUserActivity("PhoneBridge: phone is back" as CFString, kIOPMUserActiveLocal, &id)
    }
}

extension Presence: CBCentralManagerDelegate {
    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        MainActor.assumeIsolated {
            if central.state == .poweredOn {
                central.scanForPeripherals(withServices: [Self.service], options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
            } else {
                status = central.state == .unauthorized ? "Allow Bluetooth for PhoneBridge in System Settings" : "Bluetooth is off"
            }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                                    advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let md = advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data
        let value = RSSI.doubleValue
        MainActor.assumeIsolated { sighting(rssi: value, manufacturerData: md) }
    }
}
