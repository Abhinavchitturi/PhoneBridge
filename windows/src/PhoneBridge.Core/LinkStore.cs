using System.Text.Json;
using System.Text.Json.Nodes;

namespace PhoneBridge.Core;

/// <summary>Encrypts secrets at rest. On Windows this is DPAPI (bound to the signed-in user); tests use a pass-through.</summary>
public interface ISecretProtector
{
    byte[] Protect(byte[] plain);
    byte[] Unprotect(byte[] sealedData);
}

/// <summary>
/// What the link remembers between runs, in one folder (%APPDATA%\PhoneBridge on Windows):
///  - secrets.bin: our X25519 identity and the paired phone's public key, sealed by <see cref="ISecretProtector"/>
///    (so the trusted key can't be swapped by editing a plain file — same idea as the Mac's Keychain item)
///  - settings.json: phone name, last address that worked, the phone's VPN addresses. Nothing secret.
/// Thread-safe.
/// </summary>
public sealed class LinkStore
{
    private readonly string _dir;
    private readonly ISecretProtector _protector;
    private readonly object _lock = new();
    private Dictionary<string, string>? _secrets;
    private JsonObject? _settings;

    public LinkStore(string dir, ISecretProtector protector)
    {
        _dir = dir;
        _protector = protector;
        Directory.CreateDirectory(dir);
    }

    private string SecretsPath => Path.Combine(_dir, "secrets.bin");
    private string SettingsPath => Path.Combine(_dir, "settings.json");

    /// <summary>Our long-term private key; made (and saved) on first use.</summary>
    public byte[] IdentityKey
    {
        get
        {
            lock (_lock)
            {
                var s = Secrets();
                if (s.TryGetValue("identity", out var b64)) return Convert.FromBase64String(b64);
                var k = Keys.NewPrivateKey();
                s["identity"] = Convert.ToBase64String(k);
                SaveSecrets();
                return k;
            }
        }
    }

    /// <summary>The paired phone's public key, or null when not paired.</summary>
    public byte[]? TrustedPhone
    {
        get
        {
            lock (_lock) return Secrets().TryGetValue("trustedPhone", out var b64) ? Convert.FromBase64String(b64) : null;
        }
        set
        {
            lock (_lock)
            {
                if (value == null) Secrets().Remove("trustedPhone");
                else Secrets()["trustedPhone"] = Convert.ToBase64String(value);
                SaveSecrets();
            }
        }
    }

    public string? PhoneName { get => GetString("phoneName"); set => SetValue("phoneName", value); }
    public string? LastHost { get => GetString("lastHost"); set => SetValue("lastHost", value); }

    public IReadOnlyList<string> RemoteHosts
    {
        get
        {
            lock (_lock)
                return Settings()["remoteHosts"] is JsonArray a ? a.Select(n => n?.GetValue<string>() ?? "").Where(s => s != "").ToList() : [];
        }
        set => SetNode("remoteHosts", new JsonArray(value.Select(v => (JsonNode)JsonValue.Create(v)!).ToArray()));
    }

    public bool GetBool(string key, bool fallback)
    {
        lock (_lock) return Settings()[key] is JsonValue v && v.TryGetValue<bool>(out var b) ? b : fallback;
    }

    public void SetBool(string key, bool value) => SetNode(key, JsonValue.Create(value));

    private string? GetString(string key)
    {
        lock (_lock) return Settings()[key] is JsonValue v && v.TryGetValue<string>(out var s) ? s : null;
    }

    private void SetValue(string key, string? value) => SetNode(key, value == null ? null : JsonValue.Create(value));

    private void SetNode(string key, JsonNode? node)
    {
        lock (_lock)
        {
            var s = Settings();
            if (node == null) s.Remove(key); else s[key] = node;
            WriteAtomically(SettingsPath, JsonSerializer.SerializeToUtf8Bytes(s, new JsonSerializerOptions { WriteIndented = true }));
        }
    }

    private JsonObject Settings()
    {
        if (_settings != null) return _settings;
        try
        {
            _settings = File.Exists(SettingsPath) ? JsonNode.Parse(File.ReadAllBytes(SettingsPath)) as JsonObject : null;
        }
        catch (JsonException)
        {
            _settings = null; // a corrupt settings file only loses non-secret hints
        }
        return _settings ??= new JsonObject();
    }

    private Dictionary<string, string> Secrets()
    {
        if (_secrets != null) return _secrets;
        if (File.Exists(SecretsPath))
        {
            // If this fails (another Windows user's file, a corrupt file), throw rather than silently make a new
            // identity: that would quietly unpair the phone. The app shows the error.
            var plain = _protector.Unprotect(File.ReadAllBytes(SecretsPath));
            _secrets = JsonSerializer.Deserialize<Dictionary<string, string>>(plain) ?? [];
        }
        return _secrets ??= [];
    }

    private void SaveSecrets() => WriteAtomically(SecretsPath, _protector.Protect(JsonSerializer.SerializeToUtf8Bytes(Secrets())));

    private static void WriteAtomically(string path, byte[] data)
    {
        var tmp = path + ".tmp";
        File.WriteAllBytes(tmp, data);
        File.Move(tmp, path, overwrite: true);
    }
}
