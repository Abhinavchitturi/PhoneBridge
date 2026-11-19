package app.phonebridge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** The small line icons used on the home screen, drawn on a 24-unit grid so they stay crisp and match the theme colour. */
enum class LineIcon { LOCK, MOON, PIN, PREV, NEXT, PLAY, PAUSE, STOP, MIC, SCAN, KEYBOARD, MOUSE, GLOBE, CLIPBOARD, VOL_DOWN, VOL_UP, MUTE }

class IconView(ctx: Context, var icon: LineIcon, var tint: Int) : View(ctx) {
    override fun onDraw(c: Canvas) {
        val s = minOf(width, height) / 24f
        c.save()
        c.translate((width - 24 * s) / 2, (height - 24 * s) / 2)
        c.scale(s, s)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.8f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = tint
        }
        val f = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint }
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = c.drawLine(x1, y1, x2, y2, p)
        fun rr(l: Float, t: Float, r: Float, b: Float, rad: Float) = c.drawRoundRect(RectF(l, t, r, b), rad, rad, p)
        fun speaker() {
            val sp = Path().apply { moveTo(4f, 10f); lineTo(8f, 10f); lineTo(12.5f, 6f); lineTo(12.5f, 18f); lineTo(8f, 14f); lineTo(4f, 14f); close() }
            c.drawPath(sp, p)
        }
        when (icon) {
            LineIcon.LOCK -> {
                rr(5f, 11f, 19f, 21f, 2.5f)
                c.drawPath(Path().apply { moveTo(8f, 11f); lineTo(8f, 8f); arcTo(RectF(8f, 4f, 16f, 12f), 180f, 180f); lineTo(16f, 11f) }, p)
                c.drawCircle(12f, 16f, 1.3f, f)
            }
            LineIcon.MOON -> {
                val a = Path().apply { addCircle(11f, 12f, 8f, Path.Direction.CW) }
                val b = Path().apply { addCircle(15.5f, 9f, 7f, Path.Direction.CW) }
                a.op(b, Path.Op.DIFFERENCE)
                c.drawPath(a, p)
            }
            LineIcon.PIN -> {
                c.drawCircle(12f, 9.5f, 5.5f, p)
                c.drawPath(Path().apply { moveTo(8.3f, 13.6f); lineTo(12f, 21f); lineTo(15.7f, 13.6f) }, p)
                c.drawCircle(12f, 9.5f, 1.8f, f)
            }
            LineIcon.PREV -> {
                line(6f, 6f, 6f, 18f)
                c.drawPath(Path().apply { moveTo(18f, 6f); lineTo(9f, 12f); lineTo(18f, 18f); close() }, p)
            }
            LineIcon.NEXT -> {
                line(18f, 6f, 18f, 18f)
                c.drawPath(Path().apply { moveTo(6f, 6f); lineTo(15f, 12f); lineTo(6f, 18f); close() }, p)
            }
            LineIcon.PLAY -> c.drawPath(Path().apply { moveTo(8f, 5f); lineTo(19f, 12f); lineTo(8f, 19f); close() }, p)
            LineIcon.PAUSE -> { line(9f, 6f, 9f, 18f); line(15f, 6f, 15f, 18f) }
            LineIcon.STOP -> rr(7f, 7f, 17f, 17f, 2.5f)
            LineIcon.MIC -> {
                rr(9f, 3f, 15f, 14f, 3f)
                c.drawPath(Path().apply { moveTo(6f, 12f); arcTo(RectF(6f, 6f, 18f, 18f), 180f, -180f) }, p)
                line(12f, 18f, 12f, 22f); line(8.5f, 22f, 15.5f, 22f)
            }
            LineIcon.SCAN -> {
                c.drawPath(Path().apply {
                    moveTo(4f, 8f); lineTo(4f, 4f); lineTo(8f, 4f)
                    moveTo(16f, 4f); lineTo(20f, 4f); lineTo(20f, 8f)
                    moveTo(4f, 16f); lineTo(4f, 20f); lineTo(8f, 20f)
                    moveTo(20f, 16f); lineTo(20f, 20f); lineTo(16f, 20f)
                }, p)
                line(7f, 12f, 17f, 12f)
            }
            LineIcon.KEYBOARD -> {
                rr(3f, 6f, 21f, 18f, 2.5f)
                for (x in floatArrayOf(7f, 10.5f, 14f, 17.5f)) c.drawCircle(x, 10f, 0.9f, f)
                line(8f, 14.5f, 16f, 14.5f)
            }
            LineIcon.MOUSE -> { rr(7f, 3f, 17f, 21f, 5f); line(12f, 3.5f, 12f, 9f) }
            LineIcon.GLOBE -> {
                c.drawCircle(12f, 12f, 9f, p)
                c.drawOval(RectF(7.5f, 3f, 16.5f, 21f), p)
                line(3f, 12f, 21f, 12f)
            }
            LineIcon.CLIPBOARD -> {
                rr(5f, 5f, 19f, 21f, 2.5f); rr(9f, 3f, 15f, 7f, 1.5f)
                line(8.5f, 12f, 15.5f, 12f); line(8.5f, 16f, 13f, 16f)
            }
            LineIcon.VOL_DOWN -> { speaker(); c.drawArc(RectF(9f, 8f, 17f, 16f), -45f, 90f, false, p) }
            LineIcon.VOL_UP -> {
                speaker()
                c.drawArc(RectF(9f, 8f, 17f, 16f), -45f, 90f, false, p)
                c.drawArc(RectF(8f, 5f, 20f, 19f), -45f, 90f, false, p)
            }
            LineIcon.MUTE -> { speaker(); line(16f, 9.5f, 21f, 14.5f); line(21f, 9.5f, 16f, 14.5f) }
        }
        c.restore()
    }
}
