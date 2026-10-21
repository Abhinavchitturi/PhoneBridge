package app.phonebridge

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Remote tools for the Mac's Device tab: storage, flashlight, ringer, lock, find-my-phone location. */
object DeviceTools {
    @Volatile private var torchOn = false

    private fun has(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun ringerName(ctx: Context): String = when (ctx.getSystemService(AudioManager::class.java).ringerMode) {
        AudioManager.RINGER_MODE_SILENT -> "silent"
        AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
        else -> "normal"
    }

    // MARK: Storage

    fun sendInfo(ctx: Context) = thread {
        val st = StatFs(Environment.getDataDirectory().path)
        fun sum(uri: android.net.Uri, perm: String): Long {
            if (!has(ctx, perm)) return 0
            var s = 0L
            ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use { while (it.moveToNext()) s += it.getLong(0) }
            return s
        }
        val images = sum(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Manifest.permission.READ_MEDIA_IMAGES)
        val videos = sum(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, Manifest.permission.READ_MEDIA_VIDEO)
        Link.send(JSONObject().put("t", "device").put("total", st.totalBytes).put("free", st.availableBytes)
            .put("images", images).put("videos", videos).put("torch", torchOn).put("ringer", ringerName(ctx)))
    }

    /** The largest videos, so you can clear space from the Mac. */
    fun sendBiggest(ctx: Context) = thread {
        val items = JSONArray()
        if (has(ctx, Manifest.permission.READ_MEDIA_VIDEO)) {
            val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val args = Bundle().apply {
                putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.SIZE))
                putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION, android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, 20)
            }
            ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DURATION), args, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val thumb = try {
                        val bmp = ctx.contentResolver.loadThumbnail(ContentUris.withAppendedId(uri, id), Size(160, 160), null)
                        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 70, it) }.let { Crypto.b64e(it.toByteArray()) }
                    } catch (e: Exception) {
                        null
                    }
                    items.put(JSONObject().put("id", id).put("name", c.getString(1)).put("size", c.getLong(2))
                        .put("date", c.getLong(3) * 1000).put("duration", if (c.isNull(4)) 0 else c.getLong(4)).put("thumb", thumb))
                }
            }
        }
        Link.send(JSONObject().put("t", "biggest").put("items", items))
    }

    // MARK: Remote controls

    fun torch(ctx: Context, on: Boolean) {
        try {
            val cm = ctx.getSystemService(CameraManager::class.java)
            val id = cm.cameraIdList.firstOrNull {
                val ch = cm.getCameraCharacteristics(it)
                ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true && ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
            if (id == null) {
                Link.send("alert", "title" to "No flashlight", "body" to "This phone has no flash to switch on.")
                return
            }
            cm.setTorchMode(id, on)
            torchOn = on
            Link.send("torch_state", "on" to on)
        } catch (e: Exception) {
            Log.w(TAG, "torch failed", e)
            Link.send("alert", "title" to "Flashlight", "body" to "Couldn't switch it: ${e.message}")
        }
    }

    fun setRinger(ctx: Context, mode: String) {
        try {
            ctx.getSystemService(AudioManager::class.java).ringerMode = when (mode) {
                "silent" -> AudioManager.RINGER_MODE_SILENT
                "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
                else -> AudioManager.RINGER_MODE_NORMAL
            }
        } catch (e: SecurityException) {
            Link.send("alert", "title" to "Silent mode needs permission",
                "body" to "On the phone: Settings → Notifications → Do Not Disturb → Apps → allow PhoneBridge. Vibrate works without it.")
        }
        sendInfo(ctx)
    }

    fun lock(ctx: Context) {
        val svc = TypingService.instance
        if (svc == null) {
            Link.send("alert", "title" to "Turn on control from the Mac",
                "body" to "On the phone: PhoneBridge → Type from Mac → switch on PhoneBridge in Accessibility, then lock works.")
            return
        }
        svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
    }

    /** Wake the phone, swipe past the lock screen, and tap in [pin] if one is given (never stored or logged). */
    fun unlock(ctx: Context, pin: String?) {
        val svc = TypingService.instance
        if (svc == null) {
            Link.send("alert", "title" to "Turn on control from the Mac",
                "body" to "On the phone: PhoneBridge → Type from Mac → switch on PhoneBridge in Accessibility, then unlock works.")
            return
        }
        thread {
            val problem = try { svc.unlockBlocking(pin) } catch (e: Exception) { "Unlock failed: ${e.message}" }
            Link.send("alert", "title" to (if (problem == null) "Phone woken" else "Couldn't unlock"),
                "body" to (problem ?: if (pin.isNullOrEmpty()) "Screen on and swiped up." else "PIN entered — check your phone."))
        }
    }

    // MARK: Find my phone

    fun sendLocation(ctx: Context) = thread {
        val fine = has(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = has(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!fine && !coarse) {
            Link.send(JSONObject().put("t", "loc").put("error", "permission"))
            return@thread
        }
        val lm = ctx.getSystemService(LocationManager::class.java)
        val providers = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) {
            Link.send(JSONObject().put("t", "loc").put("error", "off"))
            return@thread
        }
        var best: Location? = null
        val executor = Executors.newSingleThreadExecutor()
        for (p in providers) {
            val latch = CountDownLatch(1)
            try {
                lm.getCurrentLocation(p, null, executor) { best = it; latch.countDown() }
                latch.await(8, TimeUnit.SECONDS)
            } catch (e: SecurityException) {
                Log.w(TAG, "location denied", e)
            }
            if (best != null) break
        }
        if (best == null) best = providers.firstNotNullOfOrNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        executor.shutdown()
        val loc = best
        if (loc == null) Link.send(JSONObject().put("t", "loc").put("error", "no_fix"))
        else Link.send(JSONObject().put("t", "loc").put("lat", loc.latitude).put("lon", loc.longitude)
            .put("acc", loc.accuracy.toDouble()).put("time", loc.time))
    }
}
