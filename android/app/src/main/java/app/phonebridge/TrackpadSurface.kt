package app.phonebridge

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View

/**
 * The trackpad's glass: a soft gradient with a fine dot texture, a thin edge, a glow under each finger that fades
 * when it lifts, and quiet hint lines at the bottom. Touch handling stays with the caller; it passes each event to
 * [track] so the glows follow the fingers.
 */
class TrackpadSurface(ctx: Context, private val base: Int, private val accent: Int, private val hintColor: Int) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val radius = 28 * d
    private val rect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1 * d }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12.5f * d; color = hintColor }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f * d; color = withAlpha(hintColor, 200); isFakeBoldText = true; letterSpacing = 0.08f
    }

    /** Finger positions, and how strongly each glow shows (1 while down, fading to 0 after it lifts). */
    private val fingers = HashMap<Int, FloatArray>() // id → [x, y, strength]
    private var fade: ValueAnimator? = null

    var hint: List<String> = emptyList()
        set(v) { field = v; invalidate() }
    /** Corners ("tl", "tr", "bl", "br") and edges ("l", "r", "t", "b") that have a gesture: drawn as faint marks. */
    var places: Set<String> = emptySet()
        set(v) { field = v; invalidate() }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    /**
     * Recording guide: (corner size, edge size) in pixels, as the trackpad reads places. When set, the corners, edges
     * and middle are drawn as labelled areas, and [activePlace] (or the middle, with [activeMiddle]) lights up.
     */
    var guide: Pair<Float, Float>? = null
        set(v) { field = v; invalidate() }
    var activePlace: String? = null
        set(v) { field = v; invalidate() }
    var activeMiddle = false
        set(v) { field = v; invalidate() }
    private val zoneFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val zoneEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * d; pathEffect = android.graphics.DashPathEffect(floatArrayOf(5 * d, 4 * d), 0f)
    }
    private val zoneText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11.5f * d; isFakeBoldText = true }
    private val middleText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15f * d; isFakeBoldText = true }

    private fun zoneLabel(c: Canvas, text: String, r: RectF, active: Boolean, rotate: Float = 0f) {
        zoneText.color = if (active) accent else withAlpha(hintColor, 230)
        val lines = text.split('\n')
        // Shrink to fit the area (rotated labels fit its height).
        val room = (if (rotate != 0f) r.height() else r.width()) - 10 * d
        val widest = lines.maxOf { zoneText.measureText(it) }
        val baseSize = zoneText.textSize
        if (widest > room) zoneText.textSize = baseSize * room / widest
        c.save()
        c.translate(r.centerX(), r.centerY())
        if (rotate != 0f) c.rotate(rotate)
        val lh = zoneText.textSize * 1.15f
        lines.forEachIndexed { i, line ->
            c.drawText(line, -zoneText.measureText(line) / 2, (i - (lines.size - 1) / 2f) * lh + zoneText.textSize * 0.35f, zoneText)
        }
        c.restore()
        zoneText.textSize = baseSize
    }

    private fun drawGuide(c: Canvas, cs: Float, es: Float) {
        val w = width.toFloat(); val h = height.toFloat()
        val band = Gestures.sideBand(h, d)
        val bandTop = (h - band) / 2; val bandBottom = bandTop + band
        val zones = listOf(
            Triple("tl", RectF(0f, 0f, cs, cs), "Top-left\ncorner"), Triple("tr", RectF(w - cs, 0f, w, cs), "Top-right\ncorner"),
            Triple("bl", RectF(0f, h - cs, cs, h), "Bottom-left\ncorner"), Triple("br", RectF(w - cs, h - cs, w, h), "Bottom-right\ncorner"),
            Triple("t", RectF(cs, 0f, w - cs, es), "Top edge"), Triple("b", RectF(cs, h - es, w - cs, h), "Bottom edge"),
            Triple("l", RectF(0f, bandTop, es, bandBottom), "Left edge"), Triple("r", RectF(w - es, bandTop, w, bandBottom), "Right edge"),
        )
        c.save()
        c.clipPath(android.graphics.Path().apply { addRoundRect(rect, radius, radius, android.graphics.Path.Direction.CW) })
        for ((id, r, label) in zones) {
            val on = id == activePlace
            zoneFill.color = withAlpha(accent, if (on) 90 else 26)
            c.drawRect(r, zoneFill)
            zoneEdge.color = withAlpha(accent, if (on) 220 else 90)
            c.drawRect(r, zoneEdge)
            when (id) {
                "l" -> zoneLabel(c, label, r, on, -90f)
                "r" -> zoneLabel(c, label, r, on, 90f)
                else -> zoneLabel(c, label, r, on)
            }
        }
        c.restore()
        // The middle.
        middleText.color = if (activeMiddle) accent else withAlpha(Color.WHITE, 200)
        zoneText.color = withAlpha(hintColor, 230)
        val l1 = "Middle — 3, 4 or 5 fingers"
        val l2 = "tap, double-tap or swipe"
        val l3 = "1–2 fingers: start in a corner or at an edge"
        val cy = h / 2
        c.drawText(l1, (w - middleText.measureText(l1)) / 2, cy - 10 * d, middleText)
        c.drawText(l2, (w - zoneText.measureText(l2)) / 2, cy + 12 * d, zoneText)
        c.drawText(l3, (w - zoneText.measureText(l3)) / 2, cy + 32 * d, zoneText)
    }

    var title: String = ""
        set(v) { field = v; invalidate() }

    /** Light the edge while a gesture is recognised (e.g. after a swipe fires). */
    fun pulse() {
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 420
            addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
            start()
        }
    }
    private var pulse = 0f

    fun track(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_MOVE -> {
                fade?.cancel()
                fingers.keys.retainAll((0 until e.pointerCount).map { e.getPointerId(it) }.toSet())
                for (i in 0 until e.pointerCount) fingers[e.getPointerId(i)] = floatArrayOf(e.getX(i), e.getY(i), 1f)
            }
            MotionEvent.ACTION_POINTER_UP -> fingers[e.getPointerId(e.actionIndex)]?.set(2, 0.6f)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                fade = ValueAnimator.ofFloat(1f, 0f).apply {
                    duration = 360
                    val start = fingers.mapValues { it.value[2] }
                    addUpdateListener { a -> fingers.forEach { (k, v) -> v[2] = (start[k] ?: 0f) * (a.animatedValue as Float) }; invalidate() }
                    start()
                }
            }
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        rect.set(0.5f * d, 0.5f * d, w - 0.5f * d, h - 0.5f * d)
        // Android's Back gesture swipes in from the screen's side edges, which the trackpad nearly touches. Keep the
        // middle of each side for the trackpad (Android allows up to 200 dp per edge): edge gestures start there.
        val band = Gestures.sideBand(h.toFloat(), d)
        val bandTop = ((h - band) / 2).toInt(); val bandBottom = bandTop + band.toInt()
        systemGestureExclusionRects = listOf(
            android.graphics.Rect(0, bandTop, (40 * d).toInt(), bandBottom),
            android.graphics.Rect(w - (40 * d).toInt(), bandTop, w, bandBottom),
        )
        val top = blend(base, Color.WHITE, 0.06f)
        val bottom = blend(base, Color.BLACK, 0.12f)
        fill.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        edge.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            withAlpha(Color.WHITE, 46), withAlpha(Color.WHITE, 10), Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        c.drawRoundRect(rect, radius, radius, fill)

        // A fine dot grid, faintest at the edges.
        val step = 22 * d
        val cx = width / 2f; val cy = height / 2f
        val maxDist = Math.hypot(cx.toDouble(), cy.toDouble()).toFloat()
        var y = step
        while (y < height - step / 2) {
            var x = step
            while (x < width - step / 2) {
                val k = 1f - (Math.hypot((x - cx).toDouble(), (y - cy).toDouble()).toFloat() / maxDist)
                dot.color = withAlpha(Color.WHITE, (8 + 22 * k).toInt())
                c.drawCircle(x, y, 1.1f * d, dot)
                x += step
            }
            y += step
        }

        // Corner and edge marks: a soft arc inside a corner, a short bar along the middle of an edge.
        if (places.isNotEmpty()) {
            mark.strokeWidth = 3 * d
            mark.color = withAlpha(accent, 90)
            val r = 30 * d; val inset = 10 * d
            for (p in places) when (p) {
                "tl" -> c.drawArc(RectF(inset, inset, inset + 2 * r, inset + 2 * r), 180f, 90f, false, mark)
                "tr" -> c.drawArc(RectF(width - inset - 2 * r, inset, width - inset, inset + 2 * r), 270f, 90f, false, mark)
                "bl" -> c.drawArc(RectF(inset, height - inset - 2 * r, inset + 2 * r, height - inset), 90f, 90f, false, mark)
                "br" -> c.drawArc(RectF(width - inset - 2 * r, height - inset - 2 * r, width - inset, height - inset), 0f, 90f, false, mark)
                "l" -> Gestures.sideBand(height.toFloat(), d).let { b -> c.drawLine(6 * d, height / 2f - b / 2 + 12 * d, 6 * d, height / 2f + b / 2 - 12 * d, mark) }
                "r" -> Gestures.sideBand(height.toFloat(), d).let { b -> c.drawLine(width - 6 * d, height / 2f - b / 2 + 12 * d, width - 6 * d, height / 2f + b / 2 - 12 * d, mark) }
                "t" -> c.drawLine(width / 2f - 34 * d, 6 * d, width / 2f + 34 * d, 6 * d, mark)
                "b" -> c.drawLine(width / 2f - 34 * d, height - 6 * d, width / 2f + 34 * d, height - 6 * d, mark)
            }
        }

        guide?.let { (cs, es) -> drawGuide(c, cs, es) }

        // Finger glows.
        val r = 70 * d
        for (f in fingers.values) {
            if (f[2] <= 0.01f) continue
            glow.shader = RadialGradient(f[0], f[1], r,
                intArrayOf(withAlpha(accent, (120 * f[2]).toInt()), withAlpha(accent, (36 * f[2]).toInt()), Color.TRANSPARENT),
                floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(f[0], f[1], r, glow)
        }

        // Edge, brighter for a moment when a gesture fires.
        if (pulse > 0f) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 2 * d; color = withAlpha(accent, (200 * pulse).toInt())
            }
            c.drawRoundRect(rect, radius, radius, p)
        } else c.drawRoundRect(rect, radius, radius, edge)

        // Title at the top, hints at the bottom (they step aside while fingers are down).
        val busy = fingers.values.any { it[2] > 0.05f }
        val alpha = if (busy) 70 else 255
        if (title.isNotEmpty()) {
            titlePaint.alpha = (200 * alpha / 255)
            c.drawText(title, (width - titlePaint.measureText(title)) / 2f, 30 * d, titlePaint)
        }
        if (hint.isNotEmpty()) {
            textPaint.alpha = (Color.alpha(hintColor) * alpha / 255)
            val text = hint.joinToString("\n")
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, (width - 40 * d).toInt())
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(5 * d, 1f).build()
            c.save()
            c.translate(20 * d, height - layout.height - 22 * d)
            layout.draw(c)
            c.restore()
        }
    }
}

private fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

private fun blend(a: Int, b: Int, t: Float): Int {
    fun ch(s: Int) = ((a shr s and 0xFF) * (1 - t) + (b shr s and 0xFF) * t).toInt() shl s
    return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
}
