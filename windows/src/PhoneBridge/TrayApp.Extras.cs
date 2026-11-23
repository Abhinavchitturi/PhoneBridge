using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text.Json.Nodes;
using PhoneBridge.Core;
using Windows.Devices.Bluetooth.Advertisement;

namespace PhoneBridge;

/// <summary>
/// The rest of the Mac's extras on Windows: lock when the phone walks away (Presence), the phone as a microphone,
/// new PC screenshots → phone, Type on phone, the Explorer "Send to" entry, and phone apps / the phone's screen
/// (scrcpy windows or the cast window).
/// </summary>
internal sealed partial class TrayApp
{
    // MARK: Presence (mac/Sources/Presence.swift; beacon layout from android/.../Automations.kt)

    private static readonly Guid PresenceService = Guid.Parse("6a1b2c3d-5e6f-4a7b-8c9d-50484f4e4542");
    private BluetoothLEAdvertisementWatcher? _watcher;
    private readonly System.Windows.Forms.Timer _presenceTimer = new() { Interval = 2000 };
    private double? _rssi;
    private DateTime? _lastSeen, _weakSince;
    private bool _armed, _lockedByUs;
    private string PresenceStatus { get; set; } = "Waiting for your phone's beacon…";

    private void PresenceInit()
    {
        _presenceTimer.Tick += (_, _) => PresenceEvaluate();
        PresenceChanged();
    }

    private void PresenceChanged()
    {
        var on = _store.GetBool("presence", false);
        if (on && _watcher == null)
        {
            try
            {
                _watcher = new BluetoothLEAdvertisementWatcher { ScanningMode = BluetoothLEScanningMode.Passive };
                _watcher.AdvertisementFilter.Advertisement.ServiceUuids.Add(PresenceService);
                _watcher.Received += (_, e) => _ui.Post(_ => PresenceSighting(e), null);
                _watcher.Stopped += (_, e) => _ui.Post(_ =>
                {
                    if (e.Error != Windows.Devices.Bluetooth.BluetoothError.Success) { PresenceStatus = "Bluetooth is off or not available"; SchedulePhoneState(); }
                }, null);
                _watcher.Start();
                _presenceTimer.Start();
            }
            catch (Exception e)
            {
                Log.Write("presence: " + e.Message);
                PresenceStatus = "Bluetooth isn't available on this PC";
                _watcher = null;
            }
        }
        else if (!on && _watcher != null)
        {
            _watcher.Stop();
            _watcher = null;
            _presenceTimer.Stop();
            _armed = false;
        }
        SchedulePhoneState();
    }

    private void PresenceSighting(BluetoothLEAdvertisementReceivedEventArgs e)
    {
        if (_store.TrustedPhone is not { } pub) return;
        var id = SHA256.HashData(pub).AsSpan(0, 6).ToArray(); // the phone puts this in its beacon
        var match = e.Advertisement.ManufacturerData.Any(md =>
        {
            if (md.CompanyId != 0xFFFF || md.Data.Length < 6) return false;
            var data = new byte[md.Data.Length];
            using (var r = Windows.Storage.Streams.DataReader.FromBuffer(md.Data)) r.ReadBytes(data);
            return data.AsSpan(0, 6).SequenceEqual(id);
        });
        if (!match || e.RawSignalStrengthInDBm >= 0) return;
        _rssi = _rssi is { } s ? s * 0.7 + e.RawSignalStrengthInDBm * 0.3 : e.RawSignalStrengthInDBm;
        _lastSeen = DateTime.UtcNow;
        _armed = true; // only after we've actually seen the phone, so a missing beacon never locks
        PresenceEvaluate();
    }

    private void PresenceEvaluate()
    {
        if (_lastSeen is not { } seen || _rssi is not { } rssi) return;
        var threshold = _store.Get("presenceRange")?.GetValue<int>() ?? -75;
        var gone = DateTime.UtcNow - seen > TimeSpan.FromSeconds(25);
        var weak = rssi < threshold;
        _weakSince = weak ? _weakSince ?? DateTime.UtcNow : null;
        var away = gone || (_weakSince is { } ws && DateTime.UtcNow - ws > TimeSpan.FromSeconds(10));
        var status = away ? "Phone away" : $"Phone nearby (signal {(int)rssi} dBm)";
        if (status != PresenceStatus) { PresenceStatus = status; SchedulePhoneState(); }
        if (away && _armed && !_lockedByUs && IdleSeconds() > 20)
        {
            _lockedByUs = true;
            LockWorkStation();
        }
        else if (!away && _lockedByUs && rssi > threshold + 5)
        {
            _lockedByUs = false;
            // Wake the screen: a zero-length mouse move counts as user activity.
            mouse_event(0x0001, 0, 0, 0, IntPtr.Zero);
        }
    }

    /// <summary>Seconds since the last keyboard/mouse input — don't lock someone who's actively using the PC.</summary>
    private static double IdleSeconds()
    {
        var info = new LASTINPUTINFO { cbSize = (uint)Marshal.SizeOf<LASTINPUTINFO>() };
        return GetLastInputInfo(ref info) ? (Environment.TickCount - (int)info.dwTime) / 1000.0 : 0;
    }

    [StructLayout(LayoutKind.Sequential)] private struct LASTINPUTINFO { public uint cbSize; public uint dwTime; }
    [DllImport("user32.dll")] private static extern bool GetLastInputInfo(ref LASTINPUTINFO info);
    [DllImport("user32.dll")] private static extern bool LockWorkStation();
    [DllImport("user32.dll")] private static extern void mouse_event(uint flags, int dx, int dy, int data, IntPtr extra);

    // MARK: Phone as microphone (Capture.swift PhoneMic → VB-CABLE instead of BlackHole)

    private PhoneMic? _mic;
    private string _micStatus = "Off";
    private bool _micActive;

    private void MicState(bool on)
    {
        _mic ??= new PhoneMic();
        if (on) _mic.Start(); else _mic.Stop();
        _micActive = _mic.Active;
        _micStatus = _mic.Status;
        SchedulePhoneState();
        if (on && !_mic.Active) _toasts.Info("Phone as microphone", _mic.Status);
    }

    private void MicAudio(string b64) => _mic?.Play(b64);

    // MARK: Screenshots, Send to, Type on phone

    private FileSystemWatcher? _shots;
    private DateTime _started = DateTime.UtcNow;
    private System.Windows.Forms.Timer? _typeDebounce;

    private void ExtrasInit()
    {
        ExtrasChanged();
        InstallSendTo();
        StreamInit();
    }

    /// <summary>New PC screenshots → phone (Win+PrtScn and Snipping Tool save them in Pictures\Screenshots).</summary>
    private void ExtrasChanged()
    {
        var on = _store.GetBool("screenshots", true);
        if (on && _shots == null)
        {
            var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyPictures), "Screenshots");
            Directory.CreateDirectory(dir);
            _started = DateTime.UtcNow;
            _shots = new FileSystemWatcher(dir) { IncludeSubdirectories = false, EnableRaisingEvents = true };
            _shots.Created += (_, e) => _ui.Post(async _ =>
            {
                if (!_connected || !(e.Name ?? "").EndsWith(".png", StringComparison.OrdinalIgnoreCase)) return;
                await Task.Delay(800); // let Windows finish writing it
                if (File.GetCreationTimeUtc(e.FullPath) > _started) _ = SendFiles([e.FullPath], kind: "screenshot", quiet: true);
            }, null);
        }
        else if (!on && _shots != null)
        {
            _shots.Dispose();
            _shots = null;
        }
    }

    /// <summary>Puts "PhoneBridge" in Explorer's right-click → Send to menu (a shortcut in the SendTo folder).</summary>
    private static void InstallSendTo()
    {
        try
        {
            var exe = Environment.ProcessPath;
            if (exe == null) return;
            var link = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.SendTo), "PhoneBridge.lnk");
            var shellType = Type.GetTypeFromProgID("WScript.Shell");
            if (shellType == null) return;
            dynamic shell = Activator.CreateInstance(shellType)!;
            var sc = shell.CreateShortcut(link);
            if (File.Exists(link) && string.Equals((string)sc.TargetPath, exe, StringComparison.OrdinalIgnoreCase)) return;
            sc.TargetPath = exe;
            sc.Arguments = "--send";
            sc.Description = "Send to your phone with PhoneBridge";
            sc.IconLocation = exe + ",0";
            sc.Save();
        }
        catch (Exception e) { Log.Write("send to: " + e.Message); }
    }

    /// <summary>Files (or text) handed over by a second PhoneBridge.exe started from Send to / the command line.</summary>
    internal void FromCommandLine(string[] args)
    {
        if (args.Contains("--quit")) { Quit(); return; } // the installer is replacing this copy
        var files = args.Where(a => !a.StartsWith("--") && File.Exists(a)).ToArray();
        if (files.Length > 0) _ = SendFiles(files);
        else if (args.SkipWhile(a => a != "--text").Skip(1).FirstOrDefault() is { } text)
        {
            _ = _link.SendAsync("clip", ("text", text));
            _toasts.Info("Sent to phone", text.Length > 80 ? text[..80] : text);
        }
        else _window?.ShowAndActivate();
    }

    /// <summary>Mirrors the "Type on phone" text into the phone's focused text field (debounced).</summary>
    private void TypeOnPhone(string text)
    {
        _typeDebounce?.Dispose();
        _typeDebounce = new System.Windows.Forms.Timer { Interval = 150 };
        _typeDebounce.Tick += (_, _) => { _typeDebounce?.Dispose(); _typeDebounce = null; _ = _link.SendAsync("phone_set_text", ("text", text)); };
        _typeDebounce.Start();
    }

    // MARK: Phone apps and the phone's screen (App.swift openApp / mirror / startSession)

    private AppStreamer? _streamer;
    private CastWindow? _cast;
    private Action? _pendingOpen, _pendingFallback;

    private string StreamStatus => _streamer?.Status switch
    {
        AppStreamer.State.MissingTools => "missing", AppStreamer.State.Ready => "ready", AppStreamer.State.Connecting => "connecting",
        AppStreamer.State.Idle => "idle", _ => "notPaired",
    };

    private string StreamNote => _streamer?.Status switch
    {
        AppStreamer.State.Ready => "Session running — Developer options are on until you close the last phone window. Clipboard syncs automatically.",
        AppStreamer.State.Connecting => "Connecting to your phone…",
        AppStreamer.State.Idle => "Apps open in their own windows while your phone stays free. PhoneBridge switches Developer options on only while a phone window is open (phone on Wi-Fi). On a hotspot it shows the phone's screen instead.",
        AppStreamer.State.MissingTools => "Right now apps open by showing the phone's screen. For apps in their own windows, install scrcpy (it includes adb): open a terminal and run  winget install Genymobile.scrcpy , then restart PhoneBridge.",
        _ => "Right now apps open by showing the phone's screen. For apps in their own windows while your phone stays free, do the one-time setup (on Wi-Fi). Afterwards Developer options stay off except while you use a phone app here.",
    } + (_streamer?.PairMessage is { } pm ? "\n" + pm : "");

    private void StreamInit()
    {
        _streamer = new AppStreamer(() => _store.GetBool("adbPaired", false), v => _store.SetBool("adbPaired", v))
        {
            OnIdle = () => _ = _link.SendAsync("debug_off"),
            OnSetupDone = () => _ = _link.SendAsync("debug_off"),
            Changed = SchedulePhoneState,
        };
        _ = Task.Run(() => _ui.Post(_ => _streamer.Start(), null));
    }

    private void OpenApp(string pkg, string name)
    {
        StartSession(() => _streamer!.Open(pkg, name), () => OpenCast(pkg, name), name);
    }

    private void Mirror(string? _)
    {
        var name = _phoneName;
        StartSession(() => _streamer!.Mirror(name), () => OpenCast(null, name), name);
    }

    private void StartSession(Action open, Action fallback, string label)
    {
        if (_streamer?.Serial != null) { open(); return; } // session already running
        if (_streamer == null || !_streamer.IsPaired || _streamer.Status == AppStreamer.State.MissingTools || !_connected) { fallback(); return; }
        _pendingOpen = open;
        _pendingFallback = fallback;
        if (_streamer.Status == AppStreamer.State.Connecting) return; // already on its way
        _toasts.Info($"Opening {label}…", "Switching on wireless debugging on your phone");
        _ = _link.SendAsync("debug_on");
        var t = new System.Windows.Forms.Timer { Interval = 25_000 };
        t.Tick += (_, _) =>
        {
            t.Dispose();
            if (_pendingOpen != null && _streamer.Serial == null) RunFallback("Couldn't reach the phone over Wi-Fi — showing its screen instead.");
        };
        t.Start();
    }

    private void StreamDebugState(JsonObject m)
    {
        if (_pendingOpen == null) return;
        if (!(m["on"] is JsonValue v && v.TryGetValue<bool>(out var on) && on))
        {
            RunFallback(PhoneLink.Str(m, "reason") switch
            {
                "no_wifi" => "Your phone isn't on Wi-Fi (e.g. it's sharing a hotspot), so showing its screen instead.",
                "no_permission" => "Finish the one-time setup in Settings → Phone apps & screen to open apps separately.",
                _ => null,
            });
            return;
        }
        _streamer!.Connect(serial =>
        {
            if (serial != null)
            {
                _pendingOpen?.Invoke();
                _pendingOpen = _pendingFallback = null;
            }
            else
            {
                _ = _link.SendAsync("debug_off");
                RunFallback("Wireless debugging didn't connect — showing the phone's screen instead.");
            }
        });
    }

    private void RunFallback(string? why)
    {
        if (why != null) _toasts.Info("Phone apps", why);
        _pendingFallback?.Invoke();
        _pendingOpen = _pendingFallback = null;
    }

    /// <summary>One-time setup: asks for the phone's Wireless debugging pairing address and code.</summary>
    private void StreamSetup()
    {
        var w = EnsurePhoneWindow();
        w?.Push("stream-setup", new JsonObject());
    }

    internal void StreamPair(string hostPort, string code) =>
        _streamer?.Pair(hostPort, code, msg => { _phoneWin?.Push("stream-message", new JsonObject { ["text"] = msg }); SchedulePhoneState(); });

    private void OpenCast(string? pkg, string title)
    {
        if (_window == null) return;
        if (_cast == null || _cast.IsDisposed)
        {
            _cast = new CastWindow(_icon)
            {
                Send = m => _ = _link.SendAsync(m),
            };
        }
        _cast.Open(pkg, title);
    }

    /// <summary>A video frame from the phone ("v"), on the link's thread: ack straight away, draw on the UI thread.</summary>
    private void CastFrame(JsonObject m)
    {
        if (m["s"] is JsonValue sv && sv.TryGetValue<long>(out var seq)) _ = _link.SendAsync("va", ("s", seq));
        _ui.Post(_ => _cast?.Frame(m), null);
    }

    private void CastMessage(JsonObject m)
    {
        if (PhoneLink.Str(m, "t") == "cast_diag") { _cast?.Diag(m); return; }
        var on = m["on"] is JsonValue v && v.TryGetValue<bool>(out var b) && b;
        if (on) { if (_cast == null || _cast.IsDisposed) OpenCast(null, _phoneName); _cast?.State(m); }
        else
        {
            if (PhoneLink.Str(m, "error") is { } err) _toasts.Info("Phone screen not shown", err);
            _cast?.CloseQuietly();
        }
    }
}
