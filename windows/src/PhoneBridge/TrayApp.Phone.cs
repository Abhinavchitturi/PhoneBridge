using System.Text.Json.Nodes;
using PhoneBridge.Core;

namespace PhoneBridge;

/// <summary>
/// The phone window and the features around it: notification history and muting, clipboard history, the shared notepad,
/// phone battery estimates, quick replies, read aloud, Focus → phone Do Not Disturb, themes, full-size photos,
/// the chat reply pop-up and app updates. Ports of PhoneStore and the Bridge wiring in mac/Sources/App.swift.
/// </summary>
internal sealed partial class TrayApp
{
    private static readonly string PhotoCache = Path.Combine(Log.Dir, "Photos");
    private static readonly string[] DefaultQuickReplies =
        ["Can't talk now — I'll call you back", "On my way", "OK 👍", "Thanks!", "I'll get back to you soon"];

    /// <summary>Messages the phone window's page may send to the phone ({cmd:"tx"}); anything else is refused.</summary>
    private static readonly HashSet<string> PageMayTx =
    [
        "sms_threads_request", "sms_thread_request", "sms_send", "albums_request", "photos_request", "photo_get", "photos_delete",
        "call_log_request", "contacts_request", "device_request", "biggest_request", "loc_request", "torch", "ringer", "phone_dnd",
        "autoreply_request", "autoreply_set", "apps_request", "dismiss", "reply", "notif_action", "ring", "ring_stop",
    ];

    /// <summary>Phone messages the phone window shows.</summary>
    private static readonly HashSet<string> ForPhoneWindow =
    [
        "sms_threads", "sms_thread", "sms_new", "sms_state", "albums", "photos", "photos_deleted", "call_log", "contacts",
        "autoreply", "device", "torch_state", "biggest", "loc", "apps",
    ];

    private NotificationHistory _history = null!;
    private readonly NotifRules _rules = new();
    private readonly ClipboardHistory _clipHistory = new();
    private SharedNote _sharedNote = null!;
    private readonly BatteryWatch _batteryWatch = new();
    private readonly FocusWatch _focus = new();
    private List<string> _quickReplies = [];
    private string _theme = "midnight";
    private bool _dnd;
    private PhoneWindow? _phoneWin;
    private ChatPopup? _chat;
    private Updater? _updater;
    private Releases.Release? _update;
    private System.Windows.Forms.Timer? _phoneStateDebounce;
    /// <summary>Photos asked for with "Download": saved to Downloads once they arrive.</summary>
    private readonly HashSet<long> _saveWhenDownloaded = [];
    private readonly List<string> _savedBatch = [];
    private readonly Dictionary<long, string> _photoFiles = [];

    private void InitPhone()
    {
        Directory.CreateDirectory(PhotoCache);
        foreach (var f in Directory.EnumerateFiles(PhotoCache)) { try { File.Delete(f); } catch (IOException) { } } // full-size copies only live while you look at them
        _history = new NotificationHistory(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "PhoneBridge", "history.bin"), new DpapiProtector());
        _history.Changed += SchedulePhoneState;
        _rules.MutedApps = [.. _store.GetList("mutedApps", [])];
        _rules.Keywords = _store.GetList("mutedKeywords", []);
        _rules.ReplyPopup = _store.GetBool("replyPopup", true);
        _rules.SmartReplies = _store.GetBool("smartReplies", true);
        _quickReplies = _store.GetList("quickReplies", DefaultQuickReplies);
        _sharedNote = new SharedNote(_store.GetString("noteText", "") ?? "", _store.Get("noteTs") is JsonValue tv && tv.TryGetValue<long>(out var ts) ? ts : 0);
        _batteryWatch.Alerts = _store.GetBool("batteryAlerts", true);
        _batteryWatch.Notify = (t, b) => _toasts.Info(t, b);
        _focus.SetPhoneDnd = on => _ = _link.SendAsync("phone_dnd", ("on", on));
        _focus.Enabled = _store.GetBool("focusDnd", false);
        _theme = _store.GetString("theme", "midnight") ?? "midnight";
        _clip.OnText = (text, fromPhone) => { _clipHistory.Add(text, fromPhone, DateTimeOffset.Now); SchedulePhoneState(); };

        if (_window != null)
        {
            _chat = new ChatPopup(
                reply: (id, text) => _ = _link.SendAsync("reply", ("id", id), ("text", text)),
                dismiss: id => { _ = _link.SendAsync("dismiss", ("id", id)); _history.MarkRemoved(id); })
            { Theme = () => _theme };
        }

        _updater = new Updater(Version)
        {
            Found = r => _ui.Post(_ => UpdateFound(r), null),
            Notify = (t, b) => _ui.Post(_ => _toasts.Info(t, b), null),
            BeforeRestart = () => _ui.Send(_ => Quit(), null),
        };
        _updater.Start();
        PresenceInit();
        ExtrasInit();
    }

    private PhoneWindow? EnsurePhoneWindow()
    {
        if (_window == null) return null;
        if (_phoneWin == null || _phoneWin.IsDisposed)
        {
            _phoneWin = new PhoneWindow(_icon, PhotoCache, OnPhoneCommand);
            PushPhoneState();
        }
        return _phoneWin;
    }

    // MARK: Messages from the phone

    /// <summary>Feature messages; true when fully handled here.</summary>
    private bool OnPhoneMessage(JsonObject m)
    {
        var t = PhoneLink.Str(m, "t");
        if (t != null && ForPhoneWindow.Contains(t))
        {
            _phoneWin?.Push("phone", new JsonObject { ["m"] = m.DeepClone() });
            return true;
        }
        switch (t)
        {
            case "phone_dnd":
                _dnd = m["on"] is JsonValue v && v.TryGetValue<bool>(out var on) && on;
                SchedulePhoneState();
                return true;
            case "theme":
                // The phone picked a theme: switch to it here too (without telling the phone back).
                if (PhoneLink.Str(m, "id") is { } id && id != _theme) SetTheme(id, fromPhone: true);
                return true;
            case "note":
                if (_sharedNote.Receive(m)) { SaveNote(); SchedulePhoneState(); }
                return true;
            case "mic_state":
                MicState(m["on"] is JsonValue mv && mv.TryGetValue<bool>(out var mon) && mon);
                return true;
            case "mic_audio":
                if (PhoneLink.Str(m, "pcm") is { } pcm) MicAudio(pcm);
                return true;
            case "cast_state": case "cast_diag":
                CastMessage(m);
                return true;
            case "debug_state":
                StreamDebugState(m);
                return true;
        }
        return false;
    }

    /// <summary>A phone notification: into History; a toast or the chat pop-up unless muted; read aloud if asked.</summary>
    private void NotificationArrived(JsonObject m)
    {
        var muted = _rules.IsMuted(m) || !_showNotifs.Checked;
        var canReply = m["reply"] is JsonValue rv && rv.TryGetValue<bool>(out var r) && r;
        var app = PhoneLink.Str(m, "app") ?? "";
        var sender = PhoneLink.Str(m, "title") ?? "";
        var latest = PhoneLink.Str(m, "text") ?? "";
        var earlier = _history.Earlier(app, sender);
        if (!muted)
        {
            if (_rules.ReplyPopup && canReply && _chat != null && PhoneLink.Str(m, "id") is { } id)
            {
                _chat.Show(m);
                if (_rules.SmartReplies && latest.Length > 0)
                    _ = Task.Run(async () =>
                    {
                        var s = await SmartReply.SuggestAsync(sender, latest, earlier);
                        if (s.Count > 0) _ui.Post(_ => _chat.SetSuggestions(id, s), null);
                    });
            }
            else ShowNotification(m);
        }
        _history.Add(m, DateTimeOffset.Now);
        if (!muted && _store.GetBool("readAloud", false) && canReply)
            Speech.Speak(string.Join(". ", new[] { sender, latest }.Where(x => x.Length > 0)));
    }

    // MARK: State for the pages

    private void SchedulePhoneState()
    {
        if (_phoneStateDebounce == null)
        {
            _phoneStateDebounce = new System.Windows.Forms.Timer { Interval = 120 };
            _phoneStateDebounce.Tick += (_, _) => { _phoneStateDebounce.Stop(); PushPhoneState(); };
        }
        _phoneStateDebounce.Stop();
        _phoneStateDebounce.Start();
    }

    private void PushPhoneState()
    {
        if (_phoneWin == null || _history == null) return;
        var items = _history.Items;
        _phoneWin.Push("state", new JsonObject
        {
            ["state"] = new JsonObject
            {
                ["connected"] = _connected, ["phoneName"] = _phoneName, ["battery"] = _connected ? _battery : null,
                ["charging"] = _charging, ["eta"] = _batteryWatch.Eta, ["ringing"] = _ringing, ["dnd"] = _dnd, ["theme"] = _theme,
                ["history"] = new JsonArray(items.Select(i => (JsonNode)new JsonObject
                {
                    ["key"] = i.Key, ["active"] = i.Active, ["canReply"] = i.CanReply, ["app"] = i.App, ["title"] = i.Title,
                    ["text"] = i.Text, ["date"] = i.Date.ToUnixTimeMilliseconds(),
                }).ToArray()),
                ["clips"] = new JsonArray(_clipHistory.Items.Select(c => (JsonNode)new JsonObject
                {
                    ["id"] = c.Id.ToString(), ["text"] = c.Text, ["date"] = c.Date.ToUnixTimeMilliseconds(), ["fromPhone"] = c.FromPhone,
                }).ToArray()),
                ["note"] = _sharedNote.Text,
                ["quickReplies"] = new JsonArray(_quickReplies.Select(q => (JsonNode)q).ToArray()),
                ["rules"] = new JsonObject
                {
                    ["mutedApps"] = new JsonArray(_rules.MutedApps.Select(a => (JsonNode)a).ToArray()),
                    ["keywords"] = new JsonArray(_rules.Keywords.Select(k => (JsonNode)k).ToArray()),
                    ["replyPopup"] = _rules.ReplyPopup, ["smartReplies"] = _rules.SmartReplies,
                },
                ["settings"] = new JsonObject
                {
                    ["batteryAlerts"] = _batteryWatch.Alerts, ["focusDnd"] = _focus.Enabled, ["readAloud"] = _store.GetBool("readAloud", false),
                    ["presence"] = _store.GetBool("presence", false), ["presenceRange"] = _store.Get("presenceRange")?.GetValue<int>() ?? -75,
                    ["screenshots"] = _store.GetBool("screenshots", true), ["autostart"] = _autoStart.Checked,
                },
                ["smartReplyStatus"] = SmartReply.Status,
                ["streaming"] = StreamStatus, ["streamingNote"] = StreamNote,
                ["micStatus"] = _micStatus, ["micActive"] = _micActive, ["presence"] = PresenceStatus,
                ["version"] = Version,
            },
        });
    }

    // MARK: Commands from the pages (the phone window, and shared ones from the main window)

    private void OnPhoneCommand(string cmd, JsonObject m)
    {
        string? S(string k) => PhoneLink.Str(m, k);
        switch (cmd)
        {
            case "ready": PushPhoneState(); break;
            case "tx":
                if (m["m"] is JsonObject msg && PhoneLink.Str(msg, "t") is { } t && PageMayTx.Contains(t))
                {
                    _ = _link.SendAsync((JsonObject)msg.DeepClone());
                    if (t == "phone_dnd" && msg["on"] is JsonValue dv && dv.TryGetValue<bool>(out var dnd)) { _dnd = dnd; SchedulePhoneState(); }
                }
                break;
            case "dial": if (S("number") is { } n) Dial(S("name"), n); break;
            case "ring": _ = _link.SendAsync(_ringing ? "ring_stop" : "ring"); break;
            case "send-files": PickFiles(); break;
            case "reconnect": _link.Reconnect(); break;
            case "mirror": Mirror(null); break;
            case "open-app": if (S("pkg") is { } pkg) OpenApp(pkg, S("name") ?? pkg); break;
            case "stream-pair": StreamPair(S("hostPort") ?? "", S("code") ?? ""); break;
            case "stream-setup": StreamSetup(); break;
            case "open-log": Shell.Reveal(Path.Combine(Log.Dir, "phonebridge.log")); break;
            case "quit": Quit(); break;
            case "forget": Forget(confirmed: true); break;
            case "phone": OpenPhone(S("tab") ?? "messages"); break;
            case "phone-settings": OpenPhone("settings"); break;
            case "sms": OpenPhone("messages", S("number") ?? ""); break;
            case "type-text": TypeOnPhone(S("text") ?? ""); break;
            case "type-enter": _ = _link.SendAsync("phone_enter"); break;
            case "drop":
                if (m["paths"] is JsonArray paths)
                {
                    var files = paths.Select(p => p?.GetValue<string>()).OfType<string>().Where(File.Exists).ToArray();
                    if (files.Length > 0) _ = SendFiles(files);
                }
                break;
            case "set": SetSetting(S("key") ?? "", m["value"]); break;
            case "rules":
                switch (S("op"))
                {
                    case "mute": if (S("value") is { } app) _rules.Toggle(app); break;
                    case "keyword": if (S("value") is { } k) _rules.AddKeyword(k); break;
                    case "unkeyword": _rules.Keywords.Remove(S("value") ?? ""); break;
                    case "replyPopup": _rules.ReplyPopup = m["value"]?.GetValue<bool>() ?? true; break;
                    case "smartReplies": _rules.SmartReplies = m["value"]?.GetValue<bool>() ?? true; break;
                }
                _store.SetList("mutedApps", _rules.MutedApps);
                _store.SetList("mutedKeywords", _rules.Keywords);
                _store.SetBool("replyPopup", _rules.ReplyPopup);
                _store.SetBool("smartReplies", _rules.SmartReplies);
                PushPhoneState();
                break;
            case "quick":
                var v = (S("value") ?? "").Trim();
                if (S("op") == "add" && v.Length > 0 && !_quickReplies.Contains(v)) _quickReplies.Add(v);
                if (S("op") == "remove") _quickReplies.Remove(v);
                _store.SetList("quickReplies", _quickReplies);
                PushPhoneState();
                break;
            case "history-clear": _history.Clear(); break;
            case "history-dismiss":
                if (S("key") is { Length: > 0 } key) { _ = _link.SendAsync("dismiss", ("id", key)); _history.MarkRemoved(key); }
                break;
            case "feed-clear":
                foreach (var i in _history.Feed.Where(i => i.Key != null)) { _ = _link.SendAsync("dismiss", ("id", i.Key)); _history.MarkRemoved(i.Key!); }
                break;
            case "clip-copy":
                if (Guid.TryParse(S("id"), out var cid) && _clipHistory.Items.FirstOrDefault(c => c.Id == cid) is { } item)
                    CopyText(item.Text); // ClipboardSync then carries it to the phone too
                break;
            case "clip-remove": if (Guid.TryParse(S("id"), out var rid)) { _clipHistory.Remove(rid); PushPhoneState(); } break;
            case "clip-clear": _clipHistory.Clear(); PushPhoneState(); break;
            case "copy-text": CopyText(S("text") ?? ""); break;
            case "note":
                _sharedNote.Edit(S("text") ?? "", DateTimeOffset.Now);
                SaveNote();
                _ = _link.SendAsync(new JsonObject { ["t"] = "note", ["text"] = _sharedNote.Text, ["ts"] = _sharedNote.Ts });
                break;
            case "speak": if (S("text") is { } st) Speech.Speak(st); break;
            case "theme": if (S("id") is { } th) SetTheme(th, fromPhone: false); break;
            case "photos-save": SavePhotos(Ids(m)); break;
            case "photo-copy": CopyPhoto(Id(m)); break;
            case "photo-open": if (_photoFiles.TryGetValue(Id(m), out var of)) Shell.Open(of); break;
            case "photo-ocr": _ = CopyPhotoText(Id(m)); break;
            case "update-install": _updater?.Install(); break;
            case "update-check": _updater?.Check(manual: true); break;
        }
    }

    private static long Id(JsonObject m) => m["id"] is JsonValue v && v.TryGetValue<long>(out var id) ? id : -1;
    private static List<long> Ids(JsonObject m) => m["ids"] is JsonArray a ? a.Select(x => x?.GetValue<long>() ?? -1).Where(x => x >= 0).ToList() : [];

    private void SetSetting(string key, JsonNode? value)
    {
        var on = value is JsonValue v && v.TryGetValue<bool>(out var b) && b;
        switch (key)
        {
            case "batteryAlerts": _batteryWatch.Alerts = on; _store.SetBool(key, on); break;
            case "focusDnd": _focus.Enabled = on; _store.SetBool(key, on); break;
            case "readAloud": case "screenshots": _store.SetBool(key, on); ExtrasChanged(); break;
            case "presence": _store.SetBool(key, on); PresenceChanged(); break;
            case "presenceRange": if (value is JsonValue rv && rv.TryGetValue<int>(out var range)) { _store.Set(key, range); PresenceChanged(); } break;
            case "autostart": _autoStart.Checked = on; break;
            case "notifications": _showNotifs.Checked = on; break;
            case "clipboard": _syncClip.Checked = on; break;
        }
        Refresh();
    }

    private void SaveNote()
    {
        _store.Set("noteText", _sharedNote.Text);
        _store.Set("noteTs", _sharedNote.Ts);
    }

    private void SetTheme(string id, bool fromPhone)
    {
        _theme = id;
        _store.Set("theme", id);
        if (!fromPhone) _ = _link.SendAsync("theme", ("id", id)); // the phone follows
        Refresh();
    }

    private void CopyText(string text)
    {
        if (text.Length == 0) return;
        try { Clipboard.SetText(text); }
        catch (System.Runtime.InteropServices.ExternalException) { }
    }

    // MARK: Photos

    private void PhotoProgress(string? kind, long got, long total)
    {
        if (kind == null || !kind.StartsWith("photo:") || !long.TryParse(kind[6..], out var id)) return;
        _phoneWin?.Push("progress", new JsonObject { ["id"] = id, ["got"] = got, ["total"] = total });
    }

    private void PhotoReceived(string kind, string path)
    {
        if (!long.TryParse(kind[6..], out var id)) return;
        _photoFiles[id] = path;
        _phoneWin?.Push("photo", new JsonObject { ["id"] = id, ["url"] = "https://photos.phonebridge/" + Uri.EscapeDataString(Path.GetFileName(path)) });
        if (_saveWhenDownloaded.Remove(id))
        {
            SaveToDownloads(path);
            RevealSavedIfDone();
        }
    }

    /// <summary>Saves full-size copies to Downloads (fetching any that aren't here yet).</summary>
    private void SavePhotos(List<long> ids)
    {
        _savedBatch.Clear();
        foreach (var id in ids)
        {
            if (_photoFiles.TryGetValue(id, out var f) && File.Exists(f)) SaveToDownloads(f);
            else { _saveWhenDownloaded.Add(id); _ = _link.SendAsync("photo_get", ("id", id)); }
        }
        RevealSavedIfDone();
    }

    private void SaveToDownloads(string cached)
    {
        try
        {
            var dest = Transfers.UniquePath(Shell.Downloads(), Path.GetFileName(cached));
            File.Copy(cached, dest);
            _savedBatch.Add(dest);
        }
        catch (IOException e) { Log.Write("save photo: " + e.Message); }
    }

    private void RevealSavedIfDone()
    {
        if (_saveWhenDownloaded.Count > 0 || _savedBatch.Count == 0) return;
        Shell.Reveal(_savedBatch[0]);
        _toasts.Info("Saved to Downloads", $"{_savedBatch.Count} item{(_savedBatch.Count == 1 ? "" : "s")}");
        _savedBatch.Clear();
    }

    private void CopyPhoto(long id)
    {
        if (!_photoFiles.TryGetValue(id, out var path) || !File.Exists(path)) return;
        try
        {
            if (Path.GetExtension(path).ToLowerInvariant() is ".mp4" or ".mov" or ".3gp" or ".mkv" or ".webm")
            {
                Clipboard.SetFileDropList([path]);
                _phoneWin?.Push("toast", new JsonObject { ["text"] = "Video copied — paste it in File Explorer" });
            }
            else
            {
                using var img = Image.FromFile(path);
                Clipboard.SetImage(img);
                _phoneWin?.Push("toast", new JsonObject { ["text"] = "Photo copied — paste it anywhere" });
            }
        }
        catch (Exception e) when (e is IOException or OutOfMemoryException or System.Runtime.InteropServices.ExternalException) { Log.Write("copy photo: " + e.Message); }
    }

    private async Task CopyPhotoText(long id)
    {
        if (!_photoFiles.TryGetValue(id, out var path)) return;
        string text;
        try { text = await Ocr.RecognizeAsync(path); }
        catch (Exception e) { Log.Write("ocr: " + e.Message); text = ""; }
        _ui.Post(_ =>
        {
            if (text.Trim().Length == 0) { _phoneWin?.Push("toast", new JsonObject { ["text"] = "No text found in this photo" }); return; }
            CopyText(text);
            var lines = text.Split('\n').Length;
            _phoneWin?.Push("toast", new JsonObject { ["text"] = $"Text copied — {lines} line{(lines == 1 ? "" : "s")} from the photo" });
        }, null);
    }

    // MARK: Updates

    private void UpdateFound(Releases.Release r)
    {
        _update = r;
        Refresh();
        _toasts.Show("A new version of PhoneBridge is available", $"PhoneBridge {r.Version} is out (you have {Version}). Update now? PhoneBridge restarts by itself in a few seconds.",
            "info", "update", buttons: [new ToastButton("Update now", Toasts.Query(new() { ["a"] = "update" })), new ToastButton("Later", Toasts.Query(new() { ["a"] = "later" }))]);
    }
}
