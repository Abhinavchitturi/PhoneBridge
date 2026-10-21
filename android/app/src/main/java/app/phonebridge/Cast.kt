package app.phonebridge

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Phone screen → Mac using Android's own screen casting (no debugging needed, works over a hotspot).
 * Android asks for permission every session ("Start recording or casting?"); with Android 14+ you can
 * choose one app or the entire screen. Video is H.264 from the hardware encoder, sent over the
 * encrypted link; the Mac's clicks come back as taps/swipes through TypingService (accessibility).
 */
object ScreenCaster {
    private const val MAX_SIDE = 1600
    private const val NOTIF_ID = 5
    private val main = Handler(Looper.getMainLooper())

    @Volatile var running = false
        private set
    @Volatile var pendingApp: String? = null

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    // Flow control: the Mac acks each frame it shows. Frames sent but not yet acked = what's "in the pipe"
    // (our queue + socket buffers + network). Keeping that small keeps the picture current.
    private val sentSeq = AtomicInteger(0)
    @Volatile private var ackedSeq = 0
    private const val MAX_UNACKED = 4
    // Adaptive bitrate: back off when frames get dropped, creep back up when they don't.
    @Volatile private var bitrate = 4_000_000
    @Volatile private var dropsSinceCheck = 0
    private var lastRateCheck = 0L
    private var dpi = 320
    // Diagnostics, sent to the Mac once a second so a stalled stream can be told apart from a slow one.
    @Volatile private var framesOut = 0
    @Volatile private var restarts = 0
    @Volatile private var lastErr = ""
    private var curRawW = 0
    private var curRawH = 0

    /** The Mac asked to cast (optionally opening an app). The permission prompt needs to be on screen. */
    fun request(ctx: Context, app: String?) {
        pendingApp = app
        if (running) {
            app?.let { TypingService.instance?.launch(it) }
            return
        }
        val open = Intent(ctx, CastActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // With the accessibility service on, Android lets us bring the prompt up straight away.
        if (MainActivity.visible || TypingService.instance != null) {
            try {
                ctx.startActivity(open)
                return
            } catch (e: Exception) {
                Log.w(TAG, "couldn't open cast prompt directly", e)
            }
        }
        val n = Notification.Builder(ctx, Channels.PAIR)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle("Show your phone on the Mac?")
            .setContentText("Tap to start casting")
            .setContentIntent(PendingIntent.getActivity(ctx, 8, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
    }

    fun start(ctx: Context, resultCode: Int, data: Intent) {
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        // Android 14+: the service must become a mediaProjection foreground service before we take the projection.
        LinkService.instance?.setProjection(true)
        val p = ctx.getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, data) ?: return
        projection = p
        dpi = ctx.resources.displayMetrics.densityDpi
        val bounds = ctx.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = stop()
            // Rotation, or the shared app changed size: restart the encoder at the new size.
            override fun onCapturedContentResize(width: Int, height: Int) {
                // Android also reports the size it started with; restarting for that would loop forever.
                if (running && (width != curRawW || height != curRawH)) startEncoder(width, height)
            }
        }, main)
        running = true
        framesOut = 0
        restarts = 0
        lastErr = ""
        startEncoder(bounds.width(), bounds.height())
        startWatchdog()
        Link.changed()
    }

    /** Reports progress to the Mac and, if the encoder never gets a first picture, re-attaches the capture surface. */
    private fun startWatchdog() {
        thread(name = "cast-watchdog") {
            var idle = 0
            var pokes = 0
            var last = -1
            while (running) {
                try { Thread.sleep(1000) } catch (e: InterruptedException) { return@thread }
                val n = framesOut
                idle = if (n == last) idle + 1 else 0
                last = n
                if (n == 0 && idle >= 2 && pokes < 6) {
                    pokes++
                    idle = 0
                    main.post { runCatching { display?.surface = surface } }
                }
                Link.send("cast_diag", "frames" to n, "restarts" to restarts, "sent" to sentSeq.get(), "acked" to ackedSeq,
                    "pokes" to pokes, "err" to lastErr)
            }
        }
    }

    @Synchronized
    private fun startEncoder(rawW: Int, rawH: Int) {
        curRawW = rawW
        curRawH = rawH
        restarts++
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(rawW, rawH))
        // Hardware encoders want sizes that are multiples of 16.
        val w = (rawW * scale).toInt() and 15.inv()
        val h = (rawH * scale).toInt() and 15.inv()
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // Keep sending frames when the screen is still, so the Mac never shows a stale picture for long.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
        }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val s = c.createInputSurface()
        c.start()
        codec = c
        surface = s
        val d = display
        if (d == null) {
            display = projection?.createVirtualDisplay("PhoneBridge", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, s, null, null)
        } else {
            d.resize(w, h, dpi)
            d.surface = s
        }
        Link.send("cast_state", "on" to true, "w" to w, "h" to h)
        thread(name = "cast-encoder") {
            // The encoder can be shut down under us (casting stopped, or a restart after rotation): just end.
            try { drain(c) } catch (e: IllegalStateException) { lastErr = "encoder: ${e.message}"; Log.i(TAG, "encoder loop ended: $e") }
        }
    }

    /** SPS+PPS (Annex B). Some encoders (e.g. Samsung Exynos) only report these via the output format. */
    @Volatile private var header: ByteArray? = null

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        header = null
        while (running && codec === c) {
            val index = try { c.dequeueOutputBuffer(info, 50_000) } catch (e: Exception) { break }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f = c.outputFormat
                val sps = f.getByteBuffer("csd-0")
                val pps = f.getByteBuffer("csd-1")
                if (sps != null && pps != null) {
                    header = bytesOf(sps) + bytesOf(pps)
                    sendFrame(header!!, key = true)
                }
                continue
            }
            if (index < 0) continue
            val buf = c.getOutputBuffer(index)
            if (buf != null && info.size > 0) {
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                var bytes = bytesOf(buf)
                val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                if (config) header = bytes
                // Every keyframe carries the header, so the Mac can start (or recover) at any keyframe.
                if (key && !config) header?.let { bytes = it + bytes }
                // If the network can't keep up, skip frames instead of building up delay; ask for a fresh keyframe.
                if (!config && !key && sentSeq.get() - ackedSeq > MAX_UNACKED) {
                    dropsSinceCheck++
                    c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                } else {
                    sendFrame(bytes, key || config)
                }
                if (!config) framesOut++
                adaptBitrate(c)
            }
            runCatching { c.releaseOutputBuffer(index, false) }
        }
    }

    private fun sendFrame(bytes: ByteArray, key: Boolean) {
        val seq = sentSeq.incrementAndGet()
        Link.send(JSONObject().put("t", "v").put("d", Crypto.b64e(bytes)).put("k", key).put("s", seq))
    }

    fun onAck(seq: Int) {
        if (seq > ackedSeq) ackedSeq = seq
    }

    private fun adaptBitrate(c: MediaCodec) {
        val now = System.currentTimeMillis()
        if (now - lastRateCheck < 2000) return
        lastRateCheck = now
        val next = if (dropsSinceCheck > 3) (bitrate * 0.7).toInt() else (bitrate * 1.1).toInt()
        dropsSinceCheck = 0
        val clamped = next.coerceIn(1_000_000, 6_000_000)
        if (clamped != bitrate) {
            bitrate = clamped
            c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, clamped) })
        }
    }

    private fun bytesOf(b: java.nio.ByteBuffer): ByteArray {
        val dup = b.duplicate()
        return ByteArray(dup.remaining()).also { dup.get(it) }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { display?.release() }
        display = null
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        codec = null
        surface?.release()
        surface = null
        runCatching { projection?.stop() }
        projection = null
        sentSeq.set(0)
        ackedSeq = 0
        LinkService.instance?.setProjection(false)
        Link.send("cast_state", "on" to false)
        Link.changed()
    }
}

/** Shows Android's cast permission prompt, then starts casting (and opens the requested app). */
class CastActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        // Whole screen, no app picker: PhoneBridge already knows which app you clicked on the Mac and opens it.
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        startActivityForResult(intent, 1)
    }

    @Deprecated("Activity result API without AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode == RESULT_OK && data != null) {
            try {
                ScreenCaster.start(this, resultCode, data)
                ScreenCaster.pendingApp?.let { pkg ->
                    packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            } catch (e: Exception) {
                BridgeApp.recordError(this, "start casting", e)
                Link.send("cast_state", "on" to false, "error" to (e.message ?: "couldn't start"))
            }
        } else {
            Link.send("cast_state", "on" to false, "error" to "Casting was cancelled on the phone")
        }
        ScreenCaster.pendingApp = null
        finish()
    }
}
