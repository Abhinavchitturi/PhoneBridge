package app.phonebridge

import android.util.Log
import org.json.JSONObject

/** Handles messages coming from the Mac (called on the connection's reader thread). */
object Dispatch {
    private fun castInput(m: JSONObject) {
        val t = TypingService.instance ?: run {
            Link.send("alert", "title" to "Turn on control from the Mac",
                "body" to "On the phone: PhoneBridge → Type from Mac → switch on PhoneBridge in Accessibility.")
            return
        }
        when (m.getString("kind")) {
            "tap" -> t.gesture(m.getDouble("x"), m.getDouble("y"), m.getDouble("x"), m.getDouble("y"), m.optLong("ms", 40))
            "swipe" -> t.gesture(m.getDouble("x"), m.getDouble("y"), m.getDouble("x2"), m.getDouble("y2"), m.optLong("ms", 300))
            "nav" -> t.nav(m.getString("action"))
            "down", "move", "up" -> t.touch(m.getString("kind"), m.getDouble("x"), m.getDouble("y"))
            "text" -> t.type(m.getString("text"), backspace = false)
            "backspace" -> t.type(null, backspace = true)
            "enter" -> t.enter()
        }
    }

    fun handle(m: JSONObject) {
        val ctx = Link.app
        try {
            when (m.optString("t")) {
                "ping" -> Link.send("pong")
                "reply" -> NotifListener.instance?.reply(m.getString("id"), m.getString("text"))
                "dismiss" -> NotifListener.instance?.dismiss(m.getString("id"))
                "call_answer" -> Calls.answer(ctx)
                "call_end" -> Calls.end(ctx)
                "guard_setup" -> Guard.setup(ctx, m)
                "guard_off" -> Guard.off(ctx)
                "guard_alert" -> Guard.onLinkAlert(ctx, m)
                "autoreply_set" -> AutoReply.configure(ctx, m)
                "autoreply_request" -> AutoReply.report(ctx)
                "call_ctl" -> Calls.control(ctx, m.getString("action"))
                "clip" -> Clip.setFromMac(ctx, m.getString("text"))
                "ring" -> Ringer.start(ctx)
                "ring_stop" -> Ringer.stop(ctx)
                "apps_request" -> AppList.send(ctx)
                "mac_state" -> MacRemote.onState(m)
                "shortcuts" -> MacRemote.onShortcuts(m)
                "mac_media" -> MacRemote.onMedia(m)
                "deck_state" -> MacRemote.onDeck(m)
                "media_cmd" -> MediaWatch.command(m.getString("cmd"))
                "notif_action" -> NotifListener.instance?.pressAction(m.getString("id"), m.getInt("index"))
                "clip_image" -> ImageClip.setFromMac(ctx, Crypto.b64d(m.getString("png")))
                "phone_set_text" -> TypingService.instance?.setText(m.getString("text"))
                "phone_enter" -> TypingService.instance?.enter()
                "sms_threads_request" -> SmsData.sendThreads(ctx)
                "sms_thread_request" -> SmsData.sendThread(ctx, m.getLong("thread"))
                "photos_request" -> Photos.sendPage(ctx, m.optInt("offset", 0), m.optInt("limit", 60),
                    m.optString("album").takeIf { it.isNotEmpty() }, m.optString("kind", "all"))
                "albums_request" -> Photos.sendAlbums(ctx)
                "cast_start" -> ScreenCaster.request(ctx, m.optString("app").takeIf { it.isNotEmpty() })
                "cast_stop" -> ScreenCaster.stop()
                "va" -> ScreenCaster.onAck(m.getInt("s"))
                "debug_on" -> DevSwitch.on(ctx)
                "debug_off" -> DevSwitch.off(ctx)
                "cast_input" -> castInput(m)
                "photos_delete" -> {
                    val arr = m.getJSONArray("ids")
                    PhotoDelete.request(ctx, List(arr.length()) { arr.getLong(it) })
                }
                "phone_dnd" -> Dnd.set(ctx, m.getBoolean("on"))
                "note" -> Notes.onMac(ctx, m)
                "note_hello" -> Notes.onHello(ctx, m)
                "device_request" -> DeviceTools.sendInfo(ctx)
                "biggest_request" -> DeviceTools.sendBiggest(ctx)
                "loc_request" -> DeviceTools.sendLocation(ctx)
                "torch" -> DeviceTools.torch(ctx, m.getBoolean("on"))
                "ringer" -> DeviceTools.setRinger(ctx, m.getString("mode"))
                "phone_lock" -> DeviceTools.lock(ctx)
                "phone_unlock" -> DeviceTools.unlock(ctx, m.optString("pin").takeIf { it.isNotEmpty() })
                "photo_get" -> Photos.sendFull(ctx, m.getLong("id"))
                "call_log_request" -> CallData.sendLog(ctx)
                "contacts_request" -> CallData.sendContacts(ctx)
                "dial" -> CallData.dial(ctx, m.getString("number"))
                "alert" -> PhoneAlerts.alert(ctx, m.getString("title"), m.optString("body"))
                "open_on_phone" -> PhoneAlerts.openUrl(ctx, m.getString("url"), m.optString("title"))
                "sms_send" -> Sms.send(ctx, m.getString("to"), m.getString("text"))
                "file_start", "file_chunk", "file_end" -> Files.incoming(ctx, m)
                else -> Log.d(TAG, "unknown message ${m.optString("t")}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "failed handling ${m.optString("t")}", e)
        }
    }
}
