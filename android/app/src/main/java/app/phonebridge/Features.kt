package app.phonebridge

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Log
import org.json.JSONObject
import java.io.OutputStream
import java.util.UUID

private val mainHandler = Handler(Looper.getMainLooper())

/** Incoming-call state → Mac, and answer/decline requests from the Mac. */
object Calls {
    private var lastState = "idle"
    private var lastNumber: String? = null
    // For missed-call auto-reply: did this call ring, and was it ever picked up?
    private var sawRinging = false
    private var pickedUp = false

    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            @Suppress("DEPRECATION")
            onState(
                context,
                intent.getStringExtra(TelephonyManager.EXTRA_STATE),
                intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER),
            )
        }
    }

    // Android sends this broadcast twice per change (with and without the number), so de-duplicate.
    private fun onState(ctx: Context, raw: String?, number: String?) {
        val state = when (raw) {
            TelephonyManager.EXTRA_STATE_RINGING -> "ringing"
            TelephonyManager.EXTRA_STATE_OFFHOOK -> "active"
            else -> "idle"
        }
        if (state == lastState && (number == null || number == lastNumber)) return
        if (number != null) lastNumber = number
        lastState = state
        when (state) {
            "ringing" -> { sawRinging = true; pickedUp = false }
            "active" -> if (sawRinging) pickedUp = true
            else -> {
                if (sawRinging && !pickedUp) AutoReply.onUnanswered(ctx, lastNumber)
                sawRinging = false
                pickedUp = false
            }
        }
        val n = lastNumber
        Link.send("call", "state" to state, "number" to n, "name" to n?.let { contactName(ctx, it) })
        Automations.onCallState(ctx, state)
        if (state == "idle") {
            lastNumber = null
            resetAfterCall(ctx)
        }
    }

    private fun contactName(ctx: Context, number: String): String? = try {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    // Prefer pressing the dialer's own button; fall back to the Telecom API.
    @Suppress("DEPRECATION")
    fun answer(ctx: Context) {
        if (NotifListener.instance?.pressCallButton(answer = true) == true) return
        try {
            ctx.getSystemService(TelecomManager::class.java).acceptRingingCall()
        } catch (e: SecurityException) {
            Log.w(TAG, "answer needs ANSWER_PHONE_CALLS", e)
        }
    }

    // MARK: Mute / speaker / hold during a call (the Mac's in-call card)

    @Volatile private var held = false
    private var weMuted = false
    private var weSpeaker = false

    private fun report(ctx: Context, note: String? = null) {
        val am = ctx.getSystemService(AudioManager::class.java)
        @Suppress("DEPRECATION")
        Link.send("call_ctl_state", "muted" to am.isMicrophoneMute, "speaker" to am.isSpeakerphoneOn, "held" to held, "note" to note)
    }

    /** Back to normal when the call is over, so a mute we set can't outlive it. */
    private fun resetAfterCall(ctx: Context) {
        val am = ctx.getSystemService(AudioManager::class.java)
        if (weMuted) { am.isMicrophoneMute = false; weMuted = false }
        @Suppress("DEPRECATION")
        if (weSpeaker) { am.isSpeakerphoneOn = false; weSpeaker = false }
        held = false
    }

    /**
     * Prefers the audio system (works with the screen off). If that doesn't take, presses the button on the
     * dialer's own call screen through the accessibility service.
     */
    fun control(ctx: Context, action: String) {
        Thread {
            val am = ctx.getSystemService(AudioManager::class.java)
            var note: String? = null
            @Suppress("DEPRECATION")
            when (action) {
                "mute" -> {
                    val want = !am.isMicrophoneMute
                    am.isMicrophoneMute = want
                    weMuted = want
                    if (am.isMicrophoneMute != want && pressInCall(ctx, listOf("mute"))) Unit
                    else if (am.isMicrophoneMute != want) note = "This phone didn't allow muting from the Mac."
                }
                "speaker" -> {
                    val want = !am.isSpeakerphoneOn
                    am.isSpeakerphoneOn = want
                    weSpeaker = want
                    if (am.isSpeakerphoneOn != want && !pressInCall(ctx, listOf("speaker"))) note = "This phone didn't allow switching the speaker from the Mac."
                }
                "hold" -> {
                    if (pressInCall(ctx, listOf("hold"))) held = !held
                    else note = "Couldn't reach the Hold button. Turn on PhoneBridge in Accessibility (Type from Mac), then try again."
                }
            }
            report(ctx, note)
        }.start()
    }

    /** Presses a button on the dialer's call screen, bringing that screen up first if it isn't showing. */
    private fun pressInCall(ctx: Context, words: List<String>): Boolean {
        val svc = TypingService.instance ?: return false
        if (svc.pressByWords(words)) return true
        NotifListener.instance?.openCallScreen()
        Thread.sleep(900)
        return svc.pressByWords(words)
    }

    @Suppress("DEPRECATION")
    fun end(ctx: Context) {
        if (NotifListener.instance?.pressCallButton(answer = false) == true) return
        try {
            ctx.getSystemService(TelecomManager::class.java).endCall()
        } catch (e: SecurityException) {
            Log.w(TAG, "end call needs ANSWER_PHONE_CALLS", e)
        }
    }
}

object Clip {
    fun setFromMac(ctx: Context, text: String) = mainHandler.post {
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("From Mac", text))
    }

    /** Only works while one of our activities has focus (Android 10+ rule). */
    fun sendCurrent(ctx: Context): Boolean {
        if (runCatching { ImageClip.sendCurrent(ctx) }.getOrDefault(false)) return true
        val clip = ctx.getSystemService(ClipboardManager::class.java).primaryClip ?: return false
        if (clip.itemCount == 0) return false
        val text = clip.getItemAt(0).coerceToText(ctx)?.toString()
        if (text.isNullOrEmpty()) return false
        return Link.send("clip", "text" to text) != null
    }
}

/** "Find my phone": loud looping ringtone on the alarm stream; stopped from the Mac, the phone, or after 30 s. */
object Ringer {
    private const val NOTIF_ID = 3
    const val ACTION_STOP = "app.phonebridge.RING_STOP"
    private var ringtone: Ringtone? = null
    private val stopLater = Runnable { stop(Link.app) }

    fun start(ctx: Context) = mainHandler.post { if (ringtone?.isPlaying != true) begin(ctx) }

    fun stop(ctx: Context) = mainHandler.post { end(ctx) }

    private fun begin(ctx: Context) {
        val am = ctx.getSystemService(AudioManager::class.java)
        runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) }
        ringtone = RingtoneManager.getRingtone(ctx, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            isLooping = true
            play()
        }
        mainHandler.postDelayed(stopLater, 30_000)
        val stopIntent = PendingIntent.getBroadcast(
            ctx, 0, Intent(ACTION_STOP).setClass(ctx, PairReceiver::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(ctx, Channels.PAIR)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle("Ringing from your Mac")
            .setContentText("Tap to stop")
            .setContentIntent(stopIntent)
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .setDeleteIntent(stopIntent)
            .setOngoing(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
        Link.send("ring_state", "ringing" to true)
    }

    private fun end(ctx: Context) {
        mainHandler.removeCallbacks(stopLater)
        ringtone?.stop()
        ringtone = null
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        Link.send("ring_state", "ringing" to false)
    }
}

/** Launchable apps (name + icon) for the Mac's Apps window. */
object AppList {
    fun send(ctx: Context) {
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = org.json.JSONArray()
        pm.queryIntentActivities(launcher, 0)
            .distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName != ctx.packageName }
            .map { it to it.loadLabel(pm).toString() }
            .sortedBy { it.second.lowercase() }
            .forEach { (info, name) ->
                apps.put(
                    JSONObject().put("pkg", info.activityInfo.packageName).put("name", name)
                        .put("icon", Icons.png(info.loadIcon(pm), 72))
                )
            }
        Link.send(JSONObject().put("t", "apps").put("apps", apps))
    }
}

object Icons {
    fun png(d: android.graphics.drawable.Drawable, size: Int): String? = try {
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size)
        d.draw(android.graphics.Canvas(bmp))
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        Crypto.b64e(out.toByteArray())
    } catch (e: Exception) {
        null
    }
}

object Sms {
    fun send(ctx: Context, to: String, text: String) {
        val sms = ctx.getSystemService(SmsManager::class.java)
        val parts = sms.divideMessage(text)
        try {
            if (parts.size > 1) sms.sendMultipartTextMessage(to, null, parts, null, null)
            else sms.sendTextMessage(to, null, text, null, null)
            Link.send("sms_result", "ok" to true, "to" to to)
        } catch (e: Exception) {
            Log.w(TAG, "SMS failed", e)
            Link.send("sms_result", "ok" to false, "to" to to, "error" to e.message)
        }
    }
}

/** File transfer in both directions, in base64 chunks over the encrypted link. */
object Files {
    private const val CHUNK = 192 * 1024

    private class Incoming(val uri: Uri, val out: OutputStream, val name: String)
    private val incoming = HashMap<String, Incoming>()

    fun incoming(ctx: Context, m: JSONObject) {
        val fid = m.getString("fid")
        when (m.getString("t")) {
            "file_start" -> {
                val name = uniqueName(ctx, m.getString("name"))
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mimeFor(name, m.optString("mime", "application/octet-stream")))
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
                val out = ctx.contentResolver.openOutputStream(uri) ?: return
                incoming[fid] = Incoming(uri, out, name)
            }
            "file_chunk" -> incoming[fid]?.out?.write(Crypto.b64d(m.getString("data")))
            "file_end" -> {
                val f = incoming.remove(fid) ?: return
                f.out.close()
                ctx.contentResolver.update(f.uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                notifyReceived(ctx, f.uri, f.name)
            }
        }
    }

    /** The Mac often sends "application/octet-stream" (e.g. for .apk); work it out from the extension instead. */
    private fun mimeFor(name: String, sent: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return sent
        if (ext == "apk") return "application/vnd.android.package-archive"
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: sent
    }

    /**
     * "name (1).ext" when [name] is already in Downloads. Android would otherwise tack " (1)" on after the
     * extension for generic file types ("PhoneBridge.apk (1)"), which breaks opening the file.
     */
    private fun uniqueName(ctx: Context, name: String): String {
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        fun taken(n: String) = ctx.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(n), null,
        )?.use { it.count > 0 } ?: false
        var candidate = name
        var i = 1
        while (taken(candidate) && i < 1000) candidate = "$base ($i)$ext".also { i++ }
        return candidate
    }

    private fun notifyReceived(ctx: Context, uri: Uri, name: String) {
        val open = Intent(Intent.ACTION_VIEW).setDataAndType(uri, ctx.contentResolver.getType(uri))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val n = Notification.Builder(ctx, Channels.FILES)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Received from Mac")
            .setContentText("$name — saved to Downloads")
            .setContentIntent(PendingIntent.getActivity(ctx, name.hashCode(), open, PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(name.hashCode(), n)
    }

    /** Blocking; call from a background thread. [progress] gets 0..100. */
    fun send(ctx: Context, uri: Uri, kind: String? = null, progress: (Int) -> Unit): Boolean {
        val cr = ctx.contentResolver
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = if (uri.scheme == "file") java.io.File(uri.path ?: "").length() else -1L
        if (uri.scheme == "content") cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) {
                it.getString(0)?.let { n -> name = n }
                if (!it.isNull(1)) size = it.getLong(1)
            }
        }
        val fid = UUID.randomUUID().toString()
        val start = JSONObject().put("t", "file_start").put("fid", fid).put("name", name).put("size", size)
            .put("mime", cr.getType(uri) ?: "application/octet-stream")
        if (kind != null) start.put("kind", kind)
        if (!Link.sendBlocking(start)) return false
        var sent = 0L
        cr.openInputStream(uri)?.use { input ->
            val buf = ByteArray(CHUNK)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                val chunk = JSONObject().put("t", "file_chunk").put("fid", fid).put("data", Crypto.b64e(buf.copyOf(n)))
                if (!Link.sendBlocking(chunk)) return false
                sent += n
                if (size > 0) progress((sent * 100 / size).toInt())
            }
        } ?: return false
        return Link.sendBlocking(JSONObject().put("t", "file_end").put("fid", fid))
    }
}
