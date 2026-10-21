package app.phonebridge

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.os.Bundle
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Telephony
import android.telecom.TelecomManager
import android.util.Log
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

/** Contact names for numbers, cached for one request so big lists stay fast. */
private class NameLookup(private val ctx: Context) {
    private val cache = HashMap<String, String?>()

    fun name(number: String?): String? {
        if (number.isNullOrBlank()) return null
        return cache.getOrPut(number) {
            try {
                val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
                ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
            } catch (e: Exception) {
                null
            }
        }
    }
}

/** Incoming SMS: pushes one-time codes to the Mac clipboard and tells the Mac's Messages view to refresh. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return
        val from = parts[0].displayOriginatingAddress
        val body = parts.joinToString("") { it.displayMessageBody ?: "" }
        Otp.find(body)?.let { code ->
            Link.send("otp", "code" to code, "from" to from)
        }
        Link.send("sms_new", "address" to from)
    }
}

object Otp {
    private val keywords = Regex("(otp|code|verification|verify|one[- ]time|passcode|password|pin|login|2fa|security)", RegexOption.IGNORE_CASE)
    private val code = Regex("(?<![\\d₹$.,])(\\d{4,8}|\\d{3}[- ]\\d{3})(?![\\d,.])")

    /** A 4–8 digit code from a message that looks like a verification SMS, or null. */
    fun find(body: String): String? {
        if (!keywords.containsMatchIn(body)) return null
        return code.find(body)?.value?.replace(Regex("[- ]"), "")
    }
}

object SmsData {
    fun sendThreads(ctx: Context) = thread {
        val names = NameLookup(ctx)
        val seen = HashSet<Long>()
        val threads = JSONArray()
        ctx.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.READ),
            null, null, "${Telephony.Sms.DATE} DESC",
        )?.use { c ->
            while (c.moveToNext() && threads.length() < 60) {
                val thread = c.getLong(0)
                if (!seen.add(thread)) continue
                val address = c.getString(1)
                threads.put(
                    JSONObject().put("thread", thread).put("address", address).put("name", names.name(address))
                        .put("snippet", c.getString(2)?.take(140)).put("date", c.getLong(3)).put("unread", c.getInt(4) == 0)
                )
            }
        }
        Link.send(JSONObject().put("t", "sms_threads").put("threads", threads))
    }

    fun sendThread(ctx: Context, thread: Long) = thread {
        val msgs = JSONArray()
        ctx.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
            "${Telephony.Sms.THREAD_ID} = ?", arrayOf(thread.toString()), "${Telephony.Sms.DATE} DESC",
        )?.use { c ->
            while (c.moveToNext() && msgs.length() < 150) {
                msgs.put(
                    JSONObject().put("body", c.getString(0)).put("date", c.getLong(1))
                        .put("mine", c.getInt(2) != Telephony.Sms.MESSAGE_TYPE_INBOX)
                )
            }
        }
        Link.send(JSONObject().put("t", "sms_thread").put("thread", thread).put("messages", msgs))
    }
}

object Photos {
    private const val THUMB = 256
    /** Photos and videos share one table, so one query lists both. */
    private val IMAGES = MediaStore.Files.getContentUri("external")
    private const val ONLY_MEDIA = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"

    /** One page of the whole library, newest first, with thumbnails. The Mac asks for more as you scroll. */
    /** Albums (Gallery folders): Camera, Screenshots, WhatsApp Images, Downloads… with count and newest photo as cover. */
    fun sendAlbums(ctx: Context) = thread {
        class Album(val id: String, val name: String, val coverId: Long) { var count = 0 }
        val albums = LinkedHashMap<String, Album>()
        ctx.contentResolver.query(
            IMAGES,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.BUCKET_ID, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME),
            ONLY_MEDIA, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val bucket = c.getString(1) ?: continue
                albums.getOrPut(bucket) { Album(bucket, c.getString(2) ?: "Other", c.getLong(0)) }.count++
            }
        }
        val list = JSONArray()
        albums.values.sortedByDescending { it.count }.forEach { a ->
            val cover = try {
                val bmp = ctx.contentResolver.loadThumbnail(ContentUris.withAppendedId(IMAGES, a.coverId), Size(160, 160), null)
                ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 70, it) }.let { Crypto.b64e(it.toByteArray()) }
            } catch (e: Exception) {
                null
            }
            list.put(JSONObject().put("id", a.id).put("name", a.name).put("count", a.count).put("cover", cover))
        }
        Link.send(JSONObject().put("t", "albums").put("albums", list))
    }

    /** [album] = Gallery folder id (BUCKET_ID), or null for every photo. */
    fun sendPage(ctx: Context, offset: Int, limit: Int, album: String?, kind: String = "all") = thread {
        val types = when (kind) {
            "photo" -> "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}"
            "video" -> "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}"
            else -> ONLY_MEDIA
        }
        val selection = album?.let { "$types AND ${MediaStore.MediaColumns.BUCKET_ID} = ?" } ?: types
        val selectionArgs = album?.let { arrayOf(it) }
        val total = ctx.contentResolver.query(IMAGES, arrayOf(MediaStore.MediaColumns._ID), selection, selectionArgs, null)
            ?.use { it.count } ?: 0
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            if (selectionArgs != null) putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_ADDED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        val items = JSONArray()
        ctx.contentResolver.query(
            IMAGES,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.MediaColumns.DURATION),
            args, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val thumb = try {
                    val bmp = ctx.contentResolver.loadThumbnail(ContentUris.withAppendedId(IMAGES, id), Size(THUMB, THUMB), null)
                    val out = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 75, out)
                    Crypto.b64e(out.toByteArray())
                } catch (e: Exception) {
                    null
                }
                val date = if (!c.isNull(1) && c.getLong(1) > 0) c.getLong(1) else c.getLong(2) * 1000
                items.put(JSONObject().put("id", id).put("date", date).put("name", c.getString(3)).put("thumb", thumb)
                    .put("video", c.getInt(4) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO).put("duration", if (c.isNull(5)) 0 else c.getLong(5)))
            }
        }
        Link.send(JSONObject().put("t", "photos").put("album", album ?: "").put("kind", kind).put("offset", offset).put("total", total).put("items", items))
    }

    fun sendFull(ctx: Context, id: Long) = thread {
        Files.send(ctx, ContentUris.withAppendedId(IMAGES, id), kind = "photo:$id") { }
    }
}

/**
 * Deleting photos from the Mac. Android only lets another app trash photos through its own
 * confirmation ("Move to Trash?"), launched from the screen — so: straight away if PhoneBridge is open,
 * silently if "Media management" access is granted, otherwise via a tap-to-review notification.
 * Trash keeps them for 30 days.
 */
object PhotoDelete {
    private const val NOTIF_ID = 4
    @Volatile var pending: List<Long> = emptyList()
        private set

    fun request(ctx: Context, ids: List<Long>) {
        if (ids.isEmpty()) return
        pending = ids
        val intent = trashIntent(ctx, ids)
        if (MediaStore.canManageMedia(ctx)) {
            try {
                intent.send()
                finished(ctx, true)
                return
            } catch (e: Exception) {
                Log.w(TAG, "silent trash failed, asking instead", e)
            }
        }
        val open = Intent(ctx, DeletePhotosActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (MainActivity.visible) {
            ctx.startActivity(open)
            return
        }
        val n = android.app.Notification.Builder(ctx, Channels.PAIR)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle("Delete ${ids.size} photo${if (ids.size > 1) "s" else ""}?")
            .setContentText("Requested from your Mac — tap to review")
            .setContentIntent(PendingIntent.getActivity(ctx, 7, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
    }

    fun trashIntent(ctx: Context, ids: List<Long>): PendingIntent =
        MediaStore.createTrashRequest(ctx.contentResolver, ids.map { ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), it) }, true)

    fun finished(ctx: Context, ok: Boolean) {
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        Link.send(JSONObject().put("t", "photos_deleted").put("ok", ok).put("ids", JSONArray(pending)))
        pending = emptyList()
    }
}

/** Invisible activity that shows Android's "Move to Trash?" confirmation for photos the Mac asked to delete. */
class DeletePhotosActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ids = PhotoDelete.pending
        if (ids.isEmpty()) { finish(); return }
        try {
            startIntentSenderForResult(PhotoDelete.trashIntent(this, ids).intentSender, 1, null, 0, 0, 0)
        } catch (e: Exception) {
            PhotoDelete.finished(this, false)
            finish()
        }
    }

    @Deprecated("Activity result API without AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        PhotoDelete.finished(this, resultCode == RESULT_OK)
        finish()
    }
}

object CallData {
    fun sendLog(ctx: Context) = thread {
        val names = NameLookup(ctx)
        val calls = JSONArray()
        ctx.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
            null, null, "${CallLog.Calls.DATE} DESC",
        )?.use { c ->
            while (c.moveToNext() && calls.length() < 80) {
                val number = c.getString(0)
                val type = when (c.getInt(2)) {
                    CallLog.Calls.INCOMING_TYPE -> "in"
                    CallLog.Calls.OUTGOING_TYPE -> "out"
                    CallLog.Calls.MISSED_TYPE -> "missed"
                    CallLog.Calls.REJECTED_TYPE -> "rejected"
                    else -> "other"
                }
                calls.put(
                    JSONObject().put("number", number).put("name", c.getString(1) ?: names.name(number))
                        .put("type", type).put("date", c.getLong(3)).put("duration", c.getLong(4))
                )
            }
        }
        Link.send(JSONObject().put("t", "call_log").put("calls", calls))
    }

    fun sendContacts(ctx: Context) = thread {
        val contacts = JSONArray()
        val seen = HashSet<String>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val number = c.getString(1) ?: continue
                if (!seen.add(name + number.filter { it.isDigit() }.takeLast(10))) continue
                contacts.put(JSONObject().put("name", name).put("number", number))
            }
        }
        Link.send(JSONObject().put("t", "contacts").put("contacts", contacts))
    }

    /** Places the call on the phone (TelecomManager works from the background, unlike starting the dialer). */
    fun dial(ctx: Context, number: String) {
        try {
            ctx.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", number, null), null)
        } catch (e: SecurityException) {
            Log.w(TAG, "dial needs CALL_PHONE", e)
            Link.send("alert", "title" to "Can't call", "body" to "Allow the Phone permission in PhoneBridge on your phone.")
        }
    }
}
