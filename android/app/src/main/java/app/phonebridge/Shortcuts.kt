package app.phonebridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * The trackpad's controls: the buttons under the trackpad, which the user makes and arranges, and what multi-finger
 * swipes do. A control presses a key shortcut, types a piece of text, or runs one of the computer's built-in actions.
 * Macs and Windows PCs each have their own controls (a phone used with both keeps both).
 *
 * On the wire, shortcuts and text use the keyboard's own messages ({"kind":"key","key","mods"} and {"kind":"type"}),
 * which every computer version understands; built-ins are {"kind":"action","id"}.
 */
object Shortcuts {
    const val KEYS = "keys"
    const val TEXT = "text"
    const val ACTION = "action"

    class Control(
        val id: String = UUID.randomUUID().toString(),
        var name: String = "",
        var icon: String = "★",
        var type: String = KEYS,
        var mods: List<String> = emptyList(),
        var key: String = "",
        var text: String = "",
        var action: String = "",
        /** A trackpad gesture that runs it, e.g. "4:swipe_up" (see [Gestures]); empty for none. */
        var gesture: String = "",
        /** Shown as a button under the trackpad (a gesture-only control isn't). */
        var onBar: Boolean = true,
    ) {
        fun copy() = Control(id, name, icon, type, mods.toList(), key, text, action, gesture, onBar)

        fun toJson() = JSONObject().put("id", id).put("name", name).put("icon", icon).put("type", type)
            .put("mods", JSONArray(mods)).put("key", key).put("text", text).put("action", action)
            .put("gesture", gesture).put("onBar", onBar)

        /** Ready to run: a shortcut needs a key, text needs text, a built-in needs its action. */
        val complete get() = when (type) { KEYS -> key.isNotEmpty(); TEXT -> text.isNotEmpty(); else -> action.isNotEmpty() }

        companion object {
            fun from(o: JSONObject) = Control(
                o.optString("id", UUID.randomUUID().toString()), o.optString("name"), o.optString("icon", "★"),
                o.optString("type", KEYS), o.optJSONArray("mods")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                o.optString("key"), o.optString("text"), o.optString("action"),
                o.optString("gesture"), o.optBoolean("onBar", true),
            )
        }
    }

    /** A modifier as the computer calls it on the wire, its symbol and its name. */
    class Mod(val wire: String, val symbol: String, val label: String)

    fun mods(platform: String) = if (platform == "windows")
        listOf(Mod("ctrl", "Ctrl", "Ctrl"), Mod("alt", "Alt", "Alt"), Mod("shift", "Shift", "Shift"), Mod("win", "Win", "Windows"))
    else
        listOf(Mod("ctrl", "⌃", "Control"), Mod("opt", "⌥", "Option"), Mod("shift", "⇧", "Shift"), Mod("cmd", "⌘", "Command"))

    /** Keys that can't be typed as a character, with how they show on a key cap. */
    val specialKeys = listOf(
        "tab" to "Tab", "return" to "Return", "space" to "Space", "esc" to "Esc", "backspace" to "⌫", "delete" to "Del",
        "left" to "←", "right" to "→", "up" to "↑", "down" to "↓",
        "home" to "Home", "end" to "End", "pageup" to "Pg Up", "pagedown" to "Pg Dn",
    ) + (1..12).map { "f$it" to "F$it" }

    /** Characters the computers know as keys (letters, digits and the usual punctuation). */
    fun isTypableKey(c: String) = c.length == 1 && (c[0] in 'a'..'z' || c[0] in '0'..'9' || c in "=-][';\\,./`")

    fun keyCap(key: String) = specialKeys.firstOrNull { it.first == key }?.second ?: key.uppercase()

    /** "⌘ ⇧ T" on a Mac, "Ctrl + Shift + T" on Windows. */
    fun caps(platform: String, c: Control): List<String> {
        val order = mods(platform)
        return order.filter { it.wire in c.mods }.map { it.symbol } + (if (c.key.isNotEmpty()) listOf(keyCap(c.key)) else emptyList())
    }

    /** One-line description of what a control does. */
    fun describe(platform: String, c: Control): String = when (c.type) {
        TEXT -> "Types “${c.text.take(24)}${if (c.text.length > 24) "…" else ""}”"
        ACTION -> builtIns(platform).firstOrNull { it.first == c.action }?.second ?: "Built-in"
        else -> caps(platform, c).joinToString(if (platform == "windows") " + " else " ").ifEmpty { "No key yet" }
    }

    // MARK: Built-in actions (the computers know these by id)

    /** (id, name, suggested icon). Mac: its trackpad gestures and tabs. Windows: its own touchpad gestures. */
    fun builtIns(platform: String): List<Triple<String, String, String>> = if (platform == "windows") listOf(
        Triple("switch_app", "Switch apps", "⇆"),
        Triple("task_view", "Task View", "▦"),
        Triple("show_desktop", "Show desktop", "▭"),
        Triple("prev_desktop", "Previous desktop", "◀"),
        Triple("next_desktop", "Next desktop", "▶"),
        Triple("search", "Search", "⌕"),
        Triple("notifications", "Notifications", "◉"),
    ) else listOf(
        Triple("prev_tab", "Previous tab", "◀"),
        Triple("next_tab", "Next tab", "▶"),
        Triple("mission_control", "Mission Control", "▦"),
        Triple("app_expose", "App Exposé", "▤"),
        Triple("prev_space", "Previous desktop", "⇤"),
        Triple("next_space", "Next desktop", "⇥"),
        Triple("show_desktop", "Show desktop", "▭"),
        Triple("back", "Back", "‹"),
        Triple("forward", "Forward", "›"),
        Triple("spotlight", "Spotlight", "⌕"),
    )

    private fun builtIn(platform: String, id: String, name: String? = null): Control {
        val b = builtIns(platform).first { it.first == id }
        return Control(id = "default-$id", name = name ?: b.second, icon = b.third, type = ACTION, action = id)
    }

    private fun defaults(platform: String): List<Control> = if (platform == "windows") listOf(
        builtIn(platform, "prev_desktop", "Desktop"), builtIn(platform, "next_desktop", "Desktop"),
        builtIn(platform, "task_view"), builtIn(platform, "show_desktop", "Desktop"), builtIn(platform, "search"),
    ) else listOf(
        builtIn(platform, "prev_tab", "Tab"), builtIn(platform, "next_tab", "Tab"), builtIn(platform, "mission_control", "Mission"),
        builtIn(platform, "prev_space", "Desktop"), builtIn(platform, "next_space", "Desktop"),
    )

    /** Icons to pick from in the editor. */
    val icons = listOf(
        "★", "◀", "▶", "‹", "›", "⇤", "⇥", "⇆", "▦", "▤", "▭", "⌕", "◉", "⏯", "⏭", "⏮",
        "🔊", "🔇", "📸", "✂️", "📋", "💾", "🗑", "🔒", "🌐", "📁", "✉️", "💬", "🎬", "🎵", "⚡", "🚀",
    )

    // MARK: Saved choices

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("trackpad", Context.MODE_PRIVATE)
    private fun kind(platform: String) = if (platform == "windows") "windows" else "mac"
    fun computerKind(platform: String) = if (platform == "windows") "Windows PC" else "Mac"

    fun controls(ctx: Context, platform: String): List<Control> {
        val saved = prefs(ctx).getString("${kind(platform)}.controls", null) ?: return defaults(platform)
        return try {
            val a = JSONArray(saved)
            (0 until a.length()).map { Control.from(a.getJSONObject(it)) }
        } catch (e: Exception) {
            defaults(platform)
        }
    }

    fun saveControls(ctx: Context, platform: String, list: List<Control>) =
        prefs(ctx).edit().putString("${kind(platform)}.controls", JSONArray(list.map { it.toJson() }).toString()).apply()

    fun upsert(ctx: Context, platform: String, c: Control) {
        val list = controls(ctx, platform).toMutableList()
        val i = list.indexOfFirst { it.id == c.id }
        if (i >= 0) list[i] = c else list.add(c)
        saveControls(ctx, platform, list)
    }

    fun remove(ctx: Context, platform: String, id: String) =
        saveControls(ctx, platform, controls(ctx, platform).filter { it.id != id })

    fun move(ctx: Context, platform: String, id: String, by: Int) {
        val list = controls(ctx, platform).toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + by
        if (i < 0 || j !in list.indices) return
        list.add(j, list.removeAt(i))
        saveControls(ctx, platform, list)
    }

    // MARK: Gestures of your own

    /** The control a gesture runs, if any. */
    fun controlFor(ctx: Context, platform: String, key: String) =
        controls(ctx, platform).firstOrNull { it.gesture == key && it.complete }

    /**
     * Who already uses this gesture (a control, or one of the built-in swipes), as the user would call it, or null
     * if it's free. [exceptId] is the control being edited.
     */
    fun gestureOwner(ctx: Context, platform: String, key: String, exceptId: String? = null): String? {
        controls(ctx, platform).firstOrNull { it.gesture == key && it.id != exceptId }?.let { return "“${it.name.ifEmpty { "Untitled" }}”" }
        val m = Gestures.motionOf(key)
        if (m.startsWith("swipe_") && Gestures.fingersOf(key) == fingers(ctx)) {
            val dir = m.removePrefix("swipe_")
            val v = swipe(ctx, platform, dir)
            if (v != NOTHING) return "the swipe $dir (${swipeLabel(ctx, platform, v).substringAfter("  ")})"
        }
        return null
    }

    // MARK: Swipes

    const val GESTURE = "gesture" // the computer's own trackpad default
    const val NOTHING = "none"
    val DIRS = listOf("left", "right", "up", "down")

    private val defaultSwipes = mapOf(
        "mac" to mapOf("left" to GESTURE, "right" to GESTURE, "up" to "action:mission_control", "down" to "action:app_expose"),
        "windows" to mapOf("left" to "action:switch_app", "right" to "action:switch_app", "up" to "action:task_view", "down" to "action:show_desktop"),
    )

    /** GESTURE, NOTHING, "action:<id>" for a built-in, or a control's id. */
    fun swipe(ctx: Context, platform: String, dir: String): String =
        prefs(ctx).getString("${kind(platform)}.swipe.$dir", null) ?: defaultSwipes.getValue(kind(platform)).getValue(dir)

    fun setSwipe(ctx: Context, platform: String, dir: String, value: String) =
        prefs(ctx).edit().putString("${kind(platform)}.swipe.$dir", value).apply()

    fun swipeLabel(ctx: Context, platform: String, value: String): String = when {
        value == GESTURE -> "The ${computerKind(platform)}'s own"
        value == NOTHING -> "Nothing"
        value.startsWith("action:") -> builtIns(platform).firstOrNull { it.first == value.removePrefix("action:") }?.second ?: "Nothing"
        else -> controls(ctx, platform).firstOrNull { it.id == value }?.let { "${it.icon}  ${it.name.ifEmpty { "Untitled" }}" } ?: "Nothing"
    }

    /** 3 or 4. Some phones take a screenshot on a three-finger swipe, so the trackpad never sees it: use four. */
    fun fingers(ctx: Context) = prefs(ctx).getInt("fingers", 3)
    fun setFingers(ctx: Context, n: Int) = prefs(ctx).edit().putInt("fingers", n).apply()

    fun reset(ctx: Context, platform: String) {
        val k = kind(platform)
        prefs(ctx).edit().apply {
            remove("$k.controls")
            DIRS.forEach { remove("$k.swipe.$it") }
        }.apply()
    }

    // MARK: Running

    fun run(c: Control) {
        when (c.type) {
            KEYS -> if (c.key.isNotEmpty()) Link.send("input", "kind" to "key", "key" to c.key, "mods" to JSONArray(c.mods))
            TEXT -> if (c.text.isNotEmpty()) Link.send("input", "kind" to "type", "text" to c.text)
            ACTION -> if (c.action.isNotEmpty()) Link.send("input", "kind" to "action", "id" to c.action)
        }
    }

    /** Runs what a swipe is set to; false for GESTURE (the caller sends the plain gesture). */
    fun runSwipe(ctx: Context, platform: String, value: String): Boolean {
        when {
            value == GESTURE -> return false
            value == NOTHING -> {}
            value.startsWith("action:") -> Link.send("input", "kind" to "action", "id" to value.removePrefix("action:"))
            else -> controls(ctx, platform).firstOrNull { it.id == value }?.let { run(it) }
        }
        return true
    }
}
