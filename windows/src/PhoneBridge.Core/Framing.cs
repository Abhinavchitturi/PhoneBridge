using System.Buffers.Binary;

namespace PhoneBridge.Core;

/// <summary>Frames on the wire: 4-byte big-endian length, then the payload (plain JSON during the handshake, AES-GCM after).</summary>
public static class Framing
{
    public const int MaxFrame = 16_000_000;

    public static async Task<byte[]> ReadAsync(Stream s, CancellationToken ct = default)
    {
        var header = new byte[4];
        await s.ReadExactlyAsync(header, ct);
        var length = BinaryPrimitives.ReadUInt32BigEndian(header);
        if (length == 0 || length >= MaxFrame) throw new InvalidDataException($"bad frame length {length}");
        var body = new byte[length];
        await s.ReadExactlyAsync(body, ct);
        return body;
    }

    public static async Task WriteAsync(Stream s, ReadOnlyMemory<byte> payload, CancellationToken ct = default)
    {
        var frame = new byte[4 + payload.Length];
        BinaryPrimitives.WriteUInt32BigEndian(frame, (uint)payload.Length);
        payload.CopyTo(frame.AsMemory(4));
        await s.WriteAsync(frame, ct); // one write, so a frame never interleaves with another
        await s.FlushAsync(ct);
    }
}
