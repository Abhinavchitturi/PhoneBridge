package app.phonebridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.BatteryManager
import android.os.IBinder
import android.telephony.TelephonyManager
import android.util.Log

object Channels {
    const val STATUS = "status"
    const val PAIR = "pair"
    const val FILES = "files"
    const val GUARD = "guard"

    fun create(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(STATUS, "Connection status", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(PAIR, "Pairing requests", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(FILES, "Files from Mac", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(GUARD, "Mac guard alerts", NotificationManager.IMPORTANCE_HIGH))
    }
}

/** Keeps the link alive in the background and advertises the phone on the local network. */
class LinkService : Service() {
    private var nsdListener: NsdManager.RegistrationListener? = null
    private val onStatus = { updateNotification() }

    override fun onCreate() {
        super.onCreate()
        Channels.create(this)
        try {
            startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            BridgeApp.recordError(this, "startForeground", e)
        }
        // Each step is isolated so one failure is reported instead of killing the whole app.
        step("link") { Link.start(this) }
        Link.listeners.add(onStatus)
        step("bonjour") { registerNsd() }
        step("calls") {
            registerReceiver(Calls.receiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED), RECEIVER_EXPORTED)
        }
        step("battery") {
            registerReceiver(Battery.receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), RECEIVER_EXPORTED)
        }
        instance = this
        step("presence") { Presence.start(this) }
        step("screenshots") { ScreenshotWatch.start(this) }
        step("guard") { Guard.startRelay(this) }
    }

    private fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            BridgeApp.recordError(this, "service step '$name'", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    private var micOn = false
    private var projectionOn = false

    /** Adds/removes the microphone foreground-service type (Android requires it while recording). */
    fun setMicrophone(on: Boolean) {
        micOn = on
        updateTypes()
    }

    /** Adds/removes the screen-capture foreground-service type (required while casting). */
    fun setProjection(on: Boolean) {
        projectionOn = on
        updateTypes()
    }

    private fun updateTypes() {
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            (if (micOn) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0) or
            (if (projectionOn) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
        try {
            startForeground(1, buildNotification(), types)
        } catch (e: Exception) {
            BridgeApp.recordError(this, "microphone foreground", e)
        }
    }

    override fun onDestroy() {
        instance = null
        Link.listeners.remove(onStatus)
        runCatching { unregisterReceiver(Calls.receiver) }
        runCatching { unregisterReceiver(Battery.receiver) }
        nsdListener?.let { runCatching { getSystemService(NsdManager::class.java).unregisterService(it) } }
        super.onDestroy()
    }

    private fun registerNsd() {
        val info = NsdServiceInfo().apply {
            serviceName = Link.deviceName
            serviceType = "_phonebridge._tcp"
            port = Link.PORT
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {
                Log.i(TAG, "advertised as ${i.serviceName}")
            }
            override fun onRegistrationFailed(i: NsdServiceInfo, err: Int) {
                Log.w(TAG, "NSD registration failed $err")
            }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, err: Int) {}
        }
        nsdListener = listener
        getSystemService(NsdManager::class.java).registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, Channels.STATUS)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle(Link.status)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            )
            .build()

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(1, buildNotification())
    }

    companion object {
        @Volatile var instance: LinkService? = null

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, LinkService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "could not start service: $e")
            }
        }
    }
}

object Battery {
    private var last = ""

    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = report(intent)
    }

    fun sendCurrent(ctx: Context) {
        last = ""
        ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { report(it) }
    }

    private fun report(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val key = "$level/$charging"
        if (key == last) return
        Automations.onBattery(Link.app, level, charging)
        last = key
        Link.send("battery", "level" to level, "charging" to charging)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = LinkService.start(context)
}
