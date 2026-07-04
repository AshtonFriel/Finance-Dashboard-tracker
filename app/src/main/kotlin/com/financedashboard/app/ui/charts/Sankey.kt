package com.financedashboard.app.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.financedashboard.app.ui.theme.LocalChartColors

/**
 * A simple two-stage Sankey: income (left) flows to spending categories and
 * savings/debt (right), with ribbon widths proportional to amount. Compose
 * Canvas, no library.
 */
data class SankeyFlow(val label: String, val amount: Double, val color: Color)

@Composable
fun SankeyDiagram(
    incomeLabel: String,
    incomeAmount: Double,
    outflows: List<SankeyFlow>,
    modifier: Modifier = Modifier,
) {
    val chart = LocalChartColors.current
    val tm = rememberTextMeasurer()
    val total = outflows.sumOf { it.amount }.coerceAtLeast(1.0)

    Box(modifier.fillMaxWidth().height((40 + outflows.size * 34).dp)) {
        Canvas(Modifier.fillMaxWidth().height((40 + outflows.size * 34).dp)) {
            val leftX = 8.dp.toPx()
            val nodeW = 12.dp.toPx()
            val rightX = size.width - 150.dp.toPx()
            val h = size.height
            // Income node spans most of the height.
            val incomeTop = 4.dp.toPx()
            val incomeBottom = h - 4.dp.toPx()
            drawRoundRect(
                chart.seriesAqua,
                topLeft = Offset(leftX, incomeTop),
                size = Size(nodeW, incomeBottom - incomeTop),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx(), 3.dp.toPx()),
            )
            var srcY = incomeTop
            var dstY = 4.dp.toPx()
            val incomeHeight = incomeBottom - incomeTop
            for (f in outflows) {
                val frac = f.amount / total
                val ribbonH = (incomeHeight * frac).toFloat()
                val dstH = 26.dp.toPx()
                // Ribbon from income node to the category node.
                val path = Path().apply {
                    moveTo(leftX + nodeW, srcY)
                    val midX = (leftX + nodeW + rightX) / 2
                    cubicTo(midX, srcY, midX, dstY, rightX, dstY)
                    lineTo(rightX, dstY + dstH)
                    cubicTo(midX, dstY + dstH, midX, srcY + ribbonH, leftX + nodeW, srcY + ribbonH)
                    close()
                }
                drawPath(path, f.color.copy(alpha = 0.35f))
                drawRoundRect(
                    f.color,
                    topLeft = Offset(rightX, dstY),
                    size = Size(nodeW, dstH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                )
                drawText(
                    tm, "${f.label}  ${compactCurrency(f.amount)}",
                    topLeft = Offset(rightX + nodeW + 6.dp.toPx(), dstY + 4.dp.toPx()),
                    style = TextStyle(color = chart.primaryInk, fontSize = 11.sp),
                )
                srcY += ribbonH
                dstY += dstH + 8.dp.toPx()
            }
            drawText(
                tm, "$incomeLabel ${compactCurrency(incomeAmount)}",
                topLeft = Offset(leftX + nodeW + 4.dp.toPx(), incomeTop),
                style = TextStyle(color = chart.secondaryInk, fontSize = 11.sp),
            )
        }
    }
}
