// DesktopPrefs.kt
// CarrierPony Desktop. Plain, non-secret settings (the relay URL, the per-install device id,
// feature toggles): the desktop counterpart of Android's "cp.prefs" SharedPreferences. One JSON
// file in the data directory. Nothing secret belongs here; secrets go through SecureKV.

package com.carrierpony.desktop

import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path

class DesktopPrefs(private val file: Path) {

    private val lock = Any()
    private var cache: JSONObject? = null

    private fun json(): JSONObject {
        cache?.let { return it }
        val loaded = if (Files.isRegularFile(file)) {
            try { JSONObject(String(Files.readAllBytes(file), Charsets.UTF_8)) } catch (e: Exception) { JSONObject() }
        } else JSONObject()
        cache = loaded
        return loaded
    }

    fun getString(key: String): String? = synchronized(lock) {
        val j = json()
        if (j.has(key) && !j.isNull(key)) j.optString(key) else null
    }

    fun getBoolean(key: String, default: Boolean): Boolean = synchronized(lock) { json().optBoolean(key, default) }

    fun putString(key: String, value: String?) = synchronized(lock) {
        val j = json()
        if (value == null) j.remove(key) else j.put(key, value)
        AtomicFiles.write(file, j.toString(2).toByteArray(Charsets.UTF_8))
    }

    fun putBoolean(key: String, value: Boolean) = synchronized(lock) {
        val j = json()
        j.put(key, value)
        AtomicFiles.write(file, j.toString(2).toByteArray(Charsets.UTF_8))
    }
}
