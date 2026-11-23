namespace PhoneBridge;

internal static class Program
{
    [STAThread]
    private static void Main()
    {
        // One copy per Windows user: a second one would dial the phone too and the two would keep replacing each other.
        using var single = new Mutex(true, @"Local\PhoneBridge.Windows", out var first);
        if (!first) return;

        ApplicationConfiguration.Initialize();
        Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);
        Application.ThreadException += (_, e) => Log.Write("UI error: " + e.Exception);
        AppDomain.CurrentDomain.UnhandledException += (_, e) => Log.Write("fatal: " + e.ExceptionObject);
        TaskScheduler.UnobservedTaskException += (_, e) => { Log.Write("task: " + e.Exception); e.SetObserved(); };

        var app = TrayApp.Create();
        if (app != null) Application.Run(app);
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
