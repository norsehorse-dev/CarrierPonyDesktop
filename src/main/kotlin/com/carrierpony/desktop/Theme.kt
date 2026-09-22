// Theme.kt
// CarrierPony Desktop. D11: the two colour schemes and the System / Light / Dark switch.
//
// The palette is the app icon's: gold, coral and red on the gradient, and the horse's coral as
// the one flat accent. Android's ui/theme/Color.kt and iOS CPTheme sample the same hex values,
// so the three clients read as one product. The dark scheme is tuned to the iPhone app: a true
// black canvas, dark grey incoming bubbles, coral outgoing bubbles, grey metadata. The light
// scheme mirrors it on white with the iOS light grey bubble.
//
// Dynamic colour is deliberately off. The brand is the colour story.

package com.carrierpony.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color

/** The radio labels are resource keys: an enum entry is a compile-time constant. */
enum class AppTheme(val labelKey: String, val storageKey: String) {
    System("d_theme_system", "system"),
    Light("d_theme_light", "light"),
    Dark("d_theme_dark", "dark");

    companion object {
        fun fromStorage(value: String?): AppTheme = when (value) {
            Light.storageKey -> Light
            Dark.storageKey -> Dark
            else -> System
        }
    }
}

object ThemeState {
    const val KEY_THEME = "cp.desktop.theme"

    private var prefs: DesktopPrefs? = null

    /** Live value, read by CarrierPonyTheme through a snapshot subscription, written by Settings. */
    val current: MutableState<AppTheme> = mutableStateOf(AppTheme.System)

    fun attach(store: DesktopPrefs) {
        prefs = store
        current.value = AppTheme.fromStorage(runCatching { store.getString(KEY_THEME) }.getOrNull())
    }

    fun set(theme: AppTheme) {
        current.value = theme
        runCatching { prefs?.putString(KEY_THEME, theme.storageKey) }
    }
}

@Composable
fun resolveColorScheme(theme: AppTheme): ColorScheme {
    val isDark = when (theme) {
        AppTheme.System -> isSystemInDarkTheme()
        AppTheme.Light -> false
        AppTheme.Dark -> true
    }
    return if (isDark) CarrierPonyDarkColorScheme else CarrierPonyLightColorScheme
}

@Composable
fun CarrierPonyTheme(content: @Composable () -> Unit) {
    val theme by ThemeState.current
    MaterialTheme(colorScheme = resolveColorScheme(theme), content = content)
}

/** True when the scheme in force is the dark one. For the few places that pick an asset by it. */
@Composable
fun isDarkScheme(): Boolean = MaterialTheme.colorScheme.background == CarrierPonyDarkColorScheme.background

// The icon palette, exact hex from CarrierPony-AppIcon-1024.

val CPGold = Color(0xFFEBC289)
val CPCoral = Color(0xFFF28B80)
val CPRed = Color(0xFFF1667B)

val CPAccent = Color(0xFFEC6755)
val CPAccentLite = Color(0xFFEF8055)
val CPAccentDeep = Color(0xFFF15056)

val CarrierPonyDarkColorScheme: ColorScheme = darkColorScheme(
    primary = CPAccent,
    onPrimary = Color.White,
    primaryContainer = CPAccent,
    onPrimaryContainer = Color.White,
    secondary = CPAccentLite,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF3A2320),
    onSecondaryContainer = Color(0xFFFFD9D3),
    tertiary = CPAccentDeep,
    background = Color(0xFF000000),
    onBackground = Color.White,
    surface = Color(0xFF000000),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFF9B9BA1),
    surfaceContainer = Color(0xFF151517),
    surfaceContainerHigh = Color(0xFF1F1F21),
    surfaceContainerHighest = Color(0xFF2C2C2E),
    outline = Color(0xFF3A3A3C),
    outlineVariant = Color(0xFF2A2A2C),
    error = CPAccentDeep,
    onError = Color.White,
    errorContainer = Color(0xFF3B1D20),
    onErrorContainer = Color(0xFFFFB4AB)
)

val CarrierPonyLightColorScheme: ColorScheme = lightColorScheme(
    primary = CPAccent,
    onPrimary = Color.White,
    primaryContainer = CPAccent,
    onPrimaryContainer = Color.White,
    secondary = CPAccentLite,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE4DE),
    onSecondaryContainer = Color(0xFF5A211A),
    tertiary = CPAccentDeep,
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF111111),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFE9E9EB),
    onSurfaceVariant = Color(0xFF6E6E73),
    surfaceContainer = Color(0xFFF4F4F6),
    surfaceContainerHigh = Color(0xFFEDEDF0),
    surfaceContainerHighest = Color(0xFFE9E9EB),
    outline = Color(0xFFC7C7CC),
    outlineVariant = Color(0xFFE2E2E6),
    error = Color(0xFFD93B3B),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)
