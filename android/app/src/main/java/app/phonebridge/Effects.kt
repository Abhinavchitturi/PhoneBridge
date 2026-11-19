package app.phonebridge

import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

enum class FxKind { TAP, TRANSITION, CONNECT, OPEN }

/** Small themed animations on top of the screen: a puff where you press, a flourish when the tab changes, a celebration on connect. */
object Fx {
    var view: FxView? = null

    fun tap(x: Float, y: Float) { view?.play(FxKind.TAP, x, y) }
    fun transition() { view?.play(FxKind.TRANSITION, -1f, -1f) }
    private var opened = false
    /** Plays the theme's opening sequence the first time the app comes up after launch. */
    fun openOnce() {
        if (opened) return
        opened = true
        view?.play(FxKind.OPEN, -1f, -1f)
    }

    fun connect() {
        view?.removeOpening()
        view?.play(FxKind.CONNECT, -1f, -1f)
        view?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    /** Press feel for any tappable view: it dips and springs back, you feel a tick, and the theme puffs out a little effect. */
    fun press(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.animate().cancel()
                    view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80).start()
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
                MotionEvent.ACTION_UP -> {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(340).setInterpolator(OvershootInterpolator(4f)).start()
                    val loc = IntArray(2)
                    view.getLocationOnScreen(loc)
                    tap(loc[0] + e.x, loc[1] + e.y)
                }
                MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
            }
            false
        }
    }
}

class FxView(ctx: Context, private val theme: AppTheme) : View(ctx) {
    private class Event(val kind: FxKind, val x: Float, val y: Float, val start: Long, val duration: Long)
    private val events = ArrayList<Event>()

    fun play(kind: FxKind, screenX: Float, screenY: Float) {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val x = if (screenX < 0) width / 2f else screenX - loc[0]
        val y = if (screenY < 0) height / 2f else screenY - loc[1]
        val duration = when (kind) { FxKind.TAP -> 550L; FxKind.TRANSITION -> 600L; FxKind.CONNECT -> 3800L; FxKind.OPEN -> 3600L }
        events.add(Event(kind, x, y, SystemClock.uptimeMillis(), duration))
        invalidate()
    }

    /** A connection right after launch replaces the opening sequence instead of stacking on it. */
    fun removeOpening() { events.removeAll { it.kind == FxKind.OPEN } }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val d = resources.displayMetrics.density
        events.removeAll { now - it.start >= it.duration }
        for (e in events) FxArt.draw(c, width.toFloat(), height.toFloat(), theme, e.kind, (now - e.start).toDouble() / e.duration, e.x, e.y, resources, d)
        if (events.isNotEmpty()) postInvalidateOnAnimation()
    }
}

object FxArt {
    private fun a(color: Int, alpha: Float) = (color and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24)

    fun draw(c: Canvas, w: Float, h: Float, t: AppTheme, kind: FxKind, p: Double, ox: Float, oy: Float, res: Resources, d: Float) {
        when (kind) {
            FxKind.TAP -> tap(c, t, p, ox, oy, res, d)
            FxKind.TRANSITION -> transition(c, w, h, t, p, res, d)
            FxKind.CONNECT -> connect(c, w, h, t, p, ox, oy, res, d)
            FxKind.OPEN -> ThemeSeq.draw(c, w, h, t, p, res, d)
        }
    }

    private fun tap(c: Canvas, t: AppTheme, p: Double, ox: Float, oy: Float, res: Resources, d: Float) {
        val e = (1 - (1 - p).pow(3)).toFloat()
        val fade = (1 - p).toFloat()
        when (t.motif) {
            Motif.ARCH -> for (k in 0 until 2) {
                val pp = max(0.0, p - k * 0.15)
                val r = (8 + 36 * (1 - (1 - pp).pow(3)).toFloat()) * d
                c.drawCircle(ox, oy, r, ThemeArt.stroke(a(Color.WHITE, 0.5f * (1 - pp).toFloat()), 1.4f * d))
            }
            Motif.BAT -> for (i in 0 until 4) {
                val ang = -Math.PI / 2 + (i - 1.5) * 0.65
                val dist = (8 + 48 * e) * d
                ThemeArt.pictureT(c, res, R.drawable.themeart_bat, ox + cos(ang).toFloat() * dist, oy + sin(ang).toFloat() * dist, 18 * d, fade,
                    (0.5 + 0.5 * abs(sin(p * 30 + i))).toFloat())
            }
            Motif.SPIDER -> {
                val web = Path()
                for (k in 0 until 8) {
                    val ang = k * Math.PI / 4
                    web.moveTo(ox + cos(ang).toFloat() * 6 * d, oy + sin(ang).toFloat() * 6 * d)
                    web.lineTo(ox + cos(ang).toFloat() * (10 + 38 * e) * d, oy + sin(ang).toFloat() * (10 + 38 * e) * d)
                }
                for (ring in 1..2) web.addCircle(ox, oy, 14f * ring * e * d, Path.Direction.CW)
                c.drawPath(web, ThemeArt.stroke(a(t.accent2, 0.9f * fade), d))
            }
            Motif.REACTOR -> {
                c.drawCircle(ox, oy, (8 + 40 * e) * d, ThemeArt.stroke(a(t.accent, 0.9f * fade), (2.2f * fade + 0.5f) * d))
                val sparks = Path()
                for (k in 0 until 8) {
                    val ang = k * Math.PI / 4 + 0.2
                    val r0 = (10 + 34 * e) * d
                    sparks.moveTo(ox + cos(ang).toFloat() * r0, oy + sin(ang).toFloat() * r0)
                    sparks.lineTo(ox + cos(ang).toFloat() * (r0 + 9 * d), oy + sin(ang).toFloat() * (r0 + 9 * d))
                }
                c.drawPath(sparks, ThemeArt.stroke(a(Color.WHITE, fade), 1.6f * d))
            }
            Motif.THOR -> if (p < 0.7) {
                val f = (1 - p / 0.7).toFloat()
                for (k in 0 until 3) {
                    val ang = k * 2.1 + ThemeArt.hash(floor(p * 10)) * 1.5
                    val bolt = ThemeArt.lightning(ox, oy, ox + cos(ang).toFloat() * 42 * d, oy + sin(ang).toFloat() * 42 * d, k * 4 + floor(p * 10), 5, 7 * d)
                    ThemeArt.bolt2(c, bolt, t.accent, f, 1.4f * d)
                }
            }
            Motif.MATRIX -> {
                val chars = ThemeArt.rainChars
                for (k in 0 until 6) {
                    val ch = chars[(ThemeArt.hash(k + floor(p * 8)) * chars.length).toInt() % chars.length].toString()
                    c.drawText(ch, ox + (k - 2.5f) * 9 * d, oy - 6 * d - 40 * e * d * (0.6f + 0.4f * ThemeArt.hash(k.toDouble()).toFloat()),
                        ThemeArt.rainPaint(12 * d, a(t.accent, fade)))
                }
            }
        }
    }

    private fun transition(c: Canvas, w: Float, h: Float, t: AppTheme, p: Double, res: Resources, d: Float) {
        val e = (1 - (1 - p).pow(3)).toFloat()
        val fade = (1 - p).toFloat()
        when (t.motif) {
            Motif.ARCH -> {
                // the logo draws itself: the bridge arch sweeps across, its two end dots pop in, a spark runs along it, then it fades
                val cx = w / 2; val cy = h / 2
                val r = min(w, h) * 0.17f
                val vis = min(1.0, min(p / 0.1, (1 - p) / 0.35)).toFloat()
                val arch = Path().apply { moveTo(cx - r, cy + r * 0.35f); quadTo(cx, cy - r * 1.15f, cx + r, cy + r * 0.35f) }
                val pm = PathMeasure(arch, false)
                val part = Path()
                pm.getSegment(0f, pm.length * min(1.0, p / 0.55).toFloat(), part, true)
                c.drawPath(part, ThemeArt.stroke(a(Color.WHITE, 0.18f * vis), 9f * d))
                c.drawPath(part, ThemeArt.stroke(a(Color.WHITE, 0.95f * vis), 2.4f * d))
                for (i in 0..1) {
                    val pop = min(1.0, max(0.0, (p - (if (i == 0) 0.0 else 0.5)) / 0.18)).toFloat()
                    val dr = 5.5f * d * (0.4f + 0.6f * pop)
                    val x = if (i == 0) cx - r else cx + r
                    c.drawCircle(x, cy + r * 0.35f, dr * 2, ThemeArt.fill(a(Color.WHITE, 0.12f * vis * pop)))
                    c.drawCircle(x, cy + r * 0.35f, dr, ThemeArt.fill(a(Color.WHITE, vis * pop)))
                }
                val tt = min(1.0, p / 0.7).toFloat(); val u = 1 - tt
                val sx = u * u * (cx - r) + 2 * u * tt * cx + tt * tt * (cx + r)
                val sy = u * u * (cy + r * 0.35f) + 2 * u * tt * (cy - r * 1.15f) + tt * tt * (cy + r * 0.35f)
                ThemeArt.glow(c, sx, sy, 20 * d, Color.WHITE, 0.7f * vis)
            }
            Motif.BAT -> {
                // the bat emblem flares up like the signal in the sky: it swells in a golden oval of light that pulses outwards
                val cx = w / 2; val cy = h / 2
                val vis = min(1.0, min(p / 0.1, (1 - p) / 0.4)).toFloat()
                val swell = (0.55 + 0.6 * min(1.0, p / 0.4) - 0.1 * max(0.0, (p - 0.4) / 0.6)).toFloat()
                ThemeArt.glow(c, cx, cy, 120 * d * swell, t.accent, 0.45f * vis)
                for (k in 0 until 2) {
                    val pp = ((p - k * 0.15) / (1 - k * 0.15)).coerceIn(0.0, 1.0)
                    val rw = 50 * d + (1 - (1 - pp).pow(3)).toFloat() * min(w, h) * 0.4f
                    c.drawOval(cx - rw, cy - rw * 0.62f, cx + rw, cy + rw * 0.62f, ThemeArt.stroke(a(t.accent, (0.75 * (1 - pp)).toFloat()), 2f * d))
                }
                ThemeArt.pictureT(c, res, R.drawable.themeart_batoval, cx, cy, 110 * d * swell, vis)
            }
            Motif.SPIDER -> {
                // a small thing: the spider mask pops at the centre while two thin web rings spread out from it
                val cx = w / 2; val cy = h / 2
                val pop = (0.55 + 0.6 * min(1.0, p / 0.35) - 0.15 * max(0.0, (p - 0.35) / 0.65)).toFloat()
                val vis = min(1.0, min(p / 0.12, (1 - p) / 0.45)).toFloat()
                for (k in 0 until 2) {
                    val pp = ((p - k * 0.14) / (1 - k * 0.14)).coerceIn(0.0, 1.0)
                    val r = 30 * d + (1 - (1 - pp).pow(3)).toFloat() * min(w, h) * 0.34f
                    val ring = Path(); val spokes = Path()
                    for (i in 0..8) {
                        val an = i / 8.0 * 2 * Math.PI + 0.3
                        val x = cx + cos(an).toFloat() * r; val y = cy + sin(an).toFloat() * r
                        if (i == 0) ring.moveTo(x, y) else ring.lineTo(x, y)
                        spokes.moveTo(cx, cy); spokes.lineTo(x, y)
                    }
                    c.drawPath(ring, ThemeArt.stroke(a(t.accent, (0.7 * (1 - pp)).toFloat()), 1.4f * d))
                    if (k == 0) c.drawPath(spokes, ThemeArt.stroke(a(Color.WHITE, (0.22 * (1 - pp)).toFloat()), 0.8f * d))
                }
                ThemeArt.pictureT(c, res, R.drawable.themeart_mask, cx, cy, 74 * d * pop, vis)
            }
            Motif.REACTOR -> {
                // a small thing: the arc reactor lights up at the centre, spins, and pulses rings of light outwards
                val cx = w / 2; val cy = h / 2
                val vis = min(1.0, min(p / 0.12, (1 - p) / 0.4)).toFloat()
                ThemeArt.glow(c, cx, cy, (90 + 60 * p).toFloat() * d, t.accent, 0.55f * vis)
                for (k in 0 until 3) {
                    val pp = ((p - k * 0.12) / (1 - k * 0.12)).coerceIn(0.0, 1.0)
                    val r = 28 * d + (1 - (1 - pp).pow(3)).toFloat() * min(w, h) * 0.38f
                    c.drawCircle(cx, cy, r, ThemeArt.stroke(a(t.accent, ((if (k == 0) 0.8 else 0.45) * (1 - pp)).toFloat()), (if (k == 0) 2f else 1.2f) * d))
                }
                ThemeArt.pictureT(c, res, R.drawable.themeart_reactor, cx, cy, 72 * d * (0.7f + 0.35f * min(1.0, p / 0.3).toFloat()), vis, 1f, (p * 2.2).toFloat())
            }
            Motif.THOR -> {
                c.drawRect(0f, 0f, w, h, ThemeArt.fill(a(0xFFBFE3FF.toInt(), max(0.0, 0.22 - p * 0.5).toFloat())))
                if (p < 0.5) {
                    val x0 = w * (0.2f + 0.6f * ThemeArt.hash(floor(p * 12)).toFloat())
                    ThemeArt.bolt2(c, ThemeArt.lightning(x0, 0f, x0 + 40 * d, h * 0.55f, floor(p * 12), 8, 22 * d), t.accent, 1f, 1.8f * d)
                }
            }
            Motif.MATRIX -> {
                val chars = ThemeArt.rainChars
                for (col in 0 until 22) {
                    val x = w * (col + 0.5f) / 22
                    val head = (p * (1.6 + ThemeArt.hash(col.toDouble()))).toFloat() * h
                    for (r in 0 until 8) {
                        val y = head - r * 16 * d
                        if (y < 0 || y > h) continue
                        val ch = chars[(ThemeArt.hash((col * 13 + r).toDouble() + floor(p * 12)) * chars.length).toInt() % chars.length].toString()
                        val al = ((if (r == 0) 0.9 else 0.5 * (1 - r / 8.0)) * fade).toFloat()
                        c.drawText(ch, x, y, ThemeArt.rainPaint(13 * d, if (r == 0) a(Color.WHITE, al) else a(t.accent, al)))
                    }
                }
            }
        }
    }

    private fun connect(c: Canvas, w: Float, h: Float, t: AppTheme, p: Double, ox: Float, oy: Float, res: Resources, d: Float) {
        ThemeSeq.draw(c, w, h, t, p, res, d)
        val k = min(1.0, max(0.0, (p - 0.5) / 0.12))
        if (k <= 0.0) return
        val out = if (p > 0.9) max(0.0, 1 - (p - 0.9) / 0.1).toFloat() else 1f
        val cx = w / 2
        val cy = h * 0.12f
        val r = 28 * d * easeOutBack(k).toFloat()
        c.drawCircle(cx, cy, r, ThemeArt.fill(a(t.accent, 0.95f * out)))
        val draw = min(1.0, max(0.0, (p - 0.55) / 0.12)).toFloat()
        val check = Path().apply { moveTo(cx - 11 * d, cy + d); lineTo(cx - 3 * d, cy + 9 * d); lineTo(cx + 12 * d, cy - 9 * d) }
        val pm = PathMeasure(check, false)
        val seg = Path()
        pm.getSegment(0f, pm.length * draw, seg, true)
        c.drawPath(seg, ThemeArt.stroke(a(t.onAccent, out), 4 * d))
    }

    private fun easeOutBack(x: Double): Double {
        val c1 = 1.70158
        val c3 = c1 + 1
        return 1 + c3 * (x - 1).pow(3) + c1 * (x - 1).pow(2)
    }
}
