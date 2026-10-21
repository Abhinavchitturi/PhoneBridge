package app.phonebridge

import android.annotation.SuppressLint
import android.app.Activity
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
import kotlin.math.hypot

/** Phone as trackpad, keyboard and slide clicker for the Mac. */
class RemoteActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var body: FrameLayout
    private val tabs = mutableListOf<TextView>()
    private var mode = 0 // 0 trackpad, 1 keyboard, 2 slides

    private val bg = 0xFF0E1016.toInt()
    private val panel = 0xFF1A1D27.toInt()
    private val chip = 0xFF262A38.toInt()
    private val text = 0xFFF2F3F7.toInt()
    private val muted = 0xFF9097A8.toInt()
    private val brand = 0xFF5C6BC0.toInt()

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
        show(0)
    }

    private fun show(i: Int) {
        mode = i
        tabs.forEachIndexed { j, t ->
            t.background = if (j == i) rounded(brand, 100) else null
            t.setTextColor(if (j == i) 0xFFFFFFFF.toInt() else muted)
        }
        body.removeAllViews()
        body.addView(when (i) { 0 -> trackpad(); 1 -> keyboard(); else -> slides() })
        val imm = getSystemService(InputMethodManager::class.java)
        if (i != 1) imm.hideSoftInputFromWindow(body.windowToken, 0)
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

    @SuppressLint("ClickableViewAccessibility")
    private fun trackpad(): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pad = TextView(this).apply {
            text = "Move with one finger · tap to click\nTwo fingers: scroll · two-finger tap: right-click"
            setTextColor(muted)
            textSize = 13f
            gravity = Gravity.CENTER
            background = rounded(panel, 24)
        }
        val speed = 2.2f
        var lastX = 0f; var lastY = 0f
        var downTime = 0L; var travelled = 0f; var maxPointers = 1
        pad.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.x; lastY = e.y; downTime = SystemClock.uptimeMillis(); travelled = 0f; maxPointers = 1
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    maxPointers = maxOf(maxPointers, e.pointerCount)
                    lastX = avgX(e); lastY = avgY(e)
                }
                MotionEvent.ACTION_MOVE -> {
                    val x = if (e.pointerCount > 1) avgX(e) else e.x
                    val y = if (e.pointerCount > 1) avgY(e) else e.y
                    val dx = x - lastX; val dy = y - lastY
                    travelled += hypot(dx, dy)
                    if (e.pointerCount > 1) {
                        Link.send("input", "kind" to "scroll", "dx" to (dx * 1.5).toDouble(), "dy" to (dy * 1.5).toDouble())
                    } else {
                        move(dx * speed, dy * speed)
                    }
                    lastX = x; lastY = y
                }
                MotionEvent.ACTION_POINTER_UP -> { lastX = e.x; lastY = e.y }
                MotionEvent.ACTION_UP -> {
                    if (SystemClock.uptimeMillis() - downTime < 250 && travelled < dp(12)) {
                        click(if (maxPointers >= 2) "right" else "left")
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
        wrap.addView(label("Type here — it appears on your Mac", 14f, muted).apply { setPadding(dp(4), 0, 0, dp(8)) })
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
