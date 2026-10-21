package app.phonebridge

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaMetadata
import android.media.MediaRecorder
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.service.notification.NotificationListenerService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

private val main = Handler(Looper.getMainLooper())

/** What's playing on the phone (any app with a media session) → Mac, and play/pause/skip from the Mac. */
object MediaWatch {
    private var controller: MediaController? = null
    private var lastKey = ""
    private var lastArtTitle = ""

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = push()
        override fun onPlaybackStateChanged(state: PlaybackState?) = push()
        override fun onSessionDestroyed() { pick(null) }
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { pick(it) }

    /** Needs notification access (that's what lets an app see other apps' media sessions). */
    fun start(ctx: Context) {
        val manager = ctx.getSystemService(MediaSessionManager::class.java)
        val component = ComponentName(ctx, NotifListener::class.java)
        try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, component, main)
            pick(manager.getActiveSessions(component))
        } catch (e: SecurityException) {
            Log.w(TAG, "media sessions need notification access")
        }
    }

    private fun pick(sessions: List<MediaController>?) {
        val list = sessions.orEmpty()
        val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = best
            best?.registerCallback(callback, main)
        }
        push()
    }

    fun resend() {
        lastKey = ""
        lastArtTitle = ""
        push()
    }

    private fun push() {
        val c = controller
        val meta = c?.metadata
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
        if (c == null || title.isNullOrBlank()) {
            if (lastKey != "none") Link.send("media")
            lastKey = "none"
            return
        }
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        val key = "$title|$artist|$playing"
        if (key == lastKey) return
        lastKey = key
        val m = JSONObject().put("t", "media").put("title", title).put("artist", artist).put("playing", playing)
            .put("app", appLabel(c.packageName))
        // Album art only when the track changes — it's the big part.
        if (title != lastArtTitle) {
            lastArtTitle = title
            val art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            art?.let { m.put("art", jpeg(it, 200)) }
        }
        Link.send(m)
    }

    fun command(cmd: String) {
        val c = controller ?: return
        val t = c.transportControls
        when (cmd) {
            "play_pause" -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
            "next" -> t.skipToNext()
            "prev" -> t.skipToPrevious()
        }
    }

    private fun appLabel(pkg: String): String = try {
        val pm = Link.app.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun jpeg(bmp: Bitmap, size: Int): String {
        val scaled = Bitmap.createScaledBitmap(bmp, size, size * bmp.height / maxOf(1, bmp.width), true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return Crypto.b64e(out.toByteArray())
    }
}

/** New phone screenshots → Mac (copied to its clipboard and saved in Downloads). */
object ScreenshotWatch {
    private var lastId = -1L
    private var observer: ContentObserver? = null

    fun start(ctx: Context) {
        if (observer != null) return
        if (ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) return
        lastId = newest(ctx)?.first ?: -1
        val o = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                if (!Automations.enabled(ctx, Automations.SCREENSHOTS) || !Link.isConnected) return
                thread {
                    val (id, isScreenshot) = newest(ctx) ?: return@thread
                    if (id <= lastId) return@thread
                    lastId = id
                    if (isScreenshot) {
                        val uri2 = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                        Files.send(ctx, uri2, kind = "screenshot") { }
                    }
                }
            }
        }
        ctx.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, o)
        observer = o
    }

    /** (id, is it in a Screenshots folder) of the newest finished image. */
    private fun newest(ctx: Context): Pair<Long, Boolean>? =
        ctx.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.RELATIVE_PATH),
            null, null, "${MediaStore.Images.Media._ID} DESC",
        )?.use { c ->
            if (!c.moveToFirst()) null
            else c.getLong(0) to (c.getString(1)?.contains("Screenshot", ignoreCase = true) == true)
        }
}

/** Phone microphone → Mac (16 kHz mono PCM, 40 ms frames). Must be started while the app is on screen. */
object MicStreamer {
    @Volatile var running = false
        private set

    fun start(ctx: Context): Boolean {
        if (running) return true
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        LinkService.instance?.setMicrophone(true)
        running = true
        Link.send("mic_state", "on" to true)
        thread(name = "mic") {
            val rate = 16_000
            val frame = 640 // 40 ms
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min, frame * 4))
            } catch (e: SecurityException) {
                running = false
                return@thread
            }
            val samples = ShortArray(frame)
            val bytes = ByteBuffer.allocate(frame * 2).order(ByteOrder.LITTLE_ENDIAN)
            rec.startRecording()
            while (running && Link.isConnected) {
                val n = rec.read(samples, 0, frame)
                if (n <= 0) continue
                bytes.clear()
                for (i in 0 until n) bytes.putShort(samples[i])
                Link.send("mic_audio", "pcm" to Crypto.b64e(bytes.array().copyOf(n * 2)))
            }
            rec.stop()
            rec.release()
            if (running) stop()
        }
        return true
    }

    fun stop() {
        running = false
        Link.send("mic_state", "on" to false)
        LinkService.instance?.setMicrophone(false)
        Link.changed()
    }
}

/**
 * Phone Do Not Disturb, only ever switched by the user (the 🌙 toggle in the Mac sidebar).
 * Remembers whether we turned it on, so an update/restart can switch our DND back off.
 */
object Dnd {
    fun set(ctx: Context, on: Boolean) {
        val nls = NotifListener.instance ?: return
        Prefs.get(ctx).edit().putBoolean("dndByUs", on).apply()
        nls.requestInterruptionFilter(
            if (on) NotificationListenerService.INTERRUPTION_FILTER_PRIORITY else NotificationListenerService.INTERRUPTION_FILTER_ALL
        )
    }

    /** Older versions turned DND on automatically ("I'm at my Mac", meeting mode); switch that off once. */
    fun undoAutomaticDnd(nls: NotifListener) {
        val p = Prefs.get(nls)
        if (p.getBoolean("dndCleanupDone", false)) return
        p.edit().putBoolean("dndCleanupDone", true).putBoolean("dndByUs", false).apply()
        if (nls.currentInterruptionFilter == NotificationListenerService.INTERRUPTION_FILTER_PRIORITY) {
            nls.requestInterruptionFilter(NotificationListenerService.INTERRUPTION_FILTER_ALL)
        }
    }
}

/** Mac keyboard → the focused text box on the phone (needs this accessibility service switched on). */
class TypingService : AccessibilityService() {
    companion object {
        @Volatile var instance: TypingService? = null
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun focused() = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

    fun setText(text: String) = main.post {
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        focused()?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun enter() = main.post {
        focused()?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    // MARK: Control while casting — coordinates arrive normalised (0…1) to the phone's screen.

    private fun point(nx: Double, ny: Double): Pair<Float, Float> {
        val b = getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds
        return (nx.coerceIn(0.0, 1.0) * (b.width() - 1)).toFloat() to (ny.coerceIn(0.0, 1.0) * (b.height() - 1)).toFloat()
    }

    fun gesture(x1: Double, y1: Double, x2: Double, y2: Double, durationMs: Long) = main.post {
        val (ax, ay) = point(x1, y1)
        val (bx, by) = point(x2, y2)
        val path = android.graphics.Path().apply { moveTo(ax, ay); lineTo(bx, by) }
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(1, 3000))
        dispatchGesture(android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private var stroke: android.accessibilityservice.GestureDescription.StrokeDescription? = null
    private var lastX = 0f
    private var lastY = 0f
    private var lastAt = 0L

    /** Live touch from the Mac: "down" puts a finger on the screen, "move" drags it, "up" lifts it. */
    fun touch(phase: String, nx: Double, ny: Double) = main.post {
        val (x, y) = point(nx, ny)
        val now = android.os.SystemClock.uptimeMillis()
        val path = android.graphics.Path().apply {
            moveTo(if (phase == "down") x else lastX, if (phase == "down") y else lastY)
            lineTo(x, y)
        }
        val duration = if (phase == "down") 1L else (now - lastAt).coerceIn(8, 100)
        val keepDown = phase != "up"
        val prev = stroke
        val next = if (phase == "down" || prev == null) {
            android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, duration, keepDown)
        } else {
            try { prev.continueStroke(path, 0, duration, keepDown) } catch (e: IllegalStateException) {
                android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, duration, keepDown)
            }
        }
        stroke = if (keepDown) next else null
        lastX = x
        lastY = y
        lastAt = now
        dispatchGesture(android.accessibilityservice.GestureDescription.Builder().addStroke(next).build(), null, null)
    }

    fun nav(action: String) = main.post {
        performGlobalAction(when (action) {
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            else -> GLOBAL_ACTION_BACK
        })
    }

    /** Typing while casting: appends to / deletes from whatever text box is focused. */
    fun type(text: String?, backspace: Boolean) = main.post {
        val node = focused() ?: return@post
        val current = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val next = if (backspace) current.dropLast(1) else current + text.orEmpty()
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, next) }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Presses the first button on an in-call screen whose label contains one of [words] (e.g. "hold"). */
    fun pressByWords(words: List<String>): Boolean {
        for (root in allRoots()) {
            val pkg = root.packageName?.toString().orEmpty()
            if (!(pkg.contains("incall") || pkg.contains("dialer") || pkg.contains("telecom") || pkg.contains("phone"))) continue
            val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            while (queue.isNotEmpty()) {
                val n = queue.removeFirst()
                val label = ((n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "")).lowercase()
                if (words.any { label.contains(it) } && tap(n)) return true
                for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    // MARK: Unlock — wake, swipe up, then (optionally) tap the PIN. The PIN is never saved anywhere.

    private fun allRoots(): List<AccessibilityNodeInfo> =
        (runCatching { windows.mapNotNull { it.root } }.getOrDefault(emptyList()) + listOfNotNull(rootInActiveWindow))

    private fun findKey(label: String): AccessibilityNodeInfo? {
        for (root in allRoots()) {
            val hit = root.findAccessibilityNodeInfosByText(label).firstOrNull {
                it.text?.toString() == label || it.contentDescription?.toString() == label
            }
            if (hit != null) return hit
        }
        return null
    }

    private fun tap(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        return n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    /** Blocking: call from a background thread. Returns a short note for the Mac, or null if all went to plan. */
    fun unlockBlocking(pin: String?): String? {
        @Suppress("DEPRECATION")
        val wl = getSystemService(android.os.PowerManager::class.java)
            .newWakeLock(android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP, "phonebridge:wake")
        wl.acquire(15_000)
        Thread.sleep(800)
        gesture(0.5, 0.88, 0.5, 0.25, 250)
        if (pin.isNullOrEmpty()) return null
        Thread.sleep(1000)
        for (digit in pin) {
            val key = findKey(digit.toString()) ?: return "Couldn't find the PIN pad. Is the phone showing its lock screen?"
            tap(key)
            Thread.sleep(180)
        }
        // Many lock screens need a confirm key after the digits; harmless if there isn't one.
        listOf("Enter", "OK", "Done").firstNotNullOfOrNull { findKey(it) }?.let { tap(it) }
        return null
    }

    fun launch(pkg: String) = main.post {
        packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/** Serves images copied on the Mac so they can sit on the phone clipboard (content:// URI). */
class ClipProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "app.phonebridge.clip"
        fun dir(ctx: Context) = File(ctx.cacheDir, "clip").apply { mkdirs() }
    }

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = File(dir(context!!), uri.lastPathSegment ?: throw IllegalArgumentException())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/png"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val file = File(dir(context!!), uri.lastPathSegment ?: "")
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf(file.name, file.length())) }
    }

    override fun insert(uri: Uri, values: ContentValues?) = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}

object ImageClip {
    fun setFromMac(ctx: Context, png: ByteArray) {
        val dir = ClipProvider.dir(ctx)
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "mac-${System.currentTimeMillis()}.png").apply { writeBytes(png) }
        val uri = Uri.parse("content://${ClipProvider.AUTHORITY}/${file.name}")
        main.post {
            ctx.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newUri(ctx.contentResolver, "Image from Mac", uri))
        }
    }

    /** If the phone clipboard holds an image, send it; returns false if it doesn't. Call while focused. */
    fun sendCurrent(ctx: Context): Boolean {
        val clip = ctx.getSystemService(ClipboardManager::class.java).primaryClip ?: return false
        val uri = clip.getItemAt(0)?.uri ?: return false
        val type = ctx.contentResolver.getType(uri) ?: clip.description.getMimeType(0)
        if (!type.startsWith("image/")) return false
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return false
        if (bytes.size > 8_000_000) return false
        val png = if (type == "image/png") bytes else {
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
            ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }
        Link.send("clip_image", "png" to Crypto.b64e(png))
        return true
    }
}

/**
 * Developer options + Wireless debugging, switched on only while the Mac has a phone app open
 * (it asks with "debug_on"/"debug_off"). Needs WRITE_SECURE_SETTINGS, which the Mac grants once during
 * pairing. If the Mac disappears mid-session, everything is switched off after a minute anyway.
 */
object DevSwitch {
    private const val ADB_WIFI = "adb_wifi_enabled"
    private val main = Handler(Looper.getMainLooper())
    private val offIfStillGone = Runnable { if (!Link.isConnected) off(Link.app) }

    private fun canControl(ctx: Context) =
        ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Wireless debugging only works on a Wi-Fi network (not while the phone hosts a hotspot). */
    private fun onWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        return cm.allNetworks.any { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    fun on(ctx: Context) {
        val reason = when {
            !canControl(ctx) -> "no_permission"
            !onWifi(ctx) -> "no_wifi"
            else -> null
        }
        if (reason != null) {
            Link.send("debug_state", "on" to false, "reason" to reason)
            return
        }
        val cr = ctx.contentResolver
        android.provider.Settings.Global.putInt(cr, android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1)
        android.provider.Settings.Global.putInt(cr, ADB_WIFI, 1)
        Prefs.get(ctx).edit().putBoolean("devByUs", true).apply()
        Link.send("debug_state", "on" to true)
    }

    fun off(ctx: Context) {
        main.removeCallbacks(offIfStillGone)
        if (!canControl(ctx)) return
        val cr = ctx.contentResolver
        try {
            android.provider.Settings.Global.putInt(cr, ADB_WIFI, 0)
            android.provider.Settings.Global.putInt(cr, android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0)
        } catch (e: Exception) {
            Log.w(TAG, "couldn't switch developer options off", e)
        }
        Prefs.get(ctx).edit().putBoolean("devByUs", false).apply()
        Link.send("debug_state", "on" to false, "reason" to "off")
    }

    /** Link to the Mac dropped: if we turned debugging on, turn it off unless the Mac is back within a minute. */
    fun onMacGone(ctx: Context) {
        if (Prefs.get(ctx).getBoolean("devByUs", false)) {
            main.removeCallbacks(offIfStillGone)
            main.postDelayed(offIfStillGone, 60_000)
        }
    }
}
