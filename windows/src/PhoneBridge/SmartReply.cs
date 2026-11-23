using System.Net.Http.Json;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace PhoneBridge;

/// <summary>
/// Three short reply suggestions for an incoming chat message, written on this PC — the Windows twin of the Mac's
/// SmartReply (Apple's on-device model). Windows has no on-device language model that an ordinary app can call, so this
/// uses a local model server when one is running: Ollama (ollama.com) on its standard local port. Nothing leaves the PC.
/// Without one it suggests nothing. Like the Mac, it never holds a notification back for more than ~4.5 s.
/// </summary>
internal static class SmartReply
{
    private const string Base = "http://127.0.0.1:11434";
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(10) };
    private static string? _model;
    private static DateTime _lastProbe = DateTime.MinValue;

    public static string Status => _model != null
        ? $"Written on this PC by the local model “{_model}” (Ollama). Nothing is sent anywhere."
        : "Needs a local model on this PC: install Ollama (ollama.com) and run, for example, “ollama pull llama3.2”. Nothing is sent anywhere.";

    public static async Task<List<string>> SuggestAsync(string sender, string latest, List<string> earlier)
    {
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(4.5));
        try
        {
            var model = await Model(cts.Token);
            if (model == null) return [];
            var context = earlier.Count > 0 ? "Earlier messages:\n" + string.Join("\n", earlier) + "\n\n" : "";
            var prompt = context + $"New message from {sender}:\n{(latest.Length > 600 ? latest[..600] : latest)}\n\nSuggest the user's reply.";
            var body = new JsonObject
            {
                ["model"] = model, ["stream"] = false, ["prompt"] = prompt,
                ["system"] = "You suggest quick replies for the user to send to a chat message they just received. Write exactly 3 different, " +
                             "short, natural replies, one per line, with no numbering, quotes or extra words. Each under 10 words. Match the " +
                             "language and tone of the message. Don't promise anything specific like times or money, and don't invent facts.",
                ["options"] = new JsonObject { ["temperature"] = 0.7, ["num_predict"] = 80 },
            };
            using var res = await Http.PostAsJsonAsync(Base + "/api/generate", body, cts.Token);
            var json = await res.Content.ReadFromJsonAsync<JsonObject>(cts.Token);
            var text = json?["response"]?.GetValue<string>() ?? "";
            return text.Split('\n')
                .Select(l => l.Trim().Trim('-', '•', '*', '"', '“', '”', ' ', '.', ')').TrimStart("0123456789".ToCharArray()).Trim(' ', '.', ')'))
                .Where(l => l.Length > 0 && l.Length <= 80).Take(3).ToList();
        }
        catch (Exception e) when (e is HttpRequestException or TaskCanceledException or JsonException or InvalidOperationException)
        {
            return [];
        }
    }

    /// <summary>The installed model to use (a small chat model if there is one), re-checked at most every 5 minutes.</summary>
    private static async Task<string?> Model(CancellationToken ct)
    {
        if (_model != null || DateTime.UtcNow - _lastProbe < TimeSpan.FromMinutes(5)) return _model;
        _lastProbe = DateTime.UtcNow;
        try
        {
            var tags = await Http.GetFromJsonAsync<JsonObject>(Base + "/api/tags", ct);
            var names = (tags?["models"] as JsonArray)?.Select(m => m?["name"]?.GetValue<string>()).OfType<string>().ToList() ?? [];
            string[] preferred = ["llama3.2", "phi", "qwen", "gemma", "mistral", "llama"];
            _model = preferred.Select(p => names.FirstOrDefault(n => n.StartsWith(p, StringComparison.OrdinalIgnoreCase))).FirstOrDefault(n => n != null)
                     ?? names.FirstOrDefault(n => !n.Contains("embed", StringComparison.OrdinalIgnoreCase));
        }
        catch (Exception e) when (e is HttpRequestException or TaskCanceledException or JsonException) { _model = null; }
        return _model;
    }
}
