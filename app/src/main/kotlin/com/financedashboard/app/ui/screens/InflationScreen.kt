package com.financedashboard.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.financedashboard.app.AppViewModel
import com.financedashboard.app.ui.charts.LineChart
import com.financedashboard.app.ui.charts.Series
import com.financedashboard.app.ui.charts.fullCurrency
import com.financedashboard.app.ui.theme.LocalChartColors

@Composable
fun InflationScreen(vm: AppViewModel) {
    val chart = LocalChartColors.current
    val impact by vm.inflationImpact.collectAsState()
    val purchasingPower by vm.purchasingPower.collectAsState()
    val source by vm.incomeSource.collectAsState()
    val manualIncome by vm.manualIncome.collectAsState()
    var showIncomeDialog by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Inflation impact", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

        // Income source + baseline controls in one row, above the charts.
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
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Not enough income data", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Import a Transactions CSV (paychecks are detected automatically) or enter gross annual " +
                            "income for at least two consecutive years to see your purchasing-power analysis.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = chart.secondaryInk,
                    )
                }
            }
        } else {
            val lost = -imp.cumulativeNominalGap
            StatTile(
                label = "Purchasing power ${if (lost >= 0) "lost" else "gained"} since ${imp.baseYear}",
                value = fullCurrency(kotlin.math.abs(lost)),
                accent = if (lost >= 0) chart.critical else chart.good,
                sublabel = "Cumulative gap between actual income and income keeping pace with CPI",
                modifier = Modifier.fillMaxWidth(),
            )

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

            SectionTitle("Actual vs. inflation-adjusted income")
            Card {
                Column(Modifier.padding(12.dp)) {
                    val labels = listOf(imp.baseYear) + imp.years.map { it.year }
                    val actual = listOf(imp.baseIncome) + imp.years.map { it.actualIncome }
                    val needed = listOf(imp.baseIncome) + imp.years.map { it.neededIncome }
                    LineChart(
                        series = listOf(
                            Series("Actual income", actual, chart.seriesBlue),
                            Series("Needed to keep pace", needed, chart.mutedInk, dashed = true),
                        ),
                        xLabel = { i -> labels.getOrNull(i)?.toString() ?: "" },
                        shadeBetween = 0 to 1,
                        shadeColor = chart.critical,
                    )
                }
            }

            SectionTitle("What a ${imp.baseYear} dollar buys now")
            Card {
                Column(Modifier.padding(12.dp)) {
                    LineChart(
                        series = listOf(
                            Series(
                                "Value of ${imp.baseYear} $1.00",
                                purchasingPower.map { it.second },
                                chart.seriesViolet,
                            ),
                        ),
                        xLabel = { i -> purchasingPower.getOrNull(i)?.first?.toString() ?: "" },
                        yFormatter = { "$${"%.2f".format(it)}" },
                    )
                    Text(
                        "A ${imp.baseYear} dollar is worth $${"%.2f".format(imp.dollarValueAtEnd)} in ${imp.years.last().year} dollars.",
                        style = MaterialTheme.typography.bodySmall,
                        color = chart.secondaryInk,
                    )
                }
            }

            SectionTitle("Year-by-year breakdown")
            Card {
                Column(Modifier.padding(12.dp)) {
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
                    val last = imp.years.last()
                    Text(
                        "In ${imp.baseYear} dollars, ${last.year} income is ${fullCurrency(last.realIncome)} " +
                            "(${signedCurrency(last.realGap)} vs. ${imp.baseYear}). To restore ${imp.baseYear} purchasing " +
                            "power you'd need ${fullCurrency(last.neededIncome)}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = chart.secondaryInk,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }

    if (showIncomeDialog) {
        ManualIncomeDialog(vm) { showIncomeDialog = false }
    }
}

@Composable
private fun ManualIncomeDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    NumberEntryDialog(
        title = "Add gross income for a year",
        fields = listOf(
            "Year" to java.time.LocalDate.now().year.minus(1).toString(),
            "Gross income (\$)" to "",
        ),
        onConfirm = { (year, amount) ->
            vm.setManualIncome(year.toInt(), amount)
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}
