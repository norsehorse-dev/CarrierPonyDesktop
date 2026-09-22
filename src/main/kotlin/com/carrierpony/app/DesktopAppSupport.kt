// DesktopAppSupport.kt
// CarrierPony Desktop. The desktop twin of Android's AppSupport.kt, which is not vendored because
// every accessor there takes an android.content.Context. It declares the same two objects,
// AppConfig and DemoMode, because vendored files refer to them by name: ChatStore reads
// AppConfig.maxAttachmentBytes and DemoMode.fingerprint, GatewayClient reads
// AppConfig.gatewayBaseURL.
//
// DRIFT WATCH: the constants below are copies, not vendored. When AppSupport.kt changes upstream
// (relay host, attachment ceiling, demo fingerprint), change them here in the same sync.
// DesktopAppSupportTest pins the values so a silent edit shows up as a failing test.

package com.carrierpony.app

import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.desktop.Config
import com.carrierpony.desktop.DesktopPrefs
import java.net.URI
import java.util.UUID

object AppConfig {

    /** The default CarrierPony relay. Used unless the user has pointed the app at a self-hosted
     *  relay in Settings. */
    const val defaultRelayBaseURL = "https://api.carrierpony.com"

    /** Maximum total attachment bytes per message. Same ceiling as the phones. */
    const val maxAttachmentBytes = 50 * 1024 * 1024

    /** The push gateway. Desktop never registers for push; GatewayClient compiles against it. */
    const val gatewayBaseURL = "https://push.carrierpony.com"

    val defaultNostrRelays = listOf("wss://relay.damus.io", "wss://nos.lol")

    private const val relayURLKey = "cp.relayBaseURL"
    private const val lanDirectKey = "cp.lanDirect"
    private const val lanSkipRelayKey = "cp.lanDirect.skipRelay"
    private const val deviceIDKey = "cp.deviceID"

    /** Settings storage. Lazily bound to the real data directory; tests point it elsewhere. */
    @Volatile var prefs: DesktopPrefs? = null
    private fun store(): DesktopPrefs =
        prefs ?: synchronized(this) { prefs ?: DesktopPrefs(Config.dataDir.resolve("prefs.json")).also { prefs = it } }

    /** The relay this install talks to. Read once when the network stack is built, so a change
     *  takes effect on the next launch, as on the phones. */
    fun relayBaseURL(): String {
        val stored = store().getString(relayURLKey)
        return if (stored != null && isValidRelayURL(stored)) normalizedRelayURL(stored) else defaultRelayBaseURL
    }

    fun usingCustomRelay(): Boolean {
        val stored = store().getString(relayURLKey) ?: return false
        return isValidRelayURL(stored) && normalizedRelayURL(stored) != defaultRelayBaseURL
    }

    /** Store a custom relay, or clear it with null or blank. False when the URL is refused. */
    fun setRelayBaseURL(url: String?): Boolean {
        if (url.isNullOrBlank()) { store().putString(relayURLKey, null); return true }
        if (!isValidRelayURL(url)) return false
        store().putString(relayURLKey, normalizedRelayURL(url))
        return true
    }

    /** Trim whitespace and any trailing slashes. The client appends "/v1/..." paths. */
    fun normalizedRelayURL(url: String): String = url.trim().trimEnd('/')

    /** A relay URL must be an https origin with a host. http is refused because the
     *  challenge-response auth must not travel in clear. */
    fun isValidRelayURL(url: String): Boolean = try {
        val u = URI(normalizedRelayURL(url))
        u.scheme?.lowercase() == "https" && !u.host.isNullOrEmpty()
    } catch (e: Exception) {
        false
    }

    /** LAN-direct delivery (off by default: it opens a listening port, and the firewall asks). */
    fun lanDirectEnabled(): Boolean = store().getBoolean(lanDirectKey, false)
    fun setLanDirectEnabled(on: Boolean) = store().putBoolean(lanDirectKey, on)

    /** Skip the relay when a LAN path delivered. Off by default: the peer's other devices and
     *  offline delivery are lost for those messages. */
    fun lanDirectSkipRelay(): Boolean = store().getBoolean(lanSkipRelayKey, false)
    fun setLanDirectSkipRelay(on: Boolean) = store().putBoolean(lanSkipRelayKey, on)

    /** The random per-install id the relay knows this device by. 32 lowercase hex. */
    fun deviceID(): String {
        val existing = store().getString(deviceIDKey)
        if (existing != null && Regex("^[0-9a-f]{32}$").matches(existing)) return existing
        val id = UUID.randomUUID().toString().replace("-", "").lowercase()
        store().putString(deviceIDKey, id)
        return id
    }
}

// App Review demonstration mode constants. Desktop has no store review to satisfy and never
// activates the demo, but ChatStore compares against this fingerprint, so the object must exist
// with the same values.
object DemoMode {
    const val inviteCode = "CPDEMO-REVIEW"
    const val fingerprintHex = "DE300DE300DE300DE300DE300DE300DE300DE300"
    const val peerName = "CarrierPony Demo"

    val fingerprint: Fingerprint get() = Fingerprint.from(fingerprintHex)!!
}
