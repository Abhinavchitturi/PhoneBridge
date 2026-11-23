using System.Runtime.InteropServices;
using System.Text.Json.Nodes;

namespace PhoneBridge;

/// <summary>
/// The PhoneBridge window: the same design as the Mac app's main window (dark Midnight theme, serif titles, the animated
/// orb while searching, the bridge hero, round-icon tiles, settings in place). The page is ui/index.html, drawn by
/// WebView2 so it can match the SwiftUI original closely; this class only hosts it and passes messages:
/// state goes in with <see cref="PushState"/>, the page's buttons come back through <see cref="Command"/>.
/// Closing the window hides it; PhoneBridge keeps running in the notification area.
/// </summary>
internal sealed class MainWindow : Form
{
    private static readonly Color Background = Color.FromArgb(0x0B, 0x0B, 0x0D);
    private readonly WebHost _host;

    /// <summary>(command, the whole message) — e.g. ("ring", …), ("set", {key, value}), ("drop", {paths}).</summary>
    public event Action<string, JsonObject>? Command;

    public static bool RuntimeAvailable => WebHost.RuntimeAvailable;

    public MainWindow(Icon icon)
    {
        Text = "PhoneBridge";
        Icon = icon;
        AutoScaleMode = AutoScaleMode.Dpi;
        AutoScaleDimensions = new SizeF(96F, 96F);
        ClientSize = Dwm.FitScreen(LogicalToDeviceUnits(new Size(500, 720))); // the Mac window's size, in points (96-dpi units), scaled for the display
        FormBorderStyle = FormBorderStyle.FixedSingle;
        MaximizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Background;
        ShowInTaskbar = true;
        _host = new WebHost("index.html", (cmd, m) =>
        {
            if (cmd == "hide") Hide();
            else Command?.Invoke(cmd, m);
        });
        Controls.Add(_host.View);
    }

    /// <summary>Sends the app's current state to the page (kept until the page is ready).</summary>
    public void PushState(JsonObject state) => _host.Push("state", new JsonObject { ["state"] = state });

    public void ShowAndActivate()
    {
        if (!Visible) Show();
        if (WindowState == FormWindowState.Minimized) WindowState = FormWindowState.Normal;
        // Windows only lets the foreground app take focus; a brief TopMost brings the window up from the tray.
        TopMost = true;
        Activate();
        TopMost = false;
        _host.View.Focus();
    }

    public void Toggle()
    {
        if (Visible && WindowState != FormWindowState.Minimized && ContainsFocus) Hide();
        else ShowAndActivate();
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        if (e.CloseReason == CloseReason.UserClosing)
        {
            e.Cancel = true; // keeps running in the notification area, like closing the Mac window
            Hide();
            return;
        }
        base.OnFormClosing(e);
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        Dwm.Darken(Handle, Background);
    }
}

/// <summary>Dark title bar and border that match the window, and rounded corners on Windows 11.</summary>
internal static class Dwm
{
    private const int UseImmersiveDarkMode = 20;
    private const int WindowCornerPreference = 33;
    private const int BorderColor = 34;
    private const int CaptionColor = 35;
    private const int Round = 2;

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

    public static void Darken(IntPtr hwnd, Color bg)
    {
        try
        {
            var on = 1;
            DwmSetWindowAttribute(hwnd, UseImmersiveDarkMode, ref on, sizeof(int));
            var colorRef = bg.R | (bg.G << 8) | (bg.B << 16); // COLORREF is 0x00BBGGRR
            DwmSetWindowAttribute(hwnd, CaptionColor, ref colorRef, sizeof(int)); // Windows 11; ignored before
            var border = 0x2E | (0x2E << 8) | (0x30 << 16);
            DwmSetWindowAttribute(hwnd, BorderColor, ref border, sizeof(int));
        }
        catch (DllNotFoundException) { }
    }

    /// <summary>A window size that fits on the main screen (big display scaling on a small screen), leaving room for the frame.</summary>
    public static Size FitScreen(Size s)
    {
        var area = (Screen.PrimaryScreen ?? Screen.AllScreens[0]).WorkingArea;
        return new Size(Math.Min(s.Width, area.Width - 40), Math.Min(s.Height, area.Height - 60));
    }

    public static void RoundCorners(IntPtr hwnd)
    {
        try
        {
            var round = Round;
            DwmSetWindowAttribute(hwnd, WindowCornerPreference, ref round, sizeof(int));
        }
        catch (DllNotFoundException) { }
    }
}

