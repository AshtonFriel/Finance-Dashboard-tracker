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
import com.financedashboard.app.ui.charts.fullCurrency
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
    Card(modifier = modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = chart.secondaryInk)
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (accent == Color.Unspecified) chart.primaryInk else accent,
            )
            sublabel?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = chart.mutedInk)
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(top = 8.dp, bottom = 4.dp),
    )
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
) {
    var values by rememberSaveable(fields) { mutableStateOf(fields.map { it.second }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
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
