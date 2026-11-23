using System.Reflection;
using System.Security.Cryptography;
using System.Text.Json.Nodes;
using PhoneBridge.Control;
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
    private readonly PcMedia _media = new();
    private readonly PcControl _control;
    private readonly InputControl _input = new();
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
    /// <summary>The window (Mac-style design); null when the WebView2 runtime is missing, then the menu does everything.</summary>
    private readonly MainWindow? _window;
    /// <summary>A pairing request shown in the window: (phone, code, the answer).</summary>
    private (string Name, string Code, TaskCompletionSource<bool> Answer)? _pairing;

    private TrayApp(LinkStore store)
    {
        _ui = SynchronizationContext.Current ?? new WindowsFormsSynchronizationContext();
        _store = store;
        _phoneName = store.PhoneName ?? "your phone";
        _icon = LoadIcon();
        _iconOff = Dimmed(_icon);

        if (MainWindow.RuntimeAvailable)
        {
            _window = new MainWindow(_icon);
            _window.Command += (cmd, m) => OnWindowCommand(cmd, m);
        }
        else
        {
            Log.Write("WebView2 runtime not found: menu-only mode");
        }

        _tray = new NotifyIcon { Icon = _iconOff, Visible = true, Text = "PhoneBridge" };
        _tray.ContextMenuStrip = BuildMenu();
        _tray.MouseUp += (_, e) =>
        {
            if (e.Button != MouseButtons.Left) return;
            // Left click opens the window, like clicking PhoneBridge in the Dock; without it, the menu.
            if (_window != null) _window.Toggle();
            else typeof(NotifyIcon).GetMethod("ShowContextMenu", BindingFlags.Instance | BindingFlags.NonPublic)?.Invoke(_tray, null);
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

        // The phone's computer remote: commands, trackpad/keyboard, what's playing here, the player screen.
        _media.Send = m => _ = _link.SendAsync(m);
        _control = new PcControl(_media)
        {
            Send = m => _ = _link.SendAsync(m),
            Notify = (t, b) => _ui.Post(_ => _toasts.Info(t, b), null),
            OnCall = on => { if (on) _media.PauseForCall(); else _media.ResumeAfterCall(); },
            Deck = (cmd, m) =>
            {
                switch (cmd)
                {
                    case "deck_on": _media.DeckOn(); break;
                    case "deck_off": _media.DeckOff(); break;
                    default: _ = _media.DeckDo(PhoneLink.Str(m, "action"), m["value"]); break;
                }
            },
        };
        _control.Start();
        _ = _media.StartAsync();

        Refresh();
        _link.Start();
        // Not paired yet: open the window so the first-run "Looking for nearby phones" screen is right there.
        if (_window != null && store.TrustedPhone == null) _window.ShowAndActivate();
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

        if (_window != null)
        {
            // Like the Mac's menu-bar menu: status, then the quick actions; settings live in the window.
            var open = new ToolStripMenuItem("Open PhoneBridge", null, (_, _) => _window.ShowAndActivate());
            open.Font = new Font(open.Font, FontStyle.Bold);
            m.Items.AddRange([
                _statusItem, _noteItem, _batteryItem,
                new ToolStripSeparator(),
                open,
                new ToolStripSeparator(),
                _ring, _sendFiles,
                new ToolStripMenuItem("Open received files", null, (_, _) => Shell.Reveal(_transfers.Folder)),
                new ToolStripSeparator(),
                new ToolStripMenuItem("Quit PhoneBridge", null, (_, _) => Quit()),
            ]);
        }
        else
        {
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
                new ToolStripMenuItem($"About PhoneBridge {Version}", null, (_, _) => Shell.Open(Repo)),
                new ToolStripMenuItem("Quit PhoneBridge", null, (_, _) => Quit()),
            ]);
        }
        DarkMenuRenderer.Apply(m, checks: _window == null);
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
        _ring.Text = _ringing ? "Stop ringing" : "Ring phone";
        _forget.Enabled = _link?.IsPaired ?? _store.TrustedPhone != null;
        _tray.Icon = _connected ? _icon : _iconOff;
        PushState();
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
                _input.ReleaseModifiers(); // a phone that dropped mid-hold mustn't leave Alt/Ctrl stuck
                _media.DeckOff();
                _status = "Looking for your phone…";
                ClosePairDialog();
                if (_pairing is { } p) { _pairing = null; p.Answer.TrySetResult(false); }
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
                _control.SendState(force: true);
                _media.Resend();
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
        if (_window != null)
        {
            // In the window: "Found <phone>", the code, Cancel / Pair — the Mac's discovery screen plus its pairing alert.
            _ui.Post(_ =>
            {
                _pairing?.Answer.TrySetResult(false);
                _pairing = (name, code, tcs);
                Refresh();
                _window.ShowAndActivate();
            }, null);
            gone.Register(() =>
            {
                tcs.TrySetResult(false);
                _ui.Post(_ => EndPairing(tcs), null);
            });
            return tcs.Task;
        }
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

    private void EndPairing(TaskCompletionSource<bool> which)
    {
        if (_pairing?.Answer != which) return;
        _pairing = null;
        Refresh();
    }

    private void AnswerPairing(bool ok)
    {
        if (_pairing is not { } p) return;
        _pairing = null;
        p.Answer.TrySetResult(ok);
        Refresh();
    }

    private void ClosePairDialog()
    {
        var d = _pairDialog;
        _pairDialog = null;
        if (d != null && !d.IsDisposed) d.Close();
    }

    private void Forget(bool confirmed = false)
    {
        if (confirmed)
        {
            _link.Forget();
            _phoneName = "your phone";
            Refresh();
            return;
        }
        var ok = MessageBox.Show($"Forget {_phoneName}? You'll need to pair again (on the phone: Computers → Pair a new computer).",
            "PhoneBridge", MessageBoxButtons.OKCancel, MessageBoxIcon.Question);
        if (ok != DialogResult.OK) return;
        _link.Forget();
        _phoneName = "your phone";
        Refresh();
    }

    // MARK: The window

    private static string Version => Application.ProductVersion.Split('+')[0];

    private void PushState()
    {
        if (_window == null) return;
        _window.PushState(new JsonObject
        {
            ["view"] = _connected ? "connected" : "searching",
            ["status"] = _status,
            ["note"] = _note,
            ["phoneName"] = _connected || _store.TrustedPhone != null ? _phoneName : "",
            ["battery"] = _connected ? _battery : null,
            ["charging"] = _charging,
            ["ringing"] = _ringing,
            ["paired"] = _link?.IsPaired ?? _store.TrustedPhone != null,
            ["pairing"] = _pairing is { } p ? new JsonObject { ["name"] = p.Name, ["code"] = p.Code } : null,
            ["settings"] = new JsonObject
            {
                ["notifications"] = _showNotifs.Checked,
                ["clipboard"] = _syncClip.Checked,
                ["autostart"] = _autoStart.Checked,
            },
            ["version"] = Version,
        });
    }

    private void OnWindowCommand(string cmd, JsonObject m)
    {
        switch (cmd)
        {
            case "ready": PushState(); break;
            case "pair-ok": AnswerPairing(true); break;
            case "pair-cancel": AnswerPairing(false); break;
            case "ring": _ = _link.SendAsync(_ringing ? "ring_stop" : "ring"); break;
            case "send-files": PickFiles(); break;
            case "send-clipboard": SendClipboard(); break;
            case "send-tab": _control.SendTabToPhone(); break;
            case "open-downloads": Shell.Reveal(_transfers.Folder); break;
            case "reconnect": _link.Reconnect(); break;
            case "forget": Forget(confirmed: true); break;
            case "about": Shell.Open(Repo); break;
            case "open-log": Shell.Reveal(Path.Combine(Log.Dir, "phonebridge.log")); break;
            case "quit": Quit(); break;
            case "drop":
                if (m["paths"] is JsonArray paths)
                {
                    var files = paths.Select(p => p?.GetValue<string>()).OfType<string>().Where(File.Exists).ToArray();
                    if (files.Length > 0) _ = SendFiles(files);
                }
                break;
            case "set":
                var on = m["value"] is JsonValue v && v.TryGetValue<bool>(out var b) && b;
                switch (PhoneLink.Str(m, "key"))
                {
                    case "notifications": _showNotifs.Checked = on; break;
                    case "clipboard": _syncClip.Checked = on; break;
                    case "autostart": _autoStart.Checked = on; break;
                }
                Refresh();
                break;
        }
    }

    /// <summary>"Send clipboard": pushes what's on the clipboard now, even with clipboard sync off.</summary>
    private void SendClipboard()
    {
        try
        {
            if (Clipboard.ContainsText())
            {
                _ = _link.SendAsync("clip", ("text", Clipboard.GetText()));
                _toasts.Info("Clipboard sent", "Paste it on your phone");
            }
            else if (Clipboard.ContainsImage() && Clipboard.GetImage() is { } img)
            {
                using (img)
                using (var ms = new MemoryStream())
                {
                    img.Save(ms, System.Drawing.Imaging.ImageFormat.Png);
                    _ = _link.SendAsync("clip_image", ("png", Convert.ToBase64String(ms.ToArray())));
                }
                _toasts.Info("Image sent", "Paste it on your phone");
            }
            else
            {
                _toasts.Info("Nothing to send", "Copy some text or an image first");
            }
        }
        catch (System.Runtime.InteropServices.ExternalException) { }
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
                _control.Handle(m);
                break;
            case "input":
                _input.Handle(m);
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
        _control.Stop();
        _input.ReleaseModifiers();
        _tray.Visible = false;
        _window?.Dispose();
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
