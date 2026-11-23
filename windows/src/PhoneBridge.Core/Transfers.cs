using System.Text.Json.Nodes;

namespace PhoneBridge.Core;

/// <summary>
/// File transfers in both directions, same messages as mac/Sources/Sync.swift:
/// file_start {fid, name, size, mime, kind?} → file_chunk {fid, data: base64} … → file_end {fid}.
/// Incoming files land in <see cref="Folder"/> (Downloads on Windows) under a name that doesn't clash.
/// </summary>
public sealed class Transfers
{
    public const int Chunk = 192 * 1024;

    private sealed class Incoming(FileStream file, string path, string? kind, long total)
    {
        public readonly FileStream File = file;
        public readonly string Path = path;
        public readonly string? Kind = kind;
        public readonly long Total = total;
        public long Got;
        public DateTime LastReport = DateTime.MinValue;
    }

    private readonly Dictionary<string, Incoming> _incoming = [];
    public string Folder { get; set; }

    /// <summary>(path, kind) once a file has fully arrived.</summary>
    public event Action<string, string?>? Received;
    /// <summary>(kind, bytes so far, total bytes or -1) while a file arrives; a few times a second.</summary>
    public event Action<string?, long, long>? Progress;
    /// <summary>Where a file of this kind goes; by default <see cref="Folder"/>. (Photos opened in the viewer go to a cache.)</summary>
    public Func<string?, string>? FolderFor { get; set; }

    public Transfers(string folder) => Folder = folder;

    /// <summary>Handles file_start / file_chunk / file_end from the phone. Messages arrive in order on one thread.</summary>
    public void Handle(JsonObject m)
    {
        if (PhoneLink.Str(m, "fid") is not { } fid) return;
        switch (PhoneLink.Str(m, "t"))
        {
            case "file_start":
            {
                var name = SafeName(PhoneLink.Str(m, "name") ?? "file");
                var kind = PhoneLink.Str(m, "kind");
                var folder = FolderFor?.Invoke(kind) ?? Folder;
                Directory.CreateDirectory(folder);
                var path = UniquePath(folder, name);
                var total = m["size"] is JsonValue v && v.TryGetValue<long>(out var n) ? n : -1;
                lock (_incoming)
                {
                    if (_incoming.Remove(fid, out var old)) old.File.Dispose();
                    _incoming[fid] = new Incoming(new FileStream(path, FileMode.CreateNew, FileAccess.Write), path, kind, total);
                }
                Progress?.Invoke(kind, 0, total);
                break;
            }
            case "file_chunk":
            {
                Incoming? f;
                lock (_incoming) _incoming.TryGetValue(fid, out f);
                if (f != null && PhoneLink.Str(m, "data") is { } b64)
                {
                    var bytes = Convert.FromBase64String(b64);
                    f.File.Write(bytes);
                    f.Got += bytes.Length;
                    if (DateTime.UtcNow - f.LastReport > TimeSpan.FromMilliseconds(150))
                    {
                        f.LastReport = DateTime.UtcNow;
                        Progress?.Invoke(f.Kind, f.Got, f.Total);
                    }
                }
                break;
            }
            case "file_end":
            {
                Incoming? f;
                lock (_incoming) _incoming.Remove(fid, out f);
                if (f == null) return;
                f.File.Dispose();
                Received?.Invoke(f.Path, f.Kind);
                break;
            }
        }
    }

    /// <summary>Drops half-received files (the connection went away mid-transfer).</summary>
    public void Abort()
    {
        lock (_incoming)
        {
            foreach (var f in _incoming.Values)
            {
                f.File.Dispose();
                try { File.Delete(f.Path); } catch (IOException) { }
            }
            _incoming.Clear();
        }
    }

    /// <summary>Streams a file to the phone, waiting for each chunk so memory stays flat.</summary>
    public static async Task<bool> SendAsync(string path, Func<JsonObject, Task<bool>> send, string? kind = null,
                                             IProgress<double>? progress = null)
    {
        await using var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, Chunk, useAsync: true);
        var fid = Guid.NewGuid().ToString().ToUpperInvariant();
        var start = new JsonObject
        {
            ["t"] = "file_start", ["fid"] = fid, ["name"] = Path.GetFileName(path), ["size"] = fs.Length, ["mime"] = Mime(path),
        };
        if (kind != null) start["kind"] = kind;
        if (!await send(start)) return false;
        var buf = new byte[Chunk];
        long sent = 0;
        int n;
        while ((n = await fs.ReadAsync(buf)) > 0)
        {
            if (!await send(new JsonObject { ["t"] = "file_chunk", ["fid"] = fid, ["data"] = Convert.ToBase64String(buf, 0, n) }))
                return false;
            sent += n;
            progress?.Report(fs.Length > 0 ? (double)sent / fs.Length : 1);
        }
        return await send(new JsonObject { ["t"] = "file_end", ["fid"] = fid });
    }

    public static string SafeName(string name)
    {
        var bad = Path.GetInvalidFileNameChars().Concat(['/', '\\', ':', '*', '?', '"', '<', '>', '|']).ToHashSet();
        var clean = new string(name.Select(c => bad.Contains(c) || c < 32 ? '_' : c).ToArray()).Trim().TrimEnd('.');
        if (clean is "" or "." or "..") clean = "file";
        // Names Windows reserves for devices, with or without an extension.
        var stem = Path.GetFileNameWithoutExtension(clean).ToUpperInvariant();
        if (stem is "CON" or "PRN" or "AUX" or "NUL" || (stem.Length == 4 && (stem.StartsWith("COM") || stem.StartsWith("LPT")) && char.IsDigit(stem[3])))
            clean = "_" + clean;
        return clean;
    }

    /// <summary>"name (2).ext" when name.ext is taken — like Windows does for downloads.</summary>
    internal static string UniquePath(string dir, string name)
    {
        var path = Path.Combine(dir, name);
        var stem = Path.GetFileNameWithoutExtension(name);
        var ext = Path.GetExtension(name);
        for (var i = 2; File.Exists(path) || Directory.Exists(path); i++)
            path = Path.Combine(dir, $"{stem} ({i}){ext}");
        return path;
    }

    private static readonly Dictionary<string, string> Mimes = new(StringComparer.OrdinalIgnoreCase)
    {
        [".jpg"] = "image/jpeg", [".jpeg"] = "image/jpeg", [".png"] = "image/png", [".gif"] = "image/gif",
        [".webp"] = "image/webp", [".heic"] = "image/heic", [".mp4"] = "video/mp4", [".mov"] = "video/quicktime",
        [".mp3"] = "audio/mpeg", [".m4a"] = "audio/mp4", [".wav"] = "audio/wav", [".pdf"] = "application/pdf",
        [".txt"] = "text/plain", [".zip"] = "application/zip", [".apk"] = "application/vnd.android.package-archive",
        [".doc"] = "application/msword", [".docx"] = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        [".xlsx"] = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    };

    /// <summary>The phone works the type out from the extension anyway; this is a hint.</summary>
    internal static string Mime(string path) => Mimes.TryGetValue(Path.GetExtension(path), out var m) ? m : "application/octet-stream";
}
