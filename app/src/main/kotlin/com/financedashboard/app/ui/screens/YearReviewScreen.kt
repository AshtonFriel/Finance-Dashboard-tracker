package com.financedashboard.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.financedashboard.app.AppViewModel
import com.financedashboard.app.ui.charts.fullCurrency
import com.financedashboard.app.ui.theme.Fiscal
import java.time.LocalDate

@Composable
fun YearReviewScreen(vm: AppViewModel) {
    val review by vm.yearReview.collectAsState()
    val year by vm.reviewYear.collectAsState()
    val thisYear = LocalDate.now().year

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Year in Review", style = MaterialTheme.typography.headlineSmall, color = Fiscal.TextPrimary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (y in (thisYear - 1 downTo thisYear - 5)) {
                FilterChip(selected = year == y, onClick = { vm.setReviewYear(y) }, label = { Text(y.toString()) })
            }
        }

        val r = review
        if (r == null || (r.income == 0.0 && r.spending == 0.0)) {
            FiscalCard { Text("No data for $year yet.", style = MaterialTheme.typography.bodyMedium, color = Fiscal.TextSecondary) }
            return@Column
        }

        HeroCard {
            Eyebrow("Net worth ${r.year}")
            Text("${fullCurrency(r.netWorthStart)} → ${fullCurrency(r.netWorthEnd)}", style = MaterialTheme.typography.titleLarge, color = Fiscal.TextPrimary)
            Text(signedCurrency(r.netWorthEnd - r.netWorthStart) + " over the year",
                style = MaterialTheme.typography.bodySmall,
                color = if (r.netWorthEnd >= r.netWorthStart) Fiscal.Accent else Fiscal.Coral)
        }

        FiscalCard {
            Eyebrow("Cash flow")
            Spacer(Modifier.height(6.dp))
            StatRow("Income", fullCurrency(r.income), Fiscal.Accent)
            StatRow("Spending", fullCurrency(r.spending), Fiscal.Coral)
            StatRow("Savings rate", "${r.savingsRate.toInt()}%", Fiscal.TextPrimary)
        }

        r.attribution?.let { a ->
            FiscalCard {
                Eyebrow("What moved your net worth")
                Spacer(Modifier.height(6.dp))
                StatRow("Saved from income", signedCurrency(a.cashSaved), Fiscal.Accent)
                StatRow("Market growth", signedCurrency(a.marketChange), Fiscal.Sky)
                StatRow("Debt paid down", signedCurrency(a.debtPrincipalPaid), Fiscal.Coral)
                if (kotlin.math.abs(a.residual) > 1) StatRow("Unexplained", signedCurrency(a.residual), Fiscal.TextMuted)
            }
        }

        FiscalCard {
            Eyebrow("Top spending categories")
            Spacer(Modifier.height(6.dp))
            for ((cat, amt) in r.topCategories) StatRow(cat, fullCurrency(amt), Fiscal.TextPrimary)
        }

        Button(onClick = { vm.exportYearReviewPdf() }, modifier = Modifier.fillMaxWidth()) {
            Text("Export PDF")
        }
        Text(
            "The PDF is generated entirely on this device and shared via your chosen app — nothing leaves the phone until you send it.",
            style = MaterialTheme.typography.labelSmall, color = Fiscal.TextMuted,
        )
    }
}

@Composable
private fun StatRow(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Fiscal.TextSecondary, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelLarge, color = color)
    }
}
