// UpdateCheck.kt
// CarrierPony Desktop. D11: "is there a newer version" against carrierpony.com.
//
// This is the only outbound request the app makes that the user did not ask for, so the posture
// is stated rather than inferred:
//
//  - A plain GET of a static JSON file. No query string, no custom headers, no identifier, not
//    even the running version. The server learns that an IP fetched a public file.
//  - Throttled to once a day and the timestamp is persisted, so relaunching in a loop cannot
//    turn the check into a heartbeat.
//  - Opt-in. It does nothing until the user turns it on, which keeps the privacy policy true as
//    written: nothing leaves the device by default beyond the relay traffic the app exists for.
//  - It never downloads or installs anything. It surfaces that a newer version exists and points
//    at the download page.
//
// Manifest shape, produced by the release process in D12:
//   { "current": { "version": "1.0.1", "date": "2026-10-01" } }

package com.carrierpony.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object UpdateCheck {

    /** Hardcoded constant, never built from remote data. */
    const val MANIFEST_URL = "https://carrierpony.com/downloads/desktop.json"

    private const val KEY_ENABLED = "cp.desktop.updateCheck"
    private const val KEY_LAST_MS = "cp.desktop.updateCheck.lastMs"
    private const val KEY_LATEST = "cp.desktop.updateCheck.latest"

    /** Once a day. The manifest changes a handful of times a year. */
    private const val INTERVAL_MS = 24L * 60L * 60L * 1000L

    private var prefs: DesktopPrefs? = null

    /** Test hook: the fetch, replaceable so a test never touches the network. */
    internal var fetcher: suspend () -> String? = { fetchManifest() }

    enum class Status(val labelKey: String) {
        Idle("d_updates_idle"),
        Checking("d_updates_checking"),
        UpToDate("d_updates_current"),
        Available("d_updates_available"),
        Failed("d_updates_failed")
    }

    /** Compose-observable so the Settings section repaints without polling. */
    var status by mutableStateOf(Status.Idle)
        private set

    var latestVersion by mutableStateOf<String?>(null)
        private set

    /** Off by default; that default is the privacy promise, not a preference. */
    var autoEnabled by mutableStateOf(false)
        private set

    fun attach(store: DesktopPrefs) {
        prefs = store
        autoEnabled = runCatching { store.getBoolean(KEY_ENABLED, false) }.getOrDefault(false)
        latestVersion = runCatching { store.getString(KEY_LATEST) }.getOrNull()
    }

    /** Named setAuto rather than a setter on [autoEnabled], whose private setter already owns that JVM name. */
    fun setAuto(enabled: Boolean) {
        runCatching { prefs?.putBoolean(KEY_ENABLED, enabled) }
        autoEnabled = enabled
    }

    private fun lastCheckMs(): Long = runCatching { prefs?.getString(KEY_LAST_MS)?.toLongOrNull() }.getOrNull() ?: 0L

    /**
     * Compare two dotted version strings: negative when [a] is older than [b]. Segment-wise
     * numeric, so 1.0.10 is newer than 1.0.9; missing segments count as zero; a pre-release
     * suffix sorts below the same base release; junk reads as zero rather than throwing, because
     * this parses a file from the network.
     */
    fun compareVersions(a: String, b: String): Int {
        val (aBase, aPre) = splitVersion(a)
        val (bBase, bPre) = splitVersion(b)
        val an = aBase.split('.')
        val bn = bBase.split('.')
        for (i in 0 until maxOf(an.size, bn.size)) {
            val x = an.getOrNull(i)?.trim()?.toIntOrNull() ?: 0
            val y = bn.getOrNull(i)?.trim()?.toIntOrNull() ?: 0
            if (x != y) return x.compareTo(y)
        }
        return when {
            aPre == null && bPre == null -> 0
            aPre == null -> 1
            bPre == null -> -1
            else -> aPre.compareTo(bPre)
        }
    }

    private fun splitVersion(v: String): Pair<String, String?> {
        val t = v.trim()
        val i = t.indexOf('-')
        return if (i < 0) t to null else t.substring(0, i) to t.substring(i + 1)
    }

    /** True when [remote] is strictly newer than the running build. */
    fun isNewer(remote: String, running: String = AppVersion.VERSION): Boolean =
        remote.isNotBlank() && compareVersions(remote, running) > 0

    /** Read current.version out of a manifest body. Null on any failure. */
    fun parseLatest(body: String?): String? =
        runCatching { JSONObject(body ?: return null).optJSONObject("current")?.optString("version", "")?.ifBlank { null } }.getOrNull()

    private suspend fun fetchManifest(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(MANIFEST_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "application/json")
            try {
                if (conn.responseCode != 200) null else conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /** The automatic path: nothing unless enabled and a day has passed. Safe to call on every launch. */
    suspend fun checkIfDue(now: Long = System.currentTimeMillis()) {
        if (!autoEnabled) return
        if (now - lastCheckMs() < INTERVAL_MS) return
        checkNow(now)
    }

    /** The explicit path: ignores both the toggle and the throttle. */
    suspend fun checkNow(now: Long = System.currentTimeMillis()) {
        status = Status.Checking
        val remote = parseLatest(fetcher())
        runCatching { prefs?.putString(KEY_LAST_MS, now.toString()) }
        if (remote == null) {
            status = Status.Failed
            return
        }
        runCatching { prefs?.putString(KEY_LATEST, remote) }
        latestVersion = remote
        status = if (isNewer(remote)) Status.Available else Status.UpToDate
    }

    /** Test hook. */
    internal fun resetForTests() {
        prefs = null
        status = Status.Idle
        latestVersion = null
        autoEnabled = false
        fetcher = { fetchManifest() }
    }
}
