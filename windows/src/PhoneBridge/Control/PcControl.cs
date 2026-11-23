using System.Diagnostics;
using System.Media;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json.Nodes;
using System.Windows.Automation;
using PhoneBridge.Core;

namespace PhoneBridge.Control;

/// <summary>
/// Commands the phone runs on this PC ("mac_cmd" — the phone's computer remote) and the PC state it shows ("mac_state").
/// Port of mac/Sources/MacControl.swift:
///  lock / sleep / volume ± / mute / play-pause-next-prev / Find my PC / open a link / run a shortcut / mute for a call /
///  video controls (±10 s, Esc, full screen) for the video app / send the browser's tab to the phone / battery alerts.
/// "Shortcuts" on Windows are the files in %APPDATA%\PhoneBridge\Shortcuts (shortcuts, scripts, programs, links).
/// </summary>
internal sealed class PcControl
{
    public Action<JsonObject> Send = _ => { };
    public Action<string, string> Notify = (_, _) => { };
    public Action<string, JsonObject> Deck = (_, _) => { };
    public Action<bool> OnCall = _ => { };

    private readonly PcMedia _media;
    private JsonObject? _lastState;
    private bool _mutedForCall;
    private bool _lowAlerted, _fullAlerted;
    private readonly System.Windows.Forms.Timer _timer = new() { Interval = 20_000 };
    private IntPtr _hook;
    private WinEventProc? _hookProc;

    public static readonly string ShortcutsDir =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "PhoneBridge", "Shortcuts");

    public PcControl(PcMedia media) => _media = media;

    public void Start()
    {
        _timer.Tick += (_, _) => { SendState(force: false); BatteryAlerts(); };
        _timer.Start();
        // The phone's Home shows controls for whatever is in front (a video → ±10 s, Esc, Full screen).
        _hookProc = (_, _, _, _, _, _, _) => SendState(force: false);
        _hook = SetWinEventHook(3 /* EVENT_SYSTEM_FOREGROUND */, 3, IntPtr.Zero, _hookProc, 0, 0, 0 /* WINEVENT_OUTOFCONTEXT */);
        try
        {
            Directory.CreateDirectory(ShortcutsDir);
            var readme = Path.Combine(ShortcutsDir, "README.txt");
            if (!File.Exists(readme))
                File.WriteAllText(readme,
                    "Put shortcuts, scripts (.bat, .cmd, .ps1), programs or links (.url) in this folder.\r\n" +
                    "They appear in PhoneBridge on your phone under the computer's shortcuts, and run on this PC when you tap them.\r\n");
        }
        catch (IOException) { }
    }

    public void Handle(JsonObject m)
    {
        var cmd = PhoneLink.Str(m, "cmd");
        Log.Write("mac_cmd " + cmd);
        switch (cmd)
        {
            case "lock": LockWorkStation(); break;
            case "sleep": Task.Delay(500).ContinueWith(_ => SetSuspendState(false, false, false)); break;
            case "deck_on" or "deck_off" or "deck_do": Deck(cmd, m); break;
            case "play_pause" or "next" or "prev": if (!_media.Command(cmd)) InputControl.MediaKey(cmd); break;
            case "vol_up": ChangeVolume(0.0625f); break;
            case "vol_down": ChangeVolume(-0.0625f); break;
            case "mute": Audio.Muted = !Audio.Muted; break;
            case "find": FindMyPc(); break;
            case "find_stop": StopFinding(); break;
            case "open_url":
                if (PhoneLink.Str(m, "url") is { } url && Uri.TryCreate(url, UriKind.Absolute, out var u) && (u.Scheme == "http" || u.Scheme == "https"))
                    Shell.Open(u.AbsoluteUri);
                break;
            case "run_shortcut": if (PhoneLink.Str(m, "name") is { } name) RunShortcut(name); break;
            case "shortcuts_request": Send(new JsonObject { ["t"] = "shortcuts", ["names"] = new JsonArray(ShortcutNames().Select(n => (JsonNode)n).ToArray()) }); break;
            case "call_mute_on":
                OnCall(true);
                if (!Audio.Muted) { Audio.Muted = true; _mutedForCall = true; }
                break;
            case "call_mute_off":
                if (_mutedForCall) { Audio.Muted = false; _mutedForCall = false; }
                OnCall(false);
                break;
            case "video":
                VideoControl(PhoneLink.Str(m, "action") ?? "", m["value"] is JsonValue v && v.TryGetValue<int>(out var s) ? s : 0);
                break;
            case "tab_request": SendTabToPhone(); break;
        }
        SendState(force: true);
    }

    private static void ChangeVolume(float by)
    {
        Audio.Muted = false;
        Audio.Volume = (Audio.Volume ?? 0) + by;
    }

    // MARK: Find my PC

    public bool Finding { get; private set; }
    private System.Windows.Forms.Timer? _findTimer;
    private (string? Device, float Volume, bool Muted)? _savedSound;
    private SoundPlayer? _alarm;

    /// <summary>Loud repeating sound for up to 30 s; stoppable from the phone. Volume goes to 100% and is restored on stop.</summary>
    private void FindMyPc()
    {
        if (Finding) return;
        _savedSound = (Audio.DeviceId, Audio.Volume ?? 0.5f, Audio.Muted);
        Audio.Volume = 1;
        Audio.Muted = false;
        Finding = true;
        _alarm ??= new SoundPlayer(new MemoryStream(Chime()));
        _alarm.Play();
        var plays = 0;
        _findTimer = new System.Windows.Forms.Timer { Interval = 1200 };
        _findTimer.Tick += (_, _) =>
        {
            _alarm.Play();
            if (++plays >= 25) StopFinding();
        };
        _findTimer.Start();
        Notify("Find my PC", "Your phone is looking for this PC.");
    }

    public void StopFinding()
    {
        _findTimer?.Dispose();
        _findTimer = null;
        _alarm?.Stop();
        if (!Finding) return;
        Finding = false;
        if (_savedSound is { } saved && saved.Device == Audio.DeviceId) // output changed meanwhile: leave the new device alone
        {
            Audio.Volume = saved.Volume;
            Audio.Muted = saved.Muted;
        }
        _savedSound = null;
        SendState(force: true);
    }

    /// <summary>A bright two-tone chime (0.9 s, 16-bit mono WAV), made here so nothing depends on Windows' sound scheme.</summary>
    private static byte[] Chime()
    {
        const int rate = 22050;
        var samples = (int)(rate * 0.9);
        var ms = new MemoryStream();
        var w = new BinaryWriter(ms);
        w.Write("RIFF"u8); w.Write(36 + samples * 2); w.Write("WAVEfmt "u8); w.Write(16); w.Write((short)1); w.Write((short)1);
        w.Write(rate); w.Write(rate * 2); w.Write((short)2); w.Write((short)16); w.Write("data"u8); w.Write(samples * 2);
        for (var i = 0; i < samples; i++)
        {
            var t = (double)i / rate;
            var f = t < 0.45 ? 1318.5 : 1760.0; // E6 then A6
            var local = t % 0.45;
            var env = Math.Min(1, local * 60) * Math.Exp(-local * 5);
            w.Write((short)(Math.Sin(2 * Math.PI * f * t) * env * 30000));
        }
        return ms.ToArray();
    }

    // MARK: Shortcuts

    private static IEnumerable<string> ShortcutFiles()
    {
        try
        {
            return Directory.EnumerateFiles(ShortcutsDir)
                .Where(f => !Path.GetFileName(f).Equals("README.txt", StringComparison.OrdinalIgnoreCase) && !Path.GetFileName(f).StartsWith('.'))
                .OrderBy(f => f, StringComparer.OrdinalIgnoreCase);
        }
        catch (IOException) { return []; }
    }

    private static List<string> ShortcutNames() => ShortcutFiles().Select(Path.GetFileNameWithoutExtension).OfType<string>().Distinct().ToList();

    private void RunShortcut(string name)
    {
        var file = ShortcutFiles().FirstOrDefault(f => Path.GetFileNameWithoutExtension(f).Equals(name, StringComparison.OrdinalIgnoreCase));
        if (file == null) return;
        try
        {
            var psi = Path.GetExtension(file).ToLowerInvariant() == ".ps1"
                ? new ProcessStartInfo("powershell.exe", $"-NoProfile -ExecutionPolicy Bypass -File \"{file}\"") { UseShellExecute = false, CreateNoWindow = true }
                : new ProcessStartInfo(file) { UseShellExecute = true };
            Process.Start(psi);
        }
        catch (Exception e)
        {
            Log.Write("shortcut failed: " + e.Message);
            Send(new JsonObject { ["t"] = "alert", ["title"] = "Shortcut didn't run", ["body"] = $"“{name}”: {e.Message}" });
        }
    }

    // MARK: The app in front, video controls, browser tab

    private static readonly HashSet<string> Browsers = new(StringComparer.OrdinalIgnoreCase)
        { "chrome", "msedge", "firefox", "brave", "vivaldi", "opera", "arc" };
    private static readonly HashSet<string> Players = new(StringComparer.OrdinalIgnoreCase)
        { "vlc", "wmplayer", "Microsoft.Media.Player", "mpc-hc64", "mpc-be64", "PotPlayerMini64", "mpv", "Video.UI" };

    private (IntPtr Window, string Process, DateTime At)? _lastVideo;

    private static (IntPtr Window, string Process, string Title) Front()
    {
        var hwnd = GetForegroundWindow();
        GetWindowThreadProcessId(hwnd, out var pid);
        string name;
        try { name = Process.GetProcessById((int)pid).ProcessName; }
        catch (Exception) { name = ""; }
        var sb = new StringBuilder(512);
        GetWindowText(hwnd, sb, sb.Capacity);
        return (hwnd, name, sb.ToString());
    }

    /// <summary>The window the video controls act on: a player in front, a browser in front while media plays,
    /// or the last such window (for 30 minutes, while it's open).</summary>
    private (IntPtr Window, string Process)? VideoWindow()
    {
        var f = Front();
        var playingApp = _media.CurrentApp;
        if (Players.Contains(f.Process) || (Browsers.Contains(f.Process) && playingApp.Length > 0))
        {
            _lastVideo = (f.Window, f.Process, DateTime.UtcNow);
            return (f.Window, f.Process);
        }
        if (_lastVideo is { } last && IsWindow(last.Window) && DateTime.UtcNow - last.At < TimeSpan.FromMinutes(30))
            return (last.Window, last.Process);
        return null;
    }

    private string? FrontContext() => VideoWindow() != null ? "video" : null;

    private void VideoControl(string action, int seconds)
    {
        if (VideoWindow() is not { } v) return;
        if (GetForegroundWindow() != v.Window)
        {
            SetForegroundWindow(v.Window);
            Task.Delay(350).ContinueWith(_ => PressVideoKey(action, seconds, v.Process, v.Window));
        }
        else PressVideoKey(action, seconds, v.Process, v.Window);
    }

    private static void PressVideoKey(string action, int seconds, string process, IntPtr window)
    {
        var browser = Browsers.Contains(process);
        var sb = new StringBuilder(512);
        GetWindowText(window, sb, sb.Capacity);
        var youtube = sb.ToString().Contains("YouTube", StringComparison.OrdinalIgnoreCase);
        switch (action)
        {
            case "skip":
                var fwd = seconds >= 0;
                if (browser && youtube) InputControl.Tap(fwd ? "l" : "j");                       // YouTube: J / L = 10 s
                else if (process.Equals("vlc", StringComparison.OrdinalIgnoreCase)) InputControl.Tap(fwd ? "right" : "left", InputControl.Alt); // VLC: Alt+arrow = 10 s
                else InputControl.Tap(fwd ? "right" : "left");                                     // most web players and apps
                break;
            case "esc": InputControl.Tap("esc"); break;
            case "fullscreen":
                if (browser || process.Equals("vlc", StringComparison.OrdinalIgnoreCase)) InputControl.Tap("f");
                else InputControl.Tap("f11");
                break;
        }
    }

    /// <summary>Sends the front (or any open) browser's current tab to the phone, read through UI Automation.</summary>
    public void SendTabToPhone()
    {
        _ = Task.Run(() =>
        {
            var f = Front();
            var windows = new List<(IntPtr, string)>();
            if (Browsers.Contains(f.Process)) windows.Add((f.Window, f.Title));
            foreach (var p in Process.GetProcesses().Where(p => Browsers.Contains(p.ProcessName) && p.MainWindowHandle != IntPtr.Zero))
                windows.Add((p.MainWindowHandle, p.MainWindowTitle));
            foreach (var (hwnd, title) in windows)
            {
                var url = AddressBar(hwnd);
                if (url == null) continue;
                if (!url.StartsWith("http", StringComparison.OrdinalIgnoreCase)) url = "https://" + url;
                var clean = System.Text.RegularExpressions.Regex.Replace(title, @"\s+[-—–]\s+(Google Chrome|Microsoft​? Edge|Mozilla Firefox|Brave|Vivaldi|Opera)$", "");
                Send(new JsonObject { ["t"] = "open_on_phone", ["url"] = url, ["title"] = clean });
                return;
            }
            Notify("No browser tab to send", "Open Chrome, Edge, Firefox, Brave or Vivaldi first.");
        });
    }

    private static string? AddressBar(IntPtr hwnd)
    {
        try
        {
            var root = AutomationElement.FromHandle(hwnd);
            var edits = root.FindAll(TreeScope.Descendants, new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.Edit));
            foreach (AutomationElement e in edits)
            {
                var name = e.Current.Name ?? "";
                if (!(name.Contains("address", StringComparison.OrdinalIgnoreCase) || name.Contains("URL", StringComparison.OrdinalIgnoreCase)
                      || name.Contains("search", StringComparison.OrdinalIgnoreCase))) continue;
                if (e.TryGetCurrentPattern(ValuePattern.Pattern, out var p) && p is ValuePattern vp)
                {
                    var v = vp.Current.Value?.Trim();
                    if (!string.IsNullOrEmpty(v) && v.Contains('.') && !v.Contains(' ')) return v;
                }
            }
        }
        catch (Exception e) when (e is ElementNotAvailableException or InvalidOperationException or COMException or ArgumentException) { }
        return null;
    }

    // MARK: Battery and state for the phone

    private static (int Level, bool Charging)? Battery()
    {
        var p = SystemInformation.PowerStatus;
        if (p.BatteryChargeStatus.HasFlag(BatteryChargeStatus.NoSystemBattery) || p.BatteryChargeStatus == BatteryChargeStatus.Unknown) return null;
        return ((int)Math.Round(p.BatteryLifePercent * 100), p.PowerLineStatus == PowerLineStatus.Online);
    }

    private void BatteryAlerts()
    {
        if (Battery() is not { } b) return;
        if (!b.Charging && b.Level <= 20 && !_lowAlerted)
        {
            _lowAlerted = true;
            Send(new JsonObject { ["t"] = "alert", ["title"] = "PC battery low", ["body"] = $"{b.Level}% — plug in your laptop" });
        }
        if (b.Charging || b.Level > 25) _lowAlerted = false;
        if (b.Charging && b.Level >= 100 && !_fullAlerted)
        {
            _fullAlerted = true;
            Send(new JsonObject { ["t"] = "alert", ["title"] = "PC fully charged", ["body"] = "You can unplug your laptop" });
        }
        if (!b.Charging) _fullAlerted = false;
    }

    public void SendState(bool force)
    {
        var state = new JsonObject
        {
            ["t"] = "mac_state",
            ["volume"] = (int)Math.Round((Audio.Volume ?? 0) * 100),
            ["muted"] = Audio.Muted,
            ["finding"] = Finding,
        };
        if (FrontContext() is { } ctx) state["front"] = ctx;
        if (Battery() is { } b) { state["battery"] = b.Level; state["charging"] = b.Charging; }
        if (!force && _lastState != null && JsonNode.DeepEquals(state, _lastState)) return;
        _lastState = (JsonObject)state.DeepClone();
        Send(state);
    }

    public void Stop()
    {
        _timer.Dispose();
        if (_hook != IntPtr.Zero) UnhookWinEvent(_hook);
        StopFinding();
    }

    private delegate void WinEventProc(IntPtr hook, uint ev, IntPtr hwnd, int obj, int child, uint thread, uint time);

    [DllImport("user32.dll")] private static extern IntPtr SetWinEventHook(uint min, uint max, IntPtr mod, WinEventProc proc, uint pid, uint tid, uint flags);
    [DllImport("user32.dll")] private static extern bool UnhookWinEvent(IntPtr hook);
    [DllImport("user32.dll")] private static extern bool LockWorkStation();
    [DllImport("powrprof.dll")] private static extern bool SetSuspendState(bool hibernate, bool force, bool disableWake);
    [DllImport("user32.dll")] private static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] private static extern bool SetForegroundWindow(IntPtr hwnd);
    [DllImport("user32.dll")] private static extern bool IsWindow(IntPtr hwnd);
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint pid);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetWindowText(IntPtr hwnd, StringBuilder text, int max);
}
