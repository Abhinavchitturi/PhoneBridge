import CryptoKit
import Foundation

/// Secrets in the macOS Keychain (encrypted at rest, only readable by this signed app).
/// All of them live in one Keychain item, so macOS asks at most once (not once per secret) when it wants the
/// password — which happens after each new build, because the build is a different binary than the one that saved them.
enum Keychain {
    private static let service = "app.phonebridge.mac"
    private static let bundleAccount = "secrets"
    private static let legacyAccounts = ["identity", "trustedPhone", "historyKey"]
    private static let lock = NSRecursiveLock()
    nonisolated(unsafe) private static var cache: [String: Data]?

    static func get(_ account: String) -> Data? {
        lock.lock(); defer { lock.unlock() }
        return load()[account]
    }

    static func set(_ data: Data, _ account: String) {
        lock.lock(); defer { lock.unlock() }
        var all = load()
        all[account] = data
        save(all)
    }

    static func delete(_ account: String) {
        lock.lock(); defer { lock.unlock() }
        var all = load()
        guard all.removeValue(forKey: account) != nil else { return }
        save(all)
    }

    private static func load() -> [String: Data] {
        if let cache { return cache }
        var all: [String: Data] = [:]
        if let blob = read(bundleAccount),
           let dict = try? PropertyListSerialization.propertyList(from: blob, format: nil) as? [String: Data] {
            all = dict
            // Re-save once per build: the item then belongs to this build, so it won't ask again until the next update.
            let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? ""
            if UserDefaults.standard.string(forKey: "keychainBuild") != build {
                write(blob, bundleAccount)
                UserDefaults.standard.set(build, forKey: "keychainBuild")
            }
        } else {
            // Older versions kept one item per secret: fold them into the single item.
            for account in legacyAccounts { if let d = read(account) { all[account] = d } }
            if !all.isEmpty {
                save(all)
                legacyAccounts.forEach(remove)
            }
        }
        cache = all
        return all
    }

    private static func save(_ all: [String: Data]) {
        cache = all
        guard let blob = try? PropertyListSerialization.data(fromPropertyList: all, format: .binary, options: 0) else { return }
        write(blob, bundleAccount)
        UserDefaults.standard.set(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "",
                                  forKey: "keychainBuild")
    }

    private static func read(_ account: String) -> Data? {
        let q: [CFString: Any] = [kSecClass: kSecClassGenericPassword, kSecAttrService: service,
                                  kSecAttrAccount: account, kSecReturnData: true, kSecMatchLimit: kSecMatchLimitOne]
        var out: CFTypeRef?
        return SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess ? out as? Data : nil
    }

    private static func write(_ data: Data, _ account: String) {
        remove(account)
        let q: [CFString: Any] = [kSecClass: kSecClassGenericPassword, kSecAttrService: service, kSecAttrAccount: account,
                                  kSecValueData: data, kSecAttrAccessible: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        SecItemAdd(q as CFDictionary, nil)
    }

    private static func remove(_ account: String) {
        let q: [CFString: Any] = [kSecClass: kSecClassGenericPassword, kSecAttrService: service, kSecAttrAccount: account]
        SecItemDelete(q as CFDictionary)
    }

    /// Value from the Keychain, moving it out of plain UserDefaults first if an older version left it there.
    static func migrated(_ account: String, fromDefaults key: String) -> Data? {
        if let d = get(account) { return d }
        guard let old = UserDefaults.standard.data(forKey: key) else { return nil }
        set(old, account)
        UserDefaults.standard.removeObject(forKey: key)
        return old
    }
}

/// X25519 identity + session crypto. Must stay byte-for-byte compatible with android/.../Crypto.kt:
///  - pairing code = first 4 bytes (big-endian) of SHA-256(static shared) mod 1,000,000
///  - session keys = HKDF-SHA256(ikm = ss || ee || es || se, salt = macNonce || phoneNonce, info = "m2p" / "p2m")
///    where s = long-term (pairing) keys, e = fresh per-connection keys, first letter = the Mac's key.
///    The ephemeral keys give forward secrecy: a stolen pairing key can't decrypt earlier sessions.
///  - AES-256-GCM, nonce = 4 zero bytes || 8-byte big-endian counter, wire = ciphertext || tag
enum Keys {
    static let privateKey: Curve25519.KeyAgreement.PrivateKey = {
        if let d = Keychain.migrated("identity", fromDefaults: "privateKey"),
           let k = try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: d) {
            return k
        }
        let k = Curve25519.KeyAgreement.PrivateKey()
        Keychain.set(k.rawRepresentation, "identity")
        return k
    }()

    static var publicRaw: Data { privateKey.publicKey.rawRepresentation }

    /// The paired phone's public key (in the Keychain, so it can't be swapped by editing preferences).
    static var trustedPhone: Data? {
        get {
            lock.lock(); defer { lock.unlock() }
            if let cached = trustedCache { return cached }
            trustedCache = .some(Keychain.migrated("trustedPhone", fromDefaults: "phonePub"))
            return trustedCache!
        }
        set {
            lock.lock(); defer { lock.unlock() }
            if let newValue { Keychain.set(newValue, "trustedPhone") } else { Keychain.delete("trustedPhone") }
            trustedCache = .some(newValue)
        }
    }
    // Read on every Bluetooth sighting, so keep a copy in memory (nil = not loaded yet).
    nonisolated(unsafe) private static var trustedCache: Data??
    private static let lock = NSLock()

    static func dh(_ priv: Curve25519.KeyAgreement.PrivateKey, _ peer: Data) throws -> Data {
        let pk = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: peer)
        return try priv.sharedSecretFromKeyAgreement(with: pk).withUnsafeBytes { Data($0) }
    }

    static func shared(with peer: Data) throws -> Data { try dh(privateKey, peer) }

    static func pairingCode(_ shared: Data) -> String {
        let h = Data(SHA256.hash(data: shared))
        let n = h.prefix(4).reduce(UInt32(0)) { $0 << 8 | UInt32($1) }
        return String(format: "%06d", n % 1_000_000)
    }

    /// ss || ee || es || se, from the Mac's side (static + this connection's ephemeral key).
    static func sessionIKM(staticKey: Curve25519.KeyAgreement.PrivateKey, ephemeral: Curve25519.KeyAgreement.PrivateKey,
                           peerStatic: Data, peerEphemeral: Data) throws -> Data {
        try dh(staticKey, peerStatic) + dh(ephemeral, peerEphemeral) + dh(ephemeral, peerStatic) + dh(staticKey, peerEphemeral)
    }

    static func sessionKey(_ ikm: Data, salt: Data, info: String) -> SymmetricKey {
        HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: ikm), salt: salt,
                               info: Data(info.utf8), outputByteCount: 32)
    }
}

/// One direction of an encrypted session; the counter makes every nonce unique.
final class Sealer {
    private let key: SymmetricKey
    private var counter: UInt64 = 0

    init(_ key: SymmetricKey) { self.key = key }

    func seal(_ plain: Data) throws -> Data {
        let box = try AES.GCM.seal(plain, using: key, nonce: nextNonce())
        return box.ciphertext + box.tag
    }

    func open(_ sealed: Data) throws -> Data {
        guard sealed.count >= 16 else { throw CryptoKitError.incorrectParameterSize }
        let box = try AES.GCM.SealedBox(nonce: nextNonce(), ciphertext: sealed.dropLast(16), tag: sealed.suffix(16))
        return try AES.GCM.open(box, using: key)
    }

    private func nextNonce() -> AES.GCM.Nonce {
        var bytes = Data(count: 4)
        withUnsafeBytes(of: counter.bigEndian) { bytes.append(contentsOf: $0) }
        counter += 1
        return try! AES.GCM.Nonce(data: bytes)
    }
}
