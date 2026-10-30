package app.phonebridge

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/** Mirrors phone notifications to the Mac, and performs replies/dismissals the Mac asks for. */
class NotifListener : NotificationListenerService() {
    companion object {
        @Volatile var instance: NotifListener? = null
        private val SKIP_PACKAGES = setOf("android", "com.android.systemui")
        private val ANSWER_WORDS = listOf("answer", "accept")
        private val DECLINE_WORDS = listOf("decline", "reject", "hang up", "end call", "dismiss")
    }

    private val lastSent = ConcurrentHashMap<String, String>()
    private val iconCache = ConcurrentHashMap<String, String>()

    override fun onListenerConnected() {
        instance = this
        LinkService.start(this)
        MediaWatch.start(this)
        Dnd.undoAutomaticDnd(this)
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    override fun onInterruptionFilterChanged(interruptionFilter: Int) = reportDnd()

    fun reportDnd() {
        val on = currentInterruptionFilter.let { it != INTERRUPTION_FILTER_ALL && it != INTERRUPTION_FILTER_UNKNOWN }
        Link.send("phone_dnd", "on" to on)
    }


    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!Link.isConnected) return
        val n = sbn.notification
        if (sbn.packageName == packageName || sbn.packageName in SKIP_PACKAGES) return
        if (n.category == Notification.CATEGORY_CALL) {
            onCallNotification(sbn)
            return
        }
        if (sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (!NotifFilter.allows(this, sbn.packageName)) return

        val e = n.extras
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = lastMessage(e)
            ?: (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString()
            ?: ""
        if (title.isBlank() && text.isBlank()) return

        // Apps re-post the same notification often (progress, re-sorts); only forward real changes.
        // (Include the buttons: some apps add their Reply button in an update that has the same text.)
        val signature = "$title\n$text\n${replyAction(n) != null}\n${buttons(n).joinToString("|") { it.title.toString() }}"
        if (lastSent[sbn.key] == signature) return
        lastSent[sbn.key] = signature

        Link.send(
            JSONObject()
                .put("t", "notif")
                .put("id", sbn.key)
                .put("pkg", sbn.packageName)
                .put("app", appName(sbn.packageName))
                .put("title", title)
                .put("text", text)
                .put("reply", replyAction(n) != null)
                .put("actions", org.json.JSONArray(buttons(n).map { it.title.toString() }))
                .put("icon", icon(sbn.packageName))
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.key == callKey) {
            callKey = null
            callState = "idle"
            callConnected = null
            Link.send("call", "state" to "idle")
            Automations.onCallState(this, "idle")
            return
        }
        if (lastSent.remove(sbn.key) != null) Link.send("notif_removed", "id" to sbn.key)
    }

    // MARK: Calls via the dialer's own notification (more reliable than the phone-state broadcast on Samsung)

    @Volatile private var callKey: String? = null
    @Volatile private var callState = "idle"
    @Volatile private var callConnected: Boolean? = null

    private fun onCallNotification(sbn: StatusBarNotification) {
        val n = sbn.notification
        val e = n.extras
        // Samsung marks outgoing/ongoing call notifications much like incoming ones, so only call it
        // "ringing" when the phone itself says it's ringing.
        val looksIncoming = e.getInt(Notification.EXTRA_CALL_TYPE, 0) == Notification.CallStyle.CALL_TYPE_INCOMING ||
            findCallAction(n, ANSWER_WORDS) != null
        // Ask the phone itself what the call is doing; a notification alone can't tell ringing from answered.
        val state = callStateFor(telephonyState(), looksIncoming) ?: return
        val person = e.getParcelable(Notification.EXTRA_CALL_PERSON, android.app.Person::class.java)
        val name = person?.name?.toString() ?: e.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val detail = e.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        // Has the other side picked up? Dialers switch their notification to a running timer once the call connects
        // (and say "Calling…"/"Dialing…" before). null = can't tell.
        val chrono = e.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        val words = detail?.lowercase().orEmpty()
        val connected: Boolean? = when {
            state != "active" -> null
            chrono -> true
            Regex("""\d{1,2}:\d{2}""").containsMatchIn(words) -> true
            listOf("dialing", "dialling", "calling", "ringing", "connecting", "trying", "waiting").any { it in words } -> false
            else -> null
        }
        if (sbn.key == callKey && state == callState && connected == callConnected) return
        callKey = sbn.key
        callState = state
        callConnected = connected
        val since = if (connected == true && n.`when` > 0) n.`when` else null
        Link.send("call", "state" to state, "name" to name, "detail" to detail, "connected" to connected, "since" to since)
        Automations.onCallState(this, state)
    }

    @Suppress("DEPRECATION")
    /** TelephonyManager's call state (0 idle, 1 ringing, 2 off hook), or null if we can't read it without the phone permission. */
    private fun telephonyState(): Int? = try {
        getSystemService(android.telephony.TelephonyManager::class.java).callState
    } catch (e: SecurityException) {
        null
    }

    /** Brings the dialer's call screen forward (its notification's own tap action). */
    fun openCallScreen() {
        val key = callKey ?: return
        try {
            activeNotifications.firstOrNull { it.key == key }?.notification?.contentIntent?.send()
        } catch (e: Exception) {
            Log.w(TAG, "couldn't open the call screen", e)
        }
    }

    /** Presses the dialer's own Answer/Decline button. Returns false if there isn't one. */
    fun pressCallButton(answer: Boolean): Boolean {
        val key = callKey ?: return false
        val sbn = activeNotifications.firstOrNull { it.key == key } ?: return false
        val action = findCallAction(sbn.notification, if (answer) ANSWER_WORDS else DECLINE_WORDS) ?: return false
        return try {
            action.actionIntent.send()
            true
        } catch (e: Exception) {
            Log.w(TAG, "call button failed", e)
            false
        }
    }

    private fun findCallAction(n: Notification, words: List<String>): Notification.Action? =
        n.actions?.firstOrNull { a -> val t = a.title?.toString()?.lowercase().orEmpty(); words.any { it in t } }

    fun reply(key: String, text: String) {
        val sbn = activeNotifications.firstOrNull { it.key == key } ?: return
        val action = replyAction(sbn.notification) ?: return
        val results = Bundle()
        val free = action.remoteInputs.filter { it.allowFreeFormInput }
        (free.ifEmpty { action.remoteInputs.take(1) }).forEach { results.putCharSequence(it.resultKey, text) }
        val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        RemoteInput.addResultsToIntent(action.remoteInputs, intent, results)
        try {
            action.actionIntent.send(this, 0, intent)
        } catch (e: Exception) {
            Log.w(TAG, "reply failed", e)
        }
    }

    /** The app's own buttons ("Mark as read", "Archive", …) — everything that isn't a text reply. */
    private fun buttons(n: Notification): List<Notification.Action> =
        n.actions.orEmpty().filter { it.remoteInputs.isNullOrEmpty() && !it.title.isNullOrBlank() }.take(3)

    fun pressAction(key: String, index: Int) {
        val sbn = activeNotifications.firstOrNull { it.key == key } ?: return
        try {
            buttons(sbn.notification).getOrNull(index)?.actionIntent?.send()
        } catch (e: Exception) {
            Log.w(TAG, "notification action failed", e)
        }
    }

    fun dismiss(key: String) {
        lastSent.remove(key)
        cancelNotification(key)
    }

    /** The notification's Reply button: a text-input action, flagged as a reply or free-form, on the notification or its wearable extras. */
    private fun replyAction(n: Notification): Notification.Action? {
        fun isReply(a: Notification.Action): Boolean {
            val inputs = a.remoteInputs ?: return false
            if (inputs.isEmpty()) return false
            val title = a.title?.toString()?.lowercase().orEmpty()
            return a.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY ||
                inputs.any { it.allowFreeFormInput } || "reply" in title || "respond" in title
        }
        n.actions?.firstOrNull { isReply(it) }?.let { return it }
        return try {
            Notification.WearableExtender(n).actions.firstOrNull { isReply(it) }
        } catch (e: Exception) {
            null
        }
    }

    // Chat apps (WhatsApp, Messages…) put the newest message in MessagingStyle extras.
    private fun lastMessage(e: Bundle): String? {
        val arr = e.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java) ?: return null
        val msgs = Notification.MessagingStyle.Message.getMessagesFromBundleArray(arr)
        val m = msgs.lastOrNull() ?: return null
        val sender = m.senderPerson?.name?.toString()
        val isGroup = e.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)
        return if (isGroup && sender != null) "$sender: ${m.text}" else m.text?.toString()
    }

    private fun appName(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun icon(pkg: String): String? = iconCache[pkg] ?: try {
        val d = packageManager.getApplicationIcon(pkg)
        val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 96, 96)
        d.draw(Canvas(bmp))
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        Crypto.b64e(out.toByteArray()).also { iconCache[pkg] = it }
    } catch (e: Exception) {
        null
    }
}

/**
 * What the dialer's call notification means, given the phone's real call state: "ringing" only while the phone is ringing,
 * "active" only once a call is off hook (answered or dialled), and nothing (null) while the phone is idle, e.g. the
 * notification showed up a moment before the ringing state did. [tele] null = unknown, so fall back to the notification.
 */
internal fun callStateFor(tele: Int?, looksIncoming: Boolean): String? = when (tele) {
    1 -> if (looksIncoming) "ringing" else "active"
    2 -> "active"
    0 -> null
    else -> if (looksIncoming) "ringing" else "active"
}
