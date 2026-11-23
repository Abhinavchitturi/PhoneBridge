using System.Text.Json.Nodes;

namespace PhoneBridge;

/// <summary>
/// The phone window (Messages, Photos, Calls, Apps, Clipboard & Notes, Device, History, Settings): ui/phone.html,
/// a port of the Mac's PhoneView. This form only hosts the page; TrayApp.Phone.cs answers its commands.
/// Closing it hides it.
/// </summary>
internal sealed class PhoneWindow : Form
{
    private static readonly Color Background = Color.FromArgb(0x0B, 0x0B, 0x0D);
    public readonly WebHost Host;

    public PhoneWindow(Icon icon, string photoCache, Action<string, JsonObject> onCommand)
    {
        Text = "PhoneBridge — Phone";
        Icon = icon;
        AutoScaleMode = AutoScaleMode.Dpi;
        AutoScaleDimensions = new SizeF(96F, 96F);
        // Sizes are in 96-dpi units, scaled for the display (125% scaling: 1180 → 1475 pixels).
        ClientSize = Dwm.FitScreen(LogicalToDeviceUnits(new Size(1180, 760)));
        MinimumSize = Dwm.FitScreen(LogicalToDeviceUnits(new Size(900, 620)));
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Background;
        Host = new WebHost("phone.html", onCommand);
        Host.MapFolder("photos.phonebridge", photoCache);
        Controls.Add(Host.View);
    }

    public void Push(string type, JsonObject body) => Host.Push(type, body);

    public void ShowAndActivate()
    {
        if (!Visible) Show();
        if (WindowState == FormWindowState.Minimized) WindowState = FormWindowState.Normal;
        TopMost = true;
        Activate();
        TopMost = false;
        Host.View.Focus();
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        if (e.CloseReason == CloseReason.UserClosing)
        {
            e.Cancel = true;
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
