// DesktopSession.kt
// CarrierPony Desktop. One unlocked account's messaging stack: the vendored crypto engine,
// RelayClient, ContactStore and ChatStore, wired to the desktop key stores. It is the slice of
// Android's AppModel.buildStack() that matters on a desktop, without the Context plumbing, push,
// SMS, Nostr or WAN-direct. The GUI (D4) and the CLI verbs (D10) both sit on top of this class,
// so neither carries any protocol logic of its own.
//
// Everything it writes is sealed: secrets through SecureKV, state files through the AtRest codec.
// The Vault must be unlocked first.

package com.carrierpony.desktop

import com.carrierpony.app.AppConfig
import com.carrierpony.app.attachments.AttachmentStore
import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.app.crypto.PonyCryptoEngine
import com.carrierpony.app.crypto.PublicKey
import com.carrierpony.app.identity.Identity
import com.carrierpony.app.identity.PassphraseVault
import com.carrierpony.app.messaging.ChatStore
import com.carrierpony.app.messaging.ContactStore
import com.carrierpony.app.messaging.GroupKeyStore
import com.carrierpony.app.messaging.LanDirectTransport
import com.carrierpony.app.messaging.SealedKeyStore
import com.carrierpony.app.net.LanDiscovery
import com.carrierpony.app.relay.RelayClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.nio.file.Path

class DesktopSession(
    val identity: Identity,
    storage: DesktopStorage,
    scope: CoroutineScope,
    prefs: DesktopPrefs,
    profileName: () -> String? = { null },
    relayBaseURL: String = AppConfig.relayBaseURL(),
    deviceID: String = AppConfig.deviceID(),
    /** Tests pass a LanDiscovery over an in-memory mDNS; the app uses JmDNS. */
    val lanDiscovery: LanDiscovery = LanDiscovery(),
) {
    private val dir: Path = storage.dir

    init {
        // Before any vendored store is constructed: ChatStore and ContactStore read their files
        // in their constructors, and those files are sealed.
        storage.installAtRestCodec()
        // AttachmentStore is a process-wide singleton upstream (one directory for every account,
        // as on the phones) and defaults to java.io.tmpdir, which is the wrong home on a desktop.
        AttachmentStore.directory = dir.resolve("carrierpony-attachments").toFile()
    }

    private val passphrases = PassphraseVault(storage.kv(DesktopStorage.PASSPHRASES))

    val contacts = ContactStore(dir.toFile()).apply { activate(identity.fingerprint.hex) }

    private val crypto = PonyCryptoEngine(
        fingerprint = identity.fingerprint,
        secretKey = identity.secretKey,
        armored = identity.armoredPublicKey,
        // Only an imported, passphrase-protected key has an entry; a generated identity reads null.
        passphrase = { if (identity.protected) passphrases.read(identity.fingerprint.hex) else null },
        publicKeyResolver = { fingerprint: Fingerprint ->
            // Our own key verifies self-copies from our other devices; otherwise a contact.
            if (fingerprint == identity.fingerprint) PublicKey(identity.fingerprint, identity.armoredPublicKey)
            else contacts.contact(fingerprint)?.publicKey
        }
    )

    val relay = RelayClient(baseURL = relayBaseURL, crypto = crypto, deviceID = deviceID)

    val store = ChatStore(
        identity = identity.fingerprint,
        relay = relay,
        crypto = crypto,
        contacts = { contacts.contacts },
        updatePeerName = { fingerprint, name -> contacts.setName(fingerprint, name) },
        addContact = { contact -> contacts.add(contact) },
        storageDir = dir.toFile(),
        scope = scope,
        groupKeys = GroupKeyStore(storage.kv(DesktopStorage.GROUP_KEYS)),
        sealedKeys = SealedKeyStore(storage.kv(DesktopStorage.SEALED), identity.fingerprint.hex),
        // Finish accepted pairing offers before each inbox pass, as AppModel does.
        pairingSweep = { pairing.sweep() },
        lanTransport = LanDirectTransport(lanDiscovery),
        lanSkipRelay = { AppConfig.lanDirectSkipRelay() },
        deviceLabel = "desktop"
    )

    init {
        // LAN-direct, as AppModel wires it: contact keys come from the store, received envelopes go
        // to its ingest path. Discovery runs while the session does; identify and delivery only
        // when the setting is on.
        lanDiscovery.keyProvider = { store.lanContactKeys() }
        lanDiscovery.onEnvelope = { data -> scope.launch { store.ingestLanEnvelope(data) } }
    }

    /** The LAN setting changed, or a new contact was paired: re-run the identify sweep. */
    fun lanDidToggle() = lanDiscovery.lanDidToggle()

    val pairing: DesktopPairing = DesktopPairing(
        identity = identity.fingerprint,
        armoredPublicKey = identity.armoredPublicKey,
        relay = relay,
        contacts = contacts,
        store = { store },
        prefs = prefs,
        profileName = profileName
    )

    /** Register with the relay, drain the inbox once, then keep polling on [scope]. */
    suspend fun start(pollIntervalSeconds: Long = 5) {
        lanDiscovery.start()
        store.start(pollIntervalSeconds)
    }

    fun stop() {
        store.stop()
        lanDiscovery.stop()
    }
}
