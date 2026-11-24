using System.Buffers.Binary;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;

namespace PhoneBridge.Core;

/// <summary>
/// Finds the phone's "_phonebridge._tcp" service (Android NSD) with a minimal mDNS client — no Bonjour install needed.
/// Its main query goes out from port 5353 itself (shared with Windows' own mDNS service, as Chrome does), joined to the
/// mDNS group, so the phone answers to the group the normal way — Android's responder ignores the "legacy unicast"
/// queries (from an ordinary port, RFC 6762 §6.7) this sent before, which is why Windows never found the phone by
/// itself. A legacy query is still sent too, for responders that only answer those.
/// The phone's address is taken from where the answer came from (it is the phone), the port from the SRV record.
/// </summary>
public static class Discovery
{
    public const string ServiceName = "_phonebridge._tcp.local";
    private static readonly IPEndPoint MdnsGroup = new(IPAddress.Parse("224.0.0.251"), 5353);

    /// <summary>Asks every IPv4 interface and collects answers for <paramref name="wait"/>.</summary>
    public static async Task<List<IPEndPoint>> BrowseAsync(TimeSpan wait, CancellationToken ct = default)
    {
        var found = new List<IPEndPoint>();
        var query = BuildQuery(ServiceName, 12 /* PTR */);
        var sockets = new List<Socket>();
        try
        {
            // The normal mDNS way: ask from port 5353, the phone answers to the group (what Bonjour on the Mac does).
            if (JoinGroup() is { } group)
            {
                sockets.Add(group);
                foreach (var local in LocalIPv4Addresses())
                {
                    try
                    {
                        group.SetSocketOption(SocketOptionLevel.IP, SocketOptionName.MulticastInterface, local.GetAddressBytes());
                        group.SetSocketOption(SocketOptionLevel.IP, SocketOptionName.MulticastTimeToLive, 255);
                        await group.SendToAsync(query, SocketFlags.None, MdnsGroup, ct);
                    }
                    catch (SocketException) { }
                }
            }
            foreach (var local in LocalIPv4Addresses())
            {
                try
                {
                    var s = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
                    s.Bind(new IPEndPoint(local, 0));
                    s.SetSocketOption(SocketOptionLevel.IP, SocketOptionName.MulticastInterface, local.GetAddressBytes());
                    s.SetSocketOption(SocketOptionLevel.IP, SocketOptionName.MulticastTimeToLive, 255);
                    await s.SendToAsync(query, SocketFlags.None, MdnsGroup, ct);
                    sockets.Add(s);
                }
                catch (SocketException)
                {
                    // interface went away or doesn't do multicast; the others may still work
                }
            }
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeout.CancelAfter(wait);
            await Task.WhenAll(sockets.Select(async s =>
            {
                var b = new byte[9000];
                while (!timeout.IsCancellationRequested)
                {
                    try
                    {
                        var r = await s.ReceiveFromAsync(b, SocketFlags.None, new IPEndPoint(IPAddress.Any, 0), timeout.Token);
                        var port = ParseResponse(b.AsSpan(0, r.ReceivedBytes), ServiceName);
                        if (port != null && r.RemoteEndPoint is IPEndPoint from)
                        {
                            var ep = new IPEndPoint(from.Address, port.Value);
                            lock (found) if (!found.Contains(ep)) found.Add(ep);
                        }
                    }
                    catch (OperationCanceledException) { break; }
                    catch (SocketException) { break; }
                }
            }));
        }
        finally
        {
            foreach (var s in sockets) s.Dispose();
        }
        return found;
    }

    private static Socket? JoinGroup()
    {
        var s = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
        try
        {
            s.ExclusiveAddressUse = false;
            s.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            s.SetSocketOption(SocketOptionLevel.IP, SocketOptionName.MulticastLoopback, false);
            s.Bind(new IPEndPoint(IPAddress.Any, 5353));
            var joined = false;
            foreach (var local in LocalIPv4Addresses())
            {
                try
                {
                    s.SetSocketOption(SocketOptionLevel.IP, SocketOptionName.AddMembership, new MulticastOption(MdnsGroup.Address, local));
                    joined = true;
                }
                catch (SocketException) { }
            }
            if (joined) return s;
        }
        catch (SocketException) { }
        s.Dispose();
        return null;
    }

    internal static IEnumerable<IPAddress> LocalIPv4Addresses()
    {
        NetworkInterface[] nics;
        try { nics = NetworkInterface.GetAllNetworkInterfaces(); }
        catch (NetworkInformationException) { yield break; }
        foreach (var nic in nics)
        {
            if (nic.OperationalStatus != OperationalStatus.Up || nic.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
            foreach (var a in nic.GetIPProperties().UnicastAddresses)
                if (a.Address.AddressFamily == AddressFamily.InterNetwork) yield return a.Address;
        }
    }

    /// <summary>Default gateways — when this PC is on the phone's hotspot, the gateway is the phone.</summary>
    public static List<IPAddress> DefaultGateways()
    {
        var list = new List<IPAddress>();
        try
        {
            foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (nic.OperationalStatus != OperationalStatus.Up) continue;
                foreach (var g in nic.GetIPProperties().GatewayAddresses)
                    if (g.Address.AddressFamily == AddressFamily.InterNetwork && !g.Address.Equals(IPAddress.Any) && !list.Contains(g.Address))
                        list.Add(g.Address);
            }
        }
        catch (NetworkInformationException) { }
        return list;
    }

    // MARK: DNS wire format (just enough for one PTR question and its answers)

    /// <summary>A standard query: id 0, one question, class IN with the "unicast response please" bit.</summary>
    internal static byte[] BuildQuery(string name, ushort type)
    {
        var ms = new MemoryStream();
        ms.Write([0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0]);
        WriteName(ms, name);
        ms.Write([(byte)(type >> 8), (byte)type, 0x80, 0x01]);
        return ms.ToArray();
    }

    internal static void WriteName(Stream s, string name)
    {
        foreach (var label in name.TrimEnd('.').Split('.'))
        {
            var b = Encoding.UTF8.GetBytes(label);
            s.WriteByte((byte)b.Length);
            s.Write(b);
        }
        s.WriteByte(0);
    }

    /// <summary>
    /// If this mDNS response announces an instance of <paramref name="service"/>, returns its port
    /// (from the SRV record when present, else the standard port). Null for unrelated or malformed packets.
    /// </summary>
    internal static int? ParseResponse(ReadOnlySpan<byte> p, string service)
    {
        try
        {
            if (p.Length < 12) return null;
            var flags = BinaryPrimitives.ReadUInt16BigEndian(p[2..]);
            if ((flags & 0x8000) == 0) return null; // a query, not a response
            int qd = BinaryPrimitives.ReadUInt16BigEndian(p[4..]);
            int rr = BinaryPrimitives.ReadUInt16BigEndian(p[6..]) + BinaryPrimitives.ReadUInt16BigEndian(p[8..]) + BinaryPrimitives.ReadUInt16BigEndian(p[10..]);
            var pos = 12;
            for (var i = 0; i < qd; i++)
            {
                ReadName(p, ref pos);
                pos += 4;
            }
            var instances = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            var srv = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);
            for (var i = 0; i < rr; i++)
            {
                var name = ReadName(p, ref pos);
                var type = BinaryPrimitives.ReadUInt16BigEndian(p[pos..]);
                var len = BinaryPrimitives.ReadUInt16BigEndian(p[(pos + 8)..]);
                var data = pos + 10;
                if (data + len > p.Length) return null;
                if (type == 12 && name.Equals(service, StringComparison.OrdinalIgnoreCase))
                {
                    var at = data;
                    instances.Add(ReadName(p, ref at));
                }
                else if (type == 33 && len >= 6)
                {
                    srv[name] = BinaryPrimitives.ReadUInt16BigEndian(p[(data + 4)..]);
                }
                pos = data + len;
            }
            // An answer can also be just the SRV record of an instance (no PTR), e.g. to a follow-up query.
            foreach (var (name, port) in srv)
                if (name.EndsWith("." + service, StringComparison.OrdinalIgnoreCase)) return port;
            if (instances.Count > 0) return PhoneLink.Port;
            return null;
        }
        catch (Exception e) when (e is ArgumentOutOfRangeException or IndexOutOfRangeException or FormatException)
        {
            return null;
        }
    }

    internal static string ReadName(ReadOnlySpan<byte> p, ref int pos)
    {
        var labels = new List<string>();
        var at = pos;
        var jumped = false;
        for (var hops = 0; hops < 64; hops++)
        {
            int len = p[at];
            if (len == 0)
            {
                if (!jumped) pos = at + 1;
                return string.Join('.', labels);
            }
            if ((len & 0xC0) == 0xC0)
            {
                var target = ((len & 0x3F) << 8) | p[at + 1];
                if (!jumped) pos = at + 2;
                jumped = true;
                at = target;
                continue;
            }
            labels.Add(Encoding.UTF8.GetString(p.Slice(at + 1, len)));
            at += 1 + len;
        }
        throw new FormatException("name compression loop");
    }
}
