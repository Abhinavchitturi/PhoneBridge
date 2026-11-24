using System.Runtime.InteropServices;
using System.Text.Json.Nodes;
using PhoneBridge.Core;

namespace PhoneBridge.Control;

/// <summary>
/// The phone's trackpad, keyboard and clicker ("input" messages) as real mouse and keyboard input, via SendInput.
/// Port of mac/Sources/Input.swift. The phone speaks in Mac terms; this maps them to what a Windows user expects:
/// ⌘ → Ctrl (⌘C copies), but ⌘ held while tapping Tab → Alt+Tab, ⌘Q → Alt+F4, ⌘Space → search; ⌥ → Alt.
/// Three-finger swipes: left/right = back/forward, up = Task View, down = show desktop.
/// Windows doesn't let one app send input to an elevated (admin) window; that's the only permission-like limit.
/// </summary>
internal sealed class InputControl
{
    private static readonly Dictionary<string, ushort> Keys = BuildKeys();

    private static Dictionary<string, ushort> BuildKeys()
    {
        var k = new Dictionary<string, ushort>(StringComparer.OrdinalIgnoreCase)
        {
            ["return"] = 0x0D, ["enter"] = 0x0D, ["tab"] = 0x09, ["space"] = 0x20, ["backspace"] = 0x08, ["esc"] = 0x1B,
            ["delete"] = 0x2E, ["left"] = 0x25, ["up"] = 0x26, ["right"] = 0x27, ["down"] = 0x28,
            ["home"] = 0x24, ["end"] = 0x23, ["pageup"] = 0x21, ["pagedown"] = 0x22,
            ["="] = 0xBB, ["-"] = 0xBD, ["]"] = 0xDD, ["["] = 0xDB, ["'"] = 0xDE, [";"] = 0xBA, ["\\"] = 0xDC,
            [","] = 0xBC, ["/"] = 0xBF, ["."] = 0xBE, ["`"] = 0xC0,
        };
        for (var c = 'a'; c <= 'z'; c++) k[c.ToString()] = (ushort)char.ToUpperInvariant(c);
        for (var c = '0'; c <= '9'; c++) k[c.ToString()] = c;
        for (var i = 1; i <= 12; i++) k["f" + i] = (ushort)(0x70 + i - 1);
        return k;
    }

    private const ushort VkShift = 0x10, VkCtrl = 0x11, VkAlt = 0x12, VkWin = 0x5B;

    /// <summary>Modifiers the phone holds down. "cmd" stays unresolved until the next key says whether it's Alt or Ctrl.</summary>
    private readonly HashSet<string> _held = [];
    private readonly HashSet<ushort> _down = [];
    private System.Windows.Forms.Timer? _release;

    public void Handle(JsonObject m)
    {
        switch (PhoneLink.Str(m, "kind"))
        {
            case "move": Move(D(m, "dx"), D(m, "dy")); break;
            case "click": Click(PhoneLink.Str(m, "button") == "right"); break;
            case "scroll": Scroll(D(m, "dx"), D(m, "dy")); break;
            case "type": if (PhoneLink.Str(m, "text") is { } t) Type(t); break;
            case "key":
                var mods = m["mods"] is JsonArray a ? a.Select(x => x?.GetValue<string>() ?? "").ToList() : [];
                if (PhoneLink.Str(m, "key") is { } key) Press(key, mods);
                break;
            case "mod":
                if (PhoneLink.Str(m, "key") is { } name) SetModifier(name, m["down"] is JsonValue dv && dv.TryGetValue<bool>(out var down) && down);
                break;
            case "gesture": Gesture(PhoneLink.Str(m, "dir") ?? ""); break;
            case "action": RunAction(PhoneLink.Str(m, "id") ?? ""); break;
            case "zoom": Press(PhoneLink.Str(m, "dir") == "out" ? "-" : "=", ["ctrl"]); break;
        }
    }

    private static double D(JsonObject m, string k) => m[k] is JsonValue v && v.TryGetValue<double>(out var d) ? d : 0;

    // MARK: Mouse

    private static void Move(double dx, double dy)
    {
        // Relative moves go through Windows' pointer speed and acceleration, like a real mouse.
        var speed = 1.0;
        Send(Mouse(MouseeventfMove, (int)Math.Round(dx * speed), (int)Math.Round(dy * speed)));
    }

    private static void Click(bool right)
    {
        Send(Mouse(right ? MouseeventfRightdown : MouseeventfLeftdown), Mouse(right ? MouseeventfRightup : MouseeventfLeftup));
    }

    /// <summary>The phone sends how far the fingers moved ("natural" scrolling, like a touchpad on Windows too).</summary>
    private static void Scroll(double dx, double dy)
    {
        var inputs = new List<INPUT>();
        if (Math.Abs(dy) >= 0.5) inputs.Add(Mouse(MouseeventfWheel, 0, 0, (int)Math.Round(dy * 2)));
        if (Math.Abs(dx) >= 0.5) inputs.Add(Mouse(MouseeventfHwheel, 0, 0, (int)Math.Round(-dx * 2)));
        if (inputs.Count > 0) Send([.. inputs]);
    }

    // MARK: Keyboard

    private static void Type(string text)
    {
        var inputs = new List<INPUT>();
        foreach (var ch in text)
        {
            if (ch == '\n') { inputs.Add(Key(0x0D, false)); inputs.Add(Key(0x0D, true)); continue; }
            inputs.Add(Unicode(ch, false));
            inputs.Add(Unicode(ch, true));
        }
        Send([.. inputs]);
    }

    /// <summary>One key with modifiers, translated from Mac shortcuts to their Windows equivalents.</summary>
    public void Press(string name, IEnumerable<string> mods)
    {
        var set = new HashSet<string>(mods, StringComparer.OrdinalIgnoreCase);
        set.UnionWith(_held);
        var key = name.ToLowerInvariant();
        var cmd = set.Remove("cmd");

        if (cmd)
        {
            switch (key)
            {
                case "tab":
                    // ⌘Tab: if ⌘ is held on the phone, keep Alt down so repeated Tabs walk the app list.
                    if (_held.Contains("cmd")) HoldVk(VkAlt);
                    Chord(set.Contains("shift") ? [VkAlt, VkShift] : [VkAlt], 0x09, keepHeld: _held.Contains("cmd"));
                    return;
                case "q": Chord([VkAlt], 0x73); return;                  // ⌘Q → Alt+F4
                case "space": Chord([VkWin], (ushort)'S'); return;       // ⌘Space (Spotlight) → Windows search
                case "left": Chord(set.Contains("shift") ? [VkShift] : [], 0x24); return;  // ⌘← → Home (line start)
                case "right": Chord(set.Contains("shift") ? [VkShift] : [], 0x23); return; // ⌘→ → End
                case "up": Chord(set.Contains("shift") ? [VkCtrl, VkShift] : [VkCtrl], 0x24); return;   // ⌘↑ → Ctrl+Home
                case "down": Chord(set.Contains("shift") ? [VkCtrl, VkShift] : [VkCtrl], 0x23); return; // ⌘↓ → Ctrl+End
                case "backspace": Chord([VkShift], 0x24); Chord([], 0x2E); return; // ⌘⌫ delete to line start
            }
            set.Add("ctrl");
        }
        if (!Keys.TryGetValue(key, out var vk)) return;
        var vks = new List<ushort>();
        if (set.Contains("ctrl")) vks.Add(VkCtrl);
        if (set.Contains("shift")) vks.Add(VkShift);
        if (set.Contains("opt") || set.Contains("alt")) vks.Add(VkAlt);
        if (set.Contains("win")) vks.Add(VkWin); // a control made on the phone for this PC (e.g. Win+Shift+S)
        Chord([.. vks], vk);
    }

    private void Chord(ushort[] mods, ushort vk, bool keepHeld = false)
    {
        var inputs = new List<INPUT>();
        foreach (var m in mods) if (!_down.Contains(m)) inputs.Add(Key(m, false));
        inputs.Add(Key(vk, false));
        inputs.Add(Key(vk, true));
        if (!keepHeld)
            foreach (var m in mods.Reverse()) if (!_down.Contains(m)) inputs.Add(Key(m, true));
        Send([.. inputs]);
    }

    private void SetModifier(string name, bool down)
    {
        if (down) _held.Add(name);
        else if (_held.Remove(name))
        {
            // Let go of whatever that modifier turned into (Alt after ⌘Tab, etc.).
            if (name == "cmd") { ReleaseVk(VkAlt); ReleaseVk(VkCtrl); }
            if (name == "shift") ReleaseVk(VkShift);
            if (name == "opt") ReleaseVk(VkAlt);
            if (name == "ctrl") ReleaseVk(VkCtrl);
        }
        // Never leave a modifier stuck down if the phone goes quiet.
        _release?.Dispose();
        _release = null;
        if (_held.Count == 0) return;
        _release = new System.Windows.Forms.Timer { Interval = 30_000 };
        _release.Tick += (_, _) => ReleaseModifiers();
        _release.Start();
    }

    private void HoldVk(ushort vk)
    {
        if (_down.Add(vk)) Send(Key(vk, false));
    }

    private void ReleaseVk(ushort vk)
    {
        if (_down.Remove(vk)) Send(Key(vk, true));
    }

    /// <summary>Lets go of anything the phone is holding (on disconnect and by the safety timer).</summary>
    public void ReleaseModifiers()
    {
        _held.Clear();
        foreach (var vk in _down.ToList()) ReleaseVk(vk);
        _release?.Dispose();
        _release = null;
    }

    private void Gesture(string dir)
    {
        switch (dir)
        {
            // Like Windows' own three-finger touchpad gestures.
            case "left": case "right": RunAction("switch_app"); break;
            case "up": RunAction("task_view"); break;
            case "down": RunAction("show_desktop"); break;
        }
    }

    /// <summary>A trackpad shortcut button (or a swipe set to one) on the phone: Windows' touchpad gestures as keys.</summary>
    private void RunAction(string id)
    {
        switch (id)
        {
            case "switch_app": Chord([VkAlt], 0x09); break;                 // Alt+Tab: the last app
            case "task_view": Chord([VkWin], 0x09); break;                  // Win+Tab
            case "show_desktop": Chord([VkWin], (ushort)'D'); break;        // Win+D
            case "prev_desktop": Chord([VkWin, VkCtrl], 0x25); break;       // Win+Ctrl+←
            case "next_desktop": Chord([VkWin, VkCtrl], 0x27); break;       // Win+Ctrl+→
            case "search": Chord([VkWin], (ushort)'S'); break;              // Win+S
            case "notifications": Chord([VkWin], (ushort)'N'); break;       // Win+N
        }
    }

    /// <summary>Presses a named key (with Windows modifiers) in the app in front: used by the video controls.</summary>
    public static void Tap(string name, params ushort[] mods)
    {
        if (!Keys.TryGetValue(name, out var vk)) return;
        var inputs = new List<INPUT>();
        foreach (var m in mods) inputs.Add(Key(m, false));
        inputs.Add(Key(vk, false));
        inputs.Add(Key(vk, true));
        foreach (var m in mods.Reverse()) inputs.Add(Key(m, true));
        Send([.. inputs]);
    }

    public static ushort Alt => VkAlt;
    public static ushort Ctrl => VkCtrl;

    /// <summary>Media keys (fallback when no app is reachable through Windows' media controls).</summary>
    public static void MediaKey(string cmd)
    {
        ushort vk = cmd switch { "next" => 0xB0, "prev" => 0xB1, _ => 0xB3 };
        Send(Key(vk, false), Key(vk, true));
    }

    // MARK: SendInput

    private const uint MouseeventfMove = 0x0001, MouseeventfLeftdown = 0x0002, MouseeventfLeftup = 0x0004,
        MouseeventfRightdown = 0x0008, MouseeventfRightup = 0x0010, MouseeventfWheel = 0x0800, MouseeventfHwheel = 0x1000;
    private const uint KeyeventfExtended = 0x0001, KeyeventfKeyup = 0x0002, KeyeventfUnicode = 0x0004;

    private static readonly HashSet<ushort> Extended = [0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x2D, 0x2E, 0x5B];

    private static INPUT Mouse(uint flags, int dx = 0, int dy = 0, int data = 0) => new()
    {
        type = 0,
        U = new InputUnion { mi = new MOUSEINPUT { dx = dx, dy = dy, mouseData = data, dwFlags = flags } },
    };

    private static INPUT Key(ushort vk, bool up) => new()
    {
        type = 1,
        U = new InputUnion
        {
            ki = new KEYBDINPUT { wVk = vk, dwFlags = (up ? KeyeventfKeyup : 0) | (Extended.Contains(vk) ? KeyeventfExtended : 0) },
        },
    };

    private static INPUT Unicode(char c, bool up) => new()
    {
        type = 1,
        U = new InputUnion { ki = new KEYBDINPUT { wScan = c, dwFlags = KeyeventfUnicode | (up ? KeyeventfKeyup : 0) } },
    };

    private static void Send(params INPUT[] inputs)
    {
        if (inputs.Length == 0) return;
        if (SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<INPUT>()) != inputs.Length)
            Log.Write($"SendInput blocked ({Marshal.GetLastWin32Error()}): the app in front may be running as administrator");
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint count, INPUT[] inputs, int size);

    [StructLayout(LayoutKind.Sequential)]
    private struct INPUT
    {
        public uint type;
        public InputUnion U;
    }

    [StructLayout(LayoutKind.Explicit)]
    private struct InputUnion
    {
        [FieldOffset(0)] public MOUSEINPUT mi;
        [FieldOffset(0)] public KEYBDINPUT ki;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MOUSEINPUT
    {
        public int dx, dy, mouseData;
        public uint dwFlags, time;
        public IntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct KEYBDINPUT
    {
        public ushort wVk, wScan;
        public uint dwFlags, time;
        public IntPtr dwExtraInfo;
    }
}
