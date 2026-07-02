package com.financedashboard.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.financedashboard.app.ui.theme.Fiscal
import com.financedashboard.app.ui.theme.SpaceGrotesk

/** Standard card: surface #16271e, radius 20, hairline border. */
@Composable
fun FiscalCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Fiscal.Card)
            .border(1.dp, Fiscal.CardBorder, RoundedCornerShape(20.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(18.dp),
        content = content,
    )
}

/** Hero card: green gradient, radius 24, accent-tinted border. */
@Composable
fun HeroCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(Fiscal.HeroGradientStart, Fiscal.HeroGradientEnd),
                    start = Offset.Zero,
                    end = Offset.Infinite,
                )
            )
            .border(1.dp, Fiscal.HeroBorder, RoundedCornerShape(24.dp))
            .padding(20.dp),
        content = content,
    )
}

/** Section eyebrow: 12sp uppercase, letter-spaced, muted. */
@Composable
fun Eyebrow(text: String, color: Color = Fiscal.TextMuted, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.sp),
        fontWeight = FontWeight.W600,
        color = color,
        modifier = modifier,
    )
}

/** Donut progress ring with the percentage centered. */
@Composable
fun ProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = 92.dp,
    stroke: Dp = 9.dp,
    color: Color = Fiscal.Accent,
) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val strokePx = stroke.toPx()
            val arcSize = androidx.compose.ui.geometry.Size(this.size.width - strokePx, this.size.height - strokePx)
            val topLeft = Offset(strokePx / 2, strokePx / 2)
            drawArc(
                color = Color.White.copy(alpha = 0.09f),
                startAngle = 0f, sweepAngle = 360f, useCenter = false,
                style = Stroke(strokePx), topLeft = topLeft, size = arcSize,
            )
            drawArc(
                color = color,
                startAngle = -90f, sweepAngle = 360f * progress.coerceIn(0f, 1f), useCenter = false,
                style = Stroke(strokePx, cap = StrokeCap.Round), topLeft = topLeft, size = arcSize,
            )
        }
        Text(
            "${(progress * 100).toInt()}%",
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.W700,
            fontSize = 20.sp,
            color = Fiscal.TextPrimary,
        )
    }
}

/** Segmented control: card-colored track, active option filled accent with dark text. */
@Composable
fun <T> SegmentedPill(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: (T) -> String,
    sublabel: ((T) -> String)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Fiscal.Card)
            .border(1.dp, Fiscal.CardBorder, RoundedCornerShape(16.dp))
            .padding(6.dp),
    ) {
        for (opt in options) {
            val active = opt == selected
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (active) Fiscal.Accent else Color.Transparent)
                    .clickable { onSelect(opt) }
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    label(opt),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) Fiscal.OnAccent else Fiscal.TextSecondary,
                )
                sublabel?.let {
                    Text(
                        it(opt),
                        style = MaterialTheme.typography.labelSmall,
                        color = (if (active) Fiscal.OnAccent else Fiscal.TextSecondary).copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

/** Rounded progress bar, 6-10dp tall, on the faint track. */
@Composable
fun FiscalBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 8.dp,
    color: Color = Fiscal.Accent,
    gradient: Boolean = true,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(99.dp))
            .background(Fiscal.Track),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(height)
                .clip(RoundedCornerShape(99.dp))
                .background(
                    if (gradient) Brush.horizontalGradient(listOf(Fiscal.AccentDark, color))
                    else Brush.horizontalGradient(listOf(color, color))
                ),
        )
    }
}

/** 44dp rounded-square icon tile with a tinted background and geometric glyph. */
@Composable
fun GlyphTile(color: Color, glyph: Glyph, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) {
            when (glyph) {
                Glyph.CIRCLE -> drawCircle(color)
                Glyph.SQUARE -> drawRoundRect(color, cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
                Glyph.DIAMOND -> {
                    val p = androidx.compose.ui.graphics.Path().apply {
                        moveTo(size.width / 2, 0f)
                        lineTo(size.width, size.height / 2)
                        lineTo(size.width / 2, size.height)
                        lineTo(0f, size.height / 2)
                        close()
                    }
                    drawPath(p, color)
                }
                Glyph.TRIANGLE -> {
                    val p = androidx.compose.ui.graphics.Path().apply {
                        moveTo(size.width / 2, 0f)
                        lineTo(size.width, size.height)
                        lineTo(0f, size.height)
                        close()
                    }
                    drawPath(p, color)
                }
            }
        }
    }
}

enum class Glyph { CIRCLE, SQUARE, DIAMOND, TRIANGLE }
