package app.phonebridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.concurrent.thread

const val TAG = "PhoneBridge"

/**
 * The phone listens on PORT; the Mac finds it via Bonjour (or the hotspot gateway address) and connects.
 * Frames are 4-byte big-endian length + payload. "hello"/"trust"/"reject" are plaintext; everything after is AES-GCM.
 */
object Link {
    const val PORT = 47800

    lateinit var app: Context
        private set

    @Volatile var status = "Waiting for Mac…"
        private set

    val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var conn: Conn? = null
    private var started = false

    val isConnected get() = conn?.ready == true

    val deviceName: String
        get() = Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME) ?: android.os.Build.MODEL

    @Synchronized
    fun start(ctx: Context) {
        if (started) return
        started = true
        app = ctx.applicationContext
        Crypto.init(app)
        thread(name = "link-accept", isDaemon = true) { acceptLoop() }
    }

    private fun acceptLoop() {
        while (true) {
            try {
                ServerSocket().use { ss ->
                    ss.reuseAddress = true
                    ss.bind(InetSocketAddress(PORT))
                    while (true) {
                        val s = ss.accept()
                        s.tcpNoDelay = true
                        s.keepAlive = true
                        s.soTimeout = 60_000 // the Mac pings every 15 s
                        s.sendBufferSize = 256 * 1024 // keep queued video (latency) small
                        conn?.close()
                        val c = Conn(s)
                        conn = c
                        thread(name = "link-conn", isDaemon = true) { c.run() }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "server socket failed, retrying", e)
                Thread.sleep(2000)
            }
        }
    }

    fun send(obj: JSONObject): Future<*>? = conn?.send(obj)

    /** Sends and calls [done] once the frame is written (or dropped) — lets video track its backlog. */
    fun sendThen(obj: JSONObject, done: () -> Unit) {
        if (conn?.send(obj, done) == null) done()
    }

    fun send(type: String, vararg fields: Pair<String, Any?>): Future<*>? {
        val o = JSONObject().put("t", type)
        for ((k, v) in fields) o.put(k, v ?: JSONObject.NULL)
        return send(o)
    }

    /** Blocks until the frame is on the wire — used for file chunks so memory stays bounded. */
    fun sendBlocking(obj: JSONObject): Boolean {
        val f = send(obj) ?: return false
        return try {
            f.get()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** IPv4 addresses on all interfaces — lets the Mac find us over Tailscale when we're not on the same Wi-Fi. */
    private fun localAddresses(): List<String> = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .filter { it is java.net.Inet4Address && !it.isLoopbackAddress }.mapNotNull { it.hostAddress }
    } catch (e: Exception) {
        emptyList()
    }

    fun forget() {
        Crypto.setTrustedMac(app, null)
        Prefs.get(app).edit().remove("macName").apply()
        conn?.close()
    }

    internal fun setStatus(s: String) {
        status = s
        changed()
    }

    /** Tells open screens to redraw. */
    fun changed() {
        main.post { listeners.forEach { it() } }
    }

    internal fun onReady(c: Conn) {
        if (conn !== c) return
        setStatus("Connected to ${c.macName}")
        send("info", "name" to deviceName, "addrs" to org.json.JSONArray(localAddresses()))
        Battery.sendCurrent(app)
        AutoReply.report(app)
        MediaWatch.resend()
        NotifListener.instance?.reportDnd()
    }

    internal fun onClosed(c: Conn) {
        if (conn === c) {
            conn = null
            setStatus("Waiting for Mac…")
            DevSwitch.onMacGone(app)
        }
    }
}

class Conn(private val sock: Socket) {
    private val input = DataInputStream(BufferedInputStream(sock.getInputStream()))
    private val output = DataOutputStream(BufferedOutputStream(sock.getOutputStream()))
    private val writer = Executors.newSingleThreadExecutor()

    @Volatile var ready = false
        private set
    @Volatile private var closed = false
    var macName = "Mac"
        private set

    private lateinit var shared: ByteArray   // static-static: only for the pairing code
    private lateinit var ikm: ByteArray      // session key material (includes this connection's ephemeral keys)
    private val ephemeral = Crypto.Ephemeral()
    private lateinit var salt: ByteArray
    private lateinit var macPubB64: String
    private var sendSealer: Sealer? = null // only touched on the writer thread after ready
    private var recvSealer: Sealer? = null // only touched on the reader thread
    private var sentTrust = false
    private var gotTrust = false

    fun run() {
        try {
            val hello = JSONObject(String(readFrame()))
            require(hello.getString("t") == "hello") { "expected hello" }
            macPubB64 = hello.getString("pub")
            macName = hello.optString("name", "Mac")
            val myNonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
            salt = Crypto.b64d(hello.getString("nonce")) + myNonce
            val macPub = Crypto.b64d(macPubB64)
            shared = Crypto.shared(macPub)
            // Older Mac app without per-connection keys: refuse rather than fall back to weaker crypto.
            ikm = Crypto.sessionIkm(ephemeral, macPub, Crypto.b64d(hello.getString("epub")))

            writePlain(
                JSONObject().put("t", "hello").put("name", Link.deviceName)
                    .put("pub", Crypto.b64e(Crypto.pubRaw)).put("epub", Crypto.b64e(ephemeral.pub))
                    .put("nonce", Crypto.b64e(myNonce)).put("v", 2)
            )

            val trusted = Crypto.trustedMac(Link.app)
            if (trusted != null && !trusted.contentEquals(macPub)) {
                // Paired with a different Mac: never offer to pair, just refuse.
                Link.setStatus("Blocked a connection from “$macName” — not your paired Mac")
                writePlain(JSONObject().put("t", "reject"))
                return
            }
            if (trusted != null) {
                sendTrust()
            } else {
                Link.setStatus("Pairing with $macName…")
                Pairing.ask(Crypto.pairingCode(shared), macName, this)
            }

            while (!closed) {
                val frame = readFrame()
                val r = recvSealer
                if (r != null) {
                    Dispatch.handle(JSONObject(String(r.open(frame))))
                    continue
                }
                val o = JSONObject(String(frame))
                when (o.optString("t")) {
                    // After the Mac's "trust", everything it sends is encrypted.
                    "trust" -> {
                        recvSealer = Sealer(Crypto.hkdf(ikm, salt, "m2p"))
                        synchronized(this) { gotTrust = true; maybeReady() }
                    }
                    "reject" -> return
                }
            }
        } catch (e: Exception) {
            if (!closed) Log.i(TAG, "connection ended: $e")
        } finally {
            close()
        }
    }

    fun confirmPairing() {
        Crypto.setTrustedMac(Link.app, Crypto.b64d(macPubB64))
        Prefs.get(Link.app).edit().putString("macName", macName).apply()
        sendTrust()
    }

    fun rejectPairing() {
        writer.execute { runCatching { writePlain(JSONObject().put("t", "reject")) } }
        writer.execute { close() }
    }

    @Synchronized
    private fun sendTrust() {
        if (sentTrust) return
        writer.execute { runCatching { writePlain(JSONObject().put("t", "trust")) }.onFailure { close() } }
        sentTrust = true
        maybeReady()
    }

    // Caller holds the lock. Encryption starts only once both sides trust each other.
    private fun maybeReady() {
        if (!sentTrust || !gotTrust || ready) return
        val key = Crypto.hkdf(ikm, salt, "p2m")
        writer.execute { sendSealer = Sealer(key) }
        ready = true
        Link.onReady(this)
    }

    fun send(obj: JSONObject, done: (() -> Unit)? = null): Future<*>? {
        if (!ready || closed) return null
        val bytes = obj.toString().toByteArray()
        return try {
            writer.submit {
                try {
                    writeFrame(sendSealer!!.seal(bytes))
                } catch (e: Exception) {
                    close()
                    throw e
                } finally {
                    done?.invoke()
                }
            }
        } catch (e: Exception) {
            null // executor already shut down
        }
    }

    private fun readFrame(): ByteArray {
        val n = input.readInt()
        require(n in 0..16_000_000) { "bad frame length $n" }
        return ByteArray(n).also { input.readFully(it) }
    }

    private fun writePlain(o: JSONObject) = writeFrame(o.toString().toByteArray())

    private fun writeFrame(b: ByteArray) {
        output.writeInt(b.size)
        output.write(b)
        output.flush()
    }

    fun close() {
        if (closed) return
        closed = true
        ready = false
        runCatching { sock.close() }
        writer.shutdown()
        Pairing.cancel(this)
        Link.onClosed(this)
    }
}
