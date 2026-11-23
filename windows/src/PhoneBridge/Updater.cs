using System.Diagnostics;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using PhoneBridge.Core;
using Release = PhoneBridge.Core.Releases.Release;

namespace PhoneBridge;

/// <summary>
/// Keeps PhoneBridge for Windows up to date from the GitHub releases page, like the Mac and Android apps
/// (mac/Sources/Updater.swift). Checks a few seconds after it opens, when the phone connects, and every 4 hours; when a
/// newer release has a PhoneBridge-Windows.exe it asks once ("Update now?"), downloads it, checks it (GitHub's SHA-256
/// for the file, and that it is PhoneBridge at that version), swaps it in place of this exe and restarts.
/// </summary>
internal sealed class Updater
{
    public const string Repo = "Abhinavchitturi/PhoneBridge";
    public const string AssetName = "PhoneBridge-Windows.exe";


    private readonly string _current;
    private readonly System.Windows.Forms.Timer _timer = new() { Interval = 4 * 3600 * 1000 };
    private DateTime _lastCheck = DateTime.MinValue;
    private string? _askedFor;
    private bool _busy;
    private Release? _available;

    public Action<Release> Found = _ => { };
    public Action<string, string> Notify = (_, _) => { };
    /// <summary>Called just before the new copy starts: the app closes cleanly (it releases the single-instance lock).</summary>
    public Action BeforeRestart = () => { };

    public Updater(string current) => _current = current;

    public void Start()
    {
        CleanUpOld();
        _timer.Tick += (_, _) => Check(manual: false);
        _timer.Start();
        _ = Task.Delay(8000).ContinueWith(_ => Check(manual: false));
    }

    /// <summary>Asks GitHub for the latest release. `manual` also says when you're already up to date.</summary>
    public void Check(bool manual)
    {
        if (_busy) return;
        // Automatic checks at most once a minute (GitHub allows 60 an hour).
        if (!manual && DateTime.UtcNow - _lastCheck < TimeSpan.FromMinutes(1)) return;
        _lastCheck = DateTime.UtcNow;
        _ = Task.Run(async () =>
        {
            var r = await FetchLatest();
            if (r == null)
            {
                if (manual) Notify("Couldn't check for updates", "Check your internet connection and try again.");
                return;
            }
            if (IsNewer(r.Version, _current))
            {
                _available = r;
                if (manual || _askedFor != r.Version)
                {
                    _askedFor = r.Version;
                    Found(r);
                }
            }
            else if (manual) Notify("PhoneBridge is up to date", $"You have the latest version, {_current}.");
        });
    }

    private static HttpClient Http()
    {
        var c = new HttpClient { Timeout = TimeSpan.FromMinutes(5) };
        c.DefaultRequestHeaders.UserAgent.Add(new ProductInfoHeaderValue("PhoneBridge-Updater", "1"));
        c.DefaultRequestHeaders.Accept.Add(new MediaTypeWithQualityHeaderValue("application/vnd.github+json"));
        return c;
    }

    internal static async Task<Release?> FetchLatest()
    {
        try
        {
            using var http = Http();
            return Releases.Parse(await http.GetStringAsync($"https://api.github.com/repos/{Repo}/releases/latest"), AssetName);
        }
        catch (Exception e) when (e is HttpRequestException or TaskCanceledException)
        {
            Log.Write("update check: " + e.Message);
            return null;
        }
    }

    private static bool IsNewer(string a, string b) => Releases.IsNewer(a, b);

    public void Install()
    {
        if (_available is not { } r || _busy) return;
        _busy = true;
        Notify("Updating PhoneBridge", $"Downloading version {r.Version}…");
        _ = Task.Run(async () =>
        {
            try
            {
                var staged = await Download(r);
                Swap(staged);
            }
            catch (Exception e)
            {
                _busy = false;
                Log.Write("update failed: " + e);
                Notify("Update failed", e.Message + " Opening the download page instead.");
                Shell.Open($"https://github.com/{Repo}/releases/latest");
            }
        });
    }

    private static async Task<string> Download(Release r)
    {
        var exe = Environment.ProcessPath ?? throw new InvalidOperationException("Can't find PhoneBridge.exe.");
        var staged = exe + ".new";
        using (var http = Http())
        using (var src = await http.GetStreamAsync(r.Url))
        using (var dst = File.Create(staged))
            await src.CopyToAsync(dst);
        // The file must be exactly the one published on the release: GitHub lists each asset's SHA-256.
        // (Its version resource can't be relied on: release.sh may build the exe on a Mac, which leaves it out.)
        if (r.Sha256 == null) throw new InvalidDataException("The release doesn't list a checksum for the download.");
        await using (var f = File.OpenRead(staged))
        {
            var hash = Convert.ToHexString(await SHA256.HashDataAsync(f));
            if (!hash.Equals(r.Sha256, StringComparison.OrdinalIgnoreCase)) throw new InvalidDataException("The download didn't match the release's checksum.");
            f.Position = 0;
            if (f.ReadByte() != 'M' || f.ReadByte() != 'Z') throw new InvalidDataException("The download wasn't a Windows program.");
        }
        return staged;
    }

    /// <summary>A running exe can't be overwritten, but it can be renamed: move it aside, put the new one in its place, start it.</summary>
    private void Swap(string staged)
    {
        var exe = Environment.ProcessPath!;
        var old = exe + ".old";
        File.Move(exe, old, overwrite: true);
        try { File.Move(staged, exe); }
        catch { File.Move(old, exe, overwrite: true); throw; }
        var psi = new ProcessStartInfo(exe, $"--after-update {Environment.ProcessId}") { UseShellExecute = false };
        Process.Start(psi);
        BeforeRestart();
    }

    private static void CleanUpOld()
    {
        var exe = Environment.ProcessPath;
        if (exe == null) return;
        foreach (var leftover in new[] { exe + ".old", exe + ".new" })
            try { if (File.Exists(leftover)) File.Delete(leftover); } catch (Exception e) when (e is IOException or UnauthorizedAccessException) { }
    }
}
