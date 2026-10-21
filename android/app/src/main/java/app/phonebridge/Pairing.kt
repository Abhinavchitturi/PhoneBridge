package app.phonebridge

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Shows "does this code match your Mac?" on the phone; both sides must confirm. */
object Pairing {
    private const val NOTIF_ID = 2
    private const val ACTION_CONFIRM = "app.phonebridge.PAIR_CONFIRM"
    private const val ACTION_REJECT = "app.phonebridge.PAIR_REJECT"

    class Pending(val code: String, val macName: String, val conn: Conn)

    @Volatile var pending: Pending? = null
        private set

    fun ask(code: String, macName: String, conn: Conn) {
        pending = Pending(code, macName, conn)
        val ctx = Link.app
        fun action(title: String, act: String) = Notification.Action.Builder(
            null, title,
            PendingIntent.getBroadcast(
                ctx, act.hashCode(), Intent(act).setClass(ctx, PairReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        ).build()
        val n = Notification.Builder(ctx, Channels.PAIR)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle("Pair with $macName?")
            .setContentText("Check your Mac shows the same code: $code")
            .setStyle(Notification.BigTextStyle().bigText("Check your Mac shows the same code:\n$code"))
            .setContentIntent(
                PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            )
            .addAction(action("Pair", ACTION_CONFIRM))
            .addAction(action("Reject", ACTION_REJECT))
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
        Link.setStatus("Pairing with $macName — code $code")
    }

    fun confirm() {
        val p = pending ?: return
        clear()
        p.conn.confirmPairing()
    }

    fun reject() {
        val p = pending ?: return
        clear()
        p.conn.rejectPairing()
    }

    fun cancel(conn: Conn) {
        if (pending?.conn === conn) clear()
    }

    private fun clear() {
        pending = null
        Link.app.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }

    fun isConfirm(intent: Intent) = intent.action == ACTION_CONFIRM
}

/** Handles notification buttons: pairing confirm/reject and "stop ringing". */
class PairReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when {
            intent.action == Ringer.ACTION_STOP -> Ringer.stop(context)
            Pairing.isConfirm(intent) -> Pairing.confirm()
            else -> Pairing.reject()
        }
    }
}
