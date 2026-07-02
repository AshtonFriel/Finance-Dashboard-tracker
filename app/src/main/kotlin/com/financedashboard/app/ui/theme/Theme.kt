package com.financedashboard.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Chart palette: validated reference dataviz palette; light and dark are each
 * selected (not auto-flipped). Semantic assignments are fixed so a series is
 * always the same hue: income/primary = blue, investment = aqua, debt = red,
 * neutral baseline = muted gray.
 */
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
    /** Fixed categorical order per the palette spec — assigned, never cycled. */
    val categorical: List<Color>
        get() = listOf(seriesBlue, seriesAqua, seriesYellow, seriesGreen, seriesViolet, seriesRed, seriesMagenta, seriesOrange)
}

val LightChartColors = ChartColors(
    surface = Color(0xFFFCFCFB),
    primaryInk = Color(0xFF0B0B0B),
    secondaryInk = Color(0xFF52514E),
    mutedInk = Color(0xFF898781),
    gridline = Color(0xFFE1E0D9),
    baseline = Color(0xFFC3C2B7),
    seriesBlue = Color(0xFF2A78D6),
    seriesAqua = Color(0xFF1BAF7A),
    seriesYellow = Color(0xFFEDA100),
    seriesViolet = Color(0xFF4A3AA7),
    seriesRed = Color(0xFFE34948),
    seriesMagenta = Color(0xFFE87BA4),
    seriesOrange = Color(0xFFEB6834),
    seriesGreen = Color(0xFF008300),
    good = Color(0xFF006300),
    critical = Color(0xFFD03B3B),
)

val DarkChartColors = ChartColors(
    surface = Color(0xFF1A1A19),
    primaryInk = Color(0xFFFFFFFF),
    secondaryInk = Color(0xFFC3C2B7),
    mutedInk = Color(0xFF898781),
    gridline = Color(0xFF2C2C2A),
    baseline = Color(0xFF383835),
    seriesBlue = Color(0xFF3987E5),
    seriesAqua = Color(0xFF199E70),
    seriesYellow = Color(0xFFC98500),
    seriesViolet = Color(0xFF9085E9),
    seriesRed = Color(0xFFE66767),
    seriesMagenta = Color(0xFFD55181),
    seriesOrange = Color(0xFFD95926),
    seriesGreen = Color(0xFF008300),
    good = Color(0xFF0CA30C),
    critical = Color(0xFFD03B3B),
)

val LocalChartColors = staticCompositionLocalOf { LightChartColors }

private val LightScheme = lightColorScheme(
    primary = Color(0xFF2A78D6),
    secondary = Color(0xFF1BAF7A),
    error = Color(0xFFD03B3B),
    background = Color(0xFFF9F9F7),
    surface = Color(0xFFFCFCFB),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF3987E5),
    secondary = Color(0xFF199E70),
    error = Color(0xFFE66767),
    background = Color(0xFF0D0D0D),
    surface = Color(0xFF1A1A19),
)

@Composable
fun FinanceDashboardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    val chartColors = if (darkTheme) DarkChartColors else LightChartColors
    CompositionLocalProvider(LocalChartColors provides chartColors) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}
