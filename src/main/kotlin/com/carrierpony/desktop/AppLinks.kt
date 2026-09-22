// AppLinks.kt
// CarrierPony Desktop. D11: outbound links from Settings (the project's own repo and site, the
// self-host page, the rest of the NorseHorse family), the browser handoff, and the icon cache.
//
// openUri() is only ever called with the constants in this file or a mailto built here. Nothing
// takes a URL from a message, an invite, a contact or any other attacker-influenced source;
// handing java.awt.Desktop an untrusted URI is how you get a file: or smb: surprise.

package com.carrierpony.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.util.Locale
import javax.imageio.ImageIO

object Links {
    const val WEBSITE = "https://carrierpony.com"
    const val DESKTOP_DOWNLOAD = "$WEBSITE/desktop"
    const val SELF_HOST = "$WEBSITE/self-host"
    const val REPO = "https://github.com/norsehorse-dev/CarrierPonyDesktop"
    const val ISSUES = "$REPO/issues"
    const val RELAY_REPO = "https://github.com/norsehorse-dev/CarrierPony-Relay"
    const val PONY_FAMILY = "https://pony.norsehor.se"
    const val SUPPORT_EMAIL = "support@carrierpony.com"
}

/**
 * One entry in the "More from NorseHorse" list. [title] and [platforms] are product and OS names,
 * identical in every language, so only [descriptionKey] is translated. [icon] names a PNG under
 * resources/icons/, downscaled from that app's own icon master.
 */
data class PonyApp(val title: String, val url: String, val descriptionKey: String, val platforms: String, val icon: String)

object PonyApps {
    /** Same order as the Android settings screen. */
    val ALL: List<PonyApp> = listOf(
        PonyApp("PGPony", "https://pgpony.app", "settings_sub_pgpony", "iPhone · Android · macOS · Windows · Linux", "pgpony"),
        PonyApp("AgePony", "https://agepony.com", "settings_sub_agepony", "iPhone · Android", "agepony"),
        PonyApp("QuorumPony", "https://quorumpony.com", "settings_sub_quorumpony", "iPhone", "quorumpony"),
        PonyApp("BurnPony", "https://burnpony.app", "settings_sub_burnpony", "iPhone · Android", "burnpony"),
        PonyApp("RelayPony", "https://relaypony.app", "settings_sub_relaypony", "iPhone · Android · macOS · Windows · Linux", "relaypony"),
        PonyApp("ScrubPony", "https://scrubpony.app", "settings_sub_scrubpony", "Android", "scrubpony"),
        PonyApp("VaultPony", "https://vaultpony.app", "settings_sub_vaultpony", "Android", "vaultpony"),
        PonyApp("PassPony", "https://passpony.app", "settings_sub_passpony", "Android", "passpony")
    )
}

// Opening things

/**
 * Hand [url] to the user's browser. Desktop.isDesktopSupported() is not enough on its own (a GNOME
 * session without a portal answers yes and then throws from browse), so the fallbacks are the
 * platform opener command, then the clipboard. [onStatus] gets one line if it came to that.
 */
fun openUri(url: String, onStatus: ((String) -> Unit)? = null) {
    if (browse(url)) return
    if (shellOpen(url)) return
    copyToClipboard(url)
    onStatus?.invoke(tr("d_status_open_failed", url))
}

/** Reveal [file] in Finder, Explorer or the Linux file manager. */
fun openFolder(file: File, onStatus: ((String) -> Unit)? = null) {
    val ok = runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            Desktop.getDesktop().open(file); true
        } else false
    }.getOrDefault(false) || shellOpen(file.absolutePath)
    if (!ok) {
        copyToClipboard(file.absolutePath)
        onStatus?.invoke(tr("d_status_open_failed", file.absolutePath))
    }
}

/** A mailto: link, with the subject already filled in. */
fun mailto(address: String, subject: String): String {
    val encoded = java.net.URLEncoder.encode(subject, "UTF-8").replace("+", "%20")
    return "mailto:$address?subject=$encoded"
}

fun copyToClipboard(text: String) {
    runCatching {
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null)
    }
}

private fun browse(url: String): Boolean = runCatching {
    if (!Desktop.isDesktopSupported()) return false
    val desktop = Desktop.getDesktop()
    if (!desktop.isSupported(Desktop.Action.BROWSE)) return false
    desktop.browse(URI(url))
    true
}.getOrDefault(false)

private fun shellOpen(target: String): Boolean {
    val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
    val cmd = when {
        os.contains("mac") -> arrayOf("open", target)
        os.contains("win") -> arrayOf("rundll32", "url.dll,FileProtocolHandler", target)
        else -> arrayOf("xdg-open", target)
    }
    return runCatching { ProcessBuilder(*cmd).start(); true }.getOrDefault(false)
}

// Icons

/**
 * Decode resources/icons/<name>.png once and keep it. ImageIO plus BufferedImage.toPainter() has
 * been the same two-line desktop bridge since Compose Desktop 1.0, unlike the resource loaders
 * that moved between releases. A missing file yields null and the caller draws its placeholder.
 */
private val iconCache = HashMap<String, Painter?>()

@Synchronized
fun appIcon(name: String): Painter? = iconCache.getOrPut(name) {
    runCatching {
        val stream = PonyApps::class.java.getResourceAsStream("/icons/$name.png") ?: return@runCatching null
        stream.use { ImageIO.read(it) }?.toPainter()
    }.getOrNull()
}

// Rows

/** A one-line link: label on the left, the bare host on the right, the whole row clickable. */
@Composable
fun LinkRow(label: String, url: String, onStatus: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.Small)).clickable { openUri(url, onStatus) }.padding(vertical = 6.dp)
    ) {
        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp), tint = Brand.Accent)
        Spacer(Modifier.width(Spacing.Small))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(10.dp))
        Text(url.removePrefix("https://").removePrefix("mailto:"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Icon, name, platforms and one line of what the app does; the whole row opens its site. */
@Composable
fun AppLinkRow(app: PonyApp, onStatus: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(Radius.Medium),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(Radius.Medium)).clickable { openUri(app.url, onStatus) }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            val painter = appIcon(app.icon)
            if (painter != null) {
                Image(painter = painter, contentDescription = null, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(9.dp)))
            } else {
                Avatar(app.title, 40.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(app.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    Text(app.platforms, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(2.dp))
                Text(tr(app.descriptionKey), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(10.dp))
            Text(app.url.removePrefix("https://"), style = MaterialTheme.typography.bodySmall, color = Brand.Accent)
        }
    }
}
