using System.Diagnostics;
using System.IO.Pipes;
using System.Text;

namespace PhoneBridge;

/// <summary>
/// PhoneBridge.exe                 starts the app (or brings the running one's window up)
/// PhoneBridge.exe file1 file2…    sends files to the phone (this is what Explorer's Send to → PhoneBridge does)
/// PhoneBridge.exe --text "hello"  puts text on the phone's clipboard
/// A second copy hands its request to the running one over a named pipe and exits.
/// </summary>
internal static class Program
{
    private static readonly string PipeName = "PhoneBridge.Windows." + Environment.UserName;

    [STAThread]
    private static void Main(string[] args)
    {
        // Started by the updater: let the old copy finish quitting first (it holds the single-instance lock).
        var i = Array.IndexOf(args, "--after-update");
        if (i >= 0 && i + 1 < args.Length && int.TryParse(args[i + 1], out var oldPid))
        {
            try { Process.GetProcessById(oldPid).WaitForExit(15_000); } catch (ArgumentException) { }
            args = args.Where((_, k) => k != i && k != i + 1).ToArray();
        }

        // One copy per Windows user: a second one would dial the phone too and the two would keep replacing each other.
        using var single = new Mutex(true, @"Local\PhoneBridge.Windows", out var first);
        if (!first)
        {
            Forward(args);
            return;
        }

        var selfTest = Array.IndexOf(args, "--selftest") >= 0;
        if (selfTest) SelfTest.Begin();
        ApplicationConfiguration.Initialize();
        Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);
        Application.ThreadException += (_, e) => { Log.Write("UI error: " + e.Exception); SelfTest.Error("UI", e.Exception); };
        AppDomain.CurrentDomain.UnhandledException += (_, e) => Crash(e.ExceptionObject);
        TaskScheduler.UnobservedTaskException += (_, e) => { Log.Write("task: " + e.Exception); SelfTest.Error("task", e.Exception); e.SetObserved(); };

        TrayApp? app;
        try { app = TrayApp.Create(); }
        catch (Exception e) { Crash(e); return; }
        if (app == null) return;
        var ui = SynchronizationContext.Current ?? new WindowsFormsSynchronizationContext();
        _ = Task.Run(() => Listen(a => ui.Post(_ => app.FromCommandLine(a), null)));
        if (selfTest) SelfTest.Run(app);
        else if (args.Any(a => a != "--send")) ui.Post(_ => app.FromCommandLine(args), null);
        Application.Run(app);
        SelfTest.End();
    }

    /// <summary>Something went badly wrong: log it, say so (with where the log is), and quit cleanly.</summary>
    private static void Crash(object error)
    {
        Log.Write("fatal: " + error);
        SelfTest.Error("fatal", error);
        if (SelfTest.Active) { SelfTest.End(); Environment.Exit(2); }
        try
        {
            MessageBox.Show($"PhoneBridge ran into a problem and has to close.\n\n{(error as Exception)?.Message ?? error.ToString()}\n\n" +
                            $"Details are in {Path.Combine(Log.Dir, "phonebridge.log")} — please attach it to an issue on GitHub.",
                "PhoneBridge", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
        catch (Exception) { }
        Environment.Exit(1);
    }

    private static void Forward(string[] args)
    {
        try
        {
            using var pipe = new NamedPipeClientStream(".", PipeName, PipeDirection.Out);
            pipe.Connect(3000);
            var data = Encoding.UTF8.GetBytes(string.Join("\0", args.Select(a => File.Exists(a) ? Path.GetFullPath(a) : a)));
            pipe.Write(data);
        }
        catch (Exception e) when (e is TimeoutException or IOException) { Log.Write("couldn't reach the running PhoneBridge: " + e.Message); }
    }

    private static async Task Listen(Action<string[]> handle)
    {
        while (true)
        {
            try
            {
                using var pipe = new NamedPipeServerStream(PipeName, PipeDirection.In, 1, PipeTransmissionMode.Byte, PipeOptions.Asynchronous | PipeOptions.CurrentUserOnly);
                await pipe.WaitForConnectionAsync();
                using var ms = new MemoryStream();
                await pipe.CopyToAsync(ms);
                var text = Encoding.UTF8.GetString(ms.ToArray());
                handle(text.Length == 0 ? [] : text.Split('\0'));
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
                Log.Write("pipe: " + e.Message);
                await Task.Delay(1000);
            }
        }
    }
}

/// <summary>A small rolling log in %LOCALAPPDATA%\PhoneBridge\phonebridge.log, for bug reports.</summary>
internal static class Log
{
    private static readonly object Lock = new();
    public static readonly string Dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "PhoneBridge");
    private static string FilePath => Path.Combine(Dir, "phonebridge.log");

    public static void Write(string line)
    {
        try
        {
            lock (Lock)
            {
                Directory.CreateDirectory(Dir);
                var f = new FileInfo(FilePath);
                if (f.Exists && f.Length > 512 * 1024) File.Move(FilePath, FilePath + ".1", overwrite: true);
                File.AppendAllText(FilePath, $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} {line}{Environment.NewLine}");
            }
        }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }
}
