// Settings.kt
// CarrierPony Desktop. User settings the window and the controller read, over DesktopPrefs.
// Nothing here is secret. Defaults lean private: notifications carry no sender name, and the
// app locks after 15 idle minutes.

package com.carrierpony.desktop

import java.nio.file.Path
import java.nio.file.Paths

class Settings(private val prefs: DesktopPrefs) {

    /** Minutes of no keyboard or mouse activity before the app locks; 0 never locks. */
    var lockAfterMinutes: Int
        get() = prefs.getString(LOCK_MINUTES)?.toIntOrNull()?.coerceIn(0, 24 * 60) ?: 15
        set(value) = prefs.putString(LOCK_MINUTES, value.coerceIn(0, 24 * 60).toString())

    /** Closing the window hides it to the tray and keeps polling; Quit is in the tray menu. */
    var closeToTray: Boolean
        get() = prefs.getBoolean(CLOSE_TO_TRAY, true)
        set(value) = prefs.putBoolean(CLOSE_TO_TRAY, value)

    var notifications: Boolean
        get() = prefs.getBoolean(NOTIFY, true)
        set(value) = prefs.putBoolean(NOTIFY, value)

    /** Show who a message is from in the notification. Off by default: a notification can be
     *  read from across a room, and the relay-facing design keeps contact names off servers;
     *  the desktop's lock screen should not undo that. */
    var notificationsShowSender: Boolean
        get() = prefs.getBoolean(NOTIFY_SENDER, false)
        set(value) = prefs.putBoolean(NOTIFY_SENDER, value)

    var downloadsDir: Path
        get() = prefs.getString(DOWNLOADS)?.takeIf { it.isNotBlank() }?.let { Paths.get(it) } ?: Attachments.defaultDownloadsDir()
        set(value) = prefs.putString(DOWNLOADS, value.toString())

    private companion object {
        const val LOCK_MINUTES = "cp.desktop.lockAfterMinutes"
        const val CLOSE_TO_TRAY = "cp.desktop.closeToTray"
        const val NOTIFY = "cp.desktop.notifications"
        const val NOTIFY_SENDER = "cp.desktop.notificationsShowSender"
        const val DOWNLOADS = "cp.desktop.downloadsDir"
    }
}
