package com.financedashboard.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
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
import com.financedashboard.app.ui.charts.DonutChart
import com.financedashboard.app.ui.charts.Sparkline
import com.financedashboard.app.ui.charts.fullCurrency
import com.financedashboard.app.ui.theme.LocalChartColors
import com.financedashboard.core.model.AccountType

@Composable
fun AccountsScreen(vm: AppViewModel) {
    val chart = LocalChartColors.current
    val accounts by vm.accounts.collectAsState()
    val spending by vm.spendingLastYear.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Accounts", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

        if (spending.isNotEmpty()) {
            SectionTitle("Spending by category (last 12 months)")
            Card {
                Column(Modifier.padding(12.dp)) {
                    val top = spending.take(7)
                    val other = spending.drop(7).sumOf { it.total }
                    val slices = top.mapIndexed { i, c ->
                        Triple(c.category, c.total, chart.categorical[i % chart.categorical.size])
                    } + if (other > 0) listOf(Triple("Other", other, chart.mutedInk)) else emptyList()
                    DonutChart(
                        slices = slices,
                        centerLabel = "12-mo spend",
                        centerValue = com.financedashboard.app.ui.charts.compactCurrency(spending.sumOf { it.total }),
                    )
                }
            }
        }

        val grouped = accounts.groupBy { it.type }
        val order = listOf(AccountType.CASH, AccountType.INVESTMENT, AccountType.DEBT, AccountType.ASSET, AccountType.UNKNOWN)
        for (type in order) {
            val group = grouped[type] ?: continue
            SectionTitle(
                when (type) {
                    AccountType.CASH -> "Cash"
                    AccountType.INVESTMENT -> "Investments"
                    AccountType.DEBT -> "Debts"
                    AccountType.ASSET -> "Assets"
                    AccountType.UNKNOWN -> "Unclassified"
                }
            )
            for (acc in group.sortedByDescending { kotlin.math.abs(it.latestBalance) }) {
                AccountRow(vm, acc.name, acc.latestBalance, type)
            }
        }
        if (accounts.isEmpty()) {
            Text(
                "Nothing here yet — import a Balances CSV from Settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = chart.secondaryInk,
            )
        }
    }
}

@Composable
private fun AccountRow(vm: AppViewModel, name: String, balance: Double, type: AccountType) {
    val chart = LocalChartColors.current
    var menuOpen by remember { mutableStateOf(false) }
    val historyFlow = remember(name) { vm.accountHistory(name) }
    val history by historyFlow.collectAsState(emptyList())

    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall)
                Text(
                    fullCurrency(balance),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (balance < 0) chart.seriesRed else chart.secondaryInk,
                )
            }
            Sparkline(
                values = history.takeLast(90).map { it.second },
                color = if (balance < 0) chart.seriesRed else chart.seriesAqua,
                modifier = Modifier.width(72.dp).height(28.dp),
            )
            Column {
                TextButton(onClick = { menuOpen = true }) { Text("Type") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    for (t in AccountType.entries.filter { it != AccountType.UNKNOWN }) {
                        DropdownMenuItem(
                            text = { Text(t.name.lowercase().replaceFirstChar { it.uppercase() } + if (t == type) " ✓" else "") },
                            onClick = {
                                vm.overrideAccountType(name, t)
                                menuOpen = false
                            },
                        )
                    }
                }
            }
        }
    }
}
