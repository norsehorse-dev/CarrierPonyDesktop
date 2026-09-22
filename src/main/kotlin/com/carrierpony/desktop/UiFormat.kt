// UiFormat.kt
// CarrierPony Desktop. The pure label formatters the list and thread use, a copy of Android's
// ui/Components.kt UiFormat (DRIFT WATCH: ui/ is not vendored). English defaults keep the object
// testable without resources; the tr-backed wrappers below give the UI the localized words.

package com.carrierpony.desktop

import com.carrierpony.app.crypto.Fingerprint
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object UiFormat {

    /** The last 8 hex characters, grouped in fours: "1234 ABCD". */
    fun shortFingerprint(fingerprint: Fingerprint): String = shortFingerprint(fingerprint.hex)

    fun shortFingerprint(hex: String): String = hex.uppercase().takeLast(8).chunked(4).joinToString(" ")

    /** Inbox-row time: time today, "Yesterday", weekday within a week, else "Jul 8". */
    fun listTimeLabel(
        sentAt: Long,
        now: Long = System.currentTimeMillis() / 1000,
        locale: Locale = Locale.getDefault(),
        yesterdayWord: String = "Yesterday"
    ): String {
        val sent = Calendar.getInstance().apply { timeInMillis = sentAt * 1000 }
        val today = Calendar.getInstance().apply { timeInMillis = now * 1000 }

        fun sameDay(a: Calendar, b: Calendar) =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

        if (sameDay(sent, today)) return DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(Date(sentAt * 1000))
        val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        if (sameDay(sent, yesterday)) return yesterdayWord
        val daysAgo = ((now - sentAt) / 86_400).toInt()
        if (daysAgo in 0..6) return SimpleDateFormat("EEE", locale).format(Date(sentAt * 1000))
        return SimpleDateFormat("MMM d", locale).format(Date(sentAt * 1000))
    }

    /** Bubble time: short time only. */
    fun messageTimeLabel(sentAt: Long, locale: Locale = Locale.getDefault()): String =
        DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(Date(sentAt * 1000))

    /** Day separator: "Today", "Yesterday", else "Tuesday, Jul 8". */
    fun dayLabel(
        dayStartSeconds: Long,
        now: Long = System.currentTimeMillis() / 1000,
        locale: Locale = Locale.getDefault(),
        todayWord: String = "Today",
        yesterdayWord: String = "Yesterday"
    ): String {
        val todayStart = startOfDay(now)
        return when (dayStartSeconds) {
            todayStart -> todayWord
            todayStart - 86_400 -> yesterdayWord
            else -> SimpleDateFormat("EEEE, MMM d", locale).format(Date(dayStartSeconds * 1000))
        }
    }

    fun startOfDay(epochSeconds: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = epochSeconds * 1000
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis / 1000
    }

    /** "512 B" / "48 KB" / "3.2 MB". */
    fun sizeLabel(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    /** Imminent-expiry indicator: only under a day, "3h" / "12m", else null. */
    fun disappearingLabel(expiresAt: Long, now: Long = System.currentTimeMillis() / 1000): String? {
        val remaining = expiresAt - now
        if (remaining <= 0 || remaining >= 86_400) return null
        val hours = remaining / 3600
        if (hours >= 1) return "${hours}h"
        return "${maxOf(1, remaining / 60)}m"
    }
}

/** Localized wrappers: the words for Today and Yesterday come from the string layers. */
fun dayLabelText(dayStartSeconds: Long): String =
    UiFormat.dayLabel(dayStartSeconds, locale = I18n.localeOf(I18n.effective), todayWord = tr("date_today"), yesterdayWord = tr("date_yesterday"))

fun listTimeLabelText(sentAt: Long): String =
    UiFormat.listTimeLabel(sentAt, locale = I18n.localeOf(I18n.effective), yesterdayWord = tr("date_yesterday"))

fun messageTimeLabelText(sentAt: Long): String =
    UiFormat.messageTimeLabel(sentAt, locale = I18n.localeOf(I18n.effective))
