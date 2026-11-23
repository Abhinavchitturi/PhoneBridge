using System.Runtime.InteropServices;

namespace PhoneBridge;

/// <summary>
/// The tray menu as a real Windows menu (CreatePopupMenu/TrackPopupMenuEx), the one Explorer and every built-in app
/// use: Windows 11's rounded, themed menu, light or dark with the system, with its own fonts, spacing and animation.
/// The items are plain ToolStripMenuItems used as data (text, enabled, checked, available, click handlers), so the
/// rest of the app sets them as before; the menu is built from them each time it opens.
/// </summary>
internal static class NativeMenu
{
    private static NativeWindow? _owner;

    /// <summary>Lets this app's menus follow the system's dark mode (Windows 10 1903+; harmless elsewhere).</summary>
    public static void FollowSystemTheme()
    {
        try
        {
            SetPreferredAppMode(1 /* AllowDark: follow the system setting */);
            FlushMenuThemes();
        }
        catch (Exception e) when (e is EntryPointNotFoundException or DllNotFoundException) { }
    }

    /// <summary>Shows the menu at the mouse pointer and runs the chosen item. Call on the UI thread.</summary>
    public static void Show(IReadOnlyList<ToolStripItem> items)
    {
        if (_owner == null)
        {
            // Windows needs a window of ours in front for the menu to close when you click elsewhere.
            _owner = new NativeWindow();
            _owner.CreateHandle(new CreateParams { Caption = "PhoneBridge menu" });
        }
        var menu = CreatePopupMenu();
        var commands = new List<ToolStripMenuItem>();
        try
        {
            var lastWasSeparator = true; // no separator at the top, nor two in a row
            foreach (var item in items)
            {
                if (!item.Available) continue;
                if (item is ToolStripSeparator)
                {
                    if (!lastWasSeparator) AppendMenu(menu, MF_SEPARATOR, 0, null);
                    lastWasSeparator = true;
                    continue;
                }
                if (item is not ToolStripMenuItem mi) continue;
                lastWasSeparator = false;
                if (!mi.Enabled)
                {
                    // Status lines (greyed). A long one becomes a few short lines: Windows menus don't wrap.
                    foreach (var line in Wrap(mi.Text ?? "", 56))
                        AppendMenu(menu, MF_STRING | MF_GRAYED, 0, Escape(line));
                    continue;
                }
                commands.Add(mi);
                var id = (uint)commands.Count;
                AppendMenu(menu, MF_STRING | (mi.Checked ? MF_CHECKED : 0), id, Escape(mi.Text ?? ""));
                if (mi.Font.Bold) SetMenuDefaultItem(menu, id, 0); // bold: what a left click does
            }

            GetCursorPos(out var pt);
            SetForegroundWindow(_owner.Handle);
            var flags = TPM_RETURNCMD | TPM_RIGHTBUTTON | (SystemInformation.RightAlignedMenus ? TPM_RIGHTALIGN : 0);
            var chosen = TrackPopupMenuEx(menu, flags, pt.X, pt.Y, _owner.Handle, IntPtr.Zero);
            PostMessage(_owner.Handle, 0 /* WM_NULL: lets the menu close properly next time */, IntPtr.Zero, IntPtr.Zero);
            if (chosen > 0 && chosen <= commands.Count) commands[chosen - 1].PerformClick();
        }
        finally
        {
            DestroyMenu(menu);
        }
    }

    /// <summary>Closes an open menu (the self-test).</summary>
    public static void Close() => EndMenu();

    [DllImport("user32.dll")] private static extern bool EndMenu();

    /// <summary>"&" shows as a mnemonic underline in a Windows menu; "&&" is a literal ampersand.</summary>
    private static string Escape(string s) => s.Replace("&", "&&");

    /// <summary>Splits at sentence ends, then wraps at word boundaries.</summary>
    internal static IEnumerable<string> Wrap(string text, int width)
    {
        var sentences = text.Split(". ");
        for (var i = 0; i < sentences.Length; i++)
        {
            var s = i < sentences.Length - 1 ? sentences[i] + "." : sentences[i];
            if (s.Trim().Length == 0) continue;
            var line = "";
            foreach (var word in s.Split(' '))
            {
                if (line.Length > 0 && line.Length + 1 + word.Length > width) { yield return line; line = word; }
                else line = line.Length == 0 ? word : line + " " + word;
            }
            if (line.Length > 0) yield return line;
        }
    }

    private const uint MF_STRING = 0x0, MF_GRAYED = 0x1, MF_CHECKED = 0x8, MF_SEPARATOR = 0x800;
    private const uint TPM_RIGHTBUTTON = 0x2, TPM_RIGHTALIGN = 0x8, TPM_RETURNCMD = 0x100;

    [StructLayout(LayoutKind.Sequential)] private struct POINT { public int X, Y; }

    [DllImport("user32.dll")] private static extern IntPtr CreatePopupMenu();
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern bool AppendMenu(IntPtr menu, uint flags, uint id, string? text);
    [DllImport("user32.dll")] private static extern bool SetMenuDefaultItem(IntPtr menu, uint item, uint byPosition);
    [DllImport("user32.dll")] private static extern int TrackPopupMenuEx(IntPtr menu, uint flags, int x, int y, IntPtr hwnd, IntPtr tpm);
    [DllImport("user32.dll")] private static extern bool DestroyMenu(IntPtr menu);
    [DllImport("user32.dll")] private static extern bool GetCursorPos(out POINT pt);
    [DllImport("user32.dll")] private static extern bool SetForegroundWindow(IntPtr hwnd);
    [DllImport("user32.dll")] private static extern bool PostMessage(IntPtr hwnd, uint msg, IntPtr w, IntPtr l);
    [DllImport("uxtheme.dll", EntryPoint = "#135")] private static extern int SetPreferredAppMode(int mode);
    [DllImport("uxtheme.dll", EntryPoint = "#136")] private static extern void FlushMenuThemes();
}
