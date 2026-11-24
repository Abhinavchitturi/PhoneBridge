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
        // Upright by default; only the full keyboard turns the screen sideways.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
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

    private fun fullKeyboardOn() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backToVertical)
    }

    /** Leaving the full keyboard (Vertical key, Back, another tab): stand the screen back up. */
    private fun vertical() {
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backToVertical)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    private val backToVertical = android.window.OnBackInvokedCallback { vertical() }

    // Older back handling (Android 13–15 without predictive back): same thing.
    @Deprecated("Kept for Android versions that still deliver it")
    override fun onBackPressed() {
        if (requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) vertical() else super.onBackPressed()
    }

    private fun show(i: Int) {
        releaseHeld()
        if (i != 1 && requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) vertical()
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

    /** The trackpad's hint lines: the gestures as this computer's settings have them. */
    private fun trackpadHint(platform: String, n: Int): List<String> {
        val word = if (n == 4) "4 fingers" else "3 fingers"
        fun what(d: String): String {
            val v = Shortcuts.swipe(this, platform, d)
            return if (v == Shortcuts.GESTURE) (if (platform == "windows") "Windows default" else if (d == "left" || d == "right") "desktops" else "Mac default")
            else Shortcuts.swipeLabel(this, platform, v).substringAfter("  ")
        }
        val lr = if (what("left") == what("right")) "← → ${what("left")}" else "← ${what("left")} · → ${what("right")}"
        val own = Shortcuts.controls(this, platform).count { it.gesture.isNotEmpty() }
        return listOfNotNull(
            "1 finger: move, tap to click  ·  2 fingers: scroll, pinch to zoom",
            "$word: $lr · ↑ ${what("up")} · ↓ ${what("down")}",
            if (own > 0) "+ $own gesture${if (own == 1) "" else "s"} of your own" else null,
        )
    }

    /** Runs a 3+-finger gesture: your own control for it first, then (for swipes) the built-in swipe. */
    private fun runGesture(platform: String, key: String, n: Int, pad: TrackpadSurface): Boolean {
        Shortcuts.controlFor(this, platform, key)?.let { Shortcuts.run(it); pad.pulse(); return true }
        val m = Gestures.motionOf(key)
        if (m.startsWith("swipe_") && Gestures.fingersOf(key) == n) {
            val dir = m.removePrefix("swipe_")
            if (!Shortcuts.runSwipe(this, platform, Shortcuts.swipe(this, platform, dir)))
                Link.send("input", "kind" to "gesture", "fingers" to n, "dir" to dir)
            pad.pulse()
            return true
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun trackpad(): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val platform = Link.connectedPlatform
        // Three or four fingers for the built-in swipes (some phones take a screenshot on a three-finger swipe).
        val n = Shortcuts.fingers(this)
        val pad = TrackpadSurface(this, panel, brand, muted).apply {
            hint = trackpadHint(platform, n)
            title = if (Link.isConnected) Link.computerName.uppercase() else "NOT CONNECTED"
            places = Shortcuts.usedPlaces(this@RemoteActivity, platform)
        }
        val speed = 2.2f
        // A touch that starts in a corner or at an edge you've given a gesture is read as that gesture, not as moving.
        var inPlace = false
        var activePlace: String? = null
        val density = resources.displayMetrics.density
        fun hasHere(f: Int, motion: String, place: String) = Shortcuts.controlFor(this, platform, Gestures.key(f, motion, place)) != null
        var lastX = 0f; var lastY = 0f
        var downTime = 0L; var travelled = 0f; var maxPointers = 1
        var resync = false
        // Two fingers: scroll or pinch, decided by the first few millimetres (spread vs. slide), then kept.
        var twoMode = 0 // 0 undecided, 1 scroll, 2 pinch
        var lastSpread = 0f; var spreadTotal = 0f; var slideTotal = 0f; var zoomAcc = 0f
        val zoomStep = dp(38).toFloat()
        val pairer = TapPairer({ f, place -> Shortcuts.controlFor(this, platform, Gestures.key(f, "double_tap", place)) != null }) { key ->
            if (runGesture(platform, key, n, pad)) pad.performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
        }
        val reader = MultiFingerReader(dp(55).toFloat(), dp(18).toFloat(),
            onSwipe = { f, dir ->
                val p = activePlace
                // A swipe in from an edge with a 3-finger gesture set there is that gesture (the place reader has it).
                val edgeHasIt = p != null && !Gestures.isCorner(p) && dir == Gestures.inward(p) && hasHere(f, "edge_in", p)
                if (!edgeHasIt && runGesture(platform, Gestures.key(f, "swipe_$dir"), n, pad)) pad.performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
            },
            onTap = { f ->
                val p = activePlace
                val cornerHasIt = p != null && Gestures.isCorner(p) && (hasHere(f, "tap", p) || hasHere(f, "double_tap", p))
                if (!cornerHasIt) pairer.tap(f)
            })
        val placeReader = PlaceReader(dp(50).toFloat(), dp(14).toFloat(),
            onGesture = { f, motion, place ->
                val ran = f <= Gestures.PLACE_FINGERS && runGesture(platform, Gestures.key(f, motion, place), n, pad)
                if (ran) pad.performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                ran
            },
            onTap = { f, place ->
                // Nothing set for this tap here: it's an ordinary click after all (three fingers: the usual 3-finger tap).
                if (f <= Gestures.PLACE_FINGERS && (hasHere(f, "tap", place) || hasHere(f, "double_tap", place))) pairer.tap(f, place)
                else if (f <= 2) click(if (f >= 2) "right" else "left")
            })
        fun spread(e: MotionEvent) = if (e.pointerCount >= 2) hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)) else 0f
        pad.setOnTouchListener { v, e ->
            pad.track(e)
            reader.feed(e)
            if (inPlace) {
                placeReader.feed(e)
                // Started in a corner or edge but isn't a gesture (wandered off, slid along the edge): carry on as usual.
                if (placeReader.abandoned && e.actionMasked == MotionEvent.ACTION_MOVE) { inPlace = false; activePlace = null; resync = true }
            }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.x; lastY = e.y; downTime = SystemClock.uptimeMillis(); travelled = 0f; maxPointers = 1
                    resync = false; twoMode = 0; spreadTotal = 0f; slideTotal = 0f; zoomAcc = 0f
                    val place = Gestures.placeAt(e.x, e.y, v.width.toFloat(), v.height.toFloat(), density)
                    inPlace = place != null && place in Shortcuts.usedPlaces(this, platform)
                    activePlace = if (inPlace) place else null
                    if (inPlace) placeReader.start(e, place!!)
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    maxPointers = maxOf(maxPointers, e.pointerCount)
                    resync = true
                }
                MotionEvent.ACTION_MOVE -> {
                    val x = if (e.pointerCount > 1) avgX(e) else e.x
                    val y = if (e.pointerCount > 1) avgY(e) else e.y
                    if (resync) { lastX = x; lastY = y; lastSpread = spread(e); resync = false; return@setOnTouchListener true }
                    val dx = x - lastX; val dy = y - lastY
                    travelled += hypot(dx, dy)
                    when {
                        // Three fingers or more: a gesture (the reader handles it); never move or scroll as fingers lift.
                        maxPointers >= 3 -> {}
                        // Started in a corner or edge with a gesture: the place reader has it.
                        inPlace -> {}
                        e.pointerCount == 2 -> {
                            val s = spread(e)
                            val ds = s - lastSpread
                            lastSpread = s
                            if (twoMode == 0) {
                                spreadTotal += ds; slideTotal += hypot(dx, dy)
                                if (abs(spreadTotal) > dp(16) && abs(spreadTotal) > slideTotal * 1.2f) {
                                    twoMode = 2; v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                } else if (slideTotal > dp(10)) twoMode = 1
                            }
                            when (twoMode) {
                                1 -> Link.send("input", "kind" to "scroll", "dx" to (dx * 1.5).toDouble(), "dy" to (dy * 1.5).toDouble())
                                2 -> {
                                    // Like a Mac trackpad: fingers apart = zoom in, together = zoom out.
                                    zoomAcc += ds
                                    while (abs(zoomAcc) >= zoomStep) {
                                        Link.send("input", "kind" to "zoom", "dir" to if (zoomAcc > 0) "in" else "out")
                                        zoomAcc -= if (zoomAcc > 0) zoomStep else -zoomStep
                                        v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    }
                                }
                            }
                        }
                        maxPointers == 1 -> move(dx * speed, dy * speed)
                    }
                    lastX = x; lastY = y
                }
                MotionEvent.ACTION_POINTER_UP -> resync = true
                MotionEvent.ACTION_UP -> {
                    val quick = SystemClock.uptimeMillis() - downTime < 300 && travelled < dp(16)
                    if (!inPlace && maxPointers < 3 && quick && twoMode != 2) click(if (maxPointers >= 2) "right" else "left")
                    inPlace = false
                }
            }
            true
        }
        wrap.addView(pad, LinearLayout.LayoutParams(-1, 0, 1f))
        wrap.addView(controlBar(platform), LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12) })
        wrap.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(clickButton("Left click", primary = true) { click("left") }, LinearLayout.LayoutParams(0, dp(60), 1f))
            addView(View(context), LinearLayout.LayoutParams(dp(10), 1))
            addView(clickButton("Right click", primary = false) { click("right") }, LinearLayout.LayoutParams(0, dp(60), 1f))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        return wrap
    }

    // MARK: Controls — the bar under the trackpad, made by the user

    private fun clickButton(title: String, primary: Boolean, onClick: () -> Unit) = label(title, 15f, text, bold = true).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(mix(chip, android.graphics.Color.WHITE, if (primary) 0.06f else 0.03f), chip)).apply {
            cornerRadius = dp(22).toFloat(); setStroke(dp(1), mix(chip, android.graphics.Color.WHITE, 0.08f))
        }
        isClickable = true
        pressable()
        setOnClickListener { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onClick() }
    }

    /** Shrinks a little while pressed, like a real button. */
    private fun View.pressable() {
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(70).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false
        }
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = ((a shr s and 0xFF) * (1 - t) + (b shr s and 0xFF) * t).toInt() shl s
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }

    private fun tint(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)

    /** One control as a pill: its icon and name. */
    private fun controlPill(c: Shortcuts.Control, big: Boolean = false): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(if (big) 20 else 14), 0, dp(if (big) 22 else 16), 0)
        background = GradientDrawable().apply {
            cornerRadius = dp(100).toFloat()
            setColor(mix(chip, brand, 0.14f))
            setStroke(dp(1), tint(brand, 70))
        }
        addView(label(c.icon, if (big) 20f else 16f, brand).apply { setPadding(0, 0, dp(8), 0) })
        addView(label(c.name.ifEmpty { "Untitled" }, if (big) 16f else 13.5f, text, bold = true).apply { maxLines = 1 })
    }

    /** The bar: your controls (scrolls sideways if there are many), + to make one, ⚙ to arrange them and set swipes. */
    private fun controlBar(platform: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        for (c in Shortcuts.controls(this@RemoteActivity, platform).filter { it.onBar }) {
            row.addView(controlPill(c).apply {
                isClickable = true
                pressable()
                setOnClickListener {
                    if (c.complete) { Shortcuts.run(c); performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
                    else editControl(platform, c.copy(), isNew = false)
                }
                setOnLongClickListener { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); editControl(platform, c.copy(), isNew = false); true }
                contentDescription = "${c.name}: ${Shortcuts.describe(platform, c)}. Long-press to edit."
            }, LinearLayout.LayoutParams(-2, -1).apply { marginEnd = dp(8) })
        }
        row.addView(label("+", 22f, brand, bold = true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(100).toFloat(); setColor(android.graphics.Color.TRANSPARENT)
                setStroke(dp(1), tint(brand, 110), dp(5).toFloat(), dp(4).toFloat())
            }
            isClickable = true
            pressable()
            contentDescription = "Make a control"
            setOnClickListener { editControl(platform, Shortcuts.Control(), isNew = true) }
        }, LinearLayout.LayoutParams(dp(52), -1))
        addView(android.widget.HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isHorizontalFadingEdgeEnabled = true // fades at the ends: there's more to scroll to
            setFadingEdgeLength(dp(28))
            addView(row, android.widget.FrameLayout.LayoutParams(-2, -1))
        }, LinearLayout.LayoutParams(0, -1, 1f))
        addView(View(context), LinearLayout.LayoutParams(dp(8), 1))
        addView(label("⚙", 18f, text).apply {
            gravity = Gravity.CENTER
            background = rounded(chip, 100)
            isClickable = true
            pressable()
            contentDescription = "Arrange controls and set swipes"
            setOnClickListener { customize(platform) }
        }, LinearLayout.LayoutParams(dp(52), -1))
    }

    // MARK: Customize screen (arrange controls, swipes)

    /** Where a settings list was scrolled to, so changing something doesn't jump back to the top. */
    private var customizeScroll = 0

    private fun sectionTitle(s: String) = label(s, 12.5f, muted, bold = true).apply { letterSpacing = 0.08f; setPadding(dp(6), dp(22), 0, dp(8)) }

    private fun note(s: String) = label(s, 13f, muted).apply { setPadding(dp(6), 0, dp(6), dp(10)); setLineSpacing(0f, 1.2f) }

    /** A card holding rows, with hairlines between them (like an iOS/One UI grouped list). */
    private fun card(rows: List<View>) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(panel, 20)
        rows.forEachIndexed { i, r ->
            if (i > 0) addView(View(context).apply { setBackgroundColor(tint(text, 18)) }, LinearLayout.LayoutParams(-1, dp(1)).apply { marginStart = dp(18) })
            addView(r)
        }
    }

    private fun settingRow(title: String, value: String? = null, sub: String? = null, icon: String? = null, trailing: View? = null, onClick: (() -> Unit)? = null) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(13), dp(14), dp(13))
            if (onClick != null) { isClickable = true; setOnClickListener { onClick() } }
            if (icon != null) addView(label(icon, 17f, brand).apply {
                gravity = Gravity.CENTER
                background = rounded(mix(panel, brand, 0.16f), 12)
            }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(14) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(title, 15.5f, text))
                if (sub != null) addView(label(sub, 12.5f, muted).apply { setPadding(0, dp(2), 0, 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (value != null) addView(label(value, 14f, muted).apply { maxLines = 1 })
            if (trailing != null) addView(trailing)
        }

    private fun pageHeader(title: String, leftText: String?, onLeft: (() -> Unit)?, rightText: String, onRight: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
            if (leftText != null) addView(label(leftText, 15f, brand).apply {
                setPadding(dp(4), dp(8), dp(12), dp(8)); isClickable = true; setOnClickListener { onLeft?.invoke() }
            })
            addView(label(title, 20f, text, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(label(rightText, 14.5f, onBrand, bold = true).apply {
                gravity = Gravity.CENTER
                background = rounded(brand, 100)
                setPadding(dp(20), dp(9), dp(20), dp(9))
                isClickable = true
                pressable()
                setOnClickListener { onRight() }
            })
        }

    private fun page(header: View, content: LinearLayout) {
        body.removeAllViews()
        val scroller = android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(content)
            val y = customizeScroll
            post { scrollTo(0, y) }
            setOnScrollChangeListener { _, _, sy, _, _ -> customizeScroll = sy }
        }
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        })
    }

    private fun customize(platform: String, keepScroll: Boolean = false) {
        if (!keepScroll) customizeScroll = 0
        fun redraw() = customize(platform, keepScroll = true)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(24)) }

        // Your controls: tap to edit, arrows to reorder.
        list.addView(sectionTitle("YOUR CONTROLS"))
        list.addView(note("The buttons under the trackpad. Make your own: a key shortcut, a piece of text, or one of the ${Shortcuts.computerKind(platform)}'s actions. Long-press a button on the trackpad to edit it."))
        val controls = Shortcuts.controls(this, platform)
        val rows = controls.mapIndexed { i, c ->
            val arrows = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                fun arrow(s: String, enabled: Boolean, by: Int) = label(s, 16f, if (enabled) text else tint(muted, 70)).apply {
                    gravity = Gravity.CENTER
                    if (enabled) { isClickable = true; setOnClickListener { Shortcuts.move(this@RemoteActivity, platform, c.id, by); redraw() } }
                }
                addView(arrow("↑", i > 0, -1), LinearLayout.LayoutParams(dp(34), dp(34)))
                addView(arrow("↓", i < controls.size - 1, 1), LinearLayout.LayoutParams(dp(34), dp(34)))
            }
            val sub = listOfNotNull(Shortcuts.describe(platform, c), c.gesture.takeIf { it.isNotEmpty() }?.let { Gestures.describe(it) },
                if (!c.onBar) "not on the bar" else null).joinToString("  ·  ")
            settingRow(c.name.ifEmpty { "Untitled" }, sub = sub, icon = c.icon, trailing = arrows) {
                editControl(platform, c.copy(), isNew = false)
            }
        }
        if (rows.isNotEmpty()) list.addView(card(rows))
        list.addView(label("+  Make a control", 15f, onBrand, bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded(brand, 18)
            isClickable = true
            pressable()
            setOnClickListener { editControl(platform, Shortcuts.Control(), isNew = true) }
        }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(12) })

        // Gestures of your own.
        list.addView(sectionTitle("YOUR GESTURES"))
        list.addView(note("Give any control a gesture: anywhere with 3, 4 or 5 fingers (tap, double-tap, swipe), or in a corner or at an edge with 1, 2 or 3 fingers. Two fingers pinch to zoom, like a Mac trackpad."))
        val withGesture = controls.filter { it.gesture.isNotEmpty() }
        if (withGesture.isNotEmpty()) list.addView(card(withGesture.map { c ->
            settingRow(Gestures.describe(c.gesture), sub = "${c.name.ifEmpty { "Untitled" }} — ${Shortcuts.describe(platform, c)}", icon = c.icon) {
                editControl(platform, c.copy(), isNew = false)
            }
        }), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        list.addView(label("+  New gesture", 15f, text, bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded(chip, 18)
            isClickable = true
            pressable()
            setOnClickListener { editControl(platform, Shortcuts.Control(onBar = false), isNew = true) }
        }, LinearLayout.LayoutParams(-1, dp(50)))

        // Swipes.
        list.addView(sectionTitle("SWIPES"))
        list.addView(note("If a three-finger swipe takes a screenshot on your phone, swipe with four fingers — or turn off the phone's three-finger screenshot in its Settings."))
        val n = Shortcuts.fingers(this)
        list.addView(segmented(listOf("3 fingers", "4 fingers"), if (n == 4) 1 else 0) { Shortcuts.setFingers(this, if (it == 1) 4 else 3); redraw() },
            LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(10) })
        val arrows = mapOf("left" to "←", "right" to "→", "up" to "↑", "down" to "↓")
        list.addView(card(Shortcuts.DIRS.map { d ->
            settingRow("Swipe $d", value = Shortcuts.swipeLabel(this, platform, Shortcuts.swipe(this, platform, d)) + "  ›", icon = arrows.getValue(d)) {
                pickSwipe(platform, d) { redraw() }
            }
        }))

        list.addView(label("Reset to defaults", 15f, android.graphics.Color.rgb(255, 105, 97)).apply {
            gravity = Gravity.CENTER
            background = rounded(panel, 18)
            isClickable = true
            setOnClickListener {
                android.app.AlertDialog.Builder(this@RemoteActivity)
                    .setMessage("Remove your controls and swipe settings for this ${Shortcuts.computerKind(platform)}?")
                    .setPositiveButton("Reset") { _, _ -> Shortcuts.reset(this@RemoteActivity, platform); redraw() }
                    .setNegativeButton("Cancel", null).show()
            }
        }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(26) })

        page(pageHeader("Trackpad controls", null, null, "Done") { show(0) }, list)
    }

    private fun pickSwipe(platform: String, dir: String, done: () -> Unit) {
        val options = mutableListOf(Shortcuts.GESTURE)
        options += Shortcuts.controls(this, platform).filter { it.complete }.map { it.id }
        options += Shortcuts.builtIns(platform).map { "action:${it.first}" }
        options += Shortcuts.NOTHING
        android.app.AlertDialog.Builder(this)
            .setTitle("Swipe $dir")
            .setItems(options.map { Shortcuts.swipeLabel(this, platform, it) }.toTypedArray()) { _, i ->
                Shortcuts.setSwipe(this, platform, dir, options[i]); done()
            }
            .show()
    }

    /** A segmented control (one choice of a few). */
    private fun segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = rounded(panel, 100)
        setPadding(dp(4), dp(4), dp(4), dp(4))
        options.forEachIndexed { i, o ->
            addView(label(o, 14f, if (i == selected) onBrand else text, bold = i == selected).apply {
                gravity = Gravity.CENTER
                if (i == selected) background = rounded(brand, 100)
                isClickable = true
                setOnClickListener { if (i != selected) onSelect(i) }
            }, LinearLayout.LayoutParams(0, -1, 1f))
        }
    }

    /** A small toggle chip. */
    private fun toggleChip(s: String, on: Boolean, onClick: () -> Unit) = label(s, 14f, if (on) onBrand else text, bold = true).apply {
        gravity = Gravity.CENTER
        background = if (on) rounded(brand, 12) else GradientDrawable().apply {
            cornerRadius = dp(12).toFloat(); setColor(chip)
        }
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** Puts views in rows of [perRow], each taking an equal share. */
    private fun grid(views: List<View>, perRow: Int, height: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        views.chunked(perRow).forEach { chunk ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                chunk.forEachIndexed { i, v ->
                    addView(v, LinearLayout.LayoutParams(0, dp(height), 1f).apply { if (i > 0) marginStart = dp(8) })
                }
                repeat(perRow - chunk.size) { addView(View(context), LinearLayout.LayoutParams(0, dp(height), 1f).apply { marginStart = dp(8) }) }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun field(hintText: String, value: String, multiLine: Boolean = false, onChange: (String) -> Unit) = EditText(this).apply {
        setText(value)
        hint = hintText
        setHintTextColor(tint(muted, 150))
        setTextColor(this@RemoteActivity.text)
        textSize = 16f
        background = rounded(panel, 16)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        if (multiLine) { minLines = 3; gravity = Gravity.TOP; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
        else inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { onChange(s?.toString() ?: "") }
        })
    }

    // MARK: Control editor

    private fun editControl(platform: String, c: Shortcuts.Control, isNew: Boolean, keepScroll: Boolean = false) {
        if (!keepScroll) customizeScroll = 0
        fun redraw() = editControl(platform, c, isNew, keepScroll = true)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(24)) }

        // Preview: the button as it will look, and what it presses.
        val preview = controlPill(c, big = true)
        val capsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun fillCaps() {
            capsRow.removeAllViews()
            if (c.type == Shortcuts.KEYS) {
                val caps = Shortcuts.caps(platform, c)
                if (caps.isEmpty()) capsRow.addView(label("Pick a key below", 13f, muted))
                caps.forEachIndexed { i, k ->
                    if (i > 0 && platform == "windows") capsRow.addView(label("+", 14f, muted).apply { setPadding(dp(6), 0, dp(6), 0) })
                    capsRow.addView(label(k, 15f, text, bold = true).apply {
                        gravity = Gravity.CENTER
                        minWidth = dp(38)
                        setPadding(dp(10), dp(7), dp(10), dp(7))
                        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                            intArrayOf(mix(chip, android.graphics.Color.WHITE, 0.1f), chip)).apply {
                            cornerRadius = dp(9).toFloat(); setStroke(dp(1), mix(chip, android.graphics.Color.BLACK, 0.35f))
                        }
                    }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(if (platform == "windows" || i == 0) 0 else 6) })
                }
            } else capsRow.addView(label(Shortcuts.describe(platform, c), 13f, muted))
        }
        fillCaps()
        list.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(22), dp(16), dp(20))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(mix(panel, brand, 0.10f), panel)).apply { cornerRadius = dp(24).toFloat() }
            addView(preview, LinearLayout.LayoutParams(-2, dp(54)))
            addView(capsRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        list.addView(sectionTitle("NAME"))
        list.addView(field("e.g. Screenshot", c.name) { s ->
            c.name = s
            (preview.getChildAt(1) as TextView).text = s.ifEmpty { "Untitled" }
        })

        list.addView(sectionTitle("ICON"))
        list.addView(grid(Shortcuts.icons.map { ic ->
            label(ic, 18f, if (ic == c.icon) onBrand else text).apply {
                gravity = Gravity.CENTER
                background = rounded(if (ic == c.icon) brand else panel, 12)
                isClickable = true
                setOnClickListener { c.icon = ic; redraw() }
            }
        }, perRow = 8, height = 42))

        list.addView(sectionTitle("WHAT IT DOES"))
        val types = listOf(Shortcuts.KEYS, Shortcuts.TEXT, Shortcuts.ACTION)
        list.addView(segmented(listOf("Shortcut", "Type text", "Built-in"), types.indexOf(c.type).coerceAtLeast(0)) { c.type = types[it]; redraw() },
            LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(12) })

        when (c.type) {
            Shortcuts.KEYS -> {
                list.addView(note("Hold these…"))
                list.addView(grid(Shortcuts.mods(platform).map { m ->
                    toggleChip(if (platform == "windows") m.symbol else "${m.symbol}  ${m.label}", m.wire in c.mods) {
                        c.mods = if (m.wire in c.mods) c.mods - m.wire else c.mods + m.wire
                        redraw()
                    }
                }, perRow = if (platform == "windows") 4 else 2, height = 44))
                list.addView(note("…and press"))
                list.addView(field("Type a letter, number or symbol", if (c.key.length == 1) c.key else "") { s ->
                    val k = s.lowercase().takeLast(1)
                    if (Shortcuts.isTypableKey(k)) { c.key = k; fillCaps() }
                }.apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD })
                list.addView(note("or a special key"))
                list.addView(grid(Shortcuts.specialKeys.map { (k, cap) ->
                    toggleChip(cap, c.key == k) { c.key = k; redraw() }.apply { textSize = 13f }
                }, perRow = 5, height = 40))
            }
            Shortcuts.TEXT -> {
                list.addView(note("Typed on the ${Shortcuts.computerKind(platform)} wherever the cursor is — an email signature, an address, a command."))
                list.addView(field("Text to type", c.text, multiLine = true) { s -> c.text = s; fillCaps() })
            }
            else -> {
                list.addView(card(Shortcuts.builtIns(platform).map { (id, name, icon) ->
                    settingRow(name, icon = icon, value = if (c.action == id) "✓" else "") {
                        c.action = id
                        if (c.name.isEmpty()) c.name = name
                        if (c.icon == "★") c.icon = icon
                        redraw()
                    }
                }))
            }
        }

        // A gesture of your own: do it on the little trackpad to record it.
        list.addView(sectionTitle("GESTURE (OPTIONAL)"))
        list.addView(note("Record it on a full-size trackpad with the corners, edges and middle marked, so you can see exactly where it goes."))
        list.addView(card(listOf(
            settingRow(if (c.gesture.isEmpty()) "Record a gesture" else Gestures.describe(c.gesture),
                sub = if (c.gesture.isEmpty()) null else "Tap to record a different one",
                value = "›", icon = "✋") { recordGesture(platform, c, isNew) },
        )))
        if (c.gesture.isNotEmpty()) list.addView(label("Remove gesture", 14f, android.graphics.Color.rgb(255, 105, 97)).apply {
            setPadding(dp(8), dp(12), dp(8), 0)
            isClickable = true
            setOnClickListener { c.gesture = ""; redraw() }
        })
        list.addView(card(listOf(settingRow("Show as a button on the bar", value = if (c.onBar) "On" else "Off") { c.onBar = !c.onBar; redraw() })),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        // Try it now, then save or delete.
        list.addView(label("Try it on the ${Shortcuts.computerKind(platform)}", 15f, text, bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded(chip, 18)
            isClickable = true
            pressable()
            setOnClickListener {
                when {
                    !Link.isConnected -> android.widget.Toast.makeText(this@RemoteActivity, "Not connected to a computer", android.widget.Toast.LENGTH_SHORT).show()
                    !c.complete -> android.widget.Toast.makeText(this@RemoteActivity, "Finish the control first", android.widget.Toast.LENGTH_SHORT).show()
                    else -> { Shortcuts.run(c); performHapticFeedback(HapticFeedbackConstants.CONFIRM) }
                }
            }
        }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(22) })
        if (!isNew) list.addView(label("Delete control", 15f, android.graphics.Color.rgb(255, 105, 97)).apply {
            gravity = Gravity.CENTER
            background = rounded(panel, 18)
            isClickable = true
            setOnClickListener { Shortcuts.remove(this@RemoteActivity, platform, c.id); show(0) }
        }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(10) })

        page(pageHeader(if (isNew) "New control" else "Edit control", "Cancel", { show(0) }, "Save") {
            when {
                c.name.isBlank() -> android.widget.Toast.makeText(this, "Give it a name", android.widget.Toast.LENGTH_SHORT).show()
                !c.onBar && c.gesture.isEmpty() -> android.widget.Toast.makeText(this, "Put it on the bar or give it a gesture", android.widget.Toast.LENGTH_SHORT).show()
                c.gesture.isNotEmpty() && Shortcuts.gestureOwner(this, platform, c.gesture, exceptId = c.id) != null ->
                    android.widget.Toast.makeText(this, "That gesture already exists — try a new one", android.widget.Toast.LENGTH_SHORT).show()
                !c.complete -> android.widget.Toast.makeText(this, when (c.type) {
                    Shortcuts.KEYS -> "Pick a key"; Shortcuts.TEXT -> "Add the text to type"; else -> "Pick an action"
                }, android.widget.Toast.LENGTH_SHORT).show()
                else -> {
                    c.name = c.name.trim()
                    Shortcuts.upsert(this, platform, c)
                    hideKeyboard()
                    show(0)
                }
            }
        }, list)
    }

    /**
     * Records a gesture on a trackpad as big as the real one, with its corners, edges and middle drawn and labelled,
     * and the part a finger lands on lit up. "Use" keeps it for [c]; Cancel goes back to the editor unchanged.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun recordGesture(platform: String, c: Shortcuts.Control, isNew: Boolean) {
        hideKeyboard()
        body.removeAllViews()
        var recorded: String? = null
        val status = label("Do the gesture on the trackpad", 15f, muted, bold = true).apply {
            gravity = Gravity.CENTER; setPadding(dp(8), dp(14), dp(8), dp(4)); minLines = 2
        }
        // Drawn at the sizes the trackpad reads places with, so what you see here is where it works.
        val pad = TrackpadSurface(this, panel, brand, muted).apply { guide = true }
        lateinit var useButton: TextView
        fun record(key: String) {
            val owner = Shortcuts.gestureOwner(this, platform, key, exceptId = c.id)
            if (owner != null) {
                recorded = null
                status.text = "${Gestures.describe(key)} already exists — it's $owner. Try a new one."
                status.setTextColor(android.graphics.Color.rgb(255, 105, 97))
                pad.performHapticFeedback(HapticFeedbackConstants.REJECT)
            } else {
                recorded = key
                status.text = "✓  ${Gestures.describe(key)}"
                status.setTextColor(text)
                pad.pulse()
                pad.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            }
            useButton.alpha = if (recorded != null) 1f else 0.4f
        }
        fun hint(s: String) { status.text = s; status.setTextColor(muted) }
        var recordedThisTouch = false
        val pairer = TapPairer({ _, _ -> true }) { key -> record(key) }
        var place: String? = null
        val multi = MultiFingerReader(dp(55).toFloat(), dp(18).toFloat(),
            onSwipe = { f, dir ->
                // Three fingers swiping in from an edge: that edge's gesture (the place reader records it).
                val p = place
                if (!(p != null && !Gestures.isCorner(p) && dir == Gestures.inward(p) && f <= Gestures.PLACE_FINGERS)) {
                    recordedThisTouch = true; record(Gestures.key(f, "swipe_$dir"))
                }
            },
            onTap = { f ->
                // Three fingers tapping in a corner: that corner's gesture.
                val p = place
                if (!(p != null && Gestures.isCorner(p) && f <= Gestures.PLACE_FINGERS)) { recordedThisTouch = true; pairer.tap(f) }
            })
        val places = PlaceReader(dp(50).toFloat(), dp(14).toFloat(),
            onGesture = { f, motion, p ->
                if (f <= Gestures.PLACE_FINGERS) { recordedThisTouch = true; record(Gestures.key(f, motion, p)) }
                true
            },
            onTap = { f, p -> if (f <= Gestures.PLACE_FINGERS) { recordedThisTouch = true; pairer.tap(f, p) } })
        val clearHighlight = Runnable { pad.activePlace = null; pad.activeMiddle = false }
        pad.setOnTouchListener { v, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                main.removeCallbacks(clearHighlight)
                recordedThisTouch = false
                place = Gestures.placeAt(e.x, e.y, v.width.toFloat(), v.height.toFloat(), resources.displayMetrics.density)
                pad.activePlace = place
                pad.activeMiddle = place == null
                place?.let { places.start(e, it) }
            }
            pad.track(e)
            multi.feed(e)
            if (place != null) places.feed(e)
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                main.postDelayed(clearHighlight, 700)
                if (!recordedThisTouch && multi.fingers in 1..2) hint(when {
                    place == null -> "In the middle, use 3, 4 or 5 fingers. With 1 or 2, start in a corner or at an edge."
                    Gestures.isCorner(place!!) -> "In a corner: tap, double-tap, or hold for half a second — with 1, 2 or 3 fingers."
                    else -> "From an edge: start in the edge band and swipe inwards — with 1, 2 or 3 fingers."
                })
            }
            true
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
            addView(label("Cancel", 15f, brand).apply {
                setPadding(dp(4), dp(8), dp(12), dp(8)); isClickable = true
                setOnClickListener { editControl(platform, c, isNew, keepScroll = true) }
            })
            addView(label("Record a gesture", 20f, text, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            useButton = label("Use", 14.5f, onBrand, bold = true).apply {
                gravity = Gravity.CENTER
                background = rounded(brand, 100)
                setPadding(dp(22), dp(9), dp(22), dp(9))
                alpha = 0.4f
                isClickable = true
                setOnClickListener {
                    val key = recorded ?: return@setOnClickListener
                    c.gesture = key
                    editControl(platform, c, isNew, keepScroll = true)
                }
            }
            addView(useButton)
        }
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(pad, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(status)
            // About as tall as the trackpad screen's buttons, so the trackpad here is the same size as the real one.
            addView(View(context), LinearLayout.LayoutParams(1, dp(60)))
        })
    }

    private fun hideKeyboard() = getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(body.windowToken, 0)

    private fun avgX(e: MotionEvent) = (0 until e.pointerCount).map { e.getX(it) }.average().toFloat()
    private fun avgY(e: MotionEvent) = (0 until e.pointerCount).map { e.getY(it) }.average().toFloat()
    private fun click(button: String) = Link.send("input", "kind" to "click", "button" to button)

    // MARK: Keyboard

    private var ignoreEdits = false

    /** A raised key cap: a lighter top over a darker base (its "side"), sinking a little while pressed. */
    private fun keycap(fill: Int = chip, radius: Int = 10, depth: Int = 3): android.graphics.drawable.Drawable {
        fun cap(pressed: Boolean): android.graphics.drawable.Drawable {
            val side = GradientDrawable().apply { cornerRadius = dp(radius).toFloat(); setColor(mix(fill, android.graphics.Color.BLACK, 0.45f)) }
            val top = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(mix(fill, android.graphics.Color.WHITE, if (pressed) 0.02f else 0.09f), if (pressed) mix(fill, android.graphics.Color.BLACK, 0.08f) else fill)).apply {
                cornerRadius = dp(radius).toFloat(); setStroke(dp(1), tint(android.graphics.Color.WHITE, if (pressed) 8 else 18))
            }
            return android.graphics.drawable.LayerDrawable(arrayOf(side, top)).apply {
                val d = dp(depth)
                if (pressed) setLayerInset(1, 0, d - dp(1), 0, dp(1)) else setLayerInset(1, 0, 0, 0, d)
            }
        }
        return android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), cap(true))
            addState(intArrayOf(), cap(false))
        }
    }

    /** A key you tap (repeats while held when [repeats]). */
    private fun keyButton(title: String, size: Float = 16f, repeats: Boolean = false, action: () -> Unit) =
        label(title, size, text, bold = true).apply {
            gravity = Gravity.CENTER
            background = keycap()
            setPadding(0, 0, 0, dp(3))
            isClickable = true
            if (repeats) repeatTouch(this, action)
            else setOnClickListener { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); action() }
        }

    private val computerLabel get() = if (Link.isConnected) Link.computerName else Shortcuts.computerKind(Link.connectedPlatform)

    private fun keyboard(): View {
        val platform = Link.connectedPlatform
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Header: where the typing goes, and the full keyboard.
        wrap.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, 0, dp(10))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("TYPING ON", 11.5f, muted, bold = true).apply { letterSpacing = 0.1f })
                addView(label(computerLabel, 19f, text, bold = true).apply { maxLines = 1 })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(label("⌨  Full keyboard", 13.5f, onBrand, bold = true).apply {
                gravity = Gravity.CENTER
                background = rounded(brand, 100)
                setPadding(dp(16), dp(10), dp(16), dp(10))
                isClickable = true
                pressable()
                contentDescription = "Full keyboard: turn the phone sideways"
                setOnClickListener { fullKeyboardOn() }
            })
        })

        // The typing card.
        val live = label("● Live — every key goes straight to the ${Shortcuts.computerKind(platform)}", 12f, muted)
        val input = EditText(this).apply {
            setTextColor(this@RemoteActivity.text)
            setHintTextColor(tint(muted, 140))
            hint = "Start typing…"
            textSize = 19f
            background = null
            setPadding(dp(18), dp(18), dp(18), dp(10))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            gravity = Gravity.TOP
            minLines = 4
        }
        wrap.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(mix(panel, android.graphics.Color.WHITE, 0.04f), panel)).apply {
                cornerRadius = dp(24).toFloat(); setStroke(dp(1), tint(android.graphics.Color.WHITE, 22))
            }
            addView(input, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(live.apply { setPadding(dp(18), 0, dp(18), dp(14)) })
        }, LinearLayout.LayoutParams(-1, 0, 1f))
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

        // Everyday shortcuts (⌘ on a Mac, Ctrl on a PC — the PC turns ⌘ into Ctrl).
        val cmd = if (platform == "windows") "Ctrl+" else "⌘"
        wrap.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(Triple("Copy", "c", "C"), Triple("Paste", "v", "V"), Triple("Undo", "z", "Z"), Triple("Select all", "a", "A")).forEachIndexed { i, (name, k, cap) ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        cornerRadius = dp(16).toFloat(); setColor(mix(chip, brand, 0.12f)); setStroke(dp(1), tint(brand, 60))
                    }
                    isClickable = true
                    pressable()
                    setOnClickListener {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        Link.send("input", "kind" to "key", "key" to k, "mods" to org.json.JSONArray(listOf("cmd")))
                    }
                    addView(label(name, 13f, text, bold = true).apply { gravity = Gravity.CENTER; maxLines = 1 })
                    addView(label("$cmd$cap", 11f, muted).apply { gravity = Gravity.CENTER })
                }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { if (i > 0) marginStart = dp(8) })
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        // Keys the phone keyboard doesn't have, as real key caps.
        fun keyRow(keys: List<Triple<String, String, Boolean>>) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            keys.forEachIndexed { i, (k, title, rep) ->
                addView(keyButton(title, if (title.length > 2) 14f else 18f, repeats = rep) { key(k) },
                    LinearLayout.LayoutParams(0, dp(52), 1f).apply { if (i > 0) marginStart = dp(7) })
            }
        }
        wrap.addView(keyRow(listOf(Triple("esc", "Esc", false), Triple("tab", "Tab", false), Triple("backspace", "⌫", true), Triple("return", "⏎", false))),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        wrap.addView(keyRow(listOf(Triple("left", "←", true), Triple("up", "↑", true), Triple("down", "↓", true), Triple("right", "→", true))),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })

        input.requestFocus()
        main.postDelayed({ getSystemService(InputMethodManager::class.java).showSoftInput(input, 0) }, 200)
        return wrap
    }

    private fun key(name: String) = Link.send("input", "kind" to "key", "key" to name)

    // MARK: Full keyboard (landscape)

    /** Modifiers waiting for the next key (tap once), cleared after it. */
    private val mods = linkedSetOf<String>()
    /** Modifiers a finger is holding down right now (held on the Mac too, like a real keyboard). */
    private val held = linkedMapOf<String, Boolean>()   // name → used for a key while held
    private val active get() = (mods + held.keys).toList()
    private val modViews = HashMap<String, MutableList<TextView>>()
    private val charViews = mutableListOf<Pair<TextView, String>>()

    private val shifted = mapOf(
        "`" to "~", "1" to "!", "2" to "@", "3" to "#", "4" to "$", "5" to "%", "6" to "^", "7" to "&", "8" to "*",
        "9" to "(", "0" to ")", "-" to "_", "=" to "+", "[" to "{", "]" to "}", "\\" to "|", ";" to ":", "'" to "\"",
        "," to "<", "." to ">", "/" to "?",
    )

    private fun fullKeyboard(): View {
        releaseHeld()
        mods.clear(); modViews.clear(); charViews.clear()
        // A key: label, what it does, its width relative to a letter key, a modifier name, and whether holding repeats it.
        // (Every key but the modifiers and Vertical types the moment it goes down and repeats while held, like a real one.)
        class K(val label: String, val weight: Float = 1f, val mod: String? = null, val repeats: Boolean = false, val onRelease: Boolean = false, val action: () -> Unit)
        fun ch(c: String) = K(c) { typeChar(c) }
        fun sp(label: String, name: String, w: Float) =
            K(label, w, repeats = name in setOf("backspace", "left", "right", "up", "down")) { pressSpecial(name) }
        fun mod(label: String, name: String, w: Float) = K(label, w, mod = name) { }
        val rows = listOf(
            listOf(sp("esc", "esc", 1.3f)) + "`1234567890-=".map { ch(it.toString()) } + sp("⌫", "backspace", 1.7f),
            listOf(sp("tab", "tab", 1.6f)) + "qwertyuiop[]".map { ch(it.toString()) } + ch("\\").let { K(it.label, 1.4f, action = it.action) },
            listOf(mod("⌃ control", "ctrl", 1.9f)) + "asdfghjkl;'".map { ch(it.toString()) } + sp("return", "return", 2.1f),
            listOf(mod("⇧ shift", "shift", 2.4f)) + "zxcvbnm,./".map { ch(it.toString()) } + sp("↑", "up", 1f) + mod("⇧ shift", "shift", 1.6f),
            listOf(
                K("↺ Vertical", 1.5f, onRelease = true) { vertical() },
                mod("⌥ option", "opt", 1.4f), mod("⌘ cmd", "cmd", 1.7f), sp("space", "space", 5.6f),
                mod("⌘ cmd", "cmd", 1.7f), mod("⌥ option", "opt", 1.4f),
                sp("←", "left", 1f), sp("↓", "down", 1f), sp("→", "right", 1f),
            ),
        )
        // The keys sit on a base plate, like a real keyboard.
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isMotionEventSplittingEnabled = true
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(mix(bg, android.graphics.Color.WHITE, 0.05f), mix(bg, android.graphics.Color.BLACK, 0.2f))).apply {
                cornerRadius = dp(18).toFloat(); setStroke(dp(1), tint(android.graphics.Color.WHITE, 16))
            }
        }
        rows.forEach { row ->
            wrap.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                isMotionEventSplittingEnabled = true // each finger goes to the key it's on: several keys at once
                row.forEach { k ->
                    val v = label(k.label, if (k.label.length > 1) 12f else 17f, text, bold = k.label.length > 1).apply {
                        gravity = Gravity.CENTER
                        background = keycap(radius = 8, depth = 3)
                        setPadding(0, 0, 0, dp(3))
                        isClickable = true
                        maxLines = 1
                        when {
                            k.mod != null -> modifierTouch(this, k.mod)
                            k.onRelease -> setOnClickListener { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); k.action() }
                            // ⌫ and arrows start repeating sooner, like macOS; letters after a longer hold.
                            else -> repeatTouch(this, k.action, delay = if (k.repeats) 400L else 500L)
                        }
                    }
                    if (k.label.length == 1 && k.label !in setOf("↑", "↓", "←", "→")) charViews += v to k.label
                    rowMods(k.label)?.let { modViews.getOrPut(it) { mutableListOf() } += v }
                    addView(v, LinearLayout.LayoutParams(0, -1, k.weight).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) })
                }
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        refreshKeys()
        return wrap
    }

    private fun rowMods(label: String) = when (label) {
        "⌃ control" -> "ctrl"; "⇧ shift" -> "shift"; "⌥ option" -> "opt"; "⌘ cmd" -> "cmd"; else -> null
    }

    private fun toggleMod(name: String) {
        if (!mods.remove(name)) mods += name
        refreshKeys()
    }

    /**
     * A modifier key works like a real one: while a finger holds it, it's held on the Mac too, so ⌘ held + Tab, Tab, Tab
     * walks the app switcher and letting go of ⌘ opens the app. Tapped on its own, it applies to the next key instead.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun modifierTouch(v: View, name: String) {
        var downAt = 0L
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = SystemClock.uptimeMillis()
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (name !in held) {
                        held[name] = false
                        Link.send("input", "kind" to "mod", "key" to name, "down" to true)
                    }
                    refreshKeys()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val used = held.remove(name) ?: return@setOnTouchListener true
                    Link.send("input", "kind" to "mod", "key" to name, "down" to false)
                    // A quick tap with no other key: keep it for the next key, as before.
                    if (!used && e.actionMasked == MotionEvent.ACTION_UP && SystemClock.uptimeMillis() - downAt < 400) toggleMod(name)
                    refreshKeys()
                }
            }
            true
        }
    }

    /**
     * A key that types the moment a finger lands on it (so fast, overlapping fingers type in the order they pressed)
     * and repeats while held, like a real keyboard. Each key tracks its own finger, so several can be down at once.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun repeatTouch(v: View, action: () -> Unit, delay: Long = 450L) {
        val repeat = object : Runnable {
            override fun run() { action(); main.postDelayed(this, 60) }
        }
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    action()
                    main.postDelayed(repeat, delay)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; main.removeCallbacks(repeat) }
            }
            true
        }
    }

    /** Lets go of every held modifier (leaving the keyboard, or the screen). */
    private fun releaseHeld() {
        held.keys.toList().forEach { Link.send("input", "kind" to "mod", "key" to it, "down" to false) }
        held.clear()
    }

    override fun onPause() {
        releaseHeld()
        super.onPause()
    }

    /** Highlights held modifiers and shows ⇧ characters while shift is on. */
    private fun refreshKeys() {
        modViews.forEach { (name, views) ->
            views.forEach { v ->
                val on = name in mods || name in held
                v.background = keycap(if (on) brand else chip, radius = 8, depth = 3)
                v.setTextColor(if (on) onBrand else text)
            }
        }
        val shift = "shift" in mods || "shift" in held
        charViews.forEach { (v, c) ->
            v.text = if (!shift) c else if (c[0].isLetter()) c.uppercase() else shifted[c] ?: c
        }
    }

    private fun typeChar(c: String) {
        val on = active
        if (on.any { it != "shift" }) {
            // A shortcut such as ⌘C or ⌃⌥←: send the key with its modifiers.
            Link.send("input", "kind" to "key", "key" to c, "mods" to org.json.JSONArray(on))
        } else {
            val out = if ("shift" !in on) c else if (c[0].isLetter()) c.uppercase() else shifted[c] ?: c
            Link.send("input", "kind" to "type", "text" to out)
        }
        keyDone()
    }

    private fun pressSpecial(name: String) {
        val on = active
        if (name == "space" && on.isEmpty()) Link.send("input", "kind" to "type", "text" to " ")
        else Link.send("input", "kind" to "key", "key" to name, "mods" to org.json.JSONArray(on))
        keyDone()
    }

    /** After a key: held modifiers count as used (so letting go doesn't latch them); tapped ones are spent. */
    private fun keyDone() {
        held.keys.forEach { held[it] = true }
        if (mods.isNotEmpty()) { mods.clear(); refreshKeys() }
    }

    // MARK: Slides — a presenter view

    /** Which slide you're on (counted from your Next/Back presses) and the talk's clock; kept while you switch tabs. */
    private var slideNo = 1
    private var clockMs = 0L          // time run before the current stretch
    private var clockSince = 0L       // when the current stretch started (0 = paused / not started)
    private var slideLabel: TextView? = null
    private var clockLabel: TextView? = null

    private fun clockNow() = clockMs + if (clockSince > 0) SystemClock.elapsedRealtime() - clockSince else 0L

    private fun refreshPresenter() {
        slideLabel?.text = "Slide $slideNo"
        val t = clockNow() / 1000
        clockLabel?.text = (if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%02d:%02d".format(t / 60, t % 60)) +
            if (clockSince == 0L && t > 0) "  ⏸" else ""
    }

    private val tick = object : Runnable {
        override fun run() {
            if (slideLabel?.isAttachedToWindow != true) return
            refreshPresenter()
            main.postDelayed(this, 500)
        }
    }

    private fun nextSlide() {
        key("right")
        slideNo++
        if (clockSince == 0L && clockMs == 0L) clockSince = SystemClock.elapsedRealtime() // the talk starts with the first Next
        refreshPresenter()
    }

    private fun previousSlide() {
        key("left")
        if (slideNo > 1) slideNo--
        refreshPresenter()
    }

    /** A small stat chip (clock, slide number). */
    private fun statChip(title: String, onClick: () -> Unit, onLong: () -> Unit) = label(title, 17f, text, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(10), dp(18), dp(10))
        background = GradientDrawable().apply { cornerRadius = dp(100).toFloat(); setColor(panel); setStroke(dp(1), tint(android.graphics.Color.WHITE, 20)) }
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        fontFeatureSettings = "tnum"
        isClickable = true
        setOnClickListener { onClick() }
        setOnLongClickListener { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); onLong(); true }
    }

    private fun slides(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL

        // Header: where the slides are, the clock and the slide number.
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, 0, dp(12))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("PRESENTING ON", 11.5f, muted, bold = true).apply { letterSpacing = 0.1f })
                addView(label(computerLabel, 19f, text, bold = true).apply { maxLines = 1 })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            clockLabel = statChip("00:00", onClick = {
                // Tap: pause or carry on. Long-press: back to zero.
                if (clockSince > 0) { clockMs = clockNow(); clockSince = 0 } else clockSince = SystemClock.elapsedRealtime()
                refreshPresenter()
            }, onLong = { clockMs = 0; clockSince = 0; refreshPresenter() }).apply { contentDescription = "Talk time. Tap to pause, hold to reset." }
            addView(clockLabel)
            slideLabel = statChip("Slide 1", onClick = {}, onLong = { slideNo = 1; refreshPresenter() }).apply {
                contentDescription = "Slide number. Hold to start again from 1."
            }
            addView(slideLabel, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        })

        // Next: the big one.
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(mix(brand, android.graphics.Color.WHITE, 0.12f), brand, mix(brand, android.graphics.Color.BLACK, 0.25f))).apply {
                cornerRadius = dp(32).toFloat()
            }
            elevation = dp(8).toFloat()
            isClickable = true
            pressable()
            contentDescription = "Next slide"
            setOnClickListener { performHapticFeedback(HapticFeedbackConstants.CONFIRM); nextSlide() }
            addView(label("▶", 44f, onBrand).apply { gravity = Gravity.CENTER })
            addView(label("Next", 28f, onBrand, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) })
        }, LinearLayout.LayoutParams(-1, 0, 2.3f).apply { setMargins(dp(2), 0, dp(2), 0) })

        // Back.
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(mix(panel, android.graphics.Color.WHITE, 0.05f), panel)).apply {
                cornerRadius = dp(26).toFloat(); setStroke(dp(1), tint(android.graphics.Color.WHITE, 22))
            }
            isClickable = true
            pressable()
            contentDescription = "Previous slide"
            setOnClickListener { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); previousSlide() }
            addView(label("◀", 22f, text).apply { setPadding(0, 0, dp(12), 0) })
            addView(label("Back", 20f, text, bold = true))
        }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })

        // Screen and show controls.
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(Triple("■", "Black", "b"), Triple("□", "White", "w"), Triple("✕", "End show", "esc")).forEachIndexed { i, (icon, name, k) ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = rounded(panel, 20)
                    isClickable = true
                    pressable()
                    setOnClickListener { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); key(k) }
                    addView(label(icon, 18f, if (k == "esc") android.graphics.Color.rgb(255, 105, 97) else text).apply { gravity = Gravity.CENTER })
                    addView(label(name, 12.5f, muted, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(3), 0, 0) })
                }, LinearLayout.LayoutParams(0, -1, 1f).apply { if (i > 0) marginStart = dp(10) })
            }
        }, LinearLayout.LayoutParams(-1, dp(72)).apply { topMargin = dp(12) })

        addView(label("Volume down = next · volume up = back · works in Keynote, PowerPoint and Google Slides", 12f, muted).apply {
            gravity = Gravity.CENTER; setPadding(dp(12), dp(12), dp(12), 0)
        })

        refreshPresenter()
        main.removeCallbacks(tick)
        main.postDelayed(tick, 500)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (mode == 2) when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> { nextSlide(); return true }
            KeyEvent.KEYCODE_VOLUME_UP -> { previousSlide(); return true }
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
