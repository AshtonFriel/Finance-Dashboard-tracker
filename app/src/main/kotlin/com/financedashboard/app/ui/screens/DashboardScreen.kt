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

        if (netWorth.isEmpty()) {
            FiscalCard {
                Text("No data yet", style = MaterialTheme.typography.titleMedium, color = Fiscal.TextPrimary)
                Text(
                    "Import your Balances and Transactions CSV exports from More → Settings to get started. " +
                        "All data stays on this device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Fiscal.TextSecondary,
                )
            }
            return
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
private fun EmergencyFundSection(vm: AppViewModel) {
    val ef by vm.emergencyFund.collectAsState()
    val efMonths by vm.efTargetMonths.collectAsState()
    val saving by vm.efMonthlySaving.collectAsState()
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
                    "${fullCurrency(state.liquidCash)} cash · spending ~${fullCurrency(state.avgMonthlyExpenses)}/mo",
                    style = MaterialTheme.typography.bodySmall,
                    color = Fiscal.TextSecondary,
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
