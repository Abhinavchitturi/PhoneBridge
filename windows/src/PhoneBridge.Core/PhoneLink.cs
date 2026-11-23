using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace PhoneBridge.Core;

public enum LinkState { Searching, Pairing, Connected }

/// <summary>Knobs for tests; the defaults are what the Mac app uses.</summary>
public sealed class LinkOptions
{
    public TimeSpan Tick { get; init; } = TimeSpan.FromSeconds(5);
    public TimeSpan PingEvery { get; init; } = TimeSpan.FromSeconds(15);
    public TimeSpan Timeout { get; init; } = TimeSpan.FromSeconds(45);
    public TimeSpan ConnectTimeout { get; init; } = TimeSpan.FromSeconds(4);
    public TimeSpan BrowseFor { get; init; } = TimeSpan.FromSeconds(1.5);
    public TimeSpan BrowseEvery { get; init; } = TimeSpan.FromSeconds(4);
    public int Port { get; init; } = PhoneLink.Port;
    /// <summary>mDNS browse; returns the phone's endpoints (first one wins, like the Mac's Bonjour browser).</summary>
    public Func<TimeSpan, CancellationToken, Task<List<IPEndPoint>>> Browse { get; init; } = Discovery.BrowseAsync;
    public Func<List<IPAddress>> Gateways { get; init; } = Discovery.DefaultGateways;
}

/// <summary>
/// The Windows twin of mac/Sources/PhoneLink.swift. The phone is the server; we find it (mDNS, the last address that
/// worked, the default gateway for a phone hotspot, the phone's Tailscale addresses), dial it, pair or authenticate,
/// then exchange encrypted JSON messages.
///
/// Handshake (all plaintext frames): we send hello {name, pub, epub, nonce, v}; the phone answers with its hello
/// (v3 phones then send {"t":"pair"} when they're willing to pair with us, or {"t":"reject","why":"busy"|"unpaired"}).
/// Each side sends {"t":"trust"} once it trusts the other's key; after sending trust, everything that side sends is
/// AES-GCM, and the session is up once both trusts have crossed.
/// "Connect here" (<see cref="RequestSwitch"/>): when the phone is busy with another computer, our next hello says
/// "switch":true; a v4 phone answers {"t":"prove"}, we answer {"t":"proof","key":HKDF(ikm, salt, "switch")} (only the
/// paired computer can work that out), and the phone drops the other computer and trusts us.
/// Events fire on background threads.
/// </summary>
public sealed class PhoneLink : IDisposable
{
    public const int Port = 47800;

    public event Action<LinkState, string>? StateChanged;
    public event Action<JsonObject>? MessageReceived;
    /// <summary>A different phone tried to connect while we're paired — ignored. (its name)</summary>
    public event Action<string>? StrangerBlocked;
    /// <summary>The phone turned us away. (why, phoneName): "busy" = paired but using another computer right now,
    /// "unpaired" = it's paired with other computers and isn't accepting new ones.</summary>
    public event Action<string, string>? Refused;
    /// <summary>(code, phoneName, cancelled-when-the-request-goes-away) → true if the user confirmed the code.</summary>
    public Func<string, string, CancellationToken, Task<bool>> AskPairing { get; set; } = (_, _, _) => Task.FromResult(false);

    /// <summary>Whether the phone (as of its last hello) can switch to this computer on request (v4+ phone app).</summary>
    public bool PhoneCanSwitch { get; private set; }
    private DateTime _switchUntil;

    /// <summary>"Connect here": asks the phone to drop the computer it's using and switch to this one, now.</summary>
    public void RequestSwitch()
    {
        lock (_gate) _switchUntil = DateTime.UtcNow.AddSeconds(60);
        Reconnect(); // dials the address the phone last answered on, straight away
    }

    private bool WantsSwitch { get { lock (_gate) return DateTime.UtcNow < _switchUntil; } }

    private readonly LinkStore _store;
    private readonly string _computerName;
    private readonly LinkOptions _opt;
    private readonly byte[] _identity;
    private readonly byte[] _identityPub;
    private readonly object _gate = new();
    private readonly CancellationTokenSource _stop = new();
    private TaskCompletionSource _wake = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private Session? _conn;
    private IPEndPoint? _bonjour;
    /// <summary>Where the phone last answered (its hello, even if it then said "busy"): dialled first, every time,
    /// until it stops working — instead of rotating through every address we know at one try per 5 s.</summary>
    private IPEndPoint? _reached;
    private int _attempt;
    private bool _started;

    public PhoneLink(LinkStore store, string computerName, LinkOptions? options = null)
    {
        _store = store;
        _computerName = computerName;
        _opt = options ?? new LinkOptions();
        _identity = store.IdentityKey;
        _identityPub = X25519.PublicKey(_identity);
    }

    public byte[] PublicKey => (byte[])_identityPub.Clone();
    public bool IsConnected => _conn?.Ready == true;
    public bool IsPaired => _store.TrustedPhone != null;

    public void Start()
    {
        lock (_gate)
        {
            if (_started) return;
            _started = true;
        }
        _ = Task.Run(TickLoop);
        _ = Task.Run(BrowseLoop);
    }

    /// <summary>Sends an encrypted message. False if not connected (messages before the session is up are dropped).</summary>
    public Task<bool> SendAsync(JsonObject msg)
    {
        var c = _conn;
        if (c == null || !c.Ready) return Task.FromResult(false);
        return c.SendSealedAsync(JsonSerializer.SerializeToUtf8Bytes(msg));
    }

    public Task<bool> SendAsync(string type, params (string Key, object? Value)[] fields)
    {
        var o = new JsonObject { ["t"] = type };
        foreach (var (k, v) in fields) o[k] = v == null ? null : JsonSerializer.SerializeToNode(v);
        return SendAsync(o);
    }

    /// <summary>The phone's VPN addresses (Tailscale 100.64.0.0/10), tried when it isn't on the same network.</summary>
    public void SetRemoteHosts(IEnumerable<string> addrs)
    {
        _store.RemoteHosts = addrs.Where(a =>
        {
            var p = a.Split('.').Select(x => int.TryParse(x, out var n) ? n : -1).ToArray();
            return p.Length == 4 && p[0] == 100 && p[1] is >= 64 and <= 127;
        }).ToList();
    }

    /// <summary>Drops the current connection and looks for the phone again right away.</summary>
    public void Reconnect()
    {
        Session? c;
        lock (_gate)
        {
            _bonjour = null;
            _attempt = 0;
            c = _conn;
        }
        c?.Close();
        Wake();
    }

    /// <summary>Unpairs: forgets the phone's key and name and drops the connection.</summary>
    public void Forget()
    {
        _store.TrustedPhone = null;
        _store.PhoneName = null;
        _conn?.Close();
    }

    public void Dispose()
    {
        _stop.Cancel();
        _conn?.Close();
        Wake();
    }

    // MARK: Discovery and the timer

    private void Wake()
    {
        TaskCompletionSource old;
        lock (_gate)
        {
            old = _wake;
            _wake = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        }
        old.TrySetResult();
    }

    private async Task Sleep(TimeSpan d)
    {
        Task wake;
        lock (_gate) wake = _wake.Task;
        try { await Task.WhenAny(Task.Delay(d, _stop.Token), wake); }
        catch (OperationCanceledException) { }
    }

    private async Task BrowseLoop()
    {
        while (!_stop.IsCancellationRequested)
        {
            if (_conn == null)
            {
                List<IPEndPoint> found;
                try { found = await _opt.Browse(_opt.BrowseFor, _stop.Token); }
                catch (Exception e) when (e is SocketException or OperationCanceledException) { found = []; }
                bool changed;
                lock (_gate)
                {
                    var first = found.FirstOrDefault();
                    changed = first != null && !first.Equals(_bonjour);
                    _bonjour = first;
                }
                // Like the Mac's Bonjour handler: dial straight away only when the answer is new; otherwise the
                // timer's rotation (every 5 s) keeps trying, so a phone that says "busy" isn't dialled twice as often.
                if (changed) Connect(found[0]);
            }
            await Sleep(_opt.BrowseEvery);
        }
    }

    private async Task TickLoop()
    {
        await Sleep(TimeSpan.FromSeconds(1));
        while (!_stop.IsCancellationRequested)
        {
            Tick();
            await Sleep(_opt.Tick);
        }
    }

    private void Tick()
    {
        var c = _conn;
        if (c != null)
        {
            if (c.Ready)
            {
                var now = DateTime.UtcNow;
                if (now - c.LastReceived > _opt.Timeout) { c.Close(); return; }
                if (now - c.LastPing >= _opt.PingEvery)
                {
                    c.LastPing = now;
                    _ = SendAsync(new JsonObject { ["t"] = "ping" });
                }
            }
            return;
        }
        // Not connected. The phone answered at this address last time: dial it again.
        IPEndPoint? reached;
        lock (_gate) reached = _reached;
        if (reached != null) { Connect(reached); return; }
        // Otherwise rotate through every way we know to reach the phone.
        var candidates = new List<IPEndPoint>();
        lock (_gate) if (_bonjour != null) candidates.Add(_bonjour);
        if (_store.LastHost is { } host && IPAddress.TryParse(host, out var ip)) candidates.Add(new IPEndPoint(ip, _opt.Port));
        foreach (var gw in _opt.Gateways()) candidates.Add(new IPEndPoint(gw, _opt.Port)); // phone hotspot = gateway
        foreach (var h in _store.RemoteHosts)
            if (IPAddress.TryParse(h, out var r)) candidates.Add(new IPEndPoint(r, _opt.Port));
        if (candidates.Count == 0) return;
        int i;
        lock (_gate) i = _attempt++;
        Connect(candidates[i % candidates.Count]);
    }

    // MARK: Connection

    private void Connect(IPEndPoint ep)
    {
        Session s;
        lock (_gate)
        {
            if (_conn != null || _stop.IsCancellationRequested) return;
            s = new Session(this, ep);
            _conn = s;
        }
        _ = s.RunAsync();
    }

    private void Closed(Session s)
    {
        lock (_gate)
        {
            if (_conn != s) return;
            _conn = null;
        }
        StateChanged?.Invoke(LinkState.Searching, "");
        if (s.WasReady)
            _ = Task.Delay(1000).ContinueWith(_ => Tick());
    }

    private sealed class Session(PhoneLink link, IPEndPoint ep)
    {
        private readonly TcpClient _tcp = new(ep.AddressFamily) { NoDelay = true };
        private readonly SemaphoreSlim _write = new(1, 1);
        private readonly byte[] _myNonce = RandomNumberGenerator.GetBytes(16);
        private readonly byte[] _ephemeral = Keys.NewPrivateKey(); // new for every connection (forward secrecy)
        private readonly CancellationTokenSource _closed = new();
        private Stream? _stream;
        private byte[]? _ikm;
        private byte[] _shared = [];
        private byte[] _salt = [];
        private byte[] _peerPub = [];
        private string _peerName = "Phone";
        private bool _sentTrust, _gotTrust;
        /// <summary>The phone said hello on this connection.</summary>
        private bool _heard;
        /// <summary>Newer phones (hello v3+) send "pair" once they're willing to pair; until then don't show a code.</summary>
        private bool _awaitingPair;
        /// <summary>The phone is ours: send our "trust" once the phone sends its own (see below).</summary>
        private bool _trustWhenTrusted;
        private Sealer? _send, _recv;

        public volatile bool Ready;
        public bool WasReady;
        public DateTime LastReceived = DateTime.UtcNow;
        public DateTime LastPing = DateTime.MinValue;

        public async Task RunAsync()
        {
            try
            {
                using (var cts = CancellationTokenSource.CreateLinkedTokenSource(_closed.Token))
                {
                    cts.CancelAfter(link._opt.ConnectTimeout);
                    await _tcp.ConnectAsync(ep, cts.Token);
                }
                try
                {
                    _tcp.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.KeepAlive, true);
                    _tcp.Client.SetSocketOption(SocketOptionLevel.Tcp, SocketOptionName.TcpKeepAliveTime, 10);
                }
                catch (SocketException) { }
                _stream = _tcp.GetStream();
                LastReceived = DateTime.UtcNow;
                await SendPlain(new JsonObject
                {
                    ["t"] = "hello", ["name"] = link._computerName,
                    ["pub"] = Convert.ToBase64String(link._identityPub),
                    ["epub"] = Convert.ToBase64String(X25519.PublicKey(_ephemeral)),
                    ["nonce"] = Convert.ToBase64String(_myNonce),
                    ["v"] = 2, // what the Mac sends; the phone doesn't branch on it
                    ["platform"] = "windows",
                    ["switch"] = link.WantsSwitch ? true : null,
                });
                while (!_closed.IsCancellationRequested)
                    HandleFrame(await Framing.ReadAsync(_stream, _closed.Token));
            }
            catch (Exception e) when (e is IOException or SocketException or OperationCanceledException or ObjectDisposedException
                                          or InvalidDataException or CryptographicException or JsonException
                                          or FormatException or InvalidOperationException or EndOfStreamException)
            {
                // the connection ended (or the phone sent something we don't accept): look for it again
            }
            finally
            {
                // Couldn't reach the phone at its last address (new network, new IP): forget it and search again.
                if (!_heard) lock (link._gate) if (Equals(link._reached, ep)) link._reached = null;
                Close();
                link.Closed(this);
            }
        }

        public void Close()
        {
            if (_closed.IsCancellationRequested) return;
            Ready = false;
            try { _closed.Cancel(); } catch (ObjectDisposedException) { }
            _tcp.Dispose(); // unblocks the reader; also cancels any pairing prompt via _closed
        }

        private void HandleFrame(byte[] frame)
        {
            LastReceived = DateTime.UtcNow;
            if (_recv != null)
            {
                var plain = _recv.Open(frame);
                var m = JsonNode.Parse(plain) as JsonObject ?? throw new InvalidDataException("not an object");
                if (Str(m, "t") != "pong") link.MessageReceived?.Invoke(m);
                return;
            }
            var msg = JsonNode.Parse(frame) as JsonObject ?? throw new InvalidDataException("not an object");
            switch (Str(msg, "t"))
            {
                case "hello":
                    // Without "epub" it's a phone app too old for per-connection keys: refuse (the parse throws).
                    var pub = Convert.FromBase64String(Str(msg, "pub") ?? throw new InvalidDataException("no pub"));
                    var nonce = Convert.FromBase64String(Str(msg, "nonce") ?? throw new InvalidDataException("no nonce"));
                    var epub = Convert.FromBase64String(Str(msg, "epub") ?? throw new InvalidDataException("no epub"));
                    _shared = Keys.Dh(link._identity, pub);
                    _ikm = Keys.SessionIkm(link._identity, _ephemeral, pub, epub);
                    _peerPub = pub;
                    _peerName = Str(msg, "name") ?? "Phone";
                    _salt = [.. _myNonce, .. nonce];
                    _heard = true;
                    lock (link._gate) link._reached = ep; // the phone is here (also when it then says busy)
                    if (ep.AddressFamily == AddressFamily.InterNetwork) link._store.LastHost = ep.Address.ToString();
                    link.PhoneCanSwitch = (msg["v"] is JsonValue hv && hv.TryGetValue<int>(out var hver) ? hver : 0) >= 4;
                    var trusted = link._store.TrustedPhone;
                    if (trusted != null && trusted.AsSpan().SequenceEqual(pub))
                    {
                        // Our paired phone. It answers with "trust" when it lets us in, or "reject" (busy) and hangs up.
                        // Wait for that answer before sending our own "trust": if it arrived after the phone hung up,
                        // Windows would reset the connection and throw away the unread "reject", so we'd never know why.
                        // (Both sides send "trust" independently, so the order doesn't matter to the phone.)
                        _trustWhenTrusted = true;
                    }
                    else if (trusted != null)
                    {
                        // Paired with a different phone: never offer to pair, just ignore it.
                        _ = SendPlain(new JsonObject { ["t"] = "reject" }).ContinueWith(_ => Close());
                        link.StrangerBlocked?.Invoke(_peerName);
                    }
                    else if ((msg["v"] is JsonValue v && v.TryGetValue<int>(out var ver) ? ver : 0) >= 3)
                    {
                        _awaitingPair = true; // the phone may still turn us away (it's paired with other computers)
                    }
                    else
                    {
                        AskToPair();
                    }
                    break;
                case "pair":
                    if (_awaitingPair)
                    {
                        _awaitingPair = false;
                        AskToPair();
                    }
                    break;
                case "trust":
                    // After the phone's "trust", everything it sends is encrypted.
                    if (_ikm == null) throw new InvalidDataException("trust before hello");
                    _recv = new Sealer(Keys.SessionKey(_ikm, _salt, "p2m"));
                    lock (this) _gotTrust = true;
                    if (_trustWhenTrusted)
                    {
                        _trustWhenTrusted = false;
                        _ = SendTrust();
                    }
                    MaybeReady();
                    break;
                case "prove":
                    // We asked to switch: show the phone we hold this pairing's key. Its "trust" follows.
                    if (_ikm == null || !_trustWhenTrusted) throw new InvalidDataException("prove out of turn");
                    _ = SendPlain(new JsonObject { ["t"] = "proof", ["key"] = Convert.ToBase64String(Keys.SessionKey(_ikm, _salt, "switch")) });
                    break;
                case "reject":
                    if (Str(msg, "why") is { } why) link.Refused?.Invoke(why, _peerName);
                    Close();
                    break;
            }
        }

        private void AskToPair()
        {
            link.StateChanged?.Invoke(LinkState.Pairing, _peerName);
            var code = Keys.PairingCode(_shared);
            var token = _closed.Token;
            _ = Task.Run(async () =>
            {
                bool ok;
                try { ok = await link.AskPairing(code, _peerName, token); }
                catch (Exception) { ok = false; }
                if (token.IsCancellationRequested || link._conn != this) return;
                if (ok)
                {
                    link._store.TrustedPhone = _peerPub;
                    link._store.PhoneName = _peerName;
                    await SendTrust();
                }
                else
                {
                    await SendPlain(new JsonObject { ["t"] = "reject" });
                    Close();
                }
            });
        }

        private async Task SendTrust()
        {
            if (!await SendPlain(new JsonObject { ["t"] = "trust" })) return;
            lock (this) _sentTrust = true;
            MaybeReady();
        }

        private void MaybeReady()
        {
            lock (this)
            {
                if (!_sentTrust || !_gotTrust || _send != null || _ikm == null) return;
                _send = new Sealer(Keys.SessionKey(_ikm, _salt, "m2p"));
                Ready = true;
                WasReady = true;
            }
            lock (link._gate) link._switchUntil = default; // done: don't take the phone over again on a later reconnect
            if (ep.AddressFamily == AddressFamily.InterNetwork) link._store.LastHost = ep.Address.ToString();
            link.StateChanged?.Invoke(LinkState.Connected, _peerName);
        }

        private Task<bool> SendPlain(JsonObject m) => Write(JsonSerializer.SerializeToUtf8Bytes(m), seal: false);

        public Task<bool> SendSealedAsync(byte[] plain) => Write(plain, seal: true);

        /// <summary>Seal and write under one lock, so nonce counters match the order frames hit the wire.</summary>
        private async Task<bool> Write(byte[] payload, bool seal)
        {
            var s = _stream;
            if (s == null) return false;
            try
            {
                await _write.WaitAsync(_closed.Token);
            }
            catch (OperationCanceledException) { return false; }
            try
            {
                if (_closed.IsCancellationRequested) return false;
                if (seal)
                {
                    if (_send == null) return false;
                    payload = _send.Seal(payload);
                }
                await Framing.WriteAsync(s, payload, _closed.Token);
                return true;
            }
            catch (Exception e) when (e is IOException or SocketException or OperationCanceledException or ObjectDisposedException)
            {
                Close();
                return false;
            }
            finally
            {
                _write.Release();
            }
        }
    }

    /// <summary>A string field of a message, or null.</summary>
    public static string? Str(JsonObject o, string key) =>
        o[key] is JsonValue v && v.TryGetValue<string>(out var s) ? s : null;
}
