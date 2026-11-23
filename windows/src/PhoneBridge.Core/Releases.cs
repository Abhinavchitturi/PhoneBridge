using System.Text.Json;

namespace PhoneBridge.Core;

/// <summary>Reading GitHub's "latest release" answer for the updater (same rules as mac/Sources/Updater.swift).</summary>
public static class Releases
{
    public sealed record Release(string Version, string Url, string? Sha256, string Notes);

    /// <summary>The release's version (tag without "v") and the named asset's download URL and SHA-256, or null.</summary>
    public static Release? Parse(string json, string assetName)
    {
        try
        {
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;
            var tag = root.GetProperty("tag_name").GetString() ?? "";
            foreach (var a in root.GetProperty("assets").EnumerateArray())
            {
                if (a.GetProperty("name").GetString() != assetName) continue;
                // GitHub publishes each file's SHA-256 ("digest": "sha256:…").
                var digest = a.TryGetProperty("digest", out var d) && d.ValueKind == JsonValueKind.String ? d.GetString() : null;
                var sha = digest != null && digest.StartsWith("sha256:") ? digest[7..] : null;
                return new Release(tag.StartsWith('v') ? tag[1..] : tag, a.GetProperty("browser_download_url").GetString()!, sha,
                    root.TryGetProperty("body", out var b) && b.ValueKind == JsonValueKind.String ? b.GetString()! : "");
            }
        }
        catch (Exception e) when (e is JsonException or KeyNotFoundException or InvalidOperationException) { }
        return null;
    }

    /// <summary>"0.24" > "0.23", "0.23.1" > "0.23", "1.0" > "0.99".</summary>
    public static bool IsNewer(string a, string b)
    {
        var x = a.Split('.').Select(s => int.TryParse(s, out var n) ? n : 0).ToArray();
        var y = b.Split('.').Select(s => int.TryParse(s, out var n) ? n : 0).ToArray();
        for (var i = 0; i < Math.Max(x.Length, y.Length); i++)
        {
            int l = i < x.Length ? x[i] : 0, r = i < y.Length ? y[i] : 0;
            if (l != r) return l > r;
        }
        return false;
    }
}
