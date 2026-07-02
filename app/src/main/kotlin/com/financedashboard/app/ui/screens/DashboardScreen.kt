package com.financedashboard.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.financedashboard.app.AppViewModel
import com.financedashboard.app.ui.charts.Series
import com.financedashboard.app.ui.charts.StackedAreaChart
import com.financedashboard.app.ui.charts.fullCurrency
import com.financedashboard.app.ui.theme.LocalChartColors
import java.time.format.DateTimeFormatter

private val monthFmt = DateTimeFormatter.ofPattern("MMM yy")

@Composable
fun DashboardScreen(vm: AppViewModel) {
    val chart = LocalChartColors.current
    val netWorth by vm.netWorth.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val impact by vm.inflationImpact.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Dashboard", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

        if (netWorth.isEmpty()) {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("No data yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Import your Balances and Transactions CSV exports from the Settings tab to get started. " +
                            "All data stays on this device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = chart.secondaryInk,
                    )
                }
            }
            return
        }

        val latest = netWorth.last()
        val prev = netWorth.getOrNull(netWorth.size - 2)
        val delta = prev?.let { latest.net - it.net }

        StatTile(
            label = "Net worth",
            value = fullCurrency(latest.net),
            sublabel = delta?.let { "${signedCurrency(it)} vs last month" },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                label = "Total debt",
                value = fullCurrency(-latest.debts),
                accent = chart.seriesRed,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Assets",
                value = fullCurrency(latest.assets),
                accent = chart.seriesAqua,
                modifier = Modifier.weight(1f),
            )
        }
        impact?.let {
            val lost = it.cumulativeNominalGap < 0
            StatTile(
                label = "Purchasing power ${if (lost) "lost" else "gained"} vs. inflation since ${it.baseYear}",
                value = fullCurrency(kotlin.math.abs(it.cumulativeNominalGap)),
                accent = if (lost) chart.critical else chart.good,
                sublabel = "Details in the Inflation tab",
                modifier = Modifier.fillMaxWidth(),
            )
        }

        EmergencyFundSection(vm)

        SectionTitle("Assets vs. debts")
        Card {
            Column(Modifier.padding(12.dp)) {
                val months = netWorth.takeLast(24)
                StackedAreaChart(
                    series = listOf(
                        Series("Debts", months.map { it.debts }, chart.seriesRed),
                        Series("Assets", months.map { it.assets }, chart.seriesAqua),
                    ),
                    xLabel = { i -> months.getOrNull(i)?.month?.format(monthFmt) ?: "" },
                )
            }
        }

        SectionTitle("Accounts tracked")
        Text(
            "${accounts.size} accounts • drag on charts to inspect values",
            style = MaterialTheme.typography.bodySmall,
            color = chart.mutedInk,
        )
    }
}

@Composable
private fun EmergencyFundSection(vm: AppViewModel) {
    val chart = LocalChartColors.current
    val ef by vm.emergencyFund.collectAsState()
    val efMonths by vm.efTargetMonths.collectAsState()
    val saving by vm.efMonthlySaving.collectAsState()
    val state = ef ?: return

    SectionTitle("Emergency fund")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${"%.1f".format(state.runwayMonths)} months of runway",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = when {
                            state.runwayMonths >= state.targetMonths -> chart.good
                            state.runwayMonths >= 1.0 -> chart.seriesYellow
                            else -> chart.critical
                        },
                    )
                    Text(
                        "${fullCurrency(state.liquidCash)} cash • spending ~${fullCurrency(state.avgMonthlyExpenses)}/mo",
                        style = MaterialTheme.typography.bodySmall,
                        color = chart.secondaryInk,
                    )
                }
                Text(
                    "${(state.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = chart.primaryInk,
                )
            }
            LinearProgressIndicator(
                progress = { state.progress.toFloat() },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = if (state.progress >= 1f) chart.good else chart.seriesAqua,
                trackColor = chart.gridline,
            )
            Text(
                "Target: ${fullCurrency(state.targetAmount)} ($efMonths months of expenses)" +
                    if (state.gap > 0) " • ${fullCurrency(state.gap)} to go" else " • fully funded ✓",
                style = MaterialTheme.typography.bodySmall,
                color = chart.secondaryInk,
            )
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
                Text(
                    "Saving ${fullCurrency(saving)}/month",
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(
                    value = saving.toFloat(),
                    onValueChange = { vm.setEfMonthlySaving((it / 25).toInt() * 25.0) },
                    valueRange = 0f..3000f,
                )
                Text(
                    state.monthsToTarget?.let { m ->
                        val eta = java.time.YearMonth.now().plusMonths(m.toLong())
                        "Funded in $m months (${eta.month.name.lowercase().replaceFirstChar { it.uppercase() }} ${eta.year}) at this rate"
                    } ?: "Set a monthly saving amount to see when the fund completes",
                    style = MaterialTheme.typography.bodySmall,
                    color = chart.secondaryInk,
                )
            }
            Text(
                "Counts cash accounts only; average spending is the last 6 full months excluding transfers and debt payments. " +
                    "Enable “Build emergency fund first” on the Debts tab to sequence it before extra debt payments.",
                style = MaterialTheme.typography.labelSmall,
                color = chart.mutedInk,
            )
        }
    }
}
