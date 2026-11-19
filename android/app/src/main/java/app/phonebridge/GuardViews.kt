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
class Colors(val bg: Int, val card: Int, val text: Int, val muted: Int, val brand: Int, val line: Int, val onBrand: Int) {
    companion object {
        fun of(ctx: Context): Colors {
            val t = ThemeStore.get(ctx)
            return Colors(bg = t.bg, card = t.card, text = t.text, muted = t.muted, brand = t.accent, line = t.line, onBrand = t.onAccent)
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

// MARK: Authenticator code tab

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

