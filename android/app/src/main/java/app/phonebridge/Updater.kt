package app.phonebridge

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Keeps the phone app up to date from the GitHub releases page: checks when opened, when the Mac connects and every 4 hours,
 * shows "Update available" on Home, and on a tap downloads PhoneBridge.apk and installs it over itself.
 * Android shows its own install confirmation the first time; once PhoneBridge has installed itself,
 * later updates go through without it.
 */
object Updater {
    private const val REPO = "Abhinavchitturi/PhoneBridge"
    private const val CHECK_EVERY_MS = 4 * 60 * 60 * 1000L
    /** Opening the app or connecting checks right away, but never more than once a minute (GitHub's limits). */
    private const val MIN_GAP_MS = 60 * 1000L
    private const val TAG = "PhoneBridge"

    class Release(val version: String, val apkUrl: String, val notes: String)

    @Volatile var available: Release? = null
        private set
    /** null when idle; otherwise what's happening ("Downloading 42%", "Installing…"). */
    @Volatile var progress: String? = null
        private set
    @Volatile private var checking = false

    fun current(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"

    /** Checks GitHub in the background; `force` ignores the every-few-hours limit (the Settings button). */
    fun check(ctx: Context, force: Boolean = false, onDone: ((Boolean) -> Unit)? = null) {
        val app = ctx.applicationContext
        val prefs = Prefs.get(app)
        val now = System.currentTimeMillis()
        // Remember what was found last time, so the banner shows right away between checks.
        if (available == null) restore(app)
        val since = now - prefs.getLong("updateChecked", 0)
        if (checking || since < MIN_GAP_MS || (!force && since < CHECK_EVERY_MS)) { onDone?.invoke(available != null); return }
        checking = true
        thread(name = "update-check") {
            val r = fetchLatest()
            checking = false
            if (r != null) {
                prefs.edit().putLong("updateChecked", now).apply()
                val newer = isNewer(r.version, current(app))
                available = if (newer) r else null
                prefs.edit().apply {
                    if (newer) putString("updateVersion", r.version).putString("updateApk", r.apkUrl).putString("updateNotes", r.notes)
                    else remove("updateVersion").remove("updateApk").remove("updateNotes")
                }.apply()
                Link.changed()
            }
            onDone?.invoke(r != null && available != null)
        }
    }

    private fun restore(ctx: Context) {
        val p = Prefs.get(ctx)
        val v = p.getString("updateVersion", null) ?: return
        val apk = p.getString("updateApk", null) ?: return
        available = if (isNewer(v, current(ctx))) Release(v, apk, p.getString("updateNotes", "") ?: "") else null
    }

    private fun fetchLatest(): Release? = try {
        val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 15000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "PhoneBridge-Updater")
        if (c.responseCode != 200) null else {
            val json = JSONObject(c.inputStream.bufferedReader().readText())
            val tag = json.getString("tag_name").removePrefix("v")
            val assets = json.getJSONArray("assets")
            var apk: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.getString("name")
                if (name == "PhoneBridge.apk" || (apk == null && name.endsWith(".apk"))) apk = a.getString("browser_download_url")
            }
            apk?.let { Release(tag, it, json.optString("body")) }
        }
    } catch (e: Exception) {
        Log.w(TAG, "update check failed: $e")
        null
    }

    /** "0.19" > "0.18", "0.18.1" > "0.18", "1.0" > "0.99". */
    fun isNewer(a: String, b: String): Boolean {
        val x = a.split(".").map { it.toIntOrNull() ?: 0 }
        val y = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val l = x.getOrElse(i) { 0 }; val r = y.getOrElse(i) { 0 }
            if (l != r) return l > r
        }
        return false
    }

    /** The first lines of the release notes, without Markdown clutter. */
    fun shortNotes(body: String): String = body.lines().map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && !it.contains("Full Changelog") && !it.startsWith("<!--") }
        .take(3).joinToString("\n") { it.replace("**", "") }

    // MARK: Install

    fun install(activity: Activity) {
        val r = available ?: return
        if (progress != null) return
        // Android needs a one-time "Install unknown apps" switch for PhoneBridge itself.
        if (!activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(activity, "Allow PhoneBridge to install updates, then tap Update again", Toast.LENGTH_LONG).show()
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            return
        }
        val app = activity.applicationContext
        setProgress("Downloading…")
        thread(name = "update-install") {
            try {
                val file = download(app, r)
                val info = app.packageManager.getPackageArchiveInfo(file.path, 0)
                require(info?.packageName == app.packageName) { "The download wasn't PhoneBridge" }
                require(info?.versionName == r.version) { "The download wasn't version ${r.version}" }
                setProgress("Installing…")
                Prefs.get(app).edit().putLong("updateStarted", System.currentTimeMillis()).apply()
                commit(app, file)
            } catch (e: Exception) {
                Log.w(TAG, "update failed: $e")
                fail(app, e.message ?: "Update failed")
            }
        }
    }

    private fun setProgress(s: String?) {
        progress = s
        Link.changed()
    }

    fun fail(ctx: Context, why: String) {
        setProgress(null)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(ctx, "Update didn't install: $why", Toast.LENGTH_LONG).show()
        }
    }

    private fun download(ctx: Context, r: Release): File {
        val out = File(ctx.cacheDir, "update.apk")
        var url = URL(r.apkUrl)
        // GitHub answers with a redirect to its file host.
        repeat(5) {
            val c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20000; c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "PhoneBridge-Updater")
            when (c.responseCode) {
                in 300..399 -> { url = URL(url, c.getHeaderField("Location")); c.disconnect() }
                200 -> {
                    val total = c.contentLengthLong
                    var got = 0L
                    var lastPct = -1
                    c.inputStream.use { input ->
                        out.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                o.write(buf, 0, n)
                                got += n
                                if (total > 0) {
                                    val pct = (got * 100 / total).toInt()
                                    if (pct != lastPct && pct % 5 == 0) { lastPct = pct; setProgress("Downloading $pct%") }
                                }
                            }
                        }
                    }
                    return out
                }
                else -> throw IllegalStateException("Download failed (${c.responseCode})")
            }
        }
        throw IllegalStateException("Too many redirects")
    }

    private fun commit(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            // Once PhoneBridge installed itself, Android lets it update itself without asking again.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            s.openWrite("PhoneBridge.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val intent = Intent(ctx, UpdateReceiver::class.java)
            val pi = PendingIntent.getBroadcast(ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            s.commit(pi.intentSender)
        }
    }

    /** After Android replaced the app: reopen it if the update was started from it a moment ago. */
    fun afterUpdate(ctx: Context) {
        val p = Prefs.get(ctx)
        val started = p.getLong("updateStarted", 0)
        p.edit().remove("updateStarted").remove("updateVersion").remove("updateApk").remove("updateNotes").apply()
        File(ctx.cacheDir, "update.apk").delete()
        if (System.currentTimeMillis() - started > 10 * 60 * 1000) return
        val open = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(open) } catch (_: Exception) { }
        // Some phones block opening from the background; leave a tap-to-open notification as well.
        Channels.create(ctx)
        val n = Notification.Builder(ctx, Channels.PAIR)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setColor(ThemeStore.get(ctx).accent)
            .setContentTitle("PhoneBridge updated to ${current(ctx)}")
            .setContentText("Tap to open")
            .setContentIntent(PendingIntent.getActivity(ctx, 7301, open, PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .setTimeoutAfter(5 * 60 * 1000)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(7301, n)
    }

    fun clearUpdatedNotice(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).cancel(7301)
}

/** Result of the install session: show Android's confirmation if it needs one, or report a failure. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // the app is replaced; BootReceiver reopens it
            else -> Updater.fail(ctx, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "cancelled")
        }
    }
}
