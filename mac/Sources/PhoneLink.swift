import CryptoKit
import Foundation
import Network

/// Finds the phone (Bonjour, last known address, or the hotspot gateway), connects, pairs/authenticates,
/// and exchanges encrypted JSON messages. All state lives on `queue`.
final class PhoneLink {
    enum State: Equatable {
        case searching
        case pairing(String)
        case connected(String)
    }

    static let port: UInt16 = 47800
    static let serviceType = "_phonebridge._tcp"

    var onState: (State) -> Void = { _ in }
    var onMessage: ([String: Any]) -> Void = { _ in }
    /// A different phone tried to connect while we're paired — ignored. (name)
    var onStranger: (String) -> Void = { _ in }
    /// (code, phoneName, decision) — call decision(true) if the user confirms the code.
    var askPairing: (String, String, @escaping (Bool) -> Void) -> Void = { _, _, d in d(false) }

    private let queue = DispatchQueue(label: "phonebridge.link")
    private var browser: NWBrowser?
    private var bonjourEndpoint: NWEndpoint?
    private var conn: NWConnection?
    private var attempt = 0
    private var timer: DispatchSourceTimer?

    // Per-connection session state
    private var myNonce = Data()
    private var ephemeral = Curve25519.KeyAgreement.PrivateKey() // new for every connection (forward secrecy)
    private var ikm = Data()
    private var shared = Data()
    private var salt = Data()
    private var peerPub = Data()
    private var peerName = "Phone"
    private var sentTrust = false
    private var gotTrust = false
    private var sendSealer: Sealer?
    private var recvSealer: Sealer?
    private var lastReceived = Date()

    private var defaults: UserDefaults { .standard }

    func start() {
        queue.async {
            self.startBrowser()
            let t = DispatchSource.makeTimerSource(queue: self.queue)
            t.schedule(deadline: .now() + 1, repeating: 5)
            t.setEventHandler { self.tick() } // PhoneLink lives for the whole app
            t.resume()
            self.timer = t
        }
    }

    /// Thread-safe. Messages sent before the session is ready are dropped.
    func send(_ msg: [String: Any], completion: ((Bool) -> Void)? = nil) {
        queue.async {
            guard let c = self.conn, let s = self.sendSealer,
                  let data = try? JSONSerialization.data(withJSONObject: msg),
                  let sealed = try? s.seal(data) else {
                completion?(false)
                return
            }
            self.write(sealed, on: c, completion: completion)
        }
    }

    /// The phone's VPN addresses (Tailscale 100.64.0.0/10), tried when it isn't on the same network.
    func setRemoteHosts(_ addrs: [String]) {
        let tailnet = addrs.filter { a in
            let p = a.split(separator: ".").compactMap { Int($0) }
            return p.count == 4 && p[0] == 100 && (64...127).contains(p[1])
        }
        queue.async { self.defaults.set(tailnet, forKey: "remoteHosts") }
    }

    /// Drops the current connection and looks for the phone again right away: fresh Bonjour search, every known address.
    func reconnect() {
        queue.async {
            self.browser?.cancel()
            self.browser = nil
            self.bonjourEndpoint = nil
            self.startBrowser()
            self.attempt = 0
            self.conn?.cancel()
            self.queue.asyncAfter(deadline: .now() + 0.4) { [weak self] in self?.tick() }
        }
    }

    func forget() {
        queue.async {
            Keys.trustedPhone = nil
            self.defaults.removeObject(forKey: "phoneName")
            self.conn?.cancel()
        }
    }

    // MARK: Discovery

    private func startBrowser() {
        let b = NWBrowser(for: .bonjour(type: Self.serviceType, domain: nil), using: .tcp)
        b.browseResultsChangedHandler = { [weak self] results, _ in
            guard let self else { return }
            self.bonjourEndpoint = results.first?.endpoint
            if self.conn == nil, let ep = self.bonjourEndpoint { self.connect(to: ep) }
        }
        b.stateUpdateHandler = { state in
            if case .failed = state { b.cancel() }
        }
        b.start(queue: queue)
        browser = b
    }

    private func tick() {
        if let c = conn {
            if sendSealer != nil {
                if Date().timeIntervalSince(lastReceived) > 45 { c.cancel(); return }
                send(["t": "ping"])
            }
            return
        }
        // Not connected: rotate through every way we know to reach the phone.
        var candidates: [NWEndpoint] = []
        if let ep = bonjourEndpoint { candidates.append(ep) }
        if let host = defaults.string(forKey: "lastHost") { candidates.append(hostEndpoint(host)) }
        if let gw = Self.defaultGateway() { candidates.append(hostEndpoint(gw)) } // phone hotspot = gateway
        for host in defaults.stringArray(forKey: "remoteHosts") ?? [] { candidates.append(hostEndpoint(host)) }
        guard !candidates.isEmpty else { return }
        connect(to: candidates[attempt % candidates.count])
        attempt += 1
    }

    private func hostEndpoint(_ host: String) -> NWEndpoint {
        .hostPort(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: Self.port)!)
    }

    /// The default route's gateway — when the Mac is on the phone's hotspot, that's the phone.
    static func defaultGateway() -> String? {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/sbin/route")
        p.arguments = ["-n", "get", "default"]
        let pipe = Pipe()
        p.standardOutput = pipe
        p.standardError = FileHandle.nullDevice
        guard (try? p.run()) != nil else { return nil }
        p.waitUntilExit()
        let out = String(data: pipe.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8) ?? ""
        for line in out.split(separator: "\n") {
            let parts = line.split(separator: ":", maxSplits: 1).map { $0.trimmingCharacters(in: .whitespaces) }
            if parts.count == 2, parts[0] == "gateway" { return parts[1] }
        }
        return nil
    }

    // MARK: Connection

    private func connect(to ep: NWEndpoint) {
        guard conn == nil else { return }
        let tcp = NWProtocolTCP.Options()
        tcp.connectionTimeout = 4
        tcp.noDelay = true
        tcp.enableKeepalive = true
        tcp.keepaliveIdle = 10
        let c = NWConnection(to: ep, using: NWParameters(tls: nil, tcp: tcp))
        conn = c
        resetSession()
        c.stateUpdateHandler = { [weak self, weak c] state in
            guard let self, let c else { return }
            switch state {
            case .ready:
                self.lastReceived = Date()
                self.sendPlain(["t": "hello", "name": Host.current().localizedName ?? "Mac",
                                "pub": Keys.publicRaw.base64EncodedString(),
                                "epub": self.ephemeral.publicKey.rawRepresentation.base64EncodedString(),
                                "nonce": self.myNonce.base64EncodedString(), "v": 2], on: c)
                self.receiveFrame(on: c)
            case .failed, .cancelled:
                self.closed(c)
            case .waiting:
                c.cancel() // unreachable right now; the timer will try the next candidate
            default:
                break
            }
        }
        c.start(queue: queue)
    }

    private func resetSession() {
        myNonce = Data((0..<16).map { _ in UInt8.random(in: 0...255) })
        ephemeral = Curve25519.KeyAgreement.PrivateKey()
        ikm = Data()
        sentTrust = false
        gotTrust = false
        sendSealer = nil
        recvSealer = nil
    }

    private func closed(_ c: NWConnection) {
        guard conn === c else { return }
        conn = nil
        let wasUp = sendSealer != nil
        resetSession()
        onState(.searching)
        if wasUp { queue.asyncAfter(deadline: .now() + 1) { [weak self] in self?.tick() } }
    }

    private func receiveFrame(on c: NWConnection) {
        c.receive(minimumIncompleteLength: 4, maximumLength: 4) { [weak self] header, _, _, error in
            guard let self, let header, header.count == 4, error == nil else { c.cancel(); return }
            let length = header.reduce(0) { $0 << 8 | Int($1) }
            guard length > 0, length < 16_000_000 else { c.cancel(); return }
            c.receive(minimumIncompleteLength: length, maximumLength: length) { body, _, _, error in
                guard let body, body.count == length, error == nil else { c.cancel(); return }
                self.handleFrame(body, on: c)
                if self.conn === c { self.receiveFrame(on: c) }
            }
        }
    }

    private func handleFrame(_ frame: Data, on c: NWConnection) {
        guard conn === c else { return }
        lastReceived = Date()
        if let r = recvSealer {
            guard let plain = try? r.open(frame),
                  let msg = try? JSONSerialization.jsonObject(with: plain) as? [String: Any] else {
                c.cancel()
                return
            }
            if msg["t"] as? String != "pong" { onMessage(msg) }
            return
        }
        guard let msg = try? JSONSerialization.jsonObject(with: frame) as? [String: Any] else { c.cancel(); return }
        switch msg["t"] as? String {
        case "hello":
            guard let pubB64 = msg["pub"] as? String, let pub = Data(base64Encoded: pubB64),
                  let nonceB64 = msg["nonce"] as? String, let nonce = Data(base64Encoded: nonceB64),
                  let epubB64 = msg["epub"] as? String, let epub = Data(base64Encoded: epubB64), // older phone app → refuse
                  let s = try? Keys.shared(with: pub),
                  let k = try? Keys.sessionIKM(staticKey: Keys.privateKey, ephemeral: ephemeral,
                                               peerStatic: pub, peerEphemeral: epub) else { c.cancel(); return }
            peerPub = pub
            peerName = msg["name"] as? String ?? "Phone"
            shared = s
            ikm = k
            salt = myNonce + nonce
            let trusted = Keys.trustedPhone
            if trusted == pub {
                sendTrust(on: c)
            } else if trusted != nil {
                // Paired with a different phone: never offer to pair, just ignore it.
                sendPlain(["t": "reject"], on: c)
                c.cancel()
                onStranger(peerName)
            } else {
                onState(.pairing(peerName))
                askPairing(Keys.pairingCode(s), peerName) { [weak self] ok in
                    self?.queue.async {
                        guard let self, self.conn === c else { return }
                        if ok {
                            Keys.trustedPhone = self.peerPub
                            self.defaults.set(self.peerName, forKey: "phoneName")
                            self.sendTrust(on: c)
                        } else {
                            self.sendPlain(["t": "reject"], on: c)
                            c.cancel()
                        }
                    }
                }
            }
        case "trust":
            // After the phone's "trust", everything it sends is encrypted.
            recvSealer = Sealer(Keys.sessionKey(ikm, salt: salt, info: "p2m"))
            gotTrust = true
            maybeReady(on: c)
        case "reject":
            c.cancel()
        default:
            break
        }
    }

    private func sendTrust(on c: NWConnection) {
        sendPlain(["t": "trust"], on: c)
        sentTrust = true
        maybeReady(on: c)
    }

    private func maybeReady(on c: NWConnection) {
        guard sentTrust, gotTrust, sendSealer == nil else { return }
        sendSealer = Sealer(Keys.sessionKey(ikm, salt: salt, info: "m2p"))
        if case .hostPort(let host, _) = c.currentPath?.remoteEndpoint {
            var h = "\(host)"
            if let pct = h.firstIndex(of: "%") { h = String(h[..<pct]) } // drop IPv6 scope
            if !h.contains(":") { defaults.set(h, forKey: "lastHost") }
        }
        onState(.connected(peerName))
    }

    private func sendPlain(_ msg: [String: Any], on c: NWConnection) {
        guard let data = try? JSONSerialization.data(withJSONObject: msg) else { return }
        write(data, on: c, completion: nil)
    }

    private func write(_ payload: Data, on c: NWConnection, completion: ((Bool) -> Void)?) {
        var frame = Data()
        withUnsafeBytes(of: UInt32(payload.count).bigEndian) { frame.append(contentsOf: $0) }
        frame.append(payload)
        c.send(content: frame, completion: .contentProcessed { error in completion?(error == nil) })
    }
}
