package app.phonebridge

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Gestures of your own. Anywhere on the trackpad: three to five fingers, a tap, double-tap or swipe ←→↑↓. In a
 * corner: one or two fingers, a tap, double-tap or hold. From an edge: one or two fingers swiping in. In the middle,
 * one and two fingers stay the trackpad's own (move, click, scroll, pinch, right-click).
 * A gesture is named "<fingers>:<motion>" plus "@<place>" for a corner or edge: "4:swipe_up", "2:tap@tr",
 * "1:edge_in@r". The trackpad runs whatever is set for it and the editor records it.
 */
object Gestures {
    val motions = listOf("tap", "double_tap", "swipe_left", "swipe_right", "swipe_up", "swipe_down", "hold", "edge_in")

    /** Corners and edges, by their short names. */
    val places = linkedMapOf(
        "tl" to "top-left corner", "tr" to "top-right corner", "bl" to "bottom-left corner", "br" to "bottom-right corner",
        "l" to "left edge", "r" to "right edge", "t" to "top edge", "b" to "bottom edge",
    )
    fun isCorner(place: String) = place.length == 2

    fun key(fingers: Int, motion: String, place: String? = null) = "$fingers:$motion" + (place?.let { "@$it" } ?: "")
    fun fingersOf(key: String) = key.substringBefore(':').toIntOrNull() ?: 0
    fun motionOf(key: String) = key.substringAfter(':').substringBefore('@')
    fun placeOf(key: String): String? = key.substringAfter('@', "").ifEmpty { null }

    fun describe(key: String): String {
        if (key.isEmpty()) return ""
        val f = fingersOf(key)
        val place = placeOf(key)
        val m = when (motionOf(key)) {
            "tap" -> "tap"; "double_tap" -> "double-tap"; "hold" -> "hold"
            "swipe_left" -> "swipe ←"; "swipe_right" -> "swipe →"; "swipe_up" -> "swipe ↑"; "swipe_down" -> "swipe ↓"
            "edge_in" -> "swipe in from the ${places[place] ?: "edge"}"
            else -> motionOf(key)
        }
        val where = if (place != null && isCorner(place)) " · ${places[place]}" else ""
        return "$f finger${if (f == 1) "" else "s"} · $m$where"
    }

    /** How much of the left and right edges (centred) belongs to the trackpad rather than Android's Back gesture. */
    fun sideBand(h: Float, density: Float) = minOf(h * 0.6f, 190 * density)

    /** The corner or edge a touch at (x, y) starts in, on a [w]×[h] trackpad; null for the middle. */
    fun placeAt(x: Float, y: Float, w: Float, h: Float, corner: Float, edge: Float, density: Float): String? {
        val left = x < corner; val right = x > w - corner; val top = y < corner; val bottom = y > h - corner
        val inBand = kotlin.math.abs(y - h / 2) < sideBand(h, density) / 2
        return when {
            top && left -> "tl"; top && right -> "tr"; bottom && left -> "bl"; bottom && right -> "br"
            x < edge && inBand -> "l"; x > w - edge && inBand -> "r"; y < edge -> "t"; y > h - edge -> "b"
            else -> null
        }
    }
}

/**
 * Reads multi-finger taps and swipes from a touch stream. Feed it every event; it calls [onSwipe] once per touch
 * when the fingers have travelled [swipeDistance] together, or [onTap] when they land and lift quickly in place.
 * [fingers] is the most fingers seen in the current touch.
 */
class MultiFingerReader(
    private val swipeDistance: Float,
    private val tapSlop: Float,
    private val onSwipe: (fingers: Int, dir: String) -> Unit,
    private val onTap: (fingers: Int) -> Unit,
) {
    var fingers = 0
        private set
    private var startX = 0f
    private var startY = 0f
    private var travelled = 0f
    private var downAt = 0L
    private var swiped = false

    private fun avgX(e: MotionEvent) = (0 until e.pointerCount).map { e.getX(it) }.average().toFloat()
    private fun avgY(e: MotionEvent) = (0 until e.pointerCount).map { e.getY(it) }.average().toFloat()

    fun feed(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { fingers = 1; swiped = false; travelled = 0f; downAt = SystemClock.uptimeMillis() }
            MotionEvent.ACTION_POINTER_DOWN -> {
                fingers = maxOf(fingers, e.pointerCount)
                // Fingers rarely land together: measure the swipe from when the last one landed.
                if (!swiped) { startX = avgX(e); startY = avgY(e) }
            }
            MotionEvent.ACTION_MOVE -> if (fingers >= 3 && e.pointerCount >= 3 && !swiped) {
                val tx = avgX(e) - startX
                val ty = avgY(e) - startY
                travelled = maxOf(travelled, hypot(tx, ty))
                if (maxOf(abs(tx), abs(ty)) >= swipeDistance) {
                    swiped = true
                    onSwipe(fingers, if (abs(tx) > abs(ty)) (if (tx < 0) "left" else "right") else (if (ty < 0) "up" else "down"))
                }
            }
            MotionEvent.ACTION_UP -> if (fingers >= 3 && !swiped && travelled < tapSlop && SystemClock.uptimeMillis() - downAt < 400) onTap(fingers)
        }
    }
}

/**
 * Turns taps into taps and double-taps. A tap waits briefly for a second one only when [waitsForDouble] says a
 * double-tap with that many fingers (in that place) means something; otherwise it fires at once.
 */
class TapPairer(private val waitsForDouble: (fingers: Int, place: String?) -> Boolean, private val emit: (key: String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var pendingFingers = 0
    private var pendingPlace: String? = null
    private var pendingAt = 0L
    private val fireSingle = Runnable { if (pendingFingers > 0) emit(Gestures.key(pendingFingers, "tap", pendingPlace)); pendingFingers = 0 }

    fun tap(fingers: Int, place: String? = null) {
        val now = SystemClock.uptimeMillis()
        if (pendingFingers == fingers && pendingPlace == place && now - pendingAt < 350) {
            main.removeCallbacks(fireSingle)
            pendingFingers = 0
            emit(Gestures.key(fingers, "double_tap", place))
            return
        }
        if (!waitsForDouble(fingers, place)) { emit(Gestures.key(fingers, "tap", place)); return }
        main.removeCallbacks(fireSingle)
        pendingFingers = fingers
        pendingPlace = place
        pendingAt = now
        main.postDelayed(fireSingle, 320)
    }
}

/**
 * Reads one- and two-finger gestures that start in a corner or at an edge: in a corner a tap ([onTap]) or a hold
 * ([onGesture] "hold", after half a second still); from an edge a swipe inwards ([onGesture] "edge_in").
 * Call [start] when a touch lands in a place, then feed it the touch's events.
 */
class PlaceReader(
    private val swipeDistance: Float,
    private val slop: Float,
    private val onGesture: (fingers: Int, motion: String, place: String) -> Unit,
    private val onTap: (fingers: Int, place: String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    var place: String? = null
        private set
    var fingers = 0
        private set
    private var x0 = 0f
    private var y0 = 0f
    private var travelled = 0f
    private var downAt = 0L
    private var done = false
    private val hold = Runnable {
        val p = place ?: return@Runnable
        if (!done && Gestures.isCorner(p) && travelled < slop) { done = true; onGesture(fingers, "hold", p) }
    }

    fun start(e: MotionEvent, place: String) {
        this.place = place
        fingers = 1; travelled = 0f; done = false
        x0 = e.x; y0 = e.y; downAt = SystemClock.uptimeMillis()
        main.removeCallbacks(hold)
        if (Gestures.isCorner(place)) main.postDelayed(hold, 550)
    }

    fun feed(e: MotionEvent) {
        val p = place ?: return
        when (e.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> fingers = maxOf(fingers, e.pointerCount)
            MotionEvent.ACTION_MOVE -> {
                val dx = e.getX(0) - x0; val dy = e.getY(0) - y0
                travelled = maxOf(travelled, hypot(dx, dy))
                if (!done && !Gestures.isCorner(p)) {
                    // Inwards from the edge it started at.
                    val inward = when (p) { "l" -> dx; "r" -> -dx; "t" -> dy; else -> -dy }
                    if (inward >= swipeDistance) { done = true; onGesture(fingers, "edge_in", p) }
                }
            }
            MotionEvent.ACTION_UP -> {
                main.removeCallbacks(hold)
                if (!done && Gestures.isCorner(p) && travelled < slop && SystemClock.uptimeMillis() - downAt < 400) onTap(fingers, p)
                place = null
            }
            MotionEvent.ACTION_CANCEL -> { main.removeCallbacks(hold); place = null }
        }
    }
}
