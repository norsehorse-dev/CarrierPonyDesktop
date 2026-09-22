// DesktopLanDiscovery.kt
// CarrierPony Desktop. Twin of Android's LanDiscovery with the same public surface: the
// vendored LanDirectTransport, ChatStore and the window code against it by name. The wire
// protocol (LanCrypto: CPL1 magic, HELLO / HELLO_ACK / NO_MATCH / ENVELOPE frames, the per-pair
// key handshake) is byte for byte the Android and iOS one; that code is vendored. Only the
// discovery mechanism differs: Android NSD there, LanMdns here.
//
// DRIFT WATCH: the handshake, sweep and delivery logic below is a copy of Android's
// LanDiscovery.kt (2.1 M2/M3). When that file changes upstream, change this one.

package com.carrierpony.app.net

import com.carrierpony.app.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

// Traces go through the Log shim: silent unless CARRIERPONY_DEBUG=1.
private fun lanLog(msg: String) { android.util.Log.d("CPLAN", msg) }

class LanDiscovery(
    private val mdns: LanMdns = JmDnsLanMdns(),
    private val enabled: () -> Boolean = { AppConfig.lanDirectEnabled() },
) {
    data class DiscoveredNode(val nodeID: String)
    private data class Resolved(val host: InetAddress, val port: Int)

    companion object {
        const val SERVICE_TYPE = "_carrierpony._tcp."
        private const val CONNECT_TIMEOUT_MS = 3000
        private const val READ_TIMEOUT_MS = 4000

        private fun randomNodeID(): String {
            val bytes = ByteArray(8)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }

    val nodeID = randomNodeID()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _nearby = MutableStateFlow<List<DiscoveredNode>>(emptyList())
    val nearby: StateFlow<List<DiscoveredNode>> = _nearby.asStateFlow()

    private val _reachable = MutableStateFlow<Set<String>>(emptySet())
    /** Fingerprint hexes of known contacts currently identified on the LAN. */
    val reachable: StateFlow<Set<String>> = _reachable.asStateFlow()

    /** Supplied by the session: the active identity's contact -> per-pair LAN key. */
    @Volatile var keyProvider: (() -> Map<String, ByteArray>)? = null
    /** Hand a received envelope to ChatStore's ingest path. */
    @Volatile var onEnvelope: ((ByteArray) -> Unit)? = null

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var sweepJob: Job? = null

    private val lock = Any()
    private val seen = LinkedHashSet<String>()
    private val endpoints = HashMap<String, Resolved>()
    private val nodeToFpr = HashMap<String, String>()
    private val probed = HashSet<String>()
    private val resolveMutex = Mutex()

    private fun identifyEnabled(): Boolean = enabled() && keyProvider != null

    /** The port this node listens on, once started. */
    val port: Int get() = serverSocket?.localPort ?: 0

    @Synchronized
    fun start() {
        if (running) return
        running = true
        startListening()
        // Bringing up a multicast responder on every interface takes seconds on some machines;
        // the session (and the window) must not wait for it.
        scope.launch {
            runCatching { mdns.browse(object : LanMdns.Listener {
                override fun found(name: String, host: InetAddress, port: Int) = onFound(name, host, port)
                override fun lost(name: String) = onLost(name)
            }) }
        }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        acceptJob?.cancel(); acceptJob = null
        sweepJob?.cancel(); sweepJob = null
        // Closing the responder sends goodbyes and waits per interface; off the caller's thread.
        Thread({ runCatching { mdns.close() } }, "carrierpony-mdns-close").apply { isDaemon = true }.start()
        runCatching { serverSocket?.close() }
        serverSocket = null
        synchronized(lock) { seen.clear(); endpoints.clear(); nodeToFpr.clear(); probed.clear() }
        _nearby.value = emptyList()
        _reachable.value = emptySet()
    }

    /** Called when the LAN-direct toggle flips, or the contact keys change (a new pairing). */
    fun lanDidToggle() {
        if (identifyEnabled()) {
            synchronized(lock) { probed.clear() }
            kickSweep()
        } else {
            synchronized(lock) { nodeToFpr.clear(); probed.clear() }
            _reachable.value = emptySet()
        }
    }

    private fun startListening() {
        val server = runCatching { ServerSocket(0) }.getOrNull() ?: return
        serverSocket = server
        scope.launch { runCatching { mdns.advertise(nodeID, server.localPort) } }
        acceptJob = scope.launch {
            while (running) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                launch { handleInbound(socket) }
            }
        }
    }

    private fun onFound(name: String, host: InetAddress, port: Int) {
        lanLog("found $name at ${host.hostAddress}:$port (me=$nodeID)")
        if (isSelf(name, host, port)) return
        synchronized(lock) {
            seen.add(name)
            // One endpoint per node. A node is announced once per address family; an IPv4
            // address is kept over a link-local IPv6 one, which needs a scope id to dial.
            val existing = endpoints[name]
            if (existing == null || existing.host !is java.net.Inet4Address || host is java.net.Inet4Address) {
                endpoints[name] = Resolved(host, port)
            }
        }
        publishNearby()
        if (identifyEnabled()) kickSweep()
    }

    /** Our own announcement, heard back. JmDNS renames a service it sees on more than one
     *  interface ("<id> (2)", "(3)", ...), and a name check alone missed those: the desktop then
     *  dialled itself, matched its own pair keys, and mapped its own node to a contact. That is
     *  why delivery only sometimes reached the phone. Any address of this machine on our own
     *  port is us. */
    private fun isSelf(name: String, host: InetAddress, port: Int): Boolean {
        if (name == nodeID || name.startsWith("$nodeID (")) return true
        val ownPort = serverSocket?.localPort ?: return false
        if (port != ownPort) return false
        return host.isLoopbackAddress || host.isAnyLocalAddress ||
            runCatching { java.net.NetworkInterface.getByInetAddress(host) != null }.getOrDefault(false)
    }

    private fun onLost(name: String) {
        lanLog("lost $name")
        synchronized(lock) {
            seen.remove(name)
            endpoints.remove(name)
            nodeToFpr.remove(name)?.let { fpr -> _reachable.value = _reachable.value - fpr }
        }
        publishNearby()
    }

    private fun publishNearby() {
        _nearby.value = synchronized(lock) { seen.filter { it != nodeID }.sorted().map { DiscoveredNode(it) } }
    }

    private fun kickSweep() {
        synchronized(lock) { if (sweepJob?.isActive == true) return }
        sweepJob = scope.launch { runSweep() }
    }

    private suspend fun runSweep() {
        if (!identifyEnabled()) { lanLog("sweep skipped: enabled=${enabled()} keyProvider=${keyProvider != null}"); return }
        val keys = keyProvider?.invoke() ?: return
        val targets = synchronized(lock) { endpoints.toMap() }
        lanLog("sweep: ${targets.size} node(s), ${keys.size} pair key(s)")
        for ((name, ep) in targets) {
            if (synchronized(lock) { nodeToFpr.containsKey(name) }) continue
            for ((fpr, key) in keys) {
                val mark = "$name|$fpr"
                if (synchronized(lock) { !probed.add(mark) }) continue
                val ok = identify(ep, fpr, key)
                lanLog("identify $name as ${fpr.takeLast(8)} at ${ep.host.hostAddress}:${ep.port}: ${if (ok) "MATCH" else "no"}")
                if (ok) {
                    synchronized(lock) { nodeToFpr[name] = fpr }
                    _reachable.value = _reachable.value + fpr
                    break
                }
            }
        }
    }

    private fun handshakeDial(ep: Resolved, pairKey: ByteArray): Socket? {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(ep.host, ep.port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            val out = socket.getOutputStream()
            val input = socket.getInputStream()
            val dnonce = LanCrypto.randomBytes(16)
            out.write(LanCrypto.MAGIC)
            out.write(LanCrypto.frame(LanCrypto.T_HELLO, dnonce + LanCrypto.helloTag(pairKey, dnonce)))
            out.flush()
            val frame = readFrame(input)
            if (frame == null) { lanLog("dial ${ep.host.hostAddress}:${ep.port}: no reply to HELLO"); socket.close(); return null }
            val (type, payload) = frame
            if (type != LanCrypto.T_HELLO_ACK || payload.size != 48) { lanLog("dial ${ep.host.hostAddress}:${ep.port}: reply type=$type len=${payload.size} (NO_MATCH=3)"); socket.close(); return null }
            val lnonce = payload.copyOfRange(0, 16)
            val gotTag = payload.copyOfRange(16, 48)
            val want = LanCrypto.ackTag(pairKey, dnonce, lnonce)
            if (LanCrypto.constantTimeEquals(gotTag, want)) return socket
            socket.close(); return null
        } catch (e: Exception) {
            lanLog("dial ${ep.host.hostAddress}:${ep.port}: ${e::class.simpleName}: ${e.message}")
            runCatching { socket.close() }
            return null
        }
    }

    private fun identify(ep: Resolved, targetFpr: String, pairKey: ByteArray): Boolean {
        val s = handshakeDial(ep, pairKey) ?: return false
        runCatching { s.close() }
        return true
    }

    /** True if the peer is a known contact currently identified on this LAN. */
    fun canReachLan(fprHex: String): Boolean =
        identifyEnabled() && _reachable.value.contains(fprHex.lowercase())

    /** Deliver one sealed envelope to a peer over the LAN. Best-effort: true only if a matched
     *  node accepted the framed envelope; the relay is the authority. */
    suspend fun deliver(peerHex: String, envelope: ByteArray): Boolean = withContext(Dispatchers.IO) {
        if (!identifyEnabled()) return@withContext false
        val target = peerHex.lowercase()
        val key = keyProvider?.invoke()?.get(target) ?: return@withContext false
        val ep = synchronized(lock) {
            val node = nodeToFpr.entries.firstOrNull { it.value == target }?.key
            if (node == null) null else endpoints[node]
        } ?: return@withContext false
        val socket = handshakeDial(ep, key) ?: run { lanLog("deliver to ${target.takeLast(8)}: handshake failed"); return@withContext false }
        try {
            val out = socket.getOutputStream()
            out.write(LanCrypto.frame(LanCrypto.T_ENVELOPE, envelope))
            out.flush()
            lanLog("deliver to ${target.takeLast(8)}: ${envelope.size} bytes sent")
            true
        } catch (e: Exception) {
            false
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun handleInbound(socket: Socket) {
        try {
            lanLog("inbound from ${socket.inetAddress?.hostAddress}")
            if (!identifyEnabled()) { lanLog("inbound refused: LAN delivery is off here"); return }
            val keys = keyProvider?.invoke() ?: return
            socket.soTimeout = READ_TIMEOUT_MS
            val input = socket.getInputStream()
            val out = socket.getOutputStream()
            val magic = readExactly(input, 4) ?: return
            if (!magic.contentEquals(LanCrypto.MAGIC)) return
            val (type, payload) = readFrame(input) ?: return
            if (type != LanCrypto.T_HELLO || payload.size != 48) return
            val dnonce = payload.copyOfRange(0, 16)
            val gotTag = payload.copyOfRange(16, 48)
            for ((fpr, key) in keys) {
                val want = LanCrypto.helloTag(key, dnonce)
                if (LanCrypto.constantTimeEquals(gotTag, want)) {
                    val lnonce = LanCrypto.randomBytes(16)
                    out.write(LanCrypto.frame(LanCrypto.T_HELLO_ACK, lnonce + LanCrypto.ackTag(key, dnonce, lnonce)))
                    out.flush()
                    _reachable.value = _reachable.value + fpr
                    lanLog("inbound matched ${fpr.takeLast(8)}")
                    receiveLoop(input)
                    return
                }
            }
            lanLog("inbound: no pair key matched (${keys.size} known)")
            out.write(LanCrypto.frame(LanCrypto.T_NO_MATCH, LanCrypto.randomBytes(48)))
            out.flush()
        } catch (e: Exception) {
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun receiveLoop(input: InputStream) {
        while (true) {
            val frame = readFrame(input) ?: return
            if (frame.first == LanCrypto.T_ENVELOPE) onEnvelope?.invoke(frame.second) else return
        }
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = try { input.read(buf, off, n - off) } catch (e: Exception) { return null }
            if (r < 0) return null
            off += r
        }
        return buf
    }

    private fun readFrame(input: InputStream): Pair<Byte, ByteArray>? {
        val header = readExactly(input, 5) ?: return null
        val type = header[0]
        val len = ((header[1].toInt() and 0xFF) shl 24) or ((header[2].toInt() and 0xFF) shl 16) or
            ((header[3].toInt() and 0xFF) shl 8) or (header[4].toInt() and 0xFF)
        if (len < 0 || len > 80 * 1024 * 1024) return null
        val payload = if (len == 0) ByteArray(0) else (readExactly(input, len) ?: return null)
        return Pair(type, payload)
    }
}
