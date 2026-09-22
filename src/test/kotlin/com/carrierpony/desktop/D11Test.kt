// D11Test.kt
// The update check (version comparison, manifest parsing, the opt-in and the daily throttle, all
// without the network), the label formatters, and the theme and language persistence over
// DesktopPrefs.

package com.carrierpony.desktop

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class D11Test {

    private fun prefs(): DesktopPrefs = DesktopPrefs(Files.createTempDirectory("cp-d11").resolve("prefs.json"))

    // UpdateCheck

    @Test
    fun versionComparisonIsNumeric() {
        assertTrue(UpdateCheck.compareVersions("1.0.10", "1.0.9") > 0)
        assertEquals(0, UpdateCheck.compareVersions("1.1", "1.1.0"))
        assertTrue(UpdateCheck.compareVersions("1.1.0-rc.1", "1.1.0") < 0)
        assertTrue(UpdateCheck.compareVersions("junk", "1.0.0") < 0)
        assertTrue(UpdateCheck.isNewer("1.0.1", "1.0.0"))
        assertFalse(UpdateCheck.isNewer("1.0.0", "1.0.0"))
        assertFalse(UpdateCheck.isNewer("", "1.0.0"))
    }

    @Test
    fun manifestParsingNeverThrows() {
        assertEquals("1.2.3", UpdateCheck.parseLatest("""{"current":{"version":"1.2.3","date":"2026-10-01"}}"""))
        assertNull(UpdateCheck.parseLatest("""{"current":{}}"""))
        assertNull(UpdateCheck.parseLatest("not json"))
        assertNull(UpdateCheck.parseLatest(null))
    }

    @Test
    fun automaticCheckIsOptInAndDaily(): Unit = runBlocking {
        UpdateCheck.resetForTests()
        val store = prefs()
        UpdateCheck.attach(store)
        var fetches = 0
        UpdateCheck.fetcher = { fetches++; """{"current":{"version":"9.9.9"}}""" }
        val t0 = 100L * 24L * 3600L * 1000L          // well past the epoch, which is "never checked"

        UpdateCheck.checkIfDue(now = t0)
        assertEquals(0, fetches, "off by default: nothing leaves the machine")
        assertEquals(UpdateCheck.Status.Idle, UpdateCheck.status)

        UpdateCheck.setAuto(true)
        UpdateCheck.checkIfDue(now = t0)
        assertEquals(1, fetches)
        assertEquals(UpdateCheck.Status.Available, UpdateCheck.status)
        assertEquals("9.9.9", UpdateCheck.latestVersion)

        UpdateCheck.checkIfDue(now = t0 + 60_000L)
        assertEquals(1, fetches, "throttled to once a day")
        UpdateCheck.checkIfDue(now = t0 + 25L * 3600L * 1000L)
        assertEquals(2, fetches)

        // The switch and the last result survive a restart.
        UpdateCheck.resetForTests()
        UpdateCheck.attach(store)
        assertTrue(UpdateCheck.autoEnabled)
        assertEquals("9.9.9", UpdateCheck.latestVersion)
        UpdateCheck.resetForTests()
    }

    @Test
    fun explicitCheckReportsFailureWithoutThrowing(): Unit = runBlocking {
        UpdateCheck.resetForTests()
        UpdateCheck.attach(prefs())
        UpdateCheck.fetcher = { null }
        UpdateCheck.checkNow()
        assertEquals(UpdateCheck.Status.Failed, UpdateCheck.status)
        UpdateCheck.fetcher = { """{"current":{"version":"${AppVersion.VERSION}"}}""" }
        UpdateCheck.checkNow()
        assertEquals(UpdateCheck.Status.UpToDate, UpdateCheck.status)
        UpdateCheck.resetForTests()
    }

    // UiFormat

    @Test
    fun labelsReadLikeThePhone() {
        val now = 1_760_000_000L                      // a fixed instant; only day arithmetic matters
        val dayStart = UiFormat.startOfDay(now)
        assertEquals("Today", UiFormat.dayLabel(dayStart, now))
        assertEquals("Yesterday", UiFormat.dayLabel(dayStart - 86_400, now))
        assertEquals("Yesterday", UiFormat.listTimeLabel(dayStart - 3_600, now))
        assertTrue(UiFormat.listTimeLabel(now - 10 * 86_400, now, Locale.US).matches(Regex("[A-Z][a-z]{2} \\d{1,2}")))
        assertEquals("512 B", UiFormat.sizeLabel(512))
        assertEquals("48 KB", UiFormat.sizeLabel(48 * 1024))
        assertEquals("3.2 MB", UiFormat.sizeLabel((3.2 * 1024 * 1024).toLong()))
        assertEquals("ABCD 1234", UiFormat.shortFingerprint("00ff" + "abcd1234"))
        assertNull(UiFormat.disappearingLabel(now + 2 * 86_400, now))
        assertEquals("3h", UiFormat.disappearingLabel(now + 3 * 3600 + 30, now))
        assertEquals("12m", UiFormat.disappearingLabel(now + 12 * 60 + 5, now))
    }

    // Theme and language persistence

    @Test
    fun themeAndLanguagePersistOverPrefs() {
        val store = prefs()
        ThemeState.attach(store)
        assertEquals(AppTheme.System, ThemeState.current.value)
        ThemeState.set(AppTheme.Dark)
        ThemeState.attach(prefs())
        assertEquals(AppTheme.System, ThemeState.current.value, "a fresh store starts on System")
        ThemeState.attach(store)
        assertEquals(AppTheme.Dark, ThemeState.current.value)

        I18n.attach(store)
        I18n.selectLanguage("fr")
        I18n.attach(prefs())
        assertEquals(I18n.SYSTEM, I18n.language)
        I18n.attach(store)
        assertEquals("fr", I18n.language)
        I18n.pinEnglish()
        assertEquals(AppTheme.Light, AppTheme.fromStorage("light"))
        assertEquals(AppTheme.System, AppTheme.fromStorage("purple"))
    }
}
