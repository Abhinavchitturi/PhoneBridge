using System.Numerics;

namespace PhoneBridge.Core;

/// <summary>
/// X25519 (RFC 7748), a straight port of android/.../X25519.kt. .NET has no portable Curve25519 API
/// (Windows CNG has one, but not with raw 32-byte keys on every OS), and this keeps the tests runnable anywhere.
/// Not constant-time; fine for a once-per-connection handshake on a personal LAN link, as on the phone.
/// </summary>
public static class X25519
{
    private static readonly BigInteger P = (BigInteger.One << 255) - 19;
    private static readonly BigInteger A24 = 121665;
    private static readonly byte[] Base = MakeBase();

    private static byte[] MakeBase()
    {
        var b = new byte[32];
        b[0] = 9;
        return b;
    }

    public static byte[] PublicKey(ReadOnlySpan<byte> privateKey) => ScalarMult(privateKey, Base);

    public static byte[] SharedSecret(ReadOnlySpan<byte> privateKey, ReadOnlySpan<byte> peerPublic) => ScalarMult(privateKey, peerPublic);

    public static byte[] ScalarMult(ReadOnlySpan<byte> scalar, ReadOnlySpan<byte> u)
    {
        if (scalar.Length != 32 || u.Length != 32) throw new ArgumentException("X25519 keys are 32 bytes");
        var k = scalar.ToArray();
        k[0] &= 248;
        k[31] = (byte)((k[31] & 127) | 64);
        var kn = Decode(k);
        var uc = u.ToArray();
        uc[31] &= 127;
        var x1 = Mod(Decode(uc));

        BigInteger x2 = BigInteger.One, z2 = BigInteger.Zero, x3 = x1, z3 = BigInteger.One;
        var swap = false;
        for (var t = 254; t >= 0; t--)
        {
            var bit = !(kn >> t).IsEven;
            if (swap != bit)
            {
                (x2, x3) = (x3, x2);
                (z2, z3) = (z3, z2);
            }
            swap = bit;
            var a = Mod(x2 + z2);
            var aa = Mod(a * a);
            var b = Mod(x2 - z2);
            var bb = Mod(b * b);
            var e = Mod(aa - bb);
            var c = Mod(x3 + z3);
            var d = Mod(x3 - z3);
            var da = Mod(d * a);
            var cb = Mod(c * b);
            x3 = Mod(BigInteger.Pow(da + cb, 2));
            z3 = Mod(x1 * BigInteger.Pow(da - cb, 2));
            x2 = Mod(aa * bb);
            z2 = Mod(e * (aa + A24 * e));
        }
        if (swap)
        {
            x2 = x3;
            z2 = z3;
        }
        return Encode(Mod(x2 * BigInteger.ModPow(z2, P - 2, P)));
    }

    private static BigInteger Mod(BigInteger n)
    {
        var r = n % P;
        return r.Sign < 0 ? r + P : r;
    }

    private static BigInteger Decode(byte[] le) => new(le, isUnsigned: true, isBigEndian: false);

    private static byte[] Encode(BigInteger n)
    {
        var le = n.ToByteArray(isUnsigned: true, isBigEndian: false);
        var outp = new byte[32];
        Array.Copy(le, outp, Math.Min(32, le.Length));
        return outp;
    }
}
