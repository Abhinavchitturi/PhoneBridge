using System.Security;
using System.Security.Cryptography;
using System.Text;
using System.Web;
using Microsoft.Win32;
using Windows.Data.Xml.Dom;
using Windows.UI.Notifications;

namespace PhoneBridge;

/// <summary>A button on a toast: what it says and the arguments it hands back when pressed.</summary>
internal readonly record struct ToastButton(string Label, string Args, bool UsesReplyBox = false);

/// <summary>
/// Windows toast notifications for an unpackaged app. The app registers an AppUserModelID under
/// HKCU\Software\Classes\AppUserModelId (display name + icon), which is all an unpackaged Win32 app needs to show toasts.
/// Buttons and the reply box come back through the in-process Activated event — so they work while PhoneBridge is
/// running (it always is: it lives in the tray). Clicking an old toast in Action Center after PhoneBridge quit does nothing.
/// If toasts aren't available at all (e.g. some Windows Server builds), falls back to tray balloon tips.
/// </summary>
internal sealed class Toasts
{
    public const string Aumid = "PhoneBridge.Windows";

    private readonly NotifyIcon _fallback;
    private ToastNotifier? _notifier;

    /// <summary>(arguments, text typed in the reply box or null). Raised on a background thread.</summary>
    public event Action<Dictionary<string, string>, string?>? Activated;
    /// <summary>The user closed a toast (swiped it away or pressed ×). (arguments)</summary>
    public event Action<Dictionary<string, string>>? Dismissed;

    public Toasts(NotifyIcon fallback)
    {
        _fallback = fallback;
        try
        {
            Register();
            _notifier = ToastNotificationManager.CreateToastNotifier(Aumid);
        }
        catch (Exception e)
        {
            Log.Write("toasts unavailable, using balloon tips: " + e.Message);
            _notifier = null;
        }
    }

    private static void Register()
    {
        var icon = Path.Combine(Log.Dir, "PhoneBridge.png");
        Directory.CreateDirectory(Log.Dir);
        using (var s = typeof(Toasts).Assembly.GetManifestResourceStream("PhoneBridge.png"))
        using (var f = File.Create(icon))
            s?.CopyTo(f);
        using var k = Registry.CurrentUser.CreateSubKey($@"Software\Classes\AppUserModelId\{Aumid}");
        k.SetValue("DisplayName", "PhoneBridge");
        k.SetValue("IconUri", icon);
        k.SetValue("IconBackgroundColor", "FF1F1F1F");
    }

    /// <param name="tag">Same tag (within a group) replaces the earlier toast, and lets <see cref="Remove"/> find it.</param>
    /// <param name="launch">Arguments for clicking the toast itself.</param>
    public void Show(string title, string body, string group, string? tag = null, string? attribution = null, string? image = null,
                     IReadOnlyList<ToastButton>? buttons = null, bool replyBox = false, Dictionary<string, string>? launch = null,
                     bool call = false)
    {
        if (_notifier == null)
        {
            _fallback.ShowBalloonTip(6000, title, body.Length > 0 ? body : " ", ToolTipIcon.None);
            return;
        }
        var x = new StringBuilder();
        x.Append("<toast");
        if (launch != null) x.Append($" launch=\"{Esc(Query(launch))}\"");
        if (call) x.Append(" scenario=\"incomingCall\"");
        x.Append("><visual><binding template=\"ToastGeneric\">");
        x.Append($"<text>{Esc(title)}</text>");
        if (body.Length > 0) x.Append($"<text>{Esc(body)}</text>");
        if (attribution != null) x.Append($"<text placement=\"attribution\">{Esc(attribution)}</text>");
        if (image != null) x.Append($"<image placement=\"appLogoOverride\" hint-crop=\"circle\" src=\"{Esc(new Uri(image).AbsoluteUri)}\"/>");
        x.Append("</binding></visual>");
        if (replyBox || buttons is { Count: > 0 })
        {
            x.Append("<actions>");
            if (replyBox) x.Append("<input id=\"reply\" type=\"text\" placeHolderContent=\"Reply\"/>");
            foreach (var b in (buttons ?? []).Take(5))
            {
                x.Append($"<action content=\"{Esc(b.Label)}\" arguments=\"{Esc(b.Args)}\" activationType=\"foreground\"");
                if (b.UsesReplyBox) x.Append(" hint-inputId=\"reply\"");
                x.Append("/>");
            }
            x.Append("</actions>");
        }
        if (call) x.Append("<audio src=\"ms-winsoundevent:Notification.Looping.Call\" loop=\"true\"/>");
        x.Append("</toast>");

        try
        {
            var doc = new XmlDocument();
            doc.LoadXml(x.ToString());
            var t = new ToastNotification(doc) { Group = group };
            if (tag != null) t.Tag = tag;
            t.Activated += (_, a) =>
            {
                if (a is not ToastActivatedEventArgs e) return;
                string? typed = e.UserInput.TryGetValue("reply", out var v) ? v as string : null;
                Activated?.Invoke(Parse(e.Arguments), typed);
            };
            t.Dismissed += (_, e) =>
            {
                if (e.Reason == ToastDismissalReason.UserCanceled && launch != null) Dismissed?.Invoke(launch);
            };
            _notifier.Show(t);
        }
        catch (Exception e)
        {
            Log.Write("toast failed: " + e.Message);
            _fallback.ShowBalloonTip(6000, title, body.Length > 0 ? body : " ", ToolTipIcon.None);
        }
    }

    public void Remove(string group, string tag)
    {
        if (_notifier == null) return;
        try { ToastNotificationManager.History.Remove(tag, group, Aumid); }
        catch (Exception e) { Log.Write("toast remove failed: " + e.Message); }
    }

    public void Info(string title, string body, Dictionary<string, string>? launch = null) => Show(title, body, "info", launch: launch);

    /// <summary>Toast tags are capped at 64 characters; the phone's notification keys are longer. Hash them.</summary>
    public static string TagFor(string id) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(id)))[..32];

    public static string Query(Dictionary<string, string> args) =>
        string.Join("&", args.Select(kv => $"{Uri.EscapeDataString(kv.Key)}={Uri.EscapeDataString(kv.Value)}"));

    public static Dictionary<string, string> Parse(string query)
    {
        var d = new Dictionary<string, string>();
        var q = HttpUtility.ParseQueryString(query);
        foreach (var k in q.AllKeys) if (k != null) d[k] = q[k] ?? "";
        return d;
    }

    private static string Esc(string s) => SecurityElement.Escape(s) ?? "";
}
