// Gui.kt
// CarrierPony Desktop. The window: tray, theme, the gated screens (first run, unlock, identity)
// and the main frame with its navigation rail. Every decision (passphrase rules, unlock, identity,
// pairing, sending) lives in plain Kotlin classes that are unit-tested without a window.
//
// D11 rebuilt the window on the phone app's look: a rail with Messages, Files and Settings,
// the CarrierPony palette from Theme.kt, the widgets from Brand.kt, and every string through
// tr(). The screens themselves live in MessagesScreen.kt, FilesScreen.kt and SettingsScreen.kt;
// the modals in Dialogs.kt.

package com.carrierpony.desktop

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.awt.AWTEvent
import java.awt.BasicStroke
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetDropEvent
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO

/** Settings for the parts of the tree that need one (the Save button's downloads folder). */
val LocalSettings = staticCompositionLocalOf<Settings?> { null }

/** The rail's destinations. Labels are resource keys: an enum entry is a compile-time constant. */
enum class Destination(val labelKey: String, val icon: ImageVector) {
    Messages("common_messages", Icons.Filled.Forum),
    Files("common_files", Icons.Filled.Folder),
    Settings("common_settings", Icons.Filled.Settings)
}

fun cmdGui(startHidden: Boolean = false, instance: SingleInstance? = null) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // The tray does not exist until the composition does; the controller is built first. This
    // holder bridges the two: the controller notifies through it, the tray fills it in.
    val trayNotifier = AtomicReference<((String, String) -> Unit)?>(null)
    val controller = AppController(Config.dataDir, appScope, notifier = { title, body -> trayNotifier.get()?.invoke(title, body) })
    // Language, theme and the update-check switch are read before the first frame, so the window
    // never renders one English frame and then swaps.
    I18n.attach(controller.prefs)
    ThemeState.attach(controller.prefs)
    UpdateCheck.attach(controller.prefs)
    appScope.launch { controller.runIdleLock() }
    appScope.launch { UpdateCheck.checkIfDue() }
    // Any key or mouse event anywhere in the app resets the idle timer. AWT sees them all,
    // including the ones Compose consumes, which keeps this out of every composable.
    Toolkit.getDefaultToolkit().addAWTEventListener(
        { controller.noteActivity() },
        AWTEvent.KEY_EVENT_MASK or AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK or AWTEvent.MOUSE_WHEEL_EVENT_MASK
    )

    // The app icon on the Dock, the taskbar and the window frame. The packaged app carries its
    // own icns/ico (D12); this covers a development run, where the JVM would otherwise show
    // the Java cup. java.awt.Taskbar is the only way onto the macOS Dock at runtime.
    val appImage = runCatching { PonyApps::class.java.getResourceAsStream("/icons/carrierpony_512.png")?.use { ImageIO.read(it) } }.getOrNull()
    if (appImage != null) {
        runCatching {
            val taskbar = java.awt.Taskbar.getTaskbar()
            if (taskbar.isSupported(java.awt.Taskbar.Feature.ICON_IMAGE)) taskbar.iconImage = appImage
        }
    }

    application {
        var visible by remember { mutableStateOf(!startHidden || !isTraySupported) }
        val windowIcon = remember { appImage?.toPainter() }
        val unread by controller.unread.collectAsState()
        val quit = { controller.lock(); exitApplication() }

        if (isTraySupported) {
            val trayState = rememberTrayState()
            LaunchedEffect(trayState) {
                trayNotifier.set { title, body -> trayState.sendNotification(Notification(title, body, Notification.Type.Info)) }
            }
            val icon = remember(unread) { trayIcon(unread).toPainter() }
            Tray(
                icon = icon,
                state = trayState,
                tooltip = if (unread > 0) tr("d_tray_tooltip_unread", unread) else "CarrierPony",
                onAction = { visible = true },
                menu = {
                    Item(tr("d_tray_open"), onClick = { visible = true })
                    Item(tr("d_tray_lock"), onClick = { controller.lock(); visible = true })
                    Separator()
                    Item(tr("d_tray_quit"), onClick = quit)
                }
            )
        }

        Window(
            visible = visible,
            onCloseRequest = { if (controller.settings.closeToTray && isTraySupported) visible = false else quit() },
            title = if (unread > 0) "CarrierPony ($unread)" else "CarrierPony",
            icon = windowIcon,
            state = rememberWindowState(width = 1080.dp, height = 720.dp)
        ) {
            // Files dropped anywhere on the window. Plain AWT on the content pane rather than a
            // Compose drag-and-drop modifier: that API has changed shape across Compose releases
            // and the AWT one has not moved in twenty years.
            val drops = remember { MutableStateFlow<List<Path>>(emptyList()) }
            DisposableEffect(Unit) {
                window.contentPane.dropTarget = fileDropTarget { drops.value = it }
                onDispose { window.contentPane.dropTarget = null }
            }
            // A second launch (Dock click while in the tray, a login item after a manual start)
            // lands here through SingleInstance: show the window and bring it forward.
            DisposableEffect(instance) {
                instance?.focusWindow = {
                    java.awt.EventQueue.invokeLater {
                        visible = true
                        if ((window.extendedState and java.awt.Frame.ICONIFIED) != 0) window.extendedState = java.awt.Frame.NORMAL
                        window.toFront()
                        window.requestFocus()
                    }
                }
                onDispose { instance?.focusWindow = null }
            }
            CarrierPonyTheme {
                CompositionLocalProvider(LocalSettings provides controller.settings) {
                    Surface(
                        color = MaterialTheme.colorScheme.background,
                        modifier = Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.L && (event.isMetaPressed || event.isCtrlPressed)) { controller.lock(); true } else false
                        }
                    ) { Root(controller, drops) }
                }
            }
        }
    }
}

/**
 * The tray icon: the app mark from resources with the unread count on a coral badge. If the
 * resource is missing, a coral disc with a white chevron, so the tray never shows a blank.
 */
private fun trayIcon(unread: Int): BufferedImage {
    val size = 32
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    val mark = runCatching { PonyApps::class.java.getResourceAsStream("/icons/carrierpony_tray.png")?.use { ImageIO.read(it) } }.getOrNull()
    if (mark != null) {
        g.drawImage(mark, 0, 0, size, size, null)
    } else {
        g.color = java.awt.Color(0xEC, 0x67, 0x55)
        g.fillOval(3, 3, size - 6, size - 6)
        g.color = java.awt.Color.WHITE
        g.stroke = BasicStroke(3f)
        g.drawLine(10, 20, 16, 12); g.drawLine(16, 12, 22, 20)
    }
    if (unread > 0) {
        val label = if (unread > 99) "99+" else unread.toString()
        g.color = java.awt.Color(0xF1, 0x50, 0x56)
        g.fillOval(size - 18, 0, 18, 18)
        g.color = java.awt.Color.WHITE
        g.font = g.font.deriveFont(java.awt.Font.BOLD, if (label.length > 2) 8f else 11f)
        val fm = g.fontMetrics
        g.drawString(label, size - 9 - fm.stringWidth(label) / 2, 9 + fm.ascent / 2 - 1)
    }
    g.dispose()
    return image
}

private fun fileDropTarget(onFiles: (List<Path>) -> Unit): DropTarget = object : DropTarget() {
    @Synchronized
    override fun drop(event: DropTargetDropEvent) {
        try {
            event.acceptDrop(DnDConstants.ACTION_COPY)
            val list = event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
            onFiles(list.orEmpty().filterIsInstance<File>().map { it.toPath() })
            event.dropComplete(true)
        } catch (e: Exception) {
            event.dropComplete(false)
        }
    }
}

@Composable
private fun Root(controller: AppController, drops: MutableStateFlow<List<Path>>) {
    val stage by controller.stage.collectAsState()
    val error by controller.error.collectAsState()
    when (val s = stage) {
        is AppController.Stage.NeedsVault -> FirstRunScreen(controller, error)
        is AppController.Stage.Locked -> UnlockScreen(controller, error)
        is AppController.Stage.NeedsIdentity -> IdentityScreen(controller, error)
        is AppController.Stage.Ready -> MainWindow(controller, s.session, s.accounts, drops)
    }
}

// Shared pieces for the gated screens

/** A centred column with the mark on top, for the three screens shown before the session exists. */
@Composable
private fun Gate(title: String, content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 440.dp).verticalScroll(rememberScrollState()).padding(Spacing.Screen),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.Medium)
        ) {
            BrandMark(size = 84.dp)
            Spacer(Modifier.height(Spacing.Tight))
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            content()
        }
    }
}

@Composable
fun ErrorText(message: String?) {
    if (message != null) Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

@Composable
fun SecretField(value: String, onChange: (String) -> Unit, label: String, onEnter: () -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) { onEnter(); true } else false
        }
    )
}

@Composable
private fun FirstRunScreen(controller: AppController, error: String?) {
    val ui = rememberCoroutineScope()
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val submit = { if (!busy) { busy = true; ui.launch { controller.createVault(first, second); busy = false } } }
    Gate(tr("d_firstrun_title")) {
        HelpText(tr("d_firstrun_body"))
        SecretField(first, { first = it }, tr("d_launch_passphrase"), onEnter = { submit() })
        SecretField(second, { second = it }, tr("d_launch_passphrase_repeat"), onEnter = { submit() })
        ErrorText(error)
        BrandButton(onClick = { submit() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) tr("d_working") else tr("common_continue"))
        }
    }
}

@Composable
private fun UnlockScreen(controller: AppController, error: String?) {
    val ui = rememberCoroutineScope()
    var passphrase by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val submit = { if (!busy) { busy = true; ui.launch { controller.unlock(passphrase); passphrase = ""; busy = false } } }
    Gate(tr("lock_title")) {
        SecretField(passphrase, { passphrase = it }, tr("d_launch_passphrase"), onEnter = { submit() })
        ErrorText(error)
        BrandButton(onClick = { submit() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) tr("d_unlocking") else tr("lock_unlock"))
        }
    }
}

@Composable
private fun IdentityScreen(controller: AppController, error: String?) {
    val ui = rememberCoroutineScope()
    var restoring by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var blob by remember { mutableStateOf("") }
    var backupPassphrase by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Gate(tr("onb_setup_title")) {
        if (!restoring) {
            HelpText(tr("d_identity_body"))
            OutlinedTextField(name, { name = it }, label = { Text(tr("onb_name_optional")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            ErrorText(error)
            BrandButton(onClick = { busy = true; ui.launch { controller.createIdentity(name); busy = false } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) tr("d_working") else tr("onb_create"))
            }
            TextButton(onClick = { controller.clearError(); restoring = true }) { Text(tr("onb_restore")) }
        } else {
            HelpText(tr("restore_body"))
            HelpText(tr("d_restore_one_device"))
            OutlinedTextField(blob, { blob = it }, label = { Text(tr("d_backup_label")) }, modifier = Modifier.fillMaxWidth().height(160.dp))
            SecretField(backupPassphrase, { backupPassphrase = it }, tr("backup_passphrase"), onEnter = {})
            ErrorText(error)
            BrandButton(onClick = { busy = true; ui.launch { controller.restoreBackup(blob, backupPassphrase); busy = false } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) tr("d_working") else tr("restore_button"))
            }
            TextButton(onClick = { controller.clearError(); restoring = false }) { Text(tr("d_identity_create_instead")) }
        }
    }
}

// The main frame

/** Per-session UI state that survives switching rails: which conversation is open. */
class MainUiState {
    var selection by mutableStateOf<Selection?>(null)
    var destination by mutableStateOf(Destination.Messages)
}

/** What the conversation pane shows. */
sealed interface Selection {
    data class Peer(val fingerprint: com.carrierpony.app.crypto.Fingerprint) : Selection
    data class Group(val groupID: String) : Selection
}

private val RAIL_WIDTH = 92.dp

@Composable
private fun MainWindow(controller: AppController, session: DesktopSession, accounts: DesktopAccounts, drops: MutableStateFlow<List<Path>>) {
    val ui = remember(session) { MainUiState() }
    val unread by controller.unread.collectAsState()

    Row(modifier = Modifier.fillMaxSize()) {
        // The rail carries the brand: the mark on top, a faint gradient wash behind, the three
        // destinations. Pinned width, because NavigationRail sizes itself to its widest child and
        // a Row hands non-weighted children the whole width first.
        Box(modifier = Modifier.fillMaxHeight().width(RAIL_WIDTH).background(Brand.gradientWash(0.08f))) {
            NavigationRail(
                modifier = Modifier.fillMaxHeight(),
                containerColor = Color.Transparent,
                header = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = Spacing.Large, bottom = Spacing.Small)) {
                        BrandMark(size = 44.dp)
                    }
                }
            ) {
                Destination.entries.forEach { dest ->
                    NavigationRailItem(
                        modifier = Modifier.width(RAIL_WIDTH),
                        selected = ui.destination == dest,
                        onClick = { ui.destination = dest },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = Color.White,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            indicatorColor = Brand.Accent,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        icon = {
                            if (dest == Destination.Messages && unread > 0) {
                                BadgedBox(badge = { Badge(containerColor = Brand.Accent, contentColor = Color.White) { Text(if (unread > 99) "99+" else "$unread") } }) {
                                    Icon(dest.icon, contentDescription = tr(dest.labelKey))
                                }
                            } else {
                                Icon(dest.icon, contentDescription = tr(dest.labelKey))
                            }
                        },
                        label = { Text(tr(dest.labelKey), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
                    )
                }
            }
        }
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            when (ui.destination) {
                Destination.Messages -> MessagesScreen(controller, session, accounts, ui, drops)
                Destination.Files -> FilesScreen(session, drops)
                Destination.Settings -> SettingsScreen(controller, session, accounts)
            }
        }
    }
}
