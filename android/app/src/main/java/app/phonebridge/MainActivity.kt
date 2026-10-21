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
    private val refresh = { render() }

    private class Palette(dark: Boolean) {
        val bg = if (dark) 0xFF0E1016.toInt() else 0xFFF4F5FA.toInt()
        val card = if (dark) 0xFF1A1D27.toInt() else 0xFFFFFFFF.toInt()
        val text = if (dark) 0xFFF2F3F7.toInt() else 0xFF14161F.toInt()
        val muted = if (dark) 0xFF9097A8.toInt() else 0xFF667085.toInt()
        val line = if (dark) 0xFF2A2E3B.toInt() else 0xFFE6E8F0.toInt()
        val brand = 0xFF5C6BC0.toInt()
        val brandDark = 0xFF283593.toInt()
        val green = 0xFF22C55E.toInt()
        val orange = 0xFFF59E0B.toInt()
        val red = 0xFFEF4444.toInt()
        val chip = if (dark) 0xFF262A38.toInt() else 0xFFEEF0FA.toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        LinkService.start(this)
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        c = Palette(dark)
        if (!dark) {
            val light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(light, light)
        }

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(24))
        }
        val k = Colors.of(this)
        homeScroll = ScrollView(this).apply { setBackgroundColor(c.bg); addView(content) }
        galleryView = GuardGalleryView(this, k)
        galleryScroll = ScrollView(this).apply { setBackgroundColor(c.bg); addView(galleryView) }
        codeView = GuardCodeView(this, k).apply { setBackgroundColor(c.bg) }
        notesView = NotesView(this, k).apply { setBackgroundColor(c.bg) }
        body = FrameLayout(this)
        bottomBar = buildBottomBar()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c.bg)
            addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(bottomBar, LinearLayout.LayoutParams(-1, -2))
            // Drawing edge to edge (Android 15+): leave room for the status bar, the dock above the navigation bar, and the keyboard.
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
                v.setPadding(0, bars.top, 0, if (ime > 0) ime else 0)
                bottomBar.setPadding(0, 0, 0, bars.bottom)
                bottomBar.visibility = if (ime > 0) View.GONE else View.VISIBLE
                insets
            }
        }
        setContentView(root)
        showTab(Tab.HOME)
        openRequestedTab(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openRequestedTab(intent)
    }

    /** The guard alert's "Code" button opens the app straight on the code. */
    private fun openRequestedTab(i: Intent?) {
        if (i?.getStringExtra("tab") == "code") showTab(Tab.CODE)
    }

    // MARK: Bottom dock

    private enum class Tab { HOME, GUARD, CODE, NOTES }

    private var tab = Tab.HOME
    private lateinit var body: FrameLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var homeScroll: ScrollView
    private lateinit var galleryScroll: ScrollView
    private lateinit var galleryView: GuardGalleryView
    private lateinit var codeView: GuardCodeView
    private lateinit var notesView: NotesView
    private val tabButtons = HashMap<Tab, Pair<ImageView, TextView>>()

    private fun buildBottomBar(): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            Triple(Tab.HOME, "Home", R.drawable.ic_tab_home), Triple(Tab.GUARD, "Guard", R.drawable.ic_tab_guard),
            Triple(Tab.CODE, "Code", R.drawable.ic_tab_key), Triple(Tab.NOTES, "Notes", R.drawable.ic_tab_note),
        ).forEach { (t, title, icon) ->
            val img = ImageView(this).apply { setImageResource(icon) }
            val txt = TextView(this).apply { text = title; textSize = 11f; gravity = Gravity.CENTER }
            tabButtons[t] = img to txt
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = rippleFor()
                isClickable = true
                setOnClickListener { showTab(t) }
                addView(img, LinearLayout.LayoutParams(dp(24), dp(24)))
                addView(txt, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
            }, LinearLayout.LayoutParams(0, dp(62), 1f))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c.card)
            addView(View(this@MainActivity).apply { setBackgroundColor(c.line) }, LinearLayout.LayoutParams(-1, dp(1)))
            addView(row)
        }
    }

    private fun showTab(t: Tab) {
        codeView.stop()
        notesView.stop()
        tab = t
        if (t == Tab.CODE) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) // no screenshots of the code
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        body.removeAllViews()
        body.addView(
            when (t) {
                Tab.HOME -> homeScroll
                Tab.GUARD -> galleryScroll
                Tab.CODE -> codeView
                Tab.NOTES -> notesView
            },
            FrameLayout.LayoutParams(-1, -1),
        )
        when (t) {
            Tab.HOME -> render()
            Tab.GUARD -> galleryView.refresh()
            Tab.CODE -> codeView.start()
            Tab.NOTES -> notesView.start()
        }
        tabButtons.forEach { (key, views) ->
            val color = if (key == t) c.brand else c.muted
            views.first.setColorFilter(color)
            views.second.setTextColor(color)
            views.second.typeface = Typeface.create("sans-serif-medium", if (key == t) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    override fun onResume() {
        super.onResume()
        visible = true
        Link.listeners.add(refresh)
        when (tab) {
            Tab.HOME -> render()
            Tab.GUARD -> galleryView.refresh()
            Tab.CODE -> codeView.start()
            Tab.NOTES -> notesView.start()
        }
    }

    override fun onPause() {
        visible = false
        Link.listeners.remove(refresh)
        codeView.stop()
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
        if (tab != Tab.HOME) return // other tabs update themselves
        content.removeAllViews()
        content.addView(header())
        content.addView(statusCard())
        Pairing.pending?.let { content.addView(pairingCard(it)) }
        if (Link.isConnected) content.addView(macCard())
        if (Link.isConnected) content.addView(automationsCard())
        Prefs.get(this).getString("lastError", null)?.let { content.addView(errorCard(it)) }
        content.addView(setupCard())
        content.addView(actionsCard())
    }

    private fun header() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(12), 0, dp(18))
        addView(ImageView(context).apply {
            setImageDrawable(packageManager.getApplicationIcon(packageName))
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
            addView(label("PhoneBridge", 24f, c.text, bold = true))
            addView(label("Your phone, on your Mac", 14f, c.muted))
        })
    }

    private fun statusCard(): View {
        val connected = Link.isConnected
        val card = card(
            if (connected) gradient(c.brand, c.brandDark) else rounded(c.card),
        )
        val dotColor = if (connected) c.green else c.orange
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(View(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(dotColor) }
                layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(10) }
            })
            addView(label(if (connected) "Connected" else "Not connected", 13f,
                if (connected) 0xCCFFFFFF.toInt() else c.muted, bold = true))
        })
        val title = if (connected) Link.status.removePrefix("Connected to ") else "Waiting for your Mac"
        card.addView(label(title, 22f, if (connected) 0xFFFFFFFF.toInt() else c.text, bold = true).apply {
            setPadding(0, dp(8), 0, dp(4))
        })
        card.addView(label(
            if (connected) "Notifications, calls, clipboard and files are synced."
            else "Open PhoneBridge on your Mac. Both devices need to be on the same Wi-Fi, or the Mac on this phone's hotspot.",
            14f, if (connected) 0xDDFFFFFF.toInt() else c.muted,
        ))
        return card
    }

    private fun macCard(): View {
        val card = card(rounded(c.card))
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("Your Mac", 18f, c.text, bold = true), weighted())
            MacRemote.battery?.let { addView(label("🔋 $it%${if (MacRemote.charging) " ⚡" else ""}", 13f, c.muted)) }
        })

        card.addView(tileRow(
            Triple("🔒", "Lock") { MacRemote.command("lock"); toast("Mac locked") },
            Triple("🌙", "Sleep") { MacRemote.command("sleep") },
            if (MacRemote.finding) Triple("⏹", "Stop sound") { MacRemote.command("find_stop") }
            else Triple("📍", "Find Mac") { MacRemote.command("find"); toast("Playing a sound on your Mac — tap again to stop") },
        ))
        MacRemote.nowPlaying?.let { card.addView(macNowPlaying(it)) }
        val playing = MacRemote.nowPlaying?.playing
        card.addView(tileRow(
            Triple("⏮", "Previous") { MacRemote.command("prev") },
            when (playing) {
                true -> Triple("⏸", "Pause") { MacRemote.command("play_pause") }
                false -> Triple("▶️", "Play") { MacRemote.command("play_pause") }
                null -> Triple("⏯", "Play/Pause") { MacRemote.command("play_pause") }
            },
            Triple("⏭", "Next") { MacRemote.command("next") },
        ))
        card.addView(tileRow(
            if (MicStreamer.running) Triple("🔴", "Stop mic") { MicStreamer.stop(); render() }
            else Triple("🎙", "Mic to Mac") { startMic() },
            Triple("📄", "Scan to Mac") { startScan() },
            Triple("⌨️", if (TypingService.instance != null) "Type: on" else "Type from Mac") { typeFromMac() },
        ))
        card.addView(tileRow(
            Triple("🖱", "Trackpad & keys") { startActivity(Intent(this, RemoteActivity::class.java)) },
            Triple("🌐", "Mac's tab") { MacRemote.command("tab_request") },
            Triple("📋", "Clipboard") {
                val ok = Clip.sendCurrent(this)
                toast(if (ok) "Sent to your Mac" else "Clipboard is empty")
            },
        ))
        val vol = MacRemote.volume?.let { if (MacRemote.muted) "Muted" else "$it%" } ?: "Volume"
        card.addView(tileRow(
            Triple("🔉", "Vol −") { MacRemote.command("vol_down") },
            Triple(if (MacRemote.muted) "🔇" else "🔈", vol) { MacRemote.command("mute") },
            Triple("🔊", "Vol +") { MacRemote.command("vol_up") },
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
        if (TypingService.instance == null) {
            toast("Turn on PhoneBridge under Installed apps / Downloaded apps")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } else {
            toast("On your Mac: PhoneBridge → Type on phone. Tap a text box here first.")
        }
    }

    private fun tileRow(vararg tiles: Triple<String, String, () -> Unit>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
        tiles.forEachIndexed { i, (icon, title, action) ->
            if (i > 0) addView(space(dp(10)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = rounded(c.chip, radius = 16)
                setPadding(0, dp(12), 0, dp(12))
                isClickable = true
                setOnClickListener { action() }
                addView(label(icon, 20f, c.text).apply { gravity = Gravity.CENTER })
                addView(label(title, 12f, c.muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) })
            }, weighted())
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
        val smsOk = granted(smsPerms)
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
        card.addView(setupRow("SMS", "Messages on your Mac, auto-copy login codes", smsOk) {
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
            if (AutoReply.on(this)) "On — texts back when you miss a call" else "Off — text “I'm busy” when you miss a call") {
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
        if (stroke != null) setStroke(dp(2), stroke)
    }

    private fun gradient(from: Int, to: Int) =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(from, to)).apply { cornerRadius = dp(22).toFloat() }

    private fun pill(text: String, filled: Boolean, compact: Boolean = false, onClick: () -> Unit) =
        label(text, if (compact) 14f else 15f, if (filled) 0xFFFFFFFF.toInt() else c.text, bold = true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(100).toFloat()
                if (filled) setColor(c.brand) else { setColor(0); setStroke(dp(1), c.line) }
            }
            val v = if (compact) 8 else 12
            setPadding(dp(18), dp(v), dp(18), dp(v))
            isClickable = true
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

    private fun rippleFor(): Drawable? {
        val attrs = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        return attrs.getDrawable(0).also { attrs.recycle() }
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
