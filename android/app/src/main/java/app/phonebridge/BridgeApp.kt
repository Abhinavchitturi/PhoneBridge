package app.phonebridge

import android.app.Application
import android.util.Log

/** Saves any crash so MainActivity can show it on screen (there's no adb to read logs). */
class BridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            recordError(this, "crash on ${thread.name}", error)
            // A failing background task (e.g. screen casting) must not take down calls, notifications and
            // the link to the Mac with it: only a crash on the main thread is allowed to end the app.
            if (thread === android.os.Looper.getMainLooper().thread) previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        fun recordError(app: android.content.Context, where: String, error: Throwable) {
            Log.e(TAG, where, error)
            Prefs.get(app).edit()
                .putString("lastError", "$where:\n${Log.getStackTraceString(error).lines().take(12).joinToString("\n")}")
                .commit()
        }
    }
}
