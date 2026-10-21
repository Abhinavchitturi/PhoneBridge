package app.phonebridge

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

/** Colours shared by the tab views (the same palette as the home screen). */
class Colors(val bg: Int, val card: Int, val text: Int, val muted: Int, val brand: Int, val line: Int) {
    companion object {
        fun of(ctx: Context): Colors {
            val dark = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            return Colors(
                bg = if (dark) 0xFF0E1016.toInt() else 0xFFF4F5FA.toInt(), card = if (dark) 0xFF1A1D27.toInt() else 0xFFFFFFFF.toInt(),
                text = if (dark) 0xFFF2F3F7.toInt() else 0xFF14161F.toInt(), muted = if (dark) 0xFF9097A8.toInt() else 0xFF667085.toInt(),
                brand = 0xFF5C6BC0.toInt(), line = if (dark) 0xFF2A2E3B.toInt() else 0xFFE6E8F0.toInt(),
            )
        }
    }
}

private fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

private fun Context.label(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
    text = s
    textSize = size
    setTextColor(color)
    if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
}

private fun Context.rounded(color: Int, radius: Int = 18) = GradientDrawable().apply { cornerRadius = dp(radius).toFloat(); setColor(color) }

/** Loads a photo scaled down so a list of them stays light. */
fun decodeScaled(path: String, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}

/** Same as [decodeScaled], for pictures held in memory. */
fun decodeScaledBytes(data: ByteArray, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

// MARK: Guard photos tab

class GuardGalleryView(ctx: Context, private val k: Colors) : LinearLayout(ctx) {
    private val onPhotos: (String) -> Unit = { post { refresh() } }

    init {
        orientation = VERTICAL
        setPadding(ctx.dp(18), ctx.dp(12), ctx.dp(18), ctx.dp(24))
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); GuardStore.listeners.add(onPhotos) }
    override fun onDetachedFromWindow() { GuardStore.listeners.remove(onPhotos); super.onDetachedFromWindow() }

    fun refresh() {
        removeAllViews()
        val ctx = context
        addView(ctx.label("Guard photos", 26f, k.text, bold = true))
        addView(ctx.label("Alerts from your Mac. The photos stay on the Mac — this phone only shows them while you look and keeps nothing. They're deleted at the end of each day.", 14f, k.muted)
            .apply { setPadding(0, ctx.dp(6), 0, ctx.dp(16)) })
        val entries = GuardStore.entries(ctx)
        if (entries.isEmpty()) {
            addView(LinearLayout(ctx).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
                background = ctx.rounded(k.card)
                setPadding(ctx.dp(20), ctx.dp(36), ctx.dp(20), ctx.dp(36))
                addView(ctx.label("Nothing today", 18f, k.text, bold = true).apply { gravity = Gravity.CENTER })
                addView(ctx.label("Alerts from your Mac's guard show up here, with their photos.", 14f, k.muted).apply { gravity = Gravity.CENTER; setPadding(0, ctx.dp(6), 0, 0) })
            })
            return
        }
        entries.forEach { addView(entryCard(it)) }
    }

    private fun entryCard(e: GuardEntry): View {
        val ctx = context
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(e.timeMs))
        return LinearLayout(ctx).apply {
            orientation = VERTICAL
            background = ctx.rounded(k.card)
            setPadding(ctx.dp(14), ctx.dp(14), ctx.dp(14), ctx.dp(14))
            layoutParams = LayoutParams(-1, -2).apply { bottomMargin = ctx.dp(12) }
            isClickable = true
            setOnClickListener { open(e.id, 0) }
            addView(ctx.label("Opened ${e.host}", 16f, k.text, bold = true))
            addView(ctx.label("${e.reason} · $time", 13f, k.muted).apply { setPadding(0, ctx.dp(2), 0, ctx.dp(10)) })
            addView(LinearLayout(ctx).apply {
                orientation = HORIZONTAL
                if (e.count == 0) addView(ctx.label("No photo (the Mac's camera wasn't available)", 13f, k.muted))
                val inMemory = GuardStore.photos(e.id)
                if (inMemory == null && e.count > 0) Guard.requestPhotos(ctx, e.id) // fetch from the Mac when it's reachable
                for (i in 0 until minOf(e.count, 3)) {
                    val bmp = inMemory?.getOrNull(i)?.let { decodeScaledBytes(it, 300) }
                    addView(ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        if (bmp != null) setImageBitmap(bmp) else setBackgroundColor(k.line)
                        clipToOutline = true
                        outlineProvider = object : ViewOutlineProvider() {
                            override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, ctx.dp(12).toFloat())
                        }
                        setOnClickListener { open(e.id, i) }
                    }, LayoutParams(0, ctx.dp(96), 1f).apply { if (i > 0) leftMargin = ctx.dp(8) })
                }
            })
        }
    }

    private fun open(id: String, index: Int) {
        context.startActivity(Intent(context, GuardPhotoActivity::class.java).putExtra("id", id).putExtra("index", index))
    }
}

// MARK: Authenticator code tab

class GuardCodeView(ctx: Context, private val k: Colors) : LinearLayout(ctx) {
    private val handler = Handler(Looper.getMainLooper())
    private val codeView = TextView(ctx).apply { textSize = 54f; setTextColor(k.text); typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER }
    private val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply { max = 3000 }
    private val info = TextView(ctx).apply { textSize = 14f; setTextColor(k.muted); gravity = Gravity.CENTER; setPadding(0, ctx.dp(14), 0, 0) }
    private var running = false
    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val code = Guard.code(context, now)
            if (code == null) {
                codeView.text = "—"
                info.text = "Turn on the Intruder guard in PhoneBridge on your Mac first (Settings → Intruder guard)."
                bar.visibility = View.INVISIBLE
            } else {
                codeView.text = code.substring(0, 3) + " " + code.substring(3)
                val left = 30_000 - now % 30_000
                bar.visibility = View.VISIBLE
                bar.progress = (left / 10).toInt()
                info.text = "Changes in ${left / 1000 + 1} s. Type it on the Mac's lock screen. Don't share it."
            }
            if (running) handler.postDelayed(this, 250)
        }
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPadding(ctx.dp(28), ctx.dp(28), ctx.dp(28), ctx.dp(28))
        addView(ctx.label("Mac guard code", 22f, k.text).apply { gravity = Gravity.CENTER })
        addView(ctx.label("Opens your Mac's lock screen", 14f, k.muted).apply { gravity = Gravity.CENTER; setPadding(0, ctx.dp(4), 0, ctx.dp(28)) })
        addView(codeView, LayoutParams(-1, -2))
        addView(bar, LayoutParams(-1, ctx.dp(10)).apply { topMargin = ctx.dp(18) })
        addView(info, LayoutParams(-1, -2))
    }

    fun start() {
        if (running) return
        running = true
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }
}

class GuardCodeActivity : Activity() {
    private lateinit var view: GuardCodeView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) // no screenshots or recents preview of the code
        val k = Colors.of(this)
        view = GuardCodeView(this, k).apply {
            setBackgroundColor(k.bg)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(dp(28), bars.top + dp(28), dp(28), bars.bottom + dp(28))
                insets
            }
        }
        setContentView(view)
    }

    override fun onResume() { super.onResume(); view.start() }
    override fun onPause() { view.stop(); super.onPause() }
}

// MARK: Notepad tab

class NotesView(ctx: Context, private val k: Colors) : LinearLayout(ctx) {
    private var applying = false
    private val editor = EditText(ctx).apply {
        setText(Notes.text(ctx))
        setTextColor(k.text)
        setHintTextColor(k.muted)
        hint = "Write a note, a list, a link… it shows up on your Mac too."
        textSize = 17f
        gravity = Gravity.TOP
        background = null
        setPadding(0, ctx.dp(8), 0, ctx.dp(8))
    }

    init {
        orientation = VERTICAL
        setPadding(ctx.dp(20), ctx.dp(12), ctx.dp(20), ctx.dp(12))
        addView(ctx.label("Shared notepad", 24f, k.text, bold = true))
        addView(ctx.label("Synced with your Mac — edit on either.", 14f, k.muted).apply { setPadding(0, ctx.dp(4), 0, ctx.dp(10)) })
        addView(editor, LayoutParams(-1, 0, 1f))
        editor.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { if (!applying) Notes.save(context, s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    fun start() {
        Notes.listener = { t ->
            post {
                if (editor.text.toString() != t) {
                    applying = true
                    val pos = minOf(editor.selectionStart.coerceAtLeast(0), t.length)
                    editor.setText(t)
                    editor.setSelection(pos)
                    applying = false
                }
            }
        }
        val now = Notes.text(context)
        if (editor.text.toString() != now) { applying = true; editor.setText(now); applying = false }
    }

    fun stop() { Notes.listener = null }
}

class NotesActivity : Activity() {
    private lateinit var view: NotesView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val k = Colors.of(this)
        view = NotesView(this, k).apply {
            setBackgroundColor(k.bg)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(dp(20), bars.top + dp(16), dp(20), bars.bottom + dp(16))
                insets
            }
        }
        setContentView(view)
    }

    override fun onStart() { super.onStart(); view.start() }
    override fun onStop() { view.stop(); super.onStop() }
}

// MARK: Full-screen photo viewer (pinch to zoom, double-tap, swipe between the photos of one alert)

class ZoomImageView(ctx: Context) : ImageView(ctx) {
    var onSwipe: ((Int) -> Unit)? = null
    private val m = Matrix()
    private var minScale = 1f
    private val values = FloatArray(9)
    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val target = (currentScale() * d.scaleFactor).coerceIn(minScale, minScale * 6f)
            val f = target / currentScale()
            m.postScale(f, f, d.focusX, d.focusY)
            fix()
            return true
        }
    })
    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val target = if (currentScale() > minScale * 1.05f) minScale else minScale * 2.5f
            val f = target / currentScale()
            m.postScale(f, f, e.x, e.y)
            fix()
            return true
        }

        override fun onScroll(a: MotionEvent?, b: MotionEvent, dx: Float, dy: Float): Boolean {
            if (currentScale() > minScale * 1.02f) { m.postTranslate(-dx, -dy); fix() }
            return true
        }

        override fun onFling(a: MotionEvent?, b: MotionEvent, vx: Float, vy: Float): Boolean {
            if (currentScale() <= minScale * 1.02f && Math.abs(vx) > 900 && Math.abs(vx) > Math.abs(vy)) onSwipe?.invoke(if (vx < 0) 1 else -1)
            return true
        }
    })

    init { scaleType = ScaleType.MATRIX }

    fun show(bitmap: Bitmap) {
        setImageBitmap(bitmap)
        post { fit() }
    }

    private fun currentScale(): Float { m.getValues(values); return values[Matrix.MSCALE_X] }

    private fun fit() {
        val d = drawable ?: return
        if (width == 0 || height == 0) return
        minScale = minOf(width.toFloat() / d.intrinsicWidth, height.toFloat() / d.intrinsicHeight)
        m.reset()
        m.postScale(minScale, minScale)
        m.postTranslate((width - d.intrinsicWidth * minScale) / 2f, (height - d.intrinsicHeight * minScale) / 2f)
        imageMatrix = m
    }

    /** Keeps the picture on screen: centred if smaller than the view, never panned past an edge if larger. */
    private fun fix() {
        val d = drawable ?: return
        m.getValues(values)
        val w = d.intrinsicWidth * values[Matrix.MSCALE_X]
        val h = d.intrinsicHeight * values[Matrix.MSCALE_Y]
        var x = values[Matrix.MTRANS_X]
        var y = values[Matrix.MTRANS_Y]
        x = if (w <= width) (width - w) / 2f else x.coerceIn(width - w, 0f)
        y = if (h <= height) (height - h) / 2f else y.coerceIn(height - h, 0f)
        m.postTranslate(x - values[Matrix.MTRANS_X], y - values[Matrix.MTRANS_Y])
        imageMatrix = m
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        fit()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        gestures.onTouchEvent(e)
        return true
    }
}

class GuardPhotoActivity : Activity() {
    private lateinit var image: ZoomImageView
    private lateinit var caption: TextView
    private var entry: GuardEntry? = null
    private var index = 0
    private val onPhotos: (String) -> Unit = { id -> if (id == entry?.id) runOnUiThread { showPhoto() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        val id = intent.getStringExtra("id") ?: return finish()
        index = intent.getIntExtra("index", 0)
        entry = GuardStore.entries(this).firstOrNull { it.id == id }
        if (entry == null) { // deleted at midnight, or never saved
            android.widget.Toast.makeText(this, "That photo has been deleted", android.widget.Toast.LENGTH_SHORT).show()
            return finish()
        }
        window.statusBarColor = 0
        image = ZoomImageView(this).apply { onSwipe = { step(it) } }
        caption = TextView(this).apply { textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER }
        fun pill(title: String, color: Int, cmd: String) = TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            background = rounded(color, 26)
            setPadding(dp(20), dp(14), dp(20), dp(14))
            setOnClickListener { Guard.sendCommand(this@GuardPhotoActivity, cmd, id); finish() }
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            addView(TextView(this@GuardPhotoActivity).apply {
                text = "Code"
                textSize = 16f
                setTextColor(0xFFFFFFFF.toInt())
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                background = rounded(0xFF5C6BC0.toInt(), 26)
                setPadding(dp(20), dp(14), dp(20), dp(14))
                setOnClickListener { startActivity(Guard.codeIntent(this@GuardPhotoActivity)) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(pill("It's me", 0xFF22C55E.toInt(), "safe"), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) })
            addView(pill("Lock Mac", 0xFFEF4444.toInt(), "lock"), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) })
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setBackgroundColor(0x66000000)
            addView(caption)
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(image, FrameLayout.LayoutParams(-1, -1))
            addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                top.setPadding(dp(18), bars.top + dp(10), dp(18), dp(10))
                bottom.setPadding(dp(18), dp(10), dp(18), bars.bottom + dp(14))
                insets
            }
        }
        setContentView(root)
        showPhoto()
    }

    private fun step(delta: Int) {
        val e = entry ?: return
        if (e.count <= 1) return
        index = (index + delta + e.count) % e.count
        showPhoto()
    }

    override fun onStart() { super.onStart(); GuardStore.listeners.add(onPhotos) }
    override fun onStop() { GuardStore.listeners.remove(onPhotos); super.onStop() }

    private fun showPhoto() {
        val e = entry ?: return
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(e.timeMs))
        val list = GuardStore.photos(e.id)
        val data = list?.getOrNull(index)
        if (list != null && list.isEmpty() && e.count > 0) {
            caption.text = "${e.reason} · $time\nThis photo has been deleted from your Mac."
        } else if (data != null) {
            BitmapFactory.decodeByteArray(data, 0, data.size)?.let { image.show(it) }
            caption.text = "${e.reason} · $time" + if (e.count > 1) "  (${index + 1}/${e.count} — swipe)" else ""
        } else {
            caption.text = "${e.reason} · $time\nFetching the photo from your Mac…"
            Guard.requestPhotos(this, e.id) // the photos live on the Mac only
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { cornerRadius = dp(radius).toFloat(); setColor(color) }
}
