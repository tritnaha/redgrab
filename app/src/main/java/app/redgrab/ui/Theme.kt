package app.redgrab.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val LocalAppTheme = staticCompositionLocalOf { AppThemes.default }

/** Theme-aware highlight colours used across the app. */
val RgRed: Color @Composable @ReadOnlyComposable get() = LocalAppTheme.current.accent
val RgGreen: Color @Composable @ReadOnlyComposable get() = LocalAppTheme.current.saved
val RgAmber: Color @Composable @ReadOnlyComposable get() = LocalAppTheme.current.warning
val RgPurple: Color @Composable @ReadOnlyComposable get() = LocalAppTheme.current.edited

@Composable
fun RedGrabTheme(themeId: String? = null, content: @Composable () -> Unit) {
    val theme = AppThemes.byId(themeId)
    CompositionLocalProvider(LocalAppTheme provides theme) {
        MaterialTheme(colorScheme = theme.colors, content = content)
    }
}
