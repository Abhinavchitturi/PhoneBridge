using System.Net;
using System.Text.Json.Nodes;
using PhoneBridge.Core;

namespace PhoneBridge.Core.Tests;

/// <summary>The whole handshake against <see cref="FakePhone"/> (a port of the phone's Link.kt), over real loopback TCP.</summary>
public sealed class LinkTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "pb-test-" + Guid.NewGuid().ToString("N"));
    private static readonly TimeSpan Wait = TimeSpan.FromSeconds(10);

    public void Dispose()
    {
        try { Directory.Delete(_dir, true); } catch (IOException) { }
    }

    private sealed class PlainProtector : ISecretProtector
    {
        public byte[] Protect(byte[] plain) => plain.Reverse().ToArray(); // not secret: just proves the path round-trips
        public byte[] Unprotect(byte[] sealedData) => sealedData.Reverse().ToArray();
    }

    private LinkStore Store() => new(_dir, new PlainProtector());

    private PhoneLink Link(LinkStore store, FakePhone phone) => new(store, "Test PC", new LinkOptions
    {
        Tick = TimeSpan.FromMilliseconds(200),
        PingEvery = TimeSpan.FromMilliseconds(300),
        BrowseEvery = TimeSpan.FromMilliseconds(300),
        Port = phone.Port,
        Browse = (_, _) => Task.FromResult(new List<IPEndPoint> { phone.Endpoint }),
        Gateways = () => [],
    });

    private static async Task<T> Within<T>(Task<T> t)
    {
        var done = await Task.WhenAny(t, Task.Delay(Wait));
        Assert.True(done == t, "timed out");
        return await t;
    }

    [Fact]
    public async Task PairsWithV3PhoneThenTalksEncrypted()
    {
        using var phone = new FakePhone { Admit = FakePhone.AdmitKind.Pair };
        var store = Store();
        using var link = Link(store, phone);
        var asked = new TaskCompletionSource<string>();
        var connected = new TaskCompletionSource<string>();
        var got = new TaskCompletionSource<JsonObject>();
        link.AskPairing = (code, name, _) =>
        {
            Assert.Equal("Test Pixel", name);
            asked.TrySetResult(code);
            phone.ConfirmPairing(true); // the user taps Pair on the phone too
            return Task.FromResult(true);
        };
        link.StateChanged += (s, name) => { if (s == LinkState.Connected) connected.TrySetResult(name); };
        link.MessageReceived += m => { if ((string?)m["t"] == "battery") got.TrySetResult(m); };
        link.Start();

        var code = await Within(asked.Task);
        Assert.Equal(phone.PairingCode, code); // both screens show the same six digits
        Assert.Matches("^[0-9]{6}$", code);
        Assert.Equal("Test Pixel", await Within(connected.Task));
        Assert.Equal(phone.Pub, store.TrustedPhone);
        Assert.Equal("Test Pixel", store.PhoneName);
        Assert.Equal("127.0.0.1", store.LastHost);
        Assert.Equal("Test PC", phone.ComputerName);
        Assert.Equal(link.PublicKey, phone.ComputerPub);

        // Phone → computer, encrypted.
        await phone.SendAsync(new JsonObject { ["t"] = "battery", ["level"] = 77, ["charging"] = true });
        var battery = await Within(got.Task);
        Assert.Equal(77, (int)battery["level"]!);

        // Computer → phone, encrypted; pings go out on the timer and the phone's pongs are swallowed.
        Assert.True(await link.SendAsync("clip", ("text", "héllo from Windows")));
        await Within(WaitFor(() => phone.Decrypted.Any(m => (string?)m["t"] == "clip") && phone.Decrypted.Any(m => (string?)m["t"] == "ping")));
        Assert.Equal("héllo from Windows", (string?)phone.Decrypted.First(m => (string?)m["t"] == "clip")["text"]);
    }

    [Fact]
    public async Task V3PhoneWithoutPairShowsNoCode()
    {
        // A v3 phone that never sends "pair" (e.g. it's thinking it over) — we must not show a code.
        using var phone = new FakePhone { Admit = FakePhone.AdmitKind.Trust };
        var store = Store();
        using var link = Link(store, phone);
        var asked = false;
        link.AskPairing = (_, _, _) => { asked = true; return Task.FromResult(false); };
        link.Start();
        await Within(WaitFor(() => phone.Plain.Count > 0));
        await Task.Delay(800);
        Assert.False(asked);
        Assert.False(link.IsConnected); // we never trusted it, so the session can't come up
    }

    [Fact]
    public async Task OlderPhoneWithoutVersionGetsCodeStraightAway()
    {
        using var phone = new FakePhone { Admit = FakePhone.AdmitKind.Pair, HelloVersion = 2 };
        using var link = Link(Store(), phone);
        var asked = new TaskCompletionSource<string>();
        link.AskPairing = (code, _, _) => { asked.TrySetResult(code); phone.ConfirmPairing(true); return Task.FromResult(true); };
        link.Start();
        var code = await Within(asked.Task);
        Assert.Equal(phone.PairingCode, code);
    }

    [Fact]
    public async Task ReconnectsToPairedPhoneWithoutAsking()
    {
        using var phone = new FakePhone { Admit = FakePhone.AdmitKind.Trust };
        var store = Store();
        store.TrustedPhone = phone.Pub;
        using var link = Link(store, phone);
        link.AskPairing = (_, _, _) => throw new InvalidOperationException("must not ask");
        var connected = new TaskCompletionSource<string>();
        link.StateChanged += (s, n) => { if (s == LinkState.Connected) connected.TrySetResult(n); };
        link.Start();
        await Within(connected.Task);
        Assert.True(link.IsConnected);
        Assert.Contains(phone.Plain, m => (string?)m["t"] == "trust");
    }

    [Theory]
    [InlineData(FakePhone.AdmitKind.Busy, "busy")]
    [InlineData(FakePhone.AdmitKind.Stranger, "unpaired")]
    public async Task RejectReasonIsReported(FakePhone.AdmitKind admit, string why)
    {
        using var phone = new FakePhone { Admit = admit };
        var store = Store();
        if (admit == FakePhone.AdmitKind.Busy) store.TrustedPhone = phone.Pub;
        using var link = Link(store, phone);
        var asked = false;
        link.AskPairing = (_, _, _) => { asked = true; return Task.FromResult(false); };
        var refused = new TaskCompletionSource<(string, string)>();
        link.Refused += (w, n) => refused.TrySetResult((w, n));
        link.Start();
        Assert.Equal((why, "Test Pixel"), await Within(refused.Task));
        Assert.False(asked);
        Assert.False(link.IsConnected);
        // It keeps dialling, as the phone expects (it lets us in once the user picks this computer).
        await Within(WaitFor(() => phone.Connections >= 2));
    }

    [Fact]
    public async Task ConnectHereSwitchesABusyPhoneAfterProvingTheKey()
    {
        using var phone = new FakePhone { Admit = FakePhone.AdmitKind.Busy, HelloVersion = 4 };
        var store = Store();
        store.TrustedPhone = phone.Pub;
        using var link = Link(store, phone);
        var refused = new TaskCompletionSource();
        var connected = new TaskCompletionSource();
        link.Refused += (w, _) => { if (w == "busy") refused.TrySetResult(); };
        link.StateChanged += (s, _) => { if (s == LinkState.Connected) connected.TrySetResult(); };
        link.Start();
        await Within(refused.Task.ContinueWith(_ => true)); // busy with the other computer: stays out
        Assert.True(link.PhoneCanSwitch);
        Assert.False(phone.Switched);
        link.RequestSwitch(); // "Connect here"
        await Within(connected.Task.ContinueWith(_ => true));
        Assert.True(phone.Switched);
        await link.SendAsync("ping");
        await Within(WaitFor(() => phone.Decrypted.Any(m => (string?)m["t"] == "ping")));
    }

    [Fact]
    public async Task OlderPhoneSaysItCantSwitch()
    {
        using var phone = new FakePhone { Admit = FakePhone.AdmitKind.Busy, HelloVersion = 3 };
        var store = Store();
        store.TrustedPhone = phone.Pub;
        using var link = Link(store, phone);
        var refused = 0;
        link.Refused += (_, _) => Interlocked.Increment(ref refused);
        link.Start();
        await Within(WaitFor(() => refused >= 1));
        Assert.False(link.PhoneCanSwitch);
        link.RequestSwitch();
        await Within(WaitFor(() => refused >= 2)); // still busy: an old phone ignores the request
        Assert.False(link.IsConnected);
    }

    [Fact]
    public async Task DifferentPhoneIsIgnoredWhenPaired()
    {
        using var phone = new FakePhone { Admit = FakePhone.AdmitKind.Pair };
        var store = Store();
        store.TrustedPhone = Keys.NewPrivateKey(); // paired with some other phone
        using var link = Link(store, phone);
        var blocked = new TaskCompletionSource<string>();
        link.StrangerBlocked += n => blocked.TrySetResult(n);
        link.AskPairing = (_, _, _) => throw new InvalidOperationException("must not ask");
        link.Start();
        Assert.Equal("Test Pixel", await Within(blocked.Task));
        await Within(WaitFor(() => phone.Plain.Any(m => (string?)m["t"] == "reject")));
    }

    [Fact]
    public async Task DecliningPairingSendsRejectAndRemembersNothing()
    {
        using var phone = new FakePhone { Admit = FakePhone.AdmitKind.Pair };
        var store = Store();
        using var link = Link(store, phone);
        var asked = new TaskCompletionSource();
        link.AskPairing = (_, _, _) => { asked.TrySetResult(); return Task.FromResult(false); };
        link.Start();
        await Within(asked.Task.ContinueWith(_ => true));
        await Within(WaitFor(() => phone.Plain.Any(m => (string?)m["t"] == "reject")));
        Assert.Null(store.TrustedPhone);
    }

    [Fact]
    public async Task PairingPromptIsCancelledWhenPhoneGoesAway()
    {
        var phone = new FakePhone { Admit = FakePhone.AdmitKind.Pair };
        using var link = Link(Store(), phone);
        var cancelled = new TaskCompletionSource();
        var asking = new TaskCompletionSource();
        link.AskPairing = async (_, _, ct) =>
        {
            asking.TrySetResult();
            try { await Task.Delay(Timeout.Infinite, ct); }
            catch (OperationCanceledException) { cancelled.TrySetResult(); }
            return false;
        };
        link.Start();
        // Wait for the prompt itself, not just the phone's code: on a slow machine the phone can show its code before
        // this side has asked, and then there'd be no prompt yet for the reconnect to cancel.
        await Within(asking.Task.ContinueWith(_ => true));
        phone.Dispose(); // phone stops listening; its socket goes too
        link.Reconnect();
        await Within(cancelled.Task.ContinueWith(_ => true));
    }

    [Fact]
    public void StoreKeepsIdentityAndSealsSecrets()
    {
        var a = Store();
        var id = a.IdentityKey;
        a.TrustedPhone = [1, 2, 3];
        a.PhoneName = "Pixel";
        a.RemoteHosts = ["100.64.1.2"];
        var b = Store();
        Assert.Equal(id, b.IdentityKey);
        Assert.Equal(new byte[] { 1, 2, 3 }, b.TrustedPhone);
        Assert.Equal("Pixel", b.PhoneName);
        Assert.Equal(["100.64.1.2"], b.RemoteHosts);
        var raw = File.ReadAllText(Path.Combine(_dir, "secrets.bin"));
        Assert.DoesNotContain("identity", raw); // went through the protector
    }

    [Fact]
    public void OnlyTailscaleAddressesAreKept()
    {
        using var phone = new FakePhone();
        var store = Store();
        using var link = Link(store, phone);
        link.SetRemoteHosts(["192.168.1.5", "100.100.1.2", "100.63.0.1", "100.128.0.1", "10.0.0.1"]);
        Assert.Equal(["100.100.1.2"], store.RemoteHosts);
    }

    private static async Task<bool> WaitFor(Func<bool> cond)
    {
        while (true)
        {
            bool ok;
            try { ok = cond(); } catch (InvalidOperationException) { ok = false; } // list changed while we looked
            if (ok) return true;
            await Task.Delay(50);
        }
    }
}
