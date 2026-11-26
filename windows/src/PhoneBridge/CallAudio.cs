using Windows.ApplicationModel.Calls;
using Windows.Devices.Enumeration;
using Windows.Foundation.Metadata;

namespace PhoneBridge;

/// <summary>
/// Moves the voice of a phone call to this PC's speakers and microphone (and back), through Windows' own Bluetooth
/// hands-free support: the same route a car kit uses. PhoneBridge's network link can't carry call audio because Android
/// lets no ordinary app touch the voice of a mobile call. Bluetooth hands-free is the only way it leaves the phone.
///
/// Steps: find the phone's Bluetooth "phone line" device (the phone must be paired with this PC), get access, register
/// this app for it and connect (the hands-free link), find the line's active call, then ChangeAudioDevice(LocalDevice).
/// Windows does the audio itself, so there's no audio code here. Each step that fails says which one and what to do,
/// because whether Windows lets a non-Store app use these calls depends on the Windows build.
/// </summary>
internal sealed class CallAudio
{
    public readonly record struct Result(bool Ok, bool OnPc, string Message);

    private PhoneLineTransportDevice? _device;
    private PhoneLine? _line;

    /// <summary>The phone's name as PhoneBridge knows it, to pick the right device when several phones are paired.</summary>
    public string PhoneName { get; set; } = "";

    public Task<Result> MoveToPcAsync() => MoveAsync(PhoneCallAudioDevice.LocalDevice);
    public Task<Result> MoveToPhoneAsync() => MoveAsync(PhoneCallAudioDevice.RemoteDevice);

    private async Task<Result> MoveAsync(PhoneCallAudioDevice where)
    {
        try
        {
            if (!ApiInformation.IsMethodPresent("Windows.ApplicationModel.Calls.PhoneCall", "ChangeAudioDeviceAsync"))
                return Fail("Call audio on the PC needs Windows 11 (or Windows 10 build 20348 or newer).");

            var device = _device ?? await FindDeviceAsync();
            if (device == null)
                return Fail("Your phone isn't paired with this PC over Bluetooth. Pair it in Settings → Bluetooth & devices, then try again.");

            var access = await device.RequestAccessAsync();
            Log.Write($"call audio: access {access}");
            if (access != DeviceAccessStatus.Allowed)
                return Fail($"Windows refused access to the phone's call line ({access}). Close Phone Link if it's using the phone, then try again.");

            if (!device.IsRegistered())
            {
                device.RegisterApp();
                Log.Write("call audio: registered");
            }
            if (!await device.ConnectAsync())
                return Fail("Couldn't open the hands-free link. On the phone: Settings → Bluetooth → this PC → turn on Calls.");
            _device = device;
            Log.Write($"call audio: connected, routing {SafeRouting(device)}");

            var line = _line ?? await FindLineAsync(device);
            if (line == null) return Fail("Windows didn't show the phone's call line. Turn the PC's Bluetooth off and on, then try again.");
            _line = line;

            var calls = await line.GetAllActivePhoneCallsAsync();
            Log.Write($"call audio: active calls {calls.OperationStatus}, {calls.AllActivePhoneCalls?.Count ?? 0}");
            var call = calls.AllActivePhoneCalls?.FirstOrDefault(c => c.Status == PhoneCallStatus.Talking)
                       ?? calls.AllActivePhoneCalls?.FirstOrDefault();
            if (call == null) return Fail("Windows doesn't see the call yet. Wait until it's connected, then try again.");

            var status = await call.ChangeAudioDeviceAsync(where);
            Log.Write($"call audio: move to {where} → {status}, now {call.AudioDevice}");
            if (status != PhoneCallOperationStatus.Succeeded)
                return Fail($"Windows couldn't move the call audio ({status}).");
            var onPc = call.AudioDevice == PhoneCallAudioDevice.LocalDevice;
            return new Result(true, onPc, onPc ? "Call audio is on this PC." : "Call audio is back on the phone.");
        }
        catch (UnauthorizedAccessException e)
        {
            Log.Write("call audio: denied: " + e.Message);
            return Fail("Windows only lets Store-packaged apps use Bluetooth call audio on this PC.");
        }
        catch (Exception e)
        {
            Log.Write("call audio: " + e);
            _device = null;
            _line = null;
            return Fail("Call audio on the PC didn't work: " + e.Message);
        }
    }

    private static Result Fail(string why)
    {
        Log.Write("call audio: " + why);
        return new Result(false, false, why);
    }

    private static string SafeRouting(PhoneLineTransportDevice d)
    {
        try { return d.AudioRoutingStatus.ToString(); } catch { return "?"; }
    }

    private async Task<PhoneLineTransportDevice?> FindDeviceAsync()
    {
        var all = await DeviceInformation.FindAllAsync(PhoneLineTransportDevice.GetDeviceSelector(PhoneLineTransport.Bluetooth));
        Log.Write("call audio: phone lines: " + string.Join(", ", all.Select(d => d.Name)));
        if (all.Count == 0) return null;
        var pick = all.FirstOrDefault(d => Matches(d.Name, PhoneName)) ?? all[0];
        return PhoneLineTransportDevice.FromId(pick.Id);
    }

    private static bool Matches(string a, string b) =>
        a.Length > 0 && b.Length > 0 && (a.Contains(b, StringComparison.OrdinalIgnoreCase) || b.Contains(a, StringComparison.OrdinalIgnoreCase));

    /// <summary>The PhoneLine whose transport is this Bluetooth device (waits up to 5 s for Windows to list lines).</summary>
    private static async Task<PhoneLine?> FindLineAsync(PhoneLineTransportDevice device)
    {
        var store = await PhoneCallManager.RequestStoreAsync();
        var watcher = store.RequestLineWatcher();
        var found = new TaskCompletionSource<PhoneLine?>(TaskCreationOptions.RunContinuationsAsynchronously);
        var bluetooth = new List<PhoneLine>();
        watcher.LineAdded += async (_, e) =>
        {
            try
            {
                var line = await PhoneLine.FromIdAsync(e.LineId);
                if (line.Transport != PhoneLineTransport.Bluetooth) return;
                Log.Write($"call audio: line {line.DisplayName} on {line.TransportDeviceId}");
                lock (bluetooth) bluetooth.Add(line);
                if (string.Equals(line.TransportDeviceId, device.DeviceId, StringComparison.OrdinalIgnoreCase)) found.TrySetResult(line);
            }
            catch (Exception ex) { Log.Write("call audio: line: " + ex.Message); }
        };
        watcher.EnumerationCompleted += (_, _) => _ = Task.Delay(1500).ContinueWith(_ => found.TrySetResult(null));
        watcher.Start();
        var done = await Task.WhenAny(found.Task, Task.Delay(5000));
        try { watcher.Stop(); } catch { }
        if (done == found.Task && found.Task.Result is { } exact) return exact;
        // The two ids aren't always written the same way; with only one Bluetooth phone line, that's the one.
        lock (bluetooth) return bluetooth.Count == 1 ? bluetooth[0] : null;
    }

    /// <summary>Forget the call line after a call, so the next call looks it up fresh.</summary>
    public void Reset() => _line = null;
}
