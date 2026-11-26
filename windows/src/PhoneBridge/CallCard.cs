using System.Media;
using System.Text.Json.Nodes;
using PhoneBridge.Core;

namespace PhoneBridge;

/// <summary>
/// The floating call card (port of mac/Sources/CallPanel.swift): incoming → Answer / Decline and a soft ring;
/// once a call is on — whoever started it — it stays until the call ends, with the time talked and Mute / Speaker /
/// Hold / End, plus Answer / Decline for a second, waiting call. All the call rules live in Core's CallLogic.
/// </summary>
internal sealed class CallCard
{
    private readonly CallLogic _logic = new();
    private FloatingPanel? _panel;
    private readonly Func<string, (string, object?)[], Task<bool>> _send;
    private System.Windows.Forms.Timer? _ringTimer;
    private SoundPlayer? _ring;
    private bool _muted, _speaker, _held;
    /// <summary>The phone can switch its loudspeaker for this call (older phone apps don't say, so it starts on).</summary>
    private bool _canSpeaker = true;
    /// <summary>The call's voice is on this PC's speakers / mic (Bluetooth hands-free), and a move is in progress.</summary>
    private bool _pcAudio, _pcBusy;
    private readonly CallAudio _audio = new();

    /// <summary>Shows a short note (why call audio couldn't move to the PC).</summary>
    public Action<string, string> Note = (_, _) => { };
    /// <summary>The connected phone's name, to pick it among the phones paired with this PC.</summary>
    public Func<string> PhoneName = () => "";

    /// <summary>(name, number, seconds) when a call that was picked up has finished.</summary>
    public Action<string?, string?, int> Ended = (_, _, _) => { };

    public CallCard(Func<string, (string, object?)[], Task<bool>> send) => _send = send;

    public bool Active => _logic.Active || _logic.Outgoing;

    /// <summary>The phone's "call" report.</summary>
    public void Update(JsonObject m)
    {
        if (m["canSpeaker"] is JsonValue cs && cs.TryGetValue<bool>(out var canSpk)) _canSpeaker = canSpk;
        var hadWaiting = _logic.Waiting != null;
        var e = _logic.Update(m, DateTimeOffset.Now);
        switch (e.Kind)
        {
            case CallLogic.EffectKind.None: return;
            case CallLogic.EffectKind.Hide: Hide(); return;
            case CallLogic.EffectKind.Ended:
                Hide();
                Ended(e.Name, e.Number, e.Seconds);
                return;
        }
        if (_logic.Active)
        {
            StopRinging();
            Show(ring: false);
            if (_logic.Waiting != null && !hadWaiting) PlayChime();
        }
        else Show(ring: true);
    }

    /// <summary>Dialled from this PC: show who we're calling, without ringing.</summary>
    public void ShowOutgoing(string? name, string number)
    {
        Hide();
        _logic.StartOutgoing(name, number);
        Show(ring: false);
        // If the phone never reports the call (e.g. it failed to dial), don't leave the card up forever.
        var t = new System.Windows.Forms.Timer { Interval = 45_000 };
        t.Tick += (_, _) => { t.Dispose(); if (_logic.Outgoing && !_logic.Active) Hide(); };
        t.Start();
    }

    /// <summary>The phone's real mute / speaker / hold state after a button press ("call_ctl_state").</summary>
    public void SetControls(JsonObject m)
    {
        if (m["muted"] is JsonValue a && a.TryGetValue<bool>(out var mu)) _muted = mu;
        if (m["speaker"] is JsonValue b && b.TryGetValue<bool>(out var sp)) _speaker = sp;
        if (m["held"] is JsonValue c && c.TryGetValue<bool>(out var h)) _held = h;
        Push();
    }

    private void Show(bool ring)
    {
        var size = _logic.Active ? new Size(360, _logic.Waiting != null ? 232 : 168) : new Size(360, 92);
        _panel ??= new FloatingPanel("call.html", size, OnCommand);
        _panel.SetLogicalSize(size);
        Push();
        _panel.ShowQuietly();
        if (!ring || _ringTimer != null) return;
        PlayChime();
        _ringTimer = new System.Windows.Forms.Timer { Interval = 2200 };
        _ringTimer.Tick += (_, _) => PlayChime();
        _ringTimer.Start();
    }

    private void Push()
    {
        _panel?.Host.Push("state", new JsonObject
        {
            ["state"] = new JsonObject
            {
                ["mode"] = _logic.Active ? "active" : "ringing",
                ["name"] = _logic.Name, ["number"] = _logic.Number, ["detail"] = _logic.Detail,
                ["outgoing"] = _logic.Outgoing, ["answered"] = _logic.Answered,
                ["startedAt"] = _logic.StartedAt?.ToUnixTimeMilliseconds(),
                ["waiting"] = _logic.Waiting is { } w ? new JsonObject { ["name"] = w.Name, ["number"] = w.Number } : null,
                ["heldCaller"] = _logic.HeldCaller,
                ["muted"] = _muted, ["speaker"] = _speaker, ["held"] = _held, ["pcAudio"] = _pcAudio, ["canSpeaker"] = _canSpeaker, ["pcBusy"] = _pcBusy,
            },
        });
    }

    public void Hide()
    {
        StopRinging();
        _panel?.Hide();
        _logic.Update(new JsonObject { ["state"] = "idle" }, DateTimeOffset.Now);
        _muted = _speaker = _held = _pcAudio = _pcBusy = false;
        _canSpeaker = true;
        _audio.Reset();
    }

    private void StopRinging()
    {
        _ringTimer?.Dispose();
        _ringTimer = null;
    }

    private void OnCommand(string cmd, JsonObject m)
    {
        switch (cmd)
        {
            case "ready": Push(); break;
            case "answer": _ = _send("call_answer", []); Hide(); break;
            case "decline": _ = _send("call_end", []); Hide(); break;
            case "hangup": _ = _send("call_end", []); break; // the card goes away when the phone reports the call is over
            case "pc-audio": _ = TogglePcAudioAsync(); break;
            case "answer-waiting":
                _logic.WillAnswerWaiting();
                _ = _send("call_answer", [("waiting", true)]);
                break;
            case "decline-waiting":
                _ = _send("call_end", [("waiting", true)]);
                _logic.Waiting = null; // the phone's next report confirms it
                Show(ring: false);
                break;
            case "control":
                var action = PhoneLink.Str(m, "action");
                if (action == null) break;
                _ = _send("call_ctl", [("action", action)]);
                if (action == "mute") _muted = !_muted;
                if (action == "speaker") _speaker = !_speaker;
                if (action == "hold") _held = !_held;
                break;
        }
    }

    /// <summary>Moves the call's voice to this PC, or back to the phone. Runs on the UI thread (OnCommand), so state stays there.</summary>
    private async Task TogglePcAudioAsync()
    {
        if (_pcBusy) return;
        _pcBusy = true;
        Push();
        _audio.PhoneName = PhoneName();
        var r = _pcAudio ? await _audio.MoveToPhoneAsync() : await _audio.MoveToPcAsync();
        _pcBusy = false;
        if (r.Ok) _pcAudio = r.OnPc;
        else Note("Call audio on this PC", r.Message);
        Push();
    }

    /// <summary>A soft two-note glass chime, made here so nothing depends on Windows' sound scheme.</summary>
    private void PlayChime()
    {
        try
        {
            _ring ??= new SoundPlayer(new MemoryStream(Chime()));
            _ring.Play();
        }
        catch (Exception e) { Log.Write("ring: " + e.Message); }
    }

    private static byte[] Chime()
    {
        const int rate = 22050;
        var samples = (int)(rate * 1.1);
        var ms = new MemoryStream();
        var w = new BinaryWriter(ms);
        w.Write("RIFF"u8); w.Write(36 + samples * 2); w.Write("WAVEfmt "u8); w.Write(16); w.Write((short)1); w.Write((short)1);
        w.Write(rate); w.Write(rate * 2); w.Write((short)2); w.Write((short)16); w.Write("data"u8); w.Write(samples * 2);
        for (var i = 0; i < samples; i++)
        {
            var t = (double)i / rate;
            double v = 0;
            foreach (var (f, at) in new[] { (1568.0, 0.0), (2093.0, 0.16) }) // G6, C7
            {
                if (t < at) continue;
                var l = t - at;
                v += (Math.Sin(2 * Math.PI * f * l) + 0.3 * Math.Sin(2 * Math.PI * f * 2.01 * l)) * Math.Min(1, l * 200) * Math.Exp(-l * 4.5);
            }
            w.Write((short)Math.Clamp(v * 9000, short.MinValue, short.MaxValue));
        }
        return ms.ToArray();
    }
}
