package com.financedashboard.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.unit.dp
import com.financedashboard.app.AppViewModel
import com.financedashboard.app.DebtInput
import com.financedashboard.app.ui.charts.Series
import com.financedashboard.app.ui.charts.StackedAreaChart
import com.financedashboard.app.ui.charts.compactCurrency
import com.financedashboard.app.ui.charts.fullCurrency
import com.financedashboard.app.ui.theme.Fiscal
import com.financedashboard.app.ui.theme.LocalChartColors
import com.financedashboard.core.engine.PayoffStrategy
import java.time.format.DateTimeFormatter

private val monthFmt = DateTimeFormatter.ofPattern("MMM yyyy")

private val strategyExplainer = mapOf(
    PayoffStrategy.AVALANCHE to "Avalanche targets your highest-interest debt first — the fastest way to minimize total interest paid.",
    PayoffStrategy.SNOWBALL to "Snowball clears your smallest balance first — slower on interest, but the quick wins keep you motivated.",
    PayoffStrategy.PRO_RATA to "Pro-rata spreads the extra across every debt in proportion to its balance.",
)

@Composable
fun DebtsScreen(vm: AppViewModel) {
    val chart = LocalChartColors.current
    val debts by vm.debtInputs.collectAsState()
    val extraction by vm.debtExtraction.collectAsState()
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
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Debt payoff", style = MaterialTheme.typography.headlineSmall, color = Fiscal.TextPrimary)
        Text(
            "${strategy.name.lowercase().replaceFirstChar { it.uppercase() }.replace("_", "-")} strategy",
            style = MaterialTheme.typography.bodySmall,
            color = Fiscal.TextSecondary,
        )

        if (debts.isEmpty()) {
            FiscalCard {
                Text("No active debts found", style = MaterialTheme.typography.titleMedium, color = Fiscal.TextPrimary)
                Text(
                    "Import a Balances CSV from More → Settings. Active liabilities are identified from the " +
                        "latest snapshot per account; closed and stale accounts are excluded automatically.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Fiscal.TextSecondary,
                )
            }
            return
        }

        val included = debts.filter { it.includeInPlan }
        val totalRemaining = included.sumOf { it.balance }
        val totalOriginal = included.sumOf { it.originalBalance }
        val paidPct = if (totalOriginal > 0.005) (1.0 - totalRemaining / totalOriginal).coerceIn(0.0, 1.0) else 0.0
        val p = plan?.plan
        val efPlan = plan?.ef
        val b = baseline
        val interestSaved = if (p != null && b != null) (b.totalInterest - p.totalInterest) else 0.0

        // Hero: total remaining + paid progress + payoff/interest tiles.
        HeroCard {
            Text("Total remaining", style = MaterialTheme.typography.bodySmall, color = Fiscal.TextSecondary)
            Text(fullCurrency(totalRemaining), style = MaterialTheme.typography.headlineMedium, color = Fiscal.TextPrimary)
            Spacer(Modifier.height(10.dp))
            FiscalBar(progress = paidPct.toFloat())
            Spacer(Modifier.height(6.dp))
            Row {
                Text(
                    "${(paidPct * 100).toInt()}% paid off",
                    style = MaterialTheme.typography.labelSmall,
                    color = Fiscal.TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${fullCurrency(totalOriginal)} borrowed",
                    style = MaterialTheme.typography.labelSmall,
                    color = Fiscal.TextSecondary,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HeroTile(Modifier.weight(1f), "Payoff date", p?.payoffMonth?.format(monthFmt) ?: "—")
                HeroTile(
                    Modifier.weight(1f), "Interest saved",
                    if (interestSaved > 0.5) compactCurrency(interestSaved) else "—",
                    valueColor = Fiscal.Accent,
                )
            }
        }

        // Strategy control + explainer.
        SegmentedPill(
            options = PayoffStrategy.entries.toList(),
            selected = strategy,
            onSelect = { vm.setStrategy(it) },
            label = { it.name.lowercase().replaceFirstChar { c -> c.uppercase() }.replace("_", "-") },
        )
        Text(
            strategyExplainer[strategy].orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = Fiscal.TextSecondary,
        )

        // Extra payment slider.
        FiscalCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Extra payment / month", style = MaterialTheme.typography.bodyMedium, color = Fiscal.TextSecondary, modifier = Modifier.weight(1f))
                Text(fullCurrency(extra), style = MaterialTheme.typography.titleLarge, color = Fiscal.Accent)
            }
            Slider(
                value = extra.toFloat(),
                onValueChange = { vm.setExtraMonthly((it / 25).toInt() * 25.0) },
                valueRange = 0f..2000f,
            )
        }

        // Emergency-fund-first sequencing.
        val efFirst by vm.efFirstInPayoff.collectAsState()
        val efMonths by vm.efTargetMonths.collectAsState()
        val avgExpenses by vm.avgMonthlyExpenses.collectAsState()
        val liquidCash by vm.liquidCash.collectAsState()
        FiscalCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = efFirst, onCheckedChange = { vm.setEfFirstInPayoff(it) })
                Column(Modifier.padding(start = 10.dp)) {
                    Text("Build emergency fund first", style = MaterialTheme.typography.labelLarge, color = Fiscal.TextPrimary)
                    Text(
                        "Extra payments fill a $efMonths-month cushion before attacking debt",
                        style = MaterialTheme.typography.labelSmall,
                        color = Fiscal.TextMuted,
                    )
                }
            }
            if (efFirst) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (m in listOf(3, 4, 5, 6)) {
                        FilterChip(
                            selected = efMonths == m,
                            onClick = { vm.setEfTargetMonths(m) },
                            label = { Text("$m mo") },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (avgExpenses > 0.005)
                        "Target ${fullCurrency(avgExpenses * efMonths)} ($efMonths × ${fullCurrency(avgExpenses)} avg monthly spending) · " +
                            "starting from ${fullCurrency(liquidCash)} cash"
                    else
                        "Import a Transactions CSV so average monthly spending (and the fund target) can be computed.",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (avgExpenses > 0.005) Fiscal.TextSecondary else Fiscal.Amber,
                )
            }
        }

        // Delta banner.
        if (p != null && b != null && (extra > 0 || efPlan != null)) {
            val monthsSaved = (b.combinedBalanceByMonth.size - p.combinedBalanceByMonth.size)
            FiscalCard {
                efPlan?.efFundedMonth?.let { funded ->
                    Text(
                        "${fullCurrency(efPlan.efTargetAmount)} emergency fund ready ${funded.format(monthFmt)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = Fiscal.Accent,
                    )
                }
                if (extra > 0) {
                    Text(
                        if (monthsSaved >= 0)
                            "Extra ${fullCurrency(extra)}/mo → debt-free $monthsSaved months sooner, " +
                                "saves ${fullCurrency(interestSaved)} in interest"
                        else
                            "Funding the cushion first delays payoff ${-monthsSaved} months and costs " +
                                "${fullCurrency(-interestSaved)} more interest — the price of safety",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (monthsSaved >= 0) Fiscal.Accent else Fiscal.Amber,
                    )
                }
            }
        }

        // Debt list with focus badge and per-debt progress.
        Eyebrow("Your debts")
        val focus = included.filter { it.balance > 0.005 }.let { open ->
            if (strategy == PayoffStrategy.SNOWBALL) open.minByOrNull { it.balance }
            else open.maxByOrNull { it.aprPct }
        }
        included.forEachIndexed { i, d ->
            DebtRow(
                d = d,
                color = chart.categorical[i % chart.categorical.size],
                focused = strategy != PayoffStrategy.PRO_RATA && d.accountName == focus?.accountName,
                onEdit = { editing = d },
            )
        }
        Text(
            "APRs and payments are assumptions until you set real values — tap a debt to edit.",
            style = MaterialTheme.typography.labelSmall,
            color = Fiscal.TextMuted,
        )

        val suspects = debts.filter { it.reviewNote != null }
        if (suspects.isNotEmpty()) {
            Eyebrow("Needs review")
            suspects.forEachIndexed { i, d ->
                DebtRow(
                    d = d,
                    color = Fiscal.Amber,
                    focused = false,
                    onEdit = { editing = d },
                )
            }
            Text(
                "Suspected duplicates are excluded by default so nothing is double-counted; " +
                    "card balances are included. Tap to change (include = 1/0).",
                style = MaterialTheme.typography.labelSmall,
                color = Fiscal.TextMuted,
            )
        }
        if (extraction.excluded.isNotEmpty()) {
            Text(
                "${extraction.excluded.size} closed accounts excluded: " +
                    extraction.excluded.joinToString { e ->
                        e.candidate.accountName.take(22) +
                            if (e.reason == com.financedashboard.core.classify.DebtExtractor.ExclusionReason.STALE) " (stale)" else " (paid off)"
                    },
                style = MaterialTheme.typography.labelSmall,
                color = Fiscal.TextMuted,
            )
        }

        // Monthly breakdown.
        FiscalCard {
            Eyebrow("Monthly breakdown")
            Spacer(Modifier.height(8.dp))
            Row {
                Text("Minimum payments", style = MaterialTheme.typography.bodySmall, color = Fiscal.TextSecondary, modifier = Modifier.weight(1f))
                Text(fullCurrency(included.sumOf { it.minPayment }), style = MaterialTheme.typography.labelLarge, color = Fiscal.TextPrimary)
            }
            Spacer(Modifier.height(6.dp))
            Row {
                Text("Extra toward focus", style = MaterialTheme.typography.bodySmall, color = Fiscal.TextSecondary, modifier = Modifier.weight(1f))
                Text(fullCurrency(extra), style = MaterialTheme.typography.labelLarge, color = Fiscal.Accent)
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Fiscal.Hairline)
            Row {
                Text("Total per month", style = MaterialTheme.typography.labelLarge, color = Fiscal.TextPrimary, modifier = Modifier.weight(1f))
                Text(
                    fullCurrency(included.sumOf { it.minPayment } + extra),
                    style = MaterialTheme.typography.titleMedium,
                    color = Fiscal.TextPrimary,
                )
            }
        }

        p?.let { activePlan ->
            Eyebrow("Combined payoff projection")
            FiscalCard {
                val months = activePlan.combinedBalanceByMonth.map { it.first }
                val stackSeries = activePlan.debts.mapIndexed { i, dr ->
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
                    Series("Without extra", months.map { baseByMonth[it] ?: 0.0 }, Fiscal.TextMuted, dashed = true)
                }
                val efOverlay = efPlan?.let { e ->
                    val efByMonth = e.efSeries.toMap()
                    Series("Emergency fund", months.map { efByMonth[it] ?: e.efTargetAmount }, Fiscal.Accent)
                }
                StackedAreaChart(
                    series = stackSeries,
                    overlays = listOfNotNull(overlay, efOverlay),
                    xLabel = { i -> months.getOrNull(i)?.format(monthFmt) ?: "" },
                )
                activePlan.payoffMonth?.let {
                    Text(
                        "Debt-free ${it.format(monthFmt)} · total interest ${fullCurrency(activePlan.totalInterest)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Fiscal.TextSecondary,
                    )
                }
            }

            Eyebrow("Strategy comparison")
            FiscalCard {
                val weights = listOf(1.1f, 1f, 0.9f, 1f)
                TableRow(listOf("Strategy", "Debt-free", "Months", "Interest"), weights, emphasize = true)
                HorizontalDivider(color = Fiscal.Hairline)
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

            Eyebrow("Amortization schedule")
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
                FiscalCard {
                    val weights = listOf(1.1f, 1f, 0.9f, 0.9f, 1.1f)
                    TableRow(listOf("Month", "Payment", "Principal", "Interest", "Balance"), weights, emphasize = true)
                    HorizontalDivider(color = Fiscal.Hairline)
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
                    HorizontalDivider(color = Fiscal.Hairline)
                    TableRow(
                        listOf("Total", fullCurrency(dr.totalPaid), "", fullCurrency(dr.totalInterest), ""),
                        weights,
                        emphasize = true,
                    )
                }
            } ?: Text(
                "Select a debt above to see its full schedule.",
                style = MaterialTheme.typography.bodySmall,
                color = Fiscal.TextMuted,
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

@Composable
private fun HeroTile(modifier: Modifier, label: String, value: String, valueColor: androidx.compose.ui.graphics.Color = Fiscal.TextPrimary) {
    Column(
        modifier
            .background(Fiscal.AccentTint, RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Eyebrow(label)
        Text(value, style = MaterialTheme.typography.titleMedium, color = valueColor)
    }
}

@Composable
private fun DebtRow(d: DebtInput, color: androidx.compose.ui.graphics.Color, focused: Boolean, onEdit: () -> Unit) {
    FiscalCard(onClick = onEdit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(color, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(
                d.accountName,
                style = MaterialTheme.typography.titleSmall,
                color = Fiscal.TextPrimary,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (focused) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .background(Fiscal.Accent, RoundedCornerShape(99.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        "FOCUS",
                        style = MaterialTheme.typography.labelSmall,
                        color = Fiscal.OnAccent,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Text(fullCurrency(d.balance), style = MaterialTheme.typography.titleSmall, color = Fiscal.TextPrimary)
        }
        Spacer(Modifier.height(10.dp))
        FiscalBar(progress = d.paidProgress.toFloat(), height = 6.dp, color = color, gradient = false)
        Spacer(Modifier.height(6.dp))
        Row {
            Text(
                "${d.aprPct}% APR · min ${fullCurrency(d.minPayment)}" +
                    if (!d.includeInPlan) " · excluded" else "",
                style = MaterialTheme.typography.labelSmall,
                color = Fiscal.TextMuted,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${(d.paidProgress * 100).toInt()}% paid",
                style = MaterialTheme.typography.labelSmall,
                color = Fiscal.TextMuted,
            )
        }
        d.reviewNote?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = Fiscal.Amber)
        }
    }
}
