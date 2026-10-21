package app.phonebridge

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.WindowInsets
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/** One notepad shared with the Mac. The newer edit wins; both sides send theirs when they change. */
object Notes {
    @Volatile var listener: ((String) -> Unit)? = null

    fun text(ctx: Context): String = Prefs.get(ctx).getString("noteText", "") ?: ""
    private fun ts(ctx: Context): Long = Prefs.get(ctx).getLong("noteTs", 0)

    /** The user typed on the phone. */
    fun save(ctx: Context, text: String) {
        val now = System.currentTimeMillis()
        Prefs.get(ctx).edit().putString("noteText", text).putLong("noteTs", now).apply()
        Link.send("note", "text" to text, "ts" to now)
    }

    private fun adopt(ctx: Context, text: String, ts: Long) {
        Prefs.get(ctx).edit().putString("noteText", text).putLong("noteTs", ts).apply()
        listener?.invoke(text)
    }

    fun onMac(ctx: Context, m: JSONObject) {
        val ts = m.getLong("ts")
        if (ts > ts(ctx)) adopt(ctx, m.getString("text"), ts)
    }

    /** Sent by the Mac when it connects: whichever side has the newer note wins. */
    fun onHello(ctx: Context, m: JSONObject) {
        val macTs = m.getLong("ts")
        val mine = ts(ctx)
        if (macTs > mine) adopt(ctx, m.getString("text"), macTs)
        else if (mine > macTs) Link.send("note", "text" to text(ctx), "ts" to mine)
    }
}
