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
    val savedScenarios by vm.savedScenarios.collectAsState()
    val compareName by vm.compareScenario.collectAsState()
    val comparisonPlan by vm.comparisonPlan.collectAsState()
    var editing by remember { mutableStateOf<DebtInput?>(null) }
    var scheduleFor by remember { mutableStateOf<String?>(null) }
    var showSaveScenario by remember { mutableStateOf(false) }

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

        // Rates-confirmation banner: projections are only as good as the APRs.
        val unconfirmed by vm.unconfirmedRates.collectAsState()
        if (unconfirmed.isNotEmpty()) {
            FiscalCard {
                Text(
                    "⚠ ${unconfirmed.size} ${if (unconfirmed.size == 1) "debt is" else "debts are"} using guessed rates",
                    style = MaterialTheme.typography.labelLarge,
                    color = Fiscal.Amber,
                )
                Text(
                    "Payoff dates and interest are estimates until you tap each debt below and enter its real APR and payment.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Fiscal.TextSecondary,
                )
            }
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
            val growth by vm.extraGrowthPct.collectAsState()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Grows ${"%.1f".format(growth)}%/yr with raises",
                    style = MaterialTheme.typography.labelSmall,
                    color = Fiscal.TextMuted,
                    modifier = Modifier.weight(1f),
                )
            }
            Slider(
                value = growth.toFloat(),
                onValueChange = { vm.setExtraGrowthPct((it * 2).toInt() / 2.0) },
                valueRange = 0f..10f,
            )
        }

        // One-time lump sums pinned to a month.
        val lumps by vm.lumpSums.collectAsState()
        var showLumpDialog by remember { mutableStateOf(false) }
        FiscalCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Lump-sum payments", style = MaterialTheme.typography.labelLarge, color = Fiscal.TextPrimary, modifier = Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = { showLumpDialog = true }) { Text("Add") }
            }
            if (lumps.isEmpty()) {
                Text(
                    "Tax refund or bonus coming? Pin it to a month and see the payoff date move.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Fiscal.TextMuted,
                )
            }
            for ((month, amount) in lumps.toSortedMap()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${month.format(monthFmt)} — ${fullCurrency(amount)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Fiscal.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.TextButton(onClick = { vm.removeLumpSum(month) }) {
                        Text("Remove", color = Fiscal.Coral)
                    }
                }
            }
        }
        if (showLumpDialog) {
            val now = java.time.YearMonth.now()
            NumberEntryDialog(
                title = "Add lump-sum payment",
                fields = listOf(
                    "Year" to now.year.toString(),
                    "Month (1-12)" to now.monthValue.toString(),
                    "Amount (\$)" to "",
                ),
                onConfirm = { (year, month, amount) ->
                    val m = month.toInt().coerceIn(1, 12)
                    val y = year.toInt()
                    if (y in now.year..now.year + 50 && amount > 0) {
                        vm.addLumpSum(java.time.YearMonth.of(y, m), amount)
                    }
                    showLumpDialog = false
                },
                onDismiss = { showLumpDialog = false },
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
        val order by vm.customOrder.collectAsState()
        val simulated by vm.simulatePaidOff.collectAsState()
        val effectiveRank = order.withIndex().associate { (i, n) -> n to i }
        val listed = if (strategy == PayoffStrategy.CUSTOM) {
            included.sortedWith(compareBy({ effectiveRank[it.accountName] ?: Int.MAX_VALUE }, { -it.aprPct }))
        } else included
        val focus = listed.filter { it.balance > 0.005 && it.accountName !in simulated }.let { open ->
            when (strategy) {
                PayoffStrategy.SNOWBALL -> open.minByOrNull { it.balance }
                PayoffStrategy.CUSTOM -> open.firstOrNull()
                else -> open.maxByOrNull { it.aprPct }
            }
        }
        listed.forEachIndexed { i, d ->
            DebtRow(
                d = d,
                color = chart.categorical[i % chart.categorical.size],
                focused = strategy != PayoffStrategy.PRO_RATA && d.accountName == focus?.accountName,
                onEdit = { editing = d },
                simulatedPaidOff = d.accountName in simulated,
                onToggleSimulate = { vm.toggleSimulatePaidOff(d.accountName) },
                onMove = if (strategy == PayoffStrategy.CUSTOM) { up -> vm.moveInCustomOrder(d.accountName, up) } else null,
            )
        }
        if (simulated.isNotEmpty()) {
            Text(
                "What-if active: ${simulated.joinToString { it.take(20) }} treated as paid off today; " +
                    "freed minimums roll into the extra budget. This is a simulation — tap again to undo.",
                style = MaterialTheme.typography.labelSmall,
                color = Fiscal.Amber,
            )
        }
        if (strategy == PayoffStrategy.CUSTOM) {
            Text(
                "Custom order: extra goes to the top debt first — use the arrows to reorder.",
                style = MaterialTheme.typography.labelSmall,
                color = Fiscal.TextMuted,
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
                val comparison = comparisonPlan?.let { cp ->
                    val cpByMonth = cp.combinedBalanceByMonth.toMap()
                    Series("Saved: ${compareName.orEmpty()}", months.map { cpByMonth[it] ?: 0.0 }, Fiscal.Sky, dashed = true)
                }
                StackedAreaChart(
                    series = stackSeries,
                    overlays = listOfNotNull(overlay, efOverlay, comparison),
                    xLabel = { i -> months.getOrNull(i)?.format(monthFmt) ?: "" },
                )
                comparisonPlan?.let { cp ->
                    val monthsDelta = cp.combinedBalanceByMonth.size - activePlan.combinedBalanceByMonth.size
                    val interestDelta = cp.totalInterest - activePlan.totalInterest
                    Text(
                        "vs saved “${compareName}”: current plan is " +
                            (if (monthsDelta >= 0) "${monthsDelta} months faster, ${fullCurrency(interestDelta)} less interest"
                            else "${-monthsDelta} months slower, ${fullCurrency(-interestDelta)} more interest"),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (monthsDelta >= 0) Fiscal.Accent else Fiscal.Amber,
                    )
                }
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

            Eyebrow("Scenarios")
            FiscalCard {
                Text(
                    "Save the current strategy, extra payment, growth, and lump sums under a name, then overlay it " +
                        "on the chart to compare against your live settings.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Fiscal.TextMuted,
                )
                Spacer(Modifier.height(6.dp))
                androidx.compose.material3.TextButton(onClick = { showSaveScenario = true }) {
                    Text("Save current as scenario", color = Fiscal.Accent)
                }
                for (sc in savedScenarios) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(sc.name, style = MaterialTheme.typography.bodyMedium, color = Fiscal.TextPrimary)
                            Text(
                                "${sc.strategy.lowercase().replaceFirstChar { it.uppercase() }} · +${fullCurrency(sc.extra)}/mo" +
                                    (if (sc.growthPct > 0) " · grows ${sc.growthPct}%/yr" else "") +
                                    (if (sc.lumpSums.isNotEmpty()) " · ${sc.lumpSums.size} lump" else ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = Fiscal.TextMuted,
                            )
                        }
                        androidx.compose.material3.TextButton(onClick = { vm.setCompareScenario(sc.name) }) {
                            Text(if (compareName == sc.name) "Hide" else "Compare", color = Fiscal.Sky)
                        }
                        androidx.compose.material3.TextButton(onClick = { vm.deleteScenario(sc.name) }) {
                            Text("✕", color = Fiscal.TextMuted)
                        }
                    }
                    // Diff table: what changed and the resulting delta.
                    if (compareName == sc.name && comparisonPlan != null) {
                        val cp = comparisonPlan!!
                        HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Fiscal.Hairline)
                        val w = listOf(1.3f, 1f, 1f)
                        TableRow(listOf("Assumption", "Saved", "Current"), w, emphasize = true)
                        DiffRow("Strategy", sc.strategy.lowercase().replaceFirstChar { it.uppercase() }, strategy.name.lowercase().replaceFirstChar { it.uppercase() }, w)
                        DiffRow("Extra/mo", fullCurrency(sc.extra), fullCurrency(extra), w)
                        DiffRow("Growth", "${sc.growthPct}%", "${vm.extraGrowthPct.value}%", w)
                        DiffRow("Lump sums", "${sc.lumpSums.size}", "${vm.lumpSums.value.size}", w)
                        HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Fiscal.Hairline)
                        val monthsDelta = cp.combinedBalanceByMonth.size - activePlan.combinedBalanceByMonth.size
                        val interestDelta = cp.totalInterest - activePlan.totalInterest
                        TableRow(listOf("Payoff", cp.payoffMonth?.format(monthFmt) ?: "—", activePlan.payoffMonth?.format(monthFmt) ?: "—"), w)
                        TableRow(listOf("Total interest", fullCurrency(cp.totalInterest), fullCurrency(activePlan.totalInterest)), w)
                        Text(
                            "Current plan is " + (if (monthsDelta >= 0) "${monthsDelta} months faster, ${fullCurrency(interestDelta)} less interest" else "${-monthsDelta} months slower, ${fullCurrency(-interestDelta)} more interest"),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (monthsDelta >= 0) Fiscal.Accent else Fiscal.Amber,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
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
                val context = androidx.compose.ui.platform.LocalContext.current
                FiscalCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        androidx.compose.material3.TextButton(onClick = {
                            com.financedashboard.app.data.TableExporter.shareCsv(
                                context,
                                "amortization-${dr.debt.name.filter { it.isLetterOrDigit() }.take(24)}.csv",
                                header = listOf("Month", "Payment", "Principal", "Interest", "Balance"),
                                rows = dr.schedule.map {
                                    listOf(
                                        it.month.toString(),
                                        "%.2f".format(it.payment),
                                        "%.2f".format(it.principal),
                                        "%.2f".format(it.interest),
                                        "%.2f".format(it.remainingBalance),
                                    )
                                },
                            )
                        }) { Text("Export CSV", color = Fiscal.Accent, style = MaterialTheme.typography.labelSmall) }
                    }
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
        val inferred by vm.inferredPayments.collectAsState()
        val suggestion = inferred[d.accountName]
        NumberEntryDialog(
            title = d.accountName,
            fields = listOf(
                "APR %" to d.aprPct.toString(),
                "Monthly payment (\$)" to (suggestion?.monthlyPayment ?: d.minPayment).let { "%.0f".format(it) },
                "Include in plan (1 = yes, 0 = no)" to if (d.includeInPlan) "1" else "0",
            ),
            note = suggestion?.let {
                "Suggested payment ${fullCurrency(it.monthlyPayment)}/mo from ${it.monthsObserved} months of your payment history — edit if it isn't your real minimum."
            },
            onConfirm = { (apr, payment, include) ->
                vm.setDebtAssumption(d.accountName, apr, payment, include >= 0.5)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }

    if (showSaveScenario) {
        var scenarioName by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showSaveScenario = false },
            title = { Text("Save scenario") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = scenarioName,
                    onValueChange = { scenarioName = it },
                    label = { Text("Name (e.g. Aggressive +\$500)") },
                    singleLine = true,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    if (scenarioName.isNotBlank()) { vm.saveCurrentScenario(scenarioName); showSaveScenario = false }
                }) { Text("Save") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showSaveScenario = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun DiffRow(label: String, saved: String, current: String, weights: List<Float>) {
    val changed = saved != current
    TableRow(
        listOf(label, saved, current + if (changed) "  ●" else ""),
        weights,
        color = if (changed) Fiscal.Amber else Fiscal.TextSecondary,
    )
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
private fun DebtRow(
    d: DebtInput,
    color: androidx.compose.ui.graphics.Color,
    focused: Boolean,
    onEdit: () -> Unit,
    simulatedPaidOff: Boolean = false,
    onToggleSimulate: (() -> Unit)? = null,
    onMove: ((up: Boolean) -> Unit)? = null,
) {
    FiscalCard(onClick = onEdit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(color, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(
                d.accountName,
                style = MaterialTheme.typography.titleSmall,
                color = if (simulatedPaidOff) Fiscal.TextMuted else Fiscal.TextPrimary,
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
        if (onToggleSimulate != null || onMove != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                onMove?.let { move ->
                    androidx.compose.material3.TextButton(onClick = { move(true) }) { Text("↑") }
                    androidx.compose.material3.TextButton(onClick = { move(false) }) { Text("↓") }
                }
                Spacer(Modifier.weight(1f))
                onToggleSimulate?.let { toggle ->
                    androidx.compose.material3.TextButton(onClick = toggle) {
                        Text(
                            if (simulatedPaidOff) "Undo what-if" else "What if paid off today?",
                            color = if (simulatedPaidOff) Fiscal.Amber else Fiscal.Accent,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}
