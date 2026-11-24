package app.phonebridge

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Gestures made with three or more fingers: a tap, a double-tap, or a swipe left/right/up/down. One and two fingers
 * stay the trackpad's own (move, click, scroll, pinch, right-click). A gesture is named "<fingers>:<motion>", e.g.
 * "4:swipe_up" or "3:double_tap"; the trackpad runs whatever is set for it and the editor records it.
 */
object Gestures {
    val motions = listOf("tap", "double_tap", "swipe_left", "swipe_right", "swipe_up", "swipe_down")

    fun key(fingers: Int, motion: String) = "$fingers:$motion"
    fun fingersOf(key: String) = key.substringBefore(':').toIntOrNull() ?: 0
    fun motionOf(key: String) = key.substringAfter(':')

    fun describe(key: String): String {
        if (key.isEmpty()) return ""
        val m = when (motionOf(key)) {
            "tap" -> "tap"; "double_tap" -> "double-tap"
            "swipe_left" -> "swipe ←"; "swipe_right" -> "swipe →"; "swipe_up" -> "swipe ↑"; "swipe_down" -> "swipe ↓"
            else -> motionOf(key)
        }
        return "${fingersOf(key)} fingers · $m"
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
 * double-tap with that many fingers means something; otherwise it fires at once.
 */
class TapPairer(private val waitsForDouble: (fingers: Int) -> Boolean, private val emit: (key: String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var pendingFingers = 0
    private var pendingAt = 0L
    private val fireSingle = Runnable { if (pendingFingers > 0) emit(Gestures.key(pendingFingers, "tap")); pendingFingers = 0 }

    fun tap(fingers: Int) {
        val now = SystemClock.uptimeMillis()
        if (pendingFingers == fingers && now - pendingAt < 350) {
            main.removeCallbacks(fireSingle)
            pendingFingers = 0
            emit(Gestures.key(fingers, "double_tap"))
            return
        }
        if (!waitsForDouble(fingers)) { emit(Gestures.key(fingers, "tap")); return }
        main.removeCallbacks(fireSingle)
        pendingFingers = fingers
        pendingAt = now
        main.postDelayed(fireSingle, 320)
    }
}
