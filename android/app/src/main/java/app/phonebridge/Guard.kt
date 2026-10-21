package app.phonebridge

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.text.DateFormat
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/** The guard's crypto, with no Android dependencies so it can be unit-tested against the Mac's output. */
object GuardCrypto {
    private const val SALT = "phonebridge-guard"

    fun derive(key: ByteArray, label: String) = Crypto.hkdf(key, SALT.toByteArray(), label)
    fun topic(key: ByteArray, label: String) = "pbg-" + derive(key, label).copyOf(12).joinToString("") { "%02x".format(it) }
    fun totpSeed(key: ByteArray) = derive(key, "totp").copyOf(20)

    /** RFC 6238: HMAC-SHA1, 6 digits, 30 s steps. [counter] = unix seconds / 30. */
    fun totp(seed: ByteArray, counter: Long): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(seed, "HmacSHA1"))
        val h = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array())
        val o = h[19].toInt() and 0x0f
        val v = ((h[o].toInt() and 0x7f) shl 24) or ((h[o + 1].toInt() and 0xff) shl 16) or
            ((h[o + 2].toInt() and 0xff) shl 8) or (h[o + 3].toInt() and 0xff)
        return "%06d".format(v % 1_000_000)
    }

    fun sign(key: ByteArray, message: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(derive(key, "cmd"), "HmacSHA256")) }.doFinal(message)

    /** nonce (12) || ciphertext || tag, AES-256-GCM. */
    fun decrypt(key: ByteArray, blob: ByteArray): ByteArray {
        val aes = Cipher.getInstance("AES/GCM/NoPadding")
        aes.init(Cipher.DECRYPT_MODE, SecretKeySpec(derive(key, "photo"), "AES"), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        return aes.doFinal(blob, 12, blob.size - 12)
    }
}

/**
 * The phone's half of the Mac's intruder guard. The Mac hands over a random key when the guard is switched on; from it both
 * sides derive the same secrets (see mac/Sources/GuardCore.swift):
 *   HKDF-SHA256(ikm = key, salt = "phonebridge-guard", info = label), labels "photo" (AES-256-GCM key), "cmd" (HMAC key),
 *   "topic-out" / "topic-cmd" (relay topics) and "totp" (authenticator seed).
 * Alerts arrive over the paired link, or — when the Mac is somewhere else — from a ntfy relay, encrypted.
 */
object Guard {
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val seenAlerts = ArrayDeque<String>()
    @Volatile private var relayRunning = false

    fun key(ctx: Context): ByteArray? =
        Prefs.get(ctx).getString("guardKeySealed", null)?.let { runCatching { Vault.open(it) }.getOrNull() }

    fun isOn(ctx: Context) = key(ctx) != null
    private fun server(ctx: Context) = (Prefs.get(ctx).getString("guardServer", null) ?: "https://ntfy.sh").trimEnd('/', ' ')

    private fun topic(key: ByteArray, label: String) = GuardCrypto.topic(key, label)

    fun code(ctx: Context, nowMs: Long = System.currentTimeMillis()): String? {
        val k = key(ctx) ?: return null
        return GuardCrypto.totp(GuardCrypto.totpSeed(k), nowMs / 1000 / 30)
    }

    // MARK: From the Mac over the paired link

    fun setup(ctx: Context, m: JSONObject) {
        val key = Crypto.b64d(m.getString("key"))
        require(key.size == 32) { "bad guard key" }
        Prefs.get(ctx).edit().putString("guardKeySealed", Vault.seal(key)).putString("guardServer", m.optString("server", "https://ntfy.sh"))
            .remove("guardSince").apply()
        startRelay(ctx)
        PhoneAlerts.alert(ctx, "Mac guard is on", "Your unlock code is in PhoneBridge → Mac guard code.")
    }

    fun off(ctx: Context) {
        Prefs.get(ctx).edit().remove("guardKeySealed").remove("guardSince").apply()
        relayRunning = false
    }

    fun onLinkAlert(ctx: Context, m: JSONObject) {
        val photos = m.optJSONArray("photos")
        val list = List(photos?.length() ?: 0) { Crypto.b64d(photos!!.getString(it)) }
        if (m.optBoolean("resend")) { // pictures we asked the Mac for: show them, don't alert again
            GuardStore.putPhotos(m.getString("id"), list)
            return
        }
        showAlert(ctx, m.getString("id"), m.optString("reason"), m.optLong("time", System.currentTimeMillis()), m.optString("host", "your Mac"), list,
            m.optBoolean("locked"))
    }

    // MARK: The alert

    /** An intent that opens the app on the Code tab (the rolling unlock code). */
    fun codeIntent(ctx: Context): Intent = Intent(ctx, MainActivity::class.java).putExtra("tab", "code")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private fun showAlert(ctx: Context, id: String, reason: String, timeMs: Long, host: String, photos: List<ByteArray>, locked: Boolean) {
        synchronized(seenAlerts) {
            if (id in seenAlerts) return
            seenAlerts.addLast(id)
            while (seenAlerts.size > 60) seenAlerts.removeFirst()
        }
        GuardStore.addAlert(ctx, id, reason, host, timeMs, photos)
        val nid = 6000 + (id.hashCode() and 0xfff)
        fun action(title: String, act: String) = Notification.Action.Builder(
            null, title,
            PendingIntent.getBroadcast(ctx, (act + id).hashCode(), Intent(act).setClass(ctx, GuardReceiver::class.java)
                .putExtra("id", id).putExtra("nid", nid), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
        ).build()
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timeMs))
        val b = Notification.Builder(ctx, Channels.GUARD)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle(if (locked) "$host is locked — is this you?" else "Someone opened $host")
            .setContentText("$reason · $time · tap to see the photos")
            .setCategory(Notification.CATEGORY_ALARM)
            // Tapping opens the full-size photos (the picture inside the notification is cropped).
            .setContentIntent(PendingIntent.getActivity(ctx, nid, Intent(ctx, GuardPhotoActivity::class.java)
                .putExtra("id", id).putExtra("index", 0).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true)
            .addAction(action("It's me", GuardReceiver.SAFE))
            .addAction(Notification.Action.Builder(null, "Code",
                PendingIntent.getActivity(ctx, nid + 1, codeIntent(ctx), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
            .addAction(action(if (locked) "Keep locked" else "Lock Mac", GuardReceiver.LOCK))
        val bmp = photos.firstNotNullOfOrNull { BitmapFactory.decodeByteArray(it, 0, it.size) }
        if (bmp != null) b.setLargeIcon(bmp).setStyle(Notification.BigPictureStyle().bigPicture(bmp).bigLargeIcon(null as android.graphics.Bitmap?))
        else b.setContentText("$reason · $time · no photo (camera unavailable)")
        ctx.getSystemService(NotificationManager::class.java).notify(nid, b.build())
    }

    // MARK: Commands to the Mac

    /** "safe" (it was me) or "lock". Over the paired link if it's up, otherwise signed and sent through the relay. */
    private val lastPhotoRequest = HashMap<String, Long>()

    /** Asks the Mac to send an alert's pictures again (this phone doesn't keep them). */
    fun requestPhotos(ctx: Context, eventId: String) {
        synchronized(lastPhotoRequest) {
            val now = System.currentTimeMillis()
            if (now - (lastPhotoRequest[eventId] ?: 0L) < 8000) return
            lastPhotoRequest[eventId] = now
        }
        sendCommand(ctx, "photos", eventId)
    }

    fun sendCommand(ctx: Context, cmd: String, eventId: String) {
        val quiet = cmd == "photos"
        thread {
            try {
                if (Link.isConnected) {
                    Link.send("guard_cmd", "c" to cmd, "e" to eventId)
                    if (!quiet) toast(ctx, if (cmd == "lock") "Lock sent to your Mac" else "Told your Mac it's you")
                    return@thread
                }
                val key = key(ctx) ?: return@thread
                val json = JSONObject().put("c", cmd).put("e", eventId).put("ts", System.currentTimeMillis())
                    .put("n", Crypto.b64e(ByteArray(12).also { SecureRandom().nextBytes(it) })).toString().toByteArray()
                val body = b64url(json) + "." + b64url(GuardCrypto.sign(key, json))
                http("${server(ctx)}/${topic(key, "topic-cmd")}", "POST", body.toByteArray())
                if (!quiet) toast(ctx, if (cmd == "lock") "Lock sent. Your Mac will lock the next time it's online." else "Told your Mac it's you — it opens when it's online")
            } catch (e: Exception) {
                Log.w(TAG, "guard command failed", e)
                if (!quiet) toast(ctx, "Couldn't reach the relay — check your internet and tap again")
            }
        }
    }

    private fun toast(ctx: Context, text: String) = main.post { Toast.makeText(ctx, text, Toast.LENGTH_LONG).show() }

    private fun b64url(b: ByteArray) = android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING)

    // MARK: Relay (alerts from a Mac that isn't on this network)

    private fun http(url: String, method: String, body: ByteArray? = null): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15000
        c.readTimeout = 45000
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body) } }
        if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
        return c.inputStream.use { it.readBytes() }
    }

    /** Listens for encrypted alerts for as long as the guard is on. Safe to call more than once. */
    @Synchronized
    fun startRelay(ctx: Context) {
        if (relayRunning || !isOn(ctx)) return
        relayRunning = true
        val app = ctx.applicationContext
        // Photos are deleted at the end of the day.
        thread(name = "guard-purge", isDaemon = true) {
            while (relayRunning && isOn(app)) {
                GuardStore.purge(app)
                try { Thread.sleep(5 * 60 * 1000L) } catch (e: InterruptedException) { break }
            }
        }
        thread(name = "guard-relay", isDaemon = true) {
            var backoff = 5L
            while (relayRunning && isOn(app)) {
                try {
                    val key = key(app) ?: break
                    val since = Prefs.get(app).getLong("guardSince", 0L)
                    val param = if (since > 0) (since + 1).toString() else "12h"
                    val c = URL("${server(app)}/${topic(key, "topic-out")}/json?since=$param").openConnection() as HttpURLConnection
                    c.connectTimeout = 15000
                    c.readTimeout = 120000 // ntfy sends a keepalive about every 45 s
                    c.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            if (!relayRunning) break
                            handleRelayLine(app, key, line)
                        }
                    }
                    backoff = 5
                } catch (e: Exception) {
                    Log.d(TAG, "guard relay: ${e.message}")
                }
                try { Thread.sleep(backoff * 1000) } catch (e: InterruptedException) { break }
                backoff = minOf(backoff * 2, 60)
            }
            relayRunning = false
        }
    }

    private fun handleRelayLine(ctx: Context, key: ByteArray, line: String) {
        val o = try { JSONObject(line) } catch (e: Exception) { return }
        if (o.optString("event") != "message") return
        Prefs.get(ctx).edit().putLong("guardSince", o.optLong("time", 0L)).apply()
        val url = o.optJSONObject("attachment")?.optString("url").orEmpty()
        if (url.isEmpty()) return
        try {
            val bundle = JSONObject(String(GuardCrypto.decrypt(key, http(url, "GET"))))
            val arr = bundle.optJSONArray("photos")
            val photos = List(arr?.length() ?: 0) { Crypto.b64d(arr!!.getString(it)) }
            if (bundle.optBoolean("resend")) {
                GuardStore.putPhotos(bundle.getString("id"), photos)
                return
            }
            showAlert(ctx, bundle.getString("id"), bundle.optString("reason"), bundle.optLong("time", System.currentTimeMillis()),
                bundle.optString("host", "your Mac"), photos, bundle.optBoolean("locked"))
        } catch (e: Exception) {
            Log.w(TAG, "couldn't read a guard alert", e)
        }
    }
}

/** The "It's me" and "Lock Mac" buttons on the alert notification. */
class GuardReceiver : BroadcastReceiver() {
    companion object {
        const val SAFE = "app.phonebridge.GUARD_SAFE"
        const val LOCK = "app.phonebridge.GUARD_LOCK"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        context.getSystemService(NotificationManager::class.java).cancel(intent.getIntExtra("nid", 0))
        Guard.sendCommand(context, if (intent.action == LOCK) "lock" else "safe", id)
    }
}
