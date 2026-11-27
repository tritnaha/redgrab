package app.redgrab.ui

import app.redgrab.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A selectable look for the app. [accent]/[saved]/[warning]/[edited] colour badges and highlights. */
data class AppTheme(
    val id: String,
    val name: String,
    val description: String,
    val isDark: Boolean,
    val colors: ColorScheme,
    val accent: Color,
    val saved: Color,
    val warning: Color,
    val edited: Color,
) {
    val preview: List<Color> get() = listOf(colors.background, colors.surfaceContainerHigh, accent, saved)
}

private fun dark(
    accent: Color, onAccent: Color, accentContainer: Color, onAccentContainer: Color,
    bg: Color, s1: Color, s2: Color, s3: Color, s4: Color, s5: Color,
    text: Color, textDim: Color, outline: Color, secondary: Color,
) = darkColorScheme(
    primary = accent, onPrimary = onAccent, primaryContainer = accentContainer, onPrimaryContainer = onAccentContainer,
    secondary = secondary, onSecondary = bg, secondaryContainer = s4, onSecondaryContainer = text,
    tertiary = secondary, onTertiary = bg, tertiaryContainer = s4, onTertiaryContainer = text,
    background = bg, onBackground = text, surface = bg, onSurface = text,
    surfaceVariant = s4, onSurfaceVariant = textDim,
    surfaceContainerLowest = bg, surfaceContainerLow = s1, surfaceContainer = s2,
    surfaceContainerHigh = s3, surfaceContainerHighest = s5,
    outline = outline, outlineVariant = s4, error = Color(0xFFFF6B6B), onError = Color.Black,
    errorContainer = Color(0xFF6B1616), onErrorContainer = Color(0xFFFFDAD6),
    surfaceTint = accent, surfaceDim = bg, surfaceBright = s5,
    inverseSurface = text, inverseOnSurface = bg, inversePrimary = accentContainer, scrim = Color.Black,
)

object AppThemes {
    val redgrab = AppTheme(
        "redgrab", "RedGrab", "Dark graphite with RedGrab red", true,
        dark(Color(0xFFFF3B4E), Color(0xFF0E0E11), Color(0xFF4A0F18), Color(0xFFFFD9DC),
            Color(0xFF0E0E11), Color(0xFF141418), Color(0xFF18181D), Color(0xFF1F1F25), Color(0xFF26262D), Color(0xFF29292F),
            Color(0xFFECECEF), Color(0xFFA9A9B3), Color(0xFF3A3A43), Color(0xFFFF8A95)),
        accent = Color(0xFFFF3B4E), saved = Color(0xFF2FD17A), warning = Color(0xFFFFB547), edited = Color(0xFFA78BFA),
    )

    val amoled = AppTheme(
        "amoled", "AMOLED Black", "True black for OLED screens; easiest on battery", true,
        dark(Color(0xFFFF4458), Color.Black, Color(0xFF3D0A12), Color(0xFFFFD9DC),
            Color(0xFF000000), Color(0xFF080808), Color(0xFF0E0E0E), Color(0xFF151515), Color(0xFF1C1C1C), Color(0xFF222222),
            Color(0xFFF2F2F2), Color(0xFFA6A6A6), Color(0xFF333333), Color(0xFFFF8A95)),
        accent = Color(0xFFFF4458), saved = Color(0xFF34D399), warning = Color(0xFFFBBF24), edited = Color(0xFFA78BFA),
    )

    val midnight = AppTheme(
        "midnight", "Midnight Blue", "Deep navy with an electric blue accent", true,
        dark(Color(0xFF5B9DFF), Color(0xFF00214D), Color(0xFF0F2C57), Color(0xFFD6E4FF),
            Color(0xFF0A0F1C), Color(0xFF0F1626), Color(0xFF131B2E), Color(0xFF1A2338), Color(0xFF222C44), Color(0xFF28334D),
            Color(0xFFE6ECF7), Color(0xFF9AA7BF), Color(0xFF34405A), Color(0xFF8FB8FF)),
        accent = Color(0xFF5B9DFF), saved = Color(0xFF3DD68C), warning = Color(0xFFFFC14D), edited = Color(0xFFC084FC),
    )

    val forest = AppTheme(
        "forest", "Forest", "Dark moss green, calm and low-key", true,
        dark(Color(0xFF4ADE80), Color(0xFF052E16), Color(0xFF14412A), Color(0xFFD1FAE0),
            Color(0xFF0B110D), Color(0xFF101813), Color(0xFF141E17), Color(0xFF1A261E), Color(0xFF213026), Color(0xFF26372C),
            Color(0xFFE5EFE8), Color(0xFF9DB0A3), Color(0xFF34463A), Color(0xFF86EFAC)),
        accent = Color(0xFF4ADE80), saved = Color(0xFF22D3EE), warning = Color(0xFFFACC15), edited = Color(0xFFF0ABFC),
    )

    val sunset = AppTheme(
        "sunset", "Sunset", "Warm plum with an orange–magenta accent", true,
        dark(Color(0xFFFF7A45), Color(0xFF3A1000), Color(0xFF4F1B0B), Color(0xFFFFDBCC),
            Color(0xFF140C12), Color(0xFF1B1118), Color(0xFF21151D), Color(0xFF2A1B25), Color(0xFF33212D), Color(0xFF3A2733),
            Color(0xFFF5E8EF), Color(0xFFB9A2B0), Color(0xFF4A3442), Color(0xFFFF5FA2)),
        accent = Color(0xFFFF7A45), saved = Color(0xFF4ADE80), warning = Color(0xFFFFD166), edited = Color(0xFFFF5FA2),
    )

    val light = AppTheme(
        "light", "Light", "Clean light theme for daytime", false,
        lightColorScheme(
            primary = Color(0xFFC52236), onPrimary = Color.White, primaryContainer = Color(0xFFFFDADC), onPrimaryContainer = Color(0xFF41000A),
            secondary = Color(0xFF8E4A52), onSecondary = Color.White, secondaryContainer = Color(0xFFF3E1E3), onSecondaryContainer = Color(0xFF2B1518),
            tertiary = Color(0xFF8E4A52), onTertiary = Color.White,
            tertiaryContainer = Color(0xFFF3E1E3), onTertiaryContainer = Color(0xFF2B1518),
            background = Color(0xFFFAF8F8), onBackground = Color(0xFF1B1B1F), surface = Color(0xFFFAF8F8), onSurface = Color(0xFF1B1B1F),
            surfaceVariant = Color(0xFFEDE7E8), onSurfaceVariant = Color(0xFF55494B),
            surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF5F2F2), surfaceContainer = Color(0xFFF0ECEC),
            surfaceContainerHigh = Color(0xFFEAE5E6), surfaceContainerHighest = Color(0xFFE4DEDF),
            inverseSurface = Color(0xFF303034), inverseOnSurface = Color(0xFFF5F2F2), inversePrimary = Color(0xFFFFB3BA),
            surfaceTint = Color(0xFFC52236), surfaceDim = Color(0xFFE4DEDF), surfaceBright = Color(0xFFFAF8F8),
            errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
            outline = Color(0xFF887B7D), outlineVariant = Color(0xFFD8CCCE), error = Color(0xFFBA1A1A), onError = Color.White,
        ),
        accent = Color(0xFFC52236), saved = Color(0xFF117236), warning = Color(0xFF994600), edited = Color(0xFF7C3AED),
    )

    val rose = AppTheme(
        "rose", "Rosé Light", "Soft pink-cream light theme", false,
        lightColorScheme(
            primary = Color(0xFFB4235F), onPrimary = Color.White, primaryContainer = Color(0xFFFFD9E4), onPrimaryContainer = Color(0xFF3E0020),
            secondary = Color(0xFF7D5260), onSecondary = Color.White, secondaryContainer = Color(0xFFFFE4EC), onSecondaryContainer = Color(0xFF31101D),
            tertiary = Color(0xFF7D5260), onTertiary = Color.White,
            tertiaryContainer = Color(0xFFFFE4EC), onTertiaryContainer = Color(0xFF31101D),
            background = Color(0xFFFFF7F9), onBackground = Color(0xFF221A1D), surface = Color(0xFFFFF7F9), onSurface = Color(0xFF221A1D),
            surfaceVariant = Color(0xFFF3DDE4), onSurfaceVariant = Color(0xFF524348),
            surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFFF0F4), surfaceContainer = Color(0xFFFBE9EE),
            surfaceContainerHigh = Color(0xFFF5E2E8), surfaceContainerHighest = Color(0xFFEFDCE2),
            inverseSurface = Color(0xFF372D31), inverseOnSurface = Color(0xFFFFF0F4), inversePrimary = Color(0xFFFFB0CD),
            surfaceTint = Color(0xFFB4235F), surfaceDim = Color(0xFFEFDCE2), surfaceBright = Color(0xFFFFF7F9),
            errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
            outline = Color(0xFF85737A), outlineVariant = Color(0xFFD7C1C8), error = Color(0xFFBA1A1A), onError = Color.White,
        ),
        accent = Color(0xFFB4235F), saved = Color(0xFF117236), warning = Color(0xFF994600), edited = Color(0xFF6D28D9),
    )

    val graphite = AppTheme(
        "graphite", "Graphite", "Neutral charcoal with a quiet teal", true,
        dark(Color(0xFF80CBC4), Color(0xFF102826), Color(0xFF254744), Color(0xFFCEFAF4),
            Color(0xFF121414), Color(0xFF191B1B), Color(0xFF202222), Color(0xFF282B2B), Color(0xFF303535), Color(0xFF383E3E),
            Color(0xFFE9EEEE), Color(0xFFB0BCBA), Color(0xFF71827F), Color(0xFFB7C8D8)),
        accent = Color(0xFF80CBC4), saved = Color(0xFFA8D98B), warning = Color(0xFFF1BE68), edited = Color(0xFFD5A6E6),
    )

    val nord = AppTheme(
        "nord", "Nord", "Arctic blue-grey and glacier blue", true,
        dark(Color(0xFF88C0D0), Color(0xFF182D37), Color(0xFF314C5B), Color(0xFFD8F1FA),
            Color(0xFF202630), Color(0xFF272F3B), Color(0xFF2E3745), Color(0xFF354050), Color(0xFF3B4859), Color(0xFF435166),
            Color(0xFFECEFF4), Color(0xFFBBC8D9), Color(0xFF7C8EA7), Color(0xFFB5C9E7)),
        accent = Color(0xFF88C0D0), saved = Color(0xFFA3BE8C), warning = Color(0xFFEBCB8B), edited = Color(0xFFD5A7D0),
    )

    val dracula = AppTheme(
        "dracula", "Dracula", "Purple nights with vivid pastel highlights", true,
        dark(Color(0xFFBD93F9), Color(0xFF29123F), Color(0xFF4B3266), Color(0xFFEEDCFF),
            Color(0xFF191721), Color(0xFF211E2C), Color(0xFF282435), Color(0xFF302B40), Color(0xFF383248), Color(0xFF40394F),
            Color(0xFFF8F8F2), Color(0xFFC5BCD8), Color(0xFF8D80A6), Color(0xFFFF92C9)),
        accent = Color(0xFFBD93F9), saved = Color(0xFF50FA7B), warning = Color(0xFFF1FA8C), edited = Color(0xFFFF92C9),
    )

    val solar = AppTheme(
        "solar", "Solar", "Warm sepia with mellow amber light", true,
        dark(Color(0xFFE7B66A), Color(0xFF34240E), Color(0xFF59401D), Color(0xFFFFE5B7),
            Color(0xFF19150F), Color(0xFF211C14), Color(0xFF292219), Color(0xFF32291E), Color(0xFF3B3124), Color(0xFF443929),
            Color(0xFFF1E4CC), Color(0xFFC5B596), Color(0xFF958262), Color(0xFFE1C298)),
        accent = Color(0xFFE7B66A), saved = Color(0xFFB9CD8A), warning = Color(0xFFF1D887), edited = Color(0xFFC4ABDB),
    )

    val paper = AppTheme(
        "paper", "Paper", "Warm paper with an ink-blue accent", false,
        lightColorScheme(
            primary = Color(0xFF254F78), onPrimary = Color(0xFFFFFDF6), primaryContainer = Color(0xFFD8E6F1), onPrimaryContainer = Color(0xFF142D46),
            secondary = Color(0xFF5C6346), onSecondary = Color(0xFFFFFDF6), secondaryContainer = Color(0xFFE3E7D1), onSecondaryContainer = Color(0xFF282F1C),
            tertiary = Color(0xFF795281), onTertiary = Color(0xFFFFFDF6), tertiaryContainer = Color(0xFFF0DEF2), onTertiaryContainer = Color(0xFF341B3C),
            background = Color(0xFFFAF6EC), onBackground = Color(0xFF292820), surface = Color(0xFFFAF6EC), onSurface = Color(0xFF292820),
            surfaceVariant = Color(0xFFE8E2D4), onSurfaceVariant = Color(0xFF585447),
            surfaceContainerLowest = Color(0xFFFFFDF6), surfaceContainerLow = Color(0xFFF5F0E5), surfaceContainer = Color(0xFFF0EADD),
            surfaceContainerHigh = Color(0xFFEAE3D5), surfaceContainerHighest = Color(0xFFE3DCCC),
            outline = Color(0xFF827B6B), outlineVariant = Color(0xFFCEC5B2), error = Color(0xFFAC3027), onError = Color(0xFFFFFDF6),
            errorContainer = Color(0xFFFFDAD4), onErrorContainer = Color(0xFF410A05),
            inverseSurface = Color(0xFF333128), inverseOnSurface = Color(0xFFF5F0E5), inversePrimary = Color(0xFFA9CEEF),
            surfaceTint = Color(0xFF254F78), surfaceDim = Color(0xFFE3DCCC), surfaceBright = Color(0xFFFAF6EC), scrim = Color.Black,
        ),
        accent = Color(0xFF254F78), saved = Color(0xFF37653D), warning = Color(0xFF8B4B0F), edited = Color(0xFF795281),
    )

    val all = listOf(redgrab, amoled, midnight, forest, sunset, light, rose, graphite, nord, dracula, solar, paper)
    val default = redgrab
    fun byId(id: String?): AppTheme = all.firstOrNull { it.id == id } ?: default
}

/** Small card showing a theme's colours, for the theme picker in Settings. */
@Composable
fun ThemePreviewSwatch(theme: AppTheme, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.clip(shape).background(theme.colors.background)
            .border(if (selected) 2.5.dp else 1.dp, if (selected) theme.accent else theme.colors.outlineVariant, shape)
            .clickable(onClick = onClick).padding(10.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(10.dp)).background(theme.colors.surfaceContainerHigh)) {
            Text("Aa", color = theme.colors.onSurfaceVariant, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 5.dp))
            Row(Modifier.align(Alignment.BottomStart).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(theme.accent, theme.saved, theme.warning, theme.edited).forEach { c -> Box(Modifier.size(14.dp).clip(CircleShape).background(c)) }
            }
            if (selected) Icon(Icons.Default.CheckCircle, "Selected", tint = theme.accent,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp))
        }
        Text(theme.name, color = theme.colors.onBackground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp))
        Text(theme.description, color = theme.colors.onSurfaceVariant, fontSize = 11.sp, maxLines = 2, lineHeight = 13.sp)
    }
}
