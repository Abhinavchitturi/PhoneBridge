using System.Diagnostics;

namespace PhoneBridge;

/// <summary>
/// Phone apps in their own windows on this PC (each on a hidden screen on the phone, so the phone stays free), via
/// scrcpy over Wireless debugging — port of mac/Sources/Streaming.swift. Debugging is only on during a session: the
/// phone switches Developer options on when you open an app here and off again when the last window closes.
/// Needs scrcpy (which includes adb): winget install Genymobile.scrcpy
/// </summary>
internal sealed class AppStreamer
{
    public enum State { MissingTools, NotPaired, Idle, Connecting, Ready }

    public State Status { get; private set; } = State.NotPaired;
    public string? Serial { get; private set; }
    public string? PairMessage { get; private set; }
    public bool IsPaired { get => _isPaired(); set => _setPaired(value); }

    public Action OnIdle = () => { };
    public Action OnSetupDone = () => { };
    public Action Changed = () => { };

    private readonly Func<bool> _isPaired;
    private readonly Action<bool> _setPaired;
    private const string PhonePackage = "app.phonebridge";
    private string? _adb, _scrcpy;
    private Process? _clipboardDaemon;
    private readonly Dictionary<string, Process> _windows = [];
    private int _attempt;

    public AppStreamer(Func<bool> isPaired, Action<bool> setPaired)
    {
        _isPaired = isPaired;
        _setPaired = setPaired;
    }

    public void Start()
    {
        _scrcpy = Find("scrcpy.exe");
        _adb = _scrcpy != null && File.Exists(Path.Combine(Path.GetDirectoryName(_scrcpy)!, "adb.exe"))
            ? Path.Combine(Path.GetDirectoryName(_scrcpy)!, "adb.exe") : Find("adb.exe");
        Status = _adb == null || _scrcpy == null ? State.MissingTools : IsPaired ? State.Idle : State.NotPaired;
        Changed();
    }

    /// <summary>scrcpy / adb from PATH, winget's package folder, or Scoop.</summary>
    private static string? Find(string exe)
    {
        foreach (var dir in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(';', StringSplitOptions.RemoveEmptyEntries))
        {
            var p = Path.Combine(dir.Trim(), exe);
            if (File.Exists(p)) return ResolveLink(p);
        }
        var local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        foreach (var root in new[] { Path.Combine(local, "Microsoft", "WinGet", "Packages"),
                                     Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "scoop", "apps") })
        {
            try
            {
                if (Directory.Exists(root))
                    foreach (var f in Directory.EnumerateFiles(root, exe, SearchOption.AllDirectories)) return f;
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException) { }
        }
        return null;
    }

    /// <summary>winget puts a symbolic link to scrcpy.exe on PATH; adb.exe lives next to the real file.</summary>
    private static string ResolveLink(string path)
    {
        try { return new FileInfo(path).ResolveLinkTarget(true)?.FullName ?? path; }
        catch (IOException) { return path; }
    }

    // MARK: Connection

    /// <summary>The phone has just switched Wireless debugging on: find it (mDNS) and connect, retrying for a while.</summary>
    public void Connect(Action<string?> done, int timeoutSeconds = 20)
    {
        if (Serial != null) { done(Serial); return; }
        if (Status == State.MissingTools) { done(null); return; }
        Status = State.Connecting;
        Changed();
        var attempt = ++_attempt;
        var deadline = DateTime.UtcNow.AddSeconds(timeoutSeconds);
        var ui = SynchronizationContext.Current;
        _ = Task.Run(async () =>
        {
            while (true)
            {
                var serial = FindOrConnect();
                if (attempt != _attempt) return;
                if (serial != null || DateTime.UtcNow > deadline)
                {
                    ui?.Post(_ =>
                    {
                        if (serial != null)
                        {
                            Serial = serial;
                            Status = State.Ready;
                            StartClipboardDaemon(serial);
                        }
                        else Status = IsPaired ? State.Idle : State.NotPaired;
                        Changed();
                        done(serial);
                    }, null);
                    return;
                }
                await Task.Delay(1500);
            }
        });
    }

    private string? FindOrConnect()
    {
        if (Connected() is { } s) return s;
        foreach (var line in Run(_adb!, "mdns services").Output.Split('\n'))
        {
            if (!line.Contains("_adb-tls-connect._tcp")) continue;
            var hostPort = line.Split(['\t', ' '], StringSplitOptions.RemoveEmptyEntries).LastOrDefault();
            if (hostPort != null) Run(_adb!, $"connect {hostPort}", 8);
        }
        return Connected();
    }

    private string? Connected()
    {
        foreach (var line in Run(_adb!, "devices").Output.Split('\n').Skip(1))
        {
            var parts = line.Trim().Split('\t');
            if (parts.Length == 2 && parts[1] == "device") return parts[0];
        }
        return null;
    }

    /// <summary>One-time setup: pair, then let PhoneBridge on the phone switch Developer options / Wireless debugging itself.</summary>
    public void Pair(string hostPort, string code, Action<string> report)
    {
        hostPort = hostPort.Trim();
        code = code.Trim();
        if (_adb == null) { report("Install scrcpy first: winget install Genymobile.scrcpy"); return; }
        if (hostPort.Length == 0 || code.Length == 0) { report("Enter both the IP address & port and the pairing code."); return; }
        report("Pairing…");
        var ui = SynchronizationContext.Current;
        _ = Task.Run(() =>
        {
            var result = Run(_adb, $"pair {hostPort} {code}", 20);
            ui?.Post(_ =>
            {
                if (!result.Output.Contains("Successfully paired"))
                {
                    report("Pairing failed. Check the code hasn't expired and try again.\n" + result.Output.Trim());
                    return;
                }
                IsPaired = true;
                report("Paired! Finishing setup…");
                Connect(serial =>
                {
                    if (serial == null) { report("Paired, but couldn't connect. Keep Wireless debugging on and press Pair again with a new code."); return; }
                    _ = Task.Run(() =>
                    {
                        var grant = Run(_adb, $"-s {serial} shell pm grant {PhonePackage} android.permission.WRITE_SECURE_SETTINGS");
                        ui?.Post(_ =>
                        {
                            if (grant.Status == 0)
                            {
                                report("All set. PhoneBridge will switch Developer options on only while you use a phone app here — and has switched them off now.");
                                EndSession();
                                OnSetupDone();
                            }
                            else report("Paired, but the phone refused the permission:\n" + grant.Output);
                        }, null);
                    });
                });
            }, null);
        });
    }

    // MARK: Windows

    public void Open(string pkg, string name)
    {
        if (Serial == null) return;
        if (_windows.TryGetValue(pkg, out var p) && !p.HasExited) { Focus(p); return; }
        if (Launch($"-s {Serial} --new-display=1080x2340/440 --start-app={pkg} --window-title=\"{name.Replace("\"", "")}\" --window-height=860", true) is { } w)
            _windows[pkg] = w;
    }

    public void Mirror(string title)
    {
        if (Serial == null) return;
        if (_windows.TryGetValue("__mirror", out var p) && !p.HasExited) { Focus(p); return; }
        if (Launch($"-s {Serial} --window-title=\"{title.Replace("\"", "")}\" --window-height=860", true) is { } w) _windows["__mirror"] = w;
    }

    private static void Focus(Process p)
    {
        try { SetForegroundWindow(p.MainWindowHandle); } catch (InvalidOperationException) { }
    }

    private void WindowClosed()
    {
        foreach (var k in _windows.Where(kv => kv.Value.HasExited).Select(kv => kv.Key).ToList()) _windows.Remove(k);
        if (_windows.Count == 0 && Serial != null) { EndSession(); OnIdle(); }
    }

    /// <summary>Ends the session here: no more adb connection or clipboard helper.</summary>
    private void EndSession()
    {
        StopClipboardDaemon();
        if (_adb != null) _ = Task.Run(() => Run(_adb, "disconnect"));
        Serial = null;
        Status = IsPaired ? State.Idle : State.NotPaired;
        Changed();
    }

    // scrcpy with no video/audio/window still runs its control channel, which syncs the phone clipboard to the PC.
    private void StartClipboardDaemon(string serial)
    {
        if (_clipboardDaemon is { HasExited: false }) return;
        _clipboardDaemon = Launch($"-s {serial} --no-video --no-audio --no-window", false);
    }

    private void StopClipboardDaemon()
    {
        try { if (_clipboardDaemon is { HasExited: false }) _clipboardDaemon.Kill(); } catch (InvalidOperationException) { }
        _clipboardDaemon = null;
    }

    public void StopAll()
    {
        StopClipboardDaemon();
        foreach (var p in _windows.Values) try { if (!p.HasExited) p.Kill(); } catch (InvalidOperationException) { }
    }

    private Process? Launch(string args, bool isWindow)
    {
        if (_scrcpy == null) return null;
        var psi = new ProcessStartInfo(_scrcpy, args) { UseShellExecute = false, CreateNoWindow = true };
        if (_adb != null) psi.Environment["ADB"] = _adb;
        try
        {
            var p = Process.Start(psi)!;
            if (isWindow)
            {
                var ui = SynchronizationContext.Current;
                p.EnableRaisingEvents = true;
                p.Exited += (_, _) => ui?.Post(_ => WindowClosed(), null);
            }
            return p;
        }
        catch (Exception e)
        {
            Log.Write("scrcpy failed to start: " + e.Message);
            return null;
        }
    }

    private static (int Status, string Output) Run(string exe, string args, int timeoutSeconds = 10)
    {
        try
        {
            var psi = new ProcessStartInfo(exe, args) { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true };
            using var p = Process.Start(psi)!;
            var output = p.StandardOutput.ReadToEndAsync();
            var error = p.StandardError.ReadToEndAsync();
            if (!p.WaitForExit(timeoutSeconds * 1000)) { try { p.Kill(); } catch (InvalidOperationException) { } }
            return (p.HasExited ? p.ExitCode : -1, output.Result + error.Result);
        }
        catch (Exception e) { return (-1, e.Message); }
    }

    [System.Runtime.InteropServices.DllImport("user32.dll")]
    private static extern bool SetForegroundWindow(IntPtr hwnd);
}
