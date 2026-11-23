using System.Text.Json.Nodes;
using PhoneBridge.Core;
using static PhoneBridge.Core.CallLogic;

namespace PhoneBridge.Core.Tests;

/// <summary>The rules from mac/Tests/call-logic-check.swift, case for case.</summary>
public class CallLogicTests
{
    private static readonly DateTimeOffset T0 = DateTimeOffset.FromUnixTimeSeconds(1_000_000);
    private static DateTimeOffset Later(double s) => T0.AddSeconds(s);
    private static JsonObject J(string json) => (JsonObject)JsonNode.Parse(json)!;

    [Fact]
    public void RingingNeverStartsATimerAndWaitingNeverReplacesTheCall()
    {
        var c = new CallLogic();
        c.Update(J("""{"state":"ringing","name":"Alice","number":"+111"}"""), T0);
        Assert.True(!c.Active && !c.Answered && c.StartedAt == null);
        c.Update(J("""{"state":"ringing","name":"Alice"}"""), Later(3));
        Assert.True(!c.Answered && c.StartedAt == null);
        c.Update(J("""{"state":"active","name":"Alice","connected":false}"""), Later(5));
        Assert.True(c.Active && !c.Answered);
        c.Update(J("""{"state":"active","name":"Alice","connected":true,"incoming":true,"since":999000000.0}"""), Later(8));
        Assert.True(c.Answered && c.StartedAt == Later(8));

        c.Update(J("""{"state":"active","name":"Alice","connected":true,"waiting":true,"waitingName":"Bob","waitingNumber":"+222"}"""), Later(60));
        Assert.Equal(("Alice", "+111"), (c.Name, c.Number));
        Assert.Equal(new WaitingCaller("Bob", "+222"), c.Waiting);
        Assert.Equal(Later(8), c.StartedAt);
        c.Update(J("""{"state":"ringing","name":"Bob","number":"+222"}"""), Later(61));
        Assert.True(c.Name == "Alice" && c.Active);
        c.Update(J("""{"state":"active","name":"Bob","connected":true,"waiting":false}"""), Later(70));
        Assert.True(c.Name == "Alice" && c.Waiting == null && c.StartedAt == Later(8));

        c.Update(J("""{"state":"active","connected":true,"waiting":true,"waitingName":"Bob","waitingNumber":"+222"}"""), Later(80));
        c.WillAnswerWaiting();
        c.Update(J("""{"state":"active","connected":true,"waiting":false}"""), Later(85));
        Assert.True(c.Name == "Bob" && c.HeldCaller == "Alice" && c.StartedAt == Later(85) && c.Waiting == null);

        var e = c.Update(J("""{"state":"idle"}"""), Later(145));
        Assert.Equal(new Effect(EffectKind.Ended, "Bob", "+222", 60), e);
        Assert.True(c.Name == null && !c.Active && c.Waiting == null);
    }

    [Fact]
    public void MissedCallJustHides()
    {
        var m = new CallLogic();
        m.Update(J("""{"state":"ringing","name":"Carol"}"""), T0);
        Assert.Equal(Effect.Hide, m.Update(J("""{"state":"idle"}"""), Later(10)));
    }

    [Fact]
    public void DialledCallUsesTheDiallersStartTime()
    {
        var d = new CallLogic();
        d.StartOutgoing("Dave", "+333");
        Assert.True(d.Outgoing && !d.Active && !d.Answered);
        d.Update(J("""{"state":"active","connected":false,"detail":"Calling…"}"""), Later(2));
        Assert.True(d.Active && !d.Answered && d.StartedAt == null);
        var theirs = Later(7).ToUnixTimeMilliseconds();
        d.Update(J($$"""{"state":"active","connected":true,"since":{{theirs}}}"""), Later(8));
        Assert.True(d.Answered && d.StartedAt == Later(7));
        d.Update(J($$"""{"state":"active","connected":true,"since":{{theirs}},"detail":"Ongoing call"}"""), Later(30));
        Assert.True(d.Answered && d.StartedAt == Later(7));
    }

    [Fact]
    public void CallDialledOnThePhone()
    {
        var p = new CallLogic();
        p.Update(J("""{"state":"active","name":"Erin","connected":null}"""), T0);
        Assert.True(p.Active && !p.Answered);
        p.Update(J("""{"state":"active","connected":true}"""), Later(4));
        Assert.True(p.Answered && p.StartedAt == Later(4));
    }

    [Fact]
    public void FirstCallEndsWhileSecondRings()
    {
        var q = new CallLogic();
        q.Update(J("""{"state":"ringing","name":"Alice"}"""), T0);
        q.Update(J("""{"state":"active","connected":true,"incoming":true}"""), Later(1));
        q.Update(J("""{"state":"active","connected":true,"waiting":true,"waitingName":"Bob"}"""), Later(5));
        q.Update(J("""{"state":"idle"}"""), Later(20));
        q.Update(J("""{"state":"ringing","name":"Bob","number":"+222"}"""), Later(20));
        Assert.True(q.Name == "Bob" && !q.Active && !q.Answered && q.Waiting == null);
    }
}
