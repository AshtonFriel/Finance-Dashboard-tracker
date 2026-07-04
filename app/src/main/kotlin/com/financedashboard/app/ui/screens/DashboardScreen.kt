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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.financedashboard.app.AppViewModel
import com.financedashboard.app.ui.charts.Series
import com.financedashboard.app.ui.charts.StackedAreaChart
import com.financedashboard.app.ui.charts.compactCurrency
import com.financedashboard.app.ui.charts.fullCurrency
import com.financedashboard.app.ui.theme.Fiscal
import com.financedashboard.app.ui.theme.LocalChartColors
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val monthFmt = DateTimeFormatter.ofPattern("MMM yy")
private val payoffFmt = DateTimeFormatter.ofPattern("MMM yyyy")

@Composable
fun DashboardScreen(vm: AppViewModel, navController: NavHostController) {
    val chart = LocalChartColors.current
    val netWorth by vm.netWorth.collectAsState()
    val debts by vm.debtInputs.collectAsState()
    val plan by vm.debtPlan.collectAsState()
    val baseline by vm.debtPlanBaseline.collectAsState()
    val impact by vm.inflationImpact.collectAsState()
    val band by vm.investmentBand.collectAsState()
    val holdings by vm.investmentAccounts.collectAsState()
    val extra by vm.extraMonthly.collectAsState()
    val contribution by vm.monthlyContribution.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header: greeting + avatar.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(greeting(), style = MaterialTheme.typography.bodySmall, color = Fiscal.TextSecondary)
                Text("Your money", style = MaterialTheme.typography.headlineSmall, color = Fiscal.TextPrimary)
            }
            Box(
                Modifier
                    .size(42.dp)
                    .background(
                        Brush.linearGradient(listOf(Fiscal.Accent, Fiscal.AccentDark)),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text("$", style = MaterialTheme.typography.titleMedium, color = Fiscal.OnAccent)
            }
        }

        val txCount by vm.transactionCount.collectAsState()
        if (netWorth.isEmpty() || txCount == 0) {
            OnboardingCard(vm, navController, hasBalances = netWorth.isNotEmpty(), hasTransactions = txCount > 0)
            if (netWorth.isEmpty()) return
        }

        // Hero: debt-free by.
        val included = debts.filter { it.includeInPlan }
        val totalDebt = included.sumOf { it.balance }
        val totalOriginal = included.sumOf { it.originalBalance }
        val paidPct = if (totalOriginal > 0.005) (1.0 - totalDebt / totalOriginal).coerceIn(0.0, 1.0) else 0.0
        val payoff = plan?.plan?.payoffMonth
        val interestSaved = if (baseline != null && plan != null) {
            (baseline!!.totalInterest - plan!!.plan.totalInterest).coerceAtLeast(0.0)
        } else 0.0

        if (included.isNotEmpty()) {
            HeroCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProgressRing(progress = paidPct.toFloat())
                    Spacer(Modifier.width(18.dp))
                    Column {
                        Eyebrow("Debt-free by", color = Fiscal.Accent)
                        Text(
                            payoff?.format(payoffFmt) ?: "—",
                            style = MaterialTheme.typography.headlineMedium,
                            color = Fiscal.TextPrimary,
                        )
                        payoff?.let {
                            val months = YearMonth.now().until(it, java.time.temporal.ChronoUnit.MONTHS)
                            val toGo = "${months / 12}y ${months % 12}m to go"
                            Text(
                                if (interestSaved > 0.5) "$toGo · ${compactCurrency(interestSaved)} saved in interest" else toGo,
                                style = MaterialTheme.typography.bodySmall,
                                color = Fiscal.TextSecondary,
                            )
                        }
                    }
                }
            }
        }

        Eyebrow("Your money, at a glance")

        // Three pillar cards.
        PillarCard(
            tile = { GlyphTile(Fiscal.Coral, Glyph.CIRCLE) },
            label = "Total debt",
            value = fullCurrency(totalDebt),
            stat = "${(paidPct * 100).toInt()}% paid ›",
            statColor = Fiscal.Accent,
            onClick = { navController.navigate("debts") },
        )
        val latestImpact = impact?.years?.lastOrNull()
        PillarCard(
            tile = { GlyphTile(Fiscal.Amber, Glyph.DIAMOND) },
            label = impact?.let { "Income (real, ${it.baseYear} $)" } ?: "Income vs inflation",
            value = latestImpact?.let { fullCurrency(it.realIncome) } ?: "Add income data",
            stat = latestImpact?.let {
                val pts = ((it.actualIncome / (impact!!.baseIncome) - 1) - (1 / impact!!.dollarValueAtEnd - 1)) * 100
                "${if (pts >= 0) "+" else ""}${"%.0f".format(pts)} vs CPI ›"
            } ?: "›",
            statColor = Fiscal.Amber,
            onClick = { navController.navigate("inflation") },
        )
        val principal = holdings.sumOf { it.latestBalance }
        PillarCard(
            tile = { GlyphTile(Fiscal.Accent, Glyph.TRIANGLE) },
            label = "Investments",
            value = fullCurrency(principal),
            stat = band?.let { "→ ${compactCurrency(it.expected.years.last().endBalanceNominal)} ›" } ?: "›",
            statColor = Fiscal.Accent,
            onClick = { navController.navigate("invest") },
        )

        // This month.
        FiscalCard {
            Eyebrow("This month")
            Spacer(Modifier.height(10.dp))
            Row {
                MonthColumn(
                    Modifier.weight(1f),
                    fullCurrency(included.sumOf { it.minPayment } + extra), "to debt",
                )
                Box(Modifier.width(1.dp).height(40.dp).background(Fiscal.Hairline))
                MonthColumn(Modifier.weight(1f), fullCurrency(contribution), "invested")
                Box(Modifier.width(1.dp).height(40.dp).background(Fiscal.Hairline))
                MonthColumn(Modifier.weight(1f), fullCurrency(extra), "extra pmt", Fiscal.Accent)
            }
        }

        EmergencyFundSection(vm)

        SinkingFundsSection(vm)

        CashFlowSection(vm)

        AttributionSection(vm)

        MilestonesSection(vm)

        Eyebrow("Assets vs. debts", modifier = Modifier.padding(top = 6.dp))
        FiscalCard {
            val months = netWorth.takeLast(24)
            StackedAreaChart(
                series = listOf(
                    Series("Debts", months.map { it.debts }, Fiscal.Coral),
                    Series("Assets", months.map { it.assets }, Fiscal.Accent),
                ),
                xLabel = { i -> months.getOrNull(i)?.month?.format(monthFmt) ?: "" },
            )
        }
    }
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Good morning,"
    in 12..17 -> "Good afternoon,"
    else -> "Good evening,"
}

@Composable
private fun OnboardingCard(
    vm: AppViewModel,
    navController: NavHostController,
    hasBalances: Boolean,
    hasTransactions: Boolean,
) {
    val csvTypes = arrayOf("text/csv", "text/comma-separated-values", "application/csv", "text/plain")
    val balancesPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.requestImportBalances(it) } }
    val transactionsPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.requestImportTransactions(it) } }
    val status by vm.importStatus.collectAsState()

    HeroCard {
        Eyebrow("Get set up", color = Fiscal.Accent)
        Spacer(Modifier.height(8.dp))
        OnboardingStep(
            done = hasBalances,
            number = 1,
            title = "Import your Balances CSV",
            subtitle = "Accounts, debts, and net worth come from this file",
            actionLabel = if (hasBalances) null else "Choose file",
            onAction = { balancesPicker.launch(csvTypes) },
        )
        OnboardingStep(
            done = hasTransactions,
            number = 2,
            title = "Import your Transactions CSV",
            subtitle = "Income, spending, cash flow, and the inflation analysis need this",
            actionLabel = if (hasTransactions) null else "Choose file",
            onAction = { transactionsPicker.launch(csvTypes) },
        )
        OnboardingStep(
            done = false,
            number = 3,
            title = "Confirm your debts",
            subtitle = "Check the detected debts and enter real APRs",
            actionLabel = if (hasBalances) "Review" else null,
            onAction = { navController.navigate("debts") },
        )
        status?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.labelMedium, color = Fiscal.Accent)
        }
        Text(
            "Everything stays on this device — the app makes no network calls.",
            style = MaterialTheme.typography.labelSmall,
            color = Fiscal.TextMuted,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    // The confirm-before-replace dialog for these pickers lives on the Settings
    // screen too; render it here so onboarding imports can be confirmed in place.
    val pending by vm.pendingImport.collectAsState()
    pending?.let { p ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { vm.cancelPendingImport() },
            title = { Text("Import ${p.preview.rows} rows?") },
            text = {
                Text(
                    "${if (p.preview.kind == com.financedashboard.app.data.CsvImporter.Preview.Kind.BALANCES) "Balances" else "Transactions"} file" +
                        (p.preview.from?.let { " covering $it → ${p.preview.to}" } ?: "") +
                        ". This replaces any previously imported data of the same type.",
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { vm.confirmPendingImport() }) { Text("Import") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { vm.cancelPendingImport() }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun OnboardingStep(
    done: Boolean,
    number: Int,
    title: String,
    subtitle: String,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
        Box(
            Modifier
                .size(28.dp)
                .background(if (done) Fiscal.Accent else Fiscal.Track, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (done) "✓" else number.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = if (done) Fiscal.OnAccent else Fiscal.TextSecondary,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Fiscal.TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Fiscal.TextMuted)
        }
        actionLabel?.let {
            androidx.compose.material3.TextButton(onClick = onAction) { Text(it, color = Fiscal.Accent) }
        }
    }
}

@Composable
private fun PillarCard(
    tile: @Composable () -> Unit,
    label: String,
    value: String,
    stat: String,
    statColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    FiscalCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            tile()
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = Fiscal.TextSecondary)
                Text(value, style = MaterialTheme.typography.titleLarge, color = Fiscal.TextPrimary)
            }
            Text(stat, style = MaterialTheme.typography.labelLarge, color = statColor)
        }
    }
}

@Composable
private fun MonthColumn(
    modifier: Modifier,
    value: String,
    label: String,
    valueColor: androidx.compose.ui.graphics.Color = Fiscal.TextPrimary,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = valueColor)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Fiscal.TextMuted)
    }
}

@Composable
private fun CashFlowSection(vm: AppViewModel) {
    val cashFlow by vm.cashFlow.collectAsState()
    if (cashFlow.size < 2) return
    val months = cashFlow.takeLast(12)
    val totalIncome = months.sumOf { it.income }
    val totalSpend = months.sumOf { it.spending }
    val savingsRate = if (totalIncome > 0.005) (1.0 - totalSpend / totalIncome) else 0.0

    Eyebrow("Cash flow", modifier = Modifier.padding(top = 6.dp))
    FiscalCard {
        Row {
            Column(Modifier.weight(1f)) {
                Text(
                    "${(savingsRate * 100).toInt()}% kept",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (savingsRate >= 0.2) Fiscal.Accent else if (savingsRate >= 0.0) Fiscal.Amber else Fiscal.Coral,
                )
                Text(
                    "of take-home after spending, last ${months.size} months",
                    style = MaterialTheme.typography.labelSmall,
                    color = Fiscal.TextMuted,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${compactCurrency(totalIncome - totalSpend)} free",
                    style = MaterialTheme.typography.titleMedium,
                    color = Fiscal.TextPrimary,
                )
                Text(
                    "≈ ${compactCurrency((totalIncome - totalSpend) / months.size)}/mo for debt & investing",
                    style = MaterialTheme.typography.labelSmall,
                    color = Fiscal.TextMuted,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        com.financedashboard.app.ui.charts.PairedBarChart(
            labels = months.map { it.month.format(monthFmt) },
            seriesA = Series("Take-home", months.map { it.income }, Fiscal.Accent),
            seriesB = Series("Spending", months.map { it.spending }, Fiscal.Coral),
        )
    }
}

@Composable
private fun MilestonesSection(vm: AppViewModel) {
    val plan by vm.debtPlan.collectAsState()
    val ef by vm.emergencyFund.collectAsState()
    val band by vm.investmentBand.collectAsState()
    val redirect by vm.redirectInfo.collectAsState()

    data class Milestone(val month: YearMonth, val label: String, val color: androidx.compose.ui.graphics.Color)

    val now = YearMonth.now()
    val startYear = java.time.LocalDate.now().year
    val milestones = buildList {
        plan?.ef?.efFundedMonth?.let {
            if (it >= now) add(Milestone(it, "Emergency fund fully funded", Fiscal.Accent))
        } ?: ef?.monthsToTarget?.takeIf { it > 0 }?.let {
            add(Milestone(now.plusMonths(it.toLong()), "Emergency fund fully funded", Fiscal.Accent))
        }
        plan?.plan?.debts?.forEach { d ->
            d.payoffMonth?.let { add(Milestone(it, "${d.debt.name.take(24)} paid off", Fiscal.Coral)) }
        }
        band?.expected?.let { proj ->
            for (target in listOf(250_000.0, 500_000.0, 1_000_000.0)) {
                com.financedashboard.core.engine.InvestmentEngine.milestoneYear(proj, target)?.let { y ->
                    add(Milestone(YearMonth.of(startYear + y, 12), "Investments cross ${compactCurrency(target)}", Fiscal.Sky))
                }
            }
        }
        redirect?.let {
            add(Milestone(now.plusMonths(it.fromMonth.toLong()), "${fullCurrency(it.amount)}/mo debt budget redirects to investing", Fiscal.Sky))
        }
    }.filter { it.month >= now }.sortedBy { it.month }.take(6)

    if (milestones.isEmpty()) return
    Eyebrow("Milestones ahead", modifier = Modifier.padding(top = 6.dp))
    FiscalCard {
        milestones.forEachIndexed { i, m ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 5.dp)) {
                Box(Modifier.size(10.dp).background(m.color, CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(m.label, style = MaterialTheme.typography.bodySmall, color = Fiscal.TextPrimary, modifier = Modifier.weight(1f))
                Text(
                    m.month.format(payoffFmt),
                    style = MaterialTheme.typography.labelLarge,
                    color = Fiscal.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun SinkingFundsSection(vm: AppViewModel) {
    val funds by vm.sinkingFunds.collectAsState()
    var showAdd by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
        Eyebrow("Sinking funds", modifier = Modifier.weight(1f))
        androidx.compose.material3.TextButton(onClick = { showAdd = true }) { Text("Add", color = Fiscal.Accent) }
    }
    if (funds.isEmpty()) {
        FiscalCard {
            Text(
                "Reserve for lumpy expenses — car maintenance, insurance, holidays. Each fund tracks a target " +
                    "and a monthly set-aside, like the emergency fund.",
                style = MaterialTheme.typography.bodySmall, color = Fiscal.TextSecondary,
            )
        }
    }
    for (f in funds) {
        val progress = if (f.target > 0) (f.current / f.target).coerceIn(0.0, 1.0) else 0.0
        val monthsLeft = if (f.monthly > 0 && f.current < f.target) Math.ceil((f.target - f.current) / f.monthly).toInt() else 0
        FiscalCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(f.name, style = MaterialTheme.typography.titleSmall, color = Fiscal.TextPrimary)
                    Text(
                        "${fullCurrency(f.current)} of ${fullCurrency(f.target)}" +
                            if (monthsLeft > 0) " · funded in ${monthsLeft} mo at ${fullCurrency(f.monthly)}/mo" else " · funded ✓",
                        style = MaterialTheme.typography.labelSmall, color = Fiscal.TextMuted,
                    )
                }
                Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.titleMedium, color = Fiscal.TextPrimary)
                androidx.compose.material3.TextButton(onClick = { vm.removeSinkingFund(f.name) }) { Text("✕", color = Fiscal.TextMuted) }
            }
            Spacer(Modifier.height(8.dp))
            FiscalBar(progress = progress.toFloat())
        }
    }
    if (showAdd) {
        var name by remember { mutableStateOf("") }
        var target by remember { mutableStateOf("") }
        var current by remember { mutableStateOf("") }
        var monthly by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Add sinking fund") },
            text = {
                Column {
                    androidx.compose.material3.OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    androidx.compose.material3.OutlinedTextField(target, { target = it }, label = { Text("Target ($)") }, singleLine = true, modifier = Modifier.padding(top = 6.dp))
                    androidx.compose.material3.OutlinedTextField(current, { current = it }, label = { Text("Current ($)") }, singleLine = true, modifier = Modifier.padding(top = 6.dp))
                    androidx.compose.material3.OutlinedTextField(monthly, { monthly = it }, label = { Text("Monthly ($)") }, singleLine = true, modifier = Modifier.padding(top = 6.dp))
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val t = target.replace(",", "").replace("$", "").toDoubleOrNull()
                    if (name.isNotBlank() && t != null && t > 0) {
                        vm.addSinkingFund(name.trim(), t, current.replace(",", "").replace("$", "").toDoubleOrNull() ?: 0.0, monthly.replace(",", "").replace("$", "").toDoubleOrNull() ?: 0.0)
                        showAdd = false
                    }
                }) { Text("Save") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { showAdd = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AttributionSection(vm: AppViewModel) {
    val attr by vm.attribution.collectAsState()
    val a = attr ?: return
    if (kotlin.math.abs(a.totalChange) < 1.0) return

    Eyebrow("What moved your net worth (last 12 months)", modifier = Modifier.padding(top = 6.dp))
    FiscalCard {
        Text(
            "${signedCurrency(a.totalChange)} net worth change",
            style = MaterialTheme.typography.titleMedium,
            color = if (a.totalChange >= 0) Fiscal.Accent else Fiscal.Coral,
        )
        Spacer(Modifier.height(10.dp))
        val rows = listOf(
            Triple("Saved from income", a.cashSaved, Fiscal.Accent),
            Triple("Market growth", a.marketChange, Fiscal.Sky),
            Triple("Debt paid down", a.debtPrincipalPaid, Fiscal.Coral),
            Triple("Asset revaluation", a.assetRevaluation, Fiscal.Amber),
            Triple("Unexplained (data gaps)", a.residual, Fiscal.TextMuted),
        ).filter { kotlin.math.abs(it.second) > 1.0 }
        val maxMag = rows.maxOfOrNull { kotlin.math.abs(it.second) } ?: 1.0
        for ((label, value, color) in rows) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = Fiscal.TextSecondary, modifier = Modifier.weight(1f))
                Box(
                    Modifier
                        .weight(1.4f)
                        .height(10.dp)
                        .fillMaxWidth(),
                ) {
                    FiscalBar(progress = (kotlin.math.abs(value) / maxMag).toFloat(), height = 10.dp, color = color, gradient = false)
                }
                Spacer(Modifier.width(8.dp))
                Text(signedCurrency(value), style = MaterialTheme.typography.labelMedium, color = Fiscal.TextPrimary)
            }
        }
        Text(
            "Approximate: contributions are inferred from transfers, and the residual absorbs anything the data can't attribute.",
            style = MaterialTheme.typography.labelSmall,
            color = Fiscal.TextMuted,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun EmergencyFundSection(vm: AppViewModel) {
    val ef by vm.emergencyFund.collectAsState()
    val efMonths by vm.efTargetMonths.collectAsState()
    val saving by vm.efMonthlySaving.collectAsState()
    val income by vm.avgMonthlyIncome.collectAsState()
    val state = ef ?: return

    Eyebrow("Emergency fund", modifier = Modifier.padding(top = 6.dp))
    FiscalCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${"%.1f".format(state.runwayMonths)} months of runway",
                    style = MaterialTheme.typography.titleMedium,
                    color = when {
                        state.runwayMonths >= state.targetMonths -> Fiscal.Accent
                        state.runwayMonths >= 1.0 -> Fiscal.Amber
                        else -> Fiscal.Coral
                    },
                )
                Text(
                    "${fullCurrency(state.liquidCash)} cash on hand",
                    style = MaterialTheme.typography.bodySmall,
                    color = Fiscal.TextSecondary,
                )
                Text(
                    (if (income > 0.005) "you earn ~${fullCurrency(income)}/mo · " else "") +
                        "you spend ~${fullCurrency(state.avgMonthlyExpenses)}/mo",
                    style = MaterialTheme.typography.labelSmall,
                    color = Fiscal.TextMuted,
                )
            }
            Text(
                "${(state.progress * 100).toInt()}%",
                style = MaterialTheme.typography.titleLarge,
                color = Fiscal.TextPrimary,
            )
        }
        Spacer(Modifier.height(10.dp))
        FiscalBar(progress = state.progress.toFloat())
        Spacer(Modifier.height(8.dp))
        Text(
            "Target ${fullCurrency(state.targetAmount)} ($efMonths months of expenses)" +
                if (state.gap > 0) " · ${fullCurrency(state.gap)} to go" else " · fully funded ✓",
            style = MaterialTheme.typography.bodySmall,
            color = Fiscal.TextSecondary,
        )
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
        if (state.gap > 0) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Saving ${fullCurrency(saving)}/month",
                style = MaterialTheme.typography.labelLarge,
                color = Fiscal.TextPrimary,
            )
            Slider(
                value = saving.toFloat(),
                onValueChange = { vm.setEfMonthlySaving((it / 25).toInt() * 25.0) },
                valueRange = 0f..3000f,
            )
            Text(
                state.monthsToTarget?.let { m ->
                    val eta = YearMonth.now().plusMonths(m.toLong())
                    "Funded in $m months (${eta.format(payoffFmt)}) at this rate"
                } ?: "Set a monthly saving amount to see when the fund completes",
                style = MaterialTheme.typography.bodySmall,
                color = Fiscal.TextSecondary,
            )
        }
    }
}
