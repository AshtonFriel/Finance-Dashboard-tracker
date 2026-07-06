package com.financedashboard.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
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
import com.financedashboard.app.ui.theme.Fiscal
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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

        val owners by vm.owners.collectAsState()
        val selectedOwner by vm.selectedOwner.collectAsState()
        val ownerSpending by vm.ownerSpending.collectAsState()
        val shownSpending = if (selectedOwner.isBlank()) spending else ownerSpending
        if (spending.isNotEmpty()) {
            SectionTitle("Spending by category (last 12 months)")
            if (owners.size > 1) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    androidx.compose.material3.FilterChip(
                        selected = selectedOwner.isBlank(),
                        onClick = { vm.setSelectedOwner("") },
                        label = { Text("Everyone") },
                    )
                    for (o in owners) {
                        androidx.compose.material3.FilterChip(
                            selected = selectedOwner == o,
                            onClick = { vm.setSelectedOwner(o) },
                            label = { Text(o) },
                        )
                    }
                }
            }
            Card {
                Column(Modifier.padding(12.dp)) {
                    val top = shownSpending.take(7)
                    val other = shownSpending.drop(7).sumOf { it.total }
                    val slices = top.mapIndexed { i, c ->
                        Triple(c.category, c.total, chart.categorical[i % chart.categorical.size])
                    } + if (other > 0) listOf(Triple("Other", other, chart.mutedInk)) else emptyList()
                    DonutChart(
                        slices = slices,
                        centerLabel = if (selectedOwner.isBlank()) "12-mo spend" else selectedOwner,
                        centerValue = com.financedashboard.app.ui.charts.compactCurrency(shownSpending.sumOf { it.total }),
                    )
                }
            }
        }

        BudgetsSection(vm)
        RecurringChargesSection(vm)
        TopMoversSection(vm)
        TransactionBrowserSection(vm)

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

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun BudgetsSection(vm: AppViewModel) {
    val summary by vm.budgetSummary.collectAsState()
    val spending by vm.spendingLastYear.collectAsState()
    val s = summary
    var adding by remember { mutableStateOf(false) }

    SectionTitle("Monthly budgets")
    FiscalCard {
        val lines = s?.lines ?: emptyList()
        if (lines.isEmpty()) {
            Text(
                "Set a monthly limit on a category to track spending against it.",
                style = MaterialTheme.typography.bodySmall, color = Fiscal.TextMuted,
            )
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${fullCurrency(s!!.totalSpent)} of ${fullCurrency(s.totalLimit)} budgeted",
                    style = MaterialTheme.typography.titleMedium, color = Fiscal.TextPrimary,
                )
                if (s.overCount > 0) {
                    Text("${s.overCount} over", style = MaterialTheme.typography.labelMedium, color = Fiscal.Coral)
                }
            }
            Spacer(Modifier.height(8.dp))
            for (line in lines) {
                val color = when (line.status) {
                    com.financedashboard.core.engine.BudgetEngine.Status.OVER -> Fiscal.Coral
                    com.financedashboard.core.engine.BudgetEngine.Status.NEAR -> Fiscal.Amber
                    com.financedashboard.core.engine.BudgetEngine.Status.UNDER -> Fiscal.Accent
                }
                Column(Modifier.padding(vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(line.category, style = MaterialTheme.typography.bodyMedium, color = Fiscal.TextPrimary, modifier = Modifier.weight(1f))
                        Text(
                            "${fullCurrency(line.spent)} / ${fullCurrency(line.limit)}",
                            style = MaterialTheme.typography.labelMedium, color = Fiscal.TextSecondary,
                        )
                        TextButton(
                            onClick = { vm.setCategoryBudget(line.category, 0.0) },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
                        ) { Text("✕", color = Fiscal.TextMuted) }
                    }
                    FiscalBar(progress = line.fractionUsed.toFloat(), height = 7.dp, color = color)
                    val note = when (line.status) {
                        com.financedashboard.core.engine.BudgetEngine.Status.OVER ->
                            "${fullCurrency(-line.remaining)} over budget"
                        com.financedashboard.core.engine.BudgetEngine.Status.NEAR ->
                            "on pace for ${fullCurrency(line.projectedSpend)} this month"
                        com.financedashboard.core.engine.BudgetEngine.Status.UNDER ->
                            "${fullCurrency(line.remaining)} left"
                    }
                    Text(note, style = MaterialTheme.typography.labelSmall, color = color)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        TextButton(onClick = { adding = true }) { Text("+ Set a budget", color = Fiscal.Accent) }
    }

    if (adding) {
        val categories = spending.map { it.category }.distinct()
        var selected by remember { mutableStateOf(categories.firstOrNull() ?: "") }
        var amount by remember { mutableStateOf("") }
        var expanded by remember { mutableStateOf(false) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Set a monthly budget") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box {
                        TextButton(onClick = { expanded = true }) {
                            Text(selected.ifBlank { "Choose category" }, color = Fiscal.TextPrimary)
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            for (c in categories) {
                                DropdownMenuItem(text = { Text(c) }, onClick = { selected = c; expanded = false })
                            }
                        }
                    }
                    androidx.compose.material3.OutlinedTextField(
                        value = amount,
                        onValueChange = { amount = it },
                        label = { Text("Monthly limit ($)") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = amount.replace(",", "").replace("$", "").toDoubleOrNull()
                    if (selected.isNotBlank() && v != null && v > 0) vm.setCategoryBudget(selected, v)
                    adding = false
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RecurringChargesSection(vm: AppViewModel) {
    val charges by vm.recurringCharges.collectAsState()
    val total by vm.recurringMonthlyTotal.collectAsState()
    val active = charges.filter { !it.possiblyCancelled }
    if (active.isEmpty()) return

    val hikes by vm.priceHikes.collectAsState()
    SectionTitle("Recurring charges")
    FiscalCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${fullCurrency(total)}/mo",
                    style = MaterialTheme.typography.titleMedium,
                    color = Fiscal.Accent,
                )
                Text(
                    "${active.size} recurring charges · ${fullCurrency(total * 12)}/yr",
                    style = MaterialTheme.typography.labelSmall,
                    color = Fiscal.TextMuted,
                )
            }
        }
        if (hikes.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "↑ Price increases: " + hikes.take(3).joinToString(", ") {
                    "${it.merchant} +${(it.priceChangePct * 100).toInt()}%"
                },
                style = MaterialTheme.typography.labelSmall,
                color = Fiscal.Coral,
            )
        }
        Spacer(Modifier.height(8.dp))
        for (s in active.take(12)) {
            val hiked = hikes.any { it.merchant == s.merchant }
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.merchant, style = MaterialTheme.typography.bodyMedium, color = Fiscal.TextPrimary)
                    Text(
                        "${s.cadence.label} · ${fullCurrency(s.typicalAmount)}" +
                            if (hiked) "  ·  was ${fullCurrency(s.earliestAmount)}" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (hiked) Fiscal.Coral else Fiscal.TextMuted,
                    )
                }
                Text("${fullCurrency(s.monthlyEquivalent)}/mo", style = MaterialTheme.typography.labelLarge, color = Fiscal.TextPrimary)
            }
        }
        val cancelled = charges.filter { it.possiblyCancelled }
        if (cancelled.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Possibly ended: ${cancelled.take(4).joinToString { it.merchant }}",
                style = MaterialTheme.typography.labelSmall,
                color = Fiscal.TextMuted,
            )
        }
    }
}

@Composable
private fun TopMoversSection(vm: AppViewModel) {
    val movers by vm.topMovers.collectAsState()
    if (movers.isEmpty()) return
    SectionTitle("Top movers (last full month vs prior)")
    FiscalCard {
        for (m in movers) {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(m.category, style = MaterialTheme.typography.bodyMedium, color = Fiscal.TextPrimary, modifier = Modifier.weight(1f))
                Text(fullCurrency(m.current), style = MaterialTheme.typography.labelMedium, color = Fiscal.TextSecondary)
                Spacer(Modifier.width(10.dp))
                // Spending up is bad (coral), down is good (accent).
                Text(
                    (if (m.delta >= 0) "▲ " else "▼ ") + fullCurrency(kotlin.math.abs(m.delta)),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (m.delta >= 0) Fiscal.Coral else Fiscal.Accent,
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TransactionBrowserSection(vm: AppViewModel) {
    val query by vm.searchQuery.collectAsState()
    val category by vm.searchCategory.collectAsState()
    val results by vm.searchResults.collectAsState()
    val spending by vm.spendingLastYear.collectAsState()

    SectionTitle("Find a transaction")
    FiscalCard {
        androidx.compose.material3.OutlinedTextField(
            value = query,
            onValueChange = { vm.setSearchQuery(it) },
            label = { Text("Search merchant or statement") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (spending.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in spending.take(8)) {
                    androidx.compose.material3.FilterChip(
                        selected = category == c.category,
                        onClick = { vm.setSearchCategory(c.category) },
                        label = { Text(c.category, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        }
        if (query.isNotBlank() || category.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            if (results.isEmpty()) {
                Text("No matches", style = MaterialTheme.typography.bodySmall, color = Fiscal.TextMuted)
            }
            for (t in results.take(50)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.merchant, style = MaterialTheme.typography.bodyMedium, color = Fiscal.TextPrimary)
                        Text(
                            "${java.time.LocalDate.ofEpochDay(t.epochDay)} · ${t.category}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Fiscal.TextMuted,
                        )
                    }
                    Text(
                        fullCurrency(t.amount),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (t.amount < 0) Fiscal.TextPrimary else Fiscal.Accent,
                    )
                }
            }
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
