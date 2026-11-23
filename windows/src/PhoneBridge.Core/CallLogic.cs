using System.Text.Json.Nodes;

namespace PhoneBridge.Core;

public sealed record WaitingCaller(string? Name, string? Number);

/// <summary>
/// What the call card shows, driven by the phone's "call" reports. A straight port of mac/Sources/CallLogic.swift
/// (and its rule checks are ported in the tests):
///  - the timer starts only when the phone says the other side is on the line ("connected"), never on "ringing";
///  - a second call that rings while you're on one goes in Waiting and never replaces the call you're on;
///  - the call you're on keeps its name and timer until it ends.
/// </summary>
public sealed class CallLogic
{
    public enum EffectKind { None, Show, Hide, Ended }

    public readonly record struct Effect(EffectKind Kind, string? Name = null, string? Number = null, int Seconds = 0)
    {
        public static readonly Effect None = new(EffectKind.None);
        public static readonly Effect Show = new(EffectKind.Show);
        public static readonly Effect Hide = new(EffectKind.Hide);
    }

    public string? Name { get; private set; }
    public string? Number { get; private set; }
    public string? Detail { get; private set; }
    /// <summary>Dialled from the computer: "Calling…" until the phone reports the call.</summary>
    public bool Outgoing { get; private set; }
    /// <summary>A call is on (answered, or dialled).</summary>
    public bool Active { get; private set; }
    /// <summary>The other side is on the line: the timer runs.</summary>
    public bool Answered { get; private set; }
    public DateTimeOffset? StartedAt { get; private set; }
    /// <summary>A second call, ringing while you're on one.</summary>
    public WaitingCaller? Waiting { get; set; }
    /// <summary>The call that was put on hold when you took the waiting one from the computer.</summary>
    public string? HeldCaller { get; private set; }
    private bool _ringingShown;
    private WaitingCaller? _switchTo;

    private void Reset()
    {
        Name = Number = Detail = HeldCaller = null;
        Outgoing = Active = Answered = _ringingShown = false;
        StartedAt = null;
        Waiting = _switchTo = null;
    }

    public void StartOutgoing(string? name, string number)
    {
        Reset();
        Outgoing = true;
        Name = name;
        Number = number;
    }

    /// <summary>You pressed Answer on the waiting call: when the phone says it's gone, that call is the one you're on.</summary>
    public void WillAnswerWaiting() => _switchTo = Waiting;

    public Effect Update(JsonObject m, DateTimeOffset now)
    {
        var state = PhoneLink.Str(m, "state") ?? "idle";
        if (state == "idle")
        {
            var effect = Effect.Hide;
            if (Answered && StartedAt is { } start && (Name != null || Number != null))
                effect = new Effect(EffectKind.Ended, Name, Number, (int)(now - start).TotalSeconds);
            Reset();
            return effect;
        }
        if (state == "ringing")
        {
            if (Outgoing || Active) return Effect.None; // a stray report must not touch the call you're on
            Name = Text(m, "name") ?? Name;
            Number = Text(m, "number") ?? Number;
            Detail = Text(m, "detail") ?? Detail;
            _ringingShown = true;
            return Effect.Show;
        }
        // state == "active"
        var wasRinging = _ringingShown && !Outgoing;
        if (!Active)
        {
            Active = true;
            _ringingShown = false;
            Answered = false;
            StartedAt = null;
        }
        // Who you're talking to is fixed when the call starts; later reports only fill in what's missing.
        Name ??= Text(m, "name");
        Number ??= Text(m, "number");
        if (Text(m, "detail") is { } d) Detail = d;
        if (Bool(m, "connected"))
        {
            DateTimeOffset? since = m["since"] is JsonValue sv && sv.TryGetValue<double>(out var ms) ? DateTimeOffset.FromUnixTimeMilliseconds((long)ms) : null;
            if (!Answered)
            {
                Answered = true;
                // Calls you picked up start now; for calls you dialled the dialer's own start time is the exact one.
                StartedAt = wasRinging || Bool(m, "incoming") ? now : since ?? now;
            }
            else if (since is { } si && StartedAt is { } s && Math.Abs((si - s).TotalSeconds) > 1 && si < s.AddSeconds(5))
            {
                StartedAt = si;
            }
        }
        if (Bool(m, "waiting"))
        {
            Waiting = new WaitingCaller(Text(m, "waitingName"), Text(m, "waitingNumber"));
        }
        else if (Waiting != null)
        {
            if (_switchTo is { } taken) // you answered it here: the old call is on hold, this one is the call now
            {
                HeldCaller = Name ?? Number;
                Name = taken.Name;
                Number = taken.Number;
                Answered = true;
                StartedAt = now;
            }
            Waiting = null;
            _switchTo = null;
        }
        return Effect.Show;
    }

    private static string? Text(JsonObject m, string k) => PhoneLink.Str(m, k) is { Length: > 0 } s ? s : null;
    private static bool Bool(JsonObject m, string k) => m[k] is JsonValue v && v.TryGetValue<bool>(out var b) && b;
}
