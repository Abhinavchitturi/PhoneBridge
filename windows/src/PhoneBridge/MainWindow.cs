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
        ClientSize = new Size(500, 720); // the Mac window's size, in points
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

/// <summary>The tray menu in the window's colours (dark, quiet hover, no gradients), with Windows 11's rounded corners.</summary>
internal sealed class DarkMenuRenderer : ToolStripProfessionalRenderer
{
    private static readonly Color Bg = Color.FromArgb(0x1C, 0x1C, 0x1F);
    private static readonly Color Hover = Color.FromArgb(0x2C, 0x2C, 0x30);
    private static readonly Color Text = Color.FromArgb(0xF5, 0xF5, 0xF7);
    private static readonly Color Dim = Color.FromArgb(0x8E, 0x8E, 0x93);
    private static readonly Color Line = Color.FromArgb(0x33, 0x33, 0x37);

    public DarkMenuRenderer() : base(new Colors()) => RoundedEdges = true;

    public static void Apply(ContextMenuStrip menu, bool checks)
    {
        menu.Renderer = new DarkMenuRenderer();
        menu.BackColor = Bg;
        menu.ForeColor = Text;
        menu.ShowImageMargin = false;
        menu.ShowCheckMargin = checks;
        menu.Padding = new Padding(4, 6, 4, 6);
        menu.HandleCreated += (_, _) => Dwm.RoundCorners(menu.Handle);
    }

    protected override void OnRenderItemText(ToolStripItemTextRenderEventArgs e)
    {
        e.TextColor = e.Item.Enabled ? Text : Dim;
        base.OnRenderItemText(e);
    }

    protected override void OnRenderMenuItemBackground(ToolStripItemRenderEventArgs e)
    {
        var r = new Rectangle(2, 0, e.Item.Width - 4, e.Item.Height);
        using var b = new SolidBrush(e.Item.Selected && e.Item.Enabled ? Hover : Bg);
        e.Graphics.FillRectangle(b, r);
    }

    protected override void OnRenderSeparator(ToolStripSeparatorRenderEventArgs e)
    {
        using var p = new Pen(Line);
        var y = e.Item.Height / 2;
        e.Graphics.DrawLine(p, 10, y, e.Item.Width - 10, y);
    }

    protected override void OnRenderItemCheck(ToolStripItemImageRenderEventArgs e)
    {
        using var p = new Pen(Text, 1.6f);
        var r = e.ImageRectangle;
        e.Graphics.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
        e.Graphics.DrawLines(p, [new Point(r.Left + 3, r.Top + r.Height / 2), new Point(r.Left + r.Width / 2 - 1, r.Bottom - 4), new Point(r.Right - 3, r.Top + 4)]);
    }

    private sealed class Colors : ProfessionalColorTable
    {
        public override Color ToolStripDropDownBackground => Bg;
        public override Color MenuBorder => Line;
        public override Color MenuItemBorder => Hover;
        public override Color MenuItemSelected => Hover;
        public override Color ImageMarginGradientBegin => Bg;
        public override Color ImageMarginGradientMiddle => Bg;
        public override Color ImageMarginGradientEnd => Bg;
        public override Color SeparatorDark => Line;
        public override Color SeparatorLight => Bg;
    }
}
