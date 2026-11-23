using System.Runtime.InteropServices;
using Windows.Graphics.Imaging;
using Windows.Media.Core;
using Windows.Media.Ocr;
using Windows.Media.Playback;
using Windows.Media.SpeechSynthesis;
using Windows.Storage;
using Windows.Storage.Streams;

namespace PhoneBridge;

/// <summary>Reads text aloud with Windows' own voices (the Mac's AVSpeechSynthesizer).</summary>
internal static class Speech
{
    private static readonly MediaPlayer Player = new();
    private static SpeechSynthesizer? _synth;

    public static async void Speak(string text)
    {
        try
        {
            _synth ??= new SpeechSynthesizer();
            Player.Pause();
            var stream = await _synth.SynthesizeTextToStreamAsync(text.Length > 2000 ? text[..2000] : text);
            Player.Source = MediaSource.CreateFromStream(stream, stream.ContentType);
            Player.Play();
        }
        catch (Exception e) { Log.Write("speech: " + e.Message); }
    }
}

/// <summary>Reads the text out of a photo with Windows' built-in OCR (the Mac's Live Text).</summary>
internal static class Ocr
{
    public static async Task<string> RecognizeAsync(string path)
    {
        var engine = OcrEngine.TryCreateFromUserProfileLanguages() ?? throw new InvalidOperationException("No OCR language installed");
        var file = await StorageFile.GetFileFromPathAsync(path);
        using IRandomAccessStream stream = await file.OpenAsync(FileAccessMode.Read);
        var decoder = await BitmapDecoder.CreateAsync(stream);
        var max = OcrEngine.MaxImageDimension;
        var scale = Math.Min(1.0, (double)max / Math.Max(decoder.PixelWidth, decoder.PixelHeight));
        var transform = new BitmapTransform { ScaledWidth = (uint)(decoder.PixelWidth * scale), ScaledHeight = (uint)(decoder.PixelHeight * scale) };
        using var bitmap = await decoder.GetSoftwareBitmapAsync(BitmapPixelFormat.Bgra8, BitmapAlphaMode.Premultiplied, transform,
            ExifOrientationMode.RespectExifOrientation, ColorManagementMode.DoNotColorManage);
        var result = await engine.RecognizeAsync(bitmap);
        return string.Join("\n", result.Lines.Select(l => l.Text));
    }
}

/// <summary>
/// "When Do Not Disturb is on for this PC, turn it on on the phone" (the Mac's FocusLink). Windows has no public API for
/// Focus / Do Not Disturb itself; SHQueryUserNotificationState reports when Windows is holding notifications back
/// (Do Not Disturb, quiet hours, presenting, a full-screen game), which is the same moment.
/// </summary>
internal sealed class FocusWatch
{
    public Action<bool> SetPhoneDnd = _ => { };
    private readonly System.Windows.Forms.Timer _timer = new() { Interval = 4000 };
    private bool? _last;

    public FocusWatch() => _timer.Tick += (_, _) => Poll();

    public bool Enabled
    {
        get => _timer.Enabled;
        set { _last = null; _timer.Enabled = value; }
    }

    private void Poll()
    {
        if (SHQueryUserNotificationState(out var state) != 0) return;
        var focused = state is 2 or 3 or 4 or 6; // busy, full-screen D3D, presentation mode, quiet time
        if (_last == null) { _last = focused; return; } // don't flip the phone just because the app started
        if (focused == _last) return;
        _last = focused;
        SetPhoneDnd(focused);
    }

    [DllImport("shell32.dll")]
    private static extern int SHQueryUserNotificationState(out int state);
}
