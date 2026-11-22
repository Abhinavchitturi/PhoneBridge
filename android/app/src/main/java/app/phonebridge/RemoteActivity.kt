package app.phonebridge

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.HapticFeedbackConstants
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot

/** Phone as trackpad, keyboard and slide clicker for the Mac. */
class RemoteActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var body: FrameLayout
    private val tabs = mutableListOf<TextView>()
    private var mode = 0 // 0 trackpad, 1 keyboard, 2 slides

    private val theme get() = ThemeStore.get(this)
    private val bg get() = theme.bg
    private val panel get() = theme.card
    private val chip get() = theme.chip
    private val text get() = theme.text
    private val muted get() = theme.muted
    private val brand get() = theme.accent
    private val onBrand get() = theme.onAccent

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(14), dp(10), dp(14), dp(14))
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(dp(14), bars.top + dp(10), dp(14), bars.bottom + dp(14))
                insets
            }
        }
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(panel, 100)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        listOf("Trackpad", "Keyboard", "Slides").forEachIndexed { i, name ->
            val t = label(name, 15f, text, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { show(i) }
            }
            tabs += t
            tabBar.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(tabBar)
        body = FrameLayout(this)
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })
        setContentView(root)
        show(savedInstanceState?.getInt("mode") ?: 0)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("mode", mode)
    }

    // Rotating doesn't restart the screen; just rebuild the current tab (the keyboard tab turns into a full keyboard in landscape).
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        show(mode)
    }

    private val landscape get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun show(i: Int) {
        mode = i
        tabs.forEachIndexed { j, t ->
            t.background = if (j == i) rounded(brand, 100) else null
            t.setTextColor(if (j == i) onBrand else muted)
        }
        body.removeAllViews()
        body.addView(when (i) { 0 -> trackpad(); 1 -> if (landscape) fullKeyboard() else keyboard(); else -> slides() })
        val imm = getSystemService(InputMethodManager::class.java)
        if (i != 1 || landscape) imm.hideSoftInputFromWindow(body.windowToken, 0)
    }

    // MARK: Trackpad

    private var pendingDx = 0f
    private var pendingDy = 0f
    private var flushScheduled = false

    // Coalesce movement into ~60 messages/second.
    private fun move(dx: Float, dy: Float) {
        pendingDx += dx
        pendingDy += dy
        if (flushScheduled) return
        flushScheduled = true
        main.postDelayed({
            flushScheduled = false
            if (pendingDx != 0f || pendingDy != 0f) {
                Link.send("input", "kind" to "move", "dx" to pendingDx.toDouble(), "dy" to pendingDy.toDouble())
                pendingDx = 0f
                pendingDy = 0f
            }
        }, 16)
    }

    /** When the last quick three-finger tap ended; three fingers down again soon after = zoom by dragging. */
    private var threeTapAt = 0L

    @SuppressLint("ClickableViewAccessibility")
    private fun trackpad(): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pad = TextView(this).apply {
            text = "One finger: move · tap to click\nTwo fingers: scroll · two-finger tap: right-click\n" +
                "Three fingers: swipe left/right to switch apps & desktops,\nup for Mission Control, down for App Exposé\n" +
                "Tap with three fingers, then tap-hold and drag up/down to zoom"
            setTextColor(muted)
            textSize = 13f
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
            setPadding(dp(16), 0, dp(16), 0)
            background = rounded(panel, 24)
        }
        val speed = 2.2f
        var lastX = 0f; var lastY = 0f
        var downTime = 0L; var travelled = 0f; var maxPointers = 1
        var resync = false
        // three-finger state: where the swipe started, whether it already fired, zoom mode and how far it has zoomed
        var startX = 0f; var startY = 0f; var swiped = false
        var zooming = false; var zoomAcc = 0f
        val swipeDistance = dp(55).toFloat()
        val zoomStep = dp(30).toFloat()
        pad.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.x; lastY = e.y; downTime = SystemClock.uptimeMillis(); travelled = 0f; maxPointers = 1
                    swiped = false; zooming = false; zoomAcc = 0f; resync = false
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    maxPointers = maxOf(maxPointers, e.pointerCount)
                    resync = true
                    if (e.pointerCount == 3) {
                        startX = avgX(e); startY = avgY(e)
                        zooming = SystemClock.uptimeMillis() - threeTapAt < 600
                        if (zooming) v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val x = if (e.pointerCount > 1) avgX(e) else e.x
                    val y = if (e.pointerCount > 1) avgY(e) else e.y
                    if (resync) { lastX = x; lastY = y; resync = false; return@setOnTouchListener true }
                    val dx = x - lastX; val dy = y - lastY
                    travelled += hypot(dx, dy)
                    when {
                        // Once three fingers touched, this touch is a gesture: never move or scroll as fingers lift.
                        maxPointers >= 3 -> if (e.pointerCount >= 3) {
                            if (zooming) {
                                zoomAcc += dy
                                while (abs(zoomAcc) >= zoomStep) {
                                    // fingers up = zoom in, down = zoom out
                                    Link.send("input", "kind" to "zoom", "dir" to if (zoomAcc < 0) "in" else "out")
                                    zoomAcc -= if (zoomAcc < 0) -zoomStep else zoomStep
                                    v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                }
                            } else if (!swiped) {
                                val tx = x - startX; val ty = y - startY
                                if (maxOf(abs(tx), abs(ty)) >= swipeDistance) {
                                    val dir = if (abs(tx) > abs(ty)) (if (tx < 0) "left" else "right") else (if (ty < 0) "up" else "down")
                                    Link.send("input", "kind" to "gesture", "fingers" to 3, "dir" to dir)
                                    v.performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                                    swiped = true
                                }
                            }
                        }
                        e.pointerCount > 1 -> Link.send("input", "kind" to "scroll", "dx" to (dx * 1.5).toDouble(), "dy" to (dy * 1.5).toDouble())
                        maxPointers == 1 -> move(dx * speed, dy * speed)
                    }
                    lastX = x; lastY = y
                }
                MotionEvent.ACTION_POINTER_UP -> resync = true
                MotionEvent.ACTION_UP -> {
                    val quick = SystemClock.uptimeMillis() - downTime < 300 && travelled < dp(16)
                    when {
                        maxPointers >= 3 -> threeTapAt = if (quick && !zooming) SystemClock.uptimeMillis() else 0L
                        quick -> click(if (maxPointers >= 2) "right" else "left")
                    }
                }
            }
            true
        }
        wrap.addView(pad, LinearLayout.LayoutParams(-1, 0, 1f))
        wrap.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(bigButton("Left click") { click("left") }, LinearLayout.LayoutParams(0, dp(64), 1f))
            addView(View(context), LinearLayout.LayoutParams(dp(10), 1))
            addView(bigButton("Right click") { click("right") }, LinearLayout.LayoutParams(0, dp(64), 1f))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        return wrap
    }

    private fun avgX(e: MotionEvent) = (0 until e.pointerCount).map { e.getX(it) }.average().toFloat()
    private fun avgY(e: MotionEvent) = (0 until e.pointerCount).map { e.getY(it) }.average().toFloat()
    private fun click(button: String) = Link.send("input", "kind" to "click", "button" to button)

    // MARK: Keyboard

    private var ignoreEdits = false

    private fun keyboard(): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, 0, dp(8))
            addView(label("Type here — it appears on your Mac", 14f, muted), LinearLayout.LayoutParams(0, -2, 1f))
            addView(bigButton("Full keyboard ⟳", size = 13f) {
                // Turn sideways for a full Mac keyboard with ⌘ ⌥ ⌃ ⇧, numbers and arrows.
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }.apply { setPadding(dp(14), dp(8), dp(14), dp(8)) })
        })
        val input = EditText(this).apply {
            setTextColor(this@RemoteActivity.text)
            textSize = 18f
            background = rounded(panel, 18)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            gravity = Gravity.TOP
            minLines = 4
        }
        // Soft keyboards don't send key events reliably, so diff the text: insertions → typed text, deletions → Backspace.
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                if (ignoreEdits) return
                repeat(before) { key("backspace") }
                if (count > 0) {
                    val typed = s.subSequence(start, start + count).toString()
                    typed.split("\n").forEachIndexed { i, part ->
                        if (i > 0) key("return")
                        if (part.isNotEmpty()) Link.send("input", "kind" to "type", "text" to part)
                    }
                }
            }
            override fun afterTextChanged(s: Editable) {
                if (s.length > 500) { ignoreEdits = true; s.clear(); ignoreEdits = false }
            }
        })
        wrap.addView(input, LinearLayout.LayoutParams(-1, 0, 1f))
        val keys = listOf("esc" to "Esc", "tab" to "Tab", "left" to "←", "up" to "↑", "down" to "↓", "right" to "→", "return" to "⏎")
        wrap.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            keys.forEachIndexed { i, (k, title) ->
                if (i > 0) addView(View(context), LinearLayout.LayoutParams(dp(6), 1))
                addView(bigButton(title, size = 15f) { key(k) }, LinearLayout.LayoutParams(0, dp(52), 1f))
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        input.requestFocus()
        main.postDelayed({ getSystemService(InputMethodManager::class.java).showSoftInput(input, 0) }, 200)
        return wrap
    }

    private fun key(name: String) = Link.send("input", "kind" to "key", "key" to name)

    // MARK: Full keyboard (landscape)

    /** Modifiers waiting for the next key (tap once), cleared after it. */
    private val mods = linkedSetOf<String>()
    private val modViews = HashMap<String, MutableList<TextView>>()
    private val charViews = mutableListOf<Pair<TextView, String>>()

    private val shifted = mapOf(
        "`" to "~", "1" to "!", "2" to "@", "3" to "#", "4" to "$", "5" to "%", "6" to "^", "7" to "&", "8" to "*",
        "9" to "(", "0" to ")", "-" to "_", "=" to "+", "[" to "{", "]" to "}", "\\" to "|", ";" to ":", "'" to "\"",
        "," to "<", "." to ">", "/" to "?",
    )

    private fun fullKeyboard(): View {
        mods.clear(); modViews.clear(); charViews.clear()
        // A key: label, what it does, and its width relative to a letter key.
        class K(val label: String, val weight: Float = 1f, val action: () -> Unit)
        fun ch(c: String) = K(c) { typeChar(c) }
        fun sp(label: String, name: String, w: Float) = K(label, w) { pressSpecial(name) }
        fun mod(label: String, name: String, w: Float) = K(label, w) { toggleMod(name) }
        val rows = listOf(
            listOf(sp("esc", "esc", 1.3f)) + "`1234567890-=".map { ch(it.toString()) } + sp("⌫", "backspace", 1.7f),
            listOf(sp("tab", "tab", 1.6f)) + "qwertyuiop[]".map { ch(it.toString()) } + ch("\\").let { K(it.label, 1.4f, it.action) },
            listOf(mod("control", "ctrl", 1.9f)) + "asdfghjkl;'".map { ch(it.toString()) } + sp("return", "return", 2.1f),
            listOf(mod("shift", "shift", 2.4f)) + "zxcvbnm,./".map { ch(it.toString()) } + sp("↑", "up", 1f) + mod("shift", "shift", 1.6f),
            listOf(
                K("▭ text", 1.5f) { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT },
                mod("option", "opt", 1.4f), mod("⌘ cmd", "cmd", 1.7f), sp("space", "space", 5.6f),
                mod("⌘ cmd", "cmd", 1.7f), mod("option", "opt", 1.4f),
                sp("←", "left", 1f), sp("↓", "down", 1f), sp("→", "right", 1f),
            ),
        )
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rows.forEach { row ->
            wrap.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                row.forEach { k ->
                    val v = label(k.label, if (k.label.length > 1) 12f else 17f, text, bold = k.label.length > 1).apply {
                        gravity = Gravity.CENTER
                        background = rounded(chip, 8)
                        isClickable = true
                        maxLines = 1
                        setOnClickListener { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); k.action() }
                    }
                    if (k.label.length == 1 && k.label !in setOf("↑", "↓", "←", "→")) charViews += v to k.label
                    rowMods(k.label)?.let { modViews.getOrPut(it) { mutableListOf() } += v }
                    addView(v, LinearLayout.LayoutParams(0, -1, k.weight).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
                }
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        refreshKeys()
        return wrap
    }

    private fun rowMods(label: String) = when (label) {
        "control" -> "ctrl"; "shift" -> "shift"; "option" -> "opt"; "⌘ cmd" -> "cmd"; else -> null
    }

    private fun toggleMod(name: String) {
        if (!mods.remove(name)) mods += name
        refreshKeys()
    }

    /** Highlights held modifiers and shows ⇧ characters while shift is on. */
    private fun refreshKeys() {
        modViews.forEach { (name, views) ->
            views.forEach { v ->
                val on = name in mods
                v.background = rounded(if (on) brand else chip, 8)
                v.setTextColor(if (on) onBrand else text)
            }
        }
        val shift = "shift" in mods
        charViews.forEach { (v, c) ->
            v.text = if (!shift) c else if (c[0].isLetter()) c.uppercase() else shifted[c] ?: c
        }
    }

    private fun typeChar(c: String) {
        val held = mods.toList()
        if (held.any { it != "shift" }) {
            // A shortcut such as ⌘C or ⌃⌥←: send the key with its modifiers.
            Link.send("input", "kind" to "key", "key" to c, "mods" to org.json.JSONArray(held))
        } else {
            val out = if ("shift" !in mods) c else if (c[0].isLetter()) c.uppercase() else shifted[c] ?: c
            Link.send("input", "kind" to "type", "text" to out)
        }
        if (mods.isNotEmpty()) { mods.clear(); refreshKeys() }
    }

    private fun pressSpecial(name: String) {
        if (name == "space" && mods.isEmpty()) Link.send("input", "kind" to "type", "text" to " ")
        else Link.send("input", "kind" to "key", "key" to name, "mods" to org.json.JSONArray(mods.toList()))
        if (mods.isNotEmpty()) { mods.clear(); refreshKeys() }
    }

    // MARK: Slides

    private fun slides(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(label("Volume buttons also work: down = next, up = back", 13f, muted).apply {
            gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(10))
        })
        addView(bigButton("Next  ▶", size = 26f, filled = true) { key("right") }, LinearLayout.LayoutParams(-1, 0, 2f))
        addView(View(context), LinearLayout.LayoutParams(1, dp(10)))
        addView(bigButton("◀  Back", size = 20f) { key("left") }, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(View(context), LinearLayout.LayoutParams(1, dp(10)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(bigButton("Black screen") { key("b") }, LinearLayout.LayoutParams(0, -1, 1f))
            addView(View(context), LinearLayout.LayoutParams(dp(10), 1))
            addView(bigButton("End show") { key("esc") }, LinearLayout.LayoutParams(0, -1, 1f))
        }, LinearLayout.LayoutParams(-1, dp(64)))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (mode == 2) when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> { key("right"); return true }
            KeyEvent.KEYCODE_VOLUME_UP -> { key("left"); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    // MARK: Helpers

    private fun bigButton(title: String, size: Float = 16f, filled: Boolean = false, onClick: () -> Unit) =
        label(title, size, text, bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded(if (filled) brand else chip, 18)
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { cornerRadius = dp(radius).toFloat(); setColor(color) }

    private fun label(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
