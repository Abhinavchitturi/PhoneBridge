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
 * The phone listens on PORT; computers find it via Bonjour (or the hotspot gateway address) and connect.
 * Frames are 4-byte big-endian length + payload. "hello"/"pair"/"trust"/"reject" are plaintext; everything after is AES-GCM.
 *
 * The phone can be paired with several computers ([Computers]) but talks to one at a time. Every paired computer
 * keeps dialling in while it isn't connected; [admit] lets in only the one we want and turns the rest away with
 * {"t":"reject","why":"busy"}. Unknown computers get "why":"unpaired" unless "Pair a new computer" is on (or nothing is paired yet).
 */
object Link {
    const val PORT = 47800

    lateinit var app: Context
        private set

    @Volatile var status = "Looking for your computer…"
        private set

    val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    /** The one connection that is in use (or being paired / authenticated). */
    @Volatile private var conn: Conn? = null
    private var started = false

    val isConnected get() = conn?.ready == true

    /** The connected computer's id, or null. */
    val connectedId: String? get() = conn?.takeIf { it.ready }?.peerId

    /** The connected computer's name as the user sees it. */
    val computerName: String
        get() = conn?.takeIf { it.ready }?.let { c -> Computers.get(app, c.peerId)?.title ?: c.macName } ?: ""

    /** The computer the user picked from the list; only it may connect until it does (or [WANT_FOR] passes). */
    @Volatile var wanted: String? = null
        private set
    private var wantedUntil = 0L
    /** Set when the picked computer didn't show up in time — the list says so plainly. */
    @Volatile var notFound: String? = null
        private set
    /** "Pair a new computer" is on: unknown computers may ask to pair, paired ones wait. */
    @Volatile var pairingNew = false
        private set
    private var pairingUntil = 0L
    /** After launch or a dropped connection, the last used computer gets a head start before any other may connect. */
    private var preferLastUntil = 0L
    /** When each paired computer last dialled in — the ones heard from recently are "nearby". */
    private val knocks = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private const val PREFER_LAST_MS = 12_000L  // computers retry every 5 s
    private const val NOT_FOUND_MS = 15_000L
    private const val WANT_FOR_MS = 120_000L
    private const val PAIRING_FOR_MS = 180_000L
    private const val NEARBY_MS = 15_000L

    fun isNearby(id: String) = (knocks[id] ?: 0L) > System.currentTimeMillis() - NEARBY_MS

    val deviceName: String
        get() = Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME) ?: android.os.Build.MODEL

    @Synchronized
    fun start(ctx: Context) {
        if (started) return
        started = true
        app = ctx.applicationContext
        Crypto.init(app)
        Computers.isEmpty(app) // runs the one-time migration from the single paired Mac
        preferLastUntil = System.currentTimeMillis() + PREFER_LAST_MS
        setStatus(waitingText())
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
                        // Not in use yet: the hello tells us who it is, then admit() decides.
                        val c = Conn(s)
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

    enum class Admit { TRUST, PAIR, BUSY, STRANGER }

    /** Decides what to do with a computer that just said hello. A TRUST or PAIR answer makes [c] the connection in use. */
    @Synchronized
    internal fun admit(c: Conn): Admit {
        val now = System.currentTimeMillis()
        expire(now)
        val id = c.peerId
        val known = Computers.get(app, id) != null
        val current = conn
        if (!known) {
            if (!pairingNew && !Computers.isEmpty(app)) return Admit.STRANGER
            if (current != null) return Admit.BUSY // already pairing with (or connected to) another one
            conn = c
            return Admit.PAIR
        }
        val firstKnock = !isNearby(id)
        knocks[id] = now
        if (firstKnock) changed()
        if (pairingNew) return Admit.BUSY
        // The same computer reconnecting (new network, woke from sleep) replaces its old, possibly dead, socket.
        if (current != null && current.peerId != id) return Admit.BUSY
        val w = wanted
        if (w != null && w != id) return Admit.BUSY
        if (w == null && now < preferLastUntil) {
            val last = Computers.lastUsed(app)
            if (last != null && last != id) return Admit.BUSY
        }
        conn = c
        current?.close()
        return Admit.TRUST
    }

    private fun expire(now: Long) {
        if (wanted != null && now > wantedUntil) { wanted = null; notFound = null }
        if (pairingNew && now > pairingUntil) pairingNew = false
    }

    /** Switches to [id]: drops the current computer and waits for this one to dial in. */
    fun connectTo(id: String) {
        val old: Conn?
        synchronized(this) {
            pairingNew = false
            notFound = null
            if (connectedId == id) { wanted = null; return }
            wanted = id
            wantedUntil = System.currentTimeMillis() + WANT_FOR_MS
            old = conn
        }
        old?.close() // onClosed() clears it and updates the status
        setStatus(waitingText())
        main.postDelayed({ recheck() }, WANT_FOR_MS + 500)
        val name = Computers.get(app, id)?.title ?: "your computer"
        main.postDelayed({
            if (wanted == id && connectedId != id) {
                notFound = id
                setStatus("Couldn't find “$name”")
            }
        }, NOT_FOUND_MS)
    }

    /** Opens the door for a computer this phone hasn't paired with yet (the current one is disconnected meanwhile). */
    fun startPairingNew() {
        val old: Conn?
        synchronized(this) {
            wanted = null
            notFound = null
            pairingNew = true
            pairingUntil = System.currentTimeMillis() + PAIRING_FOR_MS
            old = conn?.takeIf { it.ready }
        }
        old?.close()
        setStatus(waitingText())
        main.postDelayed({ recheck() }, PAIRING_FOR_MS + 500)
    }

    /** Lets a timed-out choice or pairing window lapse even if no computer dials in meanwhile. */
    private fun recheck() {
        synchronized(this) { expire(System.currentTimeMillis()) }
        if (!isConnected) setStatus(waitingText()) else changed()
    }

    fun stopPairingNew() {
        val pending: Conn?
        synchronized(this) {
            pairingNew = false
            pending = conn?.takeIf { !it.ready }
            preferLastUntil = System.currentTimeMillis() + PREFER_LAST_MS
        }
        pending?.let { Pairing.reject() }
        setStatus(waitingText())
    }

    /** Removes [id] from the list; if it's the one connected, disconnects it. */
    fun forget(id: String) {
        val old: Conn?
        synchronized(this) {
            Computers.remove(app, id)
            knocks.remove(id)
            if (wanted == id) wanted = null
            if (notFound == id) notFound = null
            old = conn?.takeIf { it.peerId == id }
        }
        old?.close()
        changed()
    }

    /** Refreshes the status line after the connected computer is renamed. */
    fun setConnectedName() {
        if (isConnected) setStatus("Connected to $computerName")
    }

    fun waitingText(): String {
        val w = wanted
        return when {
            pairingNew -> "Waiting for a new computer…"
            w != null -> "Looking for “${Computers.get(app, w)?.title ?: "your computer"}”…"
            else -> "Looking for your computer…"
        }
    }

    /** An unpaired computer tried to connect. Only shown while nothing is connected, so it never hides the status. */
    internal fun strangerBlocked(name: String) {
        if (!isConnected && conn == null) setStatus("Blocked “$name” — it isn't paired. Use Computers → Pair a new computer to add it.")
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
        synchronized(this) {
            if (conn !== c) return
            Computers.seen(app, c.peerId, c.macName, makeLast = true)
            wanted = null
            notFound = null
            pairingNew = false
        }
        setStatus("Connected to $computerName")
        send("info", "name" to deviceName, "addrs" to org.json.JSONArray(localAddresses()))
        Battery.sendCurrent(app)
        ThemeStore.push(app)
        AutoReply.report(app)
        SmsSwitch.report(app)
        MediaWatch.resend()
        NotifListener.instance?.reportDnd()
    }

    internal fun onClosed(c: Conn) {
        synchronized(this) {
            if (c.ready || c.wasReady) Computers.seen(app, c.peerId, null, makeLast = false) // "last connected" time
            if (conn !== c) return
            conn = null
            // Give the computer that just dropped a moment to come back before another one takes the phone.
            if (c.wasReady) preferLastUntil = System.currentTimeMillis() + PREFER_LAST_MS
        }
        setStatus(waitingText())
        DevSwitch.onMacGone(app)
    }
}

class Conn(private val sock: Socket) {
    private val input = DataInputStream(BufferedInputStream(sock.getInputStream()))
    private val output = DataOutputStream(BufferedOutputStream(sock.getOutputStream()))
    private val writer = Executors.newSingleThreadExecutor()

    @Volatile var ready = false
        private set
    @Volatile private var closed = false
    /** The computer's name as it reports it (any computer, despite the field name). */
    var macName = "Computer"
        private set
    /** The computer's long-term public key, base64 — its id in [Computers]. */
    var peerId = ""
        private set
    /** Was ready at some point (stays true after close, unlike [ready]). */
    @Volatile var wasReady = false
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
            macName = hello.optString("name", "Computer")
            val myNonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
            salt = Crypto.b64d(hello.getString("nonce")) + myNonce
            val macPub = Crypto.b64d(macPubB64)
            peerId = Computers.idOf(macPub)
            shared = Crypto.shared(macPub)
            // Older Mac app without per-connection keys: refuse rather than fall back to weaker crypto.
            ikm = Crypto.sessionIkm(ephemeral, macPub, Crypto.b64d(hello.getString("epub")))

            writePlain(
                JSONObject().put("t", "hello").put("name", Link.deviceName)
                    .put("pub", Crypto.b64e(Crypto.pubRaw)).put("epub", Crypto.b64e(ephemeral.pub))
                    .put("nonce", Crypto.b64e(myNonce)).put("v", 3) // v3: says "pair" before the computer shows a code
            )

            when (Link.admit(this)) {
                Link.Admit.TRUST -> sendTrust()
                Link.Admit.PAIR -> {
                    writePlain(JSONObject().put("t", "pair")) // newer computers wait for this before showing the code
                    Link.setStatus("Pairing with $macName…")
                    Pairing.ask(Crypto.pairingCode(shared), macName, this)
                }
                Link.Admit.BUSY -> {
                    // Paired, but the phone is using (or waiting for) another computer. It'll keep retrying.
                    writePlain(JSONObject().put("t", "reject").put("why", "busy"))
                    return
                }
                Link.Admit.STRANGER -> {
                    // Not paired and not pairing: never offer to pair, just refuse.
                    Link.strangerBlocked(macName)
                    writePlain(JSONObject().put("t", "reject").put("why", "unpaired"))
                    return
                }
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
        if (closed) return
        Computers.add(Link.app, peerId, macName)
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
        wasReady = true
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
