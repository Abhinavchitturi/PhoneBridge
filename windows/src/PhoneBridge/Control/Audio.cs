using System.Runtime.InteropServices;

namespace PhoneBridge.Control;

/// <summary>
/// Volume and mute of the default output device (Core Audio's IAudioEndpointVolume) — the Windows side of the Mac's
/// CoreAudio volume code. Every call looks the device up again, so switching speakers/headphones just works.
/// </summary>
internal static class Audio
{
    public static float? Volume
    {
        get => With(v => { v.GetMasterVolumeLevelScalar(out var f); return (float?)f; });
        set { if (value is { } f) With(v => { v.SetMasterVolumeLevelScalar(Math.Clamp(f, 0, 1), Guid.Empty); return 0; }); }
    }

    public static bool Muted
    {
        get => With(v => { v.GetMute(out var m); return m; });
        set => With(v => { v.SetMute(value, Guid.Empty); return 0; });
    }

    /// <summary>An id for the current output device, so "Find my PC" restores the volume only on the same speakers.</summary>
    public static string? DeviceId
    {
        get
        {
            try
            {
                var e = (IMMDeviceEnumerator)new MMDeviceEnumerator();
                e.GetDefaultAudioEndpoint(0 /* eRender */, 1 /* eMultimedia */, out var d);
                d.GetId(out var id);
                Marshal.ReleaseComObject(d);
                Marshal.ReleaseComObject(e);
                return id;
            }
            catch (Exception) { return null; }
        }
    }

    private static T? With<T>(Func<IAudioEndpointVolume, T> f)
    {
        IMMDeviceEnumerator? e = null;
        IMMDevice? d = null;
        IAudioEndpointVolume? v = null;
        try
        {
            e = (IMMDeviceEnumerator)new MMDeviceEnumerator();
            if (e.GetDefaultAudioEndpoint(0, 1, out d) != 0 || d == null) return default;
            var iid = typeof(IAudioEndpointVolume).GUID;
            if (d.Activate(ref iid, 1 /* CLSCTX_INPROC_SERVER */, IntPtr.Zero, out var o) != 0) return default;
            v = (IAudioEndpointVolume)o;
            return f(v);
        }
        catch (Exception ex) when (ex is COMException or InvalidCastException)
        {
            Log.Write("audio: " + ex.Message);
            return default;
        }
        finally
        {
            if (v != null) Marshal.ReleaseComObject(v);
            if (d != null) Marshal.ReleaseComObject(d);
            if (e != null) Marshal.ReleaseComObject(e);
        }
    }

    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    private class MMDeviceEnumerator;

    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDeviceEnumerator
    {
        int EnumAudioEndpoints(int dataFlow, int stateMask, out IntPtr devices);
        [PreserveSig] int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice device);
    }

    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDevice
    {
        [PreserveSig] int Activate(ref Guid iid, int clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object iface);
        int OpenPropertyStore(int access, out IntPtr store);
        int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);
        int GetState(out int state);
    }

    [ComImport, Guid("5CDF2C82-841E-4546-9722-0CF74078229A"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioEndpointVolume
    {
        int RegisterControlChangeNotify(IntPtr notify);
        int UnregisterControlChangeNotify(IntPtr notify);
        int GetChannelCount(out uint count);
        int SetMasterVolumeLevel(float levelDb, Guid context);
        int SetMasterVolumeLevelScalar(float level, Guid context);
        int GetMasterVolumeLevel(out float levelDb);
        int GetMasterVolumeLevelScalar(out float level);
        int SetChannelVolumeLevel(uint channel, float levelDb, Guid context);
        int SetChannelVolumeLevelScalar(uint channel, float level, Guid context);
        int GetChannelVolumeLevel(uint channel, out float levelDb);
        int GetChannelVolumeLevelScalar(uint channel, out float level);
        int SetMute([MarshalAs(UnmanagedType.Bool)] bool mute, Guid context);
        int GetMute([MarshalAs(UnmanagedType.Bool)] out bool mute);
    }
}
