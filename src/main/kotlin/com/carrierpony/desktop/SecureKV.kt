// SecureKV.kt
// CarrierPony Desktop. The desktop counterpart of "a private SharedPreferences whose values are
// sealed under an AndroidKeyStore key", which is how all four Android key stores persist. One
// JSON file maps a name to a blob sealed by the Vault, with the name as associated data, so a
// value cannot be moved to another name. Names are stored in the clear, as they are on Android.
//
// Reads and writes need the vault unlocked and throw VaultLockedException otherwise. The whole
// file is rewritten on every change; these stores hold tens of small entries, not thousands.

package com.carrierpony.desktop

import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path

class SecureKV(private val file: Path, private val vault: Vault) {

    private val lock = Any()
    private var cache: MutableMap<String, String>? = null

    private fun entries(): MutableMap<String, String> {
        cache?.let { return it }
        val map = LinkedHashMap<String, String>()
        if (Files.isRegularFile(file)) {
            val json = JSONObject(String(Files.readAllBytes(file), Charsets.UTF_8))
            for (name in json.keySet()) map[name] = json.getString(name)
        }
        cache = map
        return map
    }

    private fun persist(map: Map<String, String>) {
        val json = JSONObject()
        for ((name, sealed) in map) json.put(name, sealed)
        AtomicFiles.write(file, json.toString().toByteArray(Charsets.UTF_8))
    }

    fun getBytes(name: String): ByteArray? = synchronized(lock) {
        val sealed = entries()[name] ?: return null
        vault.open(name, sealed)
    }

    fun putBytes(name: String, value: ByteArray) = synchronized(lock) {
        val map = entries()
        map[name] = vault.seal(name, value)
        persist(map)
    }

    fun getString(name: String): String? = getBytes(name)?.let { String(it, Charsets.UTF_8) }

    fun putString(name: String, value: String) = putBytes(name, value.toByteArray(Charsets.UTF_8))

    fun contains(name: String): Boolean = synchronized(lock) { entries().containsKey(name) }

    fun names(): List<String> = synchronized(lock) { entries().keys.toList() }

    fun remove(name: String) = removeAll(listOf(name))

    /** Remove several names with a single rewrite of the file. */
    fun removeAll(names: Collection<String>) = synchronized(lock) {
        val map = entries()
        var changed = false
        for (name in names) if (map.remove(name) != null) changed = true
        if (changed) persist(map)
    }

    fun clear() = synchronized(lock) {
        val map = entries()
        if (map.isNotEmpty()) { map.clear(); persist(map) }
    }
}
