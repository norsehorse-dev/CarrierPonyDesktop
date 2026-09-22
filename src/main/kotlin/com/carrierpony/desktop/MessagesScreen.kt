// MessagesScreen.kt
// CarrierPony Desktop. D11: the Messages destination. A list pane on the left (the phone's inbox:
// account switcher, compose button, the big title, gradient avatars, verified ticks, coral unread
// pills) and the open conversation on the right (avatar, name, lock and fingerprint tail in the
// header; coral outgoing and grey incoming bubbles with time and tick under each; day separators;
// a pill composer with the gradient send button). Groups and channels use the same pane with the
// sender's name on incoming bubbles.
//
// Everything here draws and calls; the store logic is vendored ChatStore, the attachment rules are
// Attachments.kt, and the pairing flow is DesktopPairing.

package com.carrierpony.desktop

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.app.envelope.Threading
import com.carrierpony.app.messaging.ChatGroup
import com.carrierpony.app.messaging.ChatMessage
import com.carrierpony.app.messaging.Contact
import com.carrierpony.app.messaging.Conversation
import com.carrierpony.app.messaging.MessageDirection
import com.carrierpony.app.messaging.OutgoingMessage
import com.carrierpony.app.messaging.TrustLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.nio.file.Path

private val LIST_WIDTH = 340.dp

/** One row of the inbox, contacts and groups together, newest activity first. */
private data class InboxEntry(
    val selection: Selection,
    val title: String,
    val preview: String,
    val unread: Int,
    val lastAt: Long,
    val verified: Boolean,
    val timeLabel: String
)

@Composable
fun MessagesScreen(controller: AppController, session: DesktopSession, accounts: DesktopAccounts, ui: MainUiState, drops: MutableStateFlow<List<Path>>) {
    val me = session.identity.fingerprint
    val conversations by session.store.conversations.collectAsState()
    val contacts by session.contacts.contactsFlow.collectAsState()
    val groups by session.store.groups.collectAsState()
    val groupMessages by session.store.groupMessages.collectAsState()
    val lastError by session.store.lastError.collectAsState()
    var addingContact by remember { mutableStateOf(false) }
    var newMessage by remember { mutableStateOf(false) }
    var creatingGroup by remember { mutableStateOf<Boolean?>(null) }     // null: no dialog; false: group; true: channel
    var joiningChannel by remember { mutableStateOf(false) }
    var addingAccount by remember { mutableStateOf(false) }

    fun conversationWith(contact: Contact): Conversation? = conversations[Threading.pairwise(me.hex, contact.fingerprint.hex)]

    fun previewOf(last: ChatMessage?, fallback: String): String = when {
        last == null -> fallback
        !last.text.isNullOrEmpty() -> (if (last.direction == MessageDirection.OUTGOING) tr("inbox_you_prefix") else "") + last.text
        last.attachments.isNotEmpty() -> "📎 " + last.attachments.first().filename
        else -> tr("inbox_attachment_fallback")
    }

    val rows = buildList {
        for (c in contacts) {
            val conv = conversationWith(c)
            val last = conv?.lastMessage
            add(InboxEntry(
                Selection.Peer(c.fingerprint),
                c.displayName ?: UiFormat.shortFingerprint(c.fingerprint),
                previewOf(last, tr("inbox_no_messages")),
                conv?.unreadCount ?: 0,
                last?.sentAt ?: 0L,
                c.trust == TrustLevel.VERIFIED,
                last?.let { listTimeLabelText(it.sentAt) } ?: ""
            ))
        }
        for (g in groups.values) {
            val msgs = groupMessages[g.groupID].orEmpty()
            val last = msgs.maxByOrNull { it.sentAt }
            val members = if (g.members.size == 1) tr("group_member_one") else tr("group_member_many", g.members.size)
            add(InboxEntry(
                Selection.Group(g.groupID),
                g.name,
                previewOf(last, members),
                msgs.count { it.direction == MessageDirection.INCOMING && !it.isRead },
                last?.sentAt ?: g.createdAt,
                false,
                last?.let { listTimeLabelText(it.sentAt) } ?: ""
            ))
        }
    }.sortedByDescending { it.lastAt }

    Row(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.width(LIST_WIDTH).fillMaxHeight()) {
            // Top bar: who you are (and a switcher), and the compose button.
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Large, vertical = Spacing.Medium), verticalAlignment = Alignment.CenterVertically) {
                AccountSwitcher(controller, accounts, me.hex, onAdd = { addingAccount = true })
                Spacer(Modifier.weight(1f))
                ComposeMenu(
                    hasContacts = contacts.isNotEmpty(),
                    onNewMessage = { newMessage = true },
                    onAddContact = { addingContact = true },
                    onNewGroup = { creatingGroup = false },
                    onNewChannel = { creatingGroup = true },
                    onJoinChannel = { joiningChannel = true }
                )
            }
            TabTitle("CarrierPony", modifier = Modifier.padding(horizontal = Spacing.Large, vertical = Spacing.Small))
            if (lastError != null) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Medium, vertical = Spacing.Tight), verticalAlignment = Alignment.CenterVertically) {
                    StatusStrip(lastError ?: "", error = true, modifier = Modifier.weight(1f))
                    TextButton(onClick = { session.store.clearError() }) { Text(tr("common_dismiss")) }
                }
            }
            if (rows.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.Forum,
                    title = tr("inbox_no_contacts"),
                    message = tr("inbox_pair_to_start"),
                    action = { BrandButton(onClick = { addingContact = true }) { Text(tr("settings_pair_with")) } }
                )
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(rows, key = { it.selection.toString() }) { row ->
                    InboxRow(row, isSelected = row.selection == ui.selection) { ui.selection = row.selection }
                    HairlineRule(Modifier.padding(start = 74.dp))
                }
            }
        }
        Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
        // Right pane: the open conversation.
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            when (val sel = ui.selection) {
                is Selection.Peer -> {
                    val current = contacts.firstOrNull { it.fingerprint == sel.fingerprint }
                    if (current == null) EmptyPane() else ConversationPane(session, current, conversationWith(current), drops, onClosed = { ui.selection = null })
                }
                is Selection.Group -> {
                    val group = groups[sel.groupID]
                    if (group == null) EmptyPane() else GroupPane(session, group, groupMessages[group.groupID].orEmpty(), drops, onLeft = { ui.selection = null })
                }
                null -> EmptyPane()
            }
        }
    }

    if (addingContact) AddContactDialog(session, onClose = { addingContact = false }, onPaired = { ui.selection = Selection.Peer(it.fingerprint) })
    if (newMessage) NewMessageDialog(contacts, onClose = { newMessage = false }, onPick = { ui.selection = Selection.Peer(it.fingerprint); newMessage = false })
    creatingGroup?.let { isChannel -> NewGroupDialog(session, isChannel, contacts, onClose = { creatingGroup = null }) }
    if (joiningChannel) JoinChannelDialog(session, onClose = { joiningChannel = false })
    if (addingAccount) AddAccountDialog(controller, onClose = { addingAccount = false })
}

/** The account name with a chevron; a click lists the other accounts and an add row. */
@Composable
private fun AccountSwitcher(controller: AppController, accounts: DesktopAccounts, meHex: String, onAdd: () -> Unit) {
    val ui = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    val list = controller.accountList()
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clip(RoundedCornerShape(Radius.Small)).clickable { open = true }.padding(horizontal = Spacing.Small, vertical = Spacing.Tight)
        ) {
            Text(
                accounts.profileName(meHex) ?: tr("d_account_unnamed"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp)
            )
            Spacer(Modifier.width(Spacing.Tight))
            Icon(Icons.Filled.ExpandMore, contentDescription = tr("accounts_switch_cd"), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (a in list) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text((a.name ?: tr("d_account_unnamed")) + if (a.fingerprint.hex == meHex) "  (" + tr("accounts_active") + ")" else "")
                            FingerprintTail(UiFormat.shortFingerprint(a.fingerprint))
                        }
                    },
                    leadingIcon = { Avatar(a.name, 28.dp) },
                    enabled = a.fingerprint.hex != meHex,
                    onClick = { open = false; ui.launch { controller.switchAccount(a.fingerprint.hex) } }
                )
            }
            DropdownMenuItem(text = { Text(tr("accounts_add")) }, leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) }, onClick = { open = false; onAdd() })
        }
    }
}

/** The compose pencil and its menu. */
@Composable
private fun ComposeMenu(hasContacts: Boolean, onNewMessage: () -> Unit, onAddContact: () -> Unit, onNewGroup: () -> Unit, onNewChannel: () -> Unit, onJoinChannel: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        RoundIconButton(Icons.Filled.Edit, contentDescription = tr("inbox_new_message_cd"), onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(tr("inbox_new_message")) }, enabled = hasContacts, onClick = { open = false; onNewMessage() })
            DropdownMenuItem(text = { Text(tr("settings_pair_with")) }, onClick = { open = false; onAddContact() })
            DropdownMenuItem(text = { Text(tr("group_new")) }, enabled = hasContacts, onClick = { open = false; onNewGroup() })
            DropdownMenuItem(text = { Text(tr("channel_new")) }, onClick = { open = false; onNewChannel() })
            DropdownMenuItem(text = { Text(tr("channel_subscribe")) }, onClick = { open = false; onJoinChannel() })
        }
    }
}

@Composable
private fun EmptyPane() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(icon = Icons.Filled.Forum, title = tr("d_messages_pick_title"), message = tr("d_messages_pick_body"))
    }
}

@Composable
private fun InboxRow(row: InboxEntry, isSelected: Boolean, onClick: () -> Unit) {
    val background = if (isSelected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent
    Row(
        modifier = Modifier.fillMaxWidth().background(background).clickable(onClick = onClick).padding(horizontal = Spacing.Large, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(name = row.title, size = 46.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (row.verified) { Spacer(Modifier.width(4.dp)); VerifiedMark() }
            }
            Text(row.preview, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(row.timeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            if (row.unread > 0) UnreadBadge(row.unread) else Spacer(Modifier.height(18.dp))
        }
    }
}

// The open conversation

/** The thread as drawn: day markers between messages from different days. */
private sealed interface TimelineItem {
    val key: String
    data class Day(val start: Long) : TimelineItem { override val key: String get() = "day-$start" }
    data class Msg(val message: ChatMessage) : TimelineItem { override val key: String get() = message.id }
}

private fun timeline(messages: List<ChatMessage>): List<TimelineItem> {
    val out = ArrayList<TimelineItem>(messages.size + 8)
    var day = -1L
    for (m in messages) {
        val start = UiFormat.startOfDay(m.sentAt)
        if (start != day) { out.add(TimelineItem.Day(start)); day = start }
        out.add(TimelineItem.Msg(m))
    }
    return out
}

@Composable
private fun ConversationPane(session: DesktopSession, contact: Contact, conversation: Conversation?, drops: MutableStateFlow<List<Path>>, onClosed: () -> Unit) {
    val ui = rememberCoroutineScope()
    val messages = conversation?.sortedMessages ?: emptyList()
    val line = remember(messages) { timeline(messages) }
    val listState = rememberLazyListState()
    var draft by remember(contact.fingerprint) { mutableStateOf("") }
    var showSafetyNumber by remember(contact.fingerprint) { mutableStateOf(false) }
    var showNickname by remember(contact.fingerprint) { mutableStateOf(false) }
    var confirm by remember(contact.fingerprint) { mutableStateOf<String?>(null) }   // "clear", "delete", "unpair"
    var menu by remember(contact.fingerprint) { mutableStateOf(false) }
    val reachable by session.lanDiscovery.reachable.collectAsState()
    val onLan = contact.fingerprint.hex.lowercase() in reachable
    val name = contact.displayName ?: UiFormat.shortFingerprint(contact.fingerprint)

    LaunchedEffect(contact.fingerprint, messages.size) {
        if (line.isNotEmpty()) listState.scrollToItem(line.size - 1)
        conversation?.let { c -> withContext(Dispatchers.Default) { session.store.markRead(c.threadID) } }
    }

    val composer = rememberComposer(contact.fingerprint.hex, drops, canPost = true) { text, files ->
        ui.launch { withContext(Dispatchers.Default) { session.store.send(text = text, attachments = files, to = contact) } }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Large, vertical = Spacing.Medium), verticalAlignment = Alignment.CenterVertically) {
            Avatar(name, 38.dp)
            Spacer(Modifier.width(Spacing.Medium))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (contact.trust == TrustLevel.VERIFIED) { Spacer(Modifier.width(4.dp)); VerifiedMark(16.dp) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = Color(0xFF34C759), modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    FingerprintTail(UiFormat.shortFingerprint(contact.fingerprint))
                    if (onLan) {
                        Spacer(Modifier.width(Spacing.Small))
                        Icon(Icons.Filled.Wifi, contentDescription = tr("settings_lan_reachable"), tint = Brand.Accent, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(2.dp))
                        Text(tr("d_chat_on_lan"), style = MaterialTheme.typography.labelSmall, color = Brand.Accent)
                    }
                }
            }
            Box {
                RoundIconButton(Icons.Filled.MoreHoriz, contentDescription = tr("common_more"), onClick = { menu = true }, tint = MaterialTheme.colorScheme.onSurface)
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(tr("safety_title")) }, onClick = { menu = false; showSafetyNumber = true })
                    DropdownMenuItem(text = { Text(if (contact.nickname == null) tr("chat_set_nickname") else tr("chat_edit_nickname")) }, onClick = { menu = false; showNickname = true })
                    DropdownMenuItem(text = { Text(tr("chat_clear_history")) }, onClick = { menu = false; confirm = "clear" })
                    DropdownMenuItem(text = { Text(tr("inbox_delete_conversation")) }, onClick = { menu = false; confirm = "delete" })
                    DropdownMenuItem(text = { Text(tr("inbox_unpair"), color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; confirm = "unpair" })
                }
            }
        }
        HairlineRule()
        if (messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                EmptyState(icon = Icons.Filled.Lock, title = tr("chat_empty_title"), message = tr("chat_empty_body"))
            }
        } else {
            SelectionContainer(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.Large)) {
                    items(line, key = { it.key }) { item ->
                        when (item) {
                            is TimelineItem.Day -> DaySeparator(dayLabelText(item.start))
                            is TimelineItem.Msg -> MessageRow(
                                item.message,
                                senderName = null,
                                onDeleteForMe = { ui.launch { withContext(Dispatchers.Default) { session.store.deleteLocally(item.message.id) } } },
                                onDeleteForEveryone = if (item.message.direction == MessageDirection.OUTGOING) {
                                    { ui.launch { withContext(Dispatchers.Default) { session.store.deleteForEveryone(item.message.id, contact) } } }
                                } else null
                            )
                        }
                    }
                }
            }
        }
        Composer(composer, placeholder = tr("chat_placeholder"))
    }

    if (showSafetyNumber) SafetyNumberDialog(session, contact, onClose = { showSafetyNumber = false })
    if (showNickname) NicknameDialog(session, contact, onClose = { showNickname = false })
    when (confirm) {
        "clear" -> ConfirmDialog(
            title = tr("chat_clear_history_title"), body = tr("chat_clear_history_body"), action = tr("common_clear"), destructive = true,
            onConfirm = { confirm = null; conversation?.let { c -> ui.launch { withContext(Dispatchers.Default) { session.store.clearHistory(c.threadID) } } } },
            onClose = { confirm = null }
        )
        "delete" -> ConfirmDialog(
            title = tr("inbox_delete_conversation"), body = tr("d_chat_delete_conversation_body"), action = tr("inbox_delete_conversation"), destructive = true,
            onConfirm = { confirm = null; conversation?.let { c -> ui.launch { withContext(Dispatchers.Default) { session.store.deleteConversation(c.threadID) } } } },
            onClose = { confirm = null }
        )
        "unpair" -> ConfirmDialog(
            title = tr("inbox_unpair_q"), body = tr("inbox_unpair_body"), action = tr("inbox_unpair"), destructive = true,
            onConfirm = {
                confirm = null
                ui.launch {
                    withContext(Dispatchers.Default) {
                        conversation?.let { session.store.deleteConversation(it.threadID) }
                        session.contacts.remove(contact.fingerprint)
                    }
                    onClosed()
                }
            },
            onClose = { confirm = null }
        )
    }
}

@Composable
private fun GroupPane(session: DesktopSession, group: ChatGroup, messages: List<ChatMessage>, drops: MutableStateFlow<List<Path>>, onLeft: () -> Unit) {
    val ui = rememberCoroutineScope()
    val me = session.identity.fingerprint
    val isAdmin = group.isAdmin(me)
    val canPost = !group.isChannel || isAdmin
    val sorted = messages.sortedBy { it.sentAt }
    val line = remember(sorted) { timeline(sorted) }
    val listState = rememberLazyListState()
    var showMembers by remember(group.groupID) { mutableStateOf(false) }
    var menu by remember(group.groupID) { mutableStateOf(false) }
    var confirmLeave by remember(group.groupID) { mutableStateOf(false) }
    var status by remember(group.groupID) { mutableStateOf<String?>(null) }

    fun nameOf(fpr: Fingerprint): String =
        session.contacts.contact(fpr)?.displayName ?: group.member(fpr)?.name ?: UiFormat.shortFingerprint(fpr)

    LaunchedEffect(group.groupID, sorted.size) {
        if (line.isNotEmpty()) listState.scrollToItem(line.size - 1)
        withContext(Dispatchers.Default) { session.store.markGroupRead(group.groupID) }
    }

    val composer = rememberComposer(group.groupID, drops, canPost = canPost) { text, files ->
        ui.launch { withContext(Dispatchers.Default) { session.store.sendGroupMessage(group.groupID, text ?: "", files) } }
    }

    val subtitle = if (group.isChannel) {
        if (isAdmin) tr("d_channel_yours", group.members.size - 1) else tr("channel_readonly")
    } else {
        (if (group.members.size == 1) tr("group_member_one") else tr("group_member_many", group.members.size)) + (if (isAdmin) " · " + tr("group_admin") else "")
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Large, vertical = Spacing.Medium), verticalAlignment = Alignment.CenterVertically) {
            Avatar(group.name, 38.dp)
            Spacer(Modifier.width(Spacing.Medium))
            Column(modifier = Modifier.weight(1f)) {
                Text(group.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box {
                RoundIconButton(Icons.Filled.MoreHoriz, contentDescription = tr("common_more"), onClick = { menu = true }, tint = MaterialTheme.colorScheme.onSurface)
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(if (group.isChannel) tr("channel_subscribers") else tr("group_details")) }, onClick = { menu = false; showMembers = true })
                    if (group.isChannel && isAdmin) {
                        DropdownMenuItem(text = { Text(tr("channel_share_invite")) }, onClick = {
                            menu = false
                            session.store.channelInvite(group.groupID)?.let { copyToClipboard(it); status = tr("common_copied") }
                        })
                    }
                    DropdownMenuItem(
                        text = { Text(if (group.isChannel && !isAdmin) tr("channel_unsubscribe") else tr("group_leave"), color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; confirmLeave = true }
                    )
                }
            }
        }
        HairlineRule()
        status?.let { StatusStrip(it, modifier = Modifier.padding(horizontal = Spacing.Large, vertical = Spacing.Tight)) }
        if (sorted.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                EmptyState(icon = Icons.Filled.Forum, title = tr("chat_empty_title"), message = tr("group_empty"))
            }
        } else {
            SelectionContainer(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.Large)) {
                    items(line, key = { it.key }) { item ->
                        when (item) {
                            is TimelineItem.Day -> DaySeparator(dayLabelText(item.start))
                            is TimelineItem.Msg -> MessageRow(
                                item.message,
                                senderName = if (item.message.direction == MessageDirection.INCOMING) nameOf(item.message.peer) else null,
                                onDeleteForMe = { ui.launch { withContext(Dispatchers.Default) { session.store.deleteLocally(item.message.id) } } },
                                onDeleteForEveryone = null
                            )
                        }
                    }
                }
            }
        }
        if (!canPost) {
            Text(tr("channel_readonly"), modifier = Modifier.padding(Spacing.Large), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Composer(composer, placeholder = if (group.isChannel) tr("d_channel_placeholder") else tr("chat_placeholder"))
        }
    }

    if (showMembers) MembersDialog(session, group, onClose = { showMembers = false }, onLeft = { showMembers = false; onLeft() })
    if (confirmLeave) {
        val leaving = !(group.isChannel && !isAdmin)
        ConfirmDialog(
            title = if (leaving) tr("group_leave_q") else tr("channel_unsubscribe_q"),
            body = if (leaving) tr("group_leave_body") else tr("channel_unsubscribe_body"),
            action = if (leaving) tr("group_leave") else tr("channel_unsubscribe"),
            destructive = true,
            onConfirm = {
                confirmLeave = false
                ui.launch {
                    withContext(Dispatchers.Default) { if (leaving) session.store.leaveGroup(group.groupID) else session.store.unsubscribeFromChannel(group.groupID) }
                    onLeft()
                }
            },
            onClose = { confirmLeave = false }
        )
    }
}

@Composable
private fun DaySeparator(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = Spacing.Small), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
        }
    }
}

@Composable
private fun MessageRow(message: ChatMessage, senderName: String?, onDeleteForMe: () -> Unit, onDeleteForEveryone: (() -> Unit)?) {
    val outgoing = message.direction == MessageDirection.OUTGOING
    val items = buildList {
        add(ContextMenuItem(tr("chat_delete_for_me"), onDeleteForMe))
        if (onDeleteForEveryone != null) add(ContextMenuItem(tr("chat_delete_for_everyone"), onDeleteForEveryone))
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        if (outgoing) Spacer(Modifier.weight(1f).widthIn(min = 48.dp))
        ContextMenuArea(items = { items }) {
            Column(horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start, modifier = Modifier.widthIn(max = 560.dp)) {
                if (senderName != null) {
                    Text(senderName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = Brand.Accent, modifier = Modifier.padding(start = 6.dp, bottom = 2.dp))
                }
                Bubble(message, outgoing)
                Metadata(message, outgoing)
            }
        }
        if (!outgoing) Spacer(Modifier.weight(1f).widthIn(min = 48.dp))
    }
}

@Composable
private fun Bubble(message: ChatMessage, outgoing: Boolean) {
    val imageOnly = message.text.isNullOrEmpty() && message.attachments.isNotEmpty() && message.attachments.all { it.isImage }
    Surface(shape = RoundedCornerShape(Radius.Bubble), color = if (outgoing) Brand.Accent else MaterialTheme.colorScheme.surfaceVariant) {
        Column(
            modifier = Modifier.padding(horizontal = if (imageOnly) 4.dp else 14.dp, vertical = if (imageOnly) 4.dp else 9.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (attachment in message.attachments) {
                if (attachment.isImage) AttachmentImage(attachment) else AttachmentChip(attachment, onAccent = outgoing)
            }
            message.text?.takeIf { it.isNotEmpty() }?.let {
                Text(it, color = if (outgoing) Color.White else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun Metadata(message: ChatMessage, outgoing: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
        UiFormat.disappearingLabel(message.expiresAt)?.let {
            Text("⏱ $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(messageTimeLabelText(message.sentAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (message.viaLan) {
            Icon(Icons.Filled.Wifi, contentDescription = tr("chat_via_lan"), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(13.dp))
        }
        if (outgoing) {
            Icon(
                imageVector = if (message.isRead) Icons.Filled.CheckCircle else Icons.Filled.Check,
                contentDescription = if (message.isRead) tr("chat_read") else tr("chat_sent"),
                tint = if (message.isRead) Brand.Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(13.dp)
            )
        }
    }
}

// Attachments inside bubbles

/** Save and Open for any attachment, as a context menu; the same two actions the Files tab offers. */
@Composable
internal fun attachmentMenu(attachment: ChatMessage.Attachment, onStatus: (String?) -> Unit): List<ContextMenuItem> {
    val settings = LocalSettings.current
    return listOf(
        ContextMenuItem(tr("d_files_save_downloads")) { onStatus(saveAttachment(attachment, settings)) },
        ContextMenuItem(tr("d_files_save_as")) { onStatus(saveAttachmentAs(attachment)) },
        ContextMenuItem(tr("d_files_open")) { onStatus(openAttachment(attachment)) }
    )
}

internal fun saveAttachment(attachment: ChatMessage.Attachment, settings: Settings?): String = try {
    val dir = settings?.downloadsDir ?: Attachments.defaultDownloadsDir()
    val saved = Attachments.saveTo(attachment, dir)
    if (saved == null) tr("d_files_gone") else tr("d_files_saved", dir.fileName.toString(), saved.fileName.toString())
} catch (e: Exception) {
    tr("d_files_save_failed", e.message ?: "")
}

internal fun saveAttachmentAs(attachment: ChatMessage.Attachment): String? {
    val dialog = FileDialog(null as Frame?, tr("d_files_save_as"), FileDialog.SAVE)
    dialog.file = attachment.filename
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val name = dialog.file ?: return null
    return try {
        val target = java.nio.file.Paths.get(dir, name)
        val saved = Attachments.saveTo(attachment, target.parent)
        if (saved == null) tr("d_files_gone") else {
            if (saved.fileName.toString() != name) java.nio.file.Files.move(saved, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            tr("d_files_saved", target.parent.fileName.toString(), name)
        }
    } catch (e: Exception) {
        tr("d_files_save_failed", e.message ?: "")
    }
}

internal fun openAttachment(attachment: ChatMessage.Attachment): String? = try {
    val copy = Attachments.temporaryCopyForOpening(attachment)
    if (copy == null) tr("d_files_gone") else { Desktop.getDesktop().open(copy.toFile()); null }
} catch (e: Exception) {
    tr("d_files_open_failed", e.message ?: "")
}

@Composable
private fun AttachmentImage(attachment: ChatMessage.Attachment) {
    var status by remember(attachment.id) { mutableStateOf<String?>(null) }
    val thumbnail by produceState<ImageBitmap?>(initialValue = null, attachment.localPath) {
        value = withContext(Dispatchers.IO) { runCatching { Attachments.thumbnail(attachment, 360)?.toComposeImageBitmap() }.getOrNull() }
    }
    val menu = attachmentMenu(attachment) { status = it }
    Column {
        ContextMenuArea(items = { menu }) {
            val bitmap = thumbnail
            if (bitmap != null) {
                Image(bitmap = bitmap, contentDescription = attachment.filename, modifier = Modifier.widthIn(max = 360.dp).clip(RoundedCornerShape(16.dp)).clickable { status = openAttachment(attachment) })
            } else {
                AttachmentChip(attachment, onAccent = false)
            }
        }
        status?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp)) }
    }
}

@Composable
private fun AttachmentChip(attachment: ChatMessage.Attachment, onAccent: Boolean) {
    var status by remember(attachment.id) { mutableStateOf<String?>(null) }
    val menu = attachmentMenu(attachment) { status = it }
    Column {
        ContextMenuArea(items = { menu }) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { status = openAttachment(attachment) }.padding(vertical = 4.dp)
            ) {
                Icon(Icons.Filled.InsertDriveFile, contentDescription = null, tint = if (onAccent) Color.White else Brand.Accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(attachment.filename, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = if (onAccent) Color.White else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(UiFormat.sizeLabel(attachment.size), style = MaterialTheme.typography.labelSmall, color = if (onAccent) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        status?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = if (onAccent) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

// The composer

/** Draft text, queued files and the send action for one thread. Remembered per thread key. */
class ComposerState(private val onSend: (String?, List<OutgoingMessage.Attachment>) -> Unit, val canPost: Boolean) {
    var draft by mutableStateOf("")
    var pending by mutableStateOf<List<OutgoingMessage.Attachment>>(emptyList())
    var notice by mutableStateOf<String?>(null)

    fun pendingBytes(): Long = pending.sumOf { it.data.size.toLong() }

    fun queue(more: List<OutgoingMessage.Attachment>) { pending = pending + more; notice = null }

    fun remove(attachment: OutgoingMessage.Attachment) { pending = pending - attachment }

    val canSend: Boolean get() = canPost && (draft.isNotBlank() || pending.isNotEmpty())

    fun send() {
        if (!canSend) return
        val text = draft.trim().ifEmpty { null }
        val files = pending
        draft = ""; pending = emptyList(); notice = null
        onSend(text, files)
    }
}

@Composable
private fun rememberComposer(key: Any, drops: MutableStateFlow<List<Path>>, canPost: Boolean, onSend: (String?, List<OutgoingMessage.Attachment>) -> Unit): ComposerState {
    val ui = rememberCoroutineScope()
    val state = remember(key) { ComposerState(onSend, canPost) }

    fun addFiles(paths: List<Path>) {
        if (paths.isEmpty() || !canPost) return
        val already = state.pendingBytes()
        ui.launch {
            try { state.queue(withContext(Dispatchers.IO) { Attachments.fromFiles(paths, already) }) }
            catch (e: Attachments.Rejected) { state.notice = e.message }
        }
    }

    // Files dropped on the window go to whichever thread is open.
    val dropped by drops.collectAsState()
    LaunchedEffect(dropped) {
        if (dropped.isNotEmpty()) { addFiles(dropped); drops.value = emptyList() }
    }
    return state
}

/** True when the clipboard held files or an image and they were queued; false lets the field paste text. */
private fun pasteFromClipboard(state: ComposerState): Boolean {
    val clipboard = runCatching { Toolkit.getDefaultToolkit().systemClipboard }.getOrNull() ?: return false
    return try {
        when {
            clipboard.isDataFlavorAvailable(DataFlavor.javaFileListFlavor) -> {
                val files = clipboard.getData(DataFlavor.javaFileListFlavor) as? List<*>
                val paths = files.orEmpty().filterIsInstance<File>().map { it.toPath() }
                state.queue(Attachments.fromFiles(paths, state.pendingBytes())); true
            }
            clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor) -> {
                val image = clipboard.getData(DataFlavor.imageFlavor) as? java.awt.Image ?: return false
                state.queue(listOf(Attachments.fromImage(image, state.pendingBytes()))); true
            }
            else -> false
        }
    } catch (e: Attachments.Rejected) {
        state.notice = e.message; true
    } catch (e: Exception) {
        false
    }
}

private fun pickFiles(): List<Path> {
    val dialog = FileDialog(null as Frame?, tr("files_pick"), FileDialog.LOAD)
    dialog.isMultipleMode = true
    dialog.isVisible = true                     // modal: returns when the native picker closes
    return dialog.files.orEmpty().map { it.toPath() }
}

@Composable
private fun Composer(state: ComposerState, placeholder: String) {
    val ui = rememberCoroutineScope()
    Column(modifier = Modifier.fillMaxWidth()) {
        if (state.pending.isNotEmpty() || state.notice != null) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Large), horizontalArrangement = Arrangement.spacedBy(Spacing.Small), verticalAlignment = Alignment.CenterVertically) {
                for (attachment in state.pending) PendingChip(attachment) { state.remove(attachment) }
            }
            state.notice?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = Spacing.Large)) }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Medium, vertical = Spacing.Medium), verticalAlignment = Alignment.Bottom) {
            IconButton(onClick = {
                val paths = pickFiles()
                if (paths.isNotEmpty()) ui.launch {
                    try { state.queue(withContext(Dispatchers.IO) { Attachments.fromFiles(paths, state.pendingBytes()) }) }
                    catch (e: Attachments.Rejected) { state.notice = e.message }
                }
            }) {
                Icon(Icons.Filled.Add, contentDescription = tr("chat_attach"), tint = Brand.Accent, modifier = Modifier.size(26.dp))
            }
            TextField(
                value = state.draft,
                onValueChange = { state.draft = it },
                placeholder = { Text(placeholder) },
                maxLines = 6,
                shape = RoundedCornerShape(22.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    cursorColor = Brand.Accent
                ),
                modifier = Modifier.weight(1f).onPreviewKeyEvent { event ->
                    when {
                        event.type != KeyEventType.KeyDown -> false
                        event.key == Key.Enter && !event.isShiftPressed -> { state.send(); true }
                        event.key == Key.V && (event.isMetaPressed || event.isCtrlPressed) -> pasteFromClipboard(state)
                        else -> false
                    }
                }
            )
            Spacer(Modifier.width(Spacing.Small))
            SendButton(enabled = state.canSend, onClick = { state.send() })
        }
    }
}

/** The round gradient send button with the upward arrow. */
@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .then(if (enabled) Modifier.background(Brand.sendGradient()) else Modifier.background(MaterialTheme.colorScheme.surfaceVariant))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Filled.ArrowUpward, contentDescription = tr("chat_send"), tint = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun PendingChip(attachment: OutgoingMessage.Attachment, onRemove: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp, top = 4.dp, bottom = 4.dp)) {
            Column {
                Text(attachment.filename, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
                Text(UiFormat.sizeLabel(attachment.data.size.toLong()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.Close, contentDescription = tr("common_remove"), modifier = Modifier.size(14.dp))
            }
        }
    }
}
