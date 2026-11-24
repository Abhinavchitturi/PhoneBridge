package app.phonebridge

import android.content.Context

/**
 * The trackpad's shortcuts: the bar of buttons under the trackpad and what multi-finger swipes do. Each computer kind
 * has its own set — the Mac's trackpad gestures (plus tabs), and Windows' own touchpad gestures — and its own saved
 * choices, so a phone used with both keeps both. The phone sends {"t":"input","kind":"action","id":…}; the computer
 * presses the matching keys. [GESTURE] keeps the computer's own default for that swipe ({"kind":"gesture"}).
 */
object Shortcuts {
    class Action(val id: String, val short: String, val long: String)

    const val GESTURE = "gesture" // "like the computer's own trackpad"
    const val NOTHING = "none"
    const val MAX_BAR = 5
    val DIRS = listOf("left", "right", "up", "down")

    private val mac = listOf(
        Action("prev_tab", "‹ Tab", "Previous tab"),
        Action("next_tab", "Tab ›", "Next tab"),
        Action("mission_control", "Mission", "Mission Control"),
        Action("app_expose", "Exposé", "App Exposé"),
        Action("prev_space", "‹ Desk", "Previous desktop or full-screen app"),
        Action("next_space", "Desk ›", "Next desktop or full-screen app"),
        Action("show_desktop", "Desktop", "Show desktop"),
        Action("back", "‹ Back", "Back"),
        Action("forward", "Fwd ›", "Forward"),
        Action("spotlight", "Search", "Spotlight search"),
    )

    /** Windows' own touchpad gestures (Settings → Bluetooth & devices → Touchpad), as buttons. */
    private val windows = listOf(
        Action("switch_app", "Switch", "Switch apps"),
        Action("task_view", "Tasks", "Task View"),
        Action("show_desktop", "Desktop", "Show desktop"),
        Action("prev_desktop", "‹ Desk", "Previous desktop"),
        Action("next_desktop", "Desk ›", "Next desktop"),
        Action("search", "Search", "Search"),
        Action("notifications", "Alerts", "Notifications"),
    )

    fun catalog(platform: String) = if (platform == "windows") windows else mac
    fun find(platform: String, id: String) = catalog(platform).firstOrNull { it.id == id }
    fun computerKind(platform: String) = if (platform == "windows") "Windows PC" else "Mac"

    private val defaultBar = mapOf(
        "mac" to listOf("prev_tab", "next_tab", "mission_control", "prev_space", "next_space"),
        "windows" to listOf("prev_desktop", "next_desktop", "task_view", "show_desktop", "search"),
    )

    /** Mac: left/right follow the Mac's own trackpad setting (desktops or pages). Windows: its touchpad defaults. */
    private val defaultSwipes = mapOf(
        "mac" to mapOf("left" to GESTURE, "right" to GESTURE, "up" to "mission_control", "down" to "app_expose"),
        "windows" to mapOf("left" to "switch_app", "right" to "switch_app", "up" to "task_view", "down" to "show_desktop"),
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("trackpad", Context.MODE_PRIVATE)
    private fun kind(platform: String) = if (platform == "windows") "windows" else "mac"

    fun bar(ctx: Context, platform: String): List<String> {
        val saved = prefs(ctx).getString("${kind(platform)}.bar", null) ?: return defaultBar.getValue(kind(platform))
        return saved.split(',').filter { it.isNotEmpty() && find(platform, it) != null }
    }

    fun setBar(ctx: Context, platform: String, ids: List<String>) =
        prefs(ctx).edit().putString("${kind(platform)}.bar", ids.take(MAX_BAR).joinToString(",")).apply()

    fun swipe(ctx: Context, platform: String, dir: String): String =
        prefs(ctx).getString("${kind(platform)}.swipe.$dir", null) ?: defaultSwipes.getValue(kind(platform)).getValue(dir)

    fun setSwipe(ctx: Context, platform: String, dir: String, id: String) =
        prefs(ctx).edit().putString("${kind(platform)}.swipe.$dir", id).apply()

    /** 3 or 4. Some phones take a screenshot on a three-finger swipe, so the trackpad never sees it: use four. */
    fun fingers(ctx: Context) = prefs(ctx).getInt("fingers", 3)
    fun setFingers(ctx: Context, n: Int) = prefs(ctx).edit().putInt("fingers", n).apply()

    fun reset(ctx: Context, platform: String) {
        val k = kind(platform)
        prefs(ctx).edit().apply {
            remove("$k.bar")
            DIRS.forEach { remove("$k.swipe.$it") }
        }.apply()
    }

    /** What a swipe setting is called in the list. */
    fun swipeLabel(platform: String, id: String) = when (id) {
        GESTURE -> "Like the ${computerKind(platform)}'s trackpad"
        NOTHING -> "Nothing"
        else -> find(platform, id)?.long ?: "Nothing"
    }

    /** Runs a shortcut on the connected computer. */
    fun run(id: String) {
        if (id == NOTHING) return
        Link.send("input", "kind" to "action", "id" to id)
    }
}
