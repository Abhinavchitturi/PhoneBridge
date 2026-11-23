namespace PhoneBridge;

/// <summary>"Pair with Pixel 9? Check that your phone shows the same code: 123 456" — Pair / Cancel.</summary>
internal sealed class PairDialog : Form
{
    public PairDialog(string phoneName, string code, Icon icon)
    {
        Text = "PhoneBridge";
        Icon = icon;
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        TopMost = true;
        ShowInTaskbar = true;
        AutoScaleMode = AutoScaleMode.Dpi;
        AutoSize = true;
        AutoSizeMode = AutoSizeMode.GrowAndShrink;
        Padding = new Padding(20);

        var layout = new TableLayoutPanel { ColumnCount = 1, AutoSize = true, Dock = DockStyle.Fill };
        layout.Controls.Add(new Label
        {
            Text = $"Pair with {phoneName}?", AutoSize = true,
            Font = new Font(SystemFonts.MessageBoxFont ?? Font, FontStyle.Bold), Margin = new Padding(0, 0, 0, 8),
        });
        layout.Controls.Add(new Label
        {
            Text = "Check that your phone shows the same code:", AutoSize = true, MaximumSize = new Size(360, 0),
        });
        layout.Controls.Add(new Label
        {
            Text = code.Length == 6 ? $"{code[..3]} {code[3..]}" : code, AutoSize = true,
            Font = new Font(FontFamily.GenericMonospace, 28, FontStyle.Bold), Margin = new Padding(0, 12, 0, 16),
            AccessibleName = "Pairing code " + string.Join(" ", code.ToCharArray()),
        });
        var buttons = new FlowLayoutPanel { FlowDirection = FlowDirection.RightToLeft, AutoSize = true, Dock = DockStyle.Fill };
        var cancel = new Button { Text = "Cancel", DialogResult = DialogResult.Cancel, AutoSize = true };
        var pair = new Button { Text = "Pair", DialogResult = DialogResult.OK, AutoSize = true };
        buttons.Controls.Add(cancel);
        buttons.Controls.Add(pair);
        layout.Controls.Add(buttons);
        Controls.Add(layout);
        AcceptButton = pair;
        CancelButton = cancel;
    }

    protected override void OnShown(EventArgs e)
    {
        base.OnShown(e);
        Activate();
    }
}
