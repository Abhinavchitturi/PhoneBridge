using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.Json.Serialization;

namespace PhoneBridge.Core;

/// <summary>
/// Every phone notification, kept on this computer (encrypted at rest with the store's protector), newest first.
/// Port of NotificationHistory in mac/Sources/Notifier.swift. Items still showing on the phone make up the live feed.
/// </summary>
public sealed class NotificationHistory
{
    public sealed class Item
    {
        public Guid Id { get; set; } = Guid.NewGuid();
        /// <summary>The phone's notification key (for reply / dismiss).</summary>
        public string? Key { get; set; }
        public bool CanReply { get; set; }
        /// <summary>Still showing on the phone → part of the live feed.</summary>
        public bool Active { get; set; }
        public string App { get; set; } = "";
        public string Title { get; set; } = "";
        public string Text { get; set; } = "";
        public DateTimeOffset Date { get; set; }
    }

    private const int Max = 500;
    private readonly string _file;
    private readonly ISecretProtector _protector;
    private readonly object _lock = new();
    private List<Item> _items = [];

    public event Action? Changed;

    public NotificationHistory(string file, ISecretProtector protector)
    {
        _file = file;
        _protector = protector;
        try
        {
            if (File.Exists(file))
            {
                var json = protector.Unprotect(File.ReadAllBytes(file));
                // After a restart we don't know what's still on the phone; the feed refills as notifications arrive.
                _items = (JsonSerializer.Deserialize<List<Item>>(json) ?? []).Select(i => { i.Active = false; return i; }).ToList();
            }
        }
        catch (Exception e) when (e is JsonException or IOException or System.Security.Cryptography.CryptographicException)
        {
            _items = [];
        }
    }

    public List<Item> Items { get { lock (_lock) return [.. _items]; } }
    public List<Item> Feed { get { lock (_lock) return _items.Where(i => i.Active).ToList(); } }

    public void Add(JsonObject m, DateTimeOffset now)
    {
        var key = PhoneLink.Str(m, "id");
        var item = new Item
        {
            Key = key, CanReply = m["reply"] is JsonValue v && v.TryGetValue<bool>(out var r) && r, Active = true,
            App = PhoneLink.Str(m, "app") ?? "", Title = PhoneLink.Str(m, "title") ?? "", Text = PhoneLink.Str(m, "text") ?? "", Date = now,
        };
        lock (_lock)
        {
            // Apps update one notification as a chat grows; keep only its newest version.
            if (key != null) _items.RemoveAll(i => i.Key == key);
            _items.Insert(0, item);
            if (_items.Count > Max) _items.RemoveRange(Max, _items.Count - Max);
        }
        Save();
    }

    public void MarkRemoved(string key)
    {
        lock (_lock) foreach (var i in _items.Where(i => i.Key == key)) i.Active = false;
        Changed?.Invoke();
    }

    public void Clear()
    {
        lock (_lock) _items.Clear();
        Save();
    }

    /// <summary>Earlier lines from the same chat, oldest first — context for suggested replies.</summary>
    public List<string> Earlier(string app, string sender, int count = 5)
    {
        lock (_lock)
            return _items.Where(i => i.App == app && i.Title == sender).Take(count).Reverse().Select(i => $"{sender}: {i.Text}").ToList();
    }

    private void Save()
    {
        byte[] json;
        lock (_lock) json = JsonSerializer.SerializeToUtf8Bytes(_items);
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(_file)!);
            var tmp = _file + ".tmp";
            File.WriteAllBytes(tmp, _protector.Protect(json));
            File.Move(tmp, _file, overwrite: true);
        }
        catch (IOException) { }
        Changed?.Invoke();
    }
}

/// <summary>Computer-side mute, per app and per keyword. Muted notifications still land in History; they just don't pop up.</summary>
public sealed class NotifRules
{
    public HashSet<string> MutedApps { get; set; } = [];
    public List<string> Keywords { get; set; } = [];
    /// <summary>A corner pop-up with a reply box for chat messages, instead of the system notification.</summary>
    public bool ReplyPopup { get; set; } = true;
    /// <summary>Offer three suggested replies on chat notifications (written on this computer by a local model).</summary>
    public bool SmartReplies { get; set; } = true;

    public bool IsMuted(JsonObject m)
    {
        if (PhoneLink.Str(m, "app") is { } app && MutedApps.Contains(app)) return true;
        var text = ((PhoneLink.Str(m, "title") ?? "") + " " + (PhoneLink.Str(m, "text") ?? "")).ToLowerInvariant();
        return Keywords.Any(k => k.Length > 0 && text.Contains(k.ToLowerInvariant()));
    }

    public void Toggle(string app)
    {
        if (!MutedApps.Remove(app)) MutedApps.Add(app);
    }

    public void AddKeyword(string k)
    {
        var t = k.Trim();
        if (t.Length > 0 && !Keywords.Contains(t)) Keywords.Add(t);
    }
}

/// <summary>Everything copied here or sent from the phone, newest first — in memory only (clipboards often hold secrets).</summary>
public sealed class ClipboardHistory
{
    public sealed record Item(Guid Id, string Text, DateTimeOffset Date, bool FromPhone);

    private readonly List<Item> _items = [];
    public List<Item> Items { get { lock (_items) return [.. _items]; } }

    public void Add(string text, bool fromPhone, DateTimeOffset now)
    {
        var t = text.Trim();
        if (t.Length == 0) return;
        lock (_items)
        {
            _items.RemoveAll(i => i.Text == t);
            _items.Insert(0, new Item(Guid.NewGuid(), t.Length > 20_000 ? t[..20_000] : t, now, fromPhone));
            if (_items.Count > 100) _items.RemoveRange(100, _items.Count - 100);
        }
    }

    public void Remove(Guid id) { lock (_items) _items.RemoveAll(i => i.Id == id); }
    public void Clear() { lock (_items) _items.Clear(); }
}

/// <summary>The notepad shared with the phone. Newest edit wins, by timestamp (ms).</summary>
public sealed class SharedNote
{
    public string Text { get; private set; } = "";
    public long Ts { get; private set; }

    public SharedNote(string text, long ts) { Text = text; Ts = ts; }

    /// <summary>Edited here: the new timestamp to send with {"t":"note"}.</summary>
    public long Edit(string text, DateTimeOffset now)
    {
        Text = text;
        Ts = now.ToUnixTimeMilliseconds();
        return Ts;
    }

    /// <summary>The phone's copy ({"t":"note"}): taken only if it's newer. True when it changed.</summary>
    public bool Receive(JsonObject m)
    {
        if (m["ts"] is not JsonValue tv || !tv.TryGetValue<long>(out var ts) || ts <= Ts || PhoneLink.Str(m, "text") is not { } text) return false;
        Text = text;
        Ts = ts;
        return true;
    }

    /// <summary>On connecting: the phone keeps whichever copy is newer, and sends its own back if that's it.</summary>
    public JsonObject Hello() => new() { ["t"] = "note_hello", ["text"] = Text, ["ts"] = Ts };
}

/// <summary>Phone battery: "Full in 40 min" / "5 h 10 min left" from recent readings, and full / low alerts.</summary>
public sealed class BatteryWatch
{
    private readonly List<(DateTimeOffset At, int Level)> _samples = [];
    private bool? _lastCharging;
    private bool _alertedFull, _alertedLow;

    public bool Alerts { get; set; } = true;
    public string? Eta { get; private set; }
    public Action<string, string> Notify = (_, _) => { };

    public void Update(int level, bool charging, DateTimeOffset now)
    {
        if (_lastCharging != charging)
        {
            _samples.Clear();
            _lastCharging = charging;
            _alertedFull = false;
        }
        if (_samples.Count == 0 || _samples[^1].Level != level)
        {
            _samples.Add((now, level));
            if (_samples.Count > 14) _samples.RemoveAt(0);
        }
        Eta = Estimate(level, charging);
        if (!Alerts) return;
        if (charging && level >= 100 && !_alertedFull)
        {
            _alertedFull = true;
            Notify("Phone fully charged", "You can unplug it.");
        }
        if (!charging && level <= 15 && !_alertedLow)
        {
            _alertedLow = true;
            Notify("Phone battery low", $"{level}% left — time to plug in.");
        }
        if (level >= 25) _alertedLow = false;
    }

    private string? Estimate(int level, bool charging)
    {
        if (_samples.Count < 2) return null;
        var (t0, l0) = _samples[0];
        var (t1, l1) = _samples[^1];
        var minutes = (t1 - t0).TotalMinutes;
        if (minutes < 2 || l0 == l1) return null;
        var perMinute = (l1 - l0) / minutes;
        if (charging && perMinute > 0 && level < 100) return "Full in " + Format((100 - level) / perMinute);
        if (!charging && perMinute < 0) return Format(level / -perMinute) + " left";
        return null;
    }

    internal static string Format(double minutes)
    {
        var m = (int)Math.Round(minutes);
        return m < 60 ? $"{Math.Max(m, 1)} min" : $"{m / 60} h {m % 60} min";
    }
}
