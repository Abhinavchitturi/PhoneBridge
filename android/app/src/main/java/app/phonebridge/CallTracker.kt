package app.phonebridge

/** What the dialer's call notification says (the bits we use). */
class CallNote(
    val key: String,
    /** Flagged as an incoming call, or carrying an Answer button. */
    val looksIncoming: Boolean,
    val name: String?,
    val detail: String?,
    /** The notification shows a running call timer. */
    val chrono: Boolean,
    val whenMs: Long,
)

/** One report to the Mac. */
data class CallSnapshot(
    /** "idle", "ringing" (nothing answered yet) or "active" (a call is on: answered, or dialled). */
    val state: String,
    val name: String?,
    val number: String?,
    val detail: String?,
    /** Active calls: true once the other side is on the line. false = still dialling, null = can't tell. */
    val connected: Boolean?,
    val since: Long?,
    val incoming: Boolean,
    /** A second call is ringing while this one is on. It never replaces the call above. */
    val waiting: Boolean,
    val waitingName: String?,
    val waitingNumber: String?,
)

/**
 * Works out what's happening on the phone from the two things Android gives us: the phone's own call state (idle / ringing /
 * off hook) and the dialer's notification(s). The phone's own state decides ringing vs. on a call; notifications only add names
 * and the "other side picked up" hint. A second call ringing during a call (call waiting) is kept apart from the call you're on.
 */
class CallTracker(
    private val nameFor: (String) -> String? = { null },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var ringing = false
    private var onCall = false
    private var waiting = false
    private var incoming = false
    private var name: String? = null
    private var number: String? = null
    private var detail: String? = null
    private var connected: Boolean? = null
    private var since: Long? = null
    private var waitingName: String? = null
    private var waitingNumber: String? = null
    private var primaryKey: String? = null
    private var waitingKey: String? = null
    private var sawDialing = false
    private var last: CallSnapshot? = null

    /** The phone's call state changed (0 idle, 1 ringing, 2 off hook). [num] is the caller's number when Android gives it. */
    fun onTelephony(state: Int, num: String?): CallSnapshot? {
        when (state) {
            1 -> {
                if (onCall) {
                    waiting = true
                    ringing = true
                    if (num != null) { waitingNumber = num; waitingName = nameFor(num) ?: waitingName }
                } else {
                    if (!ringing) startIncoming()
                    if (num != null) { number = num; name = nameFor(num) ?: name }
                }
            }
            2 -> when {
                ringing && !onCall -> { // you picked up
                    ringing = false
                    onCall = true
                    connected = true
                    since = now()
                }
                ringing && onCall -> clearWaiting() // the waiting call was declined, missed or taken: the call you're on goes on
                !onCall -> { // you dialled
                    onCall = true
                    incoming = false
                    connected = null
                    since = null
                    sawDialing = false
                    primaryKey = null
                }
            }
            else -> reset()
        }
        return emit()
    }

    /** The dialer posted or updated a call notification. [tele] is the phone's call state right now, if readable. */
    fun onNotification(n: CallNote, tele: Int?): CallSnapshot? {
        if (tele == 0) return null // nothing is happening yet (or it just ended); the phone's own state comes first
        // The phone's state broadcast can be late or unavailable: catch up from the notification's timing.
        if (tele == 1 && !ringing) onTelephony(1, null)
        if (tele == 2 && !onCall && !ringing) onTelephony(2, null)
        when {
            waiting && n.looksIncoming -> { // the waiting caller
                waitingKey = n.key
                if (waitingNumber == null || waitingName == null) waitingName = n.name?.takeIf { it.isNotBlank() } ?: waitingName
            }
            waiting -> detailOnly(n) // the call you're on, re-posted; its name stays
            ringing && !onCall -> { // ringing — even if the dialer didn't flag it as incoming it is NOT answered
                primaryKey = n.key
                if (name == null || number == null) name = n.name?.takeIf { it.isNotBlank() } ?: name
                detail = n.detail
            }
            onCall -> {
                primaryKey = n.key
                if (name == null) name = n.name?.takeIf { it.isNotBlank() }
                detail = n.detail
                updateConnected(n)
            }
        }
        return emit()
    }

    /** A call notification went away. */
    fun onNotificationRemoved(key: String, tele: Int?): CallSnapshot? {
        if (tele == 0) { reset(); return emit() }
        if (key == waitingKey) { waitingKey = null; return null } // the waiting call's own notification; the phone's state says what happened
        if (tele == 1 && waiting && key == primaryKey) {
            // The call you were on ended while another is still ringing: that one is now the ringing call.
            val wn = waitingName
            val wnum = waitingNumber
            val wk = waitingKey
            reset()
            ringing = true
            incoming = true
            name = wn
            number = wnum
            primaryKey = wk
            return emit()
        }
        return null
    }

    private fun startIncoming() {
        reset()
        ringing = true
        incoming = true
    }

    private fun clearWaiting() {
        ringing = false
        waiting = false
        waitingName = null
        waitingNumber = null
        waitingKey = null
    }

    private fun reset() {
        ringing = false
        onCall = false
        waiting = false
        incoming = false
        name = null
        number = null
        detail = null
        connected = null
        since = null
        waitingName = null
        waitingNumber = null
        primaryKey = null
        waitingKey = null
        sawDialing = false
    }

    private fun detailOnly(n: CallNote) {
        detail = n.detail
        if (onCall) updateConnected(n)
    }

    /** For calls you dialled: has the other side picked up? Dialers show "Calling…" first, then a running timer. */
    private fun updateConnected(n: CallNote) {
        if (incoming) {
            // Answered by you: already connected. Prefer the dialer's own start time if it agrees.
            val t = since
            if (n.chrono && n.whenMs > 0 && t != null && kotlin.math.abs(n.whenMs - t) < 5000) since = n.whenMs
            return
        }
        if (connected == true) return
        val words = n.detail?.lowercase().orEmpty()
        val dialing = DIALING.any { it in words }
        connected = when {
            n.chrono || TIMER.containsMatchIn(words) -> true
            dialing -> { sawDialing = true; false }
            // The text moved on from "Calling…" to something else (e.g. "Ongoing call"): they picked up.
            sawDialing && words.isNotBlank() -> true
            else -> connected
        }
        if (connected == true && since == null) since = if (n.chrono && n.whenMs > 0) n.whenMs else now()
    }

    private fun snapshot(): CallSnapshot {
        val state = when {
            onCall -> "active"
            ringing -> "ringing"
            else -> "idle"
        }
        val n = name ?: number?.let(nameFor)
        return CallSnapshot(
            state, n, number, detail, if (state == "active") connected else null, if (state == "active") since else null,
            incoming, waiting, waitingName, waitingNumber,
        )
    }

    private fun emit(): CallSnapshot? {
        val s = snapshot()
        if (s == last) return null
        last = s
        return s
    }

    companion object {
        private val DIALING = listOf("dialing", "dialling", "calling", "ringing", "connecting", "trying", "waiting")
        private val TIMER = Regex("""\d{1,2}:\d{2}""")
    }
}

/** The one place that reports calls to the Mac: turns the phone's call state and the dialer's notifications into [CallSnapshot]s. */
object CallReporter {
    private val tracker = CallTracker(nameFor = { Calls.contactName(Link.app, it) })
    private var state = "idle"
    private var waiting = false

    @Synchronized fun telephony(ctx: android.content.Context, tele: Int, number: String?) = send(ctx, tracker.onTelephony(tele, number))
    @Synchronized fun notification(ctx: android.content.Context, n: CallNote, tele: Int?) = send(ctx, tracker.onNotification(n, tele))
    @Synchronized fun removed(ctx: android.content.Context, key: String, tele: Int?) = send(ctx, tracker.onNotificationRemoved(key, tele))
    @Synchronized fun state() = state
    @Synchronized fun hasWaiting() = waiting

    private fun send(ctx: android.content.Context, s: CallSnapshot?) {
        if (s == null) return
        state = s.state
        waiting = s.waiting
        Link.send(
            "call", "state" to s.state, "name" to s.name, "number" to s.number, "detail" to s.detail, "connected" to s.connected,
            "since" to s.since, "incoming" to s.incoming, "waiting" to s.waiting, "waitingName" to s.waitingName, "waitingNumber" to s.waitingNumber,
        )
        Automations.onCallState(ctx, s.state)
    }
}
