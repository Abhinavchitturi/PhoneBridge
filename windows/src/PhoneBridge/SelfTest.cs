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
                if (step == 2) _ = Task.Run(app.SelfTestPhoneTalks); // like the network thread does
                bool all;
                lock (Loaded) all = Expected.All(Loaded.Contains);
                if (all && step >= 8 || step >= 30) // at most a minute
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

    /// <summary>What a connected phone sends, delivered from a background thread exactly as the link does.</summary>
    internal async Task SelfTestPhoneTalks()
    {
        JsonObject J(string json) => (JsonObject)JsonNode.Parse(json)!;
        _ui.Post(_ => Update(Core.LinkState.Connected, "Self-test phone"), null);
        await Task.Delay(500);
        string[] messages =
        [
            """{"t":"info","name":"Self-test phone","addrs":["192.168.1.5","100.100.1.2"]}""",
            """{"t":"battery","level":64,"charging":true}""",
            """{"t":"phone_dnd","on":false}""",
            """{"t":"theme","id":"thor"}""",
            """{"t":"note","text":"self-test note","ts":99999999999999}""",
            """{"t":"notif","id":"0|com.whatsapp|1","pkg":"com.whatsapp","app":"WhatsApp","title":"Maya","text":"Dinner tonight?","reply":true,"actions":["Reply","Mark as read"]}""",
            """{"t":"notif","id":"0|com.google.android.gm|2","pkg":"com.google.android.gm","app":"Gmail","title":"Order shipped","text":"Arriving Thursday","reply":false,"actions":[]}""",
            """{"t":"notif_removed","id":"0|com.google.android.gm|2"}""",
            """{"t":"sms_threads","threads":[{"thread":1,"address":"+15550134","name":"Maya","snippet":"See you","date":1700000000000,"unread":true}]}""",
            """{"t":"sms_thread","thread":1,"messages":[{"body":"See you","date":1700000000000,"mine":false}]}""",
            """{"t":"contacts","contacts":[{"name":"Maya","number":"+15550134"}]}""",
            """{"t":"call_log","calls":[{"number":"+15550134","name":"Maya","type":"missed","date":1700000000000,"duration":0}]}""",
            """{"t":"device","total":256000000000,"free":90000000000,"images":30000000000,"videos":60000000000,"torch":false,"ringer":"normal"}""",
            """{"t":"apps","apps":[{"pkg":"com.whatsapp","name":"WhatsApp"}]}""",
            """{"t":"ring_state","ringing":true}""",
            """{"t":"ring_state","ringing":false}""",
            """{"t":"call","state":"ringing","name":"Maya","number":"+15550134","incoming":true}""",
            """{"t":"call","state":"active","name":"Maya","number":"+15550134","connected":true,"incoming":true}""",
            """{"t":"call_ctl_state","muted":true,"speaker":false,"held":false}""",
            """{"t":"call","state":"idle"}""",
            """{"t":"mac_cmd","cmd":"vol_up"}""",
            """{"t":"mac_cmd","cmd":"vol_down"}""",
            """{"t":"mac_cmd","cmd":"shortcuts_request"}""",
            """{"t":"mac_cmd","cmd":"deck_on"}""",
            """{"t":"clip","text":"copied on the phone"}""",
            """{"t":"otp","code":"482193","from":"Bank"}""",
            """{"t":"alert","title":"Self-test","body":"An alert from the phone"}""",
            """{"t":"file_start","fid":"F1","name":"selftest.txt","size":5,"mime":"text/plain"}""",
            """{"t":"file_chunk","fid":"F1","data":"aGVsbG8="}""",
            """{"t":"file_end","fid":"F1"}""",
            """{"t":"cast_state","on":false}""",
        ];
        foreach (var json in messages)
        {
            OnLinkMessage(J(json));
            await Task.Delay(120);
        }
        // Calls that would normally come from timers and other threads.
        Refresh();
        PushState();
        SchedulePhoneState();
        _ui.Post(_ => OpenPhone("messages", "+15550134"), null);
        await Task.Delay(1500);
        _ui.Post(_ => Update(Core.LinkState.Searching, ""), null);
    }

    internal void SelfTestQuit()
    {
        _callCard?.Hide();
        _chat?.Hide();
        Quit();
    }
}
