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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.financedashboard.app.AppViewModel
import com.financedashboard.app.ui.charts.LineChart
import com.financedashboard.app.ui.charts.Series
import com.financedashboard.app.ui.charts.fullCurrency
import com.financedashboard.app.ui.theme.LocalChartColors
import com.financedashboard.core.engine.InvestmentEngine
import java.time.LocalDate

@Composable
fun InvestScreen(vm: AppViewModel) {
    val chart = LocalChartColors.current
    val holdings by vm.investmentAccounts.collectAsState()
    val band by vm.investmentBand.collectAsState()
    val horizon by vm.horizonYears.collectAsState()
    val contribution by vm.monthlyContribution.collectAsState()
    val expectedReturn by vm.expectedReturnPct.collectAsState()
    val inflation by vm.assumedInflationPct.collectAsState()
    val showReal by vm.showReal.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Investments", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

        if (holdings.isEmpty()) {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("No investment accounts found", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Import a Balances CSV, or reclassify an account as Investment in the Accounts tab. " +
                            "Projections start from your combined investment balance.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = chart.secondaryInk,
                    )
                }
            }
            return
        }

        val principal = holdings.sumOf { it.latestBalance }
        StatTile(
            label = "Invested across ${holdings.size} accounts",
            value = fullCurrency(principal),
            accent = chart.seriesAqua,
            modifier = Modifier.fillMaxWidth(),
        )
        for (h in holdings) {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(h.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${fullCurrency(h.latestBalance)}${h.latestDate?.let { " • as of $it" } ?: ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = chart.secondaryInk,
                        )
                    }
                }
            }
        }

        SectionTitle("Assumptions")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (y in listOf(5, 10, 20, 30)) {
                FilterChip(
                    selected = horizon == y,
                    onClick = { vm.horizonYears.value = y },
                    label = { Text("${y}y") },
                )
            }
        }
        Text("Monthly contribution: ${fullCurrency(contribution)}", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = contribution.toFloat(),
            onValueChange = { vm.monthlyContribution.value = (it / 50).toInt() * 50.0 },
            valueRange = 0f..5000f,
        )
        Text("Expected return: ${"%.1f".format(expectedReturn)}%/yr", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = expectedReturn.toFloat(),
            onValueChange = { vm.expectedReturnPct.value = (it * 2).toInt() / 2.0 },
            valueRange = 1f..14f,
        )
        Text("Assumed inflation: ${"%.1f".format(inflation)}%/yr", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = inflation.toFloat(),
            onValueChange = { vm.assumedInflationPct.value = (it * 10).toInt() / 10.0 },
            valueRange = 0f..8f,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = showReal, onCheckedChange = { vm.showReal.value = it })
            Text(
                if (showReal) "Showing today's dollars (inflation-adjusted)" else "Showing nominal dollars",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        band?.let { b ->
            SectionTitle("Projection")
            Card {
                Column(Modifier.padding(12.dp)) {
                    fun values(p: InvestmentEngine.Projection) =
                        listOf(principal) + p.years.map { if (showReal) it.endBalanceReal else it.endBalanceNominal }
                    val startYear = LocalDate.now().year
                    LineChart(
                        series = listOf(
                            Series("Optimistic ${(expectedReturn + 3.0)}%", values(b.optimistic), chart.seriesAqua, dashed = true),
                            Series("Expected ${expectedReturn}%", values(b.expected), chart.seriesBlue),
                            Series("Pessimistic ${(expectedReturn - 4.0).coerceAtLeast(0.0)}%", values(b.pessimistic), chart.seriesYellow, dashed = true),
                        ),
                        xLabel = { i -> (startYear + i).toString() },
                        shadeBetween = 0 to 2,
                        shadeColor = chart.seriesBlue,
                        yMinOverride = 0.0,
                    )
                    val milestones = listOf(250_000.0, 500_000.0, 1_000_000.0).mapNotNull { target ->
                        InvestmentEngine.milestoneYear(b.expected, target)?.let { y ->
                            "${fullCurrency(target)} by ${startYear + y}"
                        }
                    }
                    if (milestones.isNotEmpty()) {
                        Text(
                            "Expected path milestones: ${milestones.joinToString(" • ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = chart.secondaryInk,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Text(
                        "Bands are simple fixed-rate scenarios, not guarantees. Volatile assets (crypto) swing far wider.",
                        style = MaterialTheme.typography.labelSmall,
                        color = chart.mutedInk,
                    )
                }
            }

            SectionTitle("Year-by-year (expected ${expectedReturn}%)")
            Card {
                Column(Modifier.padding(12.dp)) {
                    val weights = listOf(0.7f, 1.1f, 1.1f, 1.2f, 1.2f)
                    TableRow(listOf("Year", "Contrib", "Growth", "Nominal", "Real"), weights, emphasize = true)
                    HorizontalDivider(color = chart.gridline)
                    val startYear = LocalDate.now().year
                    val rows = if (b.expected.years.size > 15) {
                        b.expected.years.filter { it.yearIndex % 2 == 0 || it.yearIndex == 1 }
                    } else b.expected.years
                    for (row in rows) {
                        TableRow(
                            listOf(
                                (startYear + row.yearIndex).toString(),
                                fullCurrency(row.contributions),
                                fullCurrency(row.growth),
                                fullCurrency(row.endBalanceNominal),
                                fullCurrency(row.endBalanceReal),
                            ),
                            weights,
                        )
                    }
                }
            }
        }
    }
}
