import CryptoKit
import Foundation
func hex(_ d: Data) -> String { d.map { String(format: "%02x", $0) }.joined() }
func unhex(_ s: String) -> Data { var d = Data(); var i = s.startIndex; while i < s.endIndex { let j = s.index(i, offsetBy: 2); d.append(UInt8(s[i..<j], radix: 16)!); i = j }; return d }
func key(_ h: String) -> Curve25519.KeyAgreement.PrivateKey { try! .init(rawRepresentation: unhex(h)) }
func dh(_ k: Curve25519.KeyAgreement.PrivateKey, _ p: Data) -> Data { try! k.sharedSecretFromKeyAgreement(with: .init(rawRepresentation: p)).withUnsafeBytes { Data($0) } }
let macS = key("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
let phoneS = key("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
let macE = key(String(repeating: "11", count: 32))
let phoneE = key(String(repeating: "22", count: 32))
let macNonce = unhex("000102030405060708090a0b0c0d0e0f")
let phoneNonce = unhex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff")
let shared = dh(macS, phoneS.publicKey.rawRepresentation)
let h = Data(SHA256.hash(data: shared))
let n = h.prefix(4).reduce(UInt32(0)) { $0 << 8 | UInt32($1) }
let ikm = dh(macS, phoneS.publicKey.rawRepresentation) + dh(macE, phoneE.publicKey.rawRepresentation) + dh(macE, phoneS.publicKey.rawRepresentation) + dh(macS, phoneE.publicKey.rawRepresentation)
let salt = macNonce + phoneNonce
func k(_ info: String) -> SymmetricKey { HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: ikm), salt: salt, info: Data(info.utf8), outputByteCount: 32) }
func kd(_ s: SymmetricKey) -> Data { s.withUnsafeBytes { Data($0) } }
func seal(_ key: SymmetricKey, _ ctr: UInt64, _ m: String) -> Data { var b = Data(count: 4); withUnsafeBytes(of: ctr.bigEndian) { b.append(contentsOf: $0) }; let box = try! AES.GCM.seal(Data(m.utf8), using: key, nonce: try! .init(data: b)); return box.ciphertext + box.tag }
print("macStaticPub", hex(macS.publicKey.rawRepresentation))
print("phoneStaticPub", hex(phoneS.publicKey.rawRepresentation))
print("macEphPub", hex(macE.publicKey.rawRepresentation))
print("phoneEphPub", hex(phoneE.publicKey.rawRepresentation))
print("shared", hex(shared))
print("code", String(format: "%06d", n % 1_000_000))
print("ikm", hex(ikm))
print("m2p", hex(kd(k("m2p"))))
print("p2m", hex(kd(k("p2m"))))
print("m2p_seal0", hex(seal(k("m2p"), 0, "{\"t\":\"ping\"}")))
print("m2p_seal1", hex(seal(k("m2p"), 1, "{\"t\":\"clip\",\"text\":\"héllo\"}")))
print("p2m_seal0", hex(seal(k("p2m"), 0, "{\"t\":\"pong\"}")))
