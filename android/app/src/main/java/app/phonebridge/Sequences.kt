package app.phonebridge

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The character sequences played when a theme is picked, when the app opens and when the Mac connects. Mirrors MotifSequences.swift.
 * Thor drops in with lightning, looks up and leaps away; Spider-Man swings in, lands, looks up and swings off; Iron Man lands, looks up,
 * fires missiles across the sky and flies off; Batman brings the signal, bats and the Batmobile; Matrix rains code; Midnight draws a
 * constellation bridge.
 */
object ThemeSeq {
    private fun seg(p: Double, a: Double, b: Double) = ((p - a) / (b - a)).coerceIn(0.0, 1.0)
    private fun easeOut(x: Double) = 1 - (1 - x).pow(3)
    private fun easeIn(x: Double) = x * x * x
    private fun easeInOut(x: Double) = if (x < 0.5) 4 * x * x * x else 1 - (-2 * x + 2).pow(3) / 2
    private fun al(color: Int, a: Float) = (color and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)
    private fun hash(x: Double) = ThemeArt.hash(x)

    /** A cut-out pivoting around (px, py); (pivotX, pivotY) are fractions of the picture, (0.5, 1) is bottom middle. */
    fun sprite(c: Canvas, res: Resources, id: Int, px: Float, py: Float, height: Float, pivotX: Float = 0.5f, pivotY: Float = 1f,
               rotation: Double = 0.0, alpha: Float = 1f, flip: Boolean = false, scaleY: Float = 1f) {
        val b = ThemeArt.bitmap(res, id)
        val w = height * b.width / b.height
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt() }
        c.save()
        c.translate(px, py)
        c.rotate(Math.toDegrees(rotation).toFloat())
        c.scale(if (flip) -1f else 1f, scaleY)
        c.drawBitmap(b, null, RectF(-w * pivotX, -height * pivotY, w * (1 - pivotX), height * (1 - pivotY)), paint)
        c.restore()
    }

    private fun boltLine(c: Canvas, ax: Float, ay: Float, bx: Float, by: Float, seed: Double, color: Int, alpha: Float, width: Float, jitter: Float, segments: Int = 8) {
        if (alpha <= 0f) return
        ThemeArt.bolt2(c, ThemeArt.lightning(ax, ay, bx, by, seed, segments, jitter), color, alpha, width)
    }

    /** A missile in flight with a smoke trail. */
    fun missile(c: Canvas, path: (Double) -> Pair<Float, Float>, q: Double, color: Int, scale: Float) {
        if (q <= 0 || q >= 1) return
        for (k in 1..12) {
            val p = path(max(0.0, q - k * 0.018))
            c.drawCircle(p.first, p.second, (2 + k * 0.55f) * scale, ThemeArt.fill(al(Color.WHITE, 0.32f * (1 - k / 13f))))
        }
        val head = path(q)
        val before = path(max(0.0, q - 0.02))
        val ang = atan2((head.second - before.second).toDouble(), (head.first - before.first).toDouble())
        c.save()
        c.translate(head.first, head.second)
        c.rotate(Math.toDegrees(ang).toFloat())
        c.drawRoundRect(RectF(-7 * scale, -2 * scale, 7 * scale, 2 * scale), 2 * scale, 2 * scale, ThemeArt.fill(0xFFEBEBEB.toInt()))
        c.drawOval(RectF(-14 * scale, -3 * scale, -4 * scale, 3 * scale), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(-9 * scale, 0f, 6 * scale, color, al(color, 0f), Shader.TileMode.CLAMP)
        })
        c.restore()
    }

    fun batmobileAt(c: Canvas, res: Resources, t: AppTheme, x: Float, roadY: Float, fit: Float, time: Double, d: Float) {
        val noseX = x + fit * 0.42f
        val noseY = roadY - fit * 0.2f
        val cone = Path().apply { moveTo(noseX, noseY); lineTo(noseX + 220 * d, noseY - 46 * d); lineTo(noseX + 220 * d, noseY + 34 * d); close() }
        c.drawPath(cone, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(noseX, noseY, noseX + 220 * d, noseY, al(t.accent, 0.4f), Color.TRANSPARENT, Shader.TileMode.CLAMP) })
        for (i in 0 until 8) {
            val sx = x - fit * 0.5f - i * 26 * d
            val sy = roadY - 6 * d - hash(i + 5.0).toFloat() * fit * 0.3f
            c.drawLine(sx, sy, sx - (30 + hash(i.toDouble()).toFloat() * 36) * d, sy, ThemeArt.stroke(0x2EFFFFFF, 1.2f * d))
        }
        ThemeArt.pictureT(c, res, R.drawable.themeart_batmobile, x, roadY - fit * 0.2f + sin(time * 20).toFloat() * 0.8f * d, fit, 1f)
    }

    fun draw(c: Canvas, w: Float, h: Float, t: AppTheme, p: Double, res: Resources, d: Float) {
        val scrim = (if (t.motif == Motif.ARCH) 0.7 else 0.62) * min(seg(p, 0.0, 0.08), 1 - seg(p, 0.92, 1.0))
        c.drawRect(0f, 0f, w, h, ThemeArt.fill(al(Color.BLACK, scrim.toFloat())))
        val ground = h * 0.9f

        when (t.motif) {
            Motif.THOR -> {
                val big = min(h * 0.4f, w * 0.95f / 1.08f)
                if (p < 0.3 && hash(floor(p * 28)) > 0.45) for (k in 0 until 3) {
                    boltLine(c, w * (0.15f + 0.35f * k) + hash(floor(p * 28) + k).toFloat() * 40 * d, 0f, w * (0.1f + 0.4f * k), h * 0.7f, k * 5 + floor(p * 28), t.accent, 0.9f, 1.5f * d, 26 * d)
                }
                val drop = seg(p, 0.06, 0.28); val crouch = seg(p, 0.30, 0.58); val jump = seg(p, 0.58, 0.88)
                var y = -big * 1.3f
                var rot = 0.0
                if (p >= 0.06) y = -big * 1.3f + (ground + big * 1.3f) * easeIn(drop).toFloat()
                if (p >= 0.28) { y = ground; rot = if (p < 0.58) 0.16 * sin(Math.PI * crouch * 0.5) else 0.16 }
                if (p >= 0.58) { y = ground - (ground + big * 1.4f) * easeIn(jump).toFloat(); rot = 0.16 + 0.35 * jump }
                val cx = w * 0.5f
                if (p >= 0.6) for (k in 1..4) sprite(c, res, R.drawable.themeart_thorup, cx, y + k * big * 0.2f * jump.toFloat(), big * 1.6f, 0.5f, 0.85f, alpha = 0.2f / k)
                if (p >= 0.28 && p < 0.9) ThemeArt.glow(c, cx, y - big * 0.5f, big * 0.9f, t.accent, 0.28f)
                // he launches arm-and-hammer first, straight up into the storm
                if (p < 0.6) sprite(c, res, R.drawable.themeart_thorfig, cx, y, big, rotation = if (p >= 0.58) 0.0 else rot)
                else sprite(c, res, R.drawable.themeart_thorup, cx, y, big * 1.6f, 0.5f, 0.85f)
                if (p >= 0.27 && p < 0.5) {
                    val k = seg(p, 0.27, 0.5)
                    boltLine(c, cx + 10 * d, 0f, cx, ground - big * 0.4f, floor(p * 40), t.accent, (1 - k).toFloat(), 3 * d, 22 * d, 12)
                    c.drawRect(0f, 0f, w, h, ThemeArt.fill(al(Color.WHITE, (0.5 * (1 - seg(p, 0.27, 0.36))).toFloat())))
                    val r = easeOut(k).toFloat() * w * 0.6f
                    c.drawOval(RectF(cx - r, ground - r * 0.18f, cx + r, ground + r * 0.18f), ThemeArt.stroke(al(t.accent, 0.8f * (1 - k.toFloat())), 3 * d))
                    val cracks = Path()
                    for (i in 0 until 9) {
                        val a = Math.PI * (0.05 + 0.9 * i / 8)
                        val len = easeOut(k).toFloat() * w * (0.25f + 0.25f * hash(i.toDouble()).toFloat())
                        cracks.moveTo(cx, ground)
                        cracks.lineTo(cx + cos(a).toFloat() * len * (if (i % 2 == 0) 1 else -1) * 0.9f, ground + abs(sin(a)).toFloat() * len * 0.12f)
                    }
                    c.drawPath(cracks, ThemeArt.stroke(al(Color.WHITE, 0.6f * (1 - k.toFloat())), 1.4f * d))
                }
                if (p >= 0.3 && p < 0.9) {
                    val ax = cx; val ay = y - big * 0.5f
                    val n = if (p >= 0.58) 6 else 3
                    for (k in 0 until n) {
                        val tick = floor(p * 24)
                        val a = hash(tick * 7 + k) * 2 * Math.PI
                        boltLine(c, ax, ay, ax + cos(a).toFloat() * big, ay + sin(a).toFloat() * big * 0.9f, tick * 3 + k, t.accent, 0.9f, 1.3f * d, 14 * d, 6)
                    }
                }
                if (p >= 0.86) c.drawRect(0f, 0f, w, h, ThemeArt.fill(al(t.accent, (0.28 * (1 - seg(p, 0.86, 1.0))).toFloat())))
            }

            Motif.SPIDER -> {
                val hc = min(h * 0.32f, w * 0.8f / 0.67f)
                val anchorY = -h * 0.06f
                val figW = min(h * 0.3f, w * 0.8f)
                val sH = ground - anchorY - figW * 0.43f * 0.5f
                val anchorX = w * 0.5f
                if (p < 0.36) {
                    val a = seg(p, 0.0, 0.36)
                    val th = -0.95 * cos(Math.PI / 2 * a)
                    val px = anchorX + sH * sin(th).toFloat()
                    val py = anchorY + sH * cos(th).toFloat()
                    val th2 = -0.95 * cos(Math.PI / 2 * min(1.0, a + 0.01))
                    val nx = anchorX + sH * sin(th2).toFloat()
                    val ny = anchorY + sH * cos(th2).toFloat()
                    val heading = atan2((ny - py).toDouble(), max(0.001f, nx - px).toDouble())
                    val hx = px - figW * 0.47f * cos(heading * 0.75).toFloat()
                    val hy = py - figW * 0.08f
                    val web = Path().apply { moveTo(hx, hy); lineTo(anchorX, anchorY) }
                    c.drawPath(web, ThemeArt.stroke(0x33FFFFFF, 5 * d))
                    c.drawPath(web, ThemeArt.stroke(0xE6FFFFFF.toInt(), 1.3f * d))
                    val appear = min(1f, (p / 0.03).toFloat())
                    val blend = seg(a, 0.38, 0.42).toFloat()   // arms-up grab of the web, then stretched out through the swing
                    ThemeArt.pictureT(c, res, R.drawable.themeart_swing_a, px, py, figW, appear * (1 - blend), 1f, (heading * 0.75).toFloat(), true)
                    ThemeArt.pictureT(c, res, R.drawable.themeart_swing1, px, py, figW, appear * blend, 1f, (heading * 0.75).toFloat(), true)
                } else if (p < 0.74) {
                    val land = seg(p, 0.36, 0.52); val look = seg(p, 0.54, 0.72)
                    val squash = 1f - 0.1f * sin(Math.PI * min(1.0, land * 1.6)).toFloat()
                    val rot = -0.14 * easeInOut(look)
                    sprite(c, res, R.drawable.themeart_spidercrouch, w * 0.5f, ground, hc, rotation = rot, scaleY = squash)
                    for (i in 0 until 10) {
                        val a = Math.PI * (0.1 + 0.8 * i / 9)
                        val dist = easeOut(land).toFloat() * w * 0.28f * (0.5f + 0.5f * hash(i.toDouble()).toFloat())
                        val r = (4 + 12 * easeOut(land).toFloat()) * d
                        c.drawCircle(w * 0.5f + cos(a).toFloat() * dist * (if (i % 2 == 0) 1 else -1), ground - sin(a).toFloat() * dist * 0.35f, r, ThemeArt.fill(al(Color.WHITE, 0.22f * (1 - land.toFloat()))))
                    }
                    if (look > 0.4) {
                        val g = seg(look, 0.4, 1.0).toFloat()
                        val hx = w * 0.5f + hc * 0.28f; val hy = ground - hc * 0.9f
                        val ex = w * 0.78f; val ey = -h * 0.1f
                        c.drawLine(hx, hy, hx + (ex - hx) * g, hy + (ey - hy) * g, ThemeArt.stroke(al(Color.WHITE, 0.9f), 1.4f * d))
                    }
                } else {
                    val q = seg(p, 0.74, 1.0)
                    val u = easeIn(q)
                    val s0x = w * 0.5f; val s0y = ground - hc * 0.35f; val c1x = w * 0.8f; val c1y = ground * 0.85f; val ex = w * 1.4f; val ey = -h * 0.3f
                    fun bez(uu: Double): Pair<Float, Float> { val a = (1 - uu).toFloat(); val b = uu.toFloat(); return (a * a * s0x + 2 * a * b * c1x + b * b * ex) to (a * a * s0y + 2 * a * b * c1y + b * b * ey) }
                    val pos = bez(u); val nx = bez(min(1.0, u + 0.02))
                    val heading = atan2((nx.second - pos.second).toDouble(), max(0.001f, nx.first - pos.first).toDouble())
                    c.drawLine(pos.first + 10 * d, pos.second - figW * 0.1f, w * 0.8f, -h * 0.12f, ThemeArt.stroke(al(Color.WHITE, 0.9f), 1.3f * d))
                    val blend = seg(q, 0.28, 0.32).toFloat()   // tucked as he springs off, then stretched out
                    sprite(c, res, R.drawable.themeart_swing2, pos.first, pos.second, figW * 1.05f * 0.63f, 0.5f, 0.5f, heading * 0.5, 1 - blend, true)
                    ThemeArt.pictureT(c, res, R.drawable.themeart_swing1, pos.first, pos.second, figW * 1.05f, blend, 1f, (heading * 0.75).toFloat(), true)
                }
            }

            Motif.REACTOR -> {
                val big = min(h * 0.44f, w * 0.95f / 0.6f)
                val arrive = seg(p, 0.02, 0.24); val look = seg(p, 0.24, 0.42); val away = seg(p, 0.74, 0.97)
                val cx = w * 0.5f
                var y = -big * 0.2f + (ground + big * 0.2f) * easeOut(arrive).toFloat()
                if (p < 0.02) y = -big * 1.2f
                var rot = -0.12 * easeInOut(look)
                if (p >= 0.74) { y = ground - (ground + big * 1.4f) * easeIn(away).toFloat(); rot = -0.12 * (1 - easeIn(away)) }
                ThemeArt.glow(c, cx, y, big * 0.5f, t.accent, if (p < 0.3) (0.5 * (1 - seg(p, 0.2, 0.3))).toFloat() else if (p >= 0.74) 0.7f else 0f)
                if (p >= 0.2 && p < 0.38) {
                    val k = seg(p, 0.2, 0.38)
                    val r = easeOut(k).toFloat() * w * 0.55f
                    c.drawOval(RectF(cx - r, ground - r * 0.16f, cx + r, ground + r * 0.16f), ThemeArt.stroke(al(t.accent, 0.8f * (1 - k.toFloat())), 3 * d))
                }
                if (p >= 0.74 && away < -1.0) {
                    val fl = big * 0.55f * (0.8f + 0.2f * sin(p * 80).toFloat())
                    val flame = Path().apply {
                        moveTo(cx - big * 0.12f, y); quadTo(cx - big * 0.1f, y + fl * 0.5f, cx, y + fl); quadTo(cx + big * 0.1f, y + fl * 0.5f, cx + big * 0.12f, y); close()
                    }
                    c.drawPath(flame, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        shader = LinearGradient(cx, y, cx, y + fl, intArrayOf(Color.WHITE, t.accent, al(t.accent2, 0f)), null, Shader.TileMode.CLAMP)
                    })
                    for (k in 0 until 7) {
                        val x = cx + (k - 3) * big * 0.12f
                        c.drawLine(x, y + big * 0.2f, x, y + big * (0.7f + 0.5f * hash(k.toDouble()).toFloat()), ThemeArt.stroke(al(t.accent, 0.35f), 1.2f * d))
                    }
                }
                val flyBlend = if (p >= 0.77) seg(p, 0.77, 0.795).toFloat() else 0f
                if (flyBlend < 1f) sprite(c, res, R.drawable.themeart_ironfig, cx, y, big, rotation = rot, alpha = 1 - flyBlend)
                if (flyBlend > 0f) {
                    // he straightens into the flying pose, jets blazing, and climbs straight up out of the screen
                    sprite(c, res, R.drawable.themeart_ironup, cx, y, big * 2.08f, 0.5f, 0.48f, alpha = flyBlend)
                }
                val targets = (0 until 8).map { i -> (w * (0.1f + 0.8f * i / 7)) to (h * (0.1f + 0.26f * hash(i * 3.3).toFloat())) }
                if (p >= 0.3 && p < 0.74) {
                    val appear = seg(p, 0.3, 0.42)
                    targets.forEachIndexed { i, tg ->
                        if (p > 0.42 + i * 0.03 + 0.2) return@forEachIndexed
                        val r = 16 * d * easeOut(appear).toFloat() * (1 + 0.1f * sin(p * 30 + i).toFloat())
                        val ring = ThemeArt.stroke(al(t.accent, 0.9f), 1.4f * d)
                        c.drawCircle(tg.first, tg.second, r, ring)
                        val cross = Path().apply {
                            moveTo(tg.first - r - 5 * d, tg.second); lineTo(tg.first - r + 4 * d, tg.second)
                            moveTo(tg.first + r - 4 * d, tg.second); lineTo(tg.first + r + 5 * d, tg.second)
                            moveTo(tg.first, tg.second - r - 5 * d); lineTo(tg.first, tg.second - r + 4 * d)
                            moveTo(tg.first, tg.second + r - 4 * d); lineTo(tg.first, tg.second + r + 5 * d)
                        }
                        c.drawPath(cross, ThemeArt.stroke(al(t.accent, 0.9f), 1.2f * d))
                    }
                }
                targets.forEachIndexed { i, tg ->
                    val launch = 0.42 + i * 0.03
                    val q = (p - launch) / 0.2
                    val ox = cx + (if (i % 2 == 0) -1 else 1) * big * 0.2f
                    val oy = ground - big * 0.64f
                    val cxp = ox + (tg.first - ox) * 0.25f
                    val cyp = oy - h * 0.32f
                    missile(c, { u -> val a = (1 - u).toFloat(); val b = u.toFloat(); (a * a * ox + 2 * a * b * cxp + b * b * tg.first) to (a * a * oy + 2 * a * b * cyp + b * b * tg.second) }, q, t.accent, max(1f, h / 700f) * d / 2f)
                    if (q >= 1 && q < 1.4) {
                        val k = ((q - 1) / 0.4).toFloat()
                        ThemeArt.glow(c, tg.first, tg.second, (14 + 60 * easeOut(k.toDouble()).toFloat()) * d, 0xFFFFB347.toInt(), 0.9f * (1 - k))
                        c.drawCircle(tg.first, tg.second, 70 * d * k, ThemeArt.stroke(al(Color.WHITE, 0.7f * (1 - k)), 2 * d))
                    }
                }
            }

            Motif.BAT -> {
                val scx = w * 0.5f; val scy = h * 0.24f; val sr = min(w, h) * 0.3f
                val on = (seg(p, 0.04, 0.2) * (1 - seg(p, 0.86, 1.0))).toFloat()
                if (on > 0f) {
                    val beam = Path().apply { moveTo(w * 0.42f, h); lineTo(w * 0.58f, h); lineTo(scx + sr * 0.8f, scy); lineTo(scx - sr * 0.8f, scy); close() }
                    c.drawPath(beam, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(w * 0.5f, h, scx, scy, al(t.accent, 0f), al(t.accent, 0.3f * on), Shader.TileMode.CLAMP) })
                    ThemeArt.glow(c, scx, scy, sr, t.accent, 0.6f * on)
                    ThemeArt.pictureT(c, res, R.drawable.themeart_batoval, scx, scy, sr * 1.5f, on)
                }
                for (i in 0 until 16) {
                    val f = i.toDouble()
                    val q = seg(p, 0.12 + 0.012 * f, 0.62 + 0.012 * f)
                    if (q <= 0 || q >= 1) continue
                    val x = -w * 0.1f + q.toFloat() * w * 1.2f
                    val y = h * (0.9f - 0.75f * q.toFloat()) + sin(q * 10 + f).toFloat() * h * 0.06f + hash(f).toFloat() * h * 0.1f
                    ThemeArt.pictureT(c, res, R.drawable.themeart_bat, x, y, (30 + 22 * hash(f + 3).toFloat()) * d, 0.95f, (0.5 + 0.5 * abs(sin(p * 60 + f))).toFloat())
                }
                val q = seg(p, 0.34, 0.9)
                val fit = min(w * 0.75f, 300f * d)
                batmobileAt(c, res, t, -fit + q.toFloat() * (w + fit * 2), h * 0.9f, fit, p * 8, d)
            }

            Motif.MATRIX -> {
                val cols = max(10, (w / (18 * d)).toInt())
                val loop = (h / d + 300).toDouble()
                for (col in 0 until cols) {
                    val speed = 0.9 + 1.4 * hash(col.toDouble())
                    val start = 0.02 + 0.3 * hash(col + 4.0)
                    if (p < start) continue
                    val head = (((p - start) * speed * 1.3 * loop) % loop - 40).toFloat() * d
                    for (r in 0 until 16) {
                        val y = head - r * 17 * d
                        if (y < -10 * d || y > h + 10 * d) continue
                        val chars = ThemeArt.rainChars
                        val ch = chars[(hash((col * 31 + r).toDouble() + floor(p * 18)) * chars.length).toInt() % chars.length].toString()
                        val a = ((if (r == 0) 1.0 else 0.7 * (1 - r / 16.0)) * (1 - seg(p, 0.88, 1.0))).toFloat()
                        c.drawText(ch, 10 * d + col * 18 * d, y + 5 * d, ThemeArt.rainPaint(14 * d, if (r == 0) al(Color.WHITE, a) else al(t.accent, a)))
                    }
                }
            }

            Motif.ARCH -> {
                val lx = w * 0.14f; val ly = h * 0.56f; val rx = w * 0.86f; val cxp = w * 0.5f; val cyp = h * 0.06f
                fun q(s: Double): Pair<Float, Float> { val a = (1 - s).toFloat(); val b = s.toFloat(); return (a * a * lx + 2 * a * b * cxp + b * b * rx) to (a * a * ly + 2 * a * b * cyp + b * b * ly) }
                for (i in 0 until 70) {
                    val f = i.toDouble()
                    val tw = 0.3f + 0.7f * abs(sin(p * 14 + hash(f + 20) * 6)).toFloat()
                    val r = ((0.6 + hash(f + 30) * 1.2) * d).toFloat()
                    c.drawCircle(hash(f).toFloat() * w, hash(f + 10).toFloat() * h, r, ThemeArt.fill(al(Color.WHITE, 0.5f * tw * seg(p, 0.0, 0.2).toFloat() * (1 - seg(p, 0.9, 1.0).toFloat()))))
                }
                val n = 15
                var prev: Pair<Float, Float>? = null
                for (i in 0 until n) {
                    val s = i.toDouble() / (n - 1)
                    val appear = seg(p, 0.08 + 0.4 * s, 0.14 + 0.4 * s).toFloat()
                    if (appear <= 0f) break
                    val pt = q(s)
                    prev?.let { pv ->
                        c.drawLine(pv.first, pv.second, pv.first + (pt.first - pv.first) * appear, pv.second + (pt.second - pv.second) * appear,
                            ThemeArt.stroke(al(Color.WHITE, 0.4f * (1 - seg(p, 0.92, 1.0).toFloat())), 1.2f * d))
                    }
                    val r = (2.2f + 4 * (1 - appear)) * (1 + sin(p * 20 + i).toFloat() * 0.1f) * d
                    ThemeArt.glow(c, pt.first, pt.second, r * 4, Color.WHITE, 0.25f * appear)
                    c.drawCircle(pt.first, pt.second, r, ThemeArt.fill(al(Color.WHITE, 0.95f * appear * (1 - seg(p, 0.92, 1.0).toFloat()))))
                    prev = pt
                }
                listOf(lx to ly, rx to ly).forEachIndexed { i, pt ->
                    val a = (seg(p, 0.3 + i * 0.1, 0.45 + i * 0.1) * (1 - seg(p, 0.92, 1.0))).toFloat()
                    if (a <= 0f) return@forEachIndexed
                    c.drawCircle(pt.first, pt.second, 16 * d, ThemeArt.fill(al(Color.BLACK, 0.8f * a)))
                    c.drawCircle(pt.first, pt.second, 16 * d, ThemeArt.stroke(al(Color.WHITE, 0.8f * a), 1.2f * d))
                    val ic = ThemeArt.stroke(al(Color.WHITE, a), 1.3f * d)
                    if (i == 0) c.drawRoundRect(RectF(pt.first - 4.5f * d, pt.second - 8 * d, pt.first + 4.5f * d, pt.second + 8 * d), 2 * d, 2 * d, ic)
                    else {
                        c.drawRect(RectF(pt.first - 7.5f * d, pt.second - 6 * d, pt.first + 7.5f * d, pt.second + 3.5f * d), ic)
                        c.drawLine(pt.first - 10 * d, pt.second + 6.5f * d, pt.first + 10 * d, pt.second + 6.5f * d, ic)
                    }
                }
                val pulse = seg(p, 0.56, 0.84)
                if (pulse > 0 && pulse < 1) for (tr in 0 until 12) {
                    val pt = q(max(0.0, pulse - tr * 0.012))
                    ThemeArt.glow(c, pt.first, pt.second, (16 - tr) * 1.4f * d, Color.WHITE, 0.5f * (1 - tr / 12f))
                }
                if (p > 0.82) {
                    val k = seg(p, 0.82, 1.0).toFloat()
                    c.drawCircle(w / 2, h * 0.4f, easeOut(k.toDouble()).toFloat() * w * 0.6f, ThemeArt.stroke(al(Color.WHITE, 0.4f * (1 - k)), 2 * d))
                    ThemeArt.glow(c, w / 2, h * 0.4f, w * 0.5f, Color.WHITE, 0.14f * (1 - k))
                }
            }
        }
    }
}
