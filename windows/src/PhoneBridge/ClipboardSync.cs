using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using System.Security.Cryptography;

namespace PhoneBridge;

/// <summary>
/// PC clipboard → phone whenever it changes (WM_CLIPBOARDUPDATE, no polling); phone → PC when the phone sends it.
/// Text and images, like mac/Sources/Sync.swift. Lives on the UI thread (the clipboard needs an STA thread).
/// </summary>
internal sealed class ClipboardSync : NativeWindow, IDisposable
{
    private const int WmClipboardUpdate = 0x031D;

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool AddClipboardFormatListener(IntPtr hwnd);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool RemoveClipboardFormatListener(IntPtr hwnd);

    // Password managers and clipboard tools mark content that must not be synced or kept in history with these formats.
    private static readonly string[] SkipFormats =
        ["ExcludeClipboardContentFromMonitorProcessing", "Clipboard Viewer Ignore", "CanUploadToCloudClipboard"];

    public Action<string> SendText = _ => { };
    public Action<byte[]> SendImage = _ => { };
    /// <summary>(text, came from the phone) — feeds the clipboard history.</summary>
    public Action<string, bool> OnText = (_, _) => { };
    public bool Enabled = true;

    // Last text / image seen in either direction, so what the phone sent us isn't echoed straight back.
    private string? _lastText;
    private byte[]? _lastImageHash;
    private bool _writing;
    private System.Windows.Forms.Timer? _secretWipe;

    public ClipboardSync()
    {
        CreateHandle(new CreateParams { Parent = new IntPtr(-3) }); // HWND_MESSAGE: an invisible message-only window
        AddClipboardFormatListener(Handle);
    }

    protected override void WndProc(ref Message m)
    {
        if (m.Msg == WmClipboardUpdate && !_writing && Enabled) Changed();
        base.WndProc(ref m);
    }

    private void Changed()
    {
        try
        {
            var data = Clipboard.GetDataObject();
            if (data == null) return;
            var formats = data.GetFormats();
            if (formats.Any(f => SkipFormats.Contains(f, StringComparer.OrdinalIgnoreCase)) && IsExcluded(data)) return;
            if (Clipboard.ContainsText())
            {
                var s = Clipboard.GetText();
                if (s.Length == 0 || s.Length > 1_000_000 || s == _lastText) return;
                _lastText = s;
                OnText(s, false);
                SendText(s);
            }
            else if (Clipboard.ContainsImage())
            {
                using var img = Clipboard.GetImage();
                if (img == null) return;
                using var ms = new MemoryStream();
                img.Save(ms, ImageFormat.Png);
                var png = ms.ToArray();
                if (png.Length >= 8_000_000) return;
                var hash = SHA256.HashData(png);
                if (_lastImageHash != null && hash.AsSpan().SequenceEqual(_lastImageHash)) return;
                _lastImageHash = hash;
                SendImage(png);
            }
        }
        catch (ExternalException)
        {
            // another app has the clipboard open; its next change will come through
        }
    }

    /// <summary>"CanUploadToCloudClipboard" is only a "no" when it holds 0; the other two mean "skip" just by being there.</summary>
    private static bool IsExcluded(IDataObject data)
    {
        foreach (var f in data.GetFormats())
        {
            if (f.Equals("CanUploadToCloudClipboard", StringComparison.OrdinalIgnoreCase))
            {
                if (data.GetData(f) is MemoryStream ms && ms.Length >= 4 && BitConverter.ToInt32(ms.ToArray(), 0) == 0) return true;
            }
            else if (SkipFormats.Contains(f, StringComparer.OrdinalIgnoreCase)) return true;
        }
        return false;
    }

    /// <param name="secret">A one-time code: kept out of Windows clipboard history and cloud sync, and wiped after a minute
    /// if nothing else has been copied since.</param>
    public void SetFromPhone(string text, bool secret = false)
    {
        _lastText = text;
        if (!secret) OnText(text, true);
        Write(() =>
        {
            var d = new DataObject();
            d.SetText(text);
            if (secret)
            {
                d.SetData("ExcludeClipboardContentFromMonitorProcessing", new MemoryStream([0, 0, 0, 0]));
                d.SetData("CanIncludeInClipboardHistory", new MemoryStream([0, 0, 0, 0]));
                d.SetData("CanUploadToCloudClipboard", new MemoryStream([0, 0, 0, 0]));
            }
            Clipboard.SetDataObject(d, copy: true, retryTimes: 5, retryDelay: 100);
        });
        _secretWipe?.Dispose();
        _secretWipe = null;
        if (!secret) return;
        _secretWipe = new System.Windows.Forms.Timer { Interval = 60_000 };
        _secretWipe.Tick += (_, _) =>
        {
            _secretWipe?.Dispose();
            _secretWipe = null;
            try { if (Clipboard.ContainsText() && Clipboard.GetText() == text) Write(Clipboard.Clear); }
            catch (ExternalException) { }
        };
        _secretWipe.Start();
    }

    public void SetImageFromPhone(byte[] png)
    {
        _lastImageHash = SHA256.HashData(png);
        Write(() =>
        {
            using var ms = new MemoryStream(png);
            using var img = Image.FromStream(ms);
            var d = new DataObject();
            d.SetImage(new Bitmap(img));
            d.SetData("PNG", new MemoryStream(png)); // apps that understand PNG keep transparency
            Clipboard.SetDataObject(d, copy: true, retryTimes: 5, retryDelay: 100);
        });
    }

    private void Write(Action a)
    {
        _writing = true;
        try { a(); }
        catch (Exception e) when (e is ExternalException or ArgumentException) { Log.Write("clipboard write failed: " + e.Message); }
        finally
        {
            // WM_CLIPBOARDUPDATE for our own write arrives after this returns; skip it via _last* checks, and stop
            // ignoring updates on the next message-loop turn.
            BeginInvokeReset();
        }
    }

    private void BeginInvokeReset()
    {
        var t = new System.Windows.Forms.Timer { Interval = 200 };
        t.Tick += (_, _) => { _writing = false; t.Dispose(); };
        t.Start();
    }

    public void Dispose()
    {
        RemoveClipboardFormatListener(Handle);
        DestroyHandle();
        _secretWipe?.Dispose();
    }
}
