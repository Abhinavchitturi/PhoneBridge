using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;
using PhoneBridge.Core;

namespace PhoneBridge.Core.Tests;

/// <summary>
/// The phone's side of the link, written from android/.../Link.kt (class Conn): a TCP server that answers the
/// computer's hello with its own v3 hello, then — depending on <see cref="Admit"/> — trusts, offers to pair
/// ("pair", then "trust" once <see cref="ConfirmPairing"/> is called), or rejects with a reason.
/// </summary>
public sealed class FakePhone : IDisposable
{
    public enum AdmitKind { Trust, Pair, Busy, Stranger }

    public readonly byte[] Priv = Keys.NewPrivateKey();
    public byte[] Pub => X25519.PublicKey(Priv);
    public string Name { get; init; } = "Test Pixel";
    public AdmitKind Admit { get; set; } = AdmitKind.Pair;
    public int HelloVersion { get; init; } = 3;

    private readonly TcpListener _listener = new(IPAddress.Loopback, 0);
    private readonly CancellationTokenSource _stop = new();
    private TaskCompletionSource<bool> _pairDecision = new(TaskCreationOptions.RunContinuationsAsynchronously);

    public int Port => ((IPEndPoint)_listener.LocalEndpoint).Port;
    public IPEndPoint Endpoint => new(IPAddress.Loopback, Port);

    /// <summary>Every plaintext handshake message the computer sent, and every decrypted message after.</summary>
    public readonly List<JsonObject> Plain = [];
    public readonly List<JsonObject> Decrypted = [];
    public string? PairingCode { get; private set; }
    public string? ComputerName { get; private set; }
    public byte[]? ComputerPub { get; private set; }
    public int Connections;
    /// <summary>A v4 phone that was busy let us in after a "Connect here" request and a correct proof.</summary>
    public bool Switched;

    private Func<JsonObject, Task>? _send;
    public event Action<JsonObject>? OnMessage;

    public FakePhone()
    {
        _listener.Start();
        _ = AcceptLoop();
    }

    public void ConfirmPairing(bool ok) => _pairDecision.TrySetResult(ok);

    public Task SendAsync(JsonObject m) => _send?.Invoke(m) ?? Task.CompletedTask;

    private async Task AcceptLoop()
    {
        while (!_stop.IsCancellationRequested)
        {
            TcpClient c;
            try { c = await _listener.AcceptTcpClientAsync(_stop.Token); }
            catch (Exception) { return; }
            Interlocked.Increment(ref Connections);
            _ = Run(c);
        }
    }

    private async Task Run(TcpClient client)
    {
        using var tcpClient = client;
        var s = client.GetStream();
        var write = new SemaphoreSlim(1, 1);
        Sealer? sendSealer = null, recvSealer = null;
        bool sentTrust = false, gotTrust = false;
        byte[] ikm = [], salt = [];
        var eph = Keys.NewPrivateKey();

        async Task WritePlain(JsonObject o)
        {
            await write.WaitAsync();
            try { await Framing.WriteAsync(s, JsonSerializer.SerializeToUtf8Bytes(o)); } finally { write.Release(); }
        }

        void MaybeReady()
        {
            if (!sentTrust || !gotTrust || sendSealer != null) return;
            sendSealer = new Sealer(Keys.SessionKey(ikm, salt, "p2m"));
            _send = async o =>
            {
                await write.WaitAsync();
                try { await Framing.WriteAsync(s, sendSealer.Seal(JsonSerializer.SerializeToUtf8Bytes(o))); } finally { write.Release(); }
            };
        }

        async Task SendTrust()
        {
            await WritePlain(new JsonObject { ["t"] = "trust" });
            lock (this) { sentTrust = true; MaybeReady(); }
        }

        try
        {
            var hello = (JsonObject)JsonNode.Parse(await Framing.ReadAsync(s))!;
            lock (Plain) Plain.Add(hello);
            Assert.Equal("hello", (string?)hello["t"]);
            var computerPub = Convert.FromBase64String((string)hello["pub"]!);
            ComputerPub = computerPub;
            ComputerName = (string?)hello["name"];
            var myNonce = RandomNumberGenerator.GetBytes(16);
            salt = [.. Convert.FromBase64String((string)hello["nonce"]!), .. myNonce];
            var shared = Keys.Dh(Priv, computerPub);
            if (Admit == AdmitKind.Pair) PairingCode = Keys.PairingCode(shared);
            var computerEph = Convert.FromBase64String((string)hello["epub"]!);
            // Crypto.kt order: dh(myStatic, macStatic) + dh(myEph, macEph) + dh(myStatic, macEph) + dh(myEph, macStatic)
            ikm = [.. Keys.Dh(Priv, computerPub), .. Keys.Dh(eph, computerEph), .. Keys.Dh(Priv, computerEph), .. Keys.Dh(eph, computerPub)];
            await WritePlain(new JsonObject
            {
                ["t"] = "hello", ["name"] = Name, ["pub"] = Convert.ToBase64String(Pub),
                ["epub"] = Convert.ToBase64String(X25519.PublicKey(eph)), ["nonce"] = Convert.ToBase64String(myNonce), ["v"] = HelloVersion,
            });

            switch (Admit)
            {
                case AdmitKind.Trust:
                    await SendTrust();
                    break;
                case AdmitKind.Pair:
                    if (HelloVersion >= 3) await WritePlain(new JsonObject { ["t"] = "pair" });
                    _ = Task.Run(async () =>
                    {
                        if (await _pairDecision.Task) await SendTrust();
                        else await WritePlain(new JsonObject { ["t"] = "reject" });
                    });
                    break;
                case AdmitKind.Busy:
                    if (HelloVersion >= 4 && hello["switch"] is JsonValue sw && sw.TryGetValue<bool>(out var wants) && wants)
                    {
                        await WritePlain(new JsonObject { ["t"] = "prove" }); // like Link.kt's Admit.VERIFY
                        break;
                    }
                    await WritePlain(new JsonObject { ["t"] = "reject", ["why"] = "busy" });
                    return;
                case AdmitKind.Stranger:
                    await WritePlain(new JsonObject { ["t"] = "reject", ["why"] = "unpaired" });
                    return;
            }

            while (!_stop.IsCancellationRequested)
            {
                var frame = await Framing.ReadAsync(s, _stop.Token);
                if (recvSealer != null)
                {
                    var m = (JsonObject)JsonNode.Parse(recvSealer.Open(frame))!;
                    lock (Decrypted) Decrypted.Add(m);
                    if ((string?)m["t"] == "ping") await SendAsync(new JsonObject { ["t"] = "pong" });
                    OnMessage?.Invoke(m);
                    continue;
                }
                var o = (JsonObject)JsonNode.Parse(frame)!;
                lock (Plain) Plain.Add(o);
                switch ((string?)o["t"])
                {
                    case "trust":
                        recvSealer = new Sealer(Keys.SessionKey(ikm, salt, "m2p"));
                        lock (this) { gotTrust = true; MaybeReady(); }
                        break;
                    case "reject":
                        return;
                    case "proof":
                        var key = Convert.FromBase64String((string)o["key"]!);
                        if (!CryptographicOperations.FixedTimeEquals(key, Keys.SessionKey(ikm, salt, "switch")))
                        {
                            await WritePlain(new JsonObject { ["t"] = "reject", ["why"] = "busy" });
                            return;
                        }
                        Switched = true;
                        await SendTrust();
                        break;
                }
            }
        }
        catch (Exception)
        {
            // connection ended
        }
        finally
        {
            _send = null;
            _pairDecision = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        }
    }

    public void Dispose()
    {
        _stop.Cancel();
        _listener.Stop();
    }
}
