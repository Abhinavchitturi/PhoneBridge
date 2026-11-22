package app.phonebridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The computers this phone is paired with — a Mac today, Windows next; both speak the same protocol, so nothing here
 * is Mac-specific. Each is identified by its long-term X25519 public key. The list is sealed with the Keystore key
 * (like the single paired key it replaces) so a computer can't be slipped in by editing app storage.
 * The phone talks to one computer at a time; [Link] decides which.
 */
object Computers {
    class Computer(val id: String, var name: String, var label: String?, var lastSeen: Long) {
        /** What the user sees: their own name for it if they renamed it, otherwise the name it reports. */
        val title get() = label?.takeIf { it.isNotBlank() } ?: name
    }

    private var cache: MutableList<Computer>? = null

    fun idOf(key: ByteArray): String = Crypto.b64e(key)

    @Synchronized
    fun all(ctx: Context): List<Computer> = load(ctx).sortedByDescending { it.lastSeen }

    @Synchronized
    fun get(ctx: Context, id: String?): Computer? = if (id == null) null else load(ctx).firstOrNull { it.id == id }

    @Synchronized
    fun isEmpty(ctx: Context) = load(ctx).isEmpty()

    /** Adds a newly paired computer (or refreshes its name if it's already there). */
    @Synchronized
    fun add(ctx: Context, id: String, name: String) {
        val list = load(ctx)
        val c = list.firstOrNull { it.id == id }
        if (c == null) list += Computer(id, name, null, System.currentTimeMillis()) else c.name = name
        save(ctx, list)
    }

    /** Called when a computer connects or disconnects: remembers its current name, when we last saw it, and that it was the last used. */
    @Synchronized
    fun seen(ctx: Context, id: String, name: String?, makeLast: Boolean) {
        val list = load(ctx)
        val c = list.firstOrNull { it.id == id } ?: return
        if (!name.isNullOrBlank()) c.name = name
        c.lastSeen = System.currentTimeMillis()
        save(ctx, list)
        if (makeLast) Prefs.get(ctx).edit().putString("lastComputer", id).apply()
    }

    @Synchronized
    fun rename(ctx: Context, id: String, label: String?) {
        val list = load(ctx)
        list.firstOrNull { it.id == id }?.label = label?.trim()?.takeIf { it.isNotEmpty() }
        save(ctx, list)
    }

    @Synchronized
    fun remove(ctx: Context, id: String) {
        val list = load(ctx)
        list.removeAll { it.id == id }
        save(ctx, list)
        if (lastUsed(ctx) == id) Prefs.get(ctx).edit().remove("lastComputer").apply()
    }

    /** The computer used most recently, if it's still paired. */
    @Synchronized
    fun lastUsed(ctx: Context): String? = Prefs.get(ctx).getString("lastComputer", null)?.takeIf { id -> load(ctx).any { it.id == id } }

    private fun load(ctx: Context): MutableList<Computer> {
        cache?.let { return it }
        val sealed = Prefs.get(ctx).getString("computersSealed", null)
        val list = if (sealed != null) {
            // If the Keystore key is gone (restored backup, wiped keystore) the list can't be trusted: start empty and re-pair.
            runCatching { parse(String(Vault.open(sealed))) }.getOrElse { mutableListOf() }
        } else {
            migrate(ctx)
        }
        cache = list
        return list
    }

    /** Versions up to 0.22 kept exactly one paired Mac; move it into the list so nobody has to pair again. */
    private fun migrate(ctx: Context): MutableList<Computer> {
        val p = Prefs.get(ctx)
        val list = mutableListOf<Computer>()
        val old = Crypto.trustedMac(ctx) // also upgrades the even older unsealed "macPub"
        if (old != null) {
            val id = idOf(old)
            list += Computer(id, p.getString("macName", null) ?: "Mac", null, System.currentTimeMillis())
            p.edit().putString("lastComputer", id).apply()
        }
        save(ctx, list) // written before the old key is removed, so a crash in between can't lose the pairing
        Crypto.setTrustedMac(ctx, null)
        p.edit().remove("macName").apply()
        return list
    }

    private fun parse(json: String): MutableList<Computer> {
        val a = JSONArray(json)
        return MutableList(a.length()) { i ->
            val o = a.getJSONObject(i)
            Computer(o.getString("id"), o.optString("name", "Computer"), o.optString("label").takeIf { it.isNotEmpty() }, o.optLong("seen"))
        }
    }

    private fun save(ctx: Context, list: List<Computer>) {
        val a = JSONArray()
        for (c in list) a.put(JSONObject().put("id", c.id).put("name", c.name).put("label", c.label ?: "").put("seen", c.lastSeen))
        Prefs.get(ctx).edit().putString("computersSealed", Vault.seal(a.toString().toByteArray())).commit()
    }
}
