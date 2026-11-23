using System.Media;
using System.Text.Json.Nodes;
using PhoneBridge.Core;

namespace PhoneBridge;

/// <summary>
/// The chat reply pop-up (mac/Sources/ChatPopup.swift): WhatsApp, Instagram and other chat apps pop up in the
/// top-right with your quick replies as one-tap buttons and a box to answer straight away. Goes away by itself after 5 s unless you're
/// pointing at it, typing, or have something written; 1.4 s after you send.
/// </summary>
internal sealed class ChatPopup
{
    private readonly Action<string, string> _reply;
    private readonly Action<string> _dismiss;
    private FloatingPanel? _panel;
    private string? _id;
    private bool _hover;
    private string _draft = "";
    private readonly System.Windows.Forms.Timer _timer = new();
    private JsonObject _state = new();

    public Func<string> Theme = () => "midnight";

    public ChatPopup(Action<string, string> reply, Action<string> dismiss)
    {
        _reply = reply;
        _dismiss = dismiss;
        _timer.Tick += (_, _) =>
        {
            _timer.Stop();
            if (_hover || (_panel?.ContainsFocus ?? false) || _draft.Trim().Length > 0) Restart(5000); else Hide();
        };
    }

    public void Show(JsonObject m)
    {
        var id = PhoneLink.Str(m, "id");
        var sameChat = id != null && id == _id; // chat apps update one notification as a conversation grows
        _id = id;
        if (!sameChat) _draft = "";
        _state = new JsonObject
        {
            ["id"] = id, ["app"] = PhoneLink.Str(m, "app") ?? "", ["title"] = PhoneLink.Str(m, "title") ?? "",
            ["text"] = PhoneLink.Str(m, "text") ?? "", ["icon"] = PhoneLink.Str(m, "icon"), ["suggestions"] = new JsonArray(),
            ["sent"] = false, ["keepDraft"] = sameChat, ["theme"] = Theme(),
        };
        _panel ??= new FloatingPanel("chat.html", new Size(380, 150), OnCommand);
        Push();
        _panel.ShowQuietly();
        try { SystemSounds.Asterisk.Play(); } catch (Exception) { }
        Restart(5000);
    }

    public void SetSuggestions(string id, List<string> list)
    {
        if (id != _id || list.Count == 0) return;
        _state["suggestions"] = new JsonArray(list.Select(s => (JsonNode)s).ToArray());
        _state["keepDraft"] = true;
        Push();
    }

    /// <summary>The phone dropped that notification (you replied or read it there).</summary>
    public void Remove(string id)
    {
        if (id == _id) Hide();
    }

    public void Hide()
    {
        _timer.Stop();
        _panel?.Hide();
        _id = null;
        _hover = false;
    }

    private void Push() => _panel?.Host.Push("state", new JsonObject { ["state"] = _state.DeepClone() });

    private void Restart(int ms)
    {
        _timer.Stop();
        _timer.Interval = ms;
        _timer.Start();
    }

    private void OnCommand(string cmd, JsonObject m)
    {
        switch (cmd)
        {
            case "ready": Push(); break;
            case "size":
                if (m["height"] is JsonValue hv && hv.TryGetValue<int>(out var h) && h > 40) _panel?.SetLogicalSize(new Size(380, Math.Min(h, 400)));
                break;
            case "reply":
                if (PhoneLink.Str(m, "text") is { Length: > 0 } text && _id != null)
                {
                    _reply(_id, text);
                    _draft = "";
                    _state["sent"] = true;
                    Restart(1400);
                }
                break;
            case "typing": _draft = PhoneLink.Str(m, "draft") ?? ""; break;
            case "hover": _hover = m["on"] is JsonValue v && v.TryGetValue<bool>(out var on) && on; break;
            case "close": Hide(); break;
        }
    }
}
