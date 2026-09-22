// Strings.kt
// CarrierPony Desktop. D11: localization in two layers.
//
// The Android app carries every user-facing sentence in nine languages, with the vocabulary
// (pairing, invite, safety number, sealed, relay) already settled per locale. The desktop does not
// invent a second vocabulary. Android's strings.xml files are vendored verbatim under
// vendor/app-strings/ by tools/sync-vendor.sh, exactly like the crypto source, and are never
// hand-edited here. Wording only the desktop has (tray, close to tray, launch at login, the
// launch passphrase, the window chrome) lives in a second, desktop-owned layer under i18n/, in
// the same file format. build.gradle.kts mounts the two trees on the classpath as
// /i18n/android and /i18n/desktop.
//
// Ownership is decided by the base (English) file: if i18n/values/strings.xml declares a key, the
// desktop layer owns it and Android's files are never consulted for it. That keeps overrides
// explicit and keeps a stray Android key from quietly shadowing a desktop one after a sync.
//
// Android's format is a good fit for a plain JVM app: the placeholders (%1$s, %2$d) are
// java.util.Formatter syntax, so parameterized strings port without rewriting.
//
// Live switching: I18n.language is Compose snapshot state and tr() reads it, so every composable
// that drew a translated string is subscribed and the whole window recomposes when the picker
// changes. No restart. Outside composition tr() is a plain map lookup.
//
// Nothing here throws. A missing key returns the key itself, which is loud in the UI and caught
// by I18nTest long before a screenshot. A malformed format string returns the unformatted
// template rather than taking a screen down over a translator's typo.

package com.carrierpony.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

object I18n {

    /** Settings value meaning "follow the OS". */
    const val SYSTEM = "system"

    const val KEY_LANGUAGE = "cp.desktop.language"

    /** The languages Android ships. English is the base and always present. Picker order. */
    val SUPPORTED = listOf("en", "de", "es", "fr", "it", "ja", "pt", "ru", "zh")

    /** Endonyms: a language picker that names languages in a language you cannot read is no picker. */
    val DISPLAY_NAMES = mapOf(
        "en" to "English",
        "de" to "Deutsch",
        "es" to "Español",
        "fr" to "Français",
        "it" to "Italiano",
        "ja" to "日本語",
        "pt" to "Português",
        "ru" to "Русский",
        "zh" to "中文"
    )

    /** Where the choice is persisted. Null until the app attaches its prefs; tests leave it null. */
    private var prefs: DesktopPrefs? = null

    /** The stored preference: [SYSTEM] or a tag from [SUPPORTED]. Snapshot state. */
    var language: String by mutableStateOf(SYSTEM)
        private set

    /** The tag in use: [language] with [SYSTEM] resolved against the JVM default locale. */
    val effective: String
        get() = if (language == SYSTEM) systemMatch() else language

    /** Load the stored choice. Called once at startup, before the first frame. */
    fun attach(store: DesktopPrefs) {
        prefs = store
        val stored = runCatching { store.getString(KEY_LANGUAGE) }.getOrNull() ?: SYSTEM
        language = if (stored == SYSTEM || stored in SUPPORTED) stored else SYSTEM
    }

    /**
     * Change the language and persist it. Not named setLanguage: [language] has a private setter
     * and the JVM already emits setLanguage for it, so a hand-written twin is a platform clash.
     */
    fun selectLanguage(tag: String) {
        val next = if (tag == SYSTEM || tag in SUPPORTED) tag else SYSTEM
        runCatching { prefs?.putString(KEY_LANGUAGE, next) }
        language = next
    }

    /**
     * Pin this process to English without persisting anything. The CLI uses it: its output is
     * scriptable, and a script that greps `inbox` must not depend on the window's language.
     */
    fun pinEnglish() {
        language = "en"
    }

    /** Best supported tag for the OS locale; anything unsupported reads English. */
    fun systemMatch(default: Locale = Locale.getDefault()): String {
        val lang = default.language.lowercase()
        return if (lang in SUPPORTED) lang else "en"
    }

    /** JVM Locale for a tag, for number and date formatting inside String.format. */
    fun localeOf(tag: String): Locale = Locale.forLanguageTag(tag)

    /**
     * CLDR cardinal plural category for an integer count in the language of [tag]. A hand-written
     * table over the CLDR data rather than an ICU dependency, integer rules only: every count the
     * app pluralizes is a whole number of members, files or bytes. French and Portuguese put 0
     * with the singular; Japanese and Chinese have one form; Russian splits one/few/many on the
     * final digits. Unknown languages take the English row.
     */
    fun pluralCategory(tag: String, count: Long): String {
        val n = kotlin.math.abs(if (count == Long.MIN_VALUE) count + 1 else count)
        val mod10 = (n % 10).toInt()
        val mod100 = (n % 100).toInt()
        return when (localeOf(tag).language) {
            "ja", "zh" -> "other"
            "fr", "pt" -> if (n < 2) "one" else "other"
            "ru" -> when {
                mod10 == 1 && mod100 != 11 -> "one"
                mod10 in 2..4 && mod100 !in 12..14 -> "few"
                else -> "many"
            }
            else -> if (n == 1L) "one" else "other"
        }
    }

    // Resource layers

    /** Android's resource-qualifier spelling, kept so the vendored tree stays byte-identical. */
    private fun dirFor(tag: String): String = if (tag == "en") "values" else "values-$tag"

    /** The vendored Android layer, read-only, refreshed by sync. */
    const val ANDROID_LAYER = "/i18n/android"

    /** The desktop-owned layer: strings this app has and Android does not. */
    const val DESKTOP_LAYER = "/i18n/desktop"

    private val cache = HashMap<String, Map<String, String>>()

    @Synchronized
    private fun table(layer: String, tag: String): Map<String, String> =
        cache.getOrPut("$layer:$tag") { parse("$layer/${dirFor(tag)}/strings.xml") }

    /** Test hook: forget every parsed table so a test can swap resources. */
    @Synchronized
    internal fun resetCache() = cache.clear()

    /**
     * Read one strings.xml off the classpath. Plurals flatten to `name/quantity` keys. A missing
     * or malformed file is an empty table, never an exception.
     */
    private fun parse(path: String): Map<String, String> {
        val stream = I18n::class.java.getResourceAsStream(path) ?: return emptyMap()
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            factory.isExpandEntityReferences = false
            val doc = stream.use { factory.newDocumentBuilder().parse(it) }
            val out = HashMap<String, String>()
            val strings = doc.getElementsByTagName("string")
            for (i in 0 until strings.length) {
                val el = strings.item(i) as? Element ?: continue
                val name = el.getAttribute("name")
                if (name.isNullOrEmpty()) continue
                out[name] = unescape(el.textContent ?: "")
            }
            val plurals = doc.getElementsByTagName("plurals")
            for (i in 0 until plurals.length) {
                val el = plurals.item(i) as? Element ?: continue
                val name = el.getAttribute("name")
                if (name.isNullOrEmpty()) continue
                val items = el.getElementsByTagName("item")
                for (j in 0 until items.length) {
                    val item = items.item(j) as? Element ?: continue
                    val q = item.getAttribute("quantity")
                    if (q.isNullOrEmpty()) continue
                    out["$name/$q"] = unescape(item.textContent ?: "")
                }
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** Android's backslash escapes: \' \" \n \t \\ and \uXXXX. */
    private fun unescape(raw: String): String {
        if (!raw.contains('\\')) return raw
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '\\' || i == raw.length - 1) {
                sb.append(c)
                i++
                continue
            }
            when (val next = raw[i + 1]) {
                'n' -> sb.append('\n')
                't' -> sb.append('\t')
                '\'' -> sb.append('\'')
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
                'u' -> {
                    val hex = raw.substring(i + 2, minOf(i + 6, raw.length))
                    val cp = hex.toIntOrNull(16)
                    if (hex.length == 4 && cp != null) {
                        sb.append(cp.toChar())
                        i += 4
                    } else {
                        sb.append(next)
                    }
                }
                else -> sb.append(next)
            }
            i += 2
        }
        return sb.toString()
    }

    // Lookup

    /**
     * Raw template for [key] in the current language, or null if no layer declares it. The
     * owning layer is whichever declares the key in its English file; within that layer the
     * chain is the current tag, then English.
     */
    fun template(key: String): String? {
        val tag = effective
        val desktopOwns = table(DESKTOP_LAYER, "en").containsKey(key)
        val layer = if (desktopOwns) DESKTOP_LAYER else ANDROID_LAYER
        return table(layer, tag)[key] ?: table(layer, "en")[key]
    }

    /**
     * A plurals lookup, resolved within a language before falling back to English, so a form the
     * language does not have (Japanese `one`) degrades to that language's `other` rather than to
     * an English item inside an otherwise Japanese window.
     */
    private fun plural(key: String, quantity: String): String? {
        val tag = effective
        val desktopOwns = table(DESKTOP_LAYER, "en").containsKey("$key/other")
        val layer = if (desktopOwns) DESKTOP_LAYER else ANDROID_LAYER
        val localized = table(layer, tag)
        localized["$key/$quantity"]?.let { return it }
        localized["$key/other"]?.let { return it }
        val english = table(layer, "en")
        return english["$key/$quantity"] ?: english["$key/other"]
    }

    /** True when [key] exists in either layer. The audit test uses it; the UI does not. */
    fun has(key: String): Boolean = template(key) != null

    internal fun pluralTemplate(key: String, quantity: String): String? = plural(key, quantity)

    /** One layer's whole table for one tag, for the parity and placeholder tests. */
    fun tableOf(layer: String, tag: String): Map<String, String> = table(layer, tag)

    fun displayNameOf(tag: String): String = DISPLAY_NAMES[tag] ?: tag
}

// The call-site API

/**
 * Look up [key] in the current language. A missing key returns the key itself. Not @Composable on
 * purpose: it reads snapshot state, which subscribes a calling composition automatically, and it
 * stays usable from the controller, the tray and the CLI.
 */
fun tr(key: String): String = I18n.template(key) ?: key

/**
 * Look up [key] and format it with [args] in the current locale, so a German build groups
 * thousands as 1.234. A format string a translator broke returns the unformatted template.
 */
fun tr(key: String, vararg args: Any?): String {
    val template = I18n.template(key) ?: key
    if (args.isEmpty()) return template
    return try {
        String.format(I18n.localeOf(I18n.effective), template, *args)
    } catch (e: Exception) {
        template
    }
}

/** A plurals lookup through [I18n.pluralCategory]. [count] is the first format argument. */
fun trQuantity(key: String, count: Int, vararg args: Any?): String =
    trQuantity(key, count.toLong(), *args)

fun trQuantity(key: String, count: Long, vararg args: Any?): String {
    val quantity = I18n.pluralCategory(I18n.effective, count)
    val template = I18n.pluralTemplate(key, quantity) ?: return key
    val all = if (args.isEmpty()) arrayOf<Any?>(count) else arrayOf<Any?>(count, *args)
    return try {
        String.format(I18n.localeOf(I18n.effective), template, *all)
    } catch (e: Exception) {
        template
    }
}
