package app.phonebridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class GuardEntry(val id: String, val reason: String, val host: String, val timeMs: Long, val count: Int)

/**
 * The photos in a guard alert live on the Mac only. This phone keeps just a small list of today's alerts (when, why, how many
 * photos — no pictures) and holds the pictures in memory while you look at them; when the app closes they're gone, and
 * opening an alert again fetches them from the Mac. The list is cleared at the end of each day.
 */
object GuardStore {
    private val memory = ConcurrentHashMap<String, List<ByteArray>>()
    /** Called (on any thread) with an alert's id when its photos arrive. */
    val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    private fun dir(ctx: Context) = File(ctx.filesDir, "guard").apply { mkdirs() }
    private fun metaFile(ctx: Context) = File(dir(ctx), "meta.json")

    fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Synchronized
    private fun readMeta(ctx: Context): MutableList<GuardEntry> = try {
        val arr = JSONArray(metaFile(ctx).readText())
        MutableList(arr.length()) {
            val o = arr.getJSONObject(it)
            GuardEntry(o.getString("id"), o.optString("reason"), o.optString("host", "your Mac"), o.getLong("time"), o.optInt("count"))
        }
    } catch (e: Exception) {
        mutableListOf()
    }

    @Synchronized
    private fun writeMeta(ctx: Context, list: List<GuardEntry>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("reason", it.reason).put("host", it.host).put("time", it.timeMs).put("count", it.count)) }
        metaFile(ctx).writeText(arr.toString())
    }

    /** A new alert arrived: remember its details and keep its pictures in memory only. */
    @Synchronized
    fun addAlert(ctx: Context, id: String, reason: String, host: String, timeMs: Long, photos: List<ByteArray>) {
        purge(ctx)
        val list = readMeta(ctx).filter { it.id != id }.toMutableList()
        list.add(GuardEntry(id, reason, host, timeMs, photos.size))
        writeMeta(ctx, list)
        putPhotos(id, photos)
    }

    /** Pictures fetched from the Mac (or just received); never written to disk. */
    fun putPhotos(id: String, photos: List<ByteArray>) {
        memory[id] = photos
        listeners.forEach { it(id) }
    }

    fun photos(id: String): List<ByteArray>? = memory[id]

    fun entries(ctx: Context): List<GuardEntry> {
        purge(ctx)
        return readMeta(ctx).sortedByDescending { it.timeMs }
    }

    /** Drops everything from before today (the Mac deletes its copies at the same time) and any picture file an older version left. */
    @Synchronized
    fun purge(ctx: Context) {
        val cutoff = startOfToday()
        val all = readMeta(ctx)
        val keep = all.filter { it.timeMs >= cutoff }
        if (keep.size != all.size) writeMeta(ctx, keep)
        val ids = keep.map { it.id }.toSet()
        memory.keys.filter { it !in ids }.forEach { memory.remove(it) }
        dir(ctx).listFiles { f -> f.name.endsWith(".jpg") || (f.name.endsWith(".json") && f.name != "meta.json") }?.forEach { it.delete() }
    }
}
