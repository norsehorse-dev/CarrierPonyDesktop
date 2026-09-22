package com.carrierpony.app.nostr

import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.app.messaging.Transport
import com.carrierpony.app.messaging.TransportCapabilities
import com.carrierpony.app.messaging.TransportID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Holds the live connections to the configured Nostr relays and publishes events,
 * resolving each publish once any relay OKs it (or a timeout). Each relay runs its
 * blocking read loop on its own IO coroutine. Mirrors the iOS NostrTransportManager.
 */
@OptIn(ExperimentalEncodingApi::class)
class NostrTransportManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var clients: List<NostrRelayClient> = emptyList()
    private var readers: List<Job> = emptyList()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    @Volatile private var running = false
    @Volatile private var lastAddresses: List<String> = emptyList()
    // Relays cap the size of a single REQ filter, so a window of many hundreds of
    // #t values in one filter gets silently truncated and some mailboxes are never
    // actually subscribed. Split the window across several REQ sub ids, each a
    // bounded filter, and close any sub ids left over when the window shrinks.
    @Volatile private var activeSubCount: Int = 0
    private val subChunk = 200

    /** Called with (mailbox address, base64 content) for each sealed event received on
     *  the inbound subscription. AppModel routes it into ChatStore's ingest. */
    var onNostrEvent: ((String, String) -> Unit)? = null

    /** True when at least one relay connection has been set up. */
    val isConnected: Boolean get() = clients.isNotEmpty()

    /** Connect to the given relays, replacing any existing connections. */
    fun start(relayUrls: List<String>) {
        stop()
        running = true
        val cs = relayUrls.map { url ->
            NostrRelayClient(url).also { c ->
                c.onOk = { eventId, accepted, _ -> pending.remove(eventId)?.complete(accepted) }
                c.onEvent = { _, ev ->
                    val content = ev.optString("content", "")
                    val addr = tagT(ev)
                    if (content.isNotEmpty() && addr != null) {
                        android.util.Log.d("CPNOSTR", "RX event mailbox=${addr.takeLast(8)} relay=${c.url}")
                        onNostrEvent?.invoke(addr, content)
                    }
                }
            }
        }
        clients = cs
        readers = cs.map { c ->
            scope.launch {
                var attempt = 0
                while (running && isActive) {
                    val startedAt = System.currentTimeMillis()
                    android.util.Log.d("CPNOSTR", "CONNECT relay=${c.url} attempt=$attempt")
                    try {
                        c.connect()
                        resubscribe(c)
                        c.readLoop()
                    } catch (e: Exception) { android.util.Log.d("CPNOSTR", "CONNECT-ERR relay=${c.url} ${e.message}") }
                    if (!running || !isActive) break
                    attempt = if (System.currentTimeMillis() - startedAt >= NostrReconnect.STABLE_MS) 0
                              else minOf(attempt + 1, NostrReconnect.MAX_ATTEMPT)
                    val backoff = NostrReconnect.delayMs(attempt)
                    android.util.Log.d("CPNOSTR", "RECONNECT relay=${c.url} in ${backoff}ms attempt=$attempt")
                    try { delay(backoff) } catch (_: Exception) { break }
                }
            }
        }
    }

    /** Subscribe to our inbound mailbox addresses on every relay under a stable sub
     *  id (so a window refresh replaces the filter). Matching events arrive via
     *  onNostrEvent. A large window is one filter with many #t values. */
    fun subscribe(addresses: List<String>) {
        lastAddresses = addresses
        val cs = clients
        if (cs.isEmpty()) return
        val chunks = addresses.chunked(subChunk)
        val prevCount = activeSubCount
        activeSubCount = chunks.size
        android.util.Log.d("CPNOSTR", "SUB addresses=${addresses.size} chunks=${chunks.size} relays=${cs.size}")
        cs.forEach { c -> scope.launch {
            try {
                chunks.forEachIndexed { i, chunk -> c.subscribe("cpin$i", NostrRelayClient.mailboxFilter(chunk)) }
                for (i in chunks.size until prevCount) c.closeSub("cpin$i")
            } catch (_: Exception) {}
        } }
    }

    /** Re-send the current inbound window to one relay after it (re)connects, so the
     *  receive path survives a dropped socket. Runs on the reader's IO coroutine. */
    private fun resubscribe(c: NostrRelayClient) {
        val addrs = lastAddresses
        if (addrs.isEmpty()) return
        val chunks = addrs.chunked(subChunk)
        android.util.Log.d("CPNOSTR", "RESUB relay=${c.url} addresses=${addrs.size} chunks=${chunks.size}")
        try {
            chunks.forEachIndexed { i, chunk -> c.subscribe("cpin$i", NostrRelayClient.mailboxFilter(chunk)) }
        } catch (_: Exception) {}
    }

    private fun tagT(ev: JSONObject): String? {
        val tags = ev.optJSONArray("tags") ?: return null
        for (i in 0 until tags.length()) {
            val t = tags.optJSONArray(i) ?: continue
            if (t.length() >= 2 && t.optString(0) == "t") return t.optString(1)
        }
        return null
    }

    /** Drop all connections and fail any publish still waiting. */
    fun stop() {
        running = false
        clients.forEach { try { it.close() } catch (_: Exception) {} }
        readers.forEach { it.cancel() }
        clients = emptyList()
        readers = emptyList()
        pending.values.forEach { it.complete(false) }
        pending.clear()
    }

    /** Publish a sealed envelope to [mailbox] as a kind-1314 event with a fresh
     *  ephemeral key. True once any relay accepts it, false on timeout or if no
     *  relay is connected. The pending waiter is registered before publishing, so
     *  an OK that arrives immediately is not missed. */
    suspend fun publish(envelope: ByteArray, mailbox: String, createdAt: Long): Boolean {
        val cs = clients
        if (cs.isEmpty()) return false
        val seckey = Secp256k1.randomSecretKey()
        val event = NostrEventBuilder.build(
            seckey, createdAt, NostrKind.MAILBOX,
            listOf(listOf("t", mailbox)), Base64.Default.encode(envelope))
        val deferred = CompletableDeferred<Boolean>()
        pending[event.id] = deferred
        val json = event.json()
        android.util.Log.d("CPNOSTR", "TX mailbox=${mailbox.takeLast(8)} event=${event.id.takeLast(8)} relays=${cs.size}")
        cs.forEach { c -> scope.launch { try { c.publish(json) } catch (_: Exception) {} } }
        val accepted = try {
            withTimeout(6000L) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            pending.remove(event.id)
            false
        }
        android.util.Log.d("CPNOSTR", "TX result mailbox=${mailbox.takeLast(8)} accepted=$accepted")
        return accepted
    }
}

/**
 * A [Transport] over Nostr relays. Store-and-forward, best-effort, concurrent with
 * the relay. Delivers only to sealed-capable peers, since it needs the mailbox
 * address; a null mailbox just means no Nostr delivery. [enabled] reads the live
 * opt-in setting.
 */
class NostrTransport(
    private val manager: NostrTransportManager,
    private val enabled: () -> Boolean,
) : Transport {
    override val id = TransportID.NOSTR
    override val capabilities = TransportCapabilities(storeAndForward = true, revealsIP = true, worksOffline = false)

    override fun canReach(peer: Fingerprint): Boolean = enabled() && manager.isConnected

    override suspend fun send(envelope: ByteArray, to: Fingerprint, mailbox: String?, expiresAt: Long, silent: Boolean): Boolean {
        if (!enabled() || mailbox == null) return false
        return manager.publish(envelope, mailbox, System.currentTimeMillis() / 1000)
    }
}
