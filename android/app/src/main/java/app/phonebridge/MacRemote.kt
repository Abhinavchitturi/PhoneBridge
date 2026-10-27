package app.phonebridge

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject

/** What's playing on the Mac. */
class MacTrack(val title: String, val artist: String, val app: String, val playing: Boolean, val art: Bitmap?)

/** Latest state reported by the Mac, and commands sent to it. */
object MacRemote {
    @Volatile var battery: Int? = null
        private set
    @Volatile var charging = false
        private set
    @Volatile var volume: Int? = null
        private set
    @Volatile var muted = false
        private set
    @Volatile var shortcuts: List<String>? = null
        private set
    @Volatile var finding = false
        private set
    @Volatile var nowPlaying: MacTrack? = null
        private set

    fun onState(m: JSONObject) {
        battery = if (m.has("battery")) m.getInt("battery") else null
        charging = m.optBoolean("charging")
        volume = if (m.has("volume")) m.getInt("volume") else null
        muted = m.optBoolean("muted")
        finding = m.optBoolean("finding")
        Link.changed()
    }

    fun onMedia(m: JSONObject) {
        val title = m.optString("title")
        nowPlaying = if (title.isEmpty()) null else {
            // Album art only comes with a new track; keep the previous one otherwise.
            val art = m.optString("art").takeIf { it.isNotEmpty() }
                ?.let { Crypto.b64d(it) }?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                ?: nowPlaying?.takeIf { it.title == title }?.art
            MacTrack(title, m.optString("artist"), m.optString("app", "Mac"), m.optBoolean("playing"), art)
        }
        Link.changed()
    }

    /** Detailed player state (Spotify / YouTube) for the player screen; it has its own listeners so Home isn't redrawn each second. */
    @Volatile var deck: JSONObject? = null
        private set
    val deckListeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    fun onDeck(m: JSONObject) {
        deck = m
        deckListeners.forEach { it() }
    }

    fun onShortcuts(m: JSONObject) {
        val arr = m.getJSONArray("names")
        shortcuts = List(arr.length()) { arr.getString(it) }
        Link.changed()
    }

    fun command(cmd: String, vararg extra: Pair<String, Any?>) = Link.send("mac_cmd", "cmd" to cmd, *extra)
}
