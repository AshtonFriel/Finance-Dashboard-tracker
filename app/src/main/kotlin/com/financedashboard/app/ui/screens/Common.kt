package com.financedashboard.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import com.financedashboard.app.AppViewModel
import com.financedashboard.app.ui.charts.fullCurrency
import com.financedashboard.app.ui.theme.Fiscal
import com.financedashboard.app.ui.theme.LocalChartColors

@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = Color.Unspecified,
    sublabel: String? = null,
) {
    val chart = LocalChartColors.current
    FiscalCard(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = chart.secondaryInk)
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            color = if (accent == Color.Unspecified) chart.primaryInk else accent,
        )
        sublabel?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = chart.mutedInk)
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Eyebrow(text, modifier = modifier.padding(top = 10.dp, bottom = 2.dp))
}

/**
 * "Since last import" summary, shown once after an import that actually changed
 * something. Turns a data replace into a short statement of what moved.
 */
@Composable
fun ImportDigestDialog(vm: AppViewModel) {
    val digest by vm.importDigest.collectAsState()
    val d = digest ?: return
    if (!d.hasChanges) return

    // Debts stored positive: a drop is a paydown (good). Net worth up is good.
    fun signColor(delta: Double, goodWhenUp: Boolean): Color {
        if (kotlin.math.abs(delta) < 0.5) return Fiscal.TextSecondary
        val up = delta > 0
        return if (up == goodWhenUp) Fiscal.Accent else Fiscal.Coral
    }
    AlertDialog(
        onDismissRequest = { vm.dismissImportDigest() },
        title = { Text("Since your last import") },
        text = {
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                if (d.newTransactions > 0) {
                    Text("${d.newTransactions} new transactions imported",
                        style = MaterialTheme.typography.bodyMedium, color = Fiscal.TextPrimary)
                }
                DigestRow("Net worth", d.netWorthDelta, signColor(d.netWorthDelta, goodWhenUp = true))
                DigestRow("Debt", d.debtDelta, signColor(d.debtDelta, goodWhenUp = false))
                DigestRow("Liquid cash", d.cashDelta, signColor(d.cashDelta, goodWhenUp = true))
                if (d.daysSincePrevious > 0) {
                    Text("${d.daysSincePrevious} days since your last import",
                        style = MaterialTheme.typography.labelSmall, color = Fiscal.TextMuted)
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.dismissImportDigest() }) { Text("Got it") } },
    )
}

@Composable
private fun DigestRow(label: String, delta: Double, color: Color) {
    if (kotlin.math.abs(delta) < 0.5) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Fiscal.TextPrimary)
        Text((if (delta >= 0) "+" else "") + fullCurrency(delta),
            style = MaterialTheme.typography.bodyMedium, color = color, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun TableRow(cells: List<String>, weights: List<Float>, emphasize: Boolean = false, color: Color = Color.Unspecified) {
    val chart = LocalChartColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        cells.forEachIndexed { i, cell ->
            Text(
                cell,
                style = if (emphasize) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodySmall,
                fontWeight = if (emphasize) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    color != Color.Unspecified && i == cells.lastIndex -> color
                    emphasize -> chart.primaryInk
                    else -> chart.secondaryInk
                },
                modifier = Modifier.weight(weights.getOrElse(i) { 1f }),
            )
        }
    }
}

/** Signed currency with good/critical coloring for gap columns. */
@Composable
fun gapColor(v: Double): Color {
    val chart = LocalChartColors.current
    return if (v >= 0) chart.good else chart.critical
}

fun signedCurrency(v: Double): String = (if (v >= 0) "+" else "") + fullCurrency(v)

/** Numeric-entry dialog used for APR, payment, income, and CPI edits. */
@Composable
fun NumberEntryDialog(
    title: String,
    fields: List<Pair<String, String>>,
    onConfirm: (List<Double>) -> Unit,
    onDismiss: () -> Unit,
    note: String? = null,
) {
    val chart = LocalChartColors.current
    var values by rememberSaveable(fields) { mutableStateOf(fields.map { it.second }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                note?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = chart.secondaryInk, modifier = Modifier.padding(bottom = 6.dp))
                }
                fields.forEachIndexed { i, (label, _) ->
                    OutlinedTextField(
                        value = values[i],
                        onValueChange = { new -> values = values.toMutableList().also { it[i] = new } },
                        label = { Text(label) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsed = values.map { it.replace(",", "").replace("$", "").trim().toDoubleOrNull() }
                if (parsed.all { it != null }) onConfirm(parsed.map { it!! })
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
