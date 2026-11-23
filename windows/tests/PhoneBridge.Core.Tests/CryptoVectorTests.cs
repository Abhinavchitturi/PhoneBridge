using System.Security.Cryptography;
using System.Text;
using PhoneBridge.Core;

namespace PhoneBridge.Core.Tests;

/// <summary>
/// Fixed vectors produced by the Mac's own CryptoKit code (windows/tools/vectors.swift, run with `swift vectors.swift`),
/// using the same formulas as mac/Sources/Crypto.swift. The static keys are RFC 7748's Alice (computer) and Bob (phone);
/// the ephemeral keys are 32 × 0x11 (computer) and 32 × 0x22 (phone).
/// The phone side is computed here the way android/.../Crypto.kt does it (its own DH order), so a match proves all three agree.
/// </summary>
public class CryptoVectorTests
{
    private static byte[] H(string hex) => Convert.FromHexString(hex);

    private static readonly byte[] ComputerStatic = H("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a");
    private static readonly byte[] PhoneStatic = H("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb");
    private static readonly byte[] ComputerEph = Enumerable.Repeat((byte)0x11, 32).ToArray();
    private static readonly byte[] PhoneEph = Enumerable.Repeat((byte)0x22, 32).ToArray();
    private static readonly byte[] ComputerNonce = H("000102030405060708090a0b0c0d0e0f");
    private static readonly byte[] PhoneNonce = H("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff");
    private static readonly byte[] Salt = [.. ComputerNonce, .. PhoneNonce];

    private const string Ikm =
        "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742" +
        "9e004098efc091d4ec2663b4e9f5cfd4d7064571690b4bea97ab146ab9f35056" +
        "5035c91b66814f8a39664a505e0a50b6375ee0ef9b3fe7220bde56dc2dc77746" +
        "d8fbd109c632909addb6bf64f433792d1382ef2824934460b5fdc4f847f82a2b";

    [Fact]
    public void PublicKeysMatch()
    {
        Assert.Equal(H("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"), X25519.PublicKey(ComputerStatic));
        Assert.Equal(H("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"), X25519.PublicKey(PhoneStatic));
        Assert.Equal(H("7b4e909bbe7ffe44c465a220037d608ee35897d31ef972f07f74892cb0f73f13"), X25519.PublicKey(ComputerEph));
        Assert.Equal(H("0faa684ed28867b97f4a6a2dee5df8ce974e76b7018e3f22a1c4cf2678570f20"), X25519.PublicKey(PhoneEph));
    }

    [Fact]
    public void StaticSharedAndPairingCodeMatch()
    {
        var shared = Keys.Dh(ComputerStatic, X25519.PublicKey(PhoneStatic));
        Assert.Equal(H("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"), shared);
        Assert.Equal(shared, Keys.Dh(PhoneStatic, X25519.PublicKey(ComputerStatic)));
        Assert.Equal("897505", Keys.PairingCode(shared));
    }

    [Fact]
    public void SessionIkmMatchesFromBothSides()
    {
        var computerSide = Keys.SessionIkm(ComputerStatic, ComputerEph, X25519.PublicKey(PhoneStatic), X25519.PublicKey(PhoneEph));
        Assert.Equal(H(Ikm), computerSide);

        // Crypto.kt: dh(myStatic, macStatic) + dh(myEph, macEph) + dh(myStatic, macEph) + dh(myEph, macStatic)
        var cs = X25519.PublicKey(ComputerStatic);
        var ce = X25519.PublicKey(ComputerEph);
        byte[] phoneSide = [.. Keys.Dh(PhoneStatic, cs), .. Keys.Dh(PhoneEph, ce), .. Keys.Dh(PhoneStatic, ce), .. Keys.Dh(PhoneEph, cs)];
        Assert.Equal(computerSide, phoneSide);
    }

    [Fact]
    public void SessionKeysMatch()
    {
        Assert.Equal(H("3b01c227ba956c7864180576a8616e7a30456d81a099a72a713eb6b8f677e321"), Keys.SessionKey(H(Ikm), Salt, "m2p"));
        Assert.Equal(H("6bc5df283192488ac06a018dd525f011267b6ebca91013a29986364a704f2912"), Keys.SessionKey(H(Ikm), Salt, "p2m"));
        // Crypto.kt's hand-rolled single-block HKDF gives the same 32 bytes.
        Assert.Equal(Keys.SessionKey(H(Ikm), Salt, "m2p"), KotlinHkdf(H(Ikm), Salt, "m2p"));
    }

    [Fact]
    public void SealedFramesMatch()
    {
        var m2p = Keys.SessionKey(H(Ikm), Salt, "m2p");
        var p2m = Keys.SessionKey(H(Ikm), Salt, "p2m");
        using (var s = new Sealer(m2p))
        {
            Assert.Equal(H("a4d1180af9e10b65de514bc7f5e4a48e82e270cb0587c28d6ee72de3"), s.Seal(Encoding.UTF8.GetBytes("{\"t\":\"ping\"}")));
            Assert.Equal(H("017e5c47d12bdf77e5e878b2eefb4ba79ddcf52d865ea13318b37416eea1d5957e702c0977c2c1c42a01902d"),
                         s.Seal(Encoding.UTF8.GetBytes("{\"t\":\"clip\",\"text\":\"héllo\"}")));
        }
        using (var r = new Sealer(m2p))
        {
            Assert.Equal("{\"t\":\"ping\"}", Encoding.UTF8.GetString(r.Open(H("a4d1180af9e10b65de514bc7f5e4a48e82e270cb0587c28d6ee72de3"))));
        }
        using (var r = new Sealer(p2m))
        {
            Assert.Equal("{\"t\":\"pong\"}", Encoding.UTF8.GetString(r.Open(H("c38d69a835dc346060544406e8dc98afd402dad854e855a39a6369b9"))));
        }
    }

    [Fact]
    public void OpeningOutOfOrderOrTamperedFails()
    {
        var key = Keys.SessionKey(H(Ikm), Salt, "m2p");
        using var s = new Sealer(key);
        var first = s.Seal("a"u8);
        var second = s.Seal("b"u8);
        using (var r = new Sealer(key)) Assert.ThrowsAny<CryptographicException>(() => r.Open(second)); // counter 0 ≠ 1
        first[0] ^= 1;
        using (var r = new Sealer(key)) Assert.ThrowsAny<CryptographicException>(() => r.Open(first));
    }

    [Fact]
    public void Rfc7748Vectors()
    {
        // §5.2, first scalar-multiplication vector
        Assert.Equal(H("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552"),
            X25519.ScalarMult(H("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"),
                              H("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")));
        // §6.1 Alice/Bob
        Assert.Equal(H("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"), X25519.PublicKey(PhoneStatic));
    }

    [Fact]
    public void LowOrderPeerKeyIsRefused()
    {
        Assert.ThrowsAny<CryptographicException>(() => Keys.Dh(ComputerStatic, new byte[32]));
        Assert.ThrowsAny<CryptographicException>(() => Keys.Dh(ComputerStatic, new byte[31]));
    }

    private static byte[] KotlinHkdf(byte[] ikm, byte[] salt, string info)
    {
        var prk = HMACSHA256.HashData(salt, ikm);
        byte[] msg = [.. Encoding.UTF8.GetBytes(info), 1];
        return HMACSHA256.HashData(prk, msg);
    }
}
