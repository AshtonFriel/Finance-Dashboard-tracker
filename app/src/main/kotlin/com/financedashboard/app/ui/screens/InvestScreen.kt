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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
                // Cumulative contributions, including any redirected debt budget, from the projection itself.
                val contributions = bnd.expected.years.runningFold(principal) { acc, row -> acc + row.contributions }
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
                onSelect = { vm.setExpectedReturnPct(it.ratePct) },
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
                    onValueChange = { vm.setMonthlyContribution((it / 50).toInt() * 50.0) },
                    valueRange = 0f..3000f,
                )
            }

            // Debt-budget redirect: freed payments roll into contributions at payoff.
            val redirectOn by vm.redirectDebtBudget.collectAsState()
            val redirect by vm.redirectInfo.collectAsState()
            val debts by vm.debtInputs.collectAsState()
            if (debts.any { it.includeInPlan }) {
                FiscalCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = redirectOn, onCheckedChange = { vm.setRedirectDebtBudget(it) })
                        Column(Modifier.padding(start = 10.dp)) {
                            Text("Redirect debt budget when debt-free", style = MaterialTheme.typography.labelLarge, color = Fiscal.TextPrimary)
                            Text(
                                redirect?.let {
                                    "+${fullCurrency(it.amount)}/mo added to contributions from " +
                                        java.time.YearMonth.now().plusMonths(it.fromMonth.toLong())
                                            .format(java.time.format.DateTimeFormatter.ofPattern("MMM yyyy"))
                                } ?: "Once your payoff plan completes, its full monthly budget keeps working here",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (redirect != null) Fiscal.Accent else Fiscal.TextMuted,
                            )
                        }
                    }
                }
            }

            // Sequence-of-returns stress test.
            val stressOn by vm.stressEnabled.collectAsState()
            val stressRate by vm.stressRatePct.collectAsState()
            FiscalCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = stressOn, onCheckedChange = { vm.setStressEnabled(it) })
                    Column(Modifier.padding(start = 10.dp)) {
                        Text("Bad-decade stress test", style = MaterialTheme.typography.labelLarge, color = Fiscal.TextPrimary)
                        Text(
                            if (stressOn) "First 10 years return ${"%.1f".format(stressRate)}% instead of the scenario rate"
                            else "See what a weak first decade does to the plan",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (stressOn) Fiscal.Amber else Fiscal.TextMuted,
                        )
                    }
                }
                if (stressOn) {
                    Slider(
                        value = stressRate.toFloat(),
                        onValueChange = { vm.setStressRatePct((it * 2).toInt() / 2.0) },
                        valueRange = -5f..6f,
                    )
                }
            }

            // Horizon + real toggle.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                for (y in listOf(5, 10, 20, 30)) {
                    FilterChip(
                        selected = horizon == y,
                        onClick = { vm.setHorizonYears(y) },
                        label = { Text("${y}y") },
                    )
                }
                Spacer(Modifier.weight(1f))
                Switch(checked = showReal, onCheckedChange = { vm.setShowReal(it) })
            }
            Text(
                if (showReal) "Showing today's dollars (deflated ${"%.1f".format(inflation)}%/yr)" else "Showing nominal dollars",
                style = MaterialTheme.typography.labelSmall,
                color = Fiscal.TextMuted,
            )
            if (showReal) {
                Slider(
                    value = inflation.toFloat(),
                    onValueChange = { vm.setAssumedInflationPct((it * 10).toInt() / 10.0) },
                    valueRange = 0f..8f,
                )
            }

            // Goals: multiple, user-defined; a $1M retirement goal is seeded by default.
            val goalProjections by vm.goalProjections.collectAsState()
            var showGoalDialog by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("Goals", modifier = Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = { showGoalDialog = true }) { Text("Add goal") }
            }
            val effectiveGoals = goalProjections.ifEmpty {
                // Default retirement goal until the user adds their own.
                val nominalAtHorizon = bnd.expected.years.last().endBalanceNominal
                listOf(
                    AppViewModel.GoalUi(
                        goal = com.financedashboard.app.data.SettingsStore.Goal("Retirement", RETIREMENT_GOAL, horizon),
                        projectedNominal = nominalAtHorizon,
                        progress = (nominalAtHorizon / RETIREMENT_GOAL).coerceIn(0.0, 1.0),
                        reachedYear = InvestmentEngine.milestoneYear(bnd.expected, RETIREMENT_GOAL),
                    )
                )
            }
            for (g in effectiveGoals) {
                FiscalCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(g.goal.name, style = MaterialTheme.typography.titleSmall, color = Fiscal.TextPrimary, modifier = Modifier.weight(1f))
                        Text(compactCurrency(g.goal.target), style = MaterialTheme.typography.titleMedium, color = Fiscal.TextPrimary)
                        if (goalProjections.isNotEmpty()) {
                            androidx.compose.material3.TextButton(onClick = { vm.removeGoal(g.goal.name) }) {
                                Text("✕", color = Fiscal.TextMuted)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    FiscalBar(progress = g.progress.toFloat(), height = 10.dp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Projected ${compactCurrency(g.projectedNominal)} in ${g.goal.years}y — ${(g.progress * 100).toInt()}% of goal" +
                            (g.reachedYear?.let { " · reached ${startYear + it}" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = Fiscal.TextSecondary,
                    )
                }
            }
            if (showGoalDialog) {
                var goalName by remember { mutableStateOf("") }
                var goalTarget by remember { mutableStateOf("") }
                var goalYears by remember { mutableStateOf(horizon.toString()) }
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showGoalDialog = false },
                    title = { Text("Add goal") },
                    text = {
                        Column {
                            androidx.compose.material3.OutlinedTextField(
                                value = goalName, onValueChange = { goalName = it },
                                label = { Text("Name (e.g. House down payment)") }, singleLine = true,
                            )
                            androidx.compose.material3.OutlinedTextField(
                                value = goalTarget, onValueChange = { goalTarget = it },
                                label = { Text("Target (\$)") }, singleLine = true,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                            androidx.compose.material3.OutlinedTextField(
                                value = goalYears, onValueChange = { goalYears = it },
                                label = { Text("Years from now") }, singleLine = true,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            val target = goalTarget.replace(",", "").replace("$", "").trim().toDoubleOrNull()
                            val years = goalYears.trim().toIntOrNull()
                            if (goalName.isNotBlank() && target != null && target > 0 && years != null && years in 1..60) {
                                vm.addGoal(goalName.trim(), target, years)
                                showGoalDialog = false
                            }
                        }) { Text("Save") }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { showGoalDialog = false }) { Text("Cancel") }
                    },
                )
            }

            // Where the money comes from.
            val nominalAtHorizon = bnd.expected.years.last().endBalanceNominal
            val totalContrib = principal + bnd.expected.years.sumOf { it.contributions }
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
            // FIRE / coast-FIRE.
            val fire by vm.fire.collectAsState()
            val retYears by vm.retirementYears.collectAsState()
            fire?.let { f ->
                Eyebrow("Financial independence")
                FiscalCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("FIRE number", style = MaterialTheme.typography.bodySmall, color = Fiscal.TextSecondary)
                            Text(compactCurrency(f.fireNumber), style = MaterialTheme.typography.titleLarge, color = Fiscal.TextPrimary)
                        }
                        Text("${f.progressPct.toInt()}%", style = MaterialTheme.typography.titleLarge, color = Fiscal.Accent)
                    }
                    Spacer(Modifier.height(8.dp))
                    FiscalBar(progress = (f.progressPct / 100).toFloat(), height = 10.dp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        buildString {
                            append("25× your ${compactCurrency(f.annualSpending)}/yr spending. ")
                            append(if (f.hasCoasted) "You've already coasted — no more contributions needed to hit it by retirement. "
                                   else "Coast number: ${compactCurrency(f.coastNumber)} (invest this once and stop). ")
                            f.yearsToFire?.let { append("On track in ~$it years at current saving.") }
                        },
                        style = MaterialTheme.typography.labelSmall, color = Fiscal.TextSecondary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Retire in", style = MaterialTheme.typography.labelSmall, color = Fiscal.TextMuted)
                        for (y in listOf(10, 15, 20, 25, 30)) {
                            FilterChip(selected = retYears == y, onClick = { vm.setRetirementYears(y) }, label = { Text("${y}y") })
                        }
                    }
                }
            }

            // Crypto cost basis.
            val lots by vm.cryptoLots.collectAsState()
            lots?.let { r ->
                Eyebrow("Crypto cost basis")
                FiscalCard {
                    Text(
                        "Est. cost basis ${fullCurrency(r.remainingCostBasis)} on ${"%.4f".format(r.remainingUnits)} units held",
                        style = MaterialTheme.typography.bodyMedium, color = Fiscal.TextPrimary,
                    )
                    if (r.realized.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text("Realized gains: ${signedCurrency(r.realizedGain)} across ${r.realized.size} sells",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (r.realizedGain >= 0) Fiscal.Accent else Fiscal.Coral)
                        for ((yr, g) in com.financedashboard.core.engine.CryptoLotEngine.realizedByYear(r).toSortedMap()) {
                            Text("  $yr: ${signedCurrency(g)}", style = MaterialTheme.typography.labelSmall, color = Fiscal.TextSecondary)
                        }
                    }
                    Text(
                        "Parsed from exchange transaction memos (FIFO). Only covers holdings whose buys carry per-unit " +
                            "detail — manually-tracked balances aren't included.",
                        style = MaterialTheme.typography.labelSmall, color = Fiscal.TextMuted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

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
            val exportContext = androidx.compose.ui.platform.LocalContext.current
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("Year-by-year (expected ${expectedReturn}%)", modifier = Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = {
                    com.financedashboard.app.data.TableExporter.shareCsv(
                        exportContext,
                        "investment-projection.csv",
                        header = listOf("Year", "Contributions", "Growth", "Nominal", "Real"),
                        rows = bnd.expected.years.map {
                            listOf(
                                (startYear + it.yearIndex).toString(),
                                "%.2f".format(it.contributions),
                                "%.2f".format(it.growth),
                                "%.2f".format(it.endBalanceNominal),
                                "%.2f".format(it.endBalanceReal),
                            )
                        },
                    )
                }) { Text("Export CSV", color = Fiscal.Accent, style = MaterialTheme.typography.labelSmall) }
            }
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
            val hasCrypto by vm.hasCryptoSleeve.collectAsState()
            Text(
                if (hasCrypto)
                    "Fixed-rate scenario models, not forecasts. Your crypto holdings are modeled as their own " +
                        "sleeve with a much wider band (±15% around the scenario rate) and receive no contributions."
                else
                    "Fixed-rate scenario models, not forecasts.",
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
