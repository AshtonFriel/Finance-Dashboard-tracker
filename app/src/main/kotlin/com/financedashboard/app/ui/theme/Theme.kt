package com.financedashboard.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.financedashboard.app.R

/**
 * "Fiscal" design language (per design handoff): bold, motivating, dark-green.
 * The app is dark-only by design intent.
 */
object Fiscal {
    val Background = Color(0xFF0F1F17)
    val NavBackground = Color(0xFF0C1912)
    val Card = Color(0xFF16271E)
    val CardBorder = Color(0x0DFFFFFF) // white 5%
    val HeroGradientStart = Color(0xFF173026)
    val HeroGradientEnd = Color(0xFF12241B)
    val HeroBorder = Color(0x243ECF8E) // accent 14%
    val Accent = Color(0xFF3ECF8E)
    val AccentDark = Color(0xFF2BA876)
    val OnAccent = Color(0xFF0F1F17)
    val AccentTint = Color(0x143ECF8E) // accent 8%
    val Coral = Color(0xFFFF8B6B)
    val CoralTintBg = Color(0x1AFF8B6B) // coral 10%
    val CoralTintText = Color(0xFFFFB59E)
    val Amber = Color(0xFFF5C451)
    val Sky = Color(0xFF6BD0FF)
    val TextPrimary = Color(0xFFEAF3EE)
    val TextSecondary = Color(0xFF8FA89A)
    val TextMuted = Color(0xFF5F7A6C)
    val Track = Color(0x14FFFFFF) // white 8%
    val Hairline = Color(0x0FFFFFFF) // white 6%
}

/** Chart roles mapped onto the Fiscal palette. */
data class ChartColors(
    val surface: Color,
    val primaryInk: Color,
    val secondaryInk: Color,
    val mutedInk: Color,
    val gridline: Color,
    val baseline: Color,
    val seriesBlue: Color,
    val seriesAqua: Color,
    val seriesYellow: Color,
    val seriesViolet: Color,
    val seriesRed: Color,
    val seriesMagenta: Color,
    val seriesOrange: Color,
    val seriesGreen: Color,
    val good: Color,
    val critical: Color,
) {
    /** Fixed categorical order — assigned, never cycled. */
    val categorical: List<Color>
        get() = listOf(seriesAqua, seriesYellow, seriesBlue, seriesRed, seriesViolet, seriesMagenta, seriesOrange, seriesGreen)
}

val FiscalChartColors = ChartColors(
    surface = Fiscal.Card,
    primaryInk = Fiscal.TextPrimary,
    secondaryInk = Fiscal.TextSecondary,
    mutedInk = Fiscal.TextMuted,
    gridline = Fiscal.Hairline,
    baseline = Fiscal.Track,
    seriesBlue = Fiscal.Sky,
    seriesAqua = Fiscal.Accent,
    seriesYellow = Fiscal.Amber,
    seriesViolet = Color(0xFFB39DF1),
    seriesRed = Fiscal.Coral,
    seriesMagenta = Color(0xFFE87BA4),
    seriesOrange = Color(0xFFE8A06B),
    seriesGreen = Fiscal.AccentDark,
    good = Fiscal.Accent,
    critical = Fiscal.Coral,
)

val LocalChartColors = staticCompositionLocalOf { FiscalChartColors }

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun grotesk(weight: Int) = Font(
    R.font.space_grotesk,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun plex(weight: Int) = Font(
    R.font.ibm_plex_sans,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Numbers, titles, big stats. */
val SpaceGrotesk = FontFamily(grotesk(500), grotesk(600), grotesk(700))

/** Body, labels, buttons. */
val IbmPlexSans = FontFamily(plex(400), plex(500), plex(600))

private val FiscalTypography = Typography(
    headlineSmall = TextStyle( // screen titles: 26/700 Space Grotesk
        fontFamily = SpaceGrotesk, fontWeight = FontWeight.W700, fontSize = 26.sp,
    ),
    headlineMedium = TextStyle( // hero numbers: 30-38/700
        fontFamily = SpaceGrotesk, fontWeight = FontWeight.W700, fontSize = 34.sp,
    ),
    titleLarge = TextStyle( // card stats: 22/700
        fontFamily = SpaceGrotesk, fontWeight = FontWeight.W700, fontSize = 22.sp,
    ),
    titleMedium = TextStyle( // sub-stats: 18/700
        fontFamily = SpaceGrotesk, fontWeight = FontWeight.W700, fontSize = 18.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = SpaceGrotesk, fontWeight = FontWeight.W600, fontSize = 15.sp,
    ),
    bodyLarge = TextStyle(fontFamily = IbmPlexSans, fontWeight = FontWeight.W400, fontSize = 15.sp),
    bodyMedium = TextStyle(fontFamily = IbmPlexSans, fontWeight = FontWeight.W400, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = IbmPlexSans, fontWeight = FontWeight.W400, fontSize = 13.sp),
    labelLarge = TextStyle(fontFamily = IbmPlexSans, fontWeight = FontWeight.W600, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = IbmPlexSans, fontWeight = FontWeight.W500, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = IbmPlexSans, fontWeight = FontWeight.W500, fontSize = 11.sp),
)

private val FiscalScheme = darkColorScheme(
    primary = Fiscal.Accent,
    onPrimary = Fiscal.OnAccent,
    secondary = Fiscal.Sky,
    error = Fiscal.Coral,
    background = Fiscal.Background,
    onBackground = Fiscal.TextPrimary,
    surface = Fiscal.Card,
    onSurface = Fiscal.TextPrimary,
    surfaceVariant = Fiscal.Card,
    onSurfaceVariant = Fiscal.TextSecondary,
    outline = Fiscal.TextMuted,
    surfaceContainer = Fiscal.NavBackground,
    surfaceContainerHigh = Fiscal.Card,
    surfaceContainerHighest = Fiscal.Card,
)

@Composable
fun FinanceDashboardTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalChartColors provides FiscalChartColors) {
        MaterialTheme(
            colorScheme = FiscalScheme,
            typography = FiscalTypography,
            content = content,
        )
    }
}
