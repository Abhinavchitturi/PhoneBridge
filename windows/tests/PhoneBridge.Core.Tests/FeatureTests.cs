using System.Text.Json.Nodes;
using PhoneBridge.Core;

namespace PhoneBridge.Core.Tests;

public class FeatureTests
{
    private sealed class Flip : ISecretProtector
    {
        public byte[] Protect(byte[] p) => p.Select(b => (byte)~b).ToArray();
        public byte[] Unprotect(byte[] s) => s.Select(b => (byte)~b).ToArray();
    }

    private static JsonObject J(string s) => (JsonObject)JsonNode.Parse(s)!;

    [Fact]
    public void HistoryKeepsNewestVersionAndSurvivesRestartWithEmptyFeed()
    {
        var file = Path.Combine(Path.GetTempPath(), "pb-hist-" + Guid.NewGuid().ToString("N"), "history.bin");
        var h = new NotificationHistory(file, new Flip());
        var now = DateTimeOffset.Now;
        h.Add(J("""{"id":"k1","app":"WhatsApp","title":"Ann","text":"hi","reply":true}"""), now);
        h.Add(J("""{"id":"k2","app":"Mail","title":"Bob","text":"report"}"""), now);
        h.Add(J("""{"id":"k1","app":"WhatsApp","title":"Ann","text":"hi\nare you there?","reply":true}"""), now);
        Assert.Equal(2, h.Items.Count);
        Assert.Equal("hi\nare you there?", h.Items[0].Text);
        Assert.True(h.Items[0].CanReply);
        h.MarkRemoved("k2");
        Assert.Single(h.Feed);
        Assert.DoesNotContain("report", File.ReadAllText(file)); // encrypted at rest
        var again = new NotificationHistory(file, new Flip());
        Assert.Equal(2, again.Items.Count);
        Assert.Empty(again.Feed);
        Assert.Equal(["Ann: hi\nare you there?"], again.Earlier("WhatsApp", "Ann"));
        Directory.Delete(Path.GetDirectoryName(file)!, true);
    }

    [Fact]
    public void RulesMuteByAppAndKeyword()
    {
        var r = new NotifRules();
        r.Toggle("Shopping");
        r.AddKeyword("Sale");
        Assert.True(r.IsMuted(J("""{"app":"Shopping","title":"x","text":"y"}""")));
        Assert.True(r.IsMuted(J("""{"app":"Mail","title":"Big SALE today","text":""}""")));
        Assert.False(r.IsMuted(J("""{"app":"Mail","title":"Hello","text":"there"}""")));
    }

    [Fact]
    public void ClipboardHistoryMovesRepeatsToTheTop()
    {
        var c = new ClipboardHistory();
        c.Add("one", false, DateTimeOffset.Now);
        c.Add("two", true, DateTimeOffset.Now);
        c.Add(" one ", false, DateTimeOffset.Now);
        Assert.Equal(["one", "two"], c.Items.Select(i => i.Text));
    }

    [Fact]
    public void NoteNewestEditWins()
    {
        var n = new SharedNote("mine", 100);
        Assert.False(n.Receive(J("""{"t":"note","text":"older","ts":50}""")));
        Assert.True(n.Receive(J("""{"t":"note","text":"phone","ts":200}""")));
        Assert.Equal("phone", n.Text);
        var ts = n.Edit("typed here", DateTimeOffset.FromUnixTimeMilliseconds(300));
        Assert.Equal(300, ts);
        Assert.Equal(300, (long)n.Hello()["ts"]!);
    }

    [Fact]
    public void BatteryEstimatesAndAlertsOnce()
    {
        var b = new BatteryWatch();
        var alerts = new List<string>();
        b.Notify = (t, _) => alerts.Add(t);
        var t0 = DateTimeOffset.Now;
        b.Update(50, true, t0);
        b.Update(60, true, t0.AddMinutes(10)); // 1 %/min
        Assert.Equal("Full in 40 min", b.Eta);
        b.Update(100, true, t0.AddMinutes(50));
        b.Update(100, true, t0.AddMinutes(51));
        Assert.Equal(["Phone fully charged"], alerts);
        b.Update(16, false, t0.AddMinutes(60));
        b.Update(15, false, t0.AddMinutes(66));
        Assert.Equal("1 h 30 min left", b.Eta);
        Assert.Contains("Phone battery low", alerts);
    }
}
