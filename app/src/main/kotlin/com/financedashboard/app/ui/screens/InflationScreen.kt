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
                val labels = listOf(imp.baseYear) + imp.years.map { it.year }
                val nominal = listOf(imp.baseIncome) + imp.years.map { it.actualIncome }
                val real = listOf(imp.baseIncome) + imp.years.map { it.realIncome }
                LineChart(
                    series = listOf(
                        Series("Nominal (what you're paid)", nominal, Fiscal.Accent),
                        Series("Real buying power", real, Fiscal.Amber),
                    ),
                    xLabel = { i -> labels.getOrNull(i)?.toString() ?: "" },
                    // Pin y-min just below the smallest value to amplify the divergence.
                    yMinOverride = minOf(nominal.min(), real.min()) * 0.97,
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

    if (showIncomeDialog) {
        NumberEntryDialog(
            title = "Add gross income for a year",
            fields = listOf(
                "Year" to java.time.LocalDate.now().year.minus(1).toString(),
                "Gross income (\$)" to "",
            ),
            onConfirm = { (year, amount) ->
                vm.setManualIncome(year.toInt(), amount)
                showIncomeDialog = false
            },
            onDismiss = { showIncomeDialog = false },
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
