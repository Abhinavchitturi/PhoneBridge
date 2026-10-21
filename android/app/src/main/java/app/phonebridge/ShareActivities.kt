package app.phonebridge

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/** Share sheet → "Send to Mac": files go to the Mac's Downloads, text/links go to its clipboard. */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val label = TextView(this).apply { textSize = 18f; setPadding(60, 60, 60, 60); text = "Sending to Mac…" }
        setContentView(label)

        if (!Link.isConnected) {
            Toast.makeText(this, "Not connected to your Mac", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isEmpty()) {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            val url = text?.let { Regex("https?://\\S+").find(it)?.value }
            val openOnMac = intent.component?.className?.endsWith("OpenOnMac") == true
            val msg = when {
                openOnMac && url != null -> { MacRemote.command("open_url", "url" to url); "Opened on Mac" }
                text != null -> { Link.send("shared_text", "text" to text); "Copied on Mac" }
                else -> "Nothing to send"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Keep this activity open while sending: the read permission for shared files ends when it finishes.
        thread {
            var ok = true
            uris.forEachIndexed { i, uri ->
                ok = ok && Files.send(this, uri) { pct ->
                    runOnUiThread { label.text = "Sending ${i + 1}/${uris.size} to Mac… $pct%" }
                }
            }
            runOnUiThread {
                Toast.makeText(this, if (ok) "Sent to Mac" else "Sending failed", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}

/** Invisible activity that grabs focus for a moment so Android lets us read the clipboard. */
class ClipActivity : Activity() {
    private var done = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done) return
        done = true
        val ok = Link.isConnected && Clip.sendCurrent(this)
        Toast.makeText(this, if (ok) "Clipboard sent to Mac" else "Not connected / clipboard empty", Toast.LENGTH_SHORT).show()
        finish()
    }
}

/** Quick Settings tile: one tap sends the phone clipboard to the Mac. */
class ClipTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = if (Link.isConnected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        val i = Intent(this, ClipActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}
