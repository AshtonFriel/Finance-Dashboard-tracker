package com.financedashboard.app.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.financedashboard.app.ui.theme.LocalChartColors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/** One line/area series. Points share a common x index space across series. */
data class Series(
    val label: String,
    val values: List<Double>,
    val color: Color,
    val dashed: Boolean = false,
)

private fun niceTicks(min: Double, max: Double, count: Int = 4): List<Double> {
    if (max <= min) return listOf(min)
    val span = max - min
    val rawStep = span / count
    val mag = Math.pow(10.0, floor(Math.log10(rawStep)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= rawStep }
    val start = ceil(min / step) * step
    return generateSequence(start) { it + step }.takeWhile { it <= max + 1e-9 }.toList()
}

/**
 * Multi-series line chart with hairline grid, y-axis tick labels, an optional
 * shaded region between two series (index pair), and a scrub crosshair with a
 * value tooltip — the default interaction layer for line forms.
 */
@Composable
fun LineChart(
    series: List<Series>,
    xLabel: (Int) -> String,
    modifier: Modifier = Modifier,
    yFormatter: (Double) -> String = { compactCurrency(it) },
    shadeBetween: Pair<Int, Int>? = null,
    shadeColor: Color = Color.Unspecified,
    yMinOverride: Double? = null,
) {
    val chart = LocalChartColors.current
    val textMeasurer = rememberTextMeasurer()
    var scrubIndex by remember { mutableStateOf<Int?>(null) }
    val pointCount = series.maxOfOrNull { it.values.size } ?: 0
    if (pointCount == 0) return

    Column(modifier = modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                .pointerInput(pointCount) {
                    detectDragGestures(
                        onDragEnd = { scrubIndex = null },
                        onDragCancel = { scrubIndex = null },
                    ) { change, _ ->
                        val frac = (change.position.x / size.width).coerceIn(0f, 1f)
                        scrubIndex = (frac * (pointCount - 1)).roundToInt()
                    }
                }
                .pointerInput(pointCount) {
                    detectTapGestures(onTap = { pos ->
                        val frac = (pos.x / size.width).coerceIn(0f, 1f)
                        scrubIndex = if (scrubIndex == null) (frac * (pointCount - 1)).roundToInt() else null
                    })
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val allValues = series.flatMap { it.values }
                val yMinData = minOf(allValues.min(), yMinOverride ?: allValues.min())
                val yMin = if (yMinData > 0 && yMinData < allValues.max() * 0.5) 0.0 else yMinData
                val yMax = allValues.max()
                val ticks = niceTicks(yMin, yMax)
                val leftPad = 0f
                val plotW = size.width
                val plotH = size.height - 18.dp.toPx()

                fun yPos(v: Double): Float =
                    (plotH - ((v - yMin) / (yMax - yMin).coerceAtLeast(1e-9) * plotH)).toFloat()
                fun xPos(i: Int): Float =
                    leftPad + if (pointCount == 1) plotW / 2 else i.toFloat() / (pointCount - 1) * plotW

                // Hairline grid + tick labels (muted, recessive).
                for (t in ticks) {
                    val y = yPos(t)
                    drawLine(chart.gridline, Offset(leftPad, y), Offset(size.width, y), 1f)
                    drawText(
                        textMeasurer, yFormatter(t),
                        topLeft = Offset(4.dp.toPx(), y - 14.sp.toPx()),
                        style = TextStyle(color = chart.mutedInk, fontSize = 10.sp),
                    )
                }
                drawLine(chart.baseline, Offset(leftPad, plotH), Offset(size.width, plotH), 1.5f)

                // Shaded gap between two series (e.g. income shortfall).
                if (shadeBetween != null && shadeColor != Color.Unspecified) {
                    val (aIdx, bIdx) = shadeBetween
                    val a = series.getOrNull(aIdx)?.values ?: emptyList()
                    val b = series.getOrNull(bIdx)?.values ?: emptyList()
                    val n = minOf(a.size, b.size)
                    if (n > 1) {
                        val path = Path()
                        path.moveTo(xPos(0), yPos(a[0]))
                        for (i in 1 until n) path.lineTo(xPos(i), yPos(a[i]))
                        for (i in n - 1 downTo 0) path.lineTo(xPos(i), yPos(b[i]))
                        path.close()
                        drawPath(path, shadeColor.copy(alpha = 0.18f))
                    }
                }

                // Series lines: 2dp, dashed for reference series.
                for (s in series) {
                    if (s.values.size < 2) continue
                    val path = Path()
                    path.moveTo(xPos(0), yPos(s.values[0]))
                    for (i in 1 until s.values.size) path.lineTo(xPos(i), yPos(s.values[i]))
                    drawPath(
                        path, s.color,
                        style = Stroke(
                            width = 2.dp.toPx(),
                            pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(12f, 8f)) else null,
                        ),
                    )
                }

                // First/last x labels.
                drawText(
                    textMeasurer, xLabel(0),
                    topLeft = Offset(leftPad, plotH + 4.dp.toPx()),
                    style = TextStyle(color = chart.mutedInk, fontSize = 10.sp),
                )
                val lastLabel = xLabel(pointCount - 1)
                val measured = textMeasurer.measure(lastLabel, TextStyle(fontSize = 10.sp))
                drawText(
                    textMeasurer, lastLabel,
                    topLeft = Offset(size.width - measured.size.width, plotH + 4.dp.toPx()),
                    style = TextStyle(color = chart.mutedInk, fontSize = 10.sp),
                )

                // Scrub crosshair + markers.
                scrubIndex?.let { idx ->
                    val i = idx.coerceIn(0, pointCount - 1)
                    val x = xPos(i)
                    drawLine(chart.mutedInk, Offset(x, 0f), Offset(x, plotH), 1.5f)
                    for (s in series) {
                        s.values.getOrNull(i)?.let { v ->
                            drawCircle(chart.surface, 6.dp.toPx() / 2 + 2.dp.toPx(), Offset(x, yPos(v)))
                            drawCircle(s.color, 6.dp.toPx() / 2, Offset(x, yPos(v)))
                        }
                    }
                }
            }
            scrubIndex?.let { idx ->
                val i = idx.coerceIn(0, pointCount - 1)
                Surface(
                    modifier = Modifier
                        .align(if (i < pointCount / 2) Alignment.TopEnd else Alignment.TopStart)
                        .padding(8.dp),
                    shape = RoundedCornerShape(8.dp),
                    tonalElevation = 4.dp,
                    shadowElevation = 4.dp,
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(xLabel(i), style = MaterialTheme.typography.labelMedium, color = chart.secondaryInk)
                        for (s in series) {
                            s.values.getOrNull(i)?.let { v ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(8.dp).background(s.color, CircleShape))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "${s.label}: ${yFormatter(v)}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = chart.primaryInk,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        ChartLegend(series.map { it.label to it.color })
    }
}

/** Stacked area chart (e.g. combined debt balances declining to zero). */
@Composable
fun StackedAreaChart(
    series: List<Series>,
    xLabel: (Int) -> String,
    modifier: Modifier = Modifier,
    yFormatter: (Double) -> String = { compactCurrency(it) },
    overlays: List<Series> = emptyList(),
) {
    val chart = LocalChartColors.current
    val textMeasurer = rememberTextMeasurer()
    var scrubIndex by remember { mutableStateOf<Int?>(null) }
    val pointCount = series.maxOfOrNull { it.values.size } ?: 0
    if (pointCount == 0) return

    // Cumulative stacks.
    val stacked = remember(series) {
        val sums = MutableList(pointCount) { 0.0 }
        series.map { s ->
            val top = s.values.mapIndexed { i, v -> sums[i] + v }
            top.forEachIndexed { i, v -> sums[i] = v }
            s to top
        }
    }
    val stackMax = stacked.lastOrNull()?.second?.maxOrNull() ?: 0.0
    val overlayMax = overlays.flatMap { it.values }.maxOrNull() ?: 0.0
    val yMax = max(stackMax, overlayMax).coerceAtLeast(1e-9)

    Column(modifier = modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                .pointerInput(pointCount) {
                    detectDragGestures(
                        onDragEnd = { scrubIndex = null },
                        onDragCancel = { scrubIndex = null },
                    ) { change, _ ->
                        val frac = (change.position.x / size.width).coerceIn(0f, 1f)
                        scrubIndex = (frac * (pointCount - 1)).roundToInt()
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val plotH = size.height - 18.dp.toPx()
                fun yPos(v: Double): Float = (plotH - (v / yMax * plotH)).toFloat()
                fun xPos(i: Int): Float =
                    if (pointCount == 1) size.width / 2 else i.toFloat() / (pointCount - 1) * size.width

                for (t in niceTicks(0.0, yMax)) {
                    val y = yPos(t)
                    drawLine(chart.gridline, Offset(0f, y), Offset(size.width, y), 1f)
                    drawText(
                        textMeasurer, yFormatter(t),
                        topLeft = Offset(4.dp.toPx(), y - 14.sp.toPx()),
                        style = TextStyle(color = chart.mutedInk, fontSize = 10.sp),
                    )
                }

                var prevTop: List<Double> = List(pointCount) { 0.0 }
                for ((s, top) in stacked) {
                    val path = Path()
                    path.moveTo(xPos(0), yPos(top[0]))
                    for (i in 1 until top.size) path.lineTo(xPos(i), yPos(top[i]))
                    for (i in prevTop.indices.reversed()) path.lineTo(xPos(i), yPos(prevTop[i]))
                    path.close()
                    drawPath(path, s.color.copy(alpha = 0.75f))
                    // 2dp surface gap between stacked fills.
                    val edge = Path()
                    edge.moveTo(xPos(0), yPos(top[0]))
                    for (i in 1 until top.size) edge.lineTo(xPos(i), yPos(top[i]))
                    drawPath(edge, chart.surface, style = Stroke(2.dp.toPx()))
                    prevTop = top
                }

                for (o in overlays) {
                    if (o.values.size < 2) continue
                    val path = Path()
                    path.moveTo(xPos(0), yPos(o.values[0]))
                    for (i in 1 until o.values.size) path.lineTo(xPos(i), yPos(o.values[i]))
                    drawPath(
                        path, o.color,
                        style = Stroke(2.dp.toPx(), pathEffect = if (o.dashed) PathEffect.dashPathEffect(floatArrayOf(12f, 8f)) else null),
                    )
                }

                drawLine(chart.baseline, Offset(0f, plotH), Offset(size.width, plotH), 1.5f)
                drawText(
                    textMeasurer, xLabel(0),
                    topLeft = Offset(0f, plotH + 4.dp.toPx()),
                    style = TextStyle(color = chart.mutedInk, fontSize = 10.sp),
                )
                val lastLabel = xLabel(pointCount - 1)
                val measured = textMeasurer.measure(lastLabel, TextStyle(fontSize = 10.sp))
                drawText(
                    textMeasurer, lastLabel,
                    topLeft = Offset(size.width - measured.size.width, plotH + 4.dp.toPx()),
                    style = TextStyle(color = chart.mutedInk, fontSize = 10.sp),
                )

                scrubIndex?.let { idx ->
                    val x = xPos(idx.coerceIn(0, pointCount - 1))
                    drawLine(chart.mutedInk, Offset(x, 0f), Offset(x, plotH), 1.5f)
                }
            }
            scrubIndex?.let { idx ->
                val i = idx.coerceIn(0, pointCount - 1)
                Surface(
                    modifier = Modifier
                        .align(if (i < pointCount / 2) Alignment.TopEnd else Alignment.TopStart)
                        .padding(8.dp),
                    shape = RoundedCornerShape(8.dp),
                    tonalElevation = 4.dp,
                    shadowElevation = 4.dp,
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(xLabel(i), style = MaterialTheme.typography.labelMedium, color = chart.secondaryInk)
                        (series + overlays).forEach { s ->
                            s.values.getOrNull(i)?.let { v ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(8.dp).background(s.color, CircleShape))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "${s.label}: ${yFormatter(v)}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = chart.primaryInk,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        ChartLegend((series + overlays).map { it.label to it.color })
    }
}

/** Donut chart with 2dp surface gaps between segments and a legend. */
@Composable
fun DonutChart(
    slices: List<Triple<String, Double, Color>>,
    modifier: Modifier = Modifier,
    centerLabel: String = "",
    centerValue: String = "",
) {
    val chart = LocalChartColors.current
    val total = slices.sumOf { it.second }.coerceAtLeast(1e-9)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(140.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val strokeW = 22.dp.toPx()
                var start = -90f
                for ((_, value, color) in slices) {
                    val sweep = (value / total * 360.0).toFloat()
                    drawArc(
                        color = color,
                        startAngle = start,
                        sweepAngle = (sweep - 2f).coerceAtLeast(0.5f),
                        useCenter = false,
                        style = Stroke(strokeW),
                        topLeft = Offset(strokeW / 2, strokeW / 2),
                        size = androidx.compose.ui.geometry.Size(size.width - strokeW, size.height - strokeW),
                    )
                    start += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(centerValue, style = MaterialTheme.typography.titleMedium, color = chart.primaryInk)
                Text(centerLabel, style = MaterialTheme.typography.labelSmall, color = chart.mutedInk)
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((label, value, color) in slices) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(color, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "$label  ${compactCurrency(value)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = chart.secondaryInk,
                    )
                }
            }
        }
    }
}

@Composable
fun Sparkline(values: List<Double>, color: Color, modifier: Modifier = Modifier) {
    if (values.size < 2) { Box(modifier) ; return }
    Canvas(modifier) {
        val min = values.min()
        val max = values.max()
        val span = (max - min).coerceAtLeast(1e-9)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i.toFloat() / (values.size - 1) * size.width
            val y = (size.height - ((v - min) / span * size.height)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(1.5.dp.toPx()))
    }
}

@Composable
fun ChartLegend(entries: List<Pair<String, Color>>) {
    if (entries.size < 2) return
    val chart = LocalChartColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .wrapContentSize(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        for ((label, color) in entries) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(color, CircleShape))
                Spacer(Modifier.width(5.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, color = chart.secondaryInk)
            }
        }
    }
}

/** $12.3K / $1.2M style axis + tooltip formatting. */
fun compactCurrency(v: Double): String {
    val a = abs(v)
    val sign = if (v < 0) "-" else ""
    return when {
        a >= 1_000_000 -> "$sign$${"%.2f".format(a / 1_000_000)}M"
        a >= 10_000 -> "$sign$${"%.0f".format(a / 1_000)}K"
        a >= 1_000 -> "$sign$${"%.1f".format(a / 1_000)}K"
        else -> "$sign$${"%.0f".format(a)}"
    }
}

fun fullCurrency(v: Double): String {
    val sign = if (v < 0) "-" else ""
    return "$sign$${"%,.0f".format(abs(v))}"
}
