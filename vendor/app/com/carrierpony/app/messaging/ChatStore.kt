// ChatStore.kt
// CarrierPony Android
//
// The messaging heart, ported method-for-method from iOS
// Core/Messaging/ChatStore.swift: receive-and-file with dedup and expiry,
// send with local echo and optional self-copy for multi-device sync, read
// receipts and deletes as encrypted control messages to self, display-name
// exchange as encrypted control messages to peers, abuse reporting, demo-mode
// auto-replies, and per-identity JSON persistence so threads survive
// relaunch.
//
// Concurrency: iOS confines this to the main actor; here a Mutex serializes
// state mutation while relay I/O runs outside the lock, and the UI observes
// the conversations StateFlow. Polling is a coroutine on the supplied scope,
// started/stopped from the app lifecycle exactly like iOS start()/stop().

package com.carrierpony.app.messaging

import com.carrierpony.app.AppConfig
import com.carrierpony.app.DemoMode
import com.carrierpony.app.attachments.AttachmentStore
import com.carrierpony.app.crypto.CryptoEngine
import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.app.crypto.PublicKey
import com.carrierpony.app.envelope.CPN1
import com.carrierpony.app.envelope.Manifest
import com.carrierpony.app.pairing.ChannelInvite
import com.carrierpony.app.pairing.PairingSupport
import com.carrierpony.core.CPKeyInfo
import com.carrierpony.app.envelope.Threading
import com.carrierpony.app.relay.RelayClient
import com.carrierpony.app.storage.AtRest
import com.carrierpony.app.relay.RelayException
import com.carrierpony.app.relay.MailboxCrypto
import com.carrierpony.app.relay.SealedInboxMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.io.encoding.Base64
import javax.crypto.SecretKey

class ChatStore(
    private val identity: Fingerprint,
    private val relay: RelayClient,
    private val crypto: CryptoEngine,
    private val contacts: () -> List<Contact>,
    private val multiDevice: Boolean = true,
    private val defaultTTL: Long = 30L * 86_400,
    private val updatePeerName: (Fingerprint, String?) -> Unit = { _, _ -> },
    // Add or replace a contact (used by channel subscribe: the admin adds a new
    // subscriber, the subscriber adds the channel admin). Wired to ContactStore.
    private val addContact: (Contact) -> Unit = {},
    storageDir: File,
    private val scope: CoroutineScope,
    private val groupKeys: GroupKeyStore? = null,
    private val sealedKeys: SealedKeyStore? = null,
    // Completes any outstanding pairing offers this identity created, before the
    // inbox is processed, so a newly paired peer's first messages open on the
    // same pass. Wired to AppModel.sweepPendingInvites.
    private val pairingSweep: (suspend () -> Unit)? = null,
    // Optional best-effort direct transport (LAN). The relay stays authoritative;
    // this only accelerates delivery when the peer is reachable on this network.
    private val lanTransport: Transport? = null,
    private val wanTransport: Transport? = null,
    private val smsTransport: Transport? = null,
    // Optional best-effort store-and-forward transport over Nostr relays (2.3).
    // Concurrent with the relay; posts to the peer's sealed mailbox address.
    private val nostrTransport: Transport? = null,
    // Push the current inbound mailbox-address window to the Nostr transport so it
    // subscribes for events posted to us there. Wired by AppModel.
    private val nostrSubscribe: ((List<String>) -> Unit)? = null,
    // When this returns true AND the LAN delivered, the relay copy is skipped
    // (opt-in "direct only on this network"). Default: never skip.
    private val lanSkipRelay: () -> Boolean = { false },
    // The label this device registers with the relay. The phones keep the default;
    // the desktop client, which compiles this file verbatim, passes its own.
    private val deviceLabel: String = "android"
) {

    private val factory = EnvelopeFactory(crypto)
    private val transports: List<Transport> = listOf(RelayTransport(relay))

    /** WAN-direct (2.2) signaling hook: (peerHex, op, sdp, candidate) when a
     *  webrtc-offer/answer/ice control op arrives. AppModel wires it to the WebRTC
     *  layer (M2); M1 uses it for a received-count test. */
    @Volatile var onSignal: ((String, String, String?, String?) -> Unit)? = null
    private val fileURL = File(storageDir, "carrierpony-conversations-${identity.hex}.json")

    private val _conversations = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Conversation>>(emptyMap())
    val conversations: kotlinx.coroutines.flow.StateFlow<Map<String, Conversation>> = _conversations

    // Groups: the roster/epoch metadata (no secret material) and the per-group
    // message threads. Epoch keys live in groupKeys (Keychain-equivalent).
    private val _groups = kotlinx.coroutines.flow.MutableStateFlow<Map<String, ChatGroup>>(emptyMap())
    val groups: kotlinx.coroutines.flow.StateFlow<Map<String, ChatGroup>> = _groups
    private val _groupMessages = kotlinx.coroutines.flow.MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    val groupMessages: kotlinx.coroutines.flow.StateFlow<Map<String, List<ChatMessage>>> = _groupMessages
    private val groupMagic = "CPG1".toByteArray(Charsets.UTF_8)

    private val _lastError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val lastError: kotlinx.coroutines.flow.StateFlow<String?> = _lastError

    private val seenMessageIDs = mutableSetOf<String>()
    private var pollJob: Job? = null
    private val mutex = Mutex()
    private val sealedAddressMap = mutableMapOf<String, SealedRoute>()
    private var lastSealedWindowRefresh: Long = 0

    companion object {
        /** Diagnostics sink. A no-op by default (JVM tests); the app wires it
         *  to logcat so wire-level failures are visible via
         *  `adb logcat -s CarrierPony`. */
        var diagnostics: (String, Throwable?) -> Unit = { _, _ -> }
    }

    init {
        load()
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

    suspend fun start(pollIntervalSeconds: Long = 5) {
        try {
            relay.registerDevice(label = deviceLabel)
        } catch (e: Exception) {
            _lastError.value = describe(e)
        }
        sealedEnsureDevice()
        refresh()
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(pollIntervalSeconds * 1000)
                refresh()
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    /** Clear all conversations from memory and disk. Used on sign-out so a
     *  device doesn't keep plaintext message history after its identity is gone. */
    fun purge() {
        for (conversation in _conversations.value.values) {
            for (message in conversation.messages) {
                for (attachment in message.attachments) {
                    AttachmentStore.delete(attachment.localPath)
                }
            }
        }
        for (msgs in _groupMessages.value.values) {
            for (message in msgs) for (attachment in message.attachments) AttachmentStore.delete(attachment.localPath)
        }
        _conversations.value = emptyMap()
        _groups.value = emptyMap()
        _groupMessages.value = emptyMap()
        groupKeys?.purge()
        sealedKeys?.purge()
        synchronized(seenMessageIDs) { seenMessageIDs.clear() }
        fileURL.delete()
    }

    fun clearError() {
        _lastError.value = null
    }

    // ── Receive ────────────────────────────────────────────────────────

    /** Envelopes that failed to open get retried on later polls instead of
     *  being acked (consumed) immediately: the common cause is a message
     *  racing pairing — the sender's key lands seconds later and the retry
     *  succeeds. The bound stops poison envelopes from looping forever. */
    private val failedOpenCounts = mutableMapOf<String, Int>()
    private val maxOpenAttempts = 10
    private val maxPerRefresh = 50

    suspend fun refresh() = withContext(Dispatchers.Default) {
        // Complete any accepted pairing offers first, so a new contact exists
        // before their messages are opened this pass.
        try { pairingSweep?.invoke() } catch (e: Exception) { }
        // The PGP decrypt of every inbox envelope is CPU-heavy and used to run on
        // the caller's Main dispatcher while holding the mutex across the whole
        // loop, so a backlog (e.g. after the app was offline) froze the UI for
        // seconds on the first poll. Now the work runs on Default, the lock is
        // taken per envelope so a concurrent send isn't stuck behind the backlog,
        // and each pass is capped so a large queue drains over several polls
        // instead of one long block. Un-acked overflow simply returns next poll.
        try {
            val inbox = relay.inbox().take(maxPerRefresh)
            val ackIDs = mutableListOf<String>()
            for (item in inbox) {
                val envelope = try {
                    Base64.Default.decode(item.envelope)
                } catch (e: Exception) {
                    ackIDs.add(item.messageId)   // unparseable; drop it
                    continue
                }
                val isGroup = isGroupPayload(envelope)
                val opened = mutex.withLock {
                    if (isGroup) handleGroupLocked(envelope.copyOfRange(groupMagic.size, envelope.size)) else handleLocked(envelope)
                }
                if (opened) {
                    ackIDs.add(item.messageId)
                    failedOpenCounts.remove(item.messageId)
                } else {
                    val failures = (failedOpenCounts[item.messageId] ?: 0) + 1
                    if (failures >= maxOpenAttempts) {
                        diagnostics("giving up on envelope ${item.messageId} after $failures failed opens", null)
                        ackIDs.add(item.messageId)
                        failedOpenCounts.remove(item.messageId)
                    } else {
                        failedOpenCounts[item.messageId] = failures
                    }
                }
                yield()
            }
            if (ackIDs.isNotEmpty()) {
                relay.ack(ackIDs)
            }
            mutex.withLock { pruneExpiredLocked() }
        } catch (e: Exception) {
            diagnostics("refresh failed: ${e.javaClass.simpleName}: ${e.message}", e)
            _lastError.value = describe(e)
        }
        sealedMaintain()
    }

    /** @return true when the envelope was consumed (opened, or a duplicate /
     *  expired message we intentionally skip); false when opening failed and a
     *  retry on a later poll might succeed. */
    private fun handleLocked(envelope: ByteArray, viaLan: Boolean = false): Boolean {
        val incoming = try {
            factory.open(envelope)
        } catch (e: Exception) {
            // Before giving up: an un-verifiable envelope may be a self-authenticating
            // channel-subscribe from a stranger (decrypt-only, no signer to verify yet).
            if (tryChannelSubscribeLocked(envelope)) return true
            // A common cause is the sender not being paired on this device, so
            // their signature can't be verified. Surface it instead of dropping
            // the message silently — and log the precise failure for wire
            // debugging (the exception names the exact layer that refused).
            val chain = generateSequence<Throwable>(e) { it.cause }
                .joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }
            diagnostics("envelope open failed (${envelope.size} bytes): $chain", e)
            _lastError.value = "Couldn't open an incoming message. Is the sender paired on this device?"
            return false
        }
        val manifest = incoming.manifest

        if (seenMessageIDs.contains(manifest.messageID)) return true
        if (manifest.expiresAt <= now) {
            seenMessageIDs.add(manifest.messageID)
            return true
        }

        val senderIsSelf = incoming.sender == identity
        when {
            manifest.type == "group-key" -> applyGroupKeyLocked(incoming)
            senderIsSelf && manifest.type == "control" -> applyControlLocked(incoming)
            senderIsSelf && manifest.type == "message" -> {
                // Self-copy of one of my own sent messages, synced from another device.
                val peer = resolvePeer(incoming, senderIsSelf = true) ?: return true
                fileLocked(incoming, peer, MessageDirection.OUTGOING)
            }
            !senderIsSelf && manifest.type == "message" ->
                fileLocked(incoming, incoming.sender, MessageDirection.INCOMING, viaLan)
            !senderIsSelf && manifest.type == "control" ->
                // A peer may send a profile update (display name) or a sealed-sender
                // mailbox-key handshake. Both only affect our view of them.
                applyPeerControlLocked(incoming)
            else -> return true   // Others cannot control my state; rejected-by-policy is consumed, not retried.
        }
        seenMessageIDs.add(manifest.messageID)
        return true
    }

    private fun resolvePeer(incoming: IncomingMessage, senderIsSelf: Boolean): Fingerprint? {
        if (senderIsSelf) {
            // A self-copy is addressed to our own key, so manifest.to is our own
            // fingerprint — useless as a peer. Only take the shortcut when `to`
            // names someone else; otherwise fall through to the thread match,
            // which recovers the real peer from the contact list. (iOS note:
            // resolvePeer takes `to` unconditionally, so a thread first created
            // by a synced self-copy files under peer = self — same patch applies.)
            incoming.manifest.to
                ?.let { Fingerprint.from(it) }
                ?.takeIf { it != identity }
                ?.let { return it }
        }
        // Fallback: match the thread against known contacts.
        val thread = incoming.manifest.threadID
        return contacts().firstOrNull {
            Threading.pairwise(identity.hex, it.fingerprint.hex) == thread
        }?.fingerprint
    }

    private fun fileLocked(incoming: IncomingMessage, peer: Fingerprint, direction: MessageDirection, viaLan: Boolean = false) {
        val manifest = incoming.manifest
        val message = ChatMessage(
            id = manifest.messageID,
            threadID = manifest.threadID,
            peer = peer,
            direction = direction,
            text = incoming.text,
            attachments = incoming.files.map { (part, data) ->
                val mime = part.mime ?: "application/octet-stream"
                val filename = part.filename ?: "file"
                ChatMessage.Attachment(
                    id = UUID.randomUUID().toString(),
                    filename = filename,
                    mime = mime,
                    size = data.size.toLong(),
                    localPath = AttachmentStore.save(data, filename)
                )
            },
            sentAt = manifest.sentAt,
            expiresAt = manifest.expiresAt,
            isRead = direction == MessageDirection.OUTGOING,
            viaLan = viaLan
        )
        appendToConversationLocked(message, peer)
    }

    // ── Send ───────────────────────────────────────────────────────────

    /** Whether messages to this peer are sealed (roster-hidden) yet. False until
     *  the mailbox-key handshake completes both ways, or for a pre-2.0 peer.
     *  Drives the pair-state indicator so the honest limit is visible. */
    fun isSealed(peer: Fingerprint): Boolean = sealedKeys?.pair(peer.hex)?.canSend == true

    /** This account's sealed device id, for per-account gateway registration. */
    fun sealedDeviceId(): String? = sealedKeys?.deviceId()

    /** LAN-direct (2.1): per-pair LAN key for each sealed-capable contact of this
     *  identity, keyed by lowercased fingerprint hex. A pair with no peer inbound
     *  key is not sealed-capable and is omitted (it stays relay-only). Lets a
     *  nearby random node be matched to a known contact without revealing anything
     *  to a non-contact. Must match iOS ChatStore.lanContactKeys byte-for-byte. */
    fun lanContactKeys(): Map<String, ByteArray> {
        val sk = sealedKeys ?: return emptyMap()
        val out = HashMap<String, ByteArray>()
        for (c in contacts()) {
            val st = sk.pair(c.fingerprint.hex) ?: continue
            val peer = st.peerInboundKey ?: continue
            out[c.fingerprint.hex.lowercase()] = com.carrierpony.app.net.LanCrypto.pairKey(st.myInboundKey, peer)
        }
        return out
    }

    suspend fun send(
        text: String?,
        attachments: List<OutgoingMessage.Attachment> = emptyList(),
        to: Contact,
        ttl: Long? = null
    ) {
        val totalBytes = attachments.sumOf { it.data.size.toLong() }
        if (totalBytes > AppConfig.maxAttachmentBytes) {
            val mb = AppConfig.maxAttachmentBytes / (1024 * 1024)
            _lastError.value = "That attachment is too large to send (limit $mb MB)."
            return
        }

        val threadID = Threading.pairwise(identity.hex, to.fingerprint.hex)
        val expiresAt = now + (ttl ?: defaultTTL)
        val messageID = UUID.randomUUID().toString().uppercase()
        val outgoing = OutgoingMessage(threadID = threadID, text = text, attachments = attachments, expiresAt = expiresAt)

        // Persist attachment bytes to disk for the local echo (kept out of the
        // conversation store and out of memory).
        val storedAttachments = attachments.map { attachment ->
            ChatMessage.Attachment(
                id = UUID.randomUUID().toString(),
                filename = attachment.filename,
                mime = attachment.mime,
                size = attachment.data.size.toLong(),
                localPath = AttachmentStore.save(attachment.data, attachment.filename)
            )
        }

        // Show immediately on this device.
        val local = ChatMessage(
            id = messageID,
            threadID = threadID,
            peer = to.fingerprint,
            direction = MessageDirection.OUTGOING,
            text = text,
            attachments = storedAttachments,
            sentAt = now,
            expiresAt = expiresAt,
            isRead = true
        )
        mutex.withLock {
            seenMessageIDs.add(messageID)
            appendToConversationLocked(local, to.fingerprint, peerName = to.name)
        }

        if (to.fingerprint == DemoMode.fingerprint) {
            demoAutoReply(hadAttachment = attachments.isNotEmpty())
            return
        }

        try {
            val envelope = factory.build(outgoing, to.publicKey, messageID)
            deliverToPeer(envelope, to.fingerprint, expiresAt, silent = false)
            if (multiDevice) {
                sendSelfCopy(outgoing, messageID)
            }
        } catch (e: Exception) {
            _lastError.value = describe(e)
        }
    }

    private suspend fun sendSelfCopy(outgoing: OutgoingMessage, messageID: String) {
        val selfKey = ownPublicKey() ?: return
        val envelope = factory.build(outgoing, selfKey, messageID)
        relay.send(envelope, identity, outgoing.expiresAt, silent = true)
    }

    // ── Deletion (QoL) ─────────────────────────────────────────────────
    //
    // Ported from iOS ChatStore. Delete-for-me is local only. Delete-for-
    // everyone sends the "delete" control op to the peer (they erase their copy
    // if their build handles it) plus a self-copy so this identity's other
    // devices follow, then deletes locally. Clearing history and deleting a
    // conversation are local only and keep the contact paired.

    /** Remove a single message on this device only. */
    suspend fun deleteLocally(messageID: String) {
        mutex.withLock { deleteMessageLocked(messageID) }
    }

    /** Delete a message for everyone: tell the peer, sync our own devices, then
     *  remove it here. The peer only erases their copy if their build handles the
     *  incoming "delete" control op. */
    suspend fun deleteForEveryone(messageID: String, to: Contact) {
        sendControlToPeer(op = "delete", targetMessageID = messageID, to = to)
        if (multiDevice) {
            val threadID = Threading.pairwise(identity.hex, to.fingerprint.hex)
            emitControl(op = "delete", targetMessageID = messageID, threadID = threadID)
        }
        mutex.withLock { deleteMessageLocked(messageID) }
    }

    /** Clear every message in a thread on this device, keeping the contact and
     *  pairing intact. Local only. */
    suspend fun clearHistory(threadID: String) {
        mutex.withLock {
            val conversation = _conversations.value[threadID] ?: return@withLock
            for (message in conversation.messages) {
                for (attachment in message.attachments) AttachmentStore.delete(attachment.localPath)
            }
            _conversations.value = _conversations.value + (threadID to conversation.copy(messages = emptyList()))
            persistLocked()
        }
    }

    /** Remove a whole conversation (and its messages) from this device. The
     *  contact stays paired, unlike unpairing. */
    suspend fun deleteConversation(threadID: String) {
        mutex.withLock {
            val conversation = _conversations.value[threadID] ?: return@withLock
            for (message in conversation.messages) {
                for (attachment in message.attachments) AttachmentStore.delete(attachment.localPath)
            }
            _conversations.value = _conversations.value - threadID
            persistLocked()
        }
    }

    /** Send a control op (e.g. "delete") to the peer over the relay, silent so
     *  it never buzzes them. */
    private suspend fun sendControlToPeer(op: String, targetMessageID: String, to: Contact) {
        val threadID = Threading.pairwise(identity.hex, to.fingerprint.hex)
        val body = ControlOp(op = op, targetMessageID = targetMessageID, at = now)
        val controlMessage = OutgoingMessage(
            threadID = threadID,
            text = null,
            attachments = listOf(OutgoingMessage.Attachment("control", "application/json", body.encoded())),
            expiresAt = now + 86_400
        )
        try {
            val messageID = UUID.randomUUID().toString().uppercase()
            mutex.withLock { seenMessageIDs.add(messageID) }
            val envelope = buildControl(controlMessage, to.publicKey, messageID)
            deliverToPeer(envelope, to.fingerprint, controlMessage.expiresAt, silent = true)
        } catch (e: Exception) {
            _lastError.value = describe(e)
        }
    }

    /** WAN-direct (2.2): send a signaling control op over the sealed relay path,
     *  like any other control op, so the relay sees only opaque mailbox traffic. */
    private suspend fun sendSignal(op: String, to: Contact, sdp: String?, candidate: String?) {
        val threadID = Threading.pairwise(identity.hex, to.fingerprint.hex)
        val body = ControlOp(op = op, targetMessageID = "", at = now, sdp = sdp, candidate = candidate)
        val controlMessage = OutgoingMessage(
            threadID = threadID,
            text = null,
            attachments = listOf(OutgoingMessage.Attachment("control", "application/json", body.encoded())),
            expiresAt = now + 86_400
        )
        try {
            val messageID = UUID.randomUUID().toString().uppercase()
            mutex.withLock { seenMessageIDs.add(messageID) }
            val envelope = buildControl(controlMessage, to.publicKey, messageID)
            deliverToPeer(envelope, to.fingerprint, controlMessage.expiresAt, silent = true)
        } catch (e: Exception) {
            // Background signaling op; retried by the WebRTC layer, no banner.
        }
    }

    /** WAN-direct (2.2) public signaling send, addressed by peer fingerprint hex. */
    suspend fun sendWanSignal(op: String, peerHex: String, sdp: String?, candidate: String?) {
        val contact = contacts().firstOrNull { it.fingerprint.hex.lowercase() == peerHex.lowercase() } ?: return
        sendSignal(op, contact, sdp, candidate)
    }

    /** M1 test only: exercise the signaling round-trip with a synthetic offer + ice
     *  to every sealed-capable contact. Replaced in M2 by real WebRTC offers. */
    suspend fun sendTestWanSignals() {
        val sk = sealedKeys ?: return
        for (contact in contacts()) {
            if (sk.pair(contact.fingerprint.hex)?.peerInboundKey == null) continue
            sendSignal("webrtc-offer", contact, sdp = "m1-test-offer", candidate = null)
            sendSignal("webrtc-ice", contact, sdp = null, candidate = "m1-test-candidate")
        }
    }

    // ── Reporting ──────────────────────────────────────────────────────

    /** Text of recent messages received from a peer, oldest-first, for an abuse
     *  report. Only incoming text is included; attachment bytes are never shared. */
    fun recentIncomingText(peer: Fingerprint, limit: Int = 20): String? {
        val texts = _conversations.value.values
            .flatMap { it.messages }
            .filter { it.peer == peer && it.direction == MessageDirection.INCOMING }
            .sortedByDescending { it.sentAt }
            .take(limit)
            .mapNotNull { it.text?.trim()?.takeIf(String::isNotEmpty) }
        return if (texts.isEmpty()) null else texts.reversed().joinToString("\n")
    }

    suspend fun submitReport(contact: Contact, category: String, description: String?, includeContent: Boolean) {
        val content = if (includeContent) recentIncomingText(contact.fingerprint) else null
        relay.submitReport(
            reportedFingerprint = contact.fingerprint,
            category = category,
            description = description,
            content = content
        )
    }

    // ── Demo mode (App Review) ─────────────────────────────────────────

    /** Inject a message that appears to come from the simulated demo contact. */
    suspend fun injectDemoIncoming(text: String, attachment: ChatMessage.Attachment? = null) {
        val threadID = Threading.pairwise(identity.hex, DemoMode.fingerprint.hex)
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            threadID = threadID,
            peer = DemoMode.fingerprint,
            direction = MessageDirection.INCOMING,
            text = text,
            attachments = attachment?.let { listOf(it) } ?: emptyList(),
            sentAt = now,
            expiresAt = now + defaultTTL,
            isRead = false
        )
        mutex.withLock {
            appendToConversationLocked(message, DemoMode.fingerprint, peerName = DemoMode.peerName)
        }
    }

    private suspend fun demoAutoReply(hadAttachment: Boolean) {
        delay(1200)
        if (hadAttachment) {
            val data = "This file came back from the CarrierPony demo contact. It was delivered the same encrypted way your file was."
                .toByteArray(Charsets.UTF_8)
            val attachment = ChatMessage.Attachment(
                id = UUID.randomUUID().toString(),
                filename = "demo-reply.txt",
                mime = "text/plain",
                size = data.size.toLong(),
                localPath = AttachmentStore.save(data, "demo-reply.txt")
            )
            injectDemoIncoming(
                text = "Got your file. It was encrypted on your device and decrypted here. Here's one back so you can see file transfer both ways.",
                attachment = attachment
            )
        } else {
            injectDemoIncoming(
                text = "Received and decrypted on-device. In a real conversation this reply would come from the person you paired with. Try attaching a file next, or open the ••• menu to see Report and Block."
            )
        }
    }

    // ── Read / control ─────────────────────────────────────────────────

    suspend fun markRead(threadID: String) {
        val unread = mutex.withLock {
            val conversation = _conversations.value[threadID] ?: return
            val unread = conversation.messages.filter { it.direction == MessageDirection.INCOMING && !it.isRead }
            if (unread.isEmpty()) return
            val updated = conversation.copy(messages = conversation.messages.map { it.copy(isRead = true) })
            _conversations.value = _conversations.value + (threadID to updated)
            persistLocked()
            unread
        }
        if (multiDevice) {
            for (message in unread) {
                emitControl(op = "read", targetMessageID = message.id, threadID = threadID)
            }
        }
    }

    private fun applyControlLocked(incoming: IncomingMessage) {
        val control = controlPayload(incoming) ?: return
        when (control.op) {
            "read" -> setReadLocked(control.targetMessageID)
            "delete" -> deleteMessageLocked(control.targetMessageID)
        }
    }

    // ── Profile (display name exchange) ────────────────────────────────

    /** Send my current display name to a contact as an encrypted control message.
     *  If the name is null/empty (anonymous), nothing is sent. */
    suspend fun sendProfile(name: String?, to: Contact) {
        val displayName = name?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val threadID = Threading.pairwise(identity.hex, to.fingerprint.hex)
        val body = ControlOp(op = "profile", targetMessageID = "", at = now, name = displayName)
        val controlMessage = OutgoingMessage(
            threadID = threadID,
            text = null,
            attachments = listOf(OutgoingMessage.Attachment("control", "application/json", body.encoded())),
            expiresAt = now + 86_400
        )
        try {
            val messageID = UUID.randomUUID().toString().uppercase()
            mutex.withLock { seenMessageIDs.add(messageID) }
            val envelope = buildControl(controlMessage, to.publicKey, messageID)
            deliverToPeer(envelope, to.fingerprint, controlMessage.expiresAt, silent = true)
        } catch (e: Exception) {
            _lastError.value = describe(e)
        }
    }

    private fun applyContactProfileLocked(incoming: IncomingMessage) {
        val control = controlPayload(incoming) ?: return
        if (control.op != "profile") return
        val name = control.name?.trim()?.takeIf { it.isNotEmpty() }
        // Update the stored contact (source of truth for names).
        updatePeerName(incoming.sender, name)
        // Reflect immediately on any existing thread.
        val threadID = Threading.pairwise(identity.hex, incoming.sender.hex)
        _conversations.value[threadID]?.let { conversation ->
            _conversations.value = _conversations.value +
                (threadID to conversation.copy(peerName = name ?: conversation.peerName))
            persistLocked()
        }
    }

    private suspend fun emitControl(op: String, targetMessageID: String, threadID: String) {
        val selfKey = ownPublicKey() ?: return
        val body = ControlOp(op = op, targetMessageID = targetMessageID, at = now)
        val controlMessage = OutgoingMessage(
            threadID = threadID,
            text = null,
            attachments = listOf(OutgoingMessage.Attachment("control", "application/json", body.encoded())),
            expiresAt = now + 86_400
        )
        try {
            val messageID = UUID.randomUUID().toString().uppercase()
            mutex.withLock { seenMessageIDs.add(messageID) }
            val envelope = buildControl(controlMessage, selfKey, messageID)
            relay.send(envelope, identity, controlMessage.expiresAt, silent = true)
        } catch (e: Exception) {
            _lastError.value = describe(e)
        }
    }

    private fun buildControl(message: OutgoingMessage, recipient: PublicKey, messageID: String): ByteArray {
        // A control message is a normal envelope with manifest.type == "control".
        val parts = message.attachments.map { it.data }
        val meta = listOf(Manifest.Part(
            kind = "control",
            mime = "application/json",
            size = parts.firstOrNull()?.size?.toLong()
        ))
        val manifest = Manifest(
            v = 1,
            type = "control",
            messageID = messageID,
            threadID = message.threadID,
            to = recipient.fingerprint.hex,
            sentAt = now,
            expiresAt = message.expiresAt,
            parts = meta
        )
        val container = CPN1.encode(manifest.encoded(), parts)
        return crypto.signAndEncrypt(container, recipient)
    }

    class ControlOp(
        val op: String,
        val targetMessageID: String,
        val at: Long,
        val name: String? = null,
        val mailboxKey: String? = null,
        // WAN-direct (2.2) signaling: SDP for webrtc-offer/answer, candidate for webrtc-ice.
        val sdp: String? = null,
        val candidate: String? = null,
        // Broadcast-channel subscribe/unsubscribe (2.x Phase 2).
        val channelId: String? = null,
        val pubkey: String? = null,     // subscriber armored public key (channel-subscribe)
        val token: String? = null       // optional invite token (gated channels)
    ) {
        fun encoded(): ByteArray {
            val json = JSONObject()
            json.put("op", op)
            json.put("target_message_id", targetMessageID)
            json.put("at", at)
            if (name != null) json.put("name", name)
            if (mailboxKey != null) json.put("mailbox_key", mailboxKey)
            if (sdp != null) json.put("sdp", sdp)
            if (candidate != null) json.put("candidate", candidate)
            if (channelId != null) json.put("channel_id", channelId)
            if (pubkey != null) json.put("pubkey", pubkey)
            if (token != null) json.put("token", token)
            return json.toString().toByteArray(Charsets.UTF_8)
        }

        companion object {
            fun decode(data: ByteArray): ControlOp? = try {
                val json = JSONObject(String(data, Charsets.UTF_8))
                ControlOp(
                    op = json.getString("op"),
                    targetMessageID = json.optString("target_message_id"),
                    at = json.optLong("at"),
                    name = json.optString("name").takeIf { it.isNotEmpty() },
                    mailboxKey = json.optString("mailbox_key").takeIf { it.isNotEmpty() },
                    sdp = json.optString("sdp").takeIf { it.isNotEmpty() },
                    candidate = json.optString("candidate").takeIf { it.isNotEmpty() },
                    channelId = json.optString("channel_id").takeIf { it.isNotEmpty() },
                    pubkey = json.optString("pubkey").takeIf { it.isNotEmpty() },
                    token = json.optString("token").takeIf { it.isNotEmpty() }
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun controlPayload(incoming: IncomingMessage): ControlOp? {
        val payload = incoming.files.firstOrNull()?.second
            ?: incoming.text?.toByteArray(Charsets.UTF_8)
            ?: return null
        return ControlOp.decode(payload)
    }

    // ── Sealed sender wiring ───────────────────────────────────────────
    //
    // Roster-hiding delivery layered onto the 1:1 path. A pair becomes sealed
    // once both sides exchange inbound mailbox keys (a control op over the legacy
    // path, which also upgrades pre-2.0 pairs). Sends then address an opaque
    // rotating mailbox; the inbox is polled by mailbox and mapped back to the
    // peer. Self-copies and groups stay legacy for now. Mirrors iOS ChatStore.

    /** Re-register the sealed device with the relay (e.g. once the gateway wake
     *  token is obtained), so its wake_token is attached without waiting for the
     *  next app start. Safe to call repeatedly. */
    suspend fun sealedReregisterDevice() {
        if (sealedKeys == null) return
        sealedEnsureDevice()
    }

    private suspend fun sealedEnsureDevice() {
        val sk = sealedKeys ?: return
        try {
            relay.sealedRegisterDevice(sk.deviceId(), sk.deviceKey(), sk.wakeToken())
        } catch (e: RelayException) {
            // 401 means the relay already first-claimed this sealed device_id
            // under a key we no longer hold (Keystore wipe, or a partial backup
            // restore). Mint a fresh sealed identity and register it; the old one
            // is orphaned and reaped by the relay. Clear the stale window map so
            // it rebuilds under the new id next refresh.
            if (e.status == 401) {
                sk.rotateDeviceIdentity()
                sealedAddressMap.clear()
                try {
                    relay.sealedRegisterDevice(sk.deviceId(), sk.deviceKey(), sk.wakeToken())
                } catch (e2: Exception) {
                    // best-effort; the next refresh retries
                }
            }
        } catch (e: Exception) {
            // best-effort; sealed delivery just waits for the next refresh
        }
    }

    private suspend fun sealedMaintain() {
        if (sealedKeys == null) return
        if (sealedAddressMap.isEmpty() || (now - lastSealedWindowRefresh) > 30) {
            sealedRefreshWindows()
            lastSealedWindowRefresh = now
        }
        sealedPollInbox()
    }

    private suspend fun deliverToPeer(envelope: ByteArray, to: Fingerprint, expiresAt: Long, silent: Boolean) {
        // The direct transports (LAN, then WAN) that can reach this peer right now.
        val directs = listOfNotNull(lanTransport, wanTransport, smsTransport).filter { it.canReach(to) }

        // Direct-only mode (opt-in): deliver over a direct path and skip the relay
        // entirely. Fall back to the relay only if no direct path actually delivered,
        // so a message is never lost. The user accepted the cost (the peer's other
        // devices and offline delivery won't get it) by enabling it.
        if (lanSkipRelay() && directs.isNotEmpty()) {
            for (d in directs) {
                val ok = try { d.send(envelope, to, null, expiresAt, silent) } catch (e: Exception) { false }
                if (ok) return
            }
            // No direct path delivered: fall back to the relay, computing the sealed
            // address now so the counter only advances when a mailbox send happens.
            val mailbox = nextSealedAddress(to)
            for (transport in transports) {
                if (transport.canReach(to) && transport.send(envelope, to, mailbox, expiresAt, silent)) return
            }
            return
        }

        // The relay is authoritative (store-and-forward, the peer's other devices,
        // offline delivery). The direct transports and Nostr run concurrently as
        // best-effort accelerators on the store scope: all fire, the receiver dedupes
        // by message id, and a relay failure is tolerated only if an accelerator
        // delivered. On relay success the send returns without waiting (a WAN ARQ or a
        // Nostr OK can take seconds); they keep running in the background. The sealed
        // mailbox address is computed once here and shared by the relay and Nostr, so
        // both post to the same slot and the send counter advances a single step.
        val mailbox = nextSealedAddress(to)
        android.util.Log.d("CPSEAL", "TX to=${to.hex.takeLast(8)} mailbox=${mailbox?.takeLast(6) ?: "NULL-legacy"} newSendCtr=${sealedKeys?.pair(to.hex)?.sendCounter} peerKey=${if (sealedKeys?.pair(to.hex)?.peerInboundKey != null) "set" else "MISSING"}")
        val directDeferred = scope.async {
            for (d in directs) {
                val ok = try { d.send(envelope, to, null, expiresAt, silent) } catch (e: Exception) { false }
                if (ok) return@async true
            }
            false
        }
        val nostrDeferred: kotlinx.coroutines.Deferred<Boolean>? =
            nostrTransport?.takeIf { it.canReach(to) }?.let { n ->
                scope.async { try { n.send(envelope, to, mailbox, expiresAt, silent) } catch (e: Exception) { false } }
            }
        var relayError: Exception? = null
        var relayOk = false
        try {
            for (transport in transports) {
                if (transport.canReach(to) && transport.send(envelope, to, mailbox, expiresAt, silent)) { relayOk = true; break }
            }
        } catch (e: Exception) {
            relayError = e
        }
        android.util.Log.d("CPSEAL", "TX relayOk=$relayOk relayErr=${relayError?.message}")
        if (relayOk) return
        val directOk = directDeferred.await()
        val nostrOk = nostrDeferred?.await() ?: false
        if (relayError != null && !directOk && !nostrOk) throw relayError
    }

    /** Next sealed mailbox address for a peer, advancing the shared send counter
     *  once. Null when the pair is not sealed-capable yet (no peer inbound key), so
     *  callers fall back to the legacy fingerprint-routed relay send. */
    private fun nextSealedAddress(to: Fingerprint): String? {
        val sk = sealedKeys ?: return null
        val st = sk.pair(to.hex) ?: return null
        val peerKey = st.peerInboundKey ?: return null
        val address = com.carrierpony.app.relay.MailboxCrypto.address(peerKey, st.sendCounter)
        sk.setPair(to.hex, SealedPairState(st.myInboundKey, st.peerInboundKey, st.sendCounter + 1, st.receiveHigh, st.sharedMine))
        return address
    }

    /** Ingest one sealed envelope received over a direct transport (LAN), running
     *  it through the same open path as a relay-fetched envelope. Dedupe by
     *  message id is automatic, so a message that also arrives via the relay is
     *  filed once. No ack: there is no relay message to acknowledge. */
    suspend fun ingestLanEnvelope(envelope: ByteArray) = withContext(Dispatchers.Default) {
        val isGroup = isGroupPayload(envelope)
        mutex.withLock {
            if (isGroup) handleGroupLocked(envelope.copyOfRange(groupMagic.size, envelope.size))
            else handleLocked(envelope, viaLan = true)
        }
        Unit
    }

    /** Ingest one sealed event received over Nostr. Its mailbox address (the event's
     *  `t` tag) maps to the peer/counter through the same window the relay uses, so it
     *  opens through the normal path and advances the high-water mark like a relay-
     *  fetched item, minus the ack. Dedupe by message id is automatic, so a copy that
     *  also arrives via the relay is filed once. */
    suspend fun ingestNostrEnvelope(mailbox: String, content: String) = withContext(Dispatchers.Default) {
        val sk = sealedKeys ?: return@withContext
        val mapped = sealedAddressMap[mailbox] ?: run {
            android.util.Log.d("CPNOSTR", "INGEST mailbox=${mailbox.takeLast(8)} mapped=no")
            return@withContext
        }
        val envelope = try { Base64.Default.decode(content) } catch (e: Exception) { return@withContext }
        val isGroup = isGroupPayload(envelope)
        val (opened, newlyFiled) = mutex.withLock {
            val before = seenMessageIDs.size
            val ok = if (isGroup) handleGroupLocked(envelope.copyOfRange(groupMagic.size, envelope.size)) else handleLocked(envelope)
            ok to (seenMessageIDs.size > before)
        }
        android.util.Log.d("CPNOSTR", "INGEST mailbox=${mailbox.takeLast(8)} opened=$opened newlyFiled=$newlyFiled")
        if (opened) {
            when (mapped) {
                is SealedRoute.PairRoute -> {
                    val st = sk.pair(mapped.peer.hex)
                    if (st != null && mapped.counter > st.receiveHigh) {
                        sk.setPair(mapped.peer.hex, SealedPairState(st.myInboundKey, st.peerInboundKey, st.sendCounter, mapped.counter, st.sharedMine))
                    }
                }
                is SealedRoute.GroupRoute -> {
                    val gst = sk.group(mapped.groupID)
                    if (gst != null) {
                        val cur = gst.recvHighs[mapped.sender.hex] ?: 0L
                        if (mapped.counter > cur) {
                            gst.recvHighs[mapped.sender.hex] = mapped.counter
                            sk.setGroup(mapped.groupID, gst)
                        }
                    }
                }
            }
        }
        Unit
    }

    /** Recover sealed delivery when a pair has desynced (a peer reinstalled before the
     *  self-heal shipped, so counters drifted past the window with no key change left to
     *  trip the realign). Regenerate our inbound key for every contact and reset our
     *  counters; peers see the new key and self-heal to zero, so delivery resumes with no
     *  re-pairing and no lost identity. */
    suspend fun resyncSealedKeys() {
        val sk = sealedKeys ?: return
        for (contact in contacts()) {
            if (contact.fingerprint == DemoMode.fingerprint) continue
            val st = sk.pair(contact.fingerprint.hex) ?: SealedPairState(sk.newPairKey(), null, 0, 0, false)
            sk.setPair(contact.fingerprint.hex, SealedPairState(sk.newPairKey(), st.peerInboundKey, 0, 0, false))
        }
        android.util.Log.d("CPSEAL", "RESYNC regenerated my inbound keys and zeroed my send counters for all contacts")
        sealedAddressMap.clear()
        sealedRefreshWindows()
        sealedPollInbox()
    }

    private suspend fun sealedRefreshWindows() {
        val sk = sealedKeys ?: return
        val window = 64L
        val expiresAt = now + defaultTTL
        val batch = JSONArray()
        val map = mutableMapOf<String, SealedRoute>()

        for (contact in contacts()) {
            if (contact.fingerprint == DemoMode.fingerprint) continue
            var st = sk.pair(contact.fingerprint.hex)
                ?: SealedPairState(sk.newPairKey(), null, 0, 0, false)
            if (!st.sharedMine) {
                if (sealedSendMyKey(st.myInboundKey, contact)) {
                    st = SealedPairState(st.myInboundKey, st.peerInboundKey, st.sendCounter, st.receiveHigh, true)
                }
            }
            sk.setPair(contact.fingerprint.hex, st)

            val low = if (st.receiveHigh > window) st.receiveHigh - window else 0L
            val high = st.receiveHigh + window
            var m = low
            while (m <= high) {
                val address = MailboxCrypto.address(st.myInboundKey, m)
                batch.put(JSONObject().put("mailbox", address).put("expires_at", expiresAt))
                map[address] = SealedRoute.PairRoute(contact.fingerprint, m)
                m += 1
            }
        }

        // Group streams: an inbound window per co-member per group, so a group
        // fan-out to my per-pair group address is delivered and mapped back.
        val gk = groupKeys
        if (gk != null) {
            for ((groupID, group) in _groups.value) {
                val secret = gk.key(groupID, group.epoch) ?: continue
                val epochKey = secret.encoded
                var gst = sk.group(groupID) ?: SealedGroupState(group.epoch, mutableMapOf(), mutableMapOf())
                if (gst.epoch != group.epoch) gst = SealedGroupState(group.epoch, mutableMapOf(), mutableMapOf())
                sk.setGroup(groupID, gst)
                for (member in group.members) {
                    if (member.fingerprint == identity) continue
                    val hw = gst.recvHighs[member.fingerprint.hex] ?: 0L
                    val low = if (hw > window) hw - window else 0L
                    val high = hw + window
                    var m = low
                    while (m <= high) {
                        val address = MailboxCrypto.groupAddress(epochKey, member.fingerprint.hex, identity.hex, m)
                        batch.put(JSONObject().put("mailbox", address).put("expires_at", expiresAt))
                        map[address] = SealedRoute.GroupRoute(groupID, member.fingerprint, m)
                        m += 1
                    }
                }
            }
        }
        sealedAddressMap.clear()
        sealedAddressMap.putAll(map)
        nostrSubscribe?.invoke(map.keys.toList())

        var i = 0
        while (i < batch.length()) {
            val chunk = JSONArray()
            var j = i
            val end = minOf(i + 500, batch.length())
            while (j < end) { chunk.put(batch.getJSONObject(j)); j++ }
            try {
                relay.sealedRegisterMailboxes(sk.deviceId(), sk.deviceKey(), chunk)
            } catch (e: Exception) {
                break
            }
            i += 500
        }
    }

    private suspend fun sealedSendMyKey(key: ByteArray, to: Contact): Boolean {
        val threadID = Threading.pairwise(identity.hex, to.fingerprint.hex)
        val body = ControlOp(op = "mailbox-key", targetMessageID = "", at = now, mailboxKey = Base64.Default.encode(key))
        val controlMessage = OutgoingMessage(
            threadID = threadID,
            text = null,
            attachments = listOf(OutgoingMessage.Attachment("control", "application/json", body.encoded())),
            expiresAt = now + 7 * 86_400
        )
        return try {
            val messageID = UUID.randomUUID().toString().uppercase()
            mutex.withLock { seenMessageIDs.add(messageID) }
            val envelope = buildControl(controlMessage, to.publicKey, messageID)
            relay.send(envelope, to.fingerprint, controlMessage.expiresAt, silent = true)
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun sealedPollInbox() {
        val sk = sealedKeys ?: return
        try {
            val messages = relay.sealedInbox(sk.deviceId(), sk.deviceKey()).take(maxPerRefresh)
            val ackIDs = mutableListOf<String>()
            for (item in messages) {
                val mapped = sealedAddressMap[item.mailbox]
                if (mapped == null) { yield(); continue }
                val envelope = try {
                    Base64.Default.decode(item.envelope)
                } catch (e: Exception) {
                    ackIDs.add(item.messageId); yield(); continue
                }
                val isGroup = isGroupPayload(envelope)
                val opened = mutex.withLock {
                    if (isGroup) handleGroupLocked(envelope.copyOfRange(groupMagic.size, envelope.size)) else handleLocked(envelope)
                }
                if (opened) {
                    ackIDs.add(item.messageId)
                    when (mapped) {
                        is SealedRoute.PairRoute -> {
                            val st = sk.pair(mapped.peer.hex)
                            if (st != null && mapped.counter > st.receiveHigh) {
                                sk.setPair(mapped.peer.hex, SealedPairState(st.myInboundKey, st.peerInboundKey, st.sendCounter, mapped.counter, st.sharedMine))
                            }
                        }
                        is SealedRoute.GroupRoute -> {
                            val gst = sk.group(mapped.groupID)
                            if (gst != null) {
                                val cur = gst.recvHighs[mapped.sender.hex] ?: 0L
                                if (mapped.counter > cur) {
                                    gst.recvHighs[mapped.sender.hex] = mapped.counter
                                    sk.setGroup(mapped.groupID, gst)
                                }
                            }
                        }
                    }
                }
                yield()
            }
            if (ackIDs.isNotEmpty()) {
                relay.sealedAck(sk.deviceId(), sk.deviceKey(), ackIDs)
            }
        } catch (e: Exception) {
            // best-effort; a sealed failure must not break legacy delivery
        }
    }

    private fun applyPeerControlLocked(incoming: IncomingMessage) {
        val control = controlPayload(incoming) ?: return
        when (control.op) {
            "mailbox-key" -> {
                val b64 = control.mailboxKey ?: return
                val key = try { Base64.Default.decode(b64) } catch (e: Exception) { return }
                sealedApplyPeerKeyLocked(incoming.sender, key)
            }
            "channel-unsubscribe" -> {
                val cid = control.channelId ?: return
                val group = _groups.value[cid] ?: return
                if (!group.isAdmin(identity)) return
                if (group.members.none { it.fingerprint == incoming.sender }) return
                _groups.value = _groups.value + (cid to group.copy(
                    members = group.members.filter { it.fingerprint != incoming.sender }))
                persistLocked()
                android.util.Log.d("CPCHAN", "unsubscribe-recv channel=${cid.takeLast(8)} from=${incoming.sender.hex.takeLast(8)}")
            }
            "channel-subscribe" -> {
                // Also reached here (not only via the decrypt-only bootstrap) when the
                // subscriber is already a known contact, so their envelope verifies and
                // opens normally instead of failing.
                val cid = control.channelId ?: return
                val pk = control.pubkey ?: return
                applyChannelSubscribeLocked(cid, incoming.sender, pk, control.token)
            }
            "webrtc-offer", "webrtc-answer", "webrtc-ice" ->
                onSignal?.invoke(incoming.sender.hex, control.op, control.sdp, control.candidate)
            else -> applyContactProfileLocked(incoming)
        }
    }

    // ── Broadcast channel subscribe links (2.x Phase 2) ────────────────
    //
    // A channel invite is a reusable CPCHAN1 link/QR the admin shares publicly.
    // A stranger who opens it seals a self-authenticating channel-subscribe to
    // the admin (decrypt-only on the admin side, since there is no pairing yet;
    // the payload carries the subscriber's own key and its fingerprint must hash
    // to it). The admin auto-accepts, adds the subscriber as a non-admin member,
    // and returns the admins-only channel key. No epoch rekey on join.

    /** Admin-only: a shareable CPCHAN1 invite string for a channel I broadcast. */
    fun channelInvite(groupID: String, token: String? = null): String? {
        val group = _groups.value[groupID] ?: return null
        if (!group.isChannel || !group.isAdmin(identity)) return null
        val mine = ownPublicKey() ?: return null
        return ChannelInvite.create(groupID, identity, mine.armored, token, group.name).encoded()
    }

    /** Subscriber side: subscribe from an invite. Adds the admin as a contact so
     *  we can verify the posts it sends back, then seals a channel-subscribe to
     *  it. The channel appears once the admin returns its key. */
    suspend fun subscribeToChannel(invite: ChannelInvite): Boolean {
        val admin = PairingSupport.consistentContact(invite.f, invite.k, TrustLevel.UNVERIFIED) ?: return false
        if (admin.fingerprint == identity) return false
        // Only add the admin if we do not already know them; never overwrite an
        // existing contact (that renames the existing conversation).
        if (contacts().none { it.fingerprint == admin.fingerprint }) addContact(admin)
        val mine = ownPublicKey() ?: return false
        val op = ControlOp(op = "channel-subscribe", targetMessageID = "", at = now,
            channelId = invite.c, pubkey = mine.armored, token = invite.t)
        val outgoing = OutgoingMessage(
            threadID = invite.c, text = null,
            attachments = listOf(OutgoingMessage.Attachment("control", "application/json", op.encoded())),
            expiresAt = now + defaultTTL
        )
        return try {
            val messageID = UUID.randomUUID().toString().uppercase()
            mutex.withLock { seenMessageIDs.add(messageID) }
            val envelope = buildControl(outgoing, admin.publicKey, messageID)
            // Deliver over the sealed-first path (sealed mailbox + Nostr) when we
            // already share a session with the admin, so they process it on Nostr
            // arrival instead of their next inbox poll (cuts the join delay). Falls
            // back to the legacy relay send for a brand-new admin contact.
            deliverToPeer(envelope, admin.fingerprint, outgoing.expiresAt, silent = true)
            android.util.Log.d("CPCHAN", "subscribe-sent channel=${invite.c.takeLast(8)} to=${admin.fingerprint.hex.takeLast(8)}")
            true
        } catch (e: Exception) {
            _lastError.value = describe(e); false
        }
    }

    /** Subscriber side: unsubscribe. Best-effort tell each admin to drop us, then
     *  leave the channel locally. */
    suspend fun unsubscribeFromChannel(groupID: String) {
        val group = _groups.value[groupID] ?: return
        if (group.isAdmin(identity)) { leaveGroup(groupID); return }
        for (adminMember in group.admins.filter { it.fingerprint != identity }) {
            val op = ControlOp(op = "channel-unsubscribe", targetMessageID = "", at = now, channelId = groupID)
            val outgoing = OutgoingMessage(
                threadID = groupID, text = null,
                attachments = listOf(OutgoingMessage.Attachment("control", "application/json", op.encoded())),
                expiresAt = now + defaultTTL
            )
            try {
                val messageID = UUID.randomUUID().toString().uppercase()
                mutex.withLock { seenMessageIDs.add(messageID) }
                val envelope = buildControl(outgoing, adminMember.publicKey, messageID)
                deliverToPeer(envelope, adminMember.fingerprint, outgoing.expiresAt, silent = true)
            } catch (e: Exception) { _lastError.value = describe(e) }
        }
        groupKeys?.deleteGroup(groupID, group.epoch + 1)
        sealedKeys?.deleteGroupState(groupID)
        mutex.withLock {
            _groups.value = _groups.value - groupID
            _groupMessages.value = _groupMessages.value - groupID
            persistLocked()
        }
    }

    /** Admin side: open an un-verifiable envelope as a self-authenticating
     *  channel-subscribe (decrypt-only; the payload carries the subscriber's own
     *  key). Returns true when consumed (valid shape) so the caller stops treating
     *  it as a failed open. Runs under the receive lock. */
    private fun tryChannelSubscribeLocked(envelope: ByteArray): Boolean {
        val container = try { crypto.decryptOnly(envelope) } catch (e: Exception) { return false }
        val decoded = try { CPN1.decode(container) } catch (e: Exception) { return false }
        val manifest = try { Manifest.decode(decoded.manifest) } catch (e: Exception) { return false }
        if (manifest.type != "control") return false
        val part = decoded.parts.firstOrNull() ?: return false
        val op = ControlOp.decode(part) ?: return false
        if (op.op != "channel-subscribe") return false
        if (seenMessageIDs.contains(manifest.messageID)) return true
        val channelId = op.channelId ?: return true
        val pubkey = op.pubkey ?: return true
        val subFpr = CPKeyInfo.primaryFingerprint(pubkey)?.let { Fingerprint.from(it) } ?: return true
        applyChannelSubscribeLocked(channelId, subFpr, pubkey, op.token)
        seenMessageIDs.add(manifest.messageID)
        return true
    }

    /** Admin auto-accept: add the subscriber (non-admin) and send it the admins-only
     *  channel key. No rekey. Idempotent. Called under the receive lock. */
    private fun applyChannelSubscribeLocked(channelId: String, subFpr: Fingerprint, subPubkey: String, token: String?) {
        val group = _groups.value[channelId] ?: return
        if (!group.isChannel || !group.isAdmin(identity)) return
        if (subFpr == identity) return
        val contact = PairingSupport.consistentContact(subFpr.hex, subPubkey, TrustLevel.UNVERIFIED) ?: return
        if (contacts().none { it.fingerprint == subFpr }) addContact(contact)
        android.util.Log.d("CPCHAN", "subscribe-recv channel=${channelId.takeLast(8)} from=${subFpr.hex.takeLast(8)}")
        val gk = groupKeys ?: return
        val updated = if (group.members.any { it.fingerprint == subFpr }) group
            else group.copy(members = group.members + GroupMember(subFpr, subPubkey, contact.name, false))
        _groups.value = _groups.value + (channelId to updated)
        persistLocked()
        lastSealedWindowRefresh = 0          // register an inbound window for the new subscriber on the next refresh
        val key = gk.key(channelId, updated.epoch) ?: return
        val payload = GroupKeyPayload(channelId, updated.epoch, Base64.Default.encode(key.encoded),
            updated.name, updated.admins, isChannel = true)
        scope.launch { sendGroupKey(payload, contact.publicKey, subFpr) }
    }

    private fun sealedGroupSendAddress(groupID: String, epoch: Int, recipient: Fingerprint): String? {
        val sk = sealedKeys ?: return null
        val gk = groupKeys ?: return null
        val secret = gk.key(groupID, epoch) ?: return null
        val epochKey = secret.encoded
        var gst = sk.group(groupID) ?: SealedGroupState(epoch, mutableMapOf(), mutableMapOf())
        if (gst.epoch != epoch) gst = SealedGroupState(epoch, mutableMapOf(), mutableMapOf())
        val counter = gst.sendCounters[recipient.hex] ?: 0L
        val address = MailboxCrypto.groupAddress(epochKey, identity.hex, recipient.hex, counter)
        gst.sendCounters[recipient.hex] = counter + 1
        sk.setGroup(groupID, gst)
        return address
    }

    private fun sealedApplyPeerKeyLocked(sender: Fingerprint, key: ByteArray) {
        val sk = sealedKeys ?: return
        val st = sk.pair(sender.hex) ?: SealedPairState(sk.newPairKey(), null, 0, 0, false)
        // A changed peer inbound key means the peer reset its sealed state (e.g. a
        // reinstall): its receive window restarted at 0 and it no longer holds our key.
        // Realign both counters to 0 so our sends land in its fresh window, and re-share
        // our own key so it can seal to us again. Without this a reset peer deadlocks.
        val prev = st.peerInboundKey
        val reset = prev != null && !prev.contentEquals(key)
        val sendCounter = if (reset) 0L else st.sendCounter
        val receiveHigh = if (reset) 0L else st.receiveHigh
        val sharedMine = if (reset) false else st.sharedMine
        sk.setPair(sender.hex, SealedPairState(st.myInboundKey, key, sendCounter, receiveHigh, sharedMine))
        android.util.Log.d("CPSEAL", "KEY from=${sender.hex.takeLast(8)} healed=$reset (my sendCounter now $sendCounter)")
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun appendToConversationLocked(message: ChatMessage, peer: Fingerprint, peerName: String? = null) {
        val existing = _conversations.value[message.threadID]
        if (existing != null) {
            if (existing.messages.any { it.id == message.id }) return
            _conversations.value = _conversations.value + (message.threadID to existing.copy(
                messages = existing.messages + message,
                peerName = peerName ?: existing.peerName
            ))
        } else {
            _conversations.value = _conversations.value + (message.threadID to Conversation(
                threadID = message.threadID,
                peer = peer,
                peerName = peerName ?: contactName(peer),
                messages = listOf(message)
            ))
        }
        persistLocked()
    }

    private fun setReadLocked(messageID: String) {
        for ((threadID, conversation) in _conversations.value) {
            if (conversation.messages.any { it.id == messageID }) {
                _conversations.value = _conversations.value + (threadID to conversation.copy(
                    messages = conversation.messages.map { if (it.id == messageID) it.copy(isRead = true) else it }
                ))
                persistLocked()
                return
            }
        }
    }

    private fun deleteMessageLocked(id: String) {
        var updated = _conversations.value
        for ((threadID, conversation) in updated) {
            for (message in conversation.messages.filter { it.id == id }) {
                for (attachment in message.attachments) {
                    AttachmentStore.delete(attachment.localPath)
                }
            }
            updated = updated + (threadID to conversation.copy(
                messages = conversation.messages.filter { it.id != id }
            ))
        }
        _conversations.value = updated
        persistLocked()
    }

    private fun pruneExpiredLocked() {
        val cutoff = now
        var updated = _conversations.value
        for ((threadID, conversation) in updated) {
            val kept = conversation.messages.filter { it.expiresAt > cutoff }
            if (kept.size != conversation.messages.size) {
                updated = updated + (threadID to conversation.copy(messages = kept))
            }
        }
        _conversations.value = updated
        persistLocked()
    }

    // ── Groups ─────────────────────────────────────────────────────────
    //
    // Ported from iOS ChatStore. A group has a shared symmetric key per epoch
    // (rotated on membership change, stored in groupKeys) plus a per-sender
    // detached signature verified against the roster. A message is sealed once
    // and the identical payload is fanned out to every member by the client;
    // the relay stays dumb. Group-key envelopes ride the normal PGP path,
    // silent, one per member.

    val identityFingerprint: Fingerprint get() = identity

    fun amGroupAdmin(groupID: String): Boolean = _groups.value[groupID]?.isAdmin(identity) == true

    /** Mark every incoming message in a group thread read on this device. Local
     *  only; group read receipts are not fanned out to the whole group. */
    suspend fun markGroupRead(groupID: String) {
        mutex.withLock {
            val list = _groupMessages.value[groupID] ?: return@withLock
            if (list.none { it.direction == MessageDirection.INCOMING && !it.isRead }) return@withLock
            val updated = list.map {
                if (it.direction == MessageDirection.INCOMING && !it.isRead) it.copy(isRead = true) else it
            }
            _groupMessages.value = _groupMessages.value + (groupID to updated)
            persistLocked()
        }
    }

    private fun isGroupPayload(envelope: ByteArray): Boolean {
        if (envelope.size < groupMagic.size) return false
        for (i in groupMagic.indices) if (envelope[i] != groupMagic[i]) return false
        return true
    }

    /** Create a group, generate the epoch-0 key, and distribute the key + roster
     *  to every member. The creator is the first admin. */
    suspend fun createGroup(name: String, members: List<Contact>) {
        val gk = groupKeys ?: return
        val mine = ownPublicKey() ?: return
        val roster = mutableListOf(GroupMember(identity, mine.armored, contactName(identity), true))
        for (c in members) roster.add(GroupMember(c.fingerprint, c.publicKey.armored, c.name, false))
        val groupID = ChatGroup.newID()
        val key = gk.newKey()
        gk.store(key, groupID, 0)
        val group = ChatGroup(groupID, name, roster, 0, createdAt = now)
        mutex.withLock {
            _groups.value = _groups.value + (groupID to group)
            persistLocked()
        }
        // A local roster or epoch change needs new inbound windows on the relay before any
        // member can reach us on the new streams. Without this the creator's windows were
        // only registered on the next 30-second refresh, and a reply sent before that went
        // to an unregistered mailbox, which the relay accepts and drops.
        lastSealedWindowRefresh = 0
        distributeGroupKey(group, key)
    }

    /** Create a broadcast channel: like a group, but the creator is the sole admin
     *  (broadcaster) and everyone else is a subscriber (receive-only). */
    suspend fun createChannel(name: String, subscribers: List<Contact>) {
        val gk = groupKeys ?: return
        val mine = ownPublicKey() ?: return
        val roster = mutableListOf(GroupMember(identity, mine.armored, contactName(identity), true))
        for (c in subscribers) roster.add(GroupMember(c.fingerprint, c.publicKey.armored, c.name, false))
        val groupID = ChatGroup.newID()
        val key = gk.newKey()
        gk.store(key, groupID, 0)
        val group = ChatGroup(groupID, name, roster, 0, isChannel = true, createdAt = now)
        mutex.withLock {
            _groups.value = _groups.value + (groupID to group)
            persistLocked()
        }
        // A local roster or epoch change needs new inbound windows on the relay before any
        // member can reach us on the new streams. Without this the creator's windows were
        // only registered on the next 30-second refresh, and a reply sent before that went
        // to an unregistered mailbox, which the relay accepts and drops.
        lastSealedWindowRefresh = 0
        android.util.Log.d("CPCHAN", "created channel=${groupID.takeLast(8)} subs=${subscribers.size} groupsNow=${_groups.value.size}")
        distributeGroupKey(group, key)
    }

    /** Admin only: add subscribers to a channel WITHOUT a rekey (subscribers only
     *  receive, so there is nothing to hide from a new one). The current epoch key
     *  goes to the new subscribers only, with the admins-only roster. */
    suspend fun addSubscribers(contacts: List<Contact>, groupID: String) {
        val gk = groupKeys ?: return
        val group = _groups.value[groupID] ?: return
        if (!group.isChannel || !group.isAdmin(identity)) return
        val present = group.members.map { it.fingerprint.hex }.toSet()
        val additions = contacts
            .filter { it.fingerprint.hex !in present }
            .map { GroupMember(it.fingerprint, it.publicKey.armored, it.name, false) }
        if (additions.isEmpty()) return
        val updated = group.copy(members = group.members + additions)
        mutex.withLock {
            _groups.value = _groups.value + (groupID to updated)
            persistLocked()
        }
        lastSealedWindowRefresh = 0
        val key = gk.key(groupID, group.epoch) ?: return
        val payload = GroupKeyPayload(groupID, group.epoch, Base64.Default.encode(key.encoded), group.name, updated.admins, isChannel = true)
        for (c in additions) sendGroupKey(payload, c.publicKey, c.fingerprint)
    }

    /** Admin only: add paired contacts, rekey, and redistribute to all. */
    suspend fun addMembers(contacts: List<Contact>, groupID: String) {
        val gk = groupKeys ?: return
        val group = _groups.value[groupID] ?: return
        if (!group.isAdmin(identity)) return
        val present = group.members.map { it.fingerprint.hex }.toSet()
        val additions = contacts
            .filter { it.fingerprint.hex !in present }
            .map { GroupMember(it.fingerprint, it.publicKey.armored, it.name, false) }
        if (additions.isEmpty()) return
        val newEpoch = group.epoch + 1
        val updated = group.copy(members = group.members + additions, epoch = newEpoch)
        val key = gk.newKey()
        gk.store(key, groupID, newEpoch)
        mutex.withLock {
            _groups.value = _groups.value + (groupID to updated)
            persistLocked()
        }
        lastSealedWindowRefresh = 0
        distributeGroupKey(updated, key)
    }

    /** Admin only: remove a member (not yourself), rekey, and redistribute to the
     *  remaining members. The removed member never receives the new epoch key. */
    suspend fun removeMember(fingerprint: Fingerprint, groupID: String) {
        val gk = groupKeys ?: return
        val group = _groups.value[groupID] ?: return
        if (!group.isAdmin(identity) || fingerprint == identity) return
        val newEpoch = group.epoch + 1
        val updated = group.copy(members = group.members.filter { it.fingerprint != fingerprint }, epoch = newEpoch)
        val key = gk.newKey()
        gk.store(key, groupID, newEpoch)
        mutex.withLock {
            _groups.value = _groups.value + (groupID to updated)
            persistLocked()
        }
        lastSealedWindowRefresh = 0
        distributeGroupKey(updated, key)
    }

    /** Admin only: rename a group (no rekey; same epoch key, new name). */
    suspend fun renameGroup(groupID: String, name: String) {
        val gk = groupKeys ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val group = _groups.value[groupID] ?: return
        if (!group.isAdmin(identity)) return
        val key = gk.key(groupID, group.epoch) ?: return
        val updated = group.copy(name = trimmed)
        mutex.withLock {
            _groups.value = _groups.value + (groupID to updated)
            persistLocked()
        }
        distributeGroupKey(updated, key)
    }

    /** Leave a group. If you are an admin, remove yourself and rekey the remaining
     *  members first, so your departure cuts you off cryptographically. Then
     *  delete the group and its keys locally. */
    suspend fun leaveGroup(groupID: String) {
        val gk = groupKeys ?: return
        val group = _groups.value[groupID] ?: return
        if (group.isAdmin(identity)) {
            val remainingMembers = group.members.filter { it.fingerprint != identity }
            if (remainingMembers.isNotEmpty()) {
                val newEpoch = group.epoch + 1
                val remaining = group.copy(members = remainingMembers, epoch = newEpoch)
                val key = gk.newKey()
                gk.store(key, groupID, newEpoch)
                distributeGroupKey(remaining, key, includeSelf = false)
            }
        }
        gk.deleteGroup(groupID, group.epoch + 1)
        sealedKeys?.deleteGroupState(groupID)
        mutex.withLock {
            _groups.value = _groups.value - groupID
            _groupMessages.value = _groupMessages.value - groupID
            persistLocked()
        }
    }

    private suspend fun distributeGroupKey(group: ChatGroup, key: SecretKey, includeSelf: Boolean = true) {
        val keyB64 = Base64.Default.encode(key.encoded)
        // In a channel, a subscriber receives the admins-only roster (enough to verify
        // who may post) and never the other subscribers.
        fun payloadFor(recipient: Fingerprint): GroupKeyPayload {
            val roster = if (group.isChannel && !group.isAdmin(recipient)) group.admins else group.members
            return GroupKeyPayload(group.groupID, group.epoch, keyB64, group.name, roster, group.isChannel)
        }
        android.util.Log.d("CPCHAN", "distribute group=${group.groupID.takeLast(8)} members=${group.members.size} isChannel=${group.isChannel} multiDevice=$multiDevice")
        for (member in group.members) {
            if (member.fingerprint != identity) sendGroupKey(payloadFor(member.fingerprint), member.publicKey, member.fingerprint)
        }
        if (multiDevice && includeSelf) {
            ownPublicKey()?.let { sendGroupKey(payloadFor(identity), it, identity) }
        }
    }

    private suspend fun sendGroupKey(payload: GroupKeyPayload, to: PublicKey, recipient: Fingerprint) {
        android.util.Log.d("CPCHAN", "key-send-enter to=${recipient.hex.takeLast(8)} group=${payload.groupID.takeLast(8)}")
        val outgoing = OutgoingMessage(
            threadID = payload.groupID,
            text = null,
            attachments = listOf(OutgoingMessage.Attachment("group-key", "application/json", payload.encoded())),
            expiresAt = now + defaultTTL
        )
        try {
            val messageID = UUID.randomUUID().toString().uppercase()
            mutex.withLock { seenMessageIDs.add(messageID) }
            val envelope = buildGroupEnvelope(outgoing, "group-key", payload.groupID, payload.epoch, to, messageID)
            // Deliver over the same sealed-first path as messages: it posts to the
            // recipient's sealed mailbox (no relay identity auth, sender hidden) and
            // fires the Nostr/direct accelerators, falling back to the legacy
            // fingerprint-routed relay send only when the pair is not sealed yet.
            deliverToPeer(envelope, recipient, outgoing.expiresAt, silent = true)
            android.util.Log.d("CPCHAN", "key-send group=${payload.groupID.takeLast(8)} to=${recipient.hex.takeLast(8)} isChannel=${payload.isChannel}")
        } catch (e: Exception) {
            android.util.Log.d("CPCHAN", "key-send-FAIL to=${recipient.hex.takeLast(8)} err=${describe(e)}")
            _lastError.value = describe(e)
        }
    }

    private fun buildGroupEnvelope(message: OutgoingMessage, type: String, groupID: String, epoch: Int, recipient: PublicKey, messageID: String): ByteArray {
        val parts = message.attachments.map { it.data }
        val meta = listOf(Manifest.Part(kind = "control", mime = "application/json", size = parts.firstOrNull()?.size?.toLong()))
        val manifest = Manifest(
            v = 1, type = type, messageID = messageID, threadID = message.threadID,
            sentAt = now, expiresAt = message.expiresAt, parts = meta, groupID = groupID, epoch = epoch
        )
        val container = CPN1.encode(manifest.encoded(), parts)
        return crypto.signAndEncrypt(container, recipient)
    }

    /** Apply a received group-key envelope: store the epoch key and roster. A new
     *  group is accepted only from a creator listed as admin; an existing group
     *  only from a current admin, and only for an epoch at least as new as ours.
     *  Called under the receive lock. */
    private fun applyGroupKeyLocked(incoming: IncomingMessage) {
        val gk = groupKeys ?: return
        val data = incoming.files.firstOrNull()?.second ?: incoming.text?.toByteArray(Charsets.UTF_8) ?: return
        val payload = GroupKeyPayload.decode(data) ?: return
        val keyData = try { Base64.Default.decode(payload.keyB64) } catch (e: Exception) { return }
        val senderIsSelf = incoming.sender == identity
        val existing = _groups.value[payload.groupID]
        android.util.Log.d("CPCHAN", "key-recv group=${payload.groupID.takeLast(8)} isChannel=${payload.isChannel} from=${incoming.sender.hex.takeLast(8)} known=${existing != null}")
        if (existing != null) {
            if (!(senderIsSelf || existing.isAdmin(incoming.sender))) return
            if (payload.epoch < existing.epoch) return
        } else {
            if (!(senderIsSelf || payload.members.any { it.fingerprint == incoming.sender && it.isAdmin })) return
        }
        gk.store(gk.keyFromRaw(keyData), payload.groupID, payload.epoch)
        _groups.value = _groups.value + (payload.groupID to ChatGroup(payload.groupID, payload.name, payload.members, payload.epoch, payload.isChannel, createdAt = existing?.createdAt ?: now))
        android.util.Log.d("CPCHAN", "key-stored group=${payload.groupID.takeLast(8)} isChannel=${payload.isChannel}")
        // A newly joined group/channel (or a rekey) means our receive window for
        // this group's sealed address is not registered on the relay yet, and the
        // relay drops deposits to unregistered mailboxes. Force the next maintain
        // pass to re-register now instead of waiting up to 30s, so the admin's
        // first message is not lost to an unregistered mailbox.
        if (existing == null || payload.epoch > existing.epoch) lastSealedWindowRefresh = 0
        persistLocked()
    }

    /** Send a message to a group: seal it once, fan out the identical payload to
     *  every member, plus a silent self-copy for my other devices. */
    suspend fun sendGroupMessage(groupID: String, text: String, attachments: List<OutgoingMessage.Attachment> = emptyList()) {
        val gk = groupKeys ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachments.isEmpty()) return
        val group = _groups.value[groupID] ?: return
        if (group.isChannel && !group.isAdmin(identity)) return
        val key = gk.key(groupID, group.epoch) ?: run {
            // Silent before: the message vanished with no trace. Now the user sees why.
            _lastError.value = "No key for this group's current epoch yet. Wait for an admin's update and try again."
            return
        }
        val messageID = UUID.randomUUID().toString().uppercase()
        val expiresAt = now + defaultTTL
        val container = try {
            buildGroupContainer(if (trimmed.isEmpty()) null else trimmed, attachments, groupID, group.epoch, messageID, expiresAt)
        } catch (e: Exception) {
            _lastError.value = describe(e); return
        }
        val storedAttachments = attachments.map { a ->
            val localPath = AttachmentStore.save(a.data, a.filename)
            ChatMessage.Attachment(UUID.randomUUID().toString(), a.filename, a.mime, a.data.size.toLong(), localPath)
        }
        val local = ChatMessage(
            id = messageID, threadID = groupID, peer = identity, direction = MessageDirection.OUTGOING,
            text = if (trimmed.isEmpty()) null else trimmed, attachments = storedAttachments,
            sentAt = now, expiresAt = expiresAt, isRead = true
        )
        mutex.withLock {
            seenMessageIDs.add(messageID)
            appendGroupMessageLocked(local, groupID)
        }
        try {
            val box = GroupCrypto.seal(container, groupID, identity, group.epoch, key, crypto)
            val payload = groupMagic + box
            val recipients = group.members.map { it.fingerprint }.filter { it != identity }.toMutableList()
            if (multiDevice) recipients.add(identity)
            android.util.Log.d("CPCHAN", "send group=${groupID.takeLast(8)} isChannel=${group.isChannel} recipients=${recipients.size}")
            for (recipient in recipients) {
                val silent = recipient == identity
                val address = sealedGroupSendAddress(groupID, group.epoch, recipient)
                if (address != null) {
                    // The relay is authoritative, but Nostr and the direct transports
                    // run as best-effort accelerators so a channel/group message still
                    // lands when the relay is down or unreachable, the same model 1:1
                    // and group-key delivery already use. Each transport is isolated so
                    // one failing (e.g. relay identity 403) does not skip the others or
                    // abort the remaining recipients.
                    try {
                        relay.sealedSend(address, payload, expiresAt, silent)
                    } catch (e: RelayException) {
                        // 404: this member has no window for our stream yet (it has not polled
                        // since it got the key). Deliver over the per-peer path instead, which
                        // opens group payloads too, rather than lose the post.
                        if (e.status == 404) {
                            try { deliverToPeer(payload, recipient, expiresAt, silent) } catch (e2: Exception) { _lastError.value = describe(e2) }
                        } else _lastError.value = describe(e)
                    } catch (e: Exception) { _lastError.value = describe(e) }
                    val directs = listOfNotNull(lanTransport, wanTransport, smsTransport).filter { it.canReach(recipient) }
                    for (d in directs) { try { d.send(payload, recipient, null, expiresAt, silent) } catch (e: Exception) {} }
                    nostrTransport?.takeIf { it.canReach(recipient) }?.let { n ->
                        try { n.send(payload, recipient, address, expiresAt, silent) } catch (e: Exception) {}
                    }
                } else {
                    // No sealed group stream for this recipient yet: fall back to the
                    // shared per-peer delivery path (sealed pair address + Nostr/direct,
                    // legacy relay only as a last resort) instead of a relay-only send.
                    try { deliverToPeer(payload, recipient, expiresAt, silent) } catch (e: Exception) { _lastError.value = describe(e) }
                }
            }
        } catch (e: Exception) {
            _lastError.value = describe(e)
        }
    }

    private fun buildGroupContainer(text: String?, attachments: List<OutgoingMessage.Attachment>, groupID: String, epoch: Int, messageID: String, expiresAt: Long): ByteArray {
        val parts = mutableListOf<ByteArray>()
        val meta = mutableListOf<Manifest.Part>()
        if (text != null) {
            parts.add(text.toByteArray(Charsets.UTF_8))
            meta.add(Manifest.Part(kind = "text", mime = "text/plain; charset=utf-8"))
        }
        for (a in attachments) {
            parts.add(a.data)
            meta.add(Manifest.Part(kind = "file", mime = a.mime, filename = a.filename, size = a.data.size.toLong()))
        }
        val manifest = Manifest(
            v = 1, type = "group-msg", messageID = messageID, threadID = groupID,
            sentAt = now, expiresAt = expiresAt, parts = meta, groupID = groupID, epoch = epoch
        )
        return CPN1.encode(manifest.encoded(), parts)
    }

    /** Open a received group message: decrypt with the epoch key, verify the
     *  sender against the roster, and file it. Returns false only when the failure
     *  is recoverable (a rekey or group-key we have not received yet), so the
     *  relay keeps the message for a retry. Called under the receive lock. */
    private fun handleGroupLocked(payload: ByteArray): Boolean {
        val gk = groupKeys ?: return true
        val groupID = GroupCrypto.peekGroupID(payload) ?: return true
        val group = _groups.value[groupID] ?: return false
        val opened = try {
            GroupCrypto.open(payload, group, { gk.key(groupID, it) }, crypto)
        } catch (e: GroupCryptoException.NoKey) {
            return false
        } catch (e: Exception) {
            diagnostics("group message for $groupID dropped on open: ${e.javaClass.simpleName}: ${e.message}", e)
            return true
        }
        val (sender, container) = opened
        val decoded = try { CPN1.decode(container) } catch (e: Exception) { diagnostics("group CPN1 decode failed for $groupID: ${e.message}", e); return true }
        val manifest = try { Manifest.decode(decoded.manifest) } catch (e: Exception) { diagnostics("group manifest decode failed for $groupID: ${e.message}", e); return true }
        if (seenMessageIDs.contains(manifest.messageID)) return true
        if (manifest.expiresAt <= now) { seenMessageIDs.add(manifest.messageID); return true }
        android.util.Log.d("CPCHAN", "msg-recv group=${groupID.takeLast(8)} isChannel=${group.isChannel} from=${sender.hex.takeLast(8)} adminOK=${group.isAdmin(sender)}")
        if (group.isChannel && sender != identity && !group.isAdmin(sender)) {
            // Channel: only admins may post. Drop anything else (consume, do not retry).
            seenMessageIDs.add(manifest.messageID)
            return true
        }
        var text: String? = null
        val atts = mutableListOf<ChatMessage.Attachment>()
        for ((i, part) in manifest.parts.withIndex()) {
            if (i >= decoded.parts.size) break
            when (part.kind) {
                "text" -> text = String(decoded.parts[i], Charsets.UTF_8)
                "file" -> {
                    val filename = part.filename ?: "file"
                    val localPath = AttachmentStore.save(decoded.parts[i], filename)
                    atts.add(ChatMessage.Attachment(UUID.randomUUID().toString(), filename, part.mime ?: "application/octet-stream", decoded.parts[i].size.toLong(), localPath))
                }
            }
        }
        val message = ChatMessage(
            id = manifest.messageID, threadID = groupID, peer = sender,
            direction = if (sender == identity) MessageDirection.OUTGOING else MessageDirection.INCOMING,
            text = text, attachments = atts, sentAt = manifest.sentAt, expiresAt = manifest.expiresAt,
            isRead = sender == identity
        )
        seenMessageIDs.add(manifest.messageID)
        appendGroupMessageLocked(message, groupID)
        return true
    }

    private fun appendGroupMessageLocked(message: ChatMessage, groupID: String) {
        val list = _groupMessages.value[groupID] ?: emptyList()
        if (list.any { it.id == message.id }) return
        val updated = (list + message).sortedBy { it.sentAt }
        _groupMessages.value = _groupMessages.value + (groupID to updated)
        persistLocked()
    }

    // ── Persistence ────────────────────────────────────────────────────
    //
    // Conversations and messages are kept on disk so threads survive relaunch.
    // Stored per-identity (the filename carries the identity fingerprint), so a
    // different identity on the same device sees its own history and never the
    // previous one's. Field names match the iOS snapshot for symmetry.

    private fun persistLocked() {
        val conversationsArray = JSONArray()
        for (conversation in _conversations.value.values) {
            val messagesArray = JSONArray()
            for (message in conversation.messages) {
                val attachmentsArray = JSONArray()
                for (attachment in message.attachments) {
                    attachmentsArray.put(JSONObject()
                        .put("id", attachment.id)
                        .put("filename", attachment.filename)
                        .put("mime", attachment.mime)
                        .put("size", attachment.size)
                        .put("localPath", attachment.localPath))
                }
                val messageJson = JSONObject()
                    .put("id", message.id)
                    .put("threadID", message.threadID)
                    .put("peer", message.peer.hex)
                    .put("direction", if (message.direction == MessageDirection.INCOMING) "in" else "out")
                    .put("attachments", attachmentsArray)
                    .put("sentAt", message.sentAt)
                    .put("expiresAt", message.expiresAt)
                    .put("isRead", message.isRead)
                    .put("viaLan", message.viaLan)
                if (message.text != null) messageJson.put("text", message.text)
                messagesArray.put(messageJson)
            }
            val conversationJson = JSONObject()
                .put("threadID", conversation.threadID)
                .put("peer", conversation.peer.hex)
                .put("messages", messagesArray)
            if (conversation.peerName != null) conversationJson.put("peerName", conversation.peerName)
            conversationsArray.put(conversationJson)
        }
        val groupsArray = JSONArray()
        for (group in _groups.value.values) {
            groupsArray.put(JSONObject()
                .put("groupID", group.groupID)
                .put("name", group.name)
                .put("epoch", group.epoch)
                .put("isChannel", group.isChannel)
                .put("createdAt", group.createdAt)
                .put("members", membersToJson(group.members)))
        }
        val groupThreadsArray = JSONArray()
        for ((gid, msgs) in _groupMessages.value) {
            val marr = JSONArray()
            for (message in msgs) {
                val attachmentsArray = JSONArray()
                for (attachment in message.attachments) {
                    attachmentsArray.put(JSONObject()
                        .put("id", attachment.id)
                        .put("filename", attachment.filename)
                        .put("mime", attachment.mime)
                        .put("size", attachment.size)
                        .put("localPath", attachment.localPath))
                }
                val mj = JSONObject()
                    .put("id", message.id)
                    .put("threadID", message.threadID)
                    .put("peer", message.peer.hex)
                    .put("direction", if (message.direction == MessageDirection.INCOMING) "in" else "out")
                    .put("attachments", attachmentsArray)
                    .put("sentAt", message.sentAt)
                    .put("expiresAt", message.expiresAt)
                    .put("isRead", message.isRead)
                    .put("viaLan", message.viaLan)
                if (message.text != null) mj.put("text", message.text)
                marr.put(mj)
            }
            groupThreadsArray.put(JSONObject().put("groupID", gid).put("messages", marr))
        }
        val snapshot = JSONObject()
            .put("conversations", conversationsArray)
            .put("seen", JSONArray(seenMessageIDs.toList()))
            .put("groups", groupsArray)
            .put("groupThreads", groupThreadsArray)
        try {
            fileURL.parentFile?.mkdirs()
            AtRest.writeText(fileURL, snapshot.toString())
        } catch (e: Exception) {
            // Mirrors iOS's try? — persistence failure never crashes the app.
        }
    }

    private fun load() {
        val text = try {
            fileURL.takeIf { it.exists() }?.let { AtRest.readText(it) }
        } catch (e: Exception) {
            null
        } ?: return
        try {
            val snapshot = JSONObject(text)
            val restored = mutableMapOf<String, Conversation>()
            val conversationsArray = snapshot.getJSONArray("conversations")
            for (i in 0 until conversationsArray.length()) {
                val stored = conversationsArray.getJSONObject(i)
                val peer = Fingerprint.from(stored.getString("peer")) ?: continue
                val messagesArray = stored.getJSONArray("messages")
                val messages = mutableListOf<ChatMessage>()
                for (j in 0 until messagesArray.length()) {
                    val m = messagesArray.getJSONObject(j)
                    val messagePeer = Fingerprint.from(m.getString("peer")) ?: continue
                    val attachmentsArray = m.getJSONArray("attachments")
                    val attachments = (0 until attachmentsArray.length()).map { k ->
                        val a = attachmentsArray.getJSONObject(k)
                        ChatMessage.Attachment(
                            id = a.getString("id"),
                            filename = a.getString("filename"),
                            mime = a.getString("mime"),
                            size = a.getLong("size"),
                            localPath = a.getString("localPath")
                        )
                    }
                    messages.add(ChatMessage(
                        id = m.getString("id"),
                        threadID = m.getString("threadID"),
                        peer = messagePeer,
                        direction = if (m.getString("direction") == "in") MessageDirection.INCOMING else MessageDirection.OUTGOING,
                        text = if (m.has("text")) m.getString("text") else null,
                        attachments = attachments,
                        sentAt = m.getLong("sentAt"),
                        expiresAt = m.getLong("expiresAt"),
                        isRead = m.getBoolean("isRead"),
                        viaLan = m.optBoolean("viaLan", false)
                    ))
                }
                restored[stored.getString("threadID")] = Conversation(
                    threadID = stored.getString("threadID"),
                    peer = peer,
                    peerName = if (stored.has("peerName")) stored.getString("peerName") else null,
                    messages = messages
                )
            }
            _conversations.value = restored
            val seenArray = snapshot.getJSONArray("seen")
            seenMessageIDs.clear()
            for (i in 0 until seenArray.length()) {
                seenMessageIDs.add(seenArray.getString(i))
            }
            if (snapshot.has("groups")) {
                val ga = snapshot.getJSONArray("groups")
                val restoredGroups = mutableMapOf<String, ChatGroup>()
                for (i in 0 until ga.length()) {
                    val g = ga.getJSONObject(i)
                    restoredGroups[g.getString("groupID")] = ChatGroup(
                        groupID = g.getString("groupID"),
                        name = g.getString("name"),
                        members = membersFromJson(g.getJSONArray("members")),
                        epoch = g.getInt("epoch"),
                        isChannel = g.optBoolean("isChannel", false),
                        createdAt = g.optLong("createdAt", 0L)
                    )
                }
                _groups.value = restoredGroups
            }
            if (snapshot.has("groupThreads")) {
                val ta = snapshot.getJSONArray("groupThreads")
                val restoredThreads = mutableMapOf<String, List<ChatMessage>>()
                for (i in 0 until ta.length()) {
                    val t = ta.getJSONObject(i)
                    val msgs = mutableListOf<ChatMessage>()
                    val ma = t.getJSONArray("messages")
                    for (j in 0 until ma.length()) {
                        val m = ma.getJSONObject(j)
                        val mp = Fingerprint.from(m.getString("peer")) ?: continue
                        val aa = m.getJSONArray("attachments")
                        val atts = (0 until aa.length()).map { k ->
                            val a = aa.getJSONObject(k)
                            ChatMessage.Attachment(a.getString("id"), a.getString("filename"), a.getString("mime"), a.getLong("size"), a.getString("localPath"))
                        }
                        msgs.add(ChatMessage(
                            id = m.getString("id"), threadID = m.getString("threadID"), peer = mp,
                            direction = if (m.getString("direction") == "in") MessageDirection.INCOMING else MessageDirection.OUTGOING,
                            text = if (m.has("text")) m.getString("text") else null,
                            attachments = atts, sentAt = m.getLong("sentAt"), expiresAt = m.getLong("expiresAt"),
                            isRead = m.getBoolean("isRead"),
                            viaLan = m.optBoolean("viaLan", false)
                        ))
                    }
                    restoredThreads[t.getString("groupID")] = msgs.sortedBy { it.sentAt }
                }
                _groupMessages.value = restoredThreads
            }
        } catch (e: Exception) {
            // A corrupt snapshot loads as empty rather than crashing.
        }
    }

    private fun ownPublicKey(): PublicKey? = try {
        PublicKey(identity, crypto.armoredPublicKey())
    } catch (e: Exception) {
        null
    }

    private fun contactName(fingerprint: Fingerprint): String? =
        contacts().firstOrNull { it.fingerprint == fingerprint }?.name

    private val now: Long get() = System.currentTimeMillis() / 1000

    private fun describe(error: Exception): String {
        if (error is RelayException) {
            return "relay ${error.status}${error.code?.let { ": $it" } ?: ""}"
        }
        return error.message ?: error.javaClass.simpleName
    }
}
