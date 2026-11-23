using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;

namespace PhoneBridge.Core;

/// <summary>
/// Identity + session crypto. Must stay byte-for-byte compatible with mac/Sources/Crypto.swift and
/// android/.../Crypto.kt (the tests check this against vectors produced by the Mac's CryptoKit code):
///  - pairing code = first 4 bytes (big-endian) of SHA-256(static shared) mod 1,000,000
///  - session keys = HKDF-SHA256(ikm = ss || ee || es || se, salt = computerNonce || phoneNonce, info = "m2p" / "p2m")
///    where s = long-term (pairing) keys, e = fresh per-connection keys, first letter = the computer's key.
///    ("m2p" = computer to phone; the names date from when the computer was always a Mac.)
///  - AES-256-GCM, nonce = 4 zero bytes || 8-byte big-endian counter, wire = ciphertext || tag
/// </summary>
public static class Keys
{
    public static byte[] NewPrivateKey() => RandomNumberGenerator.GetBytes(32);

    /// <summary>X25519 that refuses low-order peer keys (an all-zero result), like the phone does.</summary>
    public static byte[] Dh(ReadOnlySpan<byte> priv, ReadOnlySpan<byte> peer)
    {
        if (peer.Length != 32) throw new CryptographicException("bad public key");
        var s = X25519.SharedSecret(priv, peer);
        if (s.All(b => b == 0)) throw new CryptographicException("invalid public key");
        return s;
    }

    public static string PairingCode(ReadOnlySpan<byte> staticShared)
    {
        var h = SHA256.HashData(staticShared);
        var n = BinaryPrimitives.ReadUInt32BigEndian(h);
        return (n % 1_000_000).ToString("D6");
    }

    /// <summary>ss || ee || es || se, from the computer's side (its static key + this connection's ephemeral key).</summary>
    public static byte[] SessionIkm(ReadOnlySpan<byte> myStatic, ReadOnlySpan<byte> myEphemeral,
                                    ReadOnlySpan<byte> peerStatic, ReadOnlySpan<byte> peerEphemeral) =>
        [.. Dh(myStatic, peerStatic), .. Dh(myEphemeral, peerEphemeral), .. Dh(myEphemeral, peerStatic), .. Dh(myStatic, peerEphemeral)];

    public static byte[] SessionKey(byte[] ikm, byte[] salt, string info) =>
        HKDF.DeriveKey(HashAlgorithmName.SHA256, ikm, 32, salt, Encoding.UTF8.GetBytes(info));
}

/// <summary>One direction of an encrypted session; the counter makes every nonce unique. Not thread-safe.</summary>
public sealed class Sealer : IDisposable
{
    private const int TagSize = 16;
    private readonly AesGcm _aes;
    private ulong _counter;

    public Sealer(byte[] key) => _aes = new AesGcm(key, TagSize);

    public byte[] Seal(ReadOnlySpan<byte> plain)
    {
        var outp = new byte[plain.Length + TagSize];
        _aes.Encrypt(NextNonce(), plain, outp.AsSpan(0, plain.Length), outp.AsSpan(plain.Length));
        return outp;
    }

    public byte[] Open(ReadOnlySpan<byte> sealedData)
    {
        if (sealedData.Length < TagSize) throw new CryptographicException("frame too short");
        var n = sealedData.Length - TagSize;
        var plain = new byte[n];
        _aes.Decrypt(NextNonce(), sealedData[..n], sealedData[n..], plain);
        return plain;
    }

    private byte[] NextNonce()
    {
        var nonce = new byte[12];
        BinaryPrimitives.WriteUInt64BigEndian(nonce.AsSpan(4), _counter++);
        return nonce;
    }

    public void Dispose() => _aes.Dispose();
}
