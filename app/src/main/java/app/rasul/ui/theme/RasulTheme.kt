package app.rasul.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalInspectionMode
import app.rasul.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Color and typography tokens for the light and dark themes. Names follow each token's role. */
data class RasulColors(
    val bg: Color,
    val ink: Color,
    val muted: Color,
    val hairline: Color,
    val cardBorder: Color,
    val fill: Color,
    val selected: Color,
    val accent: Color,
    val accentSoft: Color,
    val accentHintText: Color,
    val success: Color,
    val successSoft: Color,
    val successText: Color,
    val danger: Color,
    val dangerSoft: Color,
    val dangerText: Color,
    val warn: Color,
    val warnText: Color,
    val badge: Color,
    val onAccent: Color,
    val scrim: Color,
    val avatarCrimson: Color,
    val avatarViolet: Color,
    val avatarTan: Color,
    val toggleOff: Color,
)

val LightRasulColors = RasulColors(
    bg = Color.White, ink = Color(0xFF1C1C1E), muted = Color(0xFF6B6B70), hairline = Color(0xFFD0D0D5),
    cardBorder = Color(0xFFE0E0E4), fill = Color(0xFFF3F3F6), selected = Color(0xFFE8E8EA),
    accent = Color(0xFF1F5FD6), accentSoft = Color(0xFFE3ECFB), accentHintText = Color(0xFF1B3F8F),
    success = Color(0xFF1F9D55), successSoft = Color(0xFFE2F4EA), successText = Color(0xFF14703B),
    danger = Color(0xFFC0243F), dangerSoft = Color(0xFFFCE6EA), dangerText = Color(0xFF8E1B30),
    warn = Color(0xFFD98200), warnText = Color(0xFF8A5200), badge = Color(0xFFF23C3C), onAccent = Color.White, scrim = Color(0x73000000),
    avatarCrimson = Color(0xFFB3274A), avatarViolet = Color(0xFF9B7BF5), avatarTan = Color(0xFFC98A54),
    toggleOff = Color(0xFFD0D0D5),
)

// The dark palette is a hand-tuned inversion of the light theme's color roles.
val DarkRasulColors = RasulColors(
    bg = Color(0xFF111113), ink = Color(0xFFF2F2F5), muted = Color(0xFF9A9AA2), hairline = Color(0xFF3A3A40),
    cardBorder = Color(0xFF34343A), fill = Color(0xFF1E1E22), selected = Color(0xFF2C2C31),
    accent = Color(0xFF6E9CF5), accentSoft = Color(0xFF1B2A47), accentHintText = Color(0xFFB7CDF8),
    success = Color(0xFF3CC47A), successSoft = Color(0xFF13301F), successText = Color(0xFF8EE0B1),
    danger = Color(0xFFF0627A), dangerSoft = Color(0xFF3A1820), dangerText = Color(0xFFF7A9B6),
    warn = Color(0xFFF0A63A), warnText = Color(0xFFF0A63A), badge = Color(0xFFF23C3C), onAccent = Color(0xFF0B1B3A), scrim = Color(0x99000000),
    avatarCrimson = Color(0xFFB3274A), avatarViolet = Color(0xFF9B7BF5), avatarTan = Color(0xFFC98A54),
    toggleOff = Color(0xFF45454C),
)

val LocalRasulColors = staticCompositionLocalOf { LightRasulColors }
private val LocalRasulFontFamily = staticCompositionLocalOf<FontFamily> { FontFamily.Default }

internal fun rasulFontFamily(language: String): FontFamily = if (language == "ar") {
    FontFamily(
        Font(R.font.ibm_plex_sans_arabic_regular, FontWeight.Normal),
        Font(R.font.ibm_plex_sans_arabic_medium, FontWeight.Medium),
        Font(R.font.ibm_plex_sans_arabic_semibold, FontWeight.SemiBold),
        Font(R.font.ibm_plex_sans_arabic_bold, FontWeight.Bold),
    )
} else {
    FontFamily(
        Font(R.font.readex_pro_regular, FontWeight.Normal),
        Font(R.font.readex_pro_medium, FontWeight.Medium),
        Font(R.font.readex_pro_semibold, FontWeight.SemiBold),
        Font(R.font.readex_pro_bold, FontWeight.Bold),
    )
}

internal val RasulMonoFontFamily = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
)

internal fun rasulTypography(fontFamily: FontFamily): Typography {
    val base = Typography()
    return base.copy(
    displayLarge = base.displayLarge.copy(fontFamily = fontFamily),
    displayMedium = base.displayMedium.copy(fontFamily = fontFamily),
    displaySmall = base.displaySmall.copy(fontFamily = fontFamily),
    headlineLarge = base.headlineLarge.copy(fontFamily = fontFamily),
    headlineMedium = base.headlineMedium.copy(fontFamily = fontFamily),
    headlineSmall = base.headlineSmall.copy(fontFamily = fontFamily),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = fontFamily),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = fontFamily),
    titleSmall = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = fontFamily),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontFamily = fontFamily),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontFamily = fontFamily),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontFamily = fontFamily),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = fontFamily),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, fontFamily = fontFamily),
    labelSmall = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.Medium, fontFamily = fontFamily),
    )
}

/** Text styles use the current app locale's Readex Pro or IBM Plex Sans Arabic family. */
object RasulType {
    private val family: FontFamily @Composable get() = LocalRasulFontFamily.current
    val title: TextStyle @Composable get() = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, fontFamily = family)
    val sheetTitle: TextStyle @Composable get() = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = family)
    val dialogTitle: TextStyle @Composable get() = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Bold, fontFamily = family)
    val emptyTitle: TextStyle @Composable get() = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = family)
    val rowTitle: TextStyle @Composable get() = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = family)
    val rowSub: TextStyle @Composable get() = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontFamily = family)
    val body: TextStyle @Composable get() = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontFamily = family)
    val bodySmall: TextStyle @Composable get() = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontFamily = family)
    val meta: TextStyle @Composable get() = TextStyle(fontSize = 13.sp, fontFamily = family)
    val sectionLabel: TextStyle @Composable get() = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = family)
    val chip: TextStyle @Composable get() = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, fontFamily = family)
    val tab: TextStyle @Composable get() = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.Medium, fontFamily = family)
    val button: TextStyle @Composable get() = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = family)
    val buttonSmall: TextStyle @Composable get() = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = family)
    val pill: TextStyle @Composable get() = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, fontFamily = family)
    val navBar: TextStyle @Composable get() = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, fontFamily = family)
    val navAction: TextStyle @Composable get() = TextStyle(fontSize = 17.sp, fontFamily = family)
    val optionTitle: TextStyle @Composable get() = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium, fontFamily = family)
}

object RasulShapes {
    val glassPill = RoundedCornerShape(20.dp)
    val tabBar = RoundedCornerShape(30.dp)
    val tabSelected = RoundedCornerShape(24.dp)
    val chip = RoundedCornerShape(12.dp)
    val filter = RoundedCornerShape(16.dp)
    val dialog = RoundedCornerShape(22.dp)
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val card = RoundedCornerShape(16.dp)
    val input = RoundedCornerShape(12.dp)
    val button = RoundedCornerShape(26.dp)
    val result = RoundedCornerShape(14.dp)
}

object Rasul {
    val colors: RasulColors
        @Composable get() = LocalRasulColors.current
}

@Composable
fun RasulTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val language = LocalConfiguration.current.locales[0].language
    val fontFamily = remember(language) { rasulFontFamily(language) }
    // Preview night mode uses the same palette and Material roles as the application.
    val useDark = if (LocalInspectionMode.current) isSystemInDarkTheme() else dark
    val c = if (useDark) DarkRasulColors else LightRasulColors
    val scheme = if (useDark) {
        darkColorScheme(
            primary = c.accent, onPrimary = c.onAccent, background = c.bg, onBackground = c.ink,
            surface = c.bg, onSurface = c.ink, surfaceVariant = c.fill, onSurfaceVariant = c.muted,
            outline = c.cardBorder, outlineVariant = c.cardBorder,
            primaryContainer = c.accentSoft, onPrimaryContainer = c.accentHintText,
            secondary = c.accent, onSecondary = c.onAccent,
            secondaryContainer = c.accentSoft, onSecondaryContainer = c.accentHintText,
            error = c.danger, onError = c.onAccent,
            errorContainer = c.dangerSoft, onErrorContainer = c.dangerText,
            surfaceTint = Color.Transparent, scrim = c.scrim,
            surfaceDim = c.bg, surfaceBright = c.bg,
            surfaceContainerLowest = c.bg, surfaceContainerLow = c.bg,
            surfaceContainer = c.bg, surfaceContainerHigh = c.bg, surfaceContainerHighest = c.fill,
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = c.onAccent, background = c.bg, onBackground = c.ink,
            surface = c.bg, onSurface = c.ink, surfaceVariant = c.fill, onSurfaceVariant = c.muted,
            outline = c.cardBorder, outlineVariant = c.cardBorder,
            primaryContainer = c.accentSoft, onPrimaryContainer = c.accentHintText,
            secondary = c.accent, onSecondary = c.onAccent,
            secondaryContainer = c.accentSoft, onSecondaryContainer = c.accentHintText,
            error = c.danger, onError = c.onAccent,
            errorContainer = c.dangerSoft, onErrorContainer = c.dangerText,
            surfaceTint = Color.Transparent, scrim = c.scrim,
            surfaceDim = c.bg, surfaceBright = c.bg,
            surfaceContainerLowest = c.bg, surfaceContainerLow = c.bg,
            surfaceContainer = c.bg, surfaceContainerHigh = c.bg, surfaceContainerHighest = c.fill,
        )
    }
    val type = rasulTypography(fontFamily)
    val shapes = Shapes(
        small = RasulShapes.input, medium = RasulShapes.card, large = RasulShapes.dialog,
        extraLarge = RasulShapes.dialog,
    )
    CompositionLocalProvider(LocalRasulColors provides c, LocalRasulFontFamily provides fontFamily, LocalContentColor provides c.ink) {
        MaterialTheme(colorScheme = scheme, typography = type, shapes = shapes, content = content)
    }
}
