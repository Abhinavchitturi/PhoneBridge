package app.phonebridge

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.util.Log
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Texts back when you miss (or decline) a call: "I'm busy, will call you back". Runs on the phone itself,
 * so it works with the Mac off. Off until you switch it on; by default it only answers saved contacts,
 * and never texts the same number twice within 4 hours.
 */
object AutoReply {
    const val DEFAULT_TEXT = "Hi {name}, I'm busy right now and will call you back soon. If it's important, please text me."
    private const val OLD_DEFAULT = "Hi {name}, I'm busy right now. I'll call you back soon."
    private const val COOLDOWN_MS = 4 * 60 * 60 * 1000L
    private const val NOTIF_ID = 9

    fun on(ctx: Context) = Prefs.get(ctx).getBoolean("arOn", false)
    fun text(ctx: Context): String =
        Prefs.get(ctx).getString("arText", DEFAULT_TEXT).let { if (it == null || it == OLD_DEFAULT) DEFAULT_TEXT else it }
    fun contactsOnly(ctx: Context) = Prefs.get(ctx).getBoolean("arContacts", true)
    fun alsoDeclined(ctx: Context) = Prefs.get(ctx).getBoolean("arDeclined", true)

    fun set(ctx: Context, on: Boolean, text: String, contactsOnly: Boolean, declined: Boolean) {
        Prefs.get(ctx).edit().putBoolean("arOn", on).putString("arText", text.ifBlank { DEFAULT_TEXT })
            .putBoolean("arContacts", contactsOnly).putBoolean("arDeclined", declined).apply()
    }

    fun configure(ctx: Context, m: JSONObject) {
        set(ctx, m.optBoolean("on", on(ctx)), m.optString("text", text(ctx)), m.optBoolean("contactsOnly", contactsOnly(ctx)),
            m.optBoolean("declined", alsoDeclined(ctx)))
        report(ctx)
    }

    fun report(ctx: Context) {
        Link.send("autoreply", "on" to on(ctx), "text" to text(ctx), "contactsOnly" to contactsOnly(ctx), "declined" to alsoDeclined(ctx))
    }

    private fun contactName(ctx: Context, number: String): String? = try {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    /** The newest missed/declined call in the call log from the last minute (the log lags the call by a moment). */
    private fun latestUnanswered(ctx: Context): Pair<String, Int>? = try {
        ctx.contentResolver.query(
            CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE),
            "${CallLog.Calls.TYPE} IN (${CallLog.Calls.MISSED_TYPE}, ${CallLog.Calls.REJECTED_TYPE}) AND ${CallLog.Calls.DATE} > ?",
            arrayOf((System.currentTimeMillis() - 90_000).toString()), "${CallLog.Calls.DATE} DESC",
        )?.use { c -> if (c.moveToFirst() && c.getString(0) != null) c.getString(0) to c.getInt(1) else null }
    } catch (e: SecurityException) {
        null
    }

    /** An incoming call ended without being picked up. [hint] is the number from the phone-state broadcast, if any. */
    fun onUnanswered(ctx: Context, hint: String?) {
        if (!on(ctx)) return
        thread {
            Thread.sleep(2500)
            val logged = latestUnanswered(ctx)
            val number = logged?.first ?: hint ?: return@thread
            val declined = logged?.second == CallLog.Calls.REJECTED_TYPE
            if (declined && !alsoDeclined(ctx)) return@thread
            val digits = number.filter { it.isDigit() }
            if (digits.length < 7) return@thread // private, withheld or a short code
            val name = contactName(ctx, number)
            if (contactsOnly(ctx) && name == null) return@thread
            val key = "arLast_" + digits.takeLast(10)
            val prefs = Prefs.get(ctx)
            if (System.currentTimeMillis() - prefs.getLong(key, 0) < COOLDOWN_MS) return@thread
            val first = name?.trim()?.split(" ")?.firstOrNull().orEmpty()
            val message = text(ctx).replace("{name}", first).replace("Hi ,", "Hi,").replace("  ", " ").trim()
            try {
                val sms = ctx.getSystemService(SmsManager::class.java)
                val parts = sms.divideMessage(message)
                if (parts.size > 1) sms.sendMultipartTextMessage(number, null, parts, null, null)
                else sms.sendTextMessage(number, null, message, null, null)
            } catch (e: Exception) {
                Log.w(TAG, "auto-reply failed", e)
                return@thread
            }
            prefs.edit().putLong(key, System.currentTimeMillis()).apply()
            val who = name ?: number
            Link.send("alert", "title" to "Texted $who", "body" to message)
            val n = Notification.Builder(ctx, Channels.FILES)
                .setSmallIcon(R.drawable.ic_stat_bridge)
                .setContentTitle("Auto-replied to $who")
                .setContentText(message)
                .setAutoCancel(true)
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
        }
    }
}
