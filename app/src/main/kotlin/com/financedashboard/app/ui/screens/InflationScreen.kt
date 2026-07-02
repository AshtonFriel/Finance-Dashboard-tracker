package com.financedashboard.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.financedashboard.app.AppViewModel
import com.financedashboard.app.ui.charts.LineChart
import com.financedashboard.app.ui.charts.Series
import com.financedashboard.app.ui.charts.fullCurrency
import com.financedashboard.app.ui.theme.Fiscal
import com.financedashboard.app.ui.theme.LocalChartColors

@Composable
fun InflationScreen(vm: AppViewModel) {
    val chart = LocalChartColors.current
    val impact by vm.inflationImpact.collectAsState()
    val purchasingPower by vm.purchasingPower.collectAsState()
    val source by vm.incomeSource.collectAsState()
    val manualIncome by vm.manualIncome.collectAsState()
    val avgMonthlyIncome by vm.avgMonthlyIncome.collectAsState()
    var showIncomeDialog by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Income vs inflation", style = MaterialTheme.typography.headlineSmall, color = Fiscal.TextPrimary)
        Text(
            "Are your raises keeping up?",
            style = MaterialTheme.typography.bodySmall,
            color = Fiscal.TextSecondary,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = source == AppViewModel.IncomeSource.MERGED,
                onClick = { vm.incomeSource.value = AppViewModel.IncomeSource.MERGED },
                label = { Text("Gross (entered)") },
            )
            FilterChip(
                selected = source == AppViewModel.IncomeSource.NET,
                onClick = { vm.incomeSource.value = AppViewModel.IncomeSource.NET },
                label = { Text("Net paychecks") },
            )
        }
        OutlinedButton(onClick = { showIncomeDialog = true }) {
            Text(if (manualIncome.isEmpty()) "Enter gross income by year" else "Edit gross income (${manualIncome.size} years)")
        }

        val imp = impact
        if (imp == null) {
            FiscalCard {
                Text("Not enough income data", style = MaterialTheme.typography.titleMedium, color = Fiscal.TextPrimary)
                Text(
                    "Import a Transactions CSV (paychecks are detected automatically) or enter gross annual " +
                        "income for at least two consecutive years.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Fiscal.TextSecondary,
                )
            }
        } else {
            val last = imp.years.last()

            // Hero: nominal vs real + dual-line chart.
            HeroCard {
                Row {
                    Column(Modifier.weight(1f)) {
                        Eyebrow("Nominal ${last.year}")
                        Text(fullCurrency(last.actualIncome), style = MaterialTheme.typography.titleLarge, color = Fiscal.TextPrimary)
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        Eyebrow("Real (${imp.baseYear} $)")
                        Text(fullCurrency(last.realIncome), style = MaterialTheme.typography.titleLarge, color = Fiscal.Amber)
                    }
                }
                Spacer(Modifier.height(12.dp))
                val forward by vm.forwardProjection.collectAsState()
                val labels = listOf(imp.baseYear) + imp.years.map { it.year } +
                    (forward?.years?.map { it.year } ?: emptyList())
                val nominal = listOf(imp.baseIncome) + imp.years.map { it.actualIncome }
                val real = listOf(imp.baseIncome) + imp.years.map { it.realIncome }
                // Dashed projection series overlap history exactly, then extend 5 years.
                val projNominal = forward?.let { nominal + it.years.map { y -> y.nominal } }
                val projReal = forward?.let { real + it.years.map { y -> y.real } }
                val allValues = (projNominal ?: nominal) + (projReal ?: real)
                LineChart(
                    series = listOfNotNull(
                        projNominal?.let { Series("Projected nominal", it, Fiscal.Accent.copy(alpha = 0.55f), dashed = true) },
                        projReal?.let { Series("Projected real", it, Fiscal.Amber.copy(alpha = 0.55f), dashed = true) },
                        Series("Nominal (what you're paid)", nominal, Fiscal.Accent),
                        Series("Real buying power", real, Fiscal.Amber),
                    ),
                    xLabel = { i -> labels.getOrNull(i)?.toString() ?: "" },
                    // Pin y-min just below the smallest value to amplify the divergence.
                    yMinOverride = allValues.min() * 0.97,
                )
                if (avgMonthlyIncome > 0.005) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Recent take-home: ~${fullCurrency(avgMonthlyIncome)}/month averaged over the " +
                            "last 6 full months of paycheck deposits.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Fiscal.TextSecondary,
                    )
                }
            }

            // Purchasing power lost.
            val lost = -imp.cumulativeNominalGap
            FiscalCard {
                Text(
                    "Purchasing power ${if (lost >= 0) "lost" else "gained"} since ${imp.baseYear}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Fiscal.TextSecondary,
                )
                Text(
                    fullCurrency(kotlin.math.abs(lost)),
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (lost >= 0) Fiscal.Coral else Fiscal.Accent,
                )
                Text(
                    "Cumulative gap between what you earned and what the same purchasing power " +
                        "would have required as prices rose.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Fiscal.TextSecondary,
                )
            }

            // Raises vs CPI.
            val raisePts = (last.actualIncome / imp.baseIncome - 1.0) * 100
            val cpiPts = (1.0 / imp.dollarValueAtEnd - 1.0) * 100
            val gapPts = raisePts - cpiPts
            FiscalCard {
                Eyebrow("Raises vs CPI since ${imp.baseYear}")
                Spacer(Modifier.height(10.dp))
                LabeledBar("Cumulative raises", "+${"%.0f".format(raisePts)}%", raisePts, Fiscal.Accent)
                Spacer(Modifier.height(8.dp))
                LabeledBar("Cumulative CPI", "+${"%.0f".format(cpiPts)}%", cpiPts, Fiscal.Amber)
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            if (gapPts < 0) Fiscal.CoralTintBg else Fiscal.AccentTint,
                            RoundedCornerShape(12.dp),
                        )
                        .padding(12.dp),
                ) {
                    Text(
                        if (gapPts < 0)
                            "Your raises trailed inflation by ${"%.0f".format(-gapPts)} points since ${imp.baseYear}. " +
                                "In real terms, you earn less than you did then."
                        else
                            "Good news — your raises outpaced inflation by ${"%.0f".format(gapPts)} points since ${imp.baseYear}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (gapPts < 0) Fiscal.CoralTintText else Fiscal.Accent,
                    )
                }
            }

            // Forward-looking projector: will next year's raise keep up?
            val forward2 by vm.forwardProjection.collectAsState()
            val futureRaise by vm.futureRaisePct.collectAsState()
            val futureInfl by vm.futureInflationPct.collectAsState()
            FiscalCard {
                Eyebrow("If this continues…")
                Spacer(Modifier.height(8.dp))
                Text(
                    "Future raises: ${"%.1f".format(futureRaise)}%/yr",
                    style = MaterialTheme.typography.labelLarge, color = Fiscal.TextPrimary,
                )
                androidx.compose.material3.Slider(
                    value = futureRaise.toFloat(),
                    onValueChange = { vm.setFutureRaisePct((it * 2).toInt() / 2.0) },
                    valueRange = 0f..10f,
                )
                Text(
                    "Future inflation: ${"%.1f".format(futureInfl)}%/yr",
                    style = MaterialTheme.typography.labelLarge, color = Fiscal.TextPrimary,
                )
                androidx.compose.material3.Slider(
                    value = futureInfl.toFloat(),
                    onValueChange = { vm.setFutureInflationPct((it * 2).toInt() / 2.0) },
                    valueRange = 0f..10f,
                )
                forward2?.let { f ->
                    val fiveYr = f.years.last()
                    Text(
                        if (f.realDeltaPctPerYear < 0)
                            "At ${"%.1f".format(futureRaise)}% raises vs ${"%.1f".format(futureInfl)}% inflation, that's a " +
                                "${"%.1f".format(-f.realDeltaPctPerYear)}% real pay cut per year — by ${fiveYr.year} your " +
                                "${fullCurrency(fiveYr.nominal)} salary buys only ${fullCurrency(fiveYr.real)} in ${imp.baseYear} dollars."
                        else
                            "At ${"%.1f".format(futureRaise)}% raises vs ${"%.1f".format(futureInfl)}% inflation you gain " +
                                "${"%.1f".format(f.realDeltaPctPerYear)}% real per year — by ${fiveYr.year} you'd earn " +
                                "${fullCurrency(fiveYr.real)} in ${imp.baseYear} dollars.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (f.realDeltaPctPerYear < 0) Fiscal.CoralTintText else Fiscal.Accent,
                    )
                }
            }

            // Personal inflation: your spending mix, your rate.
            val personal by vm.personalInflation.collectAsState()
            var editingCategory by remember { mutableStateOf<String?>(null) }
            personal?.takeIf { it.categories.isNotEmpty() }?.let { pi ->
                FiscalCard {
                    Eyebrow("Your personal inflation rate")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${"%.1f".format(pi.ratePct)}%",
                            style = MaterialTheme.typography.headlineMedium,
                            color = if (pi.ratePct > pi.headlinePct) Fiscal.Coral else Fiscal.Accent,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "headline CPI ${"%.1f".format(pi.headlinePct)}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = Fiscal.TextMuted,
                        )
                    }
                    Text(
                        "Weighted by your last 12 months of spending. Categories default to headline CPI — tap one to " +
                            "set the rate you actually experience (rent, groceries, and insurance often run hotter).",
                        style = MaterialTheme.typography.labelSmall,
                        color = Fiscal.TextMuted,
                    )
                    Spacer(Modifier.height(6.dp))
                    for (c in pi.categories.take(6)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .let { m -> m },
                        ) {
                            Text(
                                "${c.category} · ${(c.share * 100).toInt()}% of spend",
                                style = MaterialTheme.typography.bodySmall,
                                color = Fiscal.TextSecondary,
                                modifier = Modifier.weight(1f),
                            )
                            androidx.compose.material3.TextButton(onClick = { editingCategory = c.category }) {
                                Text("${"%.1f".format(c.ratePct)}%", color = Fiscal.TextPrimary)
                            }
                        }
                    }
                }
            }
            editingCategory?.let { cat ->
                NumberEntryDialog(
                    title = "Annual inflation for $cat",
                    fields = listOf("Rate %/yr" to ""),
                    onConfirm = { (rate) ->
                        if (rate in -20.0..50.0) vm.setCategoryRate(cat, rate)
                        editingCategory = null
                    },
                    onDismiss = { editingCategory = null },
                )
            }

            // Baseline year picker.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val allYears = listOf(imp.baseYear) + imp.years.map { it.year }
                for (y in allYears.dropLast(1)) {
                    FilterChip(
                        selected = imp.baseYear == y,
                        onClick = { vm.baseYearOverride.value = y },
                        label = { Text("Base $y") },
                    )
                }
            }

            // Purchasing power of a base-year dollar.
            Eyebrow("What a ${imp.baseYear} dollar buys now")
            FiscalCard {
                LineChart(
                    series = listOf(
                        Series("Value of ${imp.baseYear} $1.00", purchasingPower.map { it.second }, Fiscal.Amber),
                    ),
                    xLabel = { i -> purchasingPower.getOrNull(i)?.first?.toString() ?: "" },
                    yFormatter = { "$${"%.2f".format(it)}" },
                )
                Text(
                    "A ${imp.baseYear} dollar is worth $${"%.2f".format(imp.dollarValueAtEnd)} in ${last.year} dollars.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Fiscal.TextSecondary,
                )
            }

            // Year-by-year table.
            Eyebrow("Year-by-year breakdown")
            FiscalCard {
                val weights = listOf(0.7f, 1.2f, 1.2f, 1f)
                TableRow(listOf("Year", "Actual", "Needed", "Gap"), weights, emphasize = true)
                HorizontalDivider(color = chart.gridline)
                TableRow(
                    listOf(imp.baseYear.toString(), fullCurrency(imp.baseIncome), "baseline", "—"),
                    weights,
                )
                for (row in imp.years) {
                    TableRow(
                        listOf(
                            row.year.toString(),
                            fullCurrency(row.actualIncome),
                            fullCurrency(row.neededIncome),
                            signedCurrency(row.nominalGap),
                        ),
                        weights,
                        color = gapColor(row.nominalGap),
                    )
                }
                HorizontalDivider(color = chart.gridline)
                TableRow(
                    listOf("Total", "", "", signedCurrency(imp.cumulativeNominalGap)),
                    weights,
                    emphasize = true,
                    color = gapColor(imp.cumulativeNominalGap),
                )
                Text(
                    "To restore ${imp.baseYear} purchasing power you'd need ${fullCurrency(last.neededIncome)} today.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Fiscal.TextSecondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }

    var suspiciousEntry by remember { mutableStateOf<Pair<Int, Double>?>(null) }
    if (showIncomeDialog) {
        val incomeByYear by vm.incomeByYear.collectAsState()
        NumberEntryDialog(
            title = "Add gross income for a year",
            fields = listOf(
                "Year" to java.time.LocalDate.now().year.minus(1).toString(),
                "Gross income (\$)" to "",
            ),
            onConfirm = { (yearD, amount) ->
                val year = yearD.toInt()
                val currentYear = java.time.LocalDate.now().year
                if (year in 1950..currentYear && amount > 0 && amount < 10_000_000) {
                    // Flag entries that jump >50% vs an adjacent year — usually a typo.
                    val neighbors = listOfNotNull(incomeByYear[year - 1], incomeByYear[year + 1])
                    val suspicious = neighbors.any { n ->
                        n > 0 && (amount / n > 1.5 || amount / n < 1.0 / 1.5)
                    }
                    if (suspicious) suspiciousEntry = year to amount
                    else vm.setManualIncome(year, amount)
                    showIncomeDialog = false
                }
            },
            onDismiss = { showIncomeDialog = false },
        )
    }
    suspiciousEntry?.let { (year, amount) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { suspiciousEntry = null },
            title = { Text("Double-check this entry?") },
            text = {
                Text(
                    "${fullCurrency(amount)} for $year is more than 50% different from the adjacent year — " +
                        "that's usually a typo. Save it anyway?",
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    vm.setManualIncome(year, amount)
                    suspiciousEntry = null
                }) { Text("Save anyway") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { suspiciousEntry = null }) { Text("Discard") }
            },
        )
    }
}

@Composable
private fun LabeledBar(label: String, value: String, points: Double, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Fiscal.TextSecondary, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelLarge, color = color)
    }
    Spacer(Modifier.height(4.dp))
    // Scaled so 40 points fills the bar, per the design.
    FiscalBar(progress = (points / 40.0).toFloat().coerceIn(0.02f, 1f), height = 8.dp, color = color, gradient = false)
}
