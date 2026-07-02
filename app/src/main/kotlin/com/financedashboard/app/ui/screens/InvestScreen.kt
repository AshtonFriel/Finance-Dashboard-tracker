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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.financedashboard.app.AppViewModel
import com.financedashboard.app.ui.charts.LineChart
import com.financedashboard.app.ui.charts.Series
import com.financedashboard.app.ui.charts.compactCurrency
import com.financedashboard.app.ui.charts.fullCurrency
import com.financedashboard.app.ui.theme.Fiscal
import com.financedashboard.core.engine.InvestmentEngine
import java.time.LocalDate

private data class Scenario(val label: String, val ratePct: Double)

private val scenarios = listOf(
    Scenario("Conservative", 5.0),
    Scenario("Moderate", 7.0),
    Scenario("Aggressive", 10.0),
)

private const val RETIREMENT_GOAL = 1_000_000.0

@Composable
fun InvestScreen(vm: AppViewModel) {
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
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Investments", style = MaterialTheme.typography.headlineSmall, color = Fiscal.TextPrimary)
        Text(
            "Projected to retirement ($horizon yrs)",
            style = MaterialTheme.typography.bodySmall,
            color = Fiscal.TextSecondary,
        )

        if (holdings.isEmpty()) {
            FiscalCard {
                Text("No investment accounts found", style = MaterialTheme.typography.titleMedium, color = Fiscal.TextPrimary)
                Text(
                    "Import a Balances CSV, or reclassify an account as Investment under More → Accounts.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Fiscal.TextSecondary,
                )
            }
            return
        }

        val principal = holdings.sumOf { it.latestBalance }
        val startYear = LocalDate.now().year

        band?.let { bnd ->
            val projected = bnd.expected.years.last().let { if (showReal) it.endBalanceReal else it.endBalanceNominal }

            // Hero: today vs at horizon + chart.
            HeroCard {
                Row {
                    Column(Modifier.weight(1f)) {
                        Eyebrow("Today")
                        Text(fullCurrency(principal), style = MaterialTheme.typography.titleLarge, color = Fiscal.TextPrimary)
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        Eyebrow("In $horizon years")
                        Text(compactCurrency(projected), style = MaterialTheme.typography.titleLarge, color = Fiscal.Accent)
                    }
                }
                Spacer(Modifier.height(12.dp))
                fun values(p: InvestmentEngine.Projection) =
                    listOf(principal) + p.years.map { if (showReal) it.endBalanceReal else it.endBalanceNominal }
                val contributions = (0..horizon).map { y -> principal + contribution * 12 * y }
                LineChart(
                    series = listOf(
                        Series("Total balance", values(bnd.expected), Fiscal.Accent),
                        Series("Contributions", contributions, Fiscal.Sky),
                    ),
                    xLabel = { i -> (startYear + i).toString() },
                    yMinOverride = 0.0,
                    fillUnderIndex = 0,
                )
            }

            // Scenario control.
            SegmentedPill(
                options = scenarios,
                selected = scenarios.minByOrNull { kotlin.math.abs(it.ratePct - expectedReturn) }!!,
                onSelect = { vm.expectedReturnPct.value = it.ratePct },
                label = { it.label },
                sublabel = { "${it.ratePct.toInt()}%" },
            )

            // Contribution slider.
            FiscalCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Monthly contribution", style = MaterialTheme.typography.bodyMedium, color = Fiscal.TextSecondary, modifier = Modifier.weight(1f))
                    Text(fullCurrency(contribution), style = MaterialTheme.typography.titleLarge, color = Fiscal.Accent)
                }
                Slider(
                    value = contribution.toFloat(),
                    onValueChange = { vm.monthlyContribution.value = (it / 50).toInt() * 50.0 },
                    valueRange = 0f..3000f,
                )
            }

            // Horizon + real toggle.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                for (y in listOf(5, 10, 20, 30)) {
                    FilterChip(
                        selected = horizon == y,
                        onClick = { vm.horizonYears.value = y },
                        label = { Text("${y}y") },
                    )
                }
                Spacer(Modifier.weight(1f))
                Switch(checked = showReal, onCheckedChange = { vm.showReal.value = it })
            }
            Text(
                if (showReal) "Showing today's dollars (deflated ${"%.1f".format(inflation)}%/yr)" else "Showing nominal dollars",
                style = MaterialTheme.typography.labelSmall,
                color = Fiscal.TextMuted,
            )
            if (showReal) {
                Slider(
                    value = inflation.toFloat(),
                    onValueChange = { vm.assumedInflationPct.value = (it * 10).toInt() / 10.0 },
                    valueRange = 0f..8f,
                )
            }

            // Retirement goal.
            val nominalAtHorizon = bnd.expected.years.last().endBalanceNominal
            val goalPct = (nominalAtHorizon / RETIREMENT_GOAL).coerceIn(0.0, 1.0)
            FiscalCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Retirement goal", style = MaterialTheme.typography.titleSmall, color = Fiscal.TextPrimary, modifier = Modifier.weight(1f))
                    Text("$1.0M", style = MaterialTheme.typography.titleMedium, color = Fiscal.TextPrimary)
                }
                Spacer(Modifier.height(10.dp))
                FiscalBar(progress = goalPct.toFloat(), height = 10.dp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Projected to reach ${(nominalAtHorizon / RETIREMENT_GOAL * 100).toInt()}% of goal" +
                        (InvestmentEngine.milestoneYear(bnd.expected, RETIREMENT_GOAL)?.let { " · $1M in ${startYear + it}" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = Fiscal.TextSecondary,
                )
            }

            // Where the money comes from.
            val totalContrib = principal + contribution * 12 * horizon
            val growth = (nominalAtHorizon - totalContrib).coerceAtLeast(0.0)
            val contribShare = (totalContrib / nominalAtHorizon).coerceIn(0.0, 1.0)
            FiscalCard {
                Eyebrow("Where the money comes from")
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .clip(RoundedCornerShape(99.dp)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(contribShare.toFloat().coerceAtLeast(0.02f))
                            .height(14.dp)
                            .background(Fiscal.Sky),
                    )
                    Spacer(Modifier.width(2.dp))
                    Box(
                        Modifier
                            .weight(1f)
                            .height(14.dp)
                            .background(Fiscal.Accent),
                    )
                }
                Spacer(Modifier.height(10.dp))
                LegendRow(Fiscal.Sky, "You put in", fullCurrency(totalContrib))
                Spacer(Modifier.height(4.dp))
                LegendRow(Fiscal.Accent, "Compound growth", fullCurrency(growth), valueColor = Fiscal.Accent)
            }

            // Holdings.
            Eyebrow("Holdings")
            for (h in holdings) {
                FiscalCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(h.name, style = MaterialTheme.typography.titleSmall, color = Fiscal.TextPrimary)
                            Text(
                                h.latestDate?.let { "as of $it" } ?: "",
                                style = MaterialTheme.typography.labelSmall,
                                color = Fiscal.TextMuted,
                            )
                        }
                        Text(fullCurrency(h.latestBalance), style = MaterialTheme.typography.titleMedium, color = Fiscal.TextPrimary)
                    }
                }
            }

            // Year-by-year table.
            Eyebrow("Year-by-year (expected ${expectedReturn}%)")
            FiscalCard {
                val weights = listOf(0.7f, 1.1f, 1.1f, 1.2f, 1.2f)
                TableRow(listOf("Year", "Contrib", "Growth", "Nominal", "Real"), weights, emphasize = true)
                HorizontalDivider(color = Fiscal.Hairline)
                val rows = if (bnd.expected.years.size > 15) {
                    bnd.expected.years.filter { it.yearIndex % 2 == 0 || it.yearIndex == 1 }
                } else bnd.expected.years
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
            Text(
                "Fixed-rate scenario models, not forecasts. Volatile assets (crypto) swing far wider.",
                style = MaterialTheme.typography.labelSmall,
                color = Fiscal.TextMuted,
            )
        }
    }
}

@Composable
private fun LegendRow(
    color: androidx.compose.ui.graphics.Color,
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = Fiscal.TextPrimary,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = Fiscal.TextSecondary, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelLarge, color = valueColor)
    }
}
