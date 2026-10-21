package app.phonebridge

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import kotlin.concurrent.thread

/** Which apps' notifications are forwarded to the Mac. Calls are always forwarded. */
object NotifFilter {
    fun forwardAll(ctx: Context) = Prefs.get(ctx).getBoolean("notifAll", true)
    fun selected(ctx: Context): Set<String> = Prefs.get(ctx).getStringSet("notifApps", emptySet())!!

    fun allows(ctx: Context, pkg: String) = forwardAll(ctx) || pkg in selected(ctx)

    fun setForwardAll(ctx: Context, all: Boolean) = Prefs.get(ctx).edit().putBoolean("notifAll", all).apply()

    fun setSelected(ctx: Context, pkg: String, on: Boolean) {
        val s = selected(ctx).toMutableSet()
        if (on) s.add(pkg) else s.remove(pkg)
        Prefs.get(ctx).edit().putStringSet("notifApps", s).apply()
    }
}

class NotifAppsActivity : Activity() {
    private class App(val pkg: String, val name: String, val icon: android.graphics.drawable.Drawable)

    private lateinit var list: LinearLayout
    private lateinit var allSwitch: Switch
    private var apps: List<App> = emptyList()
    private var query = ""
    private var dark = false

    private val bg get() = if (dark) 0xFF0E1016.toInt() else 0xFFF4F5FA.toInt()
    private val card get() = if (dark) 0xFF1A1D27.toInt() else 0xFFFFFFFF.toInt()
    private val text get() = if (dark) 0xFFF2F3F7.toInt() else 0xFF14161F.toInt()
    private val muted get() = if (dark) 0xFF9097A8.toInt() else 0xFF667085.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(24))
        }
        root.addView(label("Notifications on Mac", 24f, text, bold = true))
        root.addView(label("Choose which apps show their notifications on your Mac. Calls always come through.", 14f, muted)
            .apply { setPadding(0, dp(6), 0, dp(16)) })

        allSwitch = Switch(this).apply {
            text = "All apps"
            textSize = 17f
            setTextColor(this@NotifAppsActivity.text)
            isChecked = NotifFilter.forwardAll(this@NotifAppsActivity)
            setOnCheckedChangeListener { _, on ->
                NotifFilter.setForwardAll(this@NotifAppsActivity, on)
                renderList()
            }
        }
        root.addView(cardOf(allSwitch))

        root.addView(EditText(this).apply {
            hint = "Search apps"
            setTextColor(this@NotifAppsActivity.text)
            setHintTextColor(muted)
            background = rounded(card)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14); bottomMargin = dp(8) }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { query = s.toString(); renderList() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        })

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        list.addView(label("Loading apps…", 14f, muted))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(bg)
            addView(root)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
        })

        thread {
            val pm = packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val loaded = pm.queryIntentActivities(launcher, 0)
                .distinctBy { it.activityInfo.packageName }
                .filter { it.activityInfo.packageName != packageName }
                .map { App(it.activityInfo.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm)) }
                .sortedBy { it.name.lowercase() }
            runOnUiThread { apps = loaded; renderList() }
        }
    }

    private fun renderList() {
        list.removeAllViews()
        val all = NotifFilter.forwardAll(this)
        val selected = NotifFilter.selected(this)
        if (all) {
            list.addView(label("Turn off \"All apps\" to pick individual apps.", 14f, muted).apply { setPadding(dp(4), dp(8), 0, 0) })
            return
        }
        val shown = apps.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
            // Selected apps first, so it's easy to see what's on.
            .sortedByDescending { it.pkg in selected }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(card)
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        shown.forEach { app ->
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                addView(ImageView(context).apply {
                    setImageDrawable(app.icon)
                    layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(14) }
                })
                addView(label(app.name, 16f, text), LinearLayout.LayoutParams(0, -2, 1f))
                addView(Switch(context).apply {
                    isChecked = app.pkg in selected
                    setOnCheckedChangeListener { _, on -> NotifFilter.setSelected(this@NotifAppsActivity, app.pkg, on) }
                })
            })
        }
        list.addView(box)
    }

    private fun cardOf(v: View) = LinearLayout(this).apply {
        background = rounded(card)
        setPadding(dp(18), dp(14), dp(18), dp(14))
        addView(v, LinearLayout.LayoutParams(-1, -2))
    }

    private fun rounded(color: Int) = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(color) }

    private fun label(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
