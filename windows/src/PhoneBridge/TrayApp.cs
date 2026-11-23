using System.Reflection;
using System.Security.Cryptography;
using System.Text.Json.Nodes;
using PhoneBridge.Core;
using LinkState = PhoneBridge.Core.LinkState;

namespace PhoneBridge;

/// <summary>
/// The tray icon and everything it drives: the link to the phone, pairing, toasts, clipboard, battery, files, calls.
/// Mirrors the Mac app's handling in mac/Sources/App.swift for the features Windows has so far.
/// All UI work happens on the UI thread; link events are posted to it.
/// </summary>
internal sealed class TrayApp : ApplicationContext
{
    private const string Repo = "https://github.com/Abhinavchitturi/PhoneBridge";

    private readonly SynchronizationContext _ui;
    private readonly LinkStore _store;
    private readonly PhoneLink _link;
    private readonly Transfers _transfers;
    private readonly NotifyIcon _tray;
    private readonly Toasts _toasts;
    private readonly ClipboardSync _clip;
    private readonly Icon _icon;
    private readonly Icon _iconOff;

    private readonly ToolStripMenuItem _statusItem = new() { Enabled = false };
    private readonly ToolStripMenuItem _noteItem = new() { Enabled = false, Visible = false };
    private readonly ToolStripMenuItem _batteryItem = new() { Enabled = false, Visible = false };
    private readonly ToolStripMenuItem _sendFiles = new("Send files to phone…");
    private readonly ToolStripMenuItem _ring = new("Ring my phone");
    private readonly ToolStripMenuItem _syncClip = new("Sync clipboard") { CheckOnClick = true };
    private readonly ToolStripMenuItem _showNotifs = new("Show phone notifications") { CheckOnClick = true };
    private readonly ToolStripMenuItem _forget = new("Forget phone…");
    private readonly ToolStripMenuItem _autoStart = new("Start with Windows") { CheckOnClick = true };

    private string _status = "Looking for your phone…";
    private string? _note;
    private System.Windows.Forms.Timer? _noteClear;
    private bool _connected;
    private string _phoneName;
    private int? _battery;
    private bool _charging;
    private bool _ringing;
    private PairDialog? _pairDialog;
    private DateTime _lastMacOnlyHint = DateTime.MinValue;

    private TrayApp(LinkStore store)
    {
        _ui = SynchronizationContext.Current ?? new WindowsFormsSynchronizationContext();
        _store = store;
        _phoneName = store.PhoneName ?? "your phone";
        _icon = LoadIcon();
        _iconOff = Dimmed(_icon);

        _tray = new NotifyIcon { Icon = _iconOff, Visible = true, Text = "PhoneBridge" };
        _tray.ContextMenuStrip = BuildMenu();
        _tray.MouseUp += (_, e) =>
        {
            // Left click opens the same menu (WinForms only does that for right click).
            if (e.Button == MouseButtons.Left)
                typeof(NotifyIcon).GetMethod("ShowContextMenu", BindingFlags.Instance | BindingFlags.NonPublic)?.Invoke(_tray, null);
        };

        _toasts = new Toasts(_tray);
        _toasts.Activated += (args, typed) => _ui.Post(_ => OnToast(args, typed), null);
        _toasts.Dismissed += args => _ui.Post(_ => OnToastDismissed(args), null);

        _clip = new ClipboardSync
        {
            Enabled = store.GetBool("syncClipboard", true),
            SendText = t => _ = _link?.SendAsync("clip", ("text", t)),
            SendImage = png => _ = _link?.SendAsync("clip_image", ("png", Convert.ToBase64String(png))),
        };

        _transfers = new Transfers(Shell.Downloads());
        _transfers.Received += (path, kind) => _ui.Post(_ => Received(path, kind), null);

        _link = new PhoneLink(store, Environment.MachineName);
        _link.StateChanged += (s, name) => _ui.Post(_ => Update(s, name), null);
        _link.MessageReceived += m =>
        {
            // File chunks are written right here, in order, off the UI thread.
            if (PhoneLink.Str(m, "t") is "file_start" or "file_chunk" or "file_end")
            {
                try { _transfers.Handle(m); }
                catch (Exception e) when (e is IOException or UnauthorizedAccessException or FormatException) { Log.Write("receive failed: " + e.Message); }
                return;
            }
            _ui.Post(_ => Handle(m), null);
        };
        _link.StrangerBlocked += name => _ui.Post(_ => _toasts.Info("Blocked a pairing attempt",
            $"“{name}” tried to connect. PhoneBridge only talks to your paired phone — use Forget phone first if you're switching phones."), null);
        _link.Refused += (why, name) => _ui.Post(_ => Refused(why, name), null);
        _link.AskPairing = AskPairing;

        Refresh();
        _link.Start();
        Log.Write($"started {Application.ProductVersion}");
    }

    /// <summary>Opens the saved state; if the secrets can't be read (e.g. copied from another PC), offers a fresh start.</summary>
    public static TrayApp? Create()
    {
        var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "PhoneBridge");
        var store = new LinkStore(dir, new DpapiProtector());
        try
        {
            _ = store.IdentityKey;
        }
        catch (CryptographicException e)
        {
            Log.Write("can't open secrets: " + e.Message);
            var answer = MessageBox.Show(
                "PhoneBridge couldn't read its saved pairing (it may have been copied from another PC or user).\n\n" +
                "Start fresh? You'll pair with your phone again.", "PhoneBridge", MessageBoxButtons.YesNo, MessageBoxIcon.Warning);
            if (answer != DialogResult.Yes) return null;
            var secrets = Path.Combine(dir, "secrets.bin");
            File.Move(secrets, secrets + ".unreadable", overwrite: true);
            store = new LinkStore(dir, new DpapiProtector());
        }
        return new TrayApp(store);
    }

    // MARK: Menu

    private ContextMenuStrip BuildMenu()
    {
        var m = new ContextMenuStrip();
        _sendFiles.Click += (_, _) => PickFiles();
        _ring.Click += (_, _) => _ = _link.SendAsync(_ringing ? "ring_stop" : "ring");
        _syncClip.Checked = _store.GetBool("syncClipboard", true);
        _syncClip.CheckedChanged += (_, _) =>
        {
            _store.SetBool("syncClipboard", _syncClip.Checked);
            _clip.Enabled = _syncClip.Checked;
        };
        _showNotifs.Checked = _store.GetBool("showNotifications", true);
        _showNotifs.CheckedChanged += (_, _) => _store.SetBool("showNotifications", _showNotifs.Checked);
        _forget.Click += (_, _) => Forget();
        try { _autoStart.Checked = AutoStart.Enabled; } catch (Exception e) { Log.Write("autostart read: " + e.Message); }
        _autoStart.CheckedChanged += (_, _) =>
        {
            try { AutoStart.Enabled = _autoStart.Checked; }
            catch (Exception e) { Log.Write("autostart write: " + e.Message); }
        };

        m.Items.AddRange([
            _statusItem, _noteItem, _batteryItem,
            new ToolStripSeparator(),
            _sendFiles, _ring,
            new ToolStripMenuItem("Open received files", null, (_, _) => Shell.Reveal(_transfers.Folder)),
            new ToolStripSeparator(),
            _showNotifs, _syncClip, _autoStart,
            new ToolStripSeparator(),
            new ToolStripMenuItem("Reconnect", null, (_, _) => _link.Reconnect()),
            _forget,
            new ToolStripMenuItem($"About PhoneBridge {Application.ProductVersion.Split('+')[0]}", null, (_, _) => Shell.Open(Repo)),
            new ToolStripMenuItem("Quit PhoneBridge", null, (_, _) => Quit()),
        ]);
        m.Opening += (_, _) => Refresh();
        return m;
    }

    private void Refresh()
    {
        _statusItem.Text = _status;
        _noteItem.Text = _note ?? "";
        _noteItem.Visible = _note != null;
        _batteryItem.Visible = _connected && _battery != null;
        _batteryItem.Text = $"Battery {_battery}%{(_charging ? " · charging" : "")}";
        _sendFiles.Enabled = _connected;
        _ring.Enabled = _connected;
        _ring.Text = _ringing ? "Stop ringing my phone" : "Ring my phone";
        _forget.Enabled = _link?.IsPaired ?? _store.TrustedPhone != null;
        _tray.Icon = _connected ? _icon : _iconOff;
        var tip = "PhoneBridge — " + _status + (_connected && _battery != null ? $" · {_battery}%" : "");
        _tray.Text = tip.Length > 127 ? tip[..127] : tip;
    }

    // MARK: Link state

    private void Update(LinkState state, string name)
    {
        switch (state)
        {
            case LinkState.Searching:
                _connected = false;
                _ringing = false;
                _toasts.Remove("call", "call");
                _transfers.Abort();
                _status = "Looking for your phone…";
                ClosePairDialog();
                break;
            case LinkState.Pairing:
                _connected = false;
                SetNote(null);
                _status = $"Pairing with {name}…";
                break;
            case LinkState.Connected:
                _connected = true;
                SetNote(null);
                _phoneName = name;
                _status = $"Connected to {name}";
                _ = _link.SendAsync("update_hint"); // connected: the phone looks for a new version, as with the Mac
                break;
        }
        Refresh();
    }

    private void Refused(string why, string name)
    {
        switch (why)
        {
            case "busy":
                SetNote($"“{name}” is using another computer. To use it here, open PhoneBridge on the phone → Computers and tap Connect next to this PC.");
                break;
            case "unpaired":
                SetNote($"“{name}” is paired with other computers. To add this PC, open PhoneBridge on the phone → Computers → Pair a new computer.");
                break;
            default:
                return;
        }
        // The phone stopped answering after a while: back to the usual hint.
        _noteClear?.Dispose();
        _noteClear = new System.Windows.Forms.Timer { Interval = 20_000 };
        _noteClear.Tick += (_, _) => { SetNote(null); Refresh(); };
        _noteClear.Start();
        Refresh();
    }

    private void SetNote(string? note)
    {
        _note = note;
        if (note == null)
        {
            _noteClear?.Dispose();
            _noteClear = null;
        }
    }

    private Task<bool> AskPairing(string code, string name, CancellationToken gone)
    {
        var tcs = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        _ui.Post(_ =>
        {
            ClosePairDialog();
            var d = new PairDialog(name, code, _icon);
            _pairDialog = d;
            d.FormClosed += (_, _) =>
            {
                if (_pairDialog == d) _pairDialog = null;
                tcs.TrySetResult(d.DialogResult == DialogResult.OK);
            };
            d.Show();
        }, null);
        // The phone rejected or went away before the user answered: close the dialog.
        gone.Register(() =>
        {
            tcs.TrySetResult(false);
            _ui.Post(_ => ClosePairDialog(), null);
        });
        return tcs.Task;
    }

    private void ClosePairDialog()
    {
        var d = _pairDialog;
        _pairDialog = null;
        if (d != null && !d.IsDisposed) d.Close();
    }

    private void Forget()
    {
        var ok = MessageBox.Show($"Forget {_phoneName}? You'll need to pair again (on the phone: Computers → Pair a new computer).",
            "PhoneBridge", MessageBoxButtons.OKCancel, MessageBoxIcon.Question);
        if (ok != DialogResult.OK) return;
        _link.Forget();
        _phoneName = "your phone";
        Refresh();
    }

    // MARK: Messages from the phone

    private void Handle(JsonObject m)
    {
        switch (PhoneLink.Str(m, "t"))
        {
            case "notif":
                if (_showNotifs.Checked) ShowNotification(m);
                break;
            case "notif_removed":
                if (PhoneLink.Str(m, "id") is { } rid) _toasts.Remove("notif", Toasts.TagFor(rid));
                break;
            case "call":
                Call(m);
                break;
            case "otp":
                if (PhoneLink.Str(m, "code") is { } code)
                {
                    _clip.SetFromPhone(code, secret: true);
                    _toasts.Info($"Code {code} copied", $"From {PhoneLink.Str(m, "from") ?? "SMS"} — just paste it");
                }
                break;
            case "alert":
                _toasts.Info(PhoneLink.Str(m, "title") ?? "PhoneBridge", PhoneLink.Str(m, "body") ?? "");
                break;
            case "clip":
                if (PhoneLink.Str(m, "text") is { } text) _clip.SetFromPhone(text);
                break;
            case "clip_image":
                if (PhoneLink.Str(m, "png") is { } b64)
                {
                    try
                    {
                        _clip.SetImageFromPhone(Convert.FromBase64String(b64));
                        _toasts.Info("Image copied from phone", "Paste it anywhere");
                    }
                    catch (FormatException) { }
                }
                break;
            case "shared_text":
                if (PhoneLink.Str(m, "text") is { } shared)
                {
                    _clip.SetFromPhone(shared);
                    _toasts.Info("Copied from phone", shared);
                }
                break;
            case "battery":
                _battery = m["level"] is JsonValue lv && lv.TryGetValue<int>(out var level) ? level : null;
                _charging = m["charging"] is JsonValue cv && cv.TryGetValue<bool>(out var c) && c;
                Refresh();
                break;
            case "info":
                if (PhoneLink.Str(m, "name") is { } n) { _phoneName = n; _status = $"Connected to {n}"; }
                if (m["addrs"] is JsonArray addrs) _link.SetRemoteHosts(addrs.Select(a => a?.GetValue<string>() ?? ""));
                Refresh();
                break;
            case "ring_state":
                _ringing = m["ringing"] is JsonValue rv && rv.TryGetValue<bool>(out var r) && r;
                Refresh();
                break;
            case "sms_result":
                var ok = m["ok"] is JsonValue okv && okv.TryGetValue<bool>(out var o) && o;
                _toasts.Info(ok ? "SMS sent" : "SMS failed", PhoneLink.Str(m, "error") ?? PhoneLink.Str(m, "to") ?? "");
                break;
            case "mac_cmd":
                // The phone's computer remote (lock, sleep, media, volume…) isn't on Windows yet: say so, at most every 30 s.
                if (DateTime.UtcNow - _lastMacOnlyHint > TimeSpan.FromSeconds(30))
                {
                    _lastMacOnlyHint = DateTime.UtcNow;
                    _ = _link.SendAsync("alert", ("title", "Not on Windows yet"),
                        ("body", "Controlling the computer from the phone works with the Mac app for now."));
                }
                break;
        }
    }

    private void ShowNotification(JsonObject m)
    {
        var id = PhoneLink.Str(m, "id");
        if (id == null) return;
        var app = PhoneLink.Str(m, "app") ?? "";
        var title = PhoneLink.Str(m, "title") ?? "";
        var text = PhoneLink.Str(m, "text") ?? "";
        var buttons = new List<ToastButton>();
        var canReply = m["reply"] is JsonValue rv && rv.TryGetValue<bool>(out var r) && r;
        if (canReply) buttons.Add(new ToastButton("Send", Toasts.Query(new() { ["a"] = "reply", ["id"] = id }), UsesReplyBox: true));
        if (m["actions"] is JsonArray actions)
        {
            for (var i = 0; i < actions.Count && buttons.Count < 5; i++)
            {
                var label = actions[i]?.GetValue<string>();
                if (string.IsNullOrWhiteSpace(label)) continue;
                // The phone's own "Reply" action is covered by the reply box.
                if (canReply && label.Equals("Reply", StringComparison.OrdinalIgnoreCase)) continue;
                buttons.Add(new ToastButton(label, Toasts.Query(new() { ["a"] = "act", ["id"] = id, ["i"] = i.ToString() })));
            }
        }
        _toasts.Show(title.Length > 0 ? title : app, text, "notif", Toasts.TagFor(id),
            attribution: title.Length > 0 && app.Length > 0 ? app : null,
            image: IconFile(PhoneLink.Str(m, "pkg"), PhoneLink.Str(m, "icon")),
            buttons: buttons, replyBox: canReply, launch: new() { ["a"] = "notif", ["id"] = id });
    }

    /// <summary>App icons arrive as base64 PNG; toasts want a file. One per app, refreshed when it changes.</summary>
    private static string? IconFile(string? pkg, string? b64)
    {
        if (pkg == null || b64 == null) return null;
        try
        {
            var dir = Path.Combine(Log.Dir, "icons");
            Directory.CreateDirectory(dir);
            var path = Path.Combine(dir, Transfers.SafeName(pkg) + ".png");
            var png = Convert.FromBase64String(b64);
            if (!File.Exists(path) || new FileInfo(path).Length != png.Length) File.WriteAllBytes(path, png);
            return path;
        }
        catch (Exception e) when (e is IOException or FormatException or UnauthorizedAccessException)
        {
            return null;
        }
    }

    /// <summary>Incoming call (or a call waiting): a ringing toast with Answer / Decline; gone once it's answered or over.</summary>
    private void Call(JsonObject m)
    {
        var state = PhoneLink.Str(m, "state");
        var incoming = m["incoming"] is JsonValue iv && iv.TryGetValue<bool>(out var inc) && inc;
        var waiting = m["waiting"] is JsonValue wv && wv.TryGetValue<bool>(out var w) && w;
        if (state == "ringing" && incoming)
        {
            var who = PhoneLink.Str(m, "name") ?? PhoneLink.Str(m, "number") ?? "Unknown caller";
            var number = PhoneLink.Str(m, "name") != null ? PhoneLink.Str(m, "number") ?? "" : "";
            ShowCall("Incoming call", who, number, waiting: false);
        }
        else if (state == "active" && waiting)
        {
            var who = PhoneLink.Str(m, "waitingName") ?? PhoneLink.Str(m, "waitingNumber") ?? "Unknown caller";
            ShowCall("Call waiting", who, PhoneLink.Str(m, "waitingName") != null ? PhoneLink.Str(m, "waitingNumber") ?? "" : "", waiting: true);
        }
        else
        {
            _toasts.Remove("call", "call");
        }
    }

    private void ShowCall(string heading, string who, string number, bool waiting)
    {
        var w = waiting ? "1" : "0";
        _toasts.Show(who, number.Length > 0 ? $"{heading} · {number}" : heading, "call", "call", attribution: _phoneName,
            buttons:
            [
                new ToastButton("Answer", Toasts.Query(new() { ["a"] = "answer", ["w"] = w })),
                new ToastButton("Decline", Toasts.Query(new() { ["a"] = "decline", ["w"] = w })),
            ],
            call: true);
    }

    private void OnToast(Dictionary<string, string> args, string? typed)
    {
        args.TryGetValue("id", out var id);
        switch (args.GetValueOrDefault("a"))
        {
            case "reply" when id != null && !string.IsNullOrWhiteSpace(typed):
                _ = _link.SendAsync("reply", ("id", id), ("text", typed));
                break;
            case "act" when id != null && int.TryParse(args.GetValueOrDefault("i"), out var i):
                _ = _link.SendAsync("notif_action", ("id", id), ("index", i));
                break;
            case "answer":
                _ = args.GetValueOrDefault("w") == "1" ? _link.SendAsync("call_answer", ("waiting", true)) : _link.SendAsync("call_answer");
                break;
            case "decline":
                _ = args.GetValueOrDefault("w") == "1" ? _link.SendAsync("call_end", ("waiting", true)) : _link.SendAsync("call_end");
                break;
            case "reveal" when args.GetValueOrDefault("path") is { } path:
                Shell.Reveal(path);
                break;
        }
    }

    /// <summary>Swiping a phone notification away here clears it on the phone too (as on the Mac).</summary>
    private void OnToastDismissed(Dictionary<string, string> args)
    {
        if (args.GetValueOrDefault("a") == "notif" && args.GetValueOrDefault("id") is { } id)
            _ = _link.SendAsync("dismiss", ("id", id));
    }

    // MARK: Files

    private void PickFiles()
    {
        using var d = new OpenFileDialog { Multiselect = true, Title = "Send to phone" };
        if (d.ShowDialog() != DialogResult.OK) return;
        _ = SendFiles(d.FileNames);
    }

    private async Task SendFiles(string[] paths)
    {
        if (!_link.IsConnected)
        {
            _toasts.Info("Phone not connected", "Open PhoneBridge on your phone first.");
            return;
        }
        var sent = 0;
        foreach (var p in paths)
        {
            bool ok;
            try { ok = await Transfers.SendAsync(p, _link.SendAsync); }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException) { Log.Write("send failed: " + e.Message); ok = false; }
            if (ok) sent++;
            else
            {
                _toasts.Info("Couldn't send", Path.GetFileName(p));
                break;
            }
        }
        if (sent > 0) _toasts.Info("Sent to phone", sent == 1 ? Path.GetFileName(paths[0]) : $"{sent} files");
    }

    private void Received(string path, string? kind)
    {
        if (kind == "screenshot" || kind == "scan")
        {
            try
            {
                _clip.SetImageFromPhone(File.ReadAllBytes(path));
                _toasts.Info(kind == "scan" ? "Scan received" : "Screenshot from phone",
                    "Copied — paste it anywhere. Saved in Downloads.", new() { ["a"] = "reveal", ["path"] = path });
                return;
            }
            catch (Exception e) when (e is IOException or ArgumentException) { }
        }
        _toasts.Info("Received from phone", Path.GetFileName(path), new() { ["a"] = "reveal", ["path"] = path });
    }

    // MARK: Lifetime

    private void Quit()
    {
        _link.Dispose();
        _tray.Visible = false;
        _clip.Dispose();
        ExitThread();
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _tray.Dispose();
            _noteClear?.Dispose();
        }
        base.Dispose(disposing);
    }

    private static Icon LoadIcon()
    {
        using var s = typeof(TrayApp).Assembly.GetManifestResourceStream("PhoneBridge.ico");
        return s != null ? new Icon(s, SystemInformation.SmallIconSize) : SystemIcons.Application;
    }

    /// <summary>Greyed-out copy of the icon for "not connected".</summary>
    private static Icon Dimmed(Icon icon)
    {
        try
        {
            using var bmp = icon.ToBitmap();
            using var grey = ToolStripRenderer.CreateDisabledImage(bmp);
            using var b = new Bitmap(grey);
            return Icon.FromHandle(b.GetHicon());
        }
        catch (Exception)
        {
            return icon;
        }
    }
}
