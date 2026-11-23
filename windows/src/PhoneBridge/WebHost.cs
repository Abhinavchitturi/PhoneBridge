using System.Runtime.InteropServices;
using System.Text.Json;
using System.Text.Json.Nodes;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace PhoneBridge;

/// <summary>
/// Loads one of the app's pages (ui/*.html, embedded) into a WebView2 and passes messages both ways:
/// state goes in with <see cref="Push"/>; the page's {cmd: …} messages come back through the callback.
/// All pages share one browser process (one CoreWebView2Environment), so extra panels stay light.
/// </summary>
internal sealed class WebHost
{
    private static Task<CoreWebView2Environment>? _env;
    private static readonly Color Background = Color.FromArgb(0x0B, 0x0B, 0x0D);

    public readonly WebView2 View;
    private readonly string _page;
    private readonly Action<string, JsonObject> _onCommand;
    private bool _ready;
    private readonly List<string> _pending = [];

    private readonly int _thread = Environment.CurrentManagedThreadId;
    // WebView2's setup is asynchronous and must come back to this (UI) thread after each step: make sure there is
    // a UI context to come back to, even if the page is created before Windows Forms installed one.
    private readonly SynchronizationContext? _context = EnsureContext();

    private static SynchronizationContext EnsureContext()
    {
        if (SynchronizationContext.Current is UiThread or WindowsFormsSynchronizationContext) return SynchronizationContext.Current;
        var ctx = new WindowsFormsSynchronizationContext();
        SynchronizationContext.SetSynchronizationContext(ctx);
        return ctx;
    }

    /// <summary>Runs `a` on the thread that owns the WebView2 (it throws if used from any other thread).</summary>
    private bool Marshal(string what, Action a)
    {
        if (Environment.CurrentManagedThreadId == _thread) return false;
        Log.Write($"{_page}: {what} called off the UI thread, moved onto it:\n{Environment.StackTrace}");
        if (View.IsHandleCreated) View.BeginInvoke(a);
        else if (_context != null) _context.Post(_ => a(), null);
        return true;
    }

    public WebHost(string page, Action<string, JsonObject> onCommand, bool transparent = false)
    {
        _page = page;
        _onCommand = onCommand;
        View = new WebView2 { Dock = DockStyle.Fill, DefaultBackgroundColor = transparent ? Color.Transparent : Background };
        MapFolder("art.phonebridge", ArtDir.Value);
        _ = InitAsync();
    }

    public static bool RuntimeAvailable
    {
        get
        {
            try { return !string.IsNullOrEmpty(CoreWebView2Environment.GetAvailableBrowserVersionString()); }
            catch (Exception) { return false; }
        }
    }

    private async Task InitAsync()
    {
        try
        {
            _env ??= CoreWebView2Environment.CreateAsync(null, Path.Combine(Log.Dir, "WebView2"));
            await View.EnsureCoreWebView2Async(await _env);
            var core = View.CoreWebView2;
            var s = core.Settings;
            s.AreDefaultContextMenusEnabled = false;
            s.IsZoomControlEnabled = false;
            s.AreBrowserAcceleratorKeysEnabled = false;
            s.IsStatusBarEnabled = false;
            s.IsSwipeNavigationEnabled = false;
#if !DEBUG
            s.AreDevToolsEnabled = false;
#endif
            foreach (var (h, f) in _maps) core.SetVirtualHostNameToFolderMapping(h, f, CoreWebView2HostResourceAccessKind.Allow);
            core.WebMessageReceived += OnMessage;
            // Script errors in a page end up in the log (and fail the self-test).
            core.Settings.AreDevToolsEnabled = true;
            await core.CallDevToolsProtocolMethodAsync("Runtime.enable", "{}");
            core.GetDevToolsProtocolEventReceiver("Runtime.exceptionThrown").DevToolsProtocolEventReceived += (_, e) =>
            {
                Log.Write($"{_page} script error: {e.ParameterObjectAsJson}");
                SelfTest.Error(_page + " script", e.ParameterObjectAsJson);
            };
#if !DEBUG
            core.Settings.AreDevToolsEnabled = false;
#endif
            core.NewWindowRequested += (_, e) => e.Handled = true;
            // The page is the whole app: never navigate away (e.g. a file dropped outside a drop handler).
            core.NavigationStarting += (_, e) => { if (_ready) e.Cancel = true; };
            core.NewWindowRequested += (_, e) =>
            {
                // Links the page opens (maps, the project page) go to the default browser.
                if (Uri.TryCreate(e.Uri, UriKind.Absolute, out var u) && u.Scheme == "https") Shell.Open(u.AbsoluteUri);
            };
            core.NavigateToString(Page(_page));
        }
        catch (Exception e)
        {
            Log.Write($"{_page} failed to start: {e}");
            SelfTest.Error(_page, e);
        }
    }

    /// <summary>An embedded page with its local stylesheets and scripts (ui/common.css, ui/common.js, …) inlined.</summary>
    internal static string Page(string name)
    {
        var html = Resource(name);
        html = System.Text.RegularExpressions.Regex.Replace(html, "<link rel=\"stylesheet\" href=\"([a-z]+\\.css)\">",
            m => "<style>" + Resource(m.Groups[1].Value) + "</style>");
        return System.Text.RegularExpressions.Regex.Replace(html, "<script src=\"([a-z]+\\.js)\"></script>",
            m => "<script>" + Resource(m.Groups[1].Value).Replace("</script", "<\\/script") + "</script>");
    }

    private readonly List<(string Host, string Folder)> _maps = [];

    /// <summary>The theme pictures, unpacked once from the app into %LOCALAPPDATA%\PhoneBridge\art.</summary>
    private static readonly Lazy<string> ArtDir = new(() =>
    {
        var dir = Path.Combine(Log.Dir, "art");
        Directory.CreateDirectory(dir);
        var asm = typeof(WebHost).Assembly;
        foreach (var name in asm.GetManifestResourceNames().Where(n => n.StartsWith("art/")))
        {
            var path = Path.Combine(dir, name[4..]);
            if (File.Exists(path)) continue;
            using var s = asm.GetManifestResourceStream(name)!;
            using var f = File.Create(path);
            s.CopyTo(f);
        }
        return dir;
    });

    /// <summary>Serves a local folder to the page at https://{host}/ (read-only) — used for full-size photos and videos.</summary>
    public void MapFolder(string host, string folder)
    {
        if (Marshal(nameof(MapFolder), () => MapFolder(host, folder))) return;
        Directory.CreateDirectory(folder);
        if (View.CoreWebView2 != null) View.CoreWebView2.SetVirtualHostNameToFolderMapping(host, folder, CoreWebView2HostResourceAccessKind.Allow);
        else _maps.Add((host, folder));
    }

    private static string Resource(string name)
    {
        using var stream = typeof(WebHost).Assembly.GetManifestResourceStream(name)
                           ?? throw new InvalidOperationException($"ui/{name} is not embedded");
        using var reader = new StreamReader(stream);
        return reader.ReadToEnd();
    }

    private void OnMessage(object? sender, CoreWebView2WebMessageReceivedEventArgs e)
    {
        JsonObject? m;
        try { m = JsonNode.Parse(e.WebMessageAsJson) as JsonObject; }
        catch (JsonException) { return; }
        if (m?["cmd"] is not JsonValue v || !v.TryGetValue<string>(out var cmd)) return;
        if (cmd == "ready")
        {
            if (!_ready) { Log.Write($"{_page} loaded"); SelfTest.PageLoaded(_page); }
            _ready = true;
            foreach (var p in _pending) View.CoreWebView2.PostWebMessageAsJson(p);
            _pending.Clear();
        }
        if (cmd == "drop")
        {
            // The page can't see file paths; WebView2 hands them to us alongside the message.
            var paths = new JsonArray();
            if (e.AdditionalObjects != null)
                foreach (var o in e.AdditionalObjects)
                    if (o is CoreWebView2File f && !string.IsNullOrEmpty(f.Path)) paths.Add(f.Path);
            m["paths"] = paths;
        }
        _onCommand(cmd, m);
    }

    /// <summary>Sends a message to the page ({type, …}); kept until the page is ready. "state" replaces any queued state.</summary>
    public void Push(string type, JsonObject body)
    {
        if (Marshal(nameof(Push), () => Push(type, body))) return;
        body["type"] = type;
        var json = body.ToJsonString();
        if (_ready && View.CoreWebView2 != null)
        {
            View.CoreWebView2.PostWebMessageAsJson(json);
            return;
        }
        if (type == "state") _pending.RemoveAll(p => p.Contains("\"type\":\"state\""));
        _pending.Add(json);
    }
}

/// <summary>
/// A small borderless card that floats over everything in the top-right corner (the call card, the chat reply box),
/// like the Mac's NSPanel cards. Shows without taking focus; clicking it focuses it (so you can type a reply).
/// </summary>
internal sealed class FloatingPanel : Form
{
    public readonly WebHost Host;
    private readonly int _margin = 14;

    public FloatingPanel(string page, Size size, Action<string, JsonObject> onCommand)
    {
        FormBorderStyle = FormBorderStyle.None;
        ShowInTaskbar = false;
        TopMost = true;
        StartPosition = FormStartPosition.Manual;
        AutoScaleMode = AutoScaleMode.Dpi;
        AutoScaleDimensions = new SizeF(96F, 96F);
        BackColor = Color.FromArgb(0x0B, 0x0B, 0x0D);
        Host = new WebHost(page, onCommand);
        Controls.Add(Host.View);
        SetLogicalSize(size);
    }

    protected override bool ShowWithoutActivation => true;

    protected override CreateParams CreateParams
    {
        get
        {
            var cp = base.CreateParams;
            cp.ExStyle |= 0x80;      // WS_EX_TOOLWINDOW: no taskbar button, not in Alt+Tab
            cp.ClassStyle |= 0x20000; // CS_DROPSHADOW
            return cp;
        }
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        Dwm.Darken(Handle, BackColor);
        Dwm.RoundCorners(Handle);
    }

    /// <summary>Resizes in logical (96-dpi) units, growing downwards from the top-right corner.</summary>
    public void SetLogicalSize(Size size)
    {
        var scale = DeviceDpi / 96f;
        var px = new Size((int)Math.Round(size.Width * scale), (int)Math.Round(size.Height * scale));
        if (ClientSize != px) ClientSize = px;
        Place();
    }

    private void Place()
    {
        var area = (Screen.PrimaryScreen ?? Screen.AllScreens[0]).WorkingArea;
        Location = new Point(area.Right - Width - _margin, area.Top + _margin);
    }

    public void ShowQuietly()
    {
        if (!Visible) Show();
        SetWindowPos(Handle, new IntPtr(-1) /* HWND_TOPMOST */, 0, 0, 0, 0, 0x0001 | 0x0002 | 0x0010 /* NOSIZE|NOMOVE|NOACTIVATE */);
    }

    [DllImport("user32.dll")]
    private static extern bool SetWindowPos(IntPtr hwnd, IntPtr after, int x, int y, int cx, int cy, uint flags);
}
