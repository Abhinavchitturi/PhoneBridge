namespace PhoneBridge;

/// <summary>
/// Runs work on the UI thread, from any thread. Windows and WebView2 may only be touched from the thread that created
/// them; the app's background threads (the phone link, media and Bluetooth events, timers) hand their work over here.
/// It posts to a hidden window created on the UI thread, so it can't silently run work elsewhere — which is what a
/// WindowsFormsSynchronizationContext made before Windows Forms had started did, and why the app crashed with
/// "CoreWebView2 can only be accessed from the UI thread".
/// </summary>
internal sealed class UiThread : SynchronizationContext
{
    private readonly System.Windows.Forms.Control _window;
    public int ThreadId { get; }

    /// <summary>Call on the UI thread, before anything else needs it.</summary>
    public UiThread()
    {
        ThreadId = Environment.CurrentManagedThreadId;
        _window = new System.Windows.Forms.Control();
        _window.CreateControl();
        _ = _window.Handle; // the handle belongs to this thread from now on
    }

    public bool IsCurrent => Environment.CurrentManagedThreadId == ThreadId;

    public override void Post(SendOrPostCallback d, object? state)
    {
        if (_window.IsDisposed) return;
        try { _window.BeginInvoke(d, state); }
        catch (InvalidOperationException) { } // shutting down
    }

    public override void Send(SendOrPostCallback d, object? state)
    {
        if (IsCurrent) d(state);
        else if (!_window.IsDisposed) _window.Invoke(d, state);
    }

    public override SynchronizationContext CreateCopy() => this;
}
