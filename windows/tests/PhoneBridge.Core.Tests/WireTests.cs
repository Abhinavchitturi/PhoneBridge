using System.Text.Json.Nodes;
using PhoneBridge.Core;

namespace PhoneBridge.Core.Tests;

public class WireTests
{
    [Fact]
    public async Task FramesAreBigEndianLengthPrefixed()
    {
        var ms = new MemoryStream();
        await Framing.WriteAsync(ms, "{\"t\":\"ping\"}"u8.ToArray());
        Assert.Equal(new byte[] { 0, 0, 0, 12 }, ms.ToArray()[..4]);
        ms.Position = 0;
        Assert.Equal("{\"t\":\"ping\"}"u8.ToArray(), await Framing.ReadAsync(ms));
    }

    [Theory]
    [InlineData(new byte[] { 0, 0, 0, 0 })]
    [InlineData(new byte[] { 0x00, 0xF4, 0x24, 0x00 })] // 16,000,000
    public async Task BadFrameLengthsAreRefused(byte[] header)
    {
        await Assert.ThrowsAsync<InvalidDataException>(() => Framing.ReadAsync(new MemoryStream(header)));
    }

    [Fact]
    public void MdnsQueryAsksForPtrWithUnicastBit()
    {
        var q = Discovery.BuildQuery("_phonebridge._tcp.local", 12);
        Assert.Equal(Convert.FromHexString("000000000001000000000000"), q[..12]);
        Assert.Equal("\u000c_phonebridge\u0004_tcp\u0005local\0"u8.ToArray(), q[12..^4]);
        Assert.Equal(new byte[] { 0, 12, 0x80, 1 }, q[^4..]);
    }

    /// <summary>A response shaped like Android NSD's: PTR answer, SRV + A in additionals, with name compression.</summary>
    [Fact]
    public void MdnsResponseGivesSrvPort()
    {
        var ms = new MemoryStream();
        ms.Write(Convert.FromHexString("0000" + "8400" + "0000" + "0001" + "0000" + "0002"));
        var serviceAt = (int)ms.Position; // 12
        Discovery.WriteName(ms, "_phonebridge._tcp.local");
        ms.Write(Convert.FromHexString("000c" + "0001" + "00001194"));
        // rdata: "Pixel 9" + pointer to the service name
        var rdata = new MemoryStream();
        rdata.WriteByte(7);
        rdata.Write("Pixel 9"u8);
        rdata.Write([0xC0, (byte)serviceAt]);
        ms.Write([0, (byte)rdata.Length]);
        var instanceAt = (int)ms.Position;
        ms.Write(rdata.ToArray());
        // SRV: name = pointer to the instance name, port 47803
        ms.Write([0xC0, (byte)instanceAt]);
        ms.Write(Convert.FromHexString("0021" + "8001" + "00000078" + "0010" + "0000" + "0000" + "babb"));
        ms.Write([7]);
        ms.Write("android"u8);
        ms.Write([0xC0, (byte)(serviceAt + 1 + 12 + 1 + 4)]); // "local"
        // A record
        ms.Write([7]);
        ms.Write("android"u8);
        ms.Write([0xC0, (byte)(serviceAt + 1 + 12 + 1 + 4)]);
        ms.Write(Convert.FromHexString("0001" + "8001" + "00000078" + "0004" + "c0a80105"));
        Assert.Equal(47803, Discovery.ParseResponse(ms.ToArray(), Discovery.ServiceName));
    }

    /// <summary>
    /// A real answer from a phone running PhoneBridge 0.23 (Android NSD), captured with a legacy-unicast PTR query.
    /// Only the device name, host name and IPv6 address were swapped for same-length placeholders.
    /// </summary>
    [Fact]
    public void MdnsParsesRealAndroidAnswer()
    {
        var packet = Convert.FromHexString(
            "0000840000000001000000060c5f70686f6e65627269646765045f746370056c6f63616c00000c00010000119400100d4578616d706c65" +
            "2050686f6e65c00c10416e64726f69645f54455354484f5354c01e001c8001000000780010fe800000000000000000000000000001c02f" +
            "002f8001000000780009c02f00050000800040c03f00018001000000780004c0a80167c02f0010800100001194000100c02f0021800100" +
            "000078000800000000bab8c03fc03f002f8001000000780008c03f000440000008");
        Assert.Equal(47800, Discovery.ParseResponse(packet, Discovery.ServiceName));
    }

    [Fact]
    public void MdnsIgnoresOtherServicesAndQueries()
    {
        var other = new MemoryStream();
        other.Write(Convert.FromHexString("000084000000000100000000"));
        Discovery.WriteName(other, "_googlecast._tcp.local");
        other.Write(Convert.FromHexString("000c000100001194000201ff"));
        Assert.Null(Discovery.ParseResponse(other.ToArray(), Discovery.ServiceName));
        Assert.Null(Discovery.ParseResponse(Discovery.BuildQuery(Discovery.ServiceName, 12), Discovery.ServiceName));
        Assert.Null(Discovery.ParseResponse(new byte[] { 0, 0, 0x84 }, Discovery.ServiceName));
        // A pointer loop must not hang.
        var loop = Convert.FromHexString("000084000000000100000000" + "c00c" + "000c0001000011940000");
        Assert.Null(Discovery.ParseResponse(loop, Discovery.ServiceName));
    }

    [Fact]
    public async Task FileRoundTripsThroughTransferMessages()
    {
        var dir = Path.Combine(Path.GetTempPath(), "pb-xfer-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(dir);
        try
        {
            var src = Path.Combine(dir, "photo.jpg");
            var data = new byte[Transfers.Chunk * 2 + 123];
            new Random(4).NextBytes(data);
            await File.WriteAllBytesAsync(src, data);
            var inbox = Path.Combine(dir, "Downloads");
            var t = new Transfers(inbox);
            Directory.CreateDirectory(inbox);
            File.WriteAllText(Path.Combine(inbox, "photo.jpg"), "already here");
            string? got = null;
            t.Received += (p, _) => got = p;
            var sent = new List<JsonObject>();
            Assert.True(await Transfers.SendAsync(src, m => { sent.Add(m); t.Handle(m); return Task.FromResult(true); }));
            Assert.Equal(["file_start", "file_chunk", "file_chunk", "file_chunk", "file_end"], sent.Select(m => (string)m["t"]!));
            Assert.Equal("image/jpeg", (string?)sent[0]["mime"]);
            Assert.Equal(data.Length, (long)sent[0]["size"]!);
            Assert.Equal(Path.Combine(inbox, "photo (2).jpg"), got);
            Assert.Equal(data, await File.ReadAllBytesAsync(got!));
        }
        finally
        {
            Directory.Delete(dir, true);
        }
    }

    [Theory]
    [InlineData("../../evil.exe", ".._.._evil.exe")]
    [InlineData("a\\b:c.txt", "a_b_c.txt")]
    [InlineData("CON.txt", "_CON.txt")]
    [InlineData("..", "file")]
    [InlineData("report. ", "report")]
    public void IncomingNamesCantEscapeTheFolder(string sent, string saved)
    {
        Assert.Equal(saved, Transfers.SafeName(sent));
    }
}
