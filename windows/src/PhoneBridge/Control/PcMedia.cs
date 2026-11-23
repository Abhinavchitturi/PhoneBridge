using System.Text.Json.Nodes;
using Windows.Media.Control;
using Windows.Storage.Streams;

namespace PhoneBridge.Control;

/// <summary>
/// What's playing on the PC → the phone ("mac_media"), the phone's play/pause/next/prev, and the phone's player screen
/// ("deck_on" / "deck_do" → "deck_state"). Uses Windows' own media controls (the same ones behind the volume flyout),
/// so it works with Spotify, browsers (YouTube, Netflix…), the Media Player app, VLC and anything else that reports to it.
/// Port of mac/Sources/MacMedia.swift and MediaDeck.swift; the phone's player shows these as a generic player ("music").
/// </summary>
internal sealed class PcMedia
{
    public Action<JsonObject> Send = _ => { };

    private GlobalSystemMediaTransportControlsSessionManager? _manager;
    private GlobalSystemMediaTransportControlsSession? _session;
    private JsonObject _last = new();
    private string? _lastArtKey;
    private string? _lastArt;
    private readonly SemaphoreSlim _gate = new(1, 1);
    private System.Threading.Timer? _deckTimer;
    private DateTime _deckPing = DateTime.MinValue;
    private bool _pausedForCall;

    public async Task StartAsync()
    {
        try
        {
            _manager = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
            _manager.CurrentSessionChanged += (_, _) => _ = Watch();
            await Watch();
        }
        catch (Exception e)
        {
            Log.Write("media controls unavailable: " + e.Message);
        }
    }

    /// <summary>The app behind the current track ("Spotify", "chrome", …), or "" when nothing is playing.</summary>
    public string CurrentApp => PhoneBridge.Core.PhoneLink.Str(_last, "app") ?? "";
    public bool Playing => _last["playing"] is JsonValue v && v.TryGetValue<bool>(out var b) && b;

    private async Task Watch()
    {
        var s = _manager?.GetCurrentSession();
        if (!ReferenceEquals(s, _session))
        {
            _session = s;
            if (s != null)
            {
                s.MediaPropertiesChanged += (_, _) => _ = Report();
                s.PlaybackInfoChanged += (_, _) => _ = Report();
            }
        }
        await Report();
    }

    private async Task Report()
    {
        await _gate.WaitAsync();
        try
        {
            var m = new JsonObject { ["t"] = "mac_media" };
            var s = _session;
            if (s != null)
            {
                var props = await s.TryGetMediaPropertiesAsync();
                var info = s.GetPlaybackInfo();
                if (!string.IsNullOrEmpty(props?.Title))
                {
                    m["title"] = props.Title;
                    m["artist"] = props.Artist ?? "";
                    m["album"] = props.AlbumTitle ?? "";
                    m["playing"] = info.PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing;
                    m["app"] = AppName(s.SourceAppUserModelId);
                    // Art only when the track changes — it's the big part (as on the Mac).
                    var key = props.Title + "|" + props.Artist;
                    if (key != _lastArtKey)
                    {
                        _lastArtKey = key;
                        _lastArt = props.Thumbnail != null ? await ReadArt(props.Thumbnail) : null;
                        if (_lastArt != null) m["art"] = _lastArt;
                    }
                }
            }
            _last = (JsonObject)m.DeepClone();
            if (_lastArt != null && _last["title"] != null) _last["art"] = _lastArt;
            Send(m);
        }
        catch (Exception e)
        {
            Log.Write("media: " + e.Message);
        }
        finally
        {
            _gate.Release();
        }
    }

    private static async Task<string?> ReadArt(IRandomAccessStreamReference thumb)
    {
        try
        {
            using var stream = await thumb.OpenReadAsync();
            if (stream.Size == 0 || stream.Size > 4_000_000) return null;
            using var net = stream.AsStreamForRead();
            using var img = Image.FromStream(net);
            // Small JPEG, like the Mac's helper: the phone shows it at card size.
            var scale = Math.Min(1.0, 300.0 / Math.Max(img.Width, img.Height));
            using var small = new Bitmap(img, Math.Max(1, (int)(img.Width * scale)), Math.Max(1, (int)(img.Height * scale)));
            using var ms = new MemoryStream();
            small.Save(ms, System.Drawing.Imaging.ImageFormat.Jpeg);
            return Convert.ToBase64String(ms.ToArray());
        }
        catch (Exception) { return null; }
    }

    /// <summary>"Spotify.exe" / "Chrome" / "Microsoft.ZuneMusic_8wekyb3d8bbwe!Microsoft.ZuneMusic" → a readable name.</summary>
    internal static string AppName(string aumid)
    {
        var id = aumid ?? "";
        if (id.Contains('!')) id = id[(id.IndexOf('!') + 1)..];
        if (id.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)) id = id[..^4];
        id = id.Split('.', '_').LastOrDefault(p => p.Length > 0) ?? id;
        return id.ToLowerInvariant() switch
        {
            "chrome" => "Google Chrome", "msedge" => "Microsoft Edge", "firefox" => "Firefox", "brave" => "Brave Browser",
            "spotify" => "Spotify", "zunemusic" => "Media Player", "vlc" => "VLC", "" => "PC",
            _ => char.ToUpperInvariant(id[0]) + id[1..],
        };
    }

    public void Resend() => Send((JsonObject)_last.DeepClone());

    /// <summary>play / pause / play_pause / next / prev. False if there's no app to send it to.</summary>
    public bool Command(string cmd)
    {
        var s = _session;
        if (s == null) return false;
        _ = cmd switch
        {
            "play" => s.TryPlayAsync().AsTask(),
            "pause" => s.TryPauseAsync().AsTask(),
            "next" => s.TrySkipNextAsync().AsTask(),
            "prev" => s.TrySkipPreviousAsync().AsTask(),
            _ => s.TryTogglePlayPauseAsync().AsTask(),
        };
        return true;
    }

    /// <summary>A call started: pause whatever's playing (and remember, to resume afterwards).</summary>
    public void PauseForCall()
    {
        if (Playing && Command("pause")) _pausedForCall = true;
    }

    public void ResumeAfterCall()
    {
        if (_pausedForCall && !Playing) Command("play");
        _pausedForCall = false;
    }

    // MARK: The phone's player screen

    public void DeckOn()
    {
        _deckPing = DateTime.UtcNow;
        _deckTimer ??= new System.Threading.Timer(_ =>
        {
            if (DateTime.UtcNow - _deckPing > TimeSpan.FromSeconds(25)) { DeckOff(); return; } // phone left the screen
            Send(DeckState());
        }, null, 0, 1000);
    }

    public void DeckOff()
    {
        _deckTimer?.Dispose();
        _deckTimer = null;
    }

    public async Task DeckDo(string? action, JsonNode? value)
    {
        var s = _session;
        if (s != null)
        {
            try
            {
                switch (action)
                {
                    case "toggle": await s.TryTogglePlayPauseAsync(); break;
                    case "seek":
                        if (value is JsonValue sv && sv.TryGetValue<double>(out var sec))
                            await s.TryChangePlaybackPositionAsync(TimeSpan.FromSeconds(Math.Max(0, sec)).Ticks);
                        break;
                    case "skip":
                        if (value is JsonValue kv && kv.TryGetValue<double>(out var by))
                        {
                            var t = s.GetTimelineProperties();
                            await s.TryChangePlaybackPositionAsync(Math.Max(0, (t.Position + TimeSpan.FromSeconds(by)).Ticks));
                        }
                        break;
                    case "shuffle":
                        if (value is JsonValue bv && bv.TryGetValue<bool>(out var on)) await s.TryChangeShuffleActiveAsync(on);
                        break;
                    case "repeat":
                        var mode = (value as JsonValue)?.ToString() switch
                        {
                            "one" => Windows.Media.MediaPlaybackAutoRepeatMode.Track,
                            "all" => Windows.Media.MediaPlaybackAutoRepeatMode.List,
                            _ => Windows.Media.MediaPlaybackAutoRepeatMode.None,
                        };
                        await s.TryChangeAutoRepeatModeAsync(mode);
                        break;
                }
            }
            catch (Exception e) { Log.Write("deck: " + e.Message); }
        }
        await Task.Delay(150); // let the app catch up before reporting
        Send(DeckState());
    }

    private JsonObject DeckState()
    {
        var s = _session;
        if (s == null) return new JsonObject { ["t"] = "deck_state", ["kind"] = "none" };
        try
        {
            var info = s.GetPlaybackInfo();
            var tl = s.GetTimelineProperties();
            // Timeline position is a snapshot from the last update; move it on by the time since, while playing.
            var playing = info.PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing;
            var pos = tl.Position;
            if (playing && tl.LastUpdatedTime > DateTimeOffset.MinValue) pos += DateTimeOffset.Now - tl.LastUpdatedTime;
            var dur = tl.EndTime - tl.StartTime;
            if (dur > TimeSpan.Zero && pos > dur) pos = dur;
            return new JsonObject
            {
                ["t"] = "deck_state", ["kind"] = "music", ["playing"] = playing,
                ["pos"] = Math.Max(0, pos.TotalSeconds), ["dur"] = Math.Max(0, dur.TotalSeconds),
                ["shuffle"] = info.IsShuffleActive ?? false,
                ["repeat"] = info.AutoRepeatMode switch
                {
                    Windows.Media.MediaPlaybackAutoRepeatMode.Track => "one",
                    Windows.Media.MediaPlaybackAutoRepeatMode.List => "all",
                    _ => "off",
                },
            };
        }
        catch (Exception)
        {
            return new JsonObject { ["t"] = "deck_state", ["kind"] = "none" };
        }
    }
}
