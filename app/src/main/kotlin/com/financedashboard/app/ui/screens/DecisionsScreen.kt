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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.financedashboard.app.ui.charts.compactCurrency
import com.financedashboard.app.ui.charts.fullCurrency
import com.financedashboard.app.ui.theme.Fiscal
import com.financedashboard.core.engine.SensitivityEngine
import com.financedashboard.core.model.Debt
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val mFmt = DateTimeFormatter.ofPattern("MMM yyyy")

@Composable
fun DecisionsScreen(vm: AppViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Decisions", style = MaterialTheme.typography.headlineSmall, color = Fiscal.TextPrimary)
        Text("Answers computed from your own debt and investment engines.", style = MaterialTheme.typography.bodySmall, color = Fiscal.TextSecondary)

        OptimizerCard(vm)
        RefinanceCard(vm)
        SensitivityCard(vm)
    }
}

@Composable
private fun OptimizerCard(vm: AppViewModel) {
    val result by vm.optimizerResult.collectAsState()
    val extra by vm.extraMonthly.collectAsState()
    val tax by vm.marginalTaxPct.collectAsState()
    Eyebrow("Pay down debt or invest?")
    FiscalCard {
        val r = result
        if (r == null) {
            Text("Add a debt and set an extra-payment amount to compare.", style = MaterialTheme.typography.bodySmall, color = Fiscal.TextSecondary)
            return@FiscalCard
        }
        Text(
            "The next ${fullCurrency(r.monthlyAmount)}/mo",
            style = MaterialTheme.typography.titleMedium, color = Fiscal.TextPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Row {
            Column(Modifier.weight(1f)) {
                Eyebrow("Pay ${r.targetDebtName?.take(14)}")
                Text(fullCurrency(r.interestSaved), style = MaterialTheme.typography.titleMedium, color = Fiscal.Coral)
                Text("interest saved", style = MaterialTheme.typography.labelSmall, color = Fiscal.TextMuted)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Eyebrow("Invest @ ${r.expectedReturnPct.toInt()}%")
                Text(fullCurrency(r.investedGrowth), style = MaterialTheme.typography.titleMedium, color = Fiscal.Accent)
                Text("projected growth", style = MaterialTheme.typography.labelSmall, color = Fiscal.TextMuted)
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().background(Fiscal.AccentTint, RoundedCornerShape(12.dp)).padding(12.dp)) {
            Text(r.recommendation, style = MaterialTheme.typography.bodySmall, color = Fiscal.TextPrimary)
        }
        Text(
            "Investing wins above a ${"%.1f".format(r.crossoverReturnPct)}% return" +
                (if (tax > 0) " (after your ${tax.toInt()}% marginal tax)" else "") +
                ". Debt paydown is guaranteed; the market isn't.",
            style = MaterialTheme.typography.labelSmall, color = Fiscal.TextMuted,
            modifier = Modifier.padding(top = 6.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Text("Marginal tax %", style = MaterialTheme.typography.labelSmall, color = Fiscal.TextMuted)
            Spacer(Modifier.width(8.dp))
            androidx.compose.material3.Slider(
                value = tax.toFloat(), onValueChange = { vm.setMarginalTaxPct(it.toInt().toDouble()) },
                valueRange = 0f..50f, modifier = Modifier.weight(1f),
            )
            Text("${tax.toInt()}%", style = MaterialTheme.typography.labelMedium, color = Fiscal.TextPrimary)
        }
    }
}

@Composable
private fun RefinanceCard(vm: AppViewModel) {
    val debts by vm.debtInputs.collectAsState()
    val candidates = debts.filter { it.includeInPlan }
    var selected by remember { mutableStateOf<String?>(null) }
    var newRate by remember { mutableStateOf("") }
    var fees by remember { mutableStateOf("0") }

    Eyebrow("Refinance calculator")
    FiscalCard {
        if (candidates.isEmpty()) {
            Text("No debts to refinance.", style = MaterialTheme.typography.bodySmall, color = Fiscal.TextSecondary)
            return@FiscalCard
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            for (d in candidates.take(4)) {
                androidx.compose.material3.FilterChip(
                    selected = selected == d.accountName,
                    onClick = { selected = d.accountName },
                    label = { Text(d.accountName.take(12), style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }
        val debt = candidates.firstOrNull { it.accountName == selected }
        if (debt == null) {
            Text("Pick a debt to compare a new rate.", style = MaterialTheme.typography.bodySmall, color = Fiscal.TextMuted)
            return@FiscalCard
        }
        Text("${debt.accountName}: ${fullCurrency(debt.balance)} @ ${debt.aprPct}% now", style = MaterialTheme.typography.bodySmall, color = Fiscal.TextSecondary)
        Row {
            OutlinedTextField(newRate, { newRate = it }, label = { Text("New APR %") }, singleLine = true, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(fees, { fees = it }, label = { Text("Fees $") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        val nr = newRate.toDoubleOrNull()
        val fee = fees.replace(",", "").replace("$", "").toDoubleOrNull() ?: 0.0
        if (nr != null) {
            val c = vm.refinance(debt.balance, debt.aprPct, nr, debt.minPayment, fee, null)
            Spacer(Modifier.height(8.dp))
            Text(
                if (c.netSaving > 0)
                    "Saves ${fullCurrency(c.interestSaved)} interest (${fullCurrency(c.netSaving)} after fees)" +
                        (c.breakEvenMonth?.let { ", breaks even ${it.format(mFmt)}" } ?: "")
                else "Costs ${fullCurrency(-c.netSaving)} more than you'd save — not worth it at these terms.",
                style = MaterialTheme.typography.bodyMedium,
                color = if (c.netSaving > 0) Fiscal.Accent else Fiscal.Coral,
            )
        }
    }
}

@Composable
private fun SensitivityCard(vm: AppViewModel) {
    val debts by vm.debtInputs.collectAsState()
    val extra by vm.extraMonthly.collectAsState()
    val included = debts.filter { it.includeInPlan }.map { Debt(it.accountName, it.balance, it.aprPct, it.minPayment) }
    if (included.isEmpty()) return

    Eyebrow("What most affects your debt-free date")
    FiscalCard {
        // Outcome = months to debt-free. Sweep extra payment, APR, and growth.
        val baseExtra = if (extra > 0) extra else 300.0
        val avgApr = included.map { it.annualRatePct }.average()
        val factors = listOf(
            SensitivityEngine.Factor("Extra payment", baseExtra, baseExtra * 0.5, baseExtra * 1.5),
            SensitivityEngine.Factor("Interest rate", avgApr, avgApr * 0.7, avgApr * 1.3),
            SensitivityEngine.Factor("Payment growth", 0.0, 0.0, 8.0),
        )
        val impacts = SensitivityEngine.analyze(factors) { p ->
            val extraP = p.getValue("Extra payment")
            val aprMult = p.getValue("Interest rate") / avgApr
            val growth = p.getValue("Payment growth")
            val adj = included.map { Debt(it.name, it.balance, it.annualRatePct * aprMult, it.minPayment) }
            val plan = com.financedashboard.core.engine.AmortizationEngine.computePlan(
                adj, com.financedashboard.core.engine.PayoffStrategy.AVALANCHE, extraP, YearMonth.now(), growth,
            )
            (plan.combinedBalanceByMonth.size - 1).toDouble()
        }
        val maxSpread = impacts.maxOfOrNull { it.spread } ?: 1.0
        for (i in impacts) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Text(i.name, style = MaterialTheme.typography.bodySmall, color = Fiscal.TextSecondary, modifier = Modifier.weight(1f))
                Box(Modifier.weight(1.5f).height(10.dp)) {
                    FiscalBar(progress = (i.spread / maxSpread).toFloat(), height = 10.dp, color = Fiscal.Sky, gradient = false)
                }
                Spacer(Modifier.width(8.dp))
                Text("±${i.spread.toInt()} mo", style = MaterialTheme.typography.labelMedium, color = Fiscal.TextPrimary)
            }
        }
        Text(
            "How many months your debt-free date swings as each assumption moves across a plausible range.",
            style = MaterialTheme.typography.labelSmall, color = Fiscal.TextMuted, modifier = Modifier.padding(top = 6.dp),
        )
    }
}
