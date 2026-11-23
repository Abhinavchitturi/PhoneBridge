using System.Runtime.InteropServices;
using System.Text.Json.Nodes;
using PhoneBridge.Core;

namespace PhoneBridge;

/// <summary>
/// The phone's screen in a window (mac/Sources/Cast.swift CastWindow): asks the phone to cast ("cast_start", optionally
/// one app), shows its H.264 frames (decoded by WebCodecs in ui/cast.html), and sends what you type to the phone.
/// Closing the window stops the cast.
/// </summary>
internal sealed class CastWindow : Form
{
    private readonly WebHost _host;
    private Size _video = new(9, 19);
    public Action<JsonObject> Send = _ => { };

    public CastWindow(Icon icon)
    {
        Icon = icon;
        AutoScaleMode = AutoScaleMode.Dpi;
        AutoScaleDimensions = new SizeF(96F, 96F);
        ClientSize = new Size(380, 820);
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Color.Black;
        _host = new WebHost("cast.html", (cmd, m) =>
        {
            if (cmd == "input") { m.Remove("cmd"); m["t"] = "cast_input"; Send(m); }
        });
        Controls.Add(_host.View);
    }

    public void Open(string? pkg, string title)
    {
        Text = title;
        _host.Push("reset", new JsonObject());
        if (!Visible) Show();
        Activate();
        var msg = new JsonObject { ["t"] = "cast_start" };
        if (pkg != null) msg["app"] = pkg;
        Send(msg);
    }

    public void Frame(JsonObject m) => _host.Push("v", new JsonObject { ["d"] = m["d"]?.DeepClone(), ["k"] = m["k"]?.DeepClone() });

    public void Diag(JsonObject m)
    {
        var text = $"Phone: encoded {m["frames"]} frames · sent {m["sent"]} · acked {m["acked"]}";
        if (PhoneLink.Str(m, "err") is { Length: > 0 } e) text += "\n" + e;
        _host.Push("diag", new JsonObject { ["text"] = text });
    }

    public void State(JsonObject m)
    {
        if (m["w"] is JsonValue wv && wv.TryGetValue<int>(out var w) && m["h"] is JsonValue hv && hv.TryGetValue<int>(out var h) && w > 0 && h > 0)
        {
            _video = new Size(w, h);
            // Fit the phone's shape into most of the screen's height.
            var area = Screen.FromControl(this).WorkingArea;
            var height = (int)(area.Height * 0.85);
            ClientSize = new Size(Math.Max(240, height * w / h), height);
        }
        if (!Visible) Show();
    }

    public void CloseQuietly()
    {
        _quiet = true;
        Hide();
        _quiet = false;
    }

    private bool _quiet;

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        if (e.CloseReason == CloseReason.UserClosing)
        {
            e.Cancel = true;
            Hide();
        }
        base.OnFormClosing(e);
    }

    protected override void OnVisibleChanged(EventArgs e)
    {
        base.OnVisibleChanged(e);
        if (!Visible && !_quiet) Send(new JsonObject { ["t"] = "cast_stop" });
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        Dwm.Darken(Handle, Color.Black);
    }
}

/// <summary>
/// The phone's microphone as a microphone on this PC (Capture.swift PhoneMic): its 16 kHz mono PCM is played into the
/// free VB-CABLE virtual audio device ("CABLE Input"), which Zoom / Meet / Teams then offer as "CABLE Output".
/// Latency is kept under ~0.3 s by dropping audio that piles up.
/// </summary>
internal sealed class PhoneMic
{
    public bool Active { get; private set; }
    public string Status { get; private set; } = "Off";

    private IntPtr _wave;
    private readonly Queue<(IntPtr Header, IntPtr Data)> _queued = new();
    private readonly object _lock = new();

    public void Start()
    {
        if (Active) return;
        var device = FindCable();
        if (device < 0)
        {
            Status = "Install VB-CABLE first (vb-audio.com/Cable), then start Mic again from the phone";
            return;
        }
        var fmt = new WAVEFORMATEX { wFormatTag = 1, nChannels = 1, nSamplesPerSec = 16000, wBitsPerSample = 16, nBlockAlign = 2, nAvgBytesPerSec = 32000 };
        if (waveOutOpen(out _wave, (uint)device, ref fmt, IntPtr.Zero, IntPtr.Zero, 0) != 0)
        {
            Status = "Couldn't open the VB-CABLE device";
            return;
        }
        Active = true;
        Status = "Live — choose “CABLE Output” as the microphone in Zoom, Meet or Teams";
    }

    public void Stop()
    {
        if (!Active) return;
        Active = false;
        lock (_lock)
        {
            waveOutReset(_wave);
            Reap(all: true);
            waveOutClose(_wave);
            _wave = IntPtr.Zero;
        }
        Status = "Off";
    }

    public void Play(string b64)
    {
        if (!Active) return;
        byte[] pcm;
        try { pcm = Convert.FromBase64String(b64); } catch (FormatException) { return; }
        lock (_lock)
        {
            Reap(all: false);
            if (_queued.Count > 6) return; // falling behind: drop, keep latency low
            var data = Marshal.AllocHGlobal(pcm.Length);
            Marshal.Copy(pcm, 0, data, pcm.Length);
            var hdr = new WAVEHDR { lpData = data, dwBufferLength = (uint)pcm.Length };
            var h = Marshal.AllocHGlobal(Marshal.SizeOf<WAVEHDR>());
            Marshal.StructureToPtr(hdr, h, false);
            var size = (uint)Marshal.SizeOf<WAVEHDR>();
            waveOutPrepareHeader(_wave, h, size);
            waveOutWrite(_wave, h, size);
            _queued.Enqueue((h, data));
        }
    }

    /// <summary>Frees buffers Windows has finished playing.</summary>
    private void Reap(bool all)
    {
        while (_queued.Count > 0)
        {
            var (h, data) = _queued.Peek();
            var hdr = Marshal.PtrToStructure<WAVEHDR>(h);
            if (!all && (hdr.dwFlags & 1 /* WHDR_DONE */) == 0) break;
            _queued.Dequeue();
            waveOutUnprepareHeader(_wave, h, (uint)Marshal.SizeOf<WAVEHDR>());
            Marshal.FreeHGlobal(h);
            Marshal.FreeHGlobal(data);
        }
    }

    private static int FindCable()
    {
        var n = waveOutGetNumDevs();
        for (uint i = 0; i < n; i++)
        {
            var caps = new WAVEOUTCAPS();
            if (waveOutGetDevCaps((UIntPtr)i, ref caps, (uint)Marshal.SizeOf<WAVEOUTCAPS>()) == 0 &&
                caps.szPname.Contains("CABLE Input", StringComparison.OrdinalIgnoreCase)) return (int)i;
        }
        return -1;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct WAVEFORMATEX { public ushort wFormatTag, nChannels; public uint nSamplesPerSec, nAvgBytesPerSec; public ushort nBlockAlign, wBitsPerSample, cbSize; }

    [StructLayout(LayoutKind.Sequential)]
    private struct WAVEHDR { public IntPtr lpData; public uint dwBufferLength, dwBytesRecorded; public IntPtr dwUser; public uint dwFlags, dwLoops; public IntPtr lpNext, reserved; }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct WAVEOUTCAPS
    {
        public ushort wMid, wPid; public uint vDriverVersion;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string szPname;
        public uint dwFormats; public ushort wChannels, wReserved1; public uint dwSupport;
    }

    [DllImport("winmm.dll")] private static extern uint waveOutGetNumDevs();
    [DllImport("winmm.dll", CharSet = CharSet.Unicode)] private static extern int waveOutGetDevCaps(UIntPtr id, ref WAVEOUTCAPS caps, uint size);
    [DllImport("winmm.dll")] private static extern int waveOutOpen(out IntPtr h, uint id, ref WAVEFORMATEX fmt, IntPtr cb, IntPtr inst, uint flags);
    [DllImport("winmm.dll")] private static extern int waveOutPrepareHeader(IntPtr h, IntPtr hdr, uint size);
    [DllImport("winmm.dll")] private static extern int waveOutUnprepareHeader(IntPtr h, IntPtr hdr, uint size);
    [DllImport("winmm.dll")] private static extern int waveOutWrite(IntPtr h, IntPtr hdr, uint size);
    [DllImport("winmm.dll")] private static extern int waveOutReset(IntPtr h);
    [DllImport("winmm.dll")] private static extern int waveOutClose(IntPtr h);
}
