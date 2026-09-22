// SettingsScreen.kt
// CarrierPony Desktop. D11: the Settings destination, a scroll of section cards in the order the
// phone app uses: Accounts, Appearance (theme and language, both live), Notifications and lock,
// Files, Local network, Relay, Updates, About (version, runtime line, links, legal) and More from
// NorseHorse. Every control applies as soon as it changes; there is no Save button to forget.
//
// Rules live elsewhere: Settings.kt holds the persisted values, ThemeState and I18n the live
// switches, UpdateCheck the manifest logic, AppLinks the URLs and the icon cache.

package com.carrierpony.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carrierpony.app.AppConfig
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Paths

private val CONTENT_WIDTH = 720.dp

@Composable
fun SettingsScreen(controller: AppController, session: DesktopSession, accounts: DesktopAccounts) {
    var status by remember { mutableStateOf<String?>(null) }
    val onStatus: (String) -> Unit = { status = it }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(modifier = Modifier.widthIn(max = CONTENT_WIDTH).padding(horizontal = Spacing.Large, vertical = Spacing.Medium), verticalArrangement = Arrangement.spacedBy(Spacing.Large)) {
            TabTitle(tr("settings_title"))
            status?.let { StatusStrip(it) }
            AccountsSection(controller, session, accounts)
            AppearanceSection()
            NotificationsSection(controller.settings, onStatus)
            FilesSection(controller.settings, onStatus)
            LanSection(session)
            RelaySection(onStatus)
            UpdatesSection(onStatus)
            AboutSection(onStatus)
            SectionCard(tr("settings_more_from"), tr("settings_more_sub")) {
                Column {
                    PonyApps.ALL.forEach { app -> AppLinkRow(app, onStatus) }
                    Spacer(Modifier.height(Spacing.Small))
                    LinkRow(tr("settings_pony_family"), Links.PONY_FAMILY, onStatus)
                }
            }
            Spacer(Modifier.height(Spacing.Section))
        }
    }
}

// Shared controls

@Composable
private fun ToggleRow(title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.Tight)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) HelpText(subtitle)
        }
        Spacer(Modifier.width(Spacing.Medium))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = Brand.Accent, checkedThumbColor = androidx.compose.ui.graphics.Color.White)
        )
    }
}

// Accounts

@Composable
private fun AccountsSection(controller: AppController, session: DesktopSession, accounts: DesktopAccounts) {
    val ui = rememberCoroutineScope()
    val me = session.identity.fingerprint.hex
    var adding by remember { mutableStateOf(false) }
    var backingUp by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<String?>(null) }
    var editingName by remember { mutableStateOf(false) }
    var name by remember(me) { mutableStateOf(accounts.profileName(me) ?: "") }
    var busy by remember { mutableStateOf(false) }

    SectionCard(tr("accounts_title"), tr("accounts_sub")) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
            for (a in controller.accountList()) {
                val active = a.fingerprint.hex == me
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Avatar(a.name, 36.dp)
                    Spacer(Modifier.width(Spacing.Medium))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(a.name ?: tr("d_account_unnamed"), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (active) { Spacer(Modifier.width(Spacing.Small)); Text(tr("accounts_active"), style = MaterialTheme.typography.labelSmall, color = Brand.Accent) }
                        }
                        FingerprintTail(UiFormat.shortFingerprint(a.fingerprint))
                    }
                    if (!active) {
                        TextButton(enabled = !busy, onClick = { busy = true; ui.launch { controller.switchAccount(a.fingerprint.hex); busy = false } }) { Text(tr("d_account_switch")) }
                    }
                    TextButton(onClick = { confirmRemove = a.fingerprint.hex }) { Text(tr("accounts_remove"), color = MaterialTheme.colorScheme.error) }
                }
            }
            if (editingName) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(name, { name = it }, label = { Text(tr("settings_your_name")) }, singleLine = true, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(Spacing.Small))
                    TextButton(onClick = { accounts.setProfileName(me, name.trim().ifEmpty { null }); editingName = false }) { Text(tr("common_save")) }
                    TextButton(onClick = { editingName = false; name = accounts.profileName(me) ?: "" }) { Text(tr("common_cancel")) }
                }
                HelpText(tr("settings_profile_body"))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                OutlinedButton(onClick = { editingName = true }) { Text(tr("settings_edit_profile")) }
                OutlinedButton(onClick = { backingUp = true }) { Text(tr("settings_backup")) }
                OutlinedButton(onClick = { adding = true }) { Text(tr("accounts_add")) }
            }
        }
    }

    if (adding) AddAccountDialog(controller, onClose = { adding = false })
    if (backingUp) BackupDialog(accounts, me, onClose = { backingUp = false })
    confirmRemove?.let { hex ->
        ConfirmDialog(
            title = tr("accounts_remove_q"), body = tr("accounts_remove_body"), action = tr("accounts_remove"), destructive = true,
            onConfirm = { confirmRemove = null; ui.launch { controller.removeAccount(hex) } },
            onClose = { confirmRemove = null }
        )
    }
}

// Appearance

@Composable
private fun AppearanceSection() {
    val theme by ThemeState.current
    SectionCard(tr("d_settings_appearance")) {
        Column {
            SubHeading(tr("d_settings_theme"))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Large)) {
                for (option in AppTheme.entries) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(RoundedCornerShape(Radius.Small)).clickable { ThemeState.set(option) }.padding(end = Spacing.Small)) {
                        RadioButton(selected = theme == option, onClick = { ThemeState.set(option) })
                        Text(tr(option.labelKey))
                    }
                }
            }
            Spacer(Modifier.height(Spacing.Medium))
            LanguagePicker()
        }
    }
}

/** A plain DropdownMenu on an OutlinedButton; switching is instant, nothing restarts. */
@Composable
private fun LanguagePicker() {
    var expanded by remember { mutableStateOf(false) }
    val selected = I18n.language
    val systemLabel = tr("d_settings_language_system", I18n.displayNameOf(I18n.systemMatch()))
    val buttonLabel = if (selected == I18n.SYSTEM) systemLabel else I18n.displayNameOf(selected)

    SubHeading(tr("settings_language"))
    Box {
        OutlinedButton(onClick = { expanded = true }, shape = RoundedCornerShape(Radius.Small)) {
            Text(buttonLabel)
            Spacer(Modifier.width(Spacing.Tight))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(systemLabel) }, onClick = { I18n.selectLanguage(I18n.SYSTEM); expanded = false })
            I18n.SUPPORTED.forEach { tag ->
                DropdownMenuItem(text = { Text(I18n.displayNameOf(tag)) }, onClick = { I18n.selectLanguage(tag); expanded = false })
            }
        }
    }
    Spacer(Modifier.height(Spacing.Small))
    HelpText(tr("d_settings_language_note"))
}

// Notifications and lock

@Composable
private fun NotificationsSection(settings: Settings, onStatus: (String) -> Unit) {
    var notifications by remember { mutableStateOf(settings.notifications) }
    var showSender by remember { mutableStateOf(settings.notificationsShowSender) }
    var closeToTray by remember { mutableStateOf(settings.closeToTray) }
    var lockMinutes by remember { mutableStateOf(settings.lockAfterMinutes.toString()) }
    val launcher = remember { LaunchAtLogin() }
    var launchAtLogin by remember { mutableStateOf(launcher.isSupported && launcher.isEnabled()) }

    SectionCard(tr("d_settings_notifications"), tr("d_settings_notifications_sub")) {
        Column {
            ToggleRow(tr("d_settings_notify"), checked = notifications) { notifications = it; settings.notifications = it }
            ToggleRow(tr("d_settings_notify_sender"), tr("d_settings_notify_sender_sub"), checked = showSender, enabled = notifications) { showSender = it; settings.notificationsShowSender = it }
            ToggleRow(tr("d_settings_close_to_tray"), checked = closeToTray) { closeToTray = it; settings.closeToTray = it }
            if (launcher.isSupported) {
                ToggleRow(tr("d_settings_launch_at_login"), checked = launchAtLogin) { on ->
                    if (launcher.setEnabled(on)) launchAtLogin = on else onStatus(tr("d_settings_launch_failed"))
                }
            } else {
                HelpText(tr("d_settings_launch_unavailable"))
            }
            Spacer(Modifier.height(Spacing.Small))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    lockMinutes,
                    { v -> lockMinutes = v.filter { it.isDigit() }.take(4); lockMinutes.toIntOrNull()?.let { settings.lockAfterMinutes = it } },
                    label = { Text(tr("d_settings_lock_minutes")) },
                    singleLine = true,
                    modifier = Modifier.width(220.dp)
                )
                Spacer(Modifier.width(Spacing.Medium))
                HelpText(tr("d_settings_lock_note"))
            }
        }
    }
}

// Files

@Composable
private fun FilesSection(settings: Settings, onStatus: (String) -> Unit) {
    var downloads by remember { mutableStateOf(settings.downloadsDir.toString()) }
    SectionCard(tr("common_files")) {
        Column {
            SubHeading(tr("d_settings_downloads"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    downloads,
                    { downloads = it; if (it.isNotBlank()) settings.downloadsDir = Paths.get(it) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(Spacing.Small))
                OutlinedButton(onClick = {
                    System.setProperty("apple.awt.fileDialogForDirectories", "true")
                    val dialog = FileDialog(null as Frame?, tr("d_settings_downloads_choose"), FileDialog.LOAD)
                    dialog.isVisible = true
                    System.setProperty("apple.awt.fileDialogForDirectories", "false")
                    val dir = dialog.directory
                    val file = dialog.file
                    val chosen = when {
                        dir != null && file != null && java.io.File(dir, file).isDirectory -> java.io.File(dir, file).path
                        dir != null && file != null -> java.io.File(dir).path
                        else -> null
                    }
                    if (chosen != null) { downloads = chosen; settings.downloadsDir = Paths.get(chosen) }
                }) { Text(tr("d_settings_downloads_choose")) }
                Spacer(Modifier.width(Spacing.Small))
                OutlinedButton(onClick = { openFolder(settings.downloadsDir.toFile(), onStatus) }) { Text(tr("d_settings_open_folder")) }
            }
            Spacer(Modifier.height(Spacing.Small))
            HelpText(tr("d_settings_downloads_note"))
        }
    }
}

// Local network

@Composable
private fun LanSection(session: DesktopSession) {
    var lanDirect by remember { mutableStateOf(AppConfig.lanDirectEnabled()) }
    var lanSkipRelay by remember { mutableStateOf(AppConfig.lanDirectSkipRelay()) }
    val nearby by (session.lanDiscovery.nearby).collectAsState()
    val reachable by session.lanDiscovery.reachable.collectAsState()
    SectionCard(tr("settings_lan_title"), tr("settings_lan_sub")) {
        Column {
            ToggleRow(tr("settings_lan_direct"), tr("settings_lan_direct_sub"), checked = lanDirect) { on ->
                lanDirect = on
                AppConfig.setLanDirectEnabled(on)
                if (!on) { lanSkipRelay = false; AppConfig.setLanDirectSkipRelay(false) }
                session.lanDidToggle()
            }
            if (lanDirect) {
                ToggleRow(tr("settings_lan_skip_relay"), tr("settings_lan_skip_relay_sub"), checked = lanSkipRelay) { on ->
                    lanSkipRelay = on; AppConfig.setLanDirectSkipRelay(on)
                }
                HelpText(tr("d_settings_lan_firewall"))
                Spacer(Modifier.height(Spacing.Small))
                LabeledValue(tr("d_settings_lan_nearby"), nearby.size.toString())
                LabeledValue(tr("settings_lan_reachable"), reachable.size.toString())
            }
        }
    }
}

// Relay

@Composable
private fun RelaySection(onStatus: (String) -> Unit) {
    var relayUrl by remember { mutableStateOf(if (AppConfig.usingCustomRelay()) AppConfig.relayBaseURL() else "") }
    var custom by remember { mutableStateOf(AppConfig.usingCustomRelay()) }
    SectionCard(tr("settings_relay"), tr("settings_relay_sub")) {
        Column {
            LabeledValue(tr("relay_current"), AppConfig.relayBaseURL(), monospace = true)
            Spacer(Modifier.height(Spacing.Small))
            SubHeading(tr("relay_custom_section"))
            OutlinedTextField(relayUrl, { relayUrl = it }, placeholder = { Text("https://relay.example.com") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(Spacing.Small))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                OutlinedButton(enabled = relayUrl.isNotBlank(), onClick = {
                    if (AppConfig.setRelayBaseURL(relayUrl.trim())) { custom = true; onStatus(tr("relay_saved_body")) } else onStatus(tr("relay_bad_url"))
                }) { Text(tr("relay_use")) }
                if (custom) {
                    OutlinedButton(onClick = { AppConfig.setRelayBaseURL(null); relayUrl = ""; custom = false; onStatus(tr("relay_saved_body")) }) { Text(tr("relay_reset")) }
                }
            }
            Spacer(Modifier.height(Spacing.Small))
            HelpText(tr("d_settings_relay_note"))
            Spacer(Modifier.height(Spacing.Small))
            LinkRow(tr("relay_selfhost_setup"), Links.SELF_HOST, onStatus)
            LinkRow(tr("relay_selfhost_source"), Links.RELAY_REPO, onStatus)
        }
    }
}

// Updates

@Composable
private fun UpdatesSection(onStatus: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val status = UpdateCheck.status
    val latest = UpdateCheck.latestVersion
    val line = if (status == UpdateCheck.Status.Available && latest != null) tr("d_updates_available", latest) else tr(status.labelKey)
    SectionCard(tr("d_settings_updates")) {
        Column {
            Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Spacing.Medium))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                OutlinedButton(enabled = status != UpdateCheck.Status.Checking, onClick = { scope.launch { UpdateCheck.checkNow() } }) { Text(tr("d_updates_check_now")) }
                if (status == UpdateCheck.Status.Available) {
                    OutlinedButton(onClick = { openUri(Links.DESKTOP_DOWNLOAD, onStatus) }) { Text(tr("d_updates_download")) }
                }
            }
            Spacer(Modifier.height(Spacing.Small))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = UpdateCheck.autoEnabled, onCheckedChange = { UpdateCheck.setAuto(it) })
                Spacer(Modifier.width(Spacing.Small))
                Text(tr("d_updates_auto"), style = MaterialTheme.typography.bodyMedium)
            }
            HelpText(tr("d_updates_note"))
        }
    }
}

// About

/** One line, paste-ready for a bug report. */
internal fun runtimeDescription(): String {
    fun p(name: String) = System.getProperty(name).orEmpty()
    return "CarrierPony ${AppVersion.VERSION} · Java ${p("java.version")} · ${p("os.name")} ${p("os.version")} (${p("os.arch")})"
}

@Composable
private fun AboutSection(onStatus: (String) -> Unit) {
    var legal by remember { mutableStateOf<String?>(null) }    // "privacy" or "terms"
    SectionCard(tr("d_settings_about")) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandMark(48.dp)
                Spacer(Modifier.width(Spacing.Medium))
                Column {
                    Text("CarrierPony", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(tr("d_about_version", AppVersion.VERSION), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(Spacing.Medium))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(runtimeDescription(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = { copyToClipboard(runtimeDescription()); onStatus(tr("common_copied")) }) { Text(tr("common_copy")) }
            }
            Spacer(Modifier.height(Spacing.Small))
            LinkRow(tr("d_about_website"), Links.WEBSITE, onStatus)
            LinkRow(tr("d_about_repo"), Links.REPO, onStatus)
            LinkRow(tr("d_about_issues"), Links.ISSUES, onStatus)
            LinkRow(tr("settings_send_feedback"), mailto(Links.SUPPORT_EMAIL, tr("d_feedback_subject")), onStatus)
            Spacer(Modifier.height(Spacing.Small))
            SubHeading(tr("settings_legal"))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                OutlinedButton(onClick = { legal = "privacy" }) { Text(tr("settings_privacy_policy")) }
                OutlinedButton(onClick = { legal = "terms" }) { Text(tr("settings_terms")) }
            }
            Spacer(Modifier.height(Spacing.Small))
            HelpText(tr("d_about_copyright"))
        }
    }
    when (legal) {
        "privacy" -> LegalDialog(tr("settings_privacy_policy"), LegalContent.privacySections, onClose = { legal = null })
        "terms" -> LegalDialog(tr("settings_terms"), LegalContent.termsSections, onClose = { legal = null })
    }
}
