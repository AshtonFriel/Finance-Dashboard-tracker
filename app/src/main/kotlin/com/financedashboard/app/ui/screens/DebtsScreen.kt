package com.financedashboard.app.ui.screens

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.financedashboard.app.AppViewModel
import com.financedashboard.app.DebtInput
import com.financedashboard.app.ui.charts.Series
import com.financedashboard.app.ui.charts.StackedAreaChart
import com.financedashboard.app.ui.charts.fullCurrency
import com.financedashboard.app.ui.theme.LocalChartColors
import com.financedashboard.core.engine.PayoffStrategy
import java.time.format.DateTimeFormatter

private val monthFmt = DateTimeFormatter.ofPattern("MMM yyyy")

@Composable
fun DebtsScreen(vm: AppViewModel) {
    val chart = LocalChartColors.current
    val debts by vm.debtInputs.collectAsState()
    val plan by vm.debtPlan.collectAsState()
    val baseline by vm.debtPlanBaseline.collectAsState()
    val comparison by vm.strategyComparison.collectAsState()
    val extra by vm.extraMonthly.collectAsState()
    val strategy by vm.strategy.collectAsState()
    var editing by remember { mutableStateOf<DebtInput?>(null) }
    var scheduleFor by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Debt payoff", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

        if (debts.isEmpty()) {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("No active debts found", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Import a Balances CSV and any account with a negative balance shows up here. " +
                            "You can also reclassify accounts in the Accounts tab.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = chart.secondaryInk,
                    )
                }
            }
            return
        }

        StatTile(
            label = "Total debt across ${debts.count { it.includeInPlan }} accounts",
            value = fullCurrency(-debts.filter { it.includeInPlan }.sumOf { it.balance }),
            accent = chart.seriesRed,
            modifier = Modifier.fillMaxWidth(),
        )

        // Per-debt cards with editable assumptions.
        for (d in debts) {
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(d.accountName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${fullCurrency(d.balance)} • ${d.aprPct}% APR • ${fullCurrency(d.minPayment)}/mo" +
                                if (!d.includeInPlan) " • excluded" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = chart.secondaryInk,
                        )
                    }
                    TextButton(onClick = { editing = d }) { Text("Edit") }
                }
            }
        }
        Text(
            "APRs and payments are assumptions — CSV exports don't include them. Tap Edit to set real values.",
            style = MaterialTheme.typography.labelSmall,
            color = chart.mutedInk,
        )

        SectionTitle("Strategy")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            PayoffStrategy.entries.forEachIndexed { i, s ->
                SegmentedButton(
                    selected = strategy == s,
                    onClick = { vm.strategy.value = s },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = PayoffStrategy.entries.size),
                ) {
                    Text(
                        when (s) {
                            PayoffStrategy.AVALANCHE -> "Avalanche"
                            PayoffStrategy.SNOWBALL -> "Snowball"
                            PayoffStrategy.PRO_RATA -> "Pro-rata"
                        }
                    )
                }
            }
        }

        Text("Extra payment: ${fullCurrency(extra)}/month", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = extra.toFloat(),
            onValueChange = { vm.extraMonthly.value = (it / 25).toInt() * 25.0 },
            valueRange = 0f..2000f,
        )

        // Delta banner vs. no-extra baseline.
        val p = plan
        val b = baseline
        if (p != null && b != null && extra > 0) {
            val monthsSaved = (b.combinedBalanceByMonth.size - p.combinedBalanceByMonth.size)
            val interestSaved = b.totalInterest - p.totalInterest
            Card {
                Text(
                    "Extra ${fullCurrency(extra)}/mo → debt-free $monthsSaved months sooner, " +
                        "saves ${fullCurrency(interestSaved)} in interest",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = chart.good,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        p?.let { activePlan ->
            SectionTitle("Combined payoff projection")
            Card {
                Column(Modifier.padding(12.dp)) {
                    // Per-debt stacked balances sampled from each debt's schedule.
                    val months = activePlan.combinedBalanceByMonth.map { it.first }
                    val included = activePlan.debts
                    val stackSeries = included.mapIndexed { i, dr ->
                        val byMonth = dr.schedule.associate { it.month to it.remainingBalance }
                        var last = dr.debt.balance
                        Series(
                            label = dr.debt.name.take(18),
                            values = months.map { m -> byMonth[m]?.also { last = it } ?: last.takeIf { m <= (dr.payoffMonth ?: m) } ?: 0.0 },
                            color = chart.categorical[i % chart.categorical.size],
                        )
                    }
                    val overlay = b?.takeIf { extra > 0 }?.let { basePlan ->
                        val baseByMonth = basePlan.combinedBalanceByMonth.toMap()
                        Series(
                            label = "Without extra",
                            values = months.map { baseByMonth[it] ?: 0.0 },
                            color = chart.mutedInk,
                            dashed = true,
                        )
                    }
                    StackedAreaChart(
                        series = stackSeries,
                        overlays = listOfNotNull(overlay),
                        xLabel = { i -> months.getOrNull(i)?.format(monthFmt) ?: "" },
                    )
                    activePlan.payoffMonth?.let {
                        Text(
                            "Debt-free ${it.format(monthFmt)} • total interest ${fullCurrency(activePlan.totalInterest)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = chart.secondaryInk,
                        )
                    }
                }
            }

            SectionTitle("Strategy comparison")
            Card {
                Column(Modifier.padding(12.dp)) {
                    val weights = listOf(1.1f, 1f, 0.9f, 1f)
                    TableRow(listOf("Strategy", "Debt-free", "Months", "Interest"), weights, emphasize = true)
                    HorizontalDivider(color = chart.gridline)
                    for (c in comparison) {
                        TableRow(
                            listOf(
                                c.strategy.name.lowercase().replaceFirstChar { it.uppercase() }.replace("_", "-"),
                                c.payoffMonth?.format(monthFmt) ?: "—",
                                c.monthsToPayoff.toString(),
                                fullCurrency(c.totalInterest),
                            ),
                            weights,
                        )
                    }
                }
            }

            SectionTitle("Amortization schedule")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (dr in activePlan.debts) {
                    FilterChip(
                        selected = scheduleFor == dr.debt.name,
                        onClick = { scheduleFor = if (scheduleFor == dr.debt.name) null else dr.debt.name },
                        label = { Text(dr.debt.name.take(20)) },
                    )
                }
            }
            activePlan.debts.firstOrNull { it.debt.name == scheduleFor }?.let { dr ->
                Card {
                    Column(Modifier.padding(12.dp)) {
                        val weights = listOf(1.1f, 1f, 0.9f, 0.9f, 1.1f)
                        TableRow(listOf("Month", "Payment", "Principal", "Interest", "Balance"), weights, emphasize = true)
                        HorizontalDivider(color = chart.gridline)
                        // Show every month for shorter schedules, quarterly for long ones.
                        val rows = if (dr.schedule.size > 48) dr.schedule.filterIndexed { i, _ -> i % 3 == 0 } else dr.schedule
                        for (row in rows) {
                            TableRow(
                                listOf(
                                    row.month.format(monthFmt),
                                    fullCurrency(row.payment),
                                    fullCurrency(row.principal),
                                    fullCurrency(row.interest),
                                    fullCurrency(row.remainingBalance),
                                ),
                                weights,
                            )
                        }
                        HorizontalDivider(color = chart.gridline)
                        TableRow(
                            listOf("Total", fullCurrency(dr.totalPaid), "", fullCurrency(dr.totalInterest), ""),
                            weights,
                            emphasize = true,
                        )
                    }
                }
            } ?: Text(
                "Select a debt above to see its full schedule.",
                style = MaterialTheme.typography.bodySmall,
                color = chart.mutedInk,
            )
        }
    }

    editing?.let { d ->
        NumberEntryDialog(
            title = d.accountName,
            fields = listOf(
                "APR %" to d.aprPct.toString(),
                "Monthly payment (\$)" to d.minPayment.toString(),
                "Include in plan (1 = yes, 0 = no)" to if (d.includeInPlan) "1" else "0",
            ),
            onConfirm = { (apr, payment, include) ->
                vm.setDebtAssumption(d.accountName, apr, payment, include >= 0.5)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}
