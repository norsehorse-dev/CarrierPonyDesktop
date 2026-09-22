// Brand.kt
// CarrierPony Desktop. D11: the brand layer. The gradient, the spacing and radius scales, and the
// widgets every screen is built from: avatars, badges, the round toolbar button, section cards,
// the empty state, the status strip and the dialog chrome.
//
// The look is the iPhone app's: a black canvas, warm gradient avatars, coral for the one thing on
// screen that matters (the unread count, the verified tick, the send button, your own bubbles),
// grey for everything that is context. The gradient is reserved for brand moments (avatar, mark,
// send button, empty state); flat coral carries the message bubbles so a long thread stays calm.
//
// Only stable Compose APIs. No file in this project carries @OptIn.

package com.carrierpony.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Brand {

    /** The one flat brand colour: sent bubbles, badges, the verified tick, primary buttons. */
    val Accent: Color = CPAccent

    /** The icon background gradient (gold to coral to red), top-left to bottom-right. */
    fun gradient(): Brush =
        Brush.linearGradient(listOf(CPGold, CPCoral, CPRed), Offset.Zero, Offset.Infinite)

    /** Straight down, for tall narrow surfaces where the diagonal reads as a stripe. */
    fun gradientVertical(): Brush = Brush.verticalGradient(listOf(CPGold, CPCoral, CPRed))

    /** The tighter coral-to-red of the horse itself: the send button. */
    fun sendGradient(): Brush =
        Brush.linearGradient(listOf(CPAccentLite, CPAccentDeep), Offset.Zero, Offset.Infinite)

    /** The gradient at low opacity over the current surface, for washes behind the rail and empty states. */
    fun gradientWash(alpha: Float = 0.12f): Brush = Brush.linearGradient(
        listOf(CPGold.copy(alpha = alpha), CPCoral.copy(alpha = alpha), CPRed.copy(alpha = alpha)),
        Offset.Zero,
        Offset.Infinite
    )
}

/** The spacing scale, on a 4dp grid. */
object Spacing {
    val Tight: Dp = 4.dp
    val Small: Dp = 8.dp
    val Medium: Dp = 12.dp
    val Large: Dp = 16.dp
    val Section: Dp = 24.dp
    val Screen: Dp = 32.dp
}

/** Corner radii. Small for pills and chips, Medium for cards, Large for the mark, Bubble for messages. */
object Radius {
    val Small: Dp = 6.dp
    val Medium: Dp = 12.dp
    val Large: Dp = 18.dp
    val Bubble: Dp = 20.dp
}

// Avatars and marks

/**
 * The gradient circle with the first letter of the name, the same one the phone apps draw. A
 * null or blank name shows a question mark rather than nothing, so an unnamed contact still has
 * a face in the list.
 */
@Composable
fun Avatar(name: String?, size: Dp, modifier: Modifier = Modifier) {
    val initial = name?.trim()?.firstOrNull()?.uppercase() ?: "?"
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(Brand.gradient()),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            color = Color.White,
            fontSize = (size.value * 0.42f).sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * The app mark at whatever size the caller asks for, from icons/carrierpony_512.png through the
 * [appIcon] cache. If the resource is missing the widget still renders as a gradient tile with a
 * padlock: an icon is decoration and never the reason a window fails to come up.
 */
@Composable
fun BrandMark(size: Dp, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(Radius.Large)) {
    val painter: Painter? = appIcon("carrierpony_512")
    Box(modifier.size(size).clip(shape), contentAlignment = Alignment.Center) {
        if (painter != null) {
            Image(painter = painter, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(Brand.gradient()), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
            }
        }
    }
}

// Small marks

/** The coral verified tick that follows a contact's name. */
@Composable
fun VerifiedMark(size: Dp = 14.dp) {
    Icon(Icons.Filled.CheckCircle, contentDescription = tr("common_verified"), tint = Brand.Accent, modifier = Modifier.size(size))
}

/** The coral unread pill. */
@Composable
fun UnreadBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Surface(color = Brand.Accent, shape = CircleShape, modifier = modifier) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
        )
    }
}

/**
 * The round toolbar button of the phone app: a dark disc with a coral glyph. Used for the
 * settings gear, the compose pencil and the conversation menu.
 */
@Composable
fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Brand.Accent,
    size: Dp = 40.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

// Buttons

/** The primary call to action: a filled button painted with the send gradient. */
@Composable
fun BrandButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(Radius.Medium),
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = Color.White,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Box(
            modifier = Modifier
                .then(if (enabled) Modifier.background(Brand.sendGradient()) else Modifier)
                .padding(horizontal = Spacing.Large, vertical = Spacing.Medium),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

// Cards, headers, rules

/** A content card with the tonal lift the flat black surface does not give on its own. */
@Composable
fun BrandCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Radius.Medium),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Box(Modifier.padding(Spacing.Large)) { content() }
    }
}

/** A hairline in the scheme's outline colour: list separators, pane edges. */
@Composable
fun HairlineRule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

/** A 2dp gradient rule under a screen heading. */
@Composable
fun BrandRule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)).background(Brand.gradient()))
}

/** The big bold title the phone shows at the top of a tab ("CarrierPony", "Files", "Settings"). */
@Composable
fun TabTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        modifier = modifier,
        style = MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
}

/** A heading inside a screen, marked with a short gradient tick. */
@Composable
fun SectionHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 3.dp, height = 18.dp).clip(RoundedCornerShape(2.dp)).background(Brand.gradientVertical()))
            Spacer(Modifier.width(Spacing.Small))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        }
        if (subtitle != null) {
            Spacer(Modifier.height(Spacing.Tight))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 3.dp + Spacing.Small)
            )
        }
    }
}

/** A [SectionHeader] and its controls inside one [BrandCard]. Settings is a stack of these. */
@Composable
fun SectionCard(title: String, subtitle: String? = null, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    BrandCard(modifier.fillMaxWidth()) {
        Column {
            SectionHeader(title, subtitle)
            Spacer(Modifier.height(Spacing.Medium))
            content()
        }
    }
}

/** A heading one level below [SectionHeader]. No tick. */
@Composable
fun SubHeading(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        modifier = modifier.padding(bottom = Spacing.Tight),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface
    )
}

/** One line of secondary copy under a control. */
@Composable
fun HelpText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * The centred nothing-here state: a washed gradient tile with an icon, a headline, one sentence
 * on what to do about it, and optionally the control that does it. Only fillMaxWidth is applied;
 * a caller with a bounded height that wants it centred passes fillMaxSize itself.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit = {}
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 420.dp).padding(Spacing.Section)) {
            Box(
                Modifier.size(72.dp).clip(RoundedCornerShape(Radius.Large)).background(Brand.gradientWash(0.22f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = Brand.Accent, modifier = Modifier.size(34.dp))
            }
            Spacer(Modifier.height(Spacing.Large))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(Spacing.Small))
            Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Spacing.Large))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) { action() }
        }
    }
}

/** The transient one-line result of the last action. [error] swaps the wash for the error colour. */
@Composable
fun StatusStrip(text: String, modifier: Modifier = Modifier, error: Boolean = false) {
    val wash: Modifier =
        if (error) Modifier.background(MaterialTheme.colorScheme.error.copy(alpha = 0.14f))
        else Modifier.background(Brand.gradientWash(0.18f))
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.Small)).then(wash).padding(horizontal = Spacing.Medium, vertical = Spacing.Small)) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}

/** A short monospace fingerprint tail, the "D231 0DEE" under a contact's name. */
@Composable
fun FingerprintTail(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

// Dialog chrome

/**
 * Every modal in the app wearing the same clothes. Still a Material3 AlertDialog underneath, so
 * the scrim, focus handling and escape-to-dismiss are untouched. [destructive] swaps the gradient
 * tick and title colour for the error colour, so a dialog that erases an account is recognisable
 * before it is read.
 */
@Composable
fun BrandDialog(
    onDismissRequest: () -> Unit,
    title: String,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    destructive: Boolean = false,
    content: @Composable () -> Unit
) {
    val error = MaterialTheme.colorScheme.error
    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = RoundedCornerShape(Radius.Medium),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(width = 3.dp, height = 22.dp).clip(RoundedCornerShape(2.dp))
                        .then(if (destructive) Modifier.background(error) else Modifier.background(Brand.gradientVertical()))
                )
                Spacer(Modifier.width(Spacing.Small))
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = if (destructive) error else MaterialTheme.colorScheme.onSurface)
            }
        },
        text = { content() },
        confirmButton = confirmButton,
        dismissButton = dismissButton
    )
}

/** A label and the value it names, the value in a lighter weight. */
@Composable
fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier, monospace: Boolean = false, trailing: @Composable () -> Unit = {}) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (monospace) FontFamily.Monospace else null,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        trailing()
    }
}
