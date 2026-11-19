package app.phonebridge

import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** The animated banner at the top of the home screen: one small scene per theme. Mirrors mac/Sources/MotifScenes.swift. */
object ThemeScene {
    private fun fillRect(c: Canvas, r: RectF, shader: Shader) = c.drawRect(r, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })

    private fun radial(c: Canvas, r: RectF, cx: Float, cy: Float, radius: Float, color: Int) =
        fillRect(c, r, RadialGradient(cx, cy, radius, color, Color.TRANSPARENT, Shader.TileMode.CLAMP))

    private fun cover(c: Canvas, res: Resources, id: Int, rect: RectF, zoom: Float, fx: Float, fy: Float, alpha: Float) {
        val b = ThemeArt.bitmap(res, id)
        val s = max(rect.width() / b.width, rect.height() / b.height) * zoom
        val dw = b.width * s
        val dh = b.height * s
        val x = min(rect.left, max(rect.right - dw, rect.centerX() - fx * dw))
        val y = min(rect.top, max(rect.bottom - dh, rect.centerY() - fy * dh))
        c.save()
        c.clipRect(rect)
        c.drawBitmap(b, null, RectF(x, y, x + dw, y + dh), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.alpha = (alpha * 255).toInt() })
        c.restore()
    }

    private fun optionalDrawable(res: Resources, name: String) = res.getIdentifier(name, "drawable", "app.phonebridge")

    private fun hashD(x: Double) = ThemeArt.hash(x)

    /** A band of building silhouettes that slides past, with a few lit windows. */
    fun city(c: Canvas, w: Float, time: Double, speed: Double, baseline: Float, color: Int, seed: Double, hMin: Float, hMax: Float, windows: Int?, d: Float) {
        val period = 520f * d
        val blocks = ArrayList<Triple<Float, Float, Float>>()
        var x = 0f
        var i = 0.0
        while (x < period) {
            val bw = (28 + hashD(seed + i).toFloat() * 44) * d
            val bh = (hMin + hashD(seed + i + 50).toFloat() * (hMax - hMin)) * d
            blocks.add(Triple(x, bw, bh))
            x += bw + 2 * d
            i += 1
        }
        val off = ((time * speed * d) % period).toFloat()
        val fillP = ThemeArt.fill(color)
        for (rep in -1 until (w / period).toInt() + 2) {
            blocks.forEachIndexed { n, b ->
                val rx = b.first - off + rep * period
                if (rx > w || rx + b.second < 0) return@forEachIndexed
                c.drawRect(rx, baseline - b.third, rx + b.second, baseline, fillP)
                if (windows != null) for (k in 0 until 3) {
                    val wx = rx + (5 + k * 11) * d
                    val wy = baseline - b.third + 8 * d + hashD((n * 7 + k) + seed).toFloat() * (b.third - 18 * d)
                    val tw = 0.25 + 0.5 * abs(sin(time * 0.7 + n + k))
                    c.drawRect(wx, wy, wx + 3 * d, wy + 4 * d, ThemeArt.fill((windows and 0x00FFFFFF) or ((0.4 * tw * 255).toInt() shl 24)))
                }
            }
        }
    }

    /** Spider-Man on a web: a real pendulum, let go on the way up, a short flight, a new web, and another swing. Mirrors MotifSwing.swift. */
    fun swing(c: Canvas, w: Float, t: AppTheme, res: Resources, time: Double, ref: Float, top: Float = 0f) {
        val L = ref * 0.86f
        val theta = 0.9
        val u0 = 0.2
        val u1 = 0.85
        val thS = -theta * cos(Math.PI * u0)
        val thE = -theta * cos(Math.PI * u1)
        val gap = ref * 0.32f
        val span = L * (sin(thE) - sin(thS)).toFloat()
        val dStep = span + gap
        val margin = ref * 0.55f
        val nWrap = max(2, kotlin.math.ceil(((w + 2 * margin) / dStep).toDouble()).toInt())
        val swingT = 1.15
        val flightT = 0.32
        val segT = swingT + flightT
        val anchorY = top - ref * 0.18f
        fun anchorX(k: Int) = -margin - L * sin(thS).toFloat() + k * dStep
        fun onRope(k: Int, u: Double): Pair<Float, Float> {
            val th = -theta * cos(Math.PI * u)
            return (anchorX(k) + L * sin(th).toFloat()) to (anchorY + L * cos(th).toFloat())
        }
        class Pos(val x: Float, val y: Float, val k: Int, val loc: Double, val onWeb: Boolean)
        fun position(tm: Double): Pos {
            val tt = tm % (segT * nWrap)
            val k = (tt / segT).toInt()
            val loc = tt - k * segT
            if (loc < swingT) { val pr = onRope(k, u0 + (u1 - u0) * loc / swingT); return Pos(pr.first, pr.second, k, loc, true) }
            val s = (loc - swingT) / flightT
            val e = onRope(k, u1)
            val st = onRope(k + 1, u0)
            val lift = ref * 0.2f * (4 * s * (1 - s)).toFloat()
            return Pos(e.first + (st.first - e.first) * s.toFloat(), e.second + (st.second - e.second) * s.toFloat() - lift, k, loc, false)
        }
        val now = position(time)
        val poseId = if (now.k % 2 == 0) R.drawable.themeart_swing1 else R.drawable.themeart_swing_a
        val fig = ThemeArt.bitmap(res, poseId)
        val backHand = if (now.k % 2 == 0) (0.03f to 0.42f) else (0.12f to 0.08f)
        val frontHand = if (now.k % 2 == 0) (0.92f to 0.05f) else (0.56f to 0.1f)
        val ahead = position(time + 0.03)
        val heading = Math.atan2((ahead.y - now.y).toDouble(), max(0.001f, ahead.x - now.x).toDouble())
        val rot = heading * 0.75
        val fit = ref * 0.7f
        val figH = fit * fig.height / fig.width
        fun hand(back: Boolean): Pair<Float, Float> {
            // stills face left and are mirrored to face right (his travel); the raised hand behind him holds the web while the anchor is behind
            val hp = if (back) frontHand else backHand
            val lx = (0.5f - hp.first) * fit
            val ly = (hp.second - 0.5f) * figH
            return (now.x + lx * cos(rot).toFloat() - ly * sin(rot).toFloat()) to (now.y + lx * sin(rot).toFloat() + ly * cos(rot).toFloat())
        }
        val ax = anchorX(now.k)
        val web = Path()
        val d = ref / 190f
        if (now.onWeb) {
            val hd = hand(ax < now.x)
            val grow = min(1.0, now.loc / (swingT * 0.14)).toFloat()
            web.moveTo(hd.first, hd.second)
            web.lineTo(hd.first + (ax - hd.first) * grow, hd.second + (anchorY - hd.second) * grow)
            c.drawPath(web, ThemeArt.stroke(0x2EFFFFFF, 5 * d))
            c.drawPath(web, ThemeArt.stroke(0xE6FFFFFF.toInt(), 1.3f * d))
        } else {
            val sFall = ((now.loc - swingT) / flightT).toFloat()
            val hd = hand(true)
            web.moveTo(hd.first, hd.second)
            web.lineTo(hd.first + (ax - hd.first) * (1 - sFall * 0.6f), hd.second + (anchorY - hd.second) * (1 - sFall * 0.6f))
            c.drawPath(web, ThemeArt.stroke(((0.7f * (1 - sFall)).coerceIn(0f, 1f) * 255).toInt() shl 24 or 0xFFFFFF, d))
        }
        ThemeArt.pictureT(c, res, poseId, now.x, now.y, fit, 1f, 1f, rot.toFloat(), true)
    }

    /** The Batmobile rolling left to right with its lights on. */
    fun batmobile(c: Canvas, w: Float, t: AppTheme, res: Resources, time: Double, roadY: Float, fit: Float) {
        val d = fit / 150f
        val span = w + fit * 2.4f
        val x = -fit * 1.2f + ((time * 130 * d) % span).toFloat()
        val bounce = sin(time * 16).toFloat() * 0.8f * d
        c.drawRect(0f, roadY, w, roadY + d, ThemeArt.fill(0x14FFFFFF))
        for (i in 0 until 7) {
            val sx = x - fit * 0.5f - i * 22 * d - hashD(i.toDouble()).toFloat() * 14 * d
            val sy = roadY - 6 * d - hashD(i + 5.0).toFloat() * fit * 0.3f
            c.drawLine(sx, sy, sx - (26 + hashD(i + 9.0).toFloat() * 30) * d, sy, ThemeArt.stroke(0x1FFFFFFF, d))
        }
        val noseX = x + fit * 0.42f
        val noseY = roadY - fit * 0.2f
        val cone = Path().apply { moveTo(noseX, noseY); lineTo(noseX + 190 * d, noseY - 40 * d); lineTo(noseX + 190 * d, noseY + 30 * d); close() }
        c.drawPath(cone, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(noseX, noseY, noseX + 190 * d, noseY, (t.accent and 0x00FFFFFF) or (0x61 shl 24), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        })
        c.drawOval(RectF(x - fit * 0.55f, roadY - 6 * d, x + fit * 0.55f, roadY + 6 * d), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(x, roadY, fit * 0.55f, (t.accent and 0x00FFFFFF) or (0x40 shl 24), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        })
        ThemeArt.pictureT(c, res, R.drawable.themeart_batmobile, x, roadY - fit * 0.2f + bounce, fit, 1f)
    }

    /** Full-screen motion behind the searching screen for themes with a moving character. */
    fun ambient(c: Canvas, w: Float, h: Float, t: AppTheme, res: Resources, time: Double, d: Float) {
        when (t.motif) {
            Motif.SPIDER -> {
                city(c, w, time, 10.0, h * 0.62f, 0xFF14040A.toInt(), 11.0, 60f, 170f, null, d)
                swing(c, w, t, res, time, 230f * d, 10f * d)
            }
            Motif.BAT -> batmobile(c, w, t, res, time, h - 26 * d, 190f * d)
            else -> {}
        }
    }

    fun draw(c: Canvas, w: Float, h: Float, t: AppTheme, res: Resources, time: Double) {
        val rect = RectF(0f, 0f, w, h)
        c.drawRect(rect, ThemeArt.fill(t.bg))
        val d = h / 190f   // the Mac banner is 190 tall; sizes below are in those units
        when (t.motif) {
            Motif.ARCH -> {
                radial(c, rect, w * (0.3f + 0.12f * sin(time * 0.2).toFloat()), h * 0.35f, w * 0.45f, 0x14FFFFFF)
                radial(c, rect, w * (0.72f + 0.1f * cos(time * 0.17).toFloat()), h * 0.6f, w * 0.4f, 0x0FFFFFFF)
                for (i in 0 until 46) {
                    val f = i.toDouble()
                    val tw = 0.3 + 0.7 * abs(sin(time * 0.8 + ThemeArt.hash(f + 200) * 6))
                    val r = ((0.5 + ThemeArt.hash(f + 300) * 0.9) * d).toFloat()
                    c.drawCircle(ThemeArt.hash(f).toFloat() * w, ThemeArt.hash(f + 100).toFloat() * h * 0.75f, r, ThemeArt.fill(withAlphaF(Color.WHITE, (0.18 * tw).toFloat())))
                }
                val lx = w * 0.16f; val rx = w * 0.84f; val ny = h * 0.5f; val cx = w * 0.5f; val cy = -h * 0.4f
                val arc = Path().apply { moveTo(lx, ny); quadTo(cx, cy, rx, ny) }
                c.drawPath(arc, ThemeArt.stroke(withAlphaF(Color.WHITE, 0.26f), 1.4f * d))
                fun qx(s: Double): Float { val a = (1 - s).toFloat(); val b = s.toFloat(); return a * a * lx + 2 * a * b * cx + b * b * rx }
                fun qy(s: Double): Float { val a = (1 - s).toFloat(); val b = s.toFloat(); return a * a * ny + 2 * a * b * cy + b * b * ny }
                for (k in 0 until 6) {
                    val dir = if (k % 2 == 0) 1.0 else -1.0
                    val ph = (time * 0.2 + k / 6.0) % 1.0
                    val edge = sin(Math.PI * ph).toFloat()
                    for (tr in 0 until 9) {
                        val s = ((if (dir > 0) ph else 1 - ph) - dir * tr * 0.013).coerceIn(0.0, 1.0)
                        val r = max(0.6f, 2.8f - 0.28f * tr) * d
                        c.drawCircle(qx(s), qy(s), r, ThemeArt.fill(withAlphaF(Color.WHITE, (1 - tr / 9f) * 0.85f * edge)))
                    }
                }
                for (i in 0 until 2) {
                    val px = if (i == 0) lx else rx
                    val frac = ((time * 0.5 + i * 0.5) % 1.0).toFloat()
                    c.drawCircle(px, ny, (13 + 12 * frac) * d, ThemeArt.stroke(withAlphaF(Color.WHITE, (1 - frac) * 0.3f), d))
                    c.drawCircle(px, ny, 13 * d, ThemeArt.fill(t.bg))
                    c.drawCircle(px, ny, 13 * d, ThemeArt.stroke(withAlphaF(Color.WHITE, 0.55f), d))
                    val ic = ThemeArt.stroke(withAlphaF(Color.WHITE, 0.9f), 1.2f * d)
                    if (i == 0) c.drawRoundRect(RectF(px - 3.5f * d, ny - 6.5f * d, px + 3.5f * d, ny + 6.5f * d), 1.8f * d, 1.8f * d, ic)
                    else {
                        c.drawRect(RectF(px - 6 * d, ny - 5 * d, px + 6 * d, ny + 2.5f * d), ic)
                        c.drawLine(px - 8 * d, ny + 5 * d, px + 8 * d, ny + 5 * d, ic)
                    }
                }
            }

            Motif.BAT -> {
                fillRect(c, rect, LinearGradient(0f, 0f, 0f, h, t.bg, 0xFF16130A.toInt(), Shader.TileMode.CLAMP))
                for (i in 0 until 3) {
                    val cx = ((time * (6 + 3 * i) * d + i * w * 0.4) % (w * 1.4)).toFloat() - w * 0.2f
                    radial(c, rect, cx, h * (0.12f + 0.2f * i), 90 * d, 0x0DFFFFFF)
                }
                val sx = w * 0.72f; val sy = h * 0.38f; val sr = h * 0.36f
                val sway = sin(time * 0.6).toFloat() * 8 * d
                val bx = w * 0.22f; val by = h + 4 * d
                val beam = Path().apply {
                    moveTo(bx - 12 * d, by); lineTo(bx + 12 * d, by); lineTo(sx + sr * 0.85f + sway, sy); lineTo(sx - sr * 0.85f + sway, sy); close()
                }
                c.drawPath(beam, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(bx, by, sx, sy, withAlphaF(t.accent, 0f), withAlphaF(t.accent, 0.24f), Shader.TileMode.CLAMP) })
                val pulse = (0.5 + 0.5 * sin(time * 1.4)).toFloat()
                c.drawCircle(sx + sway, sy, sr, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = RadialGradient(sx + sway, sy, sr, withAlphaF(t.accent, 0.5f + 0.2f * pulse), withAlphaF(t.accent, 0.1f), Shader.TileMode.CLAMP)
                })
                ThemeArt.pictureT(c, res, R.drawable.themeart_batoval, sx + sway, sy, sr * 1.6f, 0.95f)
                for (i in 0 until 5) {
                    val f = i.toDouble()
                    val travel = ((time * (34 + 9 * f) + f * 150) * d) % (w + 100 * d)
                    val y = h * (0.16f + 0.13f * i) + 8 * d * sin(time * 2 + f).toFloat()
                    ThemeArt.pictureT(c, res, R.drawable.themeart_bat, w + 30 * d - travel.toFloat(), y, (26 + (i % 2) * 6) * d, 0.8f,
                        (0.62 + 0.38 * abs(sin(time * 9 + f * 1.7))).toFloat())
                }
                batmobile(c, w, t, res, time, h - 8 * d, min(150 * d, w * 0.34f))
            }

            Motif.SPIDER -> {
                fillRect(c, rect, LinearGradient(0f, 0f, 0f, h, 0xFF2C0508.toInt(), t.bg, Shader.TileMode.CLAMP))
                radial(c, rect, w * 0.8f, h * 0.2f, w * 0.5f, 0x597A1018)
                city(c, w, time, 7.0, h, 0xFF1A0508.toInt(), 3.0, 60f, 125f, null, d)
                city(c, w, time, 16.0, h, 0xFF0A0204.toInt(), 21.0, 28f, 78f, t.accent2, d)
                ThemeArt.pictureT(c, res, R.drawable.themeart_web, w - 62 * d, 52 * d, 112 * d, 0.35f)
                swing(c, w, t, res, time, h)
            }

            Motif.REACTOR -> {
                fillRect(c, rect, LinearGradient(0f, 0f, 0f, h, 0xFF1D0D0A.toInt(), t.bg, Shader.TileMode.CLAMP))
                val pulse = (0.5 + 0.5 * sin(time * 2.2)).toFloat()
                ThemeArt.glow(c, w * 0.74f, h * 0.5f, h * 0.9f, t.accent, 0.12f + 0.1f * pulse)
                val grid = Path()
                for (i in 1 until 8) { val gy = h * i / 8f; grid.moveTo(0f, gy); grid.lineTo(w, gy) }
                c.drawPath(grid, ThemeArt.stroke(0x0DFFC21A, 0.6f * d))
                val rx = w * 0.14f; val ry = h * 0.66f
                val cyc = time % 3.2
                val fire = if (cyc < 0.55) (1 - cyc / 0.55).toFloat() else 0f
                ThemeArt.glow(c, rx, ry, 62 * d, t.accent, 0.25f + 0.4f * fire)
                ThemeArt.pictureT(c, res, R.drawable.themeart_reactor, rx, ry, 58 * d, 0.95f, 1f, (time * 0.8).toFloat())
                ThemeSeq.sprite(c, res, R.drawable.themeart_ironfig, w * 0.76f, h * 1.0f, h * 0.98f, rotation = 0.015 * sin(time * 0.8))
                if (fire > 0f) {
                    val beam = Path().apply { moveTo(rx, ry); lineTo(w * 0.4f, -h * 0.1f) }
                    c.drawPath(beam, ThemeArt.stroke(withAlphaF(t.accent, 0.5f * fire), 12 * fire * d))
                    c.drawPath(beam, ThemeArt.stroke(withAlphaF(Color.WHITE, 0.95f * fire), 3 * fire * d))
                    c.drawCircle(rx, ry, (18 + 56 * (1 - fire)) * d, ThemeArt.stroke(withAlphaF(t.accent, 0.7f * fire), 2 * d))
                }
                val mc = (time + 1.6) % 4.4
                if (mc < 1.1) {
                    val q = mc / 1.1
                    val ox = w * 0.66f; val oy = h * 0.42f; val tx = w * 0.12f; val ty = h * 0.12f; val cx1 = w * 0.5f; val cy1 = -h * 0.1f
                    ThemeSeq.missile(c, { u -> val a = (1 - u).toFloat(); val b = u.toFloat(); (a * a * ox + 2 * a * b * cx1 + b * b * tx) to (a * a * oy + 2 * a * b * cy1 + b * b * ty) }, q, t.accent, d / 2f)
                } else if (mc < 1.35) {
                    val k = ((mc - 1.1) / 0.25).toFloat()
                    ThemeArt.glow(c, w * 0.12f, h * 0.12f, (10 + 40 * k) * d, 0xFFFFB347.toInt(), 0.9f * (1 - k))
                }
            }

            Motif.THOR -> {
                for (i in 0 until 3) {
                    radial(c, rect, w * (0.2f + 0.3f * i) + 20 * d * sin(time * 0.3 + i).toFloat(), h * 0.25f, w * 0.35f, withAlphaF(t.accent2, 0.10f))
                }
                val period = 4.4
                val p = (time % period) / period
                val cx = w * 0.74f; val cy = h * 0.44f
                val sx = -30 * d; val sy = h * 0.92f
                c.drawCircle(cx, cy, 24 * d, ThemeArt.stroke(withAlphaF(t.accent, (0.25 + 0.15 * sin(time * 3)).toFloat()), 1.2f * d))
                fun posAt(q: Double): Pair<Float, Float> {
                    val e = (1 - (1 - q).pow(2)).toFloat()
                    return (sx + (cx - sx) * e) to (sy + (cy - sy) * e - sin(Math.PI * q).toFloat() * h * 0.3f)
                }
                if (p < 0.55) {
                    val q = p / 0.55
                    for (k in 1..5) {
                        val (tx, ty) = posAt(max(0.0, q - k * 0.03))
                        c.drawCircle(tx, ty, 4 * d, ThemeArt.fill(withAlphaF(t.accent, 0.3f * (1 - k / 6f))))
                    }
                    val (hx, hy) = posAt(q)
                    ThemeArt.pictureT(c, res, R.drawable.themeart_hammer, hx, hy, 56 * d, 1f, 1f, (q * 18).toFloat())
                } else {
                    val q = (p - 0.55) / 0.45
                    val flash = max(0.0, 1 - q * 3).toFloat()
                    radial(c, rect, cx, cy, 90 * d, withAlphaF(t.accent, 0.55f * flash))
                    if (flash > 0f) {
                        c.drawCircle(cx, cy, (20 + 90 * (1 - flash)) * d, ThemeArt.stroke(withAlphaF(Color.WHITE, 0.8f * flash), 2 * d))
                        for (k in 0 until 4) {
                            val a = k * (Math.PI / 2) + 0.5
                            val bolt = ThemeArt.lightning(cx, cy, cx + cos(a).toFloat() * 120 * d, cy + sin(a).toFloat() * 80 * d, k * 3 + floor(time / period), jitter = 10 * d)
                            ThemeArt.bolt2(c, bolt, t.accent, flash, 1.5f * d)
                        }
                    }
                    ThemeArt.pictureT(c, res, R.drawable.themeart_hammer, cx, cy, 56 * d, max(0f, 1 - max(0.0, q - 0.55).toFloat() * 2.2f), 1f, 0.45f)
                }
            }

            Motif.MATRIX -> {
                val cols = max(8, (w / (18 * d)).toInt())
                for (col in 0 until cols) {
                    val speed = 40 + 50 * ThemeArt.hash(col.toDouble())
                    val head = (time * speed + ThemeArt.hash(col + 3.0) * 400) % (h / d + 160) - 20
                    for (r in 0 until 12) {
                        val y = ((head - r * 15) * d).toFloat()
                        if (y < -8 * d || y > h + 8 * d) continue
                        val chars = ThemeArt.rainChars
                        val ch = chars[(ThemeArt.hash((col * 31 + r).toDouble() + floor(time * 5)) * chars.length).toInt() % chars.length].toString()
                        val alpha = (if (r == 0) 0.9 else 0.55 * (1 - r / 12.0)).toFloat()
                        c.drawText(ch, 10 * d + col * 18 * d, y + 4 * d, ThemeArt.rainPaint(12 * d, if (r == 0) withAlphaF(Color.WHITE, alpha) else withAlphaF(t.accent, alpha)))
                    }
                }
            }
        }
    }

    private fun withAlphaF(color: Int, a: Float) = (color and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)
}

/** The banner view. Animates itself from the clock while on screen. */
class HeroView(ctx: Context, private val theme: AppTheme) : View(ctx) {
    override fun onDraw(canvas: Canvas) {
        ThemeScene.draw(canvas, width.toFloat(), height.toFloat(), theme, resources, SystemClock.uptimeMillis() / 1000.0)
        postInvalidateDelayed(33)
    }
}

/** The full-screen backdrop for the searching screen. */
class AmbientView(ctx: Context, private val theme: AppTheme) : View(ctx) {
    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        ThemeScene.ambient(canvas, width.toFloat(), height.toFloat(), theme, resources, SystemClock.uptimeMillis() / 1000.0, d)
        if (theme.motif == Motif.SPIDER || theme.motif == Motif.BAT) postInvalidateDelayed(33)
    }
}
