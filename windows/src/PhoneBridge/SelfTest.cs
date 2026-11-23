using System.Text.Json.Nodes;

namespace PhoneBridge;

/// <summary>
/// PhoneBridge.exe --selftest: starts the app as usual, opens every window (main, phone, call card, chat pop-up,
/// phone screen), shows a notification, waits for each page to load, then quits. Writes what happened to
/// %LOCALAPPDATA%\PhoneBridge\selftest.txt and exits with 0 when everything loaded without errors.
/// Used by the Windows build on GitHub Actions (.github/workflows/windows.yml), where there is no phone.
/// </summary>
internal static class SelfTest
{
    public static bool Active { get; private set; }
    private static readonly List<string> Errors = [];
    private static readonly HashSet<string> Loaded = [];
    private static readonly string[] Expected = ["index.html", "phone.html", "call.html", "chat.html", "cast.html"];
    private static string File => Path.Combine(Log.Dir, "selftest.txt");

    public static void Begin()
    {
        Active = true;
        Directory.CreateDirectory(Log.Dir);
        System.IO.File.WriteAllText(File, $"self-test started {DateTime.Now:O}\n");
    }

    public static void Error(string where, object error)
    {
        if (!Active) return;
        lock (Errors) Errors.Add($"{where}: {error}");
        Append($"ERROR {where}: {error}");
    }

    public static void PageLoaded(string page)
    {
        if (!Active) return;
        lock (Loaded) Loaded.Add(page);
        Append($"loaded {page}");
    }

    public static void Run(TrayApp app)
    {
        var step = 0;
        var timer = new System.Windows.Forms.Timer { Interval = 2000 };
        timer.Tick += (_, _) =>
        {
            try
            {
                step++;
                Append($"step {step}");
                if (step == 1) app.SelfTestOpenEverything();
                bool all;
                lock (Loaded) all = Expected.All(Loaded.Contains);
                if (all && step >= 4 || step >= 30) // at most a minute
                {
                    timer.Stop();
                    app.SelfTestQuit();
                }
            }
            catch (Exception e)
            {
                Error("self-test step " + step, e);
                timer.Stop();
                app.SelfTestQuit();
            }
        };
        timer.Start();
    }

    public static void End()
    {
        if (!Active) return;
        string[] missing;
        lock (Loaded) missing = Expected.Where(p => !Loaded.Contains(p)).ToArray();
        int errors;
        lock (Errors) errors = Errors.Count;
        Append(missing.Length == 0 && errors == 0 ? "RESULT PASS" : $"RESULT FAIL (errors: {errors}, pages not loaded: {string.Join(", ", missing)})");
        Environment.ExitCode = missing.Length == 0 && errors == 0 ? 0 : 1;
        Active = false;
    }

    private static void Append(string line)
    {
        try { System.IO.File.AppendAllText(File, $"{DateTime.Now:HH:mm:ss.fff} {line}\n"); } catch (IOException) { }
    }
}

internal sealed partial class TrayApp
{
    internal void SelfTestOpenEverything()
    {
        _window?.ShowAndActivate();
        OpenPhone("messages");
        _callCard?.Update(new JsonObject { ["t"] = "call", ["state"] = "ringing", ["name"] = "Self-test", ["number"] = "+10000000000", ["incoming"] = true });
        _chat?.Show(new JsonObject { ["t"] = "notif", ["id"] = "selftest", ["app"] = "PhoneBridge", ["title"] = "Self-test", ["text"] = "Checking the chat pop-up", ["reply"] = true });
        OpenCast(null, "Self-test");
        _toasts.Info("PhoneBridge self-test", "Checking notifications");
        foreach (var theme in new[] { "batman", "midnight" }) SetTheme(theme, fromPhone: true);
    }

    internal void SelfTestQuit()
    {
        _callCard?.Hide();
        _chat?.Hide();
        Quit();
    }
}
