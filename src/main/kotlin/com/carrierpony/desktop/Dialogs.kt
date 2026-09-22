// Dialogs.kt
// CarrierPony Desktop. D11: every modal in the app, on BrandDialog. Pairing (invite with QR and
// paste), picking a contact for a new message, new group and channel, joining a channel, members,
// safety number, nickname, the generic confirm, adding an account, exporting a backup, and the
// legal texts.

package com.carrierpony.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.app.messaging.ChatGroup
import com.carrierpony.app.messaging.Contact
import com.carrierpony.app.pairing.ChannelInvite
import com.carrierpony.app.pairing.Invite
import com.carrierpony.app.pairing.SafetyNumber
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame

// Generic

/** One question, one destructive or ordinary answer. */
@Composable
fun ConfirmDialog(title: String, body: String, action: String, destructive: Boolean = false, onConfirm: () -> Unit, onClose: () -> Unit) {
    BrandDialog(
        onDismissRequest = onClose,
        title = title,
        destructive = destructive,
        confirmButton = {
            if (destructive) {
                Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) { Text(action) }
            } else {
                BrandButton(onClick = onConfirm) { Text(action) }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(tr("common_cancel")) } }
    ) { Text(body) }
}

/** A row in a contact list: avatar, name, tick, fingerprint tail. */
@Composable
private fun ContactRow(contact: Contact, trailing: @Composable () -> Unit = {}, onClick: (() -> Unit)? = null) {
    val name = contact.displayName ?: UiFormat.shortFingerprint(contact.fingerprint)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.Small)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 6.dp, horizontal = 4.dp)
    ) {
        Avatar(name, 34.dp)
        Spacer(Modifier.width(Spacing.Medium))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (contact.trust == com.carrierpony.app.messaging.TrustLevel.VERIFIED) { Spacer(Modifier.width(4.dp)); VerifiedMark() }
            }
            FingerprintTail(UiFormat.shortFingerprint(contact.fingerprint))
        }
        trailing()
    }
}

// Messages

@Composable
fun NewMessageDialog(contacts: List<Contact>, onClose: () -> Unit, onPick: (Contact) -> Unit) {
    BrandDialog(
        onDismissRequest = onClose,
        title = tr("inbox_new_message"),
        confirmButton = { TextButton(onClick = onClose) { Text(tr("common_cancel")) } }
    ) {
        LazyColumn(modifier = Modifier.heightIn(max = 360.dp).widthIn(min = 320.dp)) {
            items(contacts, key = { it.fingerprint.hex }) { c -> ContactRow(c, onClick = { onPick(c) }) }
        }
    }
}

@Composable
fun SafetyNumberDialog(session: DesktopSession, contact: Contact, onClose: () -> Unit) {
    val name = contact.displayName ?: tr("safety_your_contact")
    BrandDialog(
        onDismissRequest = onClose,
        title = tr("safety_title"),
        confirmButton = {
            if (contact.trust != com.carrierpony.app.messaging.TrustLevel.VERIFIED) {
                BrandButton(onClick = { session.contacts.markVerified(contact.fingerprint); onClose() }) { Text(tr("safety_mark")) }
            } else {
                TextButton(onClick = onClose) { Text(tr("common_done")) }
            }
        },
        dismissButton = { if (contact.trust != com.carrierpony.app.messaging.TrustLevel.VERIFIED) TextButton(onClick = onClose) { Text(tr("common_close")) } }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
            Text(tr("safety_compare", name))
            SelectionContainer {
                Text(SafetyNumber.grouped(session.identity.fingerprint, contact.fingerprint), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
            }
            HelpText(tr("safety_only_after"))
        }
    }
}

@Composable
fun NicknameDialog(session: DesktopSession, contact: Contact, onClose: () -> Unit) {
    var nickname by remember { mutableStateOf(contact.nickname ?: "") }
    BrandDialog(
        onDismissRequest = onClose,
        title = tr("chat_nickname"),
        confirmButton = { BrandButton(onClick = { session.contacts.setNickname(contact.fingerprint, nickname.trim().ifEmpty { null }); onClose() }) { Text(tr("common_save")) } },
        dismissButton = {
            Row {
                if (contact.nickname != null) TextButton(onClick = { session.contacts.setNickname(contact.fingerprint, null); onClose() }) { Text(tr("chat_remove_nickname")) }
                TextButton(onClick = onClose) { Text(tr("common_cancel")) }
            }
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
            HelpText(tr("chat_nickname_body"))
            OutlinedTextField(nickname, { nickname = it }, label = { Text(tr("chat_nickname")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }
}

// Groups and channels

@Composable
fun MembersDialog(session: DesktopSession, group: ChatGroup, onClose: () -> Unit, onLeft: () -> Unit) {
    val ui = rememberCoroutineScope()
    val me = session.identity.fingerprint
    val isAdmin = group.isAdmin(me)
    val contacts by session.contacts.contactsFlow.collectAsState()
    var newName by remember(group.groupID) { mutableStateOf(group.name) }
    var adding by remember(group.groupID) { mutableStateOf<Set<Fingerprint>>(emptySet()) }
    var status by remember(group.groupID) { mutableStateOf<String?>(null) }
    var confirmLeave by remember(group.groupID) { mutableStateOf(false) }
    val notYet = contacts.filter { c -> group.member(c.fingerprint) == null }

    BrandDialog(
        onDismissRequest = onClose,
        title = group.name,
        confirmButton = { TextButton(onClick = onClose) { Text(tr("common_close")) } },
        dismissButton = {
            if (!confirmLeave) {
                TextButton(onClick = { confirmLeave = true }) { Text(if (group.isChannel && !isAdmin) tr("channel_unsubscribe") else tr("group_leave"), color = MaterialTheme.colorScheme.error) }
            } else {
                TextButton(onClick = {
                    ui.launch {
                        withContext(Dispatchers.Default) {
                            if (group.isChannel && !isAdmin) session.store.unsubscribeFromChannel(group.groupID) else session.store.leaveGroup(group.groupID)
                        }
                        onLeft()
                    }
                }) { Text(tr("d_confirm_leave"), color = MaterialTheme.colorScheme.error) }
            }
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small), modifier = Modifier.widthIn(min = 360.dp)) {
            if (group.isChannel && isAdmin) {
                val invite = session.store.channelInvite(group.groupID)
                if (invite != null) {
                    HelpText(tr("channel_invite_body"))
                    OutlinedButton(onClick = { copyToClipboard(invite); status = tr("common_copied") }) { Text(tr("channel_share_invite")) }
                }
            }
            SubHeading(if (group.isChannel) tr("channel_subscribers") else tr("group_members"))
            LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                items(group.members, key = { it.fingerprint.hex }) { member ->
                    val name = session.contacts.contact(member.fingerprint)?.displayName ?: member.name ?: UiFormat.shortFingerprint(member.fingerprint)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Avatar(name, 30.dp)
                        Spacer(Modifier.width(Spacing.Medium))
                        Column(Modifier.weight(1f)) {
                            Text(name + (if (member.fingerprint == me) " (" + tr("d_you") + ")" else ""), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (member.isAdmin) Text(tr("group_admin"), style = MaterialTheme.typography.labelSmall, color = Brand.Accent)
                        }
                        if (isAdmin && member.fingerprint != me) {
                            TextButton(onClick = { ui.launch { withContext(Dispatchers.Default) { session.store.removeMember(member.fingerprint, group.groupID) } } }) { Text(tr("group_remove")) }
                        }
                    }
                }
            }
            if (isAdmin && notYet.isNotEmpty()) {
                SubHeading(if (group.isChannel) tr("channel_add_subscribers") else tr("group_add_members"))
                LazyColumn(modifier = Modifier.heightIn(max = 160.dp)) {
                    items(notYet, key = { it.fingerprint.hex }) { c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = c.fingerprint in adding, onCheckedChange = { on -> adding = if (on) adding + c.fingerprint else adding - c.fingerprint })
                            Text(c.displayName ?: UiFormat.shortFingerprint(c.fingerprint))
                        }
                    }
                }
                OutlinedButton(enabled = adding.isNotEmpty(), onClick = {
                    val chosen = notYet.filter { it.fingerprint in adding }
                    adding = emptySet()
                    ui.launch {
                        withContext(Dispatchers.Default) {
                            if (group.isChannel) session.store.addSubscribers(chosen, group.groupID) else session.store.addMembers(chosen, group.groupID)
                        }
                    }
                }) { Text(tr("group_add")) }
            }
            if (isAdmin) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newName, { newName = it }, label = { Text(tr("group_name")) }, singleLine = true, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(Spacing.Small))
                    TextButton(enabled = newName.trim().isNotEmpty() && newName.trim() != group.name, onClick = {
                        ui.launch { withContext(Dispatchers.Default) { session.store.renameGroup(group.groupID, newName) } }
                    }) { Text(tr("group_rename")) }
                }
            }
            if (confirmLeave) HelpText(if (group.isChannel && !isAdmin) tr("channel_unsubscribe_body") else tr("group_leave_body"))
            status?.let { StatusStrip(it) }
        }
    }
}

@Composable
fun NewGroupDialog(session: DesktopSession, isChannel: Boolean, contacts: List<Contact>, onClose: () -> Unit) {
    val ui = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<Set<Fingerprint>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    BrandDialog(
        onDismissRequest = onClose,
        title = if (isChannel) tr("channel_new") else tr("group_new"),
        confirmButton = {
            BrandButton(enabled = !busy && name.isNotBlank() && (isChannel || chosen.isNotEmpty()), onClick = {
                busy = true
                val members = contacts.filter { it.fingerprint in chosen }
                ui.launch {
                    withContext(Dispatchers.Default) {
                        if (isChannel) session.store.createChannel(name.trim(), members) else session.store.createGroup(name.trim(), members)
                    }
                    onClose()
                }
            }) { Text(tr("group_create")) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(tr("common_cancel")) } }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small), modifier = Modifier.widthIn(min = 360.dp)) {
            HelpText(if (isChannel) tr("d_channel_new_body") else tr("d_group_new_body"))
            OutlinedTextField(name, { name = it }, label = { Text(tr("group_name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (contacts.isEmpty()) HelpText(tr("group_need_contacts"))
            LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                items(contacts, key = { it.fingerprint.hex }) { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = c.fingerprint in chosen, onCheckedChange = { on -> chosen = if (on) chosen + c.fingerprint else chosen - c.fingerprint })
                        Text(c.displayName ?: UiFormat.shortFingerprint(c.fingerprint))
                    }
                }
            }
        }
    }
}

@Composable
fun JoinChannelDialog(session: DesktopSession, onClose: () -> Unit) {
    val ui = rememberCoroutineScope()
    var pasted by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    BrandDialog(
        onDismissRequest = onClose,
        title = tr("channel_subscribe"),
        confirmButton = {
            BrandButton(enabled = !busy && pasted.isNotBlank(), onClick = {
                val invite = ChannelInvite.decode(pasted.trim())
                if (invite == null) { status = tr("channel_subscribe_bad") }
                else {
                    busy = true
                    ui.launch {
                        val ok = try { withContext(Dispatchers.Default) { session.store.subscribeToChannel(invite) } } catch (e: Exception) { false }
                        if (ok) onClose() else { status = tr("pair_couldnt_reach"); busy = false }
                    }
                }
            }) { Text(tr("channel_subscribe")) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(tr("common_cancel")) } }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small), modifier = Modifier.widthIn(min = 360.dp)) {
            HelpText(tr("channel_subscribe_sent"))
            OutlinedTextField(pasted, { pasted = it }, label = { Text(tr("channel_subscribe_paste")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            status?.let { StatusStrip(it, error = true) }
        }
    }
}

// Pairing

@Composable
fun AddContactDialog(session: DesktopSession, onClose: () -> Unit, onPaired: (Contact) -> Unit) {
    val ui = rememberCoroutineScope()
    var invite by remember { mutableStateOf<Invite?>(null) }
    var inPerson by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // While our invite is on screen, watch for the other side accepting it.
    val shown = invite
    LaunchedEffect(shown) {
        if (shown != null) {
            while (true) {
                delay(2000)
                val contact = try {
                    withContext(Dispatchers.Default) { session.pairing.pollInvite(shown.t) }
                } catch (e: Exception) {
                    status = e.message; null
                }
                if (contact != null) { onPaired(contact); onClose(); break }
            }
        }
    }

    BrandDialog(
        onDismissRequest = onClose,
        title = tr("settings_pair_with"),
        confirmButton = { TextButton(onClick = onClose) { Text(tr("common_close")) } }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.widthIn(min = 380.dp).verticalScroll(rememberScrollState())) {
            if (shown == null) {
                SubHeading(tr("pair_create_invite_title"))
                HelpText(tr("d_pair_create_body"))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = inPerson, onCheckedChange = { inPerson = it })
                    Text(tr("d_pair_in_person_hint"))
                }
                BrandButton(enabled = !busy, onClick = {
                    busy = true; status = null
                    ui.launch {
                        try {
                            invite = withContext(Dispatchers.Default) { session.pairing.createInvite(inPerson) }.first
                        } catch (e: Exception) {
                            status = tr("pair_couldnt_reach")
                        }
                        busy = false
                    }
                }) { Text(if (busy) tr("pair_creating_invite") else tr("pair_create_invite")) }

                HairlineRule(Modifier.padding(vertical = Spacing.Tight))

                SubHeading(tr("pair_enter_invite_title"))
                HelpText(tr("d_pair_enter_body"))
                OutlinedTextField(pasted, { pasted = it }, label = { Text(tr("pair_invite_cd")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                BrandButton(enabled = !busy && pasted.isNotBlank(), onClick = {
                    busy = true; status = null
                    ui.launch {
                        try {
                            val contact = withContext(Dispatchers.Default) { session.pairing.acceptInvite(pasted.trim()) }
                            onPaired(contact); onClose()
                        } catch (e: Exception) {
                            status = e.message ?: tr("pair_couldnt_pair_invite")
                        }
                        busy = false
                    }
                }) { Text(if (busy) tr("pair_pairing") else tr("pair_enter_invite")) }
            } else {
                val code = shown.encoded()
                HelpText(tr("pair_waiting_accept"))
                HelpText(tr("d_pair_waiting_body"))
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrCode(code) }
                SelectionContainer { Text(code, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = { copyToClipboard(code); status = tr("common_copied") }) { Text(tr("pair_share_invite")) }
            }
            status?.let { StatusStrip(it, error = shown == null && it != tr("common_copied")) }
        }
    }
}

@Composable
fun QrCode(text: String) {
    val matrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2))
    }
    // Always black on white, whatever the theme: phone cameras expect it.
    Canvas(modifier = Modifier.size(240.dp).clip(RoundedCornerShape(Radius.Medium)).background(Color.White)) {
        val cell = size.minDimension / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix.get(x, y)) {
                    drawRect(Color.Black, topLeft = Offset(x * cell, y * cell), size = Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}

// Accounts

/** A new identity, or one restored from a backup. Used from the switcher and from Settings. */
@Composable
fun AddAccountDialog(controller: AppController, onClose: () -> Unit) {
    val ui = rememberCoroutineScope()
    val error by controller.error.collectAsState()
    var fromBackup by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var blob by remember { mutableStateOf("") }
    var backupPassphrase by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { controller.clearError() }
    BrandDialog(
        onDismissRequest = onClose,
        title = tr("accounts_add"),
        confirmButton = {
            if (!fromBackup) {
                BrandButton(enabled = !busy, onClick = { busy = true; ui.launch { if (controller.addAccount(name)) onClose() else busy = false } }) { Text(tr("onb_create")) }
            } else {
                BrandButton(enabled = !busy && blob.isNotBlank(), onClick = { busy = true; ui.launch { if (controller.addAccountFromBackup(blob, backupPassphrase)) onClose() else busy = false } }) { Text(tr("restore_button")) }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(tr("common_cancel")) } }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small), modifier = Modifier.widthIn(min = 380.dp)) {
            HelpText(tr("accounts_add_limit"))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                OutlinedButton(onClick = { fromBackup = false; controller.clearError() }, enabled = fromBackup) { Text(tr("onb_create")) }
                OutlinedButton(onClick = { fromBackup = true; controller.clearError() }, enabled = !fromBackup) { Text(tr("onb_restore")) }
            }
            if (!fromBackup) {
                OutlinedTextField(name, { name = it }, label = { Text(tr("onb_name_optional")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            } else {
                OutlinedTextField(blob, { blob = it }, label = { Text(tr("d_backup_label")) }, modifier = Modifier.fillMaxWidth().height(140.dp))
                SecretField(backupPassphrase, { backupPassphrase = it }, tr("backup_passphrase"), onEnter = {})
                HelpText(tr("d_restore_one_device"))
            }
            ErrorText(error)
        }
    }
}

/** Export the active identity as an encrypted backup: choose a passphrase, copy or save the blob. */
@Composable
fun BackupDialog(accounts: DesktopAccounts, fprHex: String, onClose: () -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var blob by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val ready = blob
    BrandDialog(
        onDismissRequest = onClose,
        title = tr("backup_title"),
        confirmButton = {
            if (ready == null) {
                BrandButton(enabled = first.length >= 8 && first == second, onClick = {
                    blob = try { accounts.exportBackup(fprHex, first) } catch (e: Exception) { status = tr("backup_create_failed"); null }
                }) { Text(tr("backup_create")) }
            } else {
                TextButton(onClick = onClose) { Text(tr("common_done")) }
            }
        },
        dismissButton = { if (ready == null) TextButton(onClick = onClose) { Text(tr("common_cancel")) } }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small), modifier = Modifier.widthIn(min = 380.dp)) {
            if (ready == null) {
                HelpText(tr("backup_warning"))
                SecretField(first, { first = it }, tr("backup_passphrase"), onEnter = {})
                SecretField(second, { second = it }, tr("backup_confirm"), onEnter = {})
                if (first.isNotEmpty() && first.length < 8) HelpText(tr("backup_min8"))
                else if (second.isNotEmpty() && first != second) HelpText(tr("backup_mismatch"))
            } else {
                HelpText(tr("backup_ready"))
                SelectionContainer {
                    Text(ready, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                    OutlinedButton(onClick = { copyToClipboard(ready); status = tr("common_copied") }) { Text(tr("common_copy")) }
                    OutlinedButton(onClick = {
                        val dialog = FileDialog(null as Frame?, tr("backup_title"), FileDialog.SAVE)
                        dialog.file = "carrierpony-backup-${fprHex.takeLast(8).uppercase()}.txt"
                        dialog.isVisible = true
                        val dir = dialog.directory
                        val name = dialog.file
                        if (dir != null && name != null) {
                            status = try {
                                java.nio.file.Files.write(java.nio.file.Paths.get(dir, name), ready.toByteArray(Charsets.UTF_8))
                                tr("d_files_saved", java.nio.file.Paths.get(dir).fileName.toString(), name)
                            } catch (e: Exception) {
                                tr("backup_save_failed")
                            }
                        }
                    }) { Text(tr("d_backup_save_file")) }
                }
            }
            status?.let { StatusStrip(it) }
        }
    }
}

// Legal

@Composable
fun LegalDialog(title: String, sections: List<Pair<String, String>>, onClose: () -> Unit) {
    BrandDialog(
        onDismissRequest = onClose,
        title = title,
        confirmButton = { TextButton(onClick = onClose) { Text(tr("common_done")) } }
    ) {
        Column(modifier = Modifier.widthIn(min = 420.dp, max = 560.dp).heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            HelpText(LegalContent.updated)
            for ((heading, body) in sections) {
                Spacer(Modifier.height(Spacing.Large))
                Text(heading, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(Spacing.Tight))
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
