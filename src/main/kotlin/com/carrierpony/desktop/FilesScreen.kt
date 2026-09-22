// FilesScreen.kt
// CarrierPony Desktop. D11: the Files destination, ported from Android's ui/FilesScreen.kt (DRIFT
// WATCH: ui/ is not vendored). A transfer space over the same message store: every attachment ever
// sent or received, as one reverse-chronological list with a file-manager feel, plus a send flow
// that picks a contact and files directly. Nothing new is stored; this is purely a surface.

package com.carrierpony.desktop

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.app.messaging.ChatMessage
import com.carrierpony.app.messaging.Contact
import com.carrierpony.app.messaging.MessageDirection
import com.carrierpony.app.messaging.OutgoingMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path

/** One attachment as a transfer: who, which way, when. */
private data class FileTransfer(val attachment: ChatMessage.Attachment, val peer: Fingerprint, val groupName: String?, val direction: MessageDirection, val sentAt: Long)

@Composable
fun FilesScreen(session: DesktopSession, drops: MutableStateFlow<List<Path>>) {
    val conversations by session.store.conversations.collectAsState()
    val groups by session.store.groups.collectAsState()
    val groupMessages by session.store.groupMessages.collectAsState()
    val contacts by session.contacts.contactsFlow.collectAsState()
    var showingSend by remember { mutableStateOf(false) }
    var droppedFiles by remember { mutableStateOf<List<Path>>(emptyList()) }

    val transfers = remember(conversations, groupMessages, groups) {
        val direct = conversations.values.flatMap { conversation ->
            conversation.messages.flatMap { m -> m.attachments.map { FileTransfer(it, m.peer, null, m.direction, m.sentAt) } }
        }
        val inGroups = groupMessages.flatMap { (groupID, messages) ->
            val name = groups[groupID]?.name
            messages.flatMap { m -> m.attachments.map { FileTransfer(it, m.peer, name, m.direction, m.sentAt) } }
        }
        (direct + inGroups).sortedByDescending { it.sentAt }
    }

    // Files dropped while this tab is open start the send flow with them already picked.
    val dropped by drops.collectAsState()
    LaunchedEffect(dropped) {
        if (dropped.isNotEmpty()) { droppedFiles = dropped; drops.value = emptyList(); showingSend = true }
    }

    fun name(peer: Fingerprint): String =
        contacts.firstOrNull { it.fingerprint == peer }?.displayName ?: UiFormat.shortFingerprint(peer)

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Large, vertical = Spacing.Medium), verticalAlignment = Alignment.CenterVertically) {
            TabTitle(tr("common_files"))
            Spacer(Modifier.weight(1f))
            RoundIconButton(Icons.AutoMirrored.Filled.Send, contentDescription = tr("files_send_cd"), onClick = { droppedFiles = emptyList(); showingSend = true })
        }
        if (transfers.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Filled.Folder,
                    title = tr("files_no_files"),
                    message = tr("files_empty_body"),
                    action = { if (contacts.isNotEmpty()) BrandButton(onClick = { showingSend = true }) { Text(tr("files_send_cd")) } }
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(transfers, key = { it.attachment.id + it.sentAt }) { transfer ->
                    TransferRow(transfer, transfer.groupName ?: name(transfer.peer))
                    HairlineRule(Modifier.padding(start = 72.dp))
                }
            }
        }
    }

    if (showingSend) SendFileDialog(session, contacts, initial = droppedFiles, onClose = { showingSend = false; droppedFiles = emptyList() })
}

@Composable
private fun TransferRow(transfer: FileTransfer, contactName: String) {
    val settings = LocalSettings.current
    val attachment = transfer.attachment
    val incoming = transfer.direction == MessageDirection.INCOMING
    var menu by remember { mutableStateOf(false) }
    var status by remember(attachment.id) { mutableStateOf<String?>(null) }
    var showingFull by remember { mutableStateOf(false) }
    val items = attachmentMenu(attachment) { status = it }

    ContextMenuArea(items = { items }) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { if (attachment.isImage) showingFull = true else status = openAttachment(attachment) }.padding(horizontal = Spacing.Large, vertical = Spacing.Small)
            ) {
                FileThumb(attachment)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(attachment.filename, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (incoming) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                            contentDescription = null,
                            tint = if (incoming) MaterialTheme.colorScheme.tertiary else Brand.Accent,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = (if (incoming) tr("files_from") else tr("files_to")) + contactName + " · " + UiFormat.sizeLabel(attachment.size) + " · " + listTimeLabelText(transfer.sentAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = tr("files_actions"), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(tr("d_files_save_downloads")) }, onClick = { menu = false; status = saveAttachment(attachment, settings) })
                        DropdownMenuItem(text = { Text(tr("d_files_save_as")) }, onClick = { menu = false; status = saveAttachmentAs(attachment) })
                        DropdownMenuItem(text = { Text(tr("d_files_open")) }, onClick = { menu = false; status = openAttachment(attachment) })
                    }
                }
            }
            status?.let { StatusStrip(it, modifier = Modifier.padding(horizontal = Spacing.Large).padding(bottom = Spacing.Small)) }
        }
    }

    if (showingFull && attachment.isImage) {
        val full by produceState<ImageBitmap?>(initialValue = null, attachment.localPath) {
            value = withContext(Dispatchers.IO) { runCatching { Attachments.thumbnail(attachment, 1600)?.toComposeImageBitmap() }.getOrNull() }
        }
        BrandDialog(
            onDismissRequest = { showingFull = false },
            title = attachment.filename,
            confirmButton = { TextButton(onClick = { showingFull = false }) { Text(tr("common_close")) } },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                    OutlinedButton(onClick = { status = saveAttachment(attachment, settings) }) { Text(tr("d_files_save_downloads")) }
                    OutlinedButton(onClick = { status = openAttachment(attachment) }) { Text(tr("d_files_open")) }
                }
            }
        ) {
            val bitmap = full
            if (bitmap != null) {
                Image(bitmap = bitmap, contentDescription = attachment.filename, modifier = Modifier.widthIn(max = 800.dp).heightIn(max = 600.dp).clip(RoundedCornerShape(Radius.Medium)).clickable { showingFull = false })
            } else {
                Text(tr("d_files_loading"), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun FileThumb(attachment: ChatMessage.Attachment) {
    if (attachment.isImage) {
        val bitmap by produceState<ImageBitmap?>(initialValue = null, attachment.localPath) {
            value = withContext(Dispatchers.IO) { runCatching { Attachments.thumbnail(attachment, 128)?.toComposeImageBitmap() }.getOrNull() }
        }
        val current = bitmap
        if (current != null) {
            Image(bitmap = current, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)))
            return
        }
    }
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.InsertDriveFile, contentDescription = null, tint = Brand.Accent, modifier = Modifier.size(20.dp))
        }
    }
}

// Send a file

@Composable
private fun SendFileDialog(session: DesktopSession, contacts: List<Contact>, initial: List<Path>, onClose: () -> Unit) {
    val ui = rememberCoroutineScope()
    var selected by remember { mutableStateOf<Contact?>(null) }
    var picked by remember { mutableStateOf<List<OutgoingMessage.Attachment>>(emptyList()) }
    var notice by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }

    fun addPaths(paths: List<Path>) {
        if (paths.isEmpty()) return
        val already = picked.sumOf { it.data.size.toLong() }
        ui.launch {
            try { picked = picked + withContext(Dispatchers.IO) { Attachments.fromFiles(paths, already) }; notice = null }
            catch (e: Attachments.Rejected) { notice = e.message }
        }
    }
    LaunchedEffect(initial) { addPaths(initial) }

    BrandDialog(
        onDismissRequest = onClose,
        title = tr("files_send_to"),
        confirmButton = {
            BrandButton(enabled = !sending && selected != null && picked.isNotEmpty(), onClick = {
                val to = selected ?: return@BrandButton
                sending = true
                ui.launch {
                    withContext(Dispatchers.Default) { session.store.send(text = null, attachments = picked, to = to) }
                    onClose()
                }
            }) { Text(if (sending) tr("files_sending") else tr("chat_send")) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(tr("common_cancel")) } }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small), modifier = Modifier.widthIn(min = 380.dp)) {
            if (contacts.isEmpty()) {
                HelpText(tr("files_pair_first"))
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                    items(contacts, key = { it.fingerprint.hex }) { c ->
                        val name = c.displayName ?: UiFormat.shortFingerprint(c.fingerprint)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.Small))
                                .then(if (selected == c) Modifier.clickable { selected = null } else Modifier.clickable { selected = c })
                                .padding(vertical = 6.dp, horizontal = 4.dp)
                        ) {
                            Avatar(name, 32.dp)
                            Spacer(Modifier.width(Spacing.Medium))
                            Text(name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (selected == c) FontWeight.SemiBold else FontWeight.Normal)
                            if (selected == c) Text(tr("common_selected"), style = MaterialTheme.typography.labelSmall, color = Brand.Accent)
                        }
                    }
                }
            }
            OutlinedButton(onClick = {
                val dialog = FileDialog(null as Frame?, tr("files_pick"), FileDialog.LOAD)
                dialog.isMultipleMode = true
                dialog.isVisible = true
                addPaths(dialog.files.orEmpty().map { it.toPath() })
            }) { Text(tr("files_pick")) }
            for (a in picked) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${a.filename} (${UiFormat.sizeLabel(a.data.size.toLong())})", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    TextButton(onClick = { picked = picked - a }) { Text(tr("common_remove")) }
                }
            }
            notice?.let { StatusStrip(it, error = true) }
        }
    }
}
