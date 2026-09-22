// I18nTest.kt
// D11: the string layers. Every key the UI asks for exists; every locale file parses; placeholders
// agree with English in every language; the desktop layer never shadows an Android key; the
// plural table matches CLDR for the languages Android ships; and switching is live.
// Reads the sources off disk, so it runs from the repo root like VersionDriftTest.

package com.carrierpony.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull

class I18nTest {

    private fun sourceKeys(): Set<String> {
        val root = File("src/main/kotlin")
        assertTrue(root.isDirectory, "expected to run from the repo root; no ${root.absolutePath}")
        val calls = Regex("""\btr(?:Quantity)?\(\s*"([a-z0-9_]+)"""")
        val fields = Regex("""(?:labelKey|descriptionKey)\s*=\s*"([a-z0-9_]+)"""")
        // Enum entries: AppTheme("d_theme_x", "x"), UpdateCheck.Status("d_updates_x"), Destination("common_x", Icons...).
        val enumEntries = Regex("""(?<!appIcon)\("([a-z0-9]+_[a-z0-9_]+)"(?:,\s*"[a-z]+"|,\s*Icons\.[A-Za-z.]+)?\)""")
        val out = HashSet<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val text = f.readText()
            calls.findAll(text).forEach { out += it.groupValues[1] }
            fields.findAll(text).forEach { out += it.groupValues[1] }
            enumEntries.findAll(text).forEach { out += it.groupValues[1] }
        }
        return out
    }

    private fun placeholders(template: String): Set<String> =
        Regex("""%(\d+\$)?[sd]""").findAll(template).map { it.value }.toSet()

    @Test
    fun everyKeyTheUiUsesExists() {
        I18n.pinEnglish()
        val missing = sourceKeys().filter { !I18n.has(it) }.sorted()
        assertTrue(missing.isEmpty(), "keys with no string in either layer: $missing")
    }

    @Test
    fun everyLocaleFileParses() {
        for (tag in I18n.SUPPORTED) {
            val android = I18n.tableOf(I18n.ANDROID_LAYER, tag)
            assertTrue(android.size > 300, "Android layer for $tag parsed only ${android.size} keys")
        }
        assertTrue(I18n.tableOf(I18n.DESKTOP_LAYER, "en").size > 50, "desktop layer (en) looks empty")
    }

    @Test
    fun desktopLayerNeverShadowsAndroid() {
        val desktop = I18n.tableOf(I18n.DESKTOP_LAYER, "en").keys
        val android = I18n.tableOf(I18n.ANDROID_LAYER, "en").keys
        val both = desktop.intersect(android).sorted()
        assertTrue(both.isEmpty(), "declared in both layers (pick one): $both")
        val badPrefix = desktop.filter { !it.startsWith("d_") }.sorted()
        assertTrue(badPrefix.isEmpty(), "desktop-owned keys carry the d_ prefix: $badPrefix")
    }

    @Test
    fun placeholdersAgreeWithEnglishInEveryLanguage() {
        val problems = ArrayList<String>()
        for (layer in listOf(I18n.ANDROID_LAYER, I18n.DESKTOP_LAYER)) {
            val english = I18n.tableOf(layer, "en")
            for (tag in I18n.SUPPORTED) {
                if (tag == "en") continue
                val localized = I18n.tableOf(layer, tag)
                for ((key, template) in localized) {
                    val base = english[key] ?: run { problems += "$layer/$tag: $key is not in the English file"; continue }
                    if (placeholders(base) != placeholders(template)) problems += "$layer/$tag: $key placeholders ${placeholders(template)} vs English ${placeholders(base)}"
                }
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun missingKeyReturnsTheKey() {
        I18n.pinEnglish()
        assertEquals("d_no_such_key", tr("d_no_such_key"))
        assertNull(I18n.template("d_no_such_key"))
    }

    @Test
    fun formattingUsesTheTemplate() {
        I18n.pinEnglish()
        assertEquals("3 members", tr("group_member_many", 3))
        assertEquals("Version 1.2.3", tr("d_about_version", "1.2.3"))
    }

    @Test
    fun switchingIsLiveAndFallsBackToEnglish() {
        I18n.selectLanguage("de")
        assertEquals("de", I18n.effective)
        assertEquals("Abbrechen", tr("common_cancel"))
        // A desktop key reads its German if there is one and English otherwise, never the key.
        assertTrue(I18n.template("d_theme_dark") != null)
        assertTrue(tr("d_theme_dark") != "d_theme_dark")
        I18n.selectLanguage("ja")
        assertEquals("キャンセル", tr("common_cancel"))
        I18n.selectLanguage(I18n.SYSTEM)
        assertEquals(I18n.SYSTEM, I18n.language)
        I18n.pinEnglish()
    }

    @Test
    fun unknownLanguageFallsBackToSystem() {
        I18n.selectLanguage("xx")
        assertEquals(I18n.SYSTEM, I18n.language)
        I18n.pinEnglish()
    }

    @Test
    fun pluralCategoriesFollowCldr() {
        assertEquals("one", I18n.pluralCategory("en", 1))
        assertEquals("other", I18n.pluralCategory("en", 0))
        assertEquals("other", I18n.pluralCategory("de", 2))
        assertEquals("one", I18n.pluralCategory("fr", 0))
        assertEquals("one", I18n.pluralCategory("pt", 1))
        assertEquals("other", I18n.pluralCategory("fr", 2))
        assertEquals("other", I18n.pluralCategory("ja", 1))
        assertEquals("other", I18n.pluralCategory("zh", 1))
        assertEquals("one", I18n.pluralCategory("ru", 21))
        assertEquals("few", I18n.pluralCategory("ru", 3))
        assertEquals("many", I18n.pluralCategory("ru", 5))
        assertEquals("many", I18n.pluralCategory("ru", 11))
        assertEquals("other", I18n.pluralCategory("it", 4))
    }

    @Test
    fun systemMatchReadsSupportedLanguagesOnly() {
        assertEquals("de", I18n.systemMatch(java.util.Locale.GERMANY))
        assertEquals("pt", I18n.systemMatch(java.util.Locale.forLanguageTag("pt-BR")))
        assertEquals("zh", I18n.systemMatch(java.util.Locale.SIMPLIFIED_CHINESE))
        assertEquals("en", I18n.systemMatch(java.util.Locale.forLanguageTag("nl-NL")))
    }
}
