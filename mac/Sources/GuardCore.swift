import CryptoKit
import Foundation

/// Crypto for the intruder guard. One random 32-byte key is shared with the phone over the paired link; everything
/// else is derived from it, identically on both sides (see android/.../Guard.kt):
///   HKDF-SHA256(ikm = key, salt = "phonebridge-guard", info = label) → 32 bytes, with labels
///   "photo" (AES-256-GCM key for alerts sent over the internet), "cmd" (HMAC key for commands coming back),
///   "topic-out" / "topic-cmd" (first 12 bytes → hex, the unguessable relay topics) and "totp" (first 20 bytes → authenticator seed).
enum GuardCrypto {
    private static let salt = Data("phonebridge-guard".utf8)

    static func newKey() -> Data {
        var bytes = [UInt8](repeating: 0, count: 32)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return Data(bytes)
    }

    static func derive(_ key: Data, _ label: String) -> Data {
        let k = HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: key), salt: salt, info: Data(label.utf8), outputByteCount: 32)
        return k.withUnsafeBytes { Data($0) }
    }

    static func topic(_ key: Data, _ label: String) -> String {
        "pbg-" + derive(key, label).prefix(12).map { String(format: "%02x", $0) }.joined()
    }

    /// nonce (12) || ciphertext || tag (16)
    static func seal(_ plain: Data, key: Data) throws -> Data {
        guard let combined = try AES.GCM.seal(plain, using: SymmetricKey(data: derive(key, "photo"))).combined else { throw CocoaError(.coderInvalidValue) }
        return combined
    }

    static func open(_ blob: Data, key: Data) throws -> Data {
        try AES.GCM.open(AES.GCM.SealedBox(combined: blob), using: SymmetricKey(data: derive(key, "photo")))
    }

    static func sign(_ message: Data, key: Data) -> Data {
        Data(HMAC<SHA256>.authenticationCode(for: message, using: SymmetricKey(data: derive(key, "cmd"))))
    }

    static func b64url(_ d: Data) -> String {
        d.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }

    static func fromB64url(_ s: String) -> Data? {
        var t = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while t.count % 4 != 0 { t += "=" }
        return Data(base64Encoded: t)
    }

    // MARK: Authenticator code (RFC 6238, SHA-1, 6 digits, 30 s)

    static func totpSeed(_ key: Data) -> Data { derive(key, "totp").prefix(20) }

    static func totp(seed: Data, at date: Date = Date(), stepOffset: Int = 0) -> String {
        let counter = UInt64(max(0, Int64(date.timeIntervalSince1970 / 30) + Int64(stepOffset)))
        var be = counter.bigEndian
        let message = Data(bytes: &be, count: 8)
        let h = Array(HMAC<Insecure.SHA1>.authenticationCode(for: message, using: SymmetricKey(data: seed)))
        let o = Int(h[19] & 0x0f)
        let value = (UInt32(h[o] & 0x7f) << 24) | (UInt32(h[o + 1]) << 16) | (UInt32(h[o + 2]) << 8) | UInt32(h[o + 3])
        return String(format: "%06d", value % 1_000_000)
    }

    /// Accepts the current code and one step either side (clock drift).
    static func verifyTOTP(_ code: String, seed: Data, at date: Date = Date()) -> Bool {
        (-1...1).contains { totp(seed: seed, at: date, stepOffset: $0) == code }
    }

    /// otpauth:// link for Google / Microsoft Authenticator and similar apps (same codes as the PhoneBridge app shows).
    static func authenticatorURL(_ key: Data) -> String {
        let alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567")
        var out = "", buffer = 0, bits = 0
        for byte in totpSeed(key) {
            buffer = (buffer << 8) | Int(byte)
            bits += 8
            while bits >= 5 { bits -= 5; out.append(alphabet[(buffer >> bits) & 31]) }
        }
        if bits > 0 { out.append(alphabet[(buffer << (5 - bits)) & 31]) }
        return "otpauth://totp/PhoneBridge:Mac%20guard?secret=\(out)&issuer=PhoneBridge&algorithm=SHA1&digits=6&period=30"
    }

    // MARK: Recovery code (printed once; only a salted hash is kept)

    static func newRecoveryCode() -> String {
        let alphabet = Array("ABCDEFGHJKLMNPQRSTUVWXYZ23456789")
        var bytes = [UInt8](repeating: 0, count: 16)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        let chars = bytes.map { alphabet[Int($0) % alphabet.count] }
        return stride(from: 0, to: 16, by: 4).map { String(chars[$0..<$0 + 4]) }.joined(separator: "-")
    }

    static func normalizeRecovery(_ s: String) -> String { s.uppercased().filter { $0.isLetter || $0.isNumber } }

    static func recoveryHash(_ code: String, salt: Data) -> Data {
        Data(SHA256.hash(data: salt + Data(normalizeRecovery(code).utf8)))
    }
}

/// Sends and receives small messages through a ntfy server, so the Mac and the phone can reach each other from anywhere.
/// Topics are long random names and every payload is encrypted/signed, so the server only sees ciphertext.
struct GuardRelay {
    let server: String

    private func url(_ topic: String, _ suffix: String = "") -> URL? { URL(string: server.trimmingCharacters(in: CharacterSet(charactersIn: "/ ")) + "/" + topic + suffix) }

    /// Uploads [body] as an attachment on [topic] (up to 15 MB on ntfy.sh).
    func publish(topic: String, body: Data, filename: String) async throws {
        guard let u = url(topic) else { throw URLError(.badURL) }
        var req = URLRequest(url: u)
        req.httpMethod = "PUT"
        req.setValue(filename, forHTTPHeaderField: "Filename")
        req.setValue("PhoneBridge guard", forHTTPHeaderField: "Message")
        req.setValue("high", forHTTPHeaderField: "Priority")
        req.timeoutInterval = 45
        let (_, resp) = try await URLSession.shared.upload(for: req, from: body)
        guard (resp as? HTTPURLResponse)?.statusCode == 200 else { throw URLError(.badServerResponse) }
    }

    /// Streams messages on [topic] until the connection drops or the task is cancelled. [since] is a unix time or a duration like "12h".
    func subscribe(topic: String, since: String, onMessage: @escaping (String, Int) -> Void) async throws {
        guard let u = url(topic, "/json?since=\(since)") else { throw URLError(.badURL) }
        var req = URLRequest(url: u)
        req.timeoutInterval = 120 // ntfy sends a keepalive about every 45 s
        let (bytes, resp) = try await URLSession.shared.bytes(for: req)
        guard (resp as? HTTPURLResponse)?.statusCode == 200 else { throw URLError(.badServerResponse) }
        for try await line in bytes.lines {
            guard let data = line.data(using: .utf8), let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  o["event"] as? String == "message", let message = o["message"] as? String else { continue }
            onMessage(message, o["time"] as? Int ?? Int(Date().timeIntervalSince1970))
        }
    }
}
