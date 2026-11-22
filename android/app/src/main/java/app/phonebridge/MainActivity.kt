package app.phonebridge

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Home screen: connection status, pairing, setup checklist and quick actions. Built in code (no UI libraries). */
class MainActivity : Activity() {
    private val callPerms = arrayOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CALL_PHONE,
    )
    private val smsPerms = arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
    private val photoPerms = arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    private val nearbyPerms = arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE)
    private val locationPerms = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    private val notifPerms = arrayOf(Manifest.permission.POST_NOTIFICATIONS)

    private lateinit var content: LinearLayout
    private lateinit var c: Palette
    /** The theme this screen was built with; if the Mac changed it while we were in the background, rebuild on return. */
    private var shownTheme = ""
    private val refresh = { render() }

    private class Palette(t: AppTheme) {
        val bg = t.bg
        val card = t.card
        val text = t.text
        val muted = t.muted
        val line = t.line
        val brand = t.accent
        val brandDark = t.accent2
        val onBrand = t.onAccent
        val green = 0xFF30D158.toInt()
        val orange = 0xFFFF9F0A.toInt()
        val red = 0xFFFF453A.toInt()
        val chip = t.chip
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        LinkService.start(this)
        val theme = ThemeStore.get(this)
        shownTheme = theme.id
        c = Palette(theme)
        // Every theme is dark: light status and navigation bar icons off.
        window.insetsController?.setSystemBarsAppearance(0,
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(c.bg))

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(24))
        }
        val k = Colors.of(this)
        homeScroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        settingsContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(24))
        }
        settingsScroll = ScrollView(this).apply { addView(settingsContent) }
        notesView = NotesView(this, k)
        body = FrameLayout(this)
        bottomBar = buildBottomBar()
        val root = FrameLayout(this).apply {
            setBackgroundColor(c.bg)
            addView(body, FrameLayout.LayoutParams(-1, -1))
            addView(bottomBar, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
            // Drawing edge to edge (Android 15+): leave room for the status bar, the floating dock above the navigation bar, and the keyboard.
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
                v.setPadding(0, bars.top, 0, if (ime > 0) ime else 0)
                body.setPadding(0, 0, 0, if (ime > 0) 0 else dp(84) + bars.bottom)
                bottomBar.setPadding(0, 0, 0, bars.bottom + dp(12))
                bottomBar.visibility = if (ime > 0) View.GONE else View.VISIBLE
                insets
            }
        }
        setContentView(root)
        if (ThemeStore.burstPending) {
            ThemeStore.burstPending = false
            // no landing animation while still searching; it plays once a phone is connected
            if (Link.isConnected) root.addView(BurstView(this, theme), FrameLayout.LayoutParams(-1, -1))
        }
        val fx = FxView(this, theme)
        fxView = fx
        Fx.view = fx
        root.addView(fx, FrameLayout.LayoutParams(-1, -1))
        showTab(Tab.HOME)
        // the theme's opening sequence, unless a freshly picked theme is already playing its own
        if (Link.isConnected) root.post { Fx.openOnce() }   // never while still searching
    }

    override fun onDestroy() {
        if (Fx.view === fxView) Fx.view = null
        super.onDestroy()
    }

    // MARK: Bottom dock

    private enum class Tab { HOME, NOTES, SETTINGS }

    private var tab = Tab.HOME
    private lateinit var body: FrameLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var homeScroll: ScrollView
    private lateinit var settingsContent: LinearLayout
    private lateinit var settingsScroll: ScrollView
    private var fxView: FxView? = null
    private var wasConnected = false
    private lateinit var notesView: NotesView
    private val tabButtons = HashMap<Tab, Triple<View, ImageView, TextView>>()

    /** A floating, rounded dock: icons only, and the selected one grows into a pill with its name. */
    private fun buildBottomBar(): LinearLayout {
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(30).toFloat(); setColor(c.card); setStroke(dp(1), c.line) }
            setPadding(dp(6), dp(6), dp(6), dp(6))
            elevation = dp(10).toFloat()
        }
        pill.addView(GlyphView(this, ThemeStore.get(this), c.brand).apply { setOnClickListener { showTab(Tab.SETTINGS) } },
            LinearLayout.LayoutParams(dp(26), dp(26)).apply { leftMargin = dp(10); rightMargin = dp(8) })
        listOf(
            Triple(Tab.HOME, "Home", R.drawable.ic_tab_home),
            Triple(Tab.NOTES, "Notes", R.drawable.ic_tab_note),
            Triple(Tab.SETTINGS, "Settings", R.drawable.ic_tab_settings),
        ).forEach { (t, title, icon) ->
            val img = ImageView(this).apply { setImageResource(icon) }
            val txt = TextView(this).apply { text = title; textSize = 13f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(10), dp(14), dp(10))
                isClickable = true
                Fx.press(this)
                setOnClickListener { showTab(t) }
                addView(img, LinearLayout.LayoutParams(dp(22), dp(22)))
                addView(txt, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(7) })
            }
            tabButtons[t] = Triple(item, img, txt)
            pill.addView(item)
        }
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(pill)
        }
    }

    private fun showTab(t: Tab) {
        notesView.stop()
        val previous = tab
        tab = t
        body.removeAllViews()
        val view: View = when (t) {
            Tab.HOME -> homeScroll
            Tab.NOTES -> notesView
            Tab.SETTINGS -> settingsScroll
        }
        body.addView(view, FrameLayout.LayoutParams(-1, -1))
        when (t) {
            Tab.HOME -> render()
            Tab.NOTES -> notesView.start()
            Tab.SETTINGS -> renderSettings()
        }
        if (previous != t) {
            // slide in from the side the tab sits on, and let the theme play its flourish across the screen
            view.alpha = 0f
            view.translationX = (if (t.ordinal > previous.ordinal) 1 else -1) * dp(36).toFloat()
            view.animate().alpha(1f).translationX(0f).setDuration(300).setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
            Fx.transition()
        }
        tabButtons.forEach { (key, views) ->
            val on = key == t
            views.first.background = if (on) rounded(c.brand, radius = 22) else null
            views.second.setColorFilter(if (on) c.onBrand else c.muted)
            views.third.setTextColor(c.onBrand)
            views.third.visibility = if (on) View.VISIBLE else View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        if (ThemeStore.get(this).id != shownTheme) { recreate(); return }
        visible = true
        ThemeStore.onRemoteChange = { recreate() }
        ThemeStore.push(this)   // keep the Mac on the same theme
        Link.listeners.add(refresh)
        Updater.clearUpdatedNotice(this)
        Updater.check(this)
        when (tab) {
            Tab.HOME -> render()
            Tab.NOTES -> notesView.start()
            Tab.SETTINGS -> renderSettings()
        }
    }

    override fun onPause() {
        visible = false
        ThemeStore.onRemoteChange = null
        Link.listeners.remove(refresh)
        notesView.stop()
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        Presence.start(this)
        ScreenshotWatch.start(this)
        if (requestCode == 6 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startMic()
        // Android asks for "all the time" location separately, after the normal location permission.
        if (requestCode == 7 && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 8)
        }
        render()
    }

    companion object {
        /** True while the home screen is on top — then links from the Mac can open directly. */
        @Volatile var visible = false
        private const val SCAN_REQUEST = 42
    }

    // MARK: Screen

    private fun render() {
        if (tab == Tab.SETTINGS) { renderSettings(); return }
        if (tab != Tab.HOME) return // the notes tab updates itself
        val connected = Link.isConnected
        if (connected && !wasConnected && visible) Fx.connect()
        wasConnected = connected
        content.removeAllViews()
        if (!connected) {
            // Not connected: just the full-screen searching scene (plus the pairing code once a Mac is found).
            content.setPadding(0, 0, 0, 0)
            content.addView(searchingView(), LinearLayout.LayoutParams(-1, 0, 1f))
            Pairing.pending?.let { content.addView(pairingCard(it).apply { (layoutParams as LinearLayout.LayoutParams).setMargins(dp(18), 0, dp(18), dp(14)) }) }
            Updater.available?.let { content.addView(updateCard(it).apply { (layoutParams as LinearLayout.LayoutParams).setMargins(dp(18), 0, dp(18), dp(14)) }) }
            return
        }
        content.setPadding(dp(18), dp(12), dp(18), dp(24))
        content.addView(brandHeader())
        Updater.available?.let { content.addView(updateCard(it)) }
        content.addView(heroCard())
        Pairing.pending?.let { content.addView(pairingCard(it)) }
        content.addView(macCard())
    }

    /** Everything that isn't a quick action: theme, automations, setup and permissions, the rest. */
    private fun renderSettings() {
        settingsContent.removeAllViews()
        settingsContent.addView(label("Settings", 30f, c.text).apply { typeface = Typeface.SERIF; setPadding(0, dp(8), 0, dp(14)) })
        settingsContent.addView(sectionTitle("Theme"))
        settingsContent.addView(ThemeSettingsView(this, { recreate() }, showHeader = false).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
        })
        Prefs.get(this).getString("lastError", null)?.let { settingsContent.addView(errorCard(it)) }
        if (Link.isConnected) settingsContent.addView(automationsCard())
        settingsContent.addView(smsCard())
        settingsContent.addView(setupCard())
        settingsContent.addView(actionsCard())
        settingsContent.addView(aboutCard())
    }

    /** "A new version is available": one tap downloads it, installs it and PhoneBridge reopens. */
    private fun updateCard(r: Updater.Release): View {
        val card = card(rounded(c.card, stroke = c.brand))
        card.addView(label("UPDATE AVAILABLE", 11f, c.brand, bold = true).apply { letterSpacing = 0.12f })
        card.addView(label("PhoneBridge ${r.version} is ready", 18f, c.text, bold = true).apply { setPadding(0, dp(4), 0, dp(2)) })
        val notes = Updater.shortNotes(r.notes)
        card.addView(label(if (notes.isNotEmpty()) notes else "You have ${Updater.current(this)}. Update your app to get the latest fixes and features.", 13f, c.muted).apply {
            maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val busy = Updater.progress
        card.addView(pill(busy ?: "Update now", filled = busy == null) { Updater.install(this) }.apply {
            isEnabled = busy == null
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
        })
        return card
    }

    private fun aboutCard(): View {
        val card = card(rounded(c.card))
        card.addView(label("About", 18f, c.text, bold = true).apply { setPadding(0, 0, 0, dp(6)) })
        val r = Updater.available
        card.addView(actionRow(
            if (r != null) "Update to ${r.version}" else "Check for updates",
            "PhoneBridge ${Updater.current(this)}" + if (r != null) " · a new version is ready" else "",
        ) {
            if (Updater.available != null) { Updater.install(this); return@actionRow }
            toast("Checking for updates…")
            Updater.check(this, force = true) { found ->
                runOnUiThread {
                    toast(if (found) "PhoneBridge ${Updater.available?.version} is available" else "You're on the latest version")
                    renderSettings()
                }
            }
        })
        return card
    }

    private fun sectionTitle(text: String) = label(text.uppercase(), 11f, c.muted, bold = true).apply { setPadding(dp(4), dp(6), 0, dp(10)); letterSpacing = 0.12f }

    /** Searching screen: the whole screen. Serif headline, the animated orb, a help line, and any moving scene behind it. */
    private fun searchingView(): View = FrameLayout(this).apply {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(28), dp(18), dp(10))
            val found = Pairing.pending != null
            addView(label(if (found) "Found\nyour Mac" else "Looking for\nyour Mac", 34f, c.text).apply {
                gravity = Gravity.CENTER
                typeface = Typeface.SERIF
                setLineSpacing(0f, 1.05f)
            })
            addView(OrbView(context, ThemeStore.get(context)), LinearLayout.LayoutParams(-1, 0, 1f))
            addView(label(
                if (found) "Check that the 6-digit code matches the one on your Mac, then tap Pair on both."
                else "Open PhoneBridge on your Mac. Both need to be on the same Wi-Fi.", 14f, c.muted,
            ).apply { gravity = Gravity.CENTER; setPadding(dp(24), 0, dp(24), dp(18)) })
            addView(label("Having trouble finding your Mac?", 13f, c.muted).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(10)) })
            addView(pill("Wi-Fi settings", filled = false) { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
                .apply { (layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL })
        }, FrameLayout.LayoutParams(-1, -1))
    }

    /** Centred logo, name and connection line. */
    private fun brandHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(10), 0, dp(18))
        addView(FrameLayout(context).apply {
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(c.card); setStroke(dp(1), c.line) }
            addView(GlyphView(context, ThemeStore.get(context), c.brand), FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(68), dp(68)))
        addView(label("PhoneBridge", 28f, c.text).apply { typeface = Typeface.SERIF; gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(2)) })
        addView(label(if (Link.isConnected) "Connected" else "Not connected", 13f, if (Link.isConnected) c.green else c.muted).apply { gravity = Gravity.CENTER })
    }

    /** The animated scene for the chosen theme, with the Mac's name over it. */
    private fun heroCard(): View = FrameLayout(this).apply {
        background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(c.card) }
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(-1, dp(200)).apply { bottomMargin = dp(16) }
        addView(HeroView(context, ThemeStore.get(context)), FrameLayout.LayoutParams(-1, -1))
        addView(View(context).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0, 0x99000000.toInt())) },
            FrameLayout.LayoutParams(-1, dp(100), Gravity.BOTTOM))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), 0, dp(18), dp(16))
            addView(label(Link.status.removePrefix("Connected to "), 22f, 0xFFFFFFFF.toInt()).apply { typeface = Typeface.SERIF; maxLines = 1 })
            val sub = buildString {
                append("Notifications, calls, clipboard and files are synced")
                MacRemote.battery?.let { append(" · Mac $it%") }
            }
            addView(label(sub, 12f, 0xB3FFFFFF.toInt()))
        }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
    }

    private fun macCard(): View {
        val card = card(rounded(c.card))
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("Your Mac", 18f, c.text, bold = true), weighted())
            MacRemote.battery?.let { addView(label("$it%${if (MacRemote.charging) " · charging" else ""}", 13f, c.muted)) }
        })

        card.addView(tileRow(
            Triple(LineIcon.LOCK, "Lock") { MacRemote.command("lock"); toast("Mac locked") },
            Triple(LineIcon.MOON, "Sleep") { MacRemote.command("sleep") },
            if (MacRemote.finding) Triple(LineIcon.STOP, "Stop sound") { MacRemote.command("find_stop") }
            else Triple(LineIcon.PIN, "Find Mac") { MacRemote.command("find"); toast("Playing a sound on your Mac — tap again to stop") },
        ))
        // Controls for what's in front on the Mac: a video gets ±10 s, Esc and Full screen.
        if (MacRemote.front == "video") {
            card.addView(label("VIDEO ON YOUR MAC", 11f, c.muted, bold = true).apply { letterSpacing = 0.12f; setPadding(dp(2), dp(14), 0, 0) })
            card.addView(tileRow(
                Triple(LineIcon.BACK10, "−10 s") { MacRemote.command("video", "action" to "skip", "value" to -10) },
                Triple(LineIcon.FWD10, "+10 s") { MacRemote.command("video", "action" to "skip", "value" to 10) },
                Triple(LineIcon.ESC, "Esc") { MacRemote.command("video", "action" to "esc") },
            ))
            card.addView(tileRow(
                Triple(LineIcon.FULLSCREEN, "Full screen") { MacRemote.command("video", "action" to "fullscreen") },
                if (MacRemote.nowPlaying?.playing == true) Triple(LineIcon.PAUSE, "Pause") { MacRemote.command("play_pause") }
                else Triple(LineIcon.PLAY, "Play") { MacRemote.command("play_pause") },
                Triple(LineIcon.MOUSE, "Trackpad") { startActivity(Intent(this, RemoteActivity::class.java)) },
            ))
        }
        MacRemote.nowPlaying?.let { card.addView(macNowPlaying(it)) }
        val playing = MacRemote.nowPlaying?.playing
        card.addView(tileRow(
            Triple(LineIcon.PREV, "Previous") { MacRemote.command("prev") },
            if (playing == true) Triple(LineIcon.PAUSE, "Pause") { MacRemote.command("play_pause") }
            else Triple(LineIcon.PLAY, if (playing == false) "Play" else "Play/Pause") { MacRemote.command("play_pause") },
            Triple(LineIcon.NEXT, "Next") { MacRemote.command("next") },
        ))
        card.addView(tileRow(
            if (MicStreamer.running) Triple(LineIcon.STOP, "Stop mic") { MicStreamer.stop(); render() }
            else Triple(LineIcon.MIC, "Mic to Mac") { startMic() },
            Triple(LineIcon.SCAN, "Scan to Mac") { startScan() },
            Triple(LineIcon.KEYBOARD, if (TypingKeyboard.isActive(this)) "Type: on" else "Type from Mac") { typeFromMac() },
        ))
        card.addView(tileRow(
            Triple(LineIcon.MOUSE, "Trackpad") { startActivity(Intent(this, RemoteActivity::class.java)) },
            Triple(LineIcon.GLOBE, "Mac's tab") { MacRemote.command("tab_request") },
            Triple(LineIcon.CLIPBOARD, "Clipboard") {
                val ok = Clip.sendCurrent(this)
                toast(if (ok) "Sent to your Mac" else "Clipboard is empty")
            },
        ))
        val vol = MacRemote.volume?.let { if (MacRemote.muted) "Muted" else "$it%" } ?: "Volume"
        card.addView(tileRow(
            Triple(LineIcon.VOL_DOWN, "Vol −") { MacRemote.command("vol_down") },
            Triple(if (MacRemote.muted) LineIcon.MUTE else LineIcon.VOL_UP, vol) { MacRemote.command("mute") },
            Triple(LineIcon.VOL_UP, "Vol +") { MacRemote.command("vol_up") },
        ))

        // Mac Shortcuts: run any shortcut from the Mac's Shortcuts app
        val names = MacRemote.shortcuts
        if (names == null) {
            card.addView(actionRow("Mac Shortcuts", "Run any shortcut on your Mac") {
                MacRemote.command("shortcuts_request")
                toast("Loading shortcuts…")
            })
        } else {
            card.addView(label("Mac Shortcuts", 15f, c.text, bold = true).apply { setPadding(0, dp(14), 0, dp(4)) })
            if (names.isEmpty()) card.addView(label("No shortcuts on your Mac yet — make some in the Shortcuts app.", 13f, c.muted))
            names.forEach { name ->
                card.addView(actionRow("▶  $name", "Run on Mac") {
                    MacRemote.command("run_shortcut", "name" to name)
                    toast("Running “$name” on Mac")
                })
            }
        }
        return card
    }

    /** "Now playing on your Mac": album art, title, artist · app. */
    private fun macNowPlaying(t: MacTrack) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(c.chip, radius = 16)
        setPadding(dp(10), dp(10), dp(14), dp(10))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        isClickable = true
        setOnClickListener { startActivity(Intent(this@MainActivity, DeckActivity::class.java)) }
        addView(ImageView(context).apply {
            if (t.art != null) setImageBitmap(t.art) else {
                setImageResource(android.R.drawable.ic_media_play)
                setColorFilter(c.muted)
                setPadding(dp(14), dp(14), dp(14), dp(14))
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(c.line, radius = 10)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            addView(label(if (t.playing) "NOW PLAYING ON MAC" else "PAUSED ON MAC", 11f,
                if (t.playing) c.green else c.muted, bold = true))
            addView(label(t.title, 16f, c.text, bold = true).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            })
            addView(label(listOf(t.artist, t.app).filter { it.isNotBlank() }.joinToString(" · "), 13f, c.muted).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }, weighted())
    }

    private fun startMic() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 6)
            return
        }
        if (MicStreamer.start(this)) toast("Phone mic → Mac. Pick “BlackHole 2ch” as the mic in Zoom/Meet.")
        render()
    }

    private fun startScan() {
        val options = com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(10)
            .setResultFormats(com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        com.google.mlkit.vision.documentscanner.GmsDocumentScanning.getClient(options).getStartScanIntent(this)
            .addOnSuccessListener { sender -> startIntentSenderForResult(sender, SCAN_REQUEST, null, 0, 0, 0) }
            .addOnFailureListener { toast("Scanner unavailable: ${it.message}") }
    }

    @Deprecated("Activity result API without AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SCAN_REQUEST || resultCode != RESULT_OK) return
        val pages = com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult.fromActivityResultIntent(data)?.pages.orEmpty()
        if (pages.isEmpty()) return
        toast("Sending ${pages.size} page${if (pages.size > 1) "s" else ""} to your Mac…")
        kotlin.concurrent.thread { pages.forEach { Files.send(this, it.imageUri, kind = "scan") { } } }
    }

    private fun typeFromMac() {
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        when {
            !TypingKeyboard.isEnabled(this) -> {
                toast("Switch on “PhoneBridge keyboard”, then come back")
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
            !TypingKeyboard.isActive(this) -> {
                toast("Pick PhoneBridge keyboard, then tap a text box")
                imm.showInputMethodPicker()
            }
            else -> toast("On your Mac: PhoneBridge → Type on phone. Tap a text box first.")
        }
    }

    /** A row of up to three actions: a round line icon above a short label, on a soft tile. */
    private fun tileRow(vararg tiles: Triple<LineIcon, String, () -> Unit>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
        val soft = (c.brand and 0x00FFFFFF) or (0x24 shl 24)
        tiles.forEachIndexed { i, (icon, title, action) ->
            if (i > 0) addView(space(dp(10)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(c.chip); setStroke(dp(1), c.line) }
                setPadding(dp(4), dp(14), dp(4), dp(12))
                isClickable = true
                foreground = rippleFor()
                Fx.press(this)
                setOnClickListener { action() }
                addView(FrameLayout(context).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(soft) }
                    addView(IconView(context, icon, c.brand), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
                }, LinearLayout.LayoutParams(dp(44), dp(44)))
                addView(label(title, 12f, c.text).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0); maxLines = 1 })
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    private fun automationsCard(): View {
        val card = card(rounded(c.card))
        card.addView(label("Automations", 18f, c.text, bold = true))
        card.addView(label("Your phone and Mac acting together", 13f, c.muted).apply { setPadding(0, dp(2), 0, dp(4)) })
        fun rule(key: String, title: String, subtitle: String) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(title, 15f, c.text, bold = true))
                addView(label(subtitle, 13f, c.muted))
            }, weighted())
            addView(android.widget.Switch(context).apply {
                val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
                thumbTintList = android.content.res.ColorStateList(states, intArrayOf(c.onBrand, c.muted))
                trackTintList = android.content.res.ColorStateList(states, intArrayOf(c.brand, c.line))
                isChecked = Automations.enabled(this@MainActivity, key)
                setOnCheckedChangeListener { _, on -> Automations.set(this@MainActivity, key, on) }
            })
        }
        card.addView(rule(Automations.CALL_MUTE, "Calls pause the Mac", "Mac's music pauses and it goes quiet while you're on a call"))
        card.addView(rule(Automations.BATTERY_ALERTS, "Battery alerts", "Low and fully-charged alerts on both devices"))
        card.addView(rule(Automations.SCREENSHOTS, "Screenshots to Mac", "New phone screenshots appear on the Mac instantly"))
        card.addView(label("Lock-when-away is set on the Mac (PhoneBridge window).", 12f, c.muted).apply { setPadding(0, dp(12), 0, 0) })
        return card
    }

    /** "SMS on Mac": one switch for everything that touches your texts. */
    private fun smsCard(): View {
        val card = card(rounded(c.card))
        val on = SmsSwitch.on(this)
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("SMS on Mac", 18f, c.text, bold = true))
                addView(label(
                    if (on) "On — texts, sending from the Mac, login codes and missed-call auto-reply"
                    else "Off — PhoneBridge doesn't read or send your texts",
                    13f, c.muted,
                ).apply { setPadding(0, dp(2), dp(12), 0) })
                if (!on) addView(label("Notifications from your messaging app are chosen separately, under Notifications on Mac.",
                    12f, c.muted).apply { setPadding(0, dp(6), dp(12), 0) })
            }, weighted())
            addView(android.widget.Switch(context).apply {
                val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
                thumbTintList = android.content.res.ColorStateList(states, intArrayOf(c.onBrand, c.muted))
                trackTintList = android.content.res.ColorStateList(states, intArrayOf(c.brand, c.line))
                isChecked = on
                setOnCheckedChangeListener { _, checked ->
                    SmsSwitch.set(this@MainActivity, checked)
                    if (checked && !granted(smsPerms)) requestPermissions(smsPerms, 3)
                    if (!checked && granted(smsPerms)) offerSmsRevoke()
                    render()
                }
            })
        })
        return card
    }

    /** Switching SMS off stops PhoneBridge using it; this also offers to take the permission away in Android. */
    private fun offerSmsRevoke() {
        android.app.AlertDialog.Builder(this)
            .setTitle("SMS is off")
            .setMessage("PhoneBridge no longer reads or sends your texts. To also remove its SMS permission, open App info → Permissions → SMS → Don't allow.")
            .setPositiveButton("Open App info") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun pairingCard(p: Pairing.Pending): View {
        val card = card(rounded(c.card, stroke = c.brand))
        card.addView(label("Pair with ${p.macName}?", 18f, c.text, bold = true))
        card.addView(label("Check your Mac shows the same code", 14f, c.muted).apply { setPadding(0, dp(4), 0, dp(12)) })
        card.addView(label(p.code.chunked(3).joinToString(" "), 40f, c.text, bold = true).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.15f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(16))
        })
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(pill("Reject", filled = false) { Pairing.reject(); render() }, weighted())
            addView(space(dp(10)))
            addView(pill("Pair", filled = true) { Pairing.confirm(); render() }, weighted())
        })
        return card
    }

    private fun errorCard(err: String): View {
        val card = card(rounded(c.card, stroke = c.red))
        card.addView(label("Something went wrong", 16f, c.red, bold = true))
        card.addView(label("Screenshot this and send it over:", 13f, c.muted).apply { setPadding(0, dp(2), 0, dp(8)) })
        card.addView(label(err, 11f, c.text).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        })
        card.addView(pill("Dismiss", filled = false) {
            Prefs.get(this).edit().remove("lastError").apply()
            render()
        }.apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(12) })
        return card
    }

    private fun setupCard(): View {
        val callsOk = granted(callPerms)
        val smsWanted = SmsSwitch.on(this)
        val smsOk = !smsWanted || granted(smsPerms) // switched off on purpose counts as done
        val notifsOk = granted(notifPerms) && getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(this, NotifListener::class.java))
        val bgOk = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        val photosOk = granted(photoPerms)
        val nearbyOk = granted(nearbyPerms)
        val checks = listOf(callsOk, smsOk, notifsOk, bgOk, photosOk, nearbyOk)
        val done = checks.count { it }
        val total = checks.size

        val card = card(rounded(c.card))
        if (done == total) {
            card.addView(label("✓  Setup complete", 16f, c.green, bold = true))
            card.addView(label("For best reliability: Settings → Battery → Background usage limits → Never sleeping apps → add PhoneBridge.",
                13f, c.muted).apply { setPadding(0, dp(6), 0, 0) })
            return card
        }
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("Finish setup", 18f, c.text, bold = true), weighted())
            addView(label("$done of $total", 13f, c.muted))
        })
        card.addView(progress(done / total.toFloat()))

        card.addView(setupRow("Notifications", "Mirror and reply from your Mac", notifsOk) {
            if (!granted(notifPerms)) requestPermissions(notifPerms, 1)
            else startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        card.addView(setupRow("Calls", "See who's calling, answer or decline on the Mac", callsOk) {
            requestPermissions(callPerms, 2)
        })
        if (smsWanted) card.addView(setupRow("SMS", "Messages on your Mac, auto-copy login codes", smsOk) {
            requestPermissions(smsPerms, 3)
        })
        card.addView(setupRow("Photos", "See recent photos on your Mac (choose Allow all)", photosOk) {
            requestPermissions(photoPerms, 4)
        })
        card.addView(setupRow("Nearby devices", "Lock your Mac when you walk away", nearbyOk) {
            requestPermissions(nearbyPerms, 5)
        })
        card.addView(setupRow("Run in background", "Stay connected when the screen is off", bgOk) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        })

        // Sideloaded apps need "Allow restricted settings" before Android lets them have SMS/call log/notification access.
        if (!callsOk || !smsOk || !notifsOk) {
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(c.chip, radius = 14)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
                addView(label("Seeing \"App was denied access\"?", 14f, c.text, bold = true))
                addView(label("Android blocks these for apps not from the Play Store. Tap Fix → ⋮ (top right) → Allow restricted settings, then come back and tap Allow again.",
                    13f, c.muted).apply { setPadding(0, dp(4), 0, dp(10)) })
                addView(pill("Fix", filled = true) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                })
            })
        }
        return card
    }

    private fun actionsCard(): View {
        val card = card(rounded(c.card))
        card.addView(label("Quick actions", 18f, c.text, bold = true).apply { setPadding(0, 0, 0, dp(6)) })
        val notifSummary = if (NotifFilter.forwardAll(this)) "All apps" else "${NotifFilter.selected(this).size} apps selected"
        card.addView(actionRow("Notifications on Mac", "$notifSummary · choose which apps") {
            startActivity(Intent(this, NotifAppsActivity::class.java))
        })
        val locOk = granted(locationPerms) && granted(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        card.addView(actionRow("Find my phone (optional)",
            if (locOk) "Location allowed — press Locate on your Mac" else "Allow location (choose “All the time”) so your Mac can find it") {
            if (!granted(locationPerms)) requestPermissions(locationPerms, 7)
            else requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 8)
        })
        card.addView(actionRow("Missed-call auto-reply",
            when {
                !SmsSwitch.on(this) -> "Paused — needs SMS on Mac (Settings above)"
                AutoReply.on(this) -> "On — texts back when you miss a call"
                else -> "Off — text “I'm busy” when you miss a call"
            }) {
            showAutoReply()
        })
        card.addView(actionRow("Send clipboard to Mac", "Copy something, then tap here") {
            val ok = Link.isConnected && Clip.sendCurrent(this)
            toast(if (ok) "Sent to your Mac" else if (!Link.isConnected) "Not connected to your Mac" else "Clipboard is empty")
        })
        card.addView(actionRow("Add clipboard tile", "One tap from Quick Settings, anywhere") {
            getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(this, ClipTileService::class.java), "Clipboard → Mac",
                Icon.createWithResource(this, R.drawable.ic_stat_bridge), mainExecutor,
            ) { }
        })
        if (!android.provider.MediaStore.canManageMedia(this)) {
            card.addView(actionRow("Delete photos from Mac without asking", "Optional · Allow “Media management”") {
                startActivity(Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:$packageName")))
            })
        }
        card.addView(actionRow("Forget this Mac", "You'll need to pair again", danger = true) {
            Link.forget()
            toast("Forgot your Mac")
            render()
        })
        return card
    }

    // MARK: Building blocks

    private fun setupRow(title: String, subtitle: String, done: Boolean, onAllow: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, 0)
            addView(label(if (done) "✓" else "", 15f, 0xFFFFFFFF.toInt(), bold = true).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    if (done) setColor(c.green) else { setColor(0); setStroke(dp(2), c.line) }
                }
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { rightMargin = dp(14) }
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(title, 16f, c.text, bold = true))
                addView(label(subtitle, 13f, c.muted))
            }, weighted())
            if (!done) addView(pill("Allow", filled = true, compact = true, onClick = onAllow))
        }

    private fun actionRow(title: String, subtitle: String, danger: Boolean = false, onClick: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            isClickable = true
            background = rippleFor()
            Fx.press(this)
            setOnClickListener { onClick() }
            addView(label(title, 16f, if (danger) c.red else c.text, bold = true))
            addView(label(subtitle, 13f, c.muted))
        }

    private fun card(bg: Drawable) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = bg
        setPadding(dp(18), dp(18), dp(18), dp(18))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
    }

    private fun rounded(color: Int, radius: Int = 22, stroke: Int? = null) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(color)
        if (stroke != null) setStroke(dp(2), stroke) else if (color == c.card) setStroke(dp(1), c.line)
    }

    private fun gradient(from: Int, to: Int) =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(from, to)).apply { cornerRadius = dp(22).toFloat() }

    private fun pill(text: String, filled: Boolean, compact: Boolean = false, onClick: () -> Unit) =
        label(text, if (compact) 14f else 15f, if (filled) c.onBrand else c.text, bold = true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(100).toFloat()
                if (filled) setColor(c.brand) else { setColor(0); setStroke(dp(1), c.line) }
            }
            val v = if (compact) 8 else 12
            setPadding(dp(18), dp(v), dp(18), dp(v))
            isClickable = true
            Fx.press(this)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(-2, -2)
        }

    private fun progress(fraction: Float) = LinearLayout(this).apply {
        background = rounded(c.line, radius = 3)
        layoutParams = LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(10) }
        addView(View(context).apply { background = rounded(c.brand, radius = 3) },
            LinearLayout.LayoutParams(0, -1, fraction.coerceAtLeast(0.02f)))
        addView(View(context), LinearLayout.LayoutParams(0, -1, 1f - fraction))
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    /** A press highlight in the theme's colour, clipped to rounded corners (no grey rectangle). */
    private fun rippleFor(radius: Int = 18): Drawable? {
        val mask = GradientDrawable().apply { cornerRadius = dp(radius).toFloat(); setColor(0xFFFFFFFF.toInt()) }
        val tint = (c.brand and 0x00FFFFFF) or (0x40 shl 24)
        return android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(tint), null, mask)
    }

    private fun weighted() = LinearLayout.LayoutParams(0, -2, 1f)
    private fun space(w: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(w, 1) }
    private fun granted(perms: Array<String>) = perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    private fun showAutoReply() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(10), dp(22), 0) }
        val on = android.widget.Switch(this).apply { text = "Reply to missed calls"; isChecked = AutoReply.on(this@MainActivity); textSize = 16f }
        val contacts = android.widget.Switch(this).apply { text = "Only saved contacts"; isChecked = AutoReply.contactsOnly(this@MainActivity); textSize = 15f }
        val declined = android.widget.Switch(this).apply { text = "Also when I decline"; isChecked = AutoReply.alsoDeclined(this@MainActivity); textSize = 15f }
        val message = android.widget.EditText(this).apply {
            setText(AutoReply.text(this@MainActivity))
            hint = "Message"
            minLines = 2
        }
        box.addView(on)
        box.addView(message)
        box.addView(contacts)
        box.addView(declined)
        box.addView(label("{name} becomes the contact's first name. Each number is texted at most once every 4 hours.", 12f, c.muted).apply { setPadding(0, dp(8), 0, 0) })
        android.app.AlertDialog.Builder(this)
            .setTitle("Missed-call auto-reply")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                AutoReply.set(this, on.isChecked, message.text.toString(), contacts.isChecked, declined.isChecked)
                AutoReply.report(this)
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
