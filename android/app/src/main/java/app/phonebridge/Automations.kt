package app.phonebridge

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.ParcelUuid
import android.util.Log
import java.security.MessageDigest

/**
 * Bluetooth LE beacon so the Mac can tell when the phone walks away (lock) or comes back (wake).
 * Layout must match mac/Sources/Presence.swift: service UUID + manufacturer data 0xFFFF with the
 * first 6 bytes of SHA-256(phone public key), so the Mac only reacts to *this* phone.
 */
object Presence {
    val SERVICE: ParcelUuid = ParcelUuid.fromString("6a1b2c3d-5e6f-4a7b-8c9d-50484f4e4542")
    private var callback: AdvertiseCallback? = null

    fun start(ctx: Context) {
        if (callback != null) return
        if (ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) return
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        if (!adapter.isEnabled) return
        val advertiser = adapter.bluetoothLeAdvertiser ?: return
        val id = MessageDigest.getInstance("SHA-256").digest(Crypto.pubRaw).copyOf(6)
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(SERVICE)
            .addManufacturerData(0xFFFF, id)
            .setIncludeDeviceName(false)
            .build()
        val cb = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "presence beacon failed: $errorCode")
                callback = null
            }
        }
        callback = cb
        try {
            advertiser.startAdvertising(settings, data, cb)
        } catch (e: SecurityException) {
            callback = null
        }
    }
}

/** Rules that make the phone and Mac act together. Each can be switched off in the Automations screen. */
object Automations {
    const val CALL_MUTE = "auto_call_mute"
    const val BATTERY_ALERTS = "auto_battery_alerts"
    const val SCREENSHOTS = "auto_screenshots"

    fun enabled(ctx: Context, key: String) = Prefs.get(ctx).getBoolean(key, true)
    fun set(ctx: Context, key: String, on: Boolean) = Prefs.get(ctx).edit().putBoolean(key, on).apply()

    fun onBattery(ctx: Context, level: Int, charging: Boolean) = batteryAlerts(ctx, level, charging)

    // MARK: Phone battery → alert on the Mac

    private fun batteryAlerts(ctx: Context, level: Int, charging: Boolean) {
        if (!enabled(ctx, BATTERY_ALERTS)) return
        val p = Prefs.get(ctx)
        if (!charging && level <= 20 && !p.getBoolean("lowAlerted", false)) {
            p.edit().putBoolean("lowAlerted", true).apply()
            Link.send("alert", "title" to "Phone battery low", "body" to "$level% — plug in your phone")
        }
        if (charging || level > 25) p.edit().putBoolean("lowAlerted", false).apply()
        if (charging && level >= 100 && !p.getBoolean("fullAlerted", false)) {
            p.edit().putBoolean("fullAlerted", true).apply()
            Link.send("alert", "title" to "Phone fully charged", "body" to "You can unplug your phone")
        }
        if (!charging) p.edit().putBoolean("fullAlerted", false).apply()
    }

    // MARK: Call → mute the Mac

    @Volatile private var callMuted = false

    fun onCallState(ctx: Context, state: String) {
        if (!enabled(ctx, CALL_MUTE)) return
        if (state != "idle" && !callMuted) {
            callMuted = true
            MacRemote.command("call_mute_on")
        } else if (state == "idle" && callMuted) {
            callMuted = false
            MacRemote.command("call_mute_off")
        }
    }
}

/** Notifications the Mac asks the phone to show: alerts, and browser tabs handed off from the Mac. */
object PhoneAlerts {
    private const val CHANNEL = Channels.PAIR // high importance

    fun alert(ctx: Context, title: String, body: String) {
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(title.hashCode(), n)
    }

    /** Opens straight away if PhoneBridge is on screen; otherwise a tap-to-open notification (Android blocks background launches). */
    fun openUrl(ctx: Context, url: String, title: String?) {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (MainActivity.visible) {
            ctx.startActivity(view)
            return
        }
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle("From your Mac — tap to open")
            .setContentText(title?.takeIf { it.isNotBlank() } ?: url)
            .setContentIntent(PendingIntent.getActivity(ctx, url.hashCode(), view, PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(url.hashCode(), n)
    }
}
