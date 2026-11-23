using System.Diagnostics;
using System.IO.Pipes;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.ComTypes;
using System.Text;
using Microsoft.Win32;

namespace PhoneBridge;

/// <summary>
/// Makes the downloaded PhoneBridge-Windows.exe behave like a normal Windows app: opening it offers to install
/// PhoneBridge for this Windows user (no admin needed) into %LOCALAPPDATA%\Programs\PhoneBridge, with a Start menu
/// entry and an entry in Settings → Apps → Installed apps (which uninstalls it). The installed copy then starts and the
/// downloaded file is no longer in use, so it can be deleted. Updates replace the installed copy.
/// Settings, pairing and the log stay in %LOCALAPPDATA%\PhoneBridge, apart from the program.
/// </summary>
internal static class Installer
{
    private const string AppName = "PhoneBridge";
    private const string UninstallKey = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\PhoneBridge";
    private const string Publisher = "PhoneBridge";

    public static readonly string Dir = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", AppName);
    public static readonly string Exe = Path.Combine(Dir, "PhoneBridge.exe");
    private static string Shortcut => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.Programs), "PhoneBridge.lnk"); // Start menu → Programs

    public static bool IsInstalledCopy =>
        string.Equals(Path.GetFullPath(Environment.ProcessPath ?? ""), Path.GetFullPath(Exe), StringComparison.OrdinalIgnoreCase);

    /// <summary>
    /// Runs before anything else. True when this process is done (it installed or uninstalled, or the user cancelled);
    /// false to carry on starting the app.
    /// </summary>
    public static bool Handle(string[] args, string pipeName, string mutexName)
    {
        var quiet = args.Contains("--quiet"); // no questions (scripts, and CI's check of the installer)
        if (args.Contains("--uninstall")) { Uninstall(pipeName, mutexName, quiet); return true; }
        if (args.Contains("--install")) return Install(pipeName, mutexName, quiet, launch: !args.Contains("--no-launch"));
        // Not from a download: the installed copy itself, the self-test, or someone who wants it to run where it is.
        if (IsInstalledCopy || args.Contains("--selftest") || args.Contains("--portable")) return false;
        // Started from a download with files (Send to): just do it, don't interrupt with an installer.
        if (args.Any(a => !a.StartsWith("--"))) return false;
        return Install(pipeName, mutexName, quiet: false, launch: true);
    }

    /// <summary>The installed copy, on each start: keeps the Apps entry's version and the Start menu entry current.</summary>
    public static void Refresh()
    {
        if (!IsInstalledCopy) return;
        try
        {
            Register();
            if (!File.Exists(Shortcut)) CreateShortcut();
        }
        catch (Exception e) { Log.Write("installer refresh: " + e.Message); }
    }

    private static bool Install(string pipeName, string mutexName, bool quiet, bool launch)
    {
        var upgrade = File.Exists(Exe);
        var startAtSignIn = new TaskDialogVerificationCheckBox("Start PhoneBridge when I sign in to Windows", true);
        var install = new TaskDialogButton(upgrade ? "Update" : "Install");
        var page = new TaskDialogPage
        {
            Caption = AppName,
            Heading = upgrade ? $"Update PhoneBridge to {Version}?" : "Install PhoneBridge?",
            Text = "PhoneBridge will be installed for your Windows account and added to the Start menu. " +
                   "You can delete the downloaded file afterwards.\n\nTo remove it later: Settings → Apps → Installed apps → PhoneBridge.",
            Icon = new TaskDialogIcon(Icon.ExtractAssociatedIcon(Environment.ProcessPath!) ?? SystemIcons.Application),
            Buttons = { install, TaskDialogButton.Cancel },
            DefaultButton = install,
            Verification = startAtSignIn,
            AllowCancel = true,
        };
        if (!quiet && TaskDialog.ShowDialog(page) != install) return true;

        try
        {
            StopRunningCopy(pipeName, mutexName);
            Directory.CreateDirectory(Dir);
            // Copy next to the target and then swap, so a half-written exe never sits where the Start menu points.
            var staged = Exe + ".new";
            File.Copy(Environment.ProcessPath!, staged, overwrite: true);
            if (File.Exists(Exe)) File.Move(Exe, Exe + ".old", overwrite: true);
            File.Move(staged, Exe, overwrite: true);
            TryDelete(Exe + ".old");
            CreateShortcut();
            Register();
            SetStartAtSignIn(startAtSignIn.Checked);
            Log.Write($"installed {Version} to {Dir}");
        }
        catch (Exception e)
        {
            Log.Write("install failed: " + e);
            if (quiet) Environment.Exit(1);
            TaskDialog.ShowDialog(new TaskDialogPage
            {
                Caption = AppName, Heading = "PhoneBridge couldn't be installed", Icon = TaskDialogIcon.Error,
                Text = e.Message + "\n\nYou can still run it from where you downloaded it: start it with --portable.",
            });
            return true;
        }

        // Start the installed copy (it opens its window) and leave, so the downloaded file is free to delete.
        if (launch) Process.Start(new ProcessStartInfo(Exe, "--installed") { UseShellExecute = false, WorkingDirectory = Dir });
        return true;
    }

    private static void Uninstall(string pipeName, string mutexName, bool quiet)
    {
        if (!quiet)
        {
            var remove = new TaskDialogButton("Uninstall");
            var keep = new TaskDialogVerificationCheckBox("Also remove PhoneBridge's settings and pairing", false);
            var page = new TaskDialogPage
            {
                Caption = AppName, Heading = "Uninstall PhoneBridge?",
                Text = "PhoneBridge will be removed from this PC. Files you received from your phone stay where they are.",
                Icon = TaskDialogIcon.Warning, Buttons = { remove, TaskDialogButton.Cancel }, DefaultButton = TaskDialogButton.Cancel,
                Verification = keep,
            };
            if (TaskDialog.ShowDialog(page) != remove) return;
            if (keep.Checked) RemoveSettingsLater = true;
        }

        StopRunningCopy(pipeName, mutexName);
        TryDelete(Shortcut);
        try
        {
            Registry.CurrentUser.DeleteSubKeyTree(UninstallKey, throwOnMissingSubKey: false);
            using var run = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run", writable: true);
            run?.DeleteValue(AppName, throwOnMissingValue: false);
            Registry.CurrentUser.DeleteSubKeyTree($@"Software\Classes\AppUserModelId\{Toasts.Aumid}", throwOnMissingSubKey: false);
        }
        catch (Exception e) { Log.Write("uninstall registry: " + e.Message); }
        TryDelete(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.SendTo), "PhoneBridge.lnk"));

        // This exe is running (it may be the installed one), so it can't delete itself: a hidden command prompt removes
        // the folder a few seconds after we've exited.
        var targets = $"rmdir /s /q \"{Dir}\"" + (RemoveSettingsLater ? $" & rmdir /s /q \"{Log.Dir}\"" : "");
        Process.Start(new ProcessStartInfo("cmd.exe", $"/c ping -n 4 127.0.0.1 >nul & {targets}")
        {
            UseShellExecute = false, CreateNoWindow = true, WindowStyle = ProcessWindowStyle.Hidden,
        });
        Log.Write("uninstalled");
    }

    private static bool RemoveSettingsLater;

    /// <summary>Asks a running PhoneBridge (the installed one or another download) to quit, and makes sure it did.</summary>
    private static void StopRunningCopy(string pipeName, string mutexName)
    {
        if (!Mutex.TryOpenExisting(mutexName, out var running)) return;
        using (running)
        {
            try
            {
                using var pipe = new NamedPipeClientStream(".", pipeName, PipeDirection.Out);
                pipe.Connect(2000);
                pipe.Write(Encoding.UTF8.GetBytes("--quit"));
            }
            catch (Exception e) when (e is TimeoutException or IOException) { }
            try
            {
                if (running.WaitOne(8000)) { running.ReleaseMutex(); return; }
            }
            catch (AbandonedMutexException) { running.ReleaseMutex(); return; } // it quit without letting go
        }
        // Older versions don't know --quit: end them.
        foreach (var p in Process.GetProcesses())
        {
            try
            {
                if (p.Id == Environment.ProcessId || !p.ProcessName.StartsWith("PhoneBridge", StringComparison.OrdinalIgnoreCase)) continue;
                p.Kill();
                p.WaitForExit(5000);
            }
            catch (Exception) { }
            finally { p.Dispose(); }
        }
    }

    /// <summary>The entry in Settings → Apps → Installed apps (and Control Panel → Programs).</summary>
    private static void Register()
    {
        using var k = Registry.CurrentUser.CreateSubKey(UninstallKey);
        k.SetValue("DisplayName", AppName);
        k.SetValue("DisplayVersion", Version);
        k.SetValue("Publisher", Publisher);
        k.SetValue("DisplayIcon", $"\"{Exe}\",0");
        k.SetValue("InstallLocation", Dir);
        k.SetValue("UninstallString", $"\"{Exe}\" --uninstall");
        k.SetValue("QuietUninstallString", $"\"{Exe}\" --uninstall --quiet");
        k.SetValue("URLInfoAbout", "https://github.com/Abhinavchitturi/PhoneBridge");
        k.SetValue("NoModify", 1, RegistryValueKind.DWord);
        k.SetValue("NoRepair", 1, RegistryValueKind.DWord);
        try { k.SetValue("EstimatedSize", (int)(new FileInfo(Exe).Length / 1024), RegistryValueKind.DWord); } catch (IOException) { }
        if (k.GetValue("InstallDate") == null) k.SetValue("InstallDate", DateTime.Now.ToString("yyyyMMdd"));
    }

    private static void SetStartAtSignIn(bool on)
    {
        using var k = Registry.CurrentUser.CreateSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run");
        if (on) k.SetValue(AppName, $"\"{Exe}\"");
        else k.DeleteValue(AppName, throwOnMissingValue: false);
    }

    private static void TryDelete(string path)
    {
        try { if (File.Exists(path)) File.Delete(path); }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException) { }
    }

    private static string Version => Application.ProductVersion.Split('+')[0];

    // MARK: The Start menu shortcut (IShellLink), with the app's id so its toasts and taskbar button belong to it.

    private static void CreateShortcut()
    {
        var link = (IShellLinkW)new ShellLink();
        link.SetPath(Exe);
        link.SetWorkingDirectory(Dir);
        link.SetDescription("Your Android phone, on your PC");
        link.SetIconLocation(Exe, 0);
        var store = (IPropertyStore)link;
        var key = new PropertyKey(new Guid("9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3"), 5); // System.AppUserModel.ID
        var value = new PropVariant(Toasts.Aumid);
        try
        {
            store.SetValue(ref key, ref value);
            store.Commit();
        }
        finally { value.Clear(); }
        Directory.CreateDirectory(Path.GetDirectoryName(Shortcut)!);
        ((IPersistFile)link).Save(Shortcut, true);
        Marshal.ReleaseComObject(link);
    }

    [ComImport, Guid("00021401-0000-0000-C000-000000000046")]
    private class ShellLink { }

    [ComImport, InterfaceType(ComInterfaceType.InterfaceIsIUnknown), Guid("000214F9-0000-0000-C000-000000000046")]
    private interface IShellLinkW
    {
        void GetPath([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder file, int max, IntPtr data, uint flags);
        void GetIDList(out IntPtr idl);
        void SetIDList(IntPtr idl);
        void GetDescription([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder name, int max);
        void SetDescription([MarshalAs(UnmanagedType.LPWStr)] string name);
        void GetWorkingDirectory([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder dir, int max);
        void SetWorkingDirectory([MarshalAs(UnmanagedType.LPWStr)] string dir);
        void GetArguments([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder args, int max);
        void SetArguments([MarshalAs(UnmanagedType.LPWStr)] string args);
        void GetHotkey(out short hotkey);
        void SetHotkey(short hotkey);
        void GetShowCmd(out int cmd);
        void SetShowCmd(int cmd);
        void GetIconLocation([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder path, int max, out int icon);
        void SetIconLocation([MarshalAs(UnmanagedType.LPWStr)] string path, int icon);
        void SetRelativePath([MarshalAs(UnmanagedType.LPWStr)] string path, uint reserved);
        void Resolve(IntPtr hwnd, uint flags);
        void SetPath([MarshalAs(UnmanagedType.LPWStr)] string file);
    }

    [ComImport, InterfaceType(ComInterfaceType.InterfaceIsIUnknown), Guid("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99")]
    private interface IPropertyStore
    {
        void GetCount(out uint count);
        void GetAt(uint index, out PropertyKey key);
        void GetValue(ref PropertyKey key, out PropVariant value);
        void SetValue(ref PropertyKey key, ref PropVariant value);
        void Commit();
    }

    [StructLayout(LayoutKind.Sequential, Pack = 4)]
    private struct PropertyKey(Guid fmtid, uint pid)
    {
        public Guid FormatId = fmtid;
        public uint PropertyId = pid;
    }

    /// <summary>A PROPVARIANT holding a string (VT_LPWSTR), which is all a shortcut's app id needs.</summary>
    [StructLayout(LayoutKind.Explicit, Size = 24)]
    private struct PropVariant
    {
        [FieldOffset(0)] private ushort _type;
        [FieldOffset(8)] private IntPtr _value;

        public PropVariant(string s)
        {
            _type = 31; // VT_LPWSTR
            _value = Marshal.StringToCoTaskMemUni(s);
        }

        public void Clear()
        {
            if (_value != IntPtr.Zero) Marshal.FreeCoTaskMem(_value);
            _value = IntPtr.Zero;
            _type = 0;
        }
    }
}
