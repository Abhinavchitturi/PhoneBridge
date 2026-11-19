package app.phonebridge

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

// Themes (all dark): one accent colour each, plus a few small drawn motifs. Mirrors mac/Sources/Themes.swift.

enum class Motif { ARCH, BAT, SPIDER, REACTOR, THOR, MATRIX }

private fun mix(a: Int, b: Int, t: Float): Int {
    fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
    return Color.rgb(ch(Color.red(a), Color.red(b)), ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
}

private fun withAlpha(color: Int, a: Float) = (color and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)

class AppTheme(val id: String, val name: String, val bg: Int, val accent: Int, val accent2: Int, val onAccent: Int, val motif: Motif) {
    val card = mix(bg, Color.WHITE, 0.07f)
    val chip = mix(bg, Color.WHITE, 0.11f)
    val line = mix(bg, Color.WHITE, 0.15f)
    val text = 0xFFF5F5F7.toInt()
    val muted = 0xFF9A9AA2.toInt()
}

object ThemeStore {
    val all = listOf(
        AppTheme("midnight", "Midnight", 0xFF0B0B0D.toInt(), 0xFFF5F5F7.toInt(), 0xFFA1A1A6.toInt(), 0xFF0B0B0D.toInt(), Motif.ARCH),
        AppTheme("batman", "Batman", 0xFF09090A.toInt(), 0xFFF5C518.toInt(), 0xFFB8921A.toInt(), 0xFF111111.toInt(), Motif.BAT),
        AppTheme("spiderman", "Spider-Man", 0xFF0A0A0B.toInt(), 0xFFE23636.toInt(), 0xFF8FA3FF.toInt(), 0xFFFFFFFF.toInt(), Motif.SPIDER),
        AppTheme("ironman", "Iron Man", 0xFF0F0A0A.toInt(), 0xFFFFC21A.toInt(), 0xFFD0112B.toInt(), 0xFF111111.toInt(), Motif.REACTOR),
        AppTheme("thor", "Thor", 0xFF080A10.toInt(), 0xFF5AB8FF.toInt(), 0xFFA9B7C6.toInt(), 0xFF06121F.toInt(), Motif.THOR),
        AppTheme("matrix", "Matrix", 0xFF020A05.toInt(), 0xFF19F06B.toInt(), 0xFF0E9B45.toInt(), 0xFF021108.toInt(), Motif.MATRIX),
    )

    @Volatile private var cached: AppTheme? = null

    fun get(ctx: Context): AppTheme = cached ?: run {
        val id = Prefs.get(ctx).getString("theme", null)
        (all.firstOrNull { it.id == id } ?: all[0]).also { cached = it }
    }

    /** Set when a theme was just chosen, so the next screen can play its one-off burst animation. */
    @Volatile var burstPending = false

    /** Set while a screen is open, so a theme picked on the Mac redraws it. */
    @Volatile var onRemoteChange: (() -> Unit)? = null

    /** Picks a theme. When you pick it here, the Mac follows; when the Mac picked it ([fromRemote]) nothing is sent back. */
    fun set(ctx: Context, t: AppTheme, fromRemote: Boolean = false) {
        Prefs.get(ctx).edit().putString("theme", t.id).apply()
        cached = t
        burstPending = true
        LinkService.instance?.refreshNotification()
        if (!fromRemote) Link.send("theme", "id" to t.id)
    }

    /** The Mac picked a theme. */
    fun remote(ctx: Context, id: String) {
        val t = all.firstOrNull { it.id == id } ?: return
        if (t.id == get(ctx).id) return
        set(ctx, t, fromRemote = true)
        android.os.Handler(android.os.Looper.getMainLooper()).post { onRemoteChange?.invoke() }
    }

    /** The theme's mark on a dark rounded tile, used as the notification's picture so it matches the app. */
    fun badge(ctx: Context): Bitmap {
        val t = get(ctx)
        val size = 192
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = size * 0.28f
        c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.card })
        c.drawRoundRect(RectF(1.5f, 1.5f, size - 1.5f, size - 1.5f), r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 3f; color = t.line
        })
        ThemeArt.glyph(c, size / 2f, size / 2f, size * 0.5f, t, t.accent, ctx.resources)
        return bmp
    }

    /** Tells the Mac which theme we're on (sent whenever we connect). */
    fun push(ctx: Context) { Link.send("theme", "id" to get(ctx).id) }
}

object ThemeArt {
    private val cache = HashMap<Int, Bitmap>()

    internal fun bitmap(res: Resources, id: Int): Bitmap = cache.getOrPut(id) {
        BitmapFactory.decodeResource(res, id, BitmapFactory.Options().apply { inScaled = false })
    }

    /** Draws a cut-out picture centred on (x, y), scaled so its longest side is [fit]. */
    private fun picture(c: Canvas, res: Resources, id: Int, x: Float, y: Float, fit: Float, alpha: Float = 1f) {
        val b = bitmap(res, id)
        val k = fit / maxOf(b.width, b.height)
        val w = b.width * k
        val h = b.height * k
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.alpha = (alpha * 255).toInt() }
        c.drawBitmap(b, null, RectF(x - w / 2, y - h / 2, x + w / 2, y + h / 2), paint)
    }

    internal fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
    internal fun stroke(color: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = w; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    private fun poly(c: Canvas, pts: List<Pair<Float, Float>>, x: Float, y: Float, s: Float, color: Int) {
        val p = Path()
        pts.forEachIndexed { i, (a, b) -> if (i == 0) p.moveTo(x + a * s, y + b * s) else p.lineTo(x + a * s, y + b * s) }
        p.close()
        c.drawPath(p, fill(color))
    }

    /** Bat emblem: two ears, wide wings with scalloped edges. About 96 units wide at scale 1, centred on (0, 3). */
    fun bat(c: Canvas, x: Float, y: Float, s: Float, color: Int) {
        fun px(v: Float) = x + v * s
        fun py(v: Float) = y + (v - 3f) * s
        val p = Path()
        p.moveTo(px(0f), py(-9f)); p.lineTo(px(3f), py(-13f)); p.lineTo(px(4f), py(-8f))
        p.quadTo(px(9f), py(-4f), px(14f), py(-8f))
        p.quadTo(px(30f), py(-16f), px(48f), py(2f))
        p.quadTo(px(38f), py(0f), px(30f), py(7f))
        p.quadTo(px(24f), py(2f), px(16f), py(12f))
        p.quadTo(px(10f), py(8f), px(0f), py(20f))
        p.quadTo(px(-10f), py(8f), px(-16f), py(12f))
        p.quadTo(px(-24f), py(2f), px(-30f), py(7f))
        p.quadTo(px(-38f), py(0f), px(-48f), py(2f))
        p.quadTo(px(-30f), py(-16f), px(-14f), py(-8f))
        p.quadTo(px(-9f), py(-4f), px(-4f), py(-8f))
        p.lineTo(px(-3f), py(-13f))
        p.close()
        c.drawPath(p, fill(color))
    }

    /** Spider emblem: pointed body and four long angular legs on each side. About 70 units tall, centred on (0, 0). */
    fun spider(c: Canvas, x: Float, y: Float, s: Float, color: Int) {
        fun px(v: Float) = x + v * s
        fun py(v: Float) = y + (v - 4f) * s
        val legs = listOf(
            listOf(3f to -6f, 20f to -10f, 16f to -36f), listOf(3f to -3f, 28f to -4f, 27f to -30f),
            listOf(3f to 1f, 26f to 8f, 32f to 34f), listOf(3f to 5f, 16f to 14f, 17f to 40f),
        )
        val path = Path()
        for (side in floatArrayOf(-1f, 1f)) for (l in legs) {
            path.moveTo(px(side * l[0].first), py(l[0].second))
            path.lineTo(px(side * l[1].first), py(l[1].second))
            path.lineTo(px(side * l[2].first), py(l[2].second))
        }
        c.drawPath(path, stroke(color, maxOf(1f, 2.1f * s)))
        val body = Path()
        body.moveTo(px(0f), py(-2f)); body.quadTo(px(9f), py(8f), px(3f), py(24f)); body.lineTo(px(0f), py(32f)); body.lineTo(px(-3f), py(24f))
        body.quadTo(px(-9f), py(8f), px(0f), py(-2f))
        c.drawPath(body, fill(color))
        c.drawOval(RectF(x - 3.4f * s, y - 11f * s, x + 3.4f * s, y - 3.4f * s), fill(color))
    }

    /** Masked face on a web: round, with black-rimmed white eyes. */
    fun mask(c: Canvas, x: Float, y: Float, r: Float, body: Int, eye: Int, line: Int) {
        c.drawCircle(x, y, r, fill(body))
        val web = Path()
        for (i in 0 until 8) {
            val a = i * Math.PI.toFloat() / 4f
            web.moveTo(x, y); web.lineTo(x + Math.cos(a.toDouble()).toFloat() * r * 0.98f, y + Math.sin(a.toDouble()).toFloat() * r * 0.98f)
        }
        web.addCircle(x, y, r * 0.62f, Path.Direction.CW)
        c.drawPath(web, stroke(line, maxOf(0.6f, r * 0.07f)))
        for (side in floatArrayOf(-1f, 1f)) {
            val cx = x + side * r * 0.44f
            val cy = y - r * 0.04f
            c.save()
            c.rotate(-side * 28.6f, cx, cy)
            c.drawOval(RectF(cx - r * 0.46f, cy - r * 0.29f, cx + r * 0.46f, cy + r * 0.29f), fill(line))
            c.drawOval(RectF(cx - r * 0.3f, cy - r * 0.17f, cx + r * 0.3f, cy + r * 0.17f), fill(eye))
            c.restore()
        }
    }

    fun web(c: Canvas, cornerX: Float, cornerY: Float, k: Float, color: Int) {
        val ends = listOf(-95f to 28f, -78f to 72f, -48f to 108f, -4f to 124f).map { (cornerX + it.first * k) to (cornerY + it.second * k) }
        val p = Path()
        for ((ex, ey) in ends) { p.moveTo(cornerX, cornerY); p.lineTo(ex, ey) }
        for (t in floatArrayOf(0.35f, 0.65f)) {
            ends.forEachIndexed { i, (ex, ey) ->
                val qx = cornerX + (ex - cornerX) * t
                val qy = cornerY + (ey - cornerY) * t
                if (i == 0) p.moveTo(qx, qy) else p.lineTo(qx, qy)
            }
        }
        c.drawPath(p, stroke(color, maxOf(1f, k * 0.55f)))
    }

    fun reactor(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
        c.drawCircle(x, y, r, stroke(color, maxOf(1f, r * 0.01f)))
        c.drawCircle(x, y, r * 0.72f, stroke(color, maxOf(1.5f, r * 0.02f)))
        c.drawCircle(x, y, r * 0.38f, stroke(color, maxOf(1.5f, r * 0.02f)))
    }

    /** Flat helmet: red shell, gold faceplate with the red crest notch on top, white eye slits, jaw lines. About 107 units tall. */
    fun ironFace(c: Canvas, x: Float, y: Float, s: Float, helmet: Int, plate: Int, eye: Int) {
        poly(c, listOf(0f to -53f, 18f to -50f, 29f to -38f, 32f to -20f, 34f to -6f, 38f to 0f, 34f to 10f, 26f to 36f, 22f to 48f, 12f to 54f,
            -12f to 54f, -22f to 48f, -26f to 36f, -34f to 10f, -38f to 0f, -34f to -6f, -32f to -20f, -29f to -38f, -18f to -50f), x, y, s, helmet)
        poly(c, listOf(-27f to -40f, -20f to -47f, -9f to -47f, -6f to -20f, 6f to -20f, 9f to -47f, 20f to -47f, 27f to -40f, 29f to -8f, 25f to 14f,
            15f to 38f, 9f to 46f, -9f to 46f, -15f to 38f, -25f to 14f, -29f to -8f), x, y, s, plate)
        for (side in floatArrayOf(-1f, 1f)) {
            poly(c, listOf(side * 27f to -7f, side * 5f to 1f, side * 5f to 10f, side * 24f to 7f), x, y, s, helmet)
            poly(c, listOf(side * 24f to -4f, side * 8f to 3f, side * 8f to 6.5f, side * 21f to 5f), x, y, s, eye)
        }
        val lines = Path()
        lines.moveTo(x - 10 * s, y + 36 * s); lines.lineTo(x + 10 * s, y + 36 * s)
        lines.moveTo(x - 9 * s, y + 41 * s); lines.lineTo(x + 9 * s, y + 41 * s)
        for (side in floatArrayOf(-1f, 1f)) { lines.moveTo(x + side * 21 * s, y + 16 * s); lines.lineTo(x + side * 13 * s, y + 34 * s) }
        c.drawPath(lines, stroke(helmet, maxOf(0.8f, 1.7f * s)))
    }

    /** A war hammer tilted to the right: head with bevelled ends, wrapped handle. About 75 units tall. */
    fun hammer(c: Canvas, x: Float, y: Float, s: Float, color: Int, cut: Int) {
        c.save()
        c.translate(x, y)
        c.rotate(25f)
        fun box(bx: Float, by: Float, w: Float, h: Float) = c.drawRoundRect(RectF(bx * s, (by + 6) * s, (bx + w) * s, (by + h + 6) * s), 2 * s, 2 * s, fill(color))
        box(-24f, -26f, 48f, 34f); box(-6f, -31f, 12f, 6f); box(-5f, 8f, 10f, 34f); box(-7f, 40f, 14f, 4f)
        val cuts = Path()
        for (sx in floatArrayOf(-19f, 19f)) { cuts.moveTo(sx * s, (-20f + 6f) * s); cuts.lineTo(sx * s, (2f + 6f) * s) }
        for (yy in floatArrayOf(17f, 24f, 31f)) { cuts.moveTo(-5 * s, (yy + 3f + 6f) * s); cuts.lineTo(5 * s, (yy - 3f + 6f) * s) }
        c.drawPath(cuts, stroke(cut, maxOf(0.7f, 2.1f * s)).apply { strokeCap = Paint.Cap.BUTT })
        c.restore()
    }

    fun bolt(c: Canvas, x: Float, y: Float, s: Float, color: Int) =
        poly(c, listOf(0f to 0f, -14f to 34f, -2f to 34f, -18f to 78f, 8f to 28f, -4f to 28f, 10f to 0f), x, y, s, color)

    fun arch(c: Canvas, x: Float, y: Float, w: Float, color: Int, line: Float) {
        val p = Path()
        p.moveTo(x - w / 2, y + w * 0.3f)
        p.quadTo(x, y - w * 0.7f, x + w / 2, y + w * 0.3f)
        c.drawPath(p, stroke(color, line))
        c.drawCircle(x - w / 2, y + w * 0.3f, line * 1.25f, fill(color))
        c.drawCircle(x + w / 2, y + w * 0.3f, line * 1.25f, fill(color))
    }

    internal fun hash(x: Double): Double {
        val v = sin(x * 12.9898) * 43758.5453
        return v - floor(v)
    }

    internal fun pictureT(c: Canvas, res: Resources, id: Int, x: Float, y: Float, fit: Float, alpha: Float = 1f, scaleY: Float = 1f, rotation: Float = 0f, flip: Boolean = false) {
        val b = bitmap(res, id)
        val k = fit / maxOf(b.width, b.height)
        val w = b.width * k
        val h = b.height * k
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt() }
        c.save()
        c.translate(x, y)
        c.rotate(Math.toDegrees(rotation.toDouble()).toFloat())
        c.scale(if (flip) -1f else 1f, scaleY)
        c.drawBitmap(b, null, RectF(-w / 2, -h / 2, w / 2, h / 2), paint)
        c.restore()
    }

    internal fun glow(c: Canvas, x: Float, y: Float, r: Float, color: Int, alpha: Float) {
        if (alpha <= 0f) return
        c.drawCircle(x, y, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(x, y, r, withAlpha(color, alpha), withAlpha(color, 0f), Shader.TileMode.CLAMP)
        })
    }

    internal fun lightning(ax: Float, ay: Float, bx: Float, by: Float, seed: Double, segments: Int = 7, jitter: Float): Path {
        val p = Path()
        p.moveTo(ax, ay)
        val dx = bx - ax
        val dy = by - ay
        val len = maxOf(1f, Math.hypot(dx.toDouble(), dy.toDouble()).toFloat())
        val nx = -dy / len
        val ny = dx / len
        for (k in 1 until segments) {
            val f = k.toFloat() / segments
            val off = (hash(seed + k).toFloat() - 0.5f) * 2 * jitter
            p.lineTo(ax + dx * f + nx * off, ay + dy * f + ny * off)
        }
        p.lineTo(bx, by)
        return p
    }

    internal fun bolt2(c: Canvas, path: Path, color: Int, alpha: Float, w: Float) {
        c.drawPath(path, stroke(withAlpha(Color.WHITE, 0.9f * alpha), w))
        c.drawPath(path, stroke(withAlpha(color, 0.6f * alpha), w * 3.2f))
    }

    internal val rainChars = "01アウ7Z3"
    internal fun rainPaint(size: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE; textSize = size; textAlign = Paint.Align.CENTER; this.color = color
    }

    /** Each theme's character moves: bats flap across, the spider swings on its thread, the reactor pulses, lightning flickers, code falls. */
    fun drawAnimated(c: Canvas, w: Float, t: AppTheme, res: Resources, time: Double) {
        val u = w / 380f
        when (t.motif) {
            Motif.ARCH -> {
                val pulse = 0.5 + 0.5 * sin(time * 1.2)
                arch(c, w - 120 * u, 96 * u, 150 * u, withAlpha(t.accent, (0.06 + 0.07 * pulse).toFloat()), 9 * u)
            }
            Motif.BAT -> {
                val pulse = (0.5 + 0.5 * sin(time * 1.6)).toFloat()
                pictureT(c, res, R.drawable.themeart_batoval, w - 135 * u, 96 * u, 250 * u * (1 + 0.012f * pulse), 0.5f + 0.12f * pulse)
                for (i in 0 until 4) {
                    val f = i.toDouble()
                    val travel = (time * (24 + 7 * f) * u + f * 140 * u) % (w + 120 * u)
                    val x = w + 40 * u - travel.toFloat()
                    val y = (28 + 52 * i) * u + 10 * u * sin(time * 2 + f).toFloat()
                    val flap = (0.62 + 0.38 * abs(sin(time * 9 + f * 1.7))).toFloat()
                    pictureT(c, res, R.drawable.themeart_bat, x, y, (34 + 6 * (i % 2)) * u, 0.5f, flap)
                }
            }
            Motif.SPIDER -> {
                pictureT(c, res, R.drawable.themeart_mask, w - 120 * u, 112 * u, 200 * u, 0.55f)
                val sway = sin(time * 0.8).toFloat() * 10 * u
                val drop = 150 * u + sin(time * 1.1).toFloat() * 14 * u
                val x = w - 270 * u + sway
                val thread = Path().apply { moveTo(w - 270 * u + sway * 0.3f, 0f); lineTo(x, drop - 50 * u) }
                c.drawPath(thread, stroke(withAlpha(t.accent2, 0.4f), maxOf(1f, u)))
                pictureT(c, res, R.drawable.themeart_spider, x, drop, 110 * u, 0.5f, 1f, sway / u * 0.01f)
            }
            Motif.REACTOR -> {
                val pulse = (0.5 + 0.5 * sin(time * 2.2)).toFloat()
                glow(c, w - 120 * u, 112 * u, 140 * u, t.accent, 0.10f + 0.12f * pulse)
                pictureT(c, res, R.drawable.themeart_helmet, w - 120 * u, 112 * u, 210 * u, 0.8f)
                pictureT(c, res, R.drawable.themeart_reactor, w - 270 * u, 160 * u, 100 * u, 0.3f + 0.22f * pulse, 1f, (time * 0.5).toFloat())
            }
            Motif.THOR -> {
                val cycle = time % 3.4
                val flash = if (cycle < 0.35) (1 - cycle / 0.35).toFloat() else 0f
                val seed = floor(time / 3.4)
                glow(c, w - 120 * u, 113 * u, 170 * u, t.accent, 0.28f * flash)
                pictureT(c, res, R.drawable.themeart_thor, w - 120 * u, 113 * u, 215 * u, 0.5f + 0.35f * flash, 1f, (0.04 * sin(time * 0.9)).toFloat())
                if (flash > 0f) {
                    val xs = floatArrayOf(w - 300 * u, w - 215 * u, w - 40 * u)
                    xs.forEachIndexed { k, x0 ->
                        val path = lightning(x0, -6 * u, x0 + (hash(seed + k).toFloat() - 0.5f) * 90 * u, (120 + 60 * hash(seed + k + 9).toFloat()) * u, seed * 7 + k, jitter = 14 * u)
                        bolt2(c, path, t.accent, flash, 1.6f * u)
                    }
                }
            }
            Motif.MATRIX -> {
                for (col in 0 until 9) {
                    val speed = 28 + 18 * hash(col.toDouble())
                    val head = (time * speed + hash(col + 3.0) * 260) % 280 - 20
                    for (r in 0 until 10) {
                        val y = ((head - r * 16) * u).toFloat()
                        if (y < -8 * u || y > 270 * u) continue
                        val ch = rainChars[(hash((col * 31 + r).toDouble() + floor(time * 5)) * rainChars.length).toInt() % rainChars.length].toString()
                        val color = if (r == 0) withAlpha(Color.WHITE, 0.55f) else withAlpha(t.accent, (0.3 * (1 - r / 10.0)).toFloat())
                        c.drawText(ch, w - 24 * u - col * 22 * u, y + 4 * u, rainPaint(maxOf(6f, 12 * u), color))
                    }
                }
            }
        }
    }

    /** Extra motion around the searching orb, specific to the theme. Called after the blob and rings are drawn. */
    fun orbExtras(c: Canvas, cx: Float, cy: Float, r0: Float, t: AppTheme, res: Resources, time: Double, d: Float) {
        when (t.motif) {
            Motif.BAT -> for (i in 0 until 7) {
                val f = i.toDouble()
                val ring = if (i % 2 == 0) 1.62f else 1.42f
                val a = f * 0.9 + time * (if (i % 2 == 0) 0.32 else -0.26)
                val x = cx + cos(a).toFloat() * r0 * ring
                val y = cy + sin(a).toFloat() * r0 * ring
                val flap = (0.6 + 0.4 * abs(sin(time * 8 + f * 1.3))).toFloat()
                pictureT(c, res, R.drawable.themeart_bat, x, y, 22 * d, 0.8f, flap, (a + (if (i % 2 == 0) Math.PI / 2 else -Math.PI / 2)).toFloat())
            }
            Motif.SPIDER -> {
                val silk = Path()
                for (k in 0 until 8) {
                    val a = k * Math.PI / 4 + time * 0.06
                    silk.moveTo(cx + cos(a).toFloat() * r0 * 1.05f, cy + sin(a).toFloat() * r0 * 1.05f)
                    silk.lineTo(cx + cos(a).toFloat() * r0 * 1.62f, cy + sin(a).toFloat() * r0 * 1.62f)
                }
                c.drawPath(silk, stroke(withAlpha(t.accent2, 0.22f), 0.8f * d))
                val a = time * 0.3
                pictureT(c, res, R.drawable.themeart_spider, cx + cos(a).toFloat() * r0 * 1.62f, cy + sin(a).toFloat() * r0 * 1.62f, 30 * d, 0.9f, 1f,
                    (a + Math.PI / 2 + 0.15 * sin(time * 6)).toFloat())
            }
            Motif.REACTOR -> {
                val specs = listOf(
                    Triple(1.74f, 80f, 0.9) to (2.4f to t.accent), Triple(1.86f, 44f, -1.3) to (1.8f to t.accent2), Triple(1.96f, 120f, 0.5) to (1.4f to t.accent),
                )
                for ((a, b) in specs) {
                    val (scale, span, speed) = a
                    val (w, color) = b
                    val start = (time * speed * 57.3).toFloat()
                    val rr = r0 * scale
                    c.drawArc(RectF(cx - rr, cy - rr, cx + rr, cy + rr), start, span, false, stroke(withAlpha(color, 0.7f), w * d))
                }
            }
            Motif.THOR -> {
                val cycle = time % 2.2
                val flash = if (cycle < 0.3) (1 - cycle / 0.3).toFloat() else 0f
                if (flash > 0f) {
                    val seed = floor(time / 2.2)
                    for (k in 0 until 3) {
                        val a = hash(seed + k * 3.1) * 2 * Math.PI
                        val path = lightning(cx + cos(a).toFloat() * r0 * 0.95f, cy + sin(a).toFloat() * r0 * 0.95f,
                            cx + cos(a + 0.2).toFloat() * r0 * 1.95f, cy + sin(a + 0.2).toFloat() * r0 * 1.95f, seed * 5 + k, jitter = 9 * d)
                        bolt2(c, path, t.accent, flash, 1.4f * d)
                    }
                }
            }
            Motif.MATRIX -> for (i in 0 until 16) {
                val f = i.toDouble()
                val ring = if (i % 2 == 0) 1.62f else 1.42f
                val a = f * 0.83 + time * (0.1 + 0.03 * (i % 4)) * (if (i % 3 == 0) -1 else 1)
                val ch = rainChars[(hash(f + floor(time * 3)) * rainChars.length).toInt() % rainChars.length].toString()
                c.drawText(ch, cx + cos(a).toFloat() * r0 * ring, cy + sin(a).toFloat() * r0 * ring + 4 * d,
                    rainPaint(11 * d, withAlpha(t.accent, (0.35 + 0.35 * sin(time + f)).toFloat())))
            }
            else -> {}
        }
    }

    /** One-off flourish when a theme is chosen. [p] runs 0 to 1 over about 1.4 seconds. */
    fun burst(c: Canvas, w: Float, h: Float, t: AppTheme, p: Double, res: Resources) {
        ThemeSeq.draw(c, w, h, t, p, res, 1f)
    }

    /** Big faint artwork for the top-right corner. Everything scales with the width (designed at 380). */
    fun draw(c: Canvas, w: Float, t: AppTheme, res: Resources) {
        val u = w / 380f
        when (t.motif) {
            Motif.ARCH -> arch(c, w - 120 * u, 96 * u, 150 * u, withAlpha(t.accent, 0.09f), 9 * u)
            Motif.BAT -> {
                picture(c, res, R.drawable.themeart_batoval, w - 135 * u, 96 * u, 250 * u, 0.6f)
                picture(c, res, R.drawable.themeart_bat, w - 285 * u, 45 * u, 62 * u, 0.5f)
                picture(c, res, R.drawable.themeart_bat, w - 60 * u, 215 * u, 50 * u, 0.4f)
            }
            Motif.SPIDER -> {
                picture(c, res, R.drawable.themeart_mask, w - 120 * u, 112 * u, 200 * u, 0.55f)
                picture(c, res, R.drawable.themeart_spider, w - 270 * u, 150 * u, 110 * u, 0.5f)
            }
            Motif.REACTOR -> {
                picture(c, res, R.drawable.themeart_helmet, w - 120 * u, 112 * u, 210 * u, 0.8f)
                picture(c, res, R.drawable.themeart_reactor, w - 270 * u, 160 * u, 100 * u, 0.4f)
            }
            Motif.THOR -> picture(c, res, R.drawable.themeart_thor, w - 120 * u, 113 * u, 215 * u, 0.5f)
            Motif.MATRIX -> {
                val chars = "01アウ7Z3"
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textSize = maxOf(6f, 12 * u); textAlign = Paint.Align.CENTER }
                for (col in 0 until 9) for (row in 0 until 9) {
                    paint.color = withAlpha(t.accent, 0.05f + row * 0.03f)
                    c.drawText(chars[(col * 3 + row) % chars.length].toString(), w - 24 * u - col * 22 * u, 20 * u + row * 22 * u + 4 * u, paint)
                }
            }
        }
    }

    /** Small mark in a single colour (dock, tiles). Centered on (x, y), about [size] across. */
    fun glyph(c: Canvas, x: Float, y: Float, size: Float, t: AppTheme, color: Int, res: Resources) {
        val k = size / 28f
        when (t.motif) {
            Motif.ARCH -> arch(c, x, y - 1.8f * k, 18 * k, color, 2.4f * k)
            Motif.BAT -> picture(c, res, R.drawable.themeart_bat, x, y, 26 * k)
            Motif.SPIDER -> picture(c, res, R.drawable.themeart_mask, x, y, 24 * k)
            Motif.REACTOR -> picture(c, res, R.drawable.themeart_helmet, x, y, 27 * k)
            Motif.THOR -> picture(c, res, R.drawable.themeart_thor, x, y, 25 * k)
            Motif.MATRIX -> {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textSize = 12 * k; textAlign = Paint.Align.CENTER }
                c.drawText("01", x, y + 4 * k, p)
            }
        }
    }
}

class MotifView(ctx: Context, private val theme: AppTheme) : View(ctx) {
    override fun onDraw(canvas: Canvas) {
        ThemeArt.drawAnimated(canvas, width.toFloat(), theme, resources, SystemClock.uptimeMillis() / 1000.0)
        postInvalidateDelayed(33)
    }
}

/** Plays the theme's burst over the screen right after a theme is chosen, then removes itself. */
class BurstView(ctx: Context, private val theme: AppTheme) : View(ctx) {
    private val start = SystemClock.uptimeMillis()
    override fun onDraw(canvas: Canvas) {
        val p = (SystemClock.uptimeMillis() - start) / 3600.0
        if (p >= 1.0) {
            (parent as? android.view.ViewGroup)?.let { g -> g.post { g.removeView(this) } }
            return
        }
        ThemeSeq.draw(canvas, width.toFloat(), height.toFloat(), theme, p, resources, resources.displayMetrics.density)
        postInvalidateOnAnimation()
    }
}

class GlyphView(ctx: Context, private val theme: AppTheme, private val color: Int) : View(ctx) {
    override fun onDraw(canvas: Canvas) = ThemeArt.glyph(canvas, width / 2f, height / 2f, minOf(width, height).toFloat(), theme, color, resources)
}

/** One selectable theme: a small preview with its artwork, and its name underneath. */
private class ThemeTile(ctx: Context, private val t: AppTheme, private val selected: Boolean) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13 * d; textAlign = Paint.Align.CENTER }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        setMeasuredDimension(width, (width * 0.62f + 30 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val ph = w * 0.62f
        val r = 16 * d
        val box = RectF(1.5f * d, 1.5f * d, w - 1.5f * d, ph)
        c.drawRoundRect(box, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.bg })
        c.save()
        c.clipPath(Path().apply { addRoundRect(box, r, r, Path.Direction.CW) })
        ThemeArt.draw(c, w, t, resources)
        c.restore()
        c.drawCircle(18 * d, ph - 26 * d, 5 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.accent })
        val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(Color.WHITE, 0.5f) }
        c.drawRoundRect(RectF(30 * d, ph - 28.5f * d, 74 * d, ph - 23.5f * d), 3 * d, 3 * d, bar)
        c.drawRoundRect(RectF(14 * d, ph - 16 * d, 60 * d, ph - 11 * d), 3 * d, 3 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(Color.WHITE, 0.2f) })
        c.drawRoundRect(box, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = if (selected) t.accent else withAlpha(Color.WHITE, 0.12f)
            strokeWidth = (if (selected) 2.5f else 1f) * d
        })
        namePaint.color = if (selected) 0xFFF5F5F7.toInt() else 0xFF9A9AA2.toInt()
        namePaint.typeface = Typeface.create("sans-serif-medium", if (selected) Typeface.BOLD else Typeface.NORMAL)
        c.drawText(t.name, w / 2, ph + 21 * d, namePaint)
    }
}

/** The Themes tab. [onPick] is called after the choice is saved, so the screen can rebuild itself. */
class ThemeSettingsView(ctx: Context, private val onPick: () -> Unit, showHeader: Boolean = true) : LinearLayout(ctx) {
    init {
        val d = ctx.resources.displayMetrics.density
        val current = ThemeStore.get(ctx)
        orientation = VERTICAL
        if (showHeader) {
            setPadding((18 * d).toInt(), (12 * d).toInt(), (18 * d).toInt(), (24 * d).toInt())
            addView(TextView(ctx).apply { text = "Settings"; textSize = 24f; setTextColor(current.text); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) })
        }
        addView(TextView(ctx).apply {
            text = "Pick a look. It changes the colours, the banner and the little animations."
            textSize = 14f; setTextColor(current.muted); setPadding(0, (4 * d).toInt(), 0, (16 * d).toInt())
        })
        ThemeStore.all.chunked(2).forEach { pair ->
            addView(LinearLayout(ctx).apply {
                orientation = HORIZONTAL
                pair.forEachIndexed { i, t ->
                    addView(ThemeTile(ctx, t, t.id == current.id).apply {
                        isClickable = true
                        Fx.press(this)
                        setOnClickListener { ThemeStore.set(ctx, t); onPick() }
                    }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { if (i == 0) rightMargin = (8 * d).toInt() else leftMargin = (8 * d).toInt() })
                }
                if (pair.size == 1) addView(View(ctx), LayoutParams(0, 1, 1f))
            }, LayoutParams(-1, -2).apply { bottomMargin = (10 * d).toInt() })
        }
    }
}

/**
 * Searching screen artwork: a dark glossy blob that slowly changes shape, two thin wobbling rings, a ripple from the centre,
 * and small dots, squares and triangles drifting on the rings. Animates itself from the clock while on screen.
 */
class OrbView(ctx: Context, private val theme: AppTheme) : View(ctx) {
    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val t = (SystemClock.uptimeMillis() % 3_600_000L) / 1000.0
        val cx = width / 2f
        val cy = height / 2f
        val r0 = minOf(width, height) * 0.25f

        fun blob(scale: Float, phase: Double, amp: Float): Path {
            val p = Path()
            val n = 96
            for (i in 0..n) {
                val a = i.toDouble() / n * 2 * Math.PI
                val wobble = sin(3 * a + phase) * 0.6 + sin(5 * a - phase * 0.7) * 0.4 + sin(2 * a + phase * 0.5) * 0.5
                val r = r0 * scale * (1 + amp * wobble.toFloat())
                val x = cx + cos(a).toFloat() * r
                val y = cy + sin(a).toFloat() * r
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
            return p
        }
        fun line(color: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; this.color = color; strokeWidth = w; strokeCap = Paint.Cap.ROUND }

        c.drawPath(blob(1.62f, t * 0.3, 0.05f), line(withAlpha(Color.WHITE, 0.13f), d))
        c.drawPath(blob(1.42f, -t * 0.26 + 1.3, 0.06f), line(withAlpha(Color.WHITE, 0.2f), d))

        val pulse = ((t / 2.6) % 1.0).toFloat()
        c.drawPath(blob(1f + 0.55f * pulse, t * 0.7, 0.07f), line(withAlpha(theme.accent, (1 - pulse) * 0.32f), 1.2f * d))

        val core = blob(1f, t * 0.7, 0.07f)
        c.drawPath(core, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx + r0 * 0.38f, cy - r0 * 0.3f, r0 * 1.35f, intArrayOf(0xFF575757.toInt(), 0xFF1F1F1F.toInt(), 0xFF121212.toInt()),
                floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        })
        c.drawPath(core, line(withAlpha(Color.WHITE, 0.09f), d))
        c.drawArc(RectF(cx - r0 * 0.72f, cy - r0 * 0.72f, cx + r0 * 0.72f, cy + r0 * 0.72f), 120f, 90f, false, line(withAlpha(Color.WHITE, 0.1f), 1.2f * d))

        ThemeArt.orbExtras(c, cx, cy, r0, theme, resources, t, d)
        val themed = theme.motif == Motif.BAT || theme.motif == Motif.MATRIX
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until (if (themed) 0 else 16)) {
            val f = i.toDouble()
            val ring = if (i % 2 == 0) 1.62f else 1.42f
            val dir = if (i % 3 == 0) -1.0 else 1.0
            val a = f * 0.83 + t * (0.1 + 0.03 * (i % 4)) * dir
            val r = r0 * ring * (1 + 0.05f * sin(3 * a).toFloat())
            val x = cx + cos(a).toFloat() * r
            val y = cy + sin(a).toFloat() * r
            val s = (1.6f + i % 3) * d
            dot.color = withAlpha(Color.WHITE, maxOf(0.08f, 0.3f + 0.3f * sin(t * 0.9 + f).toFloat()))
            when (i % 3) {
                0 -> c.drawCircle(x, y, s, dot)
                1 -> { c.save(); c.rotate(Math.toDegrees(a + t * 0.4).toFloat(), x, y); c.drawRect(x - s, y - s, x + s, y + s, dot); c.restore() }
                else -> {
                    c.save(); c.rotate(Math.toDegrees(a * 2).toFloat(), x, y)
                    val tri = Path().apply { moveTo(x, y - s * 1.3f); lineTo(x + s * 1.2f, y + s); lineTo(x - s * 1.2f, y + s); close() }
                    c.drawPath(tri, dot); c.restore()
                }
            }
        }
        val breathe = 1f + 0.06f * sin(t * 2).toFloat()
        c.save(); c.scale(breathe, breathe, cx, cy)
        ThemeArt.glyph(c, cx, cy, 44 * d, theme, theme.accent, resources)
        c.restore()
        postInvalidateOnAnimation()
    }
}
