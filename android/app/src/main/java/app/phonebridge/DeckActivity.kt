package app.phonebridge

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import org.json.JSONObject

/** Full controls for what the Mac is playing: Spotify (seek, shuffle, repeat) or YouTube (±10 s, speed, quality, subtitles, seek). */
class DeckActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var body: LinearLayout
    private var shownKind: String? = null
    private var shownError: String? = null

    private val bg = 0xFF0E1016.toInt()
    private val panel = 0xFF1A1D27.toInt()
    private val chip = 0xFF262A38.toInt()
    private val text = 0xFFF2F3F7.toInt()
    private val muted = 0xFF9097A8.toInt()
    private val brand = 0xFF5C6BC0.toInt()
    private val green = 0xFF4CD08A.toInt()

    // Views that get updated by each state
    private var titleView: TextView? = null
    private var subtitleView: TextView? = null
    private var art: ImageView? = null
    private var seek: SeekBar? = null
    private var posView: TextView? = null
    private var durView: TextView? = null
    private var playBtn: TextView? = null
    private var shuffleBtn: TextView? = null
    private var repeatBtn: TextView? = null
    private var speedBtn: TextView? = null
    private var qualityBtn: TextView? = null
    private var subsBtn: TextView? = null
    private var dragging = false
    private var holdUntil = 0L
    private var last: JSONObject? = null

    private val listener: () -> Unit = { main.post { MacRemote.deck?.let { show(it) } } }
    private val keepAlive = object : Runnable {
        override fun run() {
            MacRemote.command("deck_on")
            main.postDelayed(this, 10_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(20))
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(dp(20), bars.top + dp(10), dp(20), bars.bottom + dp(20))
                insets
            }
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(bg); isFillViewport = true; addView(body) })
        body.addView(label("Connecting to your Mac…", 15f, muted))
    }

    override fun onResume() {
        super.onResume()
        MacRemote.deckListeners.add(listener)
        keepAlive.run()
        MacRemote.deck?.let { show(it) }
    }

    override fun onPause() {
        super.onPause()
        MacRemote.deckListeners.remove(listener)
        main.removeCallbacks(keepAlive)
        MacRemote.command("deck_off")
    }

    // ---- State → screen

    private fun show(s: JSONObject) {
        last = s
        val kind = s.optString("kind")
        val error = s.optString("error").takeIf { it.isNotEmpty() }
        if (kind != shownKind || error != shownError) {
            shownKind = kind
            shownError = error
            build(kind, error, s)
        }
        update(s)
    }

    private fun build(kind: String, error: String?, s: JSONObject) {
        body.removeAllViews()
        titleView = null; seek = null; playBtn = null; shuffleBtn = null; repeatBtn = null
        speedBtn = null; qualityBtn = null; subsBtn = null
        if (error != null || kind == "none") {
            body.addView(label(
                when (error) {
                    "perm" -> "Allow PhoneBridge on your Mac"
                    "js_off" -> "One setting to turn on"
                    else -> if (error != null) "Couldn't reach the player" else "Nothing to control yet"
                }, 22f, text, bold = true))
            body.addView(label(
                when (error) {
                    "perm" -> "On the Mac: System Settings → Privacy & Security → Automation → PhoneBridge → turn on " +
                        (if (kind == "spotify") "Spotify" else "your browser") + "."
                    "js_off" -> if (s.optString("browser") == "Safari")
                        "In Safari on the Mac: Develop menu → Allow JavaScript from Apple Events. (Safari Settings → Advanced → Show Develop menu, if you don't see it.)"
                    else "In ${s.optString("browser", "your browser")} on the Mac: View → Developer → Allow JavaScript from Apple Events."
                    else -> "Try again in a moment."
                }, 15f, muted).apply { setPadding(0, dp(10), 0, 0) })
            return
        }
        // Header: art + title
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val a = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(chip, 14)
                clipToOutline = true
                layoutParams = LinearLayout.LayoutParams(dp(84), dp(84))
            }
            art = a
            addView(a)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, 0, 0)
                titleView = label("", 19f, text, bold = true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
                subtitleView = label("", 14f, muted)
                addView(titleView); addView(subtitleView)
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        // Slider + times
        val sb = SeekBar(this).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) posView?.text = fmt(p) }
                override fun onStartTrackingTouch(b: SeekBar) { dragging = true }
                override fun onStopTrackingTouch(b: SeekBar) {
                    dragging = false
                    holdUntil = System.currentTimeMillis() + 1500
                    MacRemote.command("deck_do", "action" to "seek", "value" to b.progress)
                }
            })
        }
        seek = sb
        body.addView(sb, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })
        body.addView(LinearLayout(this).apply {
            posView = label("0:00", 12f, muted)
            durView = label("0:00", 12f, muted).apply { gravity = Gravity.END }
            addView(posView, LinearLayout.LayoutParams(0, -2, 1f))
            addView(durView, LinearLayout.LayoutParams(0, -2, 1f))
        })
        if (kind == "spotify") buildSpotify() else buildYouTube()
    }

    private fun buildSpotify() {
        shuffleBtn = tile("🔀", "Shuffle") { MacRemote.command("deck_do", "action" to "shuffle", "value" to !(last?.optBoolean("shuffle") ?: false)) }
        repeatBtn = tile("🔁", "Repeat") { MacRemote.command("deck_do", "action" to "repeat", "value" to !(last?.optBoolean("repeat") ?: false)) }
        playBtn = tile("⏯", "Play") { MacRemote.command("deck_do", "action" to "toggle") }
        body.addView(row(
            shuffleBtn!!,
            tile("⏮", "Previous") { MacRemote.command("prev") },
            playBtn!!,
            tile("⏭", "Next") { MacRemote.command("next") },
            repeatBtn!!,
        ))
    }

    private fun buildYouTube() {
        playBtn = tile("⏯", "Play") { MacRemote.command("deck_do", "action" to "toggle") }
        body.addView(row(
            tile("⏪", "−10 s") { MacRemote.command("deck_do", "action" to "skip", "value" to -10) },
            playBtn!!,
            tile("⏩", "+10 s") { MacRemote.command("deck_do", "action" to "skip", "value" to 10) },
        ))
        speedBtn = tile("⚡", "Speed") { pickSpeed() }
        qualityBtn = tile("🎞", "Quality") { pickQuality() }
        subsBtn = tile("💬", "Subtitles") { MacRemote.command("deck_do", "action" to "subs") }
        body.addView(row(speedBtn!!, qualityBtn!!, subsBtn!!))
    }

    private fun update(s: JSONObject) {
        if (titleView == null) return
        val kind = s.optString("kind")
        val np = MacRemote.nowPlaying
        titleView?.text = if (kind == "youtube") s.optString("title") else np?.title ?: ""
        subtitleView?.text = if (kind == "youtube") "YouTube" else np?.artist ?: ""
        art?.let { v ->
            if (np?.art != null) v.setImageBitmap(np.art) else v.setImageDrawable(null)
        }
        val dur = s.optDouble("dur", 0.0).toInt()
        val pos = s.optDouble("pos", 0.0).toInt()
        seek?.max = dur.coerceAtLeast(1)
        durView?.text = fmt(dur)
        if (!dragging && System.currentTimeMillis() > holdUntil) {
            seek?.progress = pos
            posView?.text = fmt(pos)
        }
        playBtn?.let { setTile(it, if (s.optBoolean("playing")) "⏸" else "▶️", if (s.optBoolean("playing")) "Pause" else "Play") }
        shuffleBtn?.let { mark(it, s.optBoolean("shuffle"), if (s.optBoolean("shuffle")) "Shuffle on" else "Shuffle off") }
        repeatBtn?.let { mark(it, s.optBoolean("repeat"), if (s.optBoolean("repeat")) "Repeat on" else "Repeat off") }
        speedBtn?.let { setTile(it, "⚡", "${trimNum(s.optDouble("speed", 1.0))}×") }
        qualityBtn?.let { setTile(it, "🎞", qualityName(s.optString("quality"))) }
        subsBtn?.let {
            if (s.optBoolean("hasSubs")) mark(it, s.optBoolean("subs"), if (s.optBoolean("subs")) "Subtitles on" else "Subtitles off")
            else { mark(it, false, "No subtitles"); it.alpha = 0.4f }
        }
    }

    private fun pickSpeed() {
        val speeds = doubleArrayOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0)
        AlertDialog.Builder(this).setTitle("Speed")
            .setItems(speeds.map { if (it == 1.0) "Normal" else "${trimNum(it)}×" }.toTypedArray()) { _, i ->
                MacRemote.command("deck_do", "action" to "speed", "value" to speeds[i])
            }.show()
    }

    private fun pickQuality() {
        val arr = last?.optJSONArray("qualities") ?: return
        val levels = List(arr.length()) { arr.getString(it) }
        if (levels.isEmpty()) return
        AlertDialog.Builder(this).setTitle("Quality")
            .setItems(levels.map { qualityName(it) }.toTypedArray()) { _, i ->
                MacRemote.command("deck_do", "action" to "quality", "value" to levels[i])
            }.show()
    }

    // ---- Small view helpers

    private fun qualityName(q: String) = when (q) {
        "highres" -> "4320p"; "hd2880" -> "2880p"; "hd2160" -> "2160p (4K)"; "hd1440" -> "1440p"; "hd1080" -> "1080p"
        "hd720" -> "720p"; "large" -> "480p"; "medium" -> "360p"; "small" -> "240p"; "tiny" -> "144p"; "auto", "" -> "Auto"; else -> q
    }

    private fun trimNum(d: Double) = if (d == d.toInt().toDouble()) d.toInt().toString() else d.toString()
    private fun fmt(sec: Int) = "%d:%02d".format(sec / 60, sec % 60)

    private fun row(vararg tiles: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) }
        tiles.forEachIndexed { i, t ->
            addView(t, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(10) })
        }
    }

    private fun tile(icon: String, name: String, onClick: () -> Unit) = TextView(this).apply {
        gravity = Gravity.CENTER
        textSize = 13f
        setTextColor(muted)
        background = rounded(chip, 18)
        setPadding(dp(4), dp(14), dp(4), dp(14))
        setOnClickListener { onClick() }
        setTile(this, icon, name)
    }

    private fun setTile(v: TextView, icon: String, name: String) {
        v.text = "$icon\n$name"
        v.alpha = 1f
    }

    private fun mark(v: TextView, on: Boolean, name: String) {
        v.alpha = 1f
        v.background = rounded(if (on) brand else chip, 18)
        v.setTextColor(if (on) 0xFFFFFFFF.toInt() else muted)
        val icon = v.text.toString().substringBefore('\n')
        v.text = "$icon\n$name"
    }

    private fun label(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = t
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(color)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
