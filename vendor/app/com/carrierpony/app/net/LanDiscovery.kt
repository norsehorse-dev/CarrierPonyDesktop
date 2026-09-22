package com.carrierpony.app.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.carrierpony.app.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/**
 * LAN-direct networking (2.1). See CarrierPony-2.1-Transport-LANDirect-Design.md
 * ("Wire protocol v1") for the byte-exact contract shared with iOS.
 *
 * M2: advertise + browse `_carrierpony._tcp` with a random per-launch node id.
 * M3a (gated behind AppConfig.lanDirectEnabled, off by default): resolve nearby
 * nodes, connect, and run the identify handshake to map a random node to a known
 * contact using the per-pair sealed keys both sides share. No envelopes move yet.
 */
class LanDiscovery(context: Context) {

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

    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val nodeID = randomNodeID()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _nearby = MutableStateFlow<List<DiscoveredNode>>(emptyList())
    val nearby: StateFlow<List<DiscoveredNode>> = _nearby.asStateFlow()

    private val _reachable = MutableStateFlow<Set<String>>(emptySet())
    /** Fingerprint hexes of known contacts currently identified on the LAN. */
    val reachable: StateFlow<Set<String>> = _reachable.asStateFlow()

    /** Supplied by AppModel: active identity's contact -> per-pair LAN key. */
    @Volatile var keyProvider: (() -> Map<String, ByteArray>)? = null
    /** M3b hook: hand a received envelope to ChatStore's ingest path. Unused in M3a. */
    @Volatile var onEnvelope: ((ByteArray) -> Unit)? = null

    private var running = false
    private var serverSocket: ServerSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registeredName: String? = null
    private var acceptJob: Job? = null
    private var sweepJob: Job? = null

    private val lock = Any()
    private val seen = LinkedHashSet<String>()               // node names present
    private val endpoints = HashMap<String, Resolved>()      // node -> host:port
    private val nodeToFpr = HashMap<String, String>()        // node -> matched fpr
    private val probed = HashSet<String>()                   // "node|fpr" attempted
    private val resolveMutex = Mutex()

    private fun identifyEnabled(): Boolean =
        AppConfig.lanDirectEnabled(appContext) && keyProvider != null

    /** The Wi-Fi network to pin outbound LAN connections to. A phone with mobile
     *  data on otherwise routes these over cellular and never reaches a peer's
     *  local IP (the "only works in airplane mode" symptom). */
    private fun wifiNetwork(): Network? {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n
        }
        return null
    }

    @Synchronized
    fun start() {
        if (running) return
        running = true
        acquireMulticastLock()
        startAdvertising()
        startBrowsing()
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        acceptJob?.cancel(); acceptJob = null
        sweepJob?.cancel(); sweepJob = null
        registrationListener?.let { runCatching { nsd.unregisterService(it) } }
        registrationListener = null
        discoveryListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discoveryListener = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { multicastLock?.release() }
        multicastLock = null
        synchronized(lock) {
            seen.clear(); endpoints.clear(); nodeToFpr.clear(); probed.clear()
        }
        _nearby.value = emptyList()
        _reachable.value = emptySet()
    }

    /** Called when the LAN-direct toggle flips. */
    fun lanDidToggle() {
        if (identifyEnabled()) {
            kickSweep()
        } else {
            synchronized(lock) { nodeToFpr.clear(); probed.clear() }
            _reachable.value = emptySet()
        }
    }

    private fun acquireMulticastLock() {
        runCatching {
            val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("carrierpony-lan").apply {
                setReferenceCounted(true)
                acquire()
            }
        }
    }

    // ── Advertise + accept ──────────────────────────────────────────────

    private fun startAdvertising() {
        val server = runCatching { ServerSocket(0) }.getOrNull() ?: return
        serverSocket = server
        val port = server.localPort

        val info = NsdServiceInfo().apply {
            serviceName = nodeID
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { registeredName = info.serviceName }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = listener
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }

        acceptJob = scope.launch {
            while (running) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                launch { handleInbound(socket) }
            }
        }
    }

    // ── Browse + resolve ────────────────────────────────────────────────

    private fun startBrowsing() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                runCatching { nsd.stopServiceDiscovery(this) }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onServiceFound(info: NsdServiceInfo) {
                val name = info.serviceName ?: return
                if (name == nodeID || name == registeredName) return
                synchronized(lock) { if (!seen.add(name)) return }
                publishNearby()
                scope.launch { resolveAndStore(name, info) }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                val name = info.serviceName ?: return
                synchronized(lock) {
                    seen.remove(name)
                    endpoints.remove(name)
                    nodeToFpr.remove(name)?.let { fpr ->
                        _reachable.value = _reachable.value - fpr
                    }
                }
                publishNearby()
            }
        }
        discoveryListener = listener
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    private suspend fun resolveAndStore(name: String, info: NsdServiceInfo) {
        // NsdManager.resolveService is unreliable when called concurrently; serialize.
        resolveMutex.withLock {
            val resolved = resolveOnce(info) ?: return
            synchronized(lock) {
                if (!seen.contains(name)) return
                endpoints[name] = resolved
            }
        }
        if (identifyEnabled()) kickSweep()
    }

    private suspend fun resolveOnce(info: NsdServiceInfo): Resolved? =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val l = object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    if (cont.isActive) cont.resumeWith(Result.success(null))
                }
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host
                    val port = info.port
                    val r = if (host != null && port > 0) Resolved(host, port) else null
                    if (cont.isActive) cont.resumeWith(Result.success(r))
                }
            }
            runCatching { nsd.resolveService(info, l) }
                .onFailure { if (cont.isActive) cont.resumeWith(Result.success(null)) }
        }

    private fun publishNearby() {
        val list = synchronized(lock) {
            seen.filter { it != nodeID && it != registeredName }.sorted().map { DiscoveredNode(it) }
        }
        _nearby.value = list
    }

    // ── Identify sweep (dialer side) ────────────────────────────────────

    private fun kickSweep() {
        synchronized(lock) { if (sweepJob?.isActive == true) return }
        sweepJob = scope.launch { runSweep() }
    }

    private suspend fun runSweep() {
        if (!identifyEnabled()) return
        val keys = keyProvider?.invoke() ?: return
        val targets = synchronized(lock) { endpoints.toMap() }
        for ((name, ep) in targets) {
            if (synchronized(lock) { nodeToFpr.containsKey(name) }) continue
            for ((fpr, key) in keys) {
                val mark = "$name|$fpr"
                if (synchronized(lock) { !probed.add(mark) }) continue
                if (identify(ep, fpr, key)) {
                    synchronized(lock) { nodeToFpr[name] = fpr }
                    _reachable.value = _reachable.value + fpr
                    break
                }
            }
        }
    }

    /** Dial a node and run the identify handshake. Returns the open, authenticated
     *  socket on a verified match (caller must close it), or null. */
    private fun handshakeDial(ep: Resolved, pairKey: ByteArray): Socket? {
        val socket = Socket()
        try {
            runCatching { wifiNetwork()?.bindSocket(socket) }
            socket.connect(InetSocketAddress(ep.host, ep.port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            val out = socket.getOutputStream()
            val input = socket.getInputStream()
            val dnonce = LanCrypto.randomBytes(16)
            out.write(LanCrypto.MAGIC)
            out.write(LanCrypto.frame(LanCrypto.T_HELLO, dnonce + LanCrypto.helloTag(pairKey, dnonce)))
            out.flush()
            val frame = readFrame(input)
            if (frame == null) { socket.close(); return null }
            val (type, payload) = frame
            if (type != LanCrypto.T_HELLO_ACK || payload.size != 48) { socket.close(); return null }
            val lnonce = payload.copyOfRange(0, 16)
            val gotTag = payload.copyOfRange(16, 48)
            val want = LanCrypto.ackTag(pairKey, dnonce, lnonce)
            if (LanCrypto.constantTimeEquals(gotTag, want)) return socket
            socket.close(); return null
        } catch (e: Exception) {
            runCatching { socket.close() }
            return null
        }
    }

    /** Probe whether a node is `targetFpr` (identify only, no delivery). */
    private fun identify(ep: Resolved, targetFpr: String, pairKey: ByteArray): Boolean {
        val s = handshakeDial(ep, pairKey) ?: return false
        runCatching { s.close() }
        return true
    }

    /** True if the peer is a known contact currently identified on this LAN. */
    fun canReachLan(fprHex: String): Boolean =
        identifyEnabled() && _reachable.value.contains(fprHex.lowercase())

    /** Deliver one sealed envelope to a peer over the LAN (M3b). Best-effort:
     *  returns true only if a matched node accepted the framed envelope; the relay
     *  is the authority, so any failure just falls back to it. */
    suspend fun deliver(peerHex: String, envelope: ByteArray): Boolean = withContext(Dispatchers.IO) {
        if (!identifyEnabled()) return@withContext false
        val target = peerHex.lowercase()
        val key = keyProvider?.invoke()?.get(target) ?: return@withContext false
        val ep = synchronized(lock) {
            val node = nodeToFpr.entries.firstOrNull { it.value == target }?.key
            if (node == null) null else endpoints[node]
        } ?: return@withContext false
        val socket = handshakeDial(ep, key) ?: return@withContext false
        try {
            val out = socket.getOutputStream()
            out.write(LanCrypto.frame(LanCrypto.T_ENVELOPE, envelope))
            out.flush()
            true
        } catch (e: Exception) {
            false
        } finally {
            runCatching { socket.close() }
        }
    }

    // ── Identify (listener side) ────────────────────────────────────────

    private fun handleInbound(socket: Socket) {
        try {
            if (!identifyEnabled()) return
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
                    val ackTag = LanCrypto.ackTag(key, dnonce, lnonce)
                    out.write(LanCrypto.frame(LanCrypto.T_HELLO_ACK, lnonce + ackTag))
                    out.flush()
                    _reachable.value = _reachable.value + fpr
                    receiveLoop(input)
                    return
                }
            }
            // No match: reply an ACK-shaped random blob so length reveals nothing.
            out.write(LanCrypto.frame(LanCrypto.T_NO_MATCH, LanCrypto.randomBytes(48)))
            out.flush()
        } catch (e: Exception) {
            // Malformed or dropped; nothing to do.
        } finally {
            runCatching { socket.close() }
        }
    }

    /** After a matched handshake, read envelope frames until the peer closes,
     *  handing each to ChatStore's ingest path (dedupe by message id is free). */
    private fun receiveLoop(input: InputStream) {
        while (true) {
            val frame = readFrame(input) ?: return
            if (frame.first == LanCrypto.T_ENVELOPE) onEnvelope?.invoke(frame.second) else return
        }
    }

    // ── Framed I/O ──────────────────────────────────────────────────────

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
        val len = ((header[1].toInt() and 0xFF) shl 24) or
                  ((header[2].toInt() and 0xFF) shl 16) or
                  ((header[3].toInt() and 0xFF) shl 8) or
                  (header[4].toInt() and 0xFF)
        if (len < 0 || len > 80 * 1024 * 1024) return null
        val payload = if (len == 0) ByteArray(0) else (readExactly(input, len) ?: return null)
        return Pair(type, payload)
    }
}
