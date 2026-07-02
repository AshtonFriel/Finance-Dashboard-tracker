package com.financedashboard.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.financedashboard.app.ui.theme.LocalChartColors
import com.financedashboard.core.engine.InflationEngine

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val chart = LocalChartColors.current
    val status by vm.importStatus.collectAsState()
    val cpi by vm.cpiTable.collectAsState()
    val txCount by vm.transactionCount.collectAsState()
    var editCpiYear by remember { mutableStateOf<Int?>(null) }
    var confirmWipe by remember { mutableStateOf(false) }

    val balancesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importBalances(it) }
    }
    val transactionsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importTransactions(it) }
    }
    val csvTypes = arrayOf("text/csv", "text/comma-separated-values", "application/csv", "text/plain")

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

        SectionTitle("Import data")
        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Import CSV exports (Date,Balance,Account and the standard transactions format). " +
                        "Each import replaces the previous one — exports are cumulative, so always import the newest file.",
                    style = MaterialTheme.typography.bodySmall,
                    color = chart.secondaryInk,
                )
                Button(onClick = { balancesPicker.launch(csvTypes) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Import Balances CSV")
                }
                Button(onClick = { transactionsPicker.launch(csvTypes) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Import Transactions CSV")
                }
                status?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = chart.primaryInk)
                }
                Text(
                    "$txCount transactions stored on this device.",
                    style = MaterialTheme.typography.labelSmall,
                    color = chart.mutedInk,
                )
            }
        }

        SectionTitle("CPI table (annual averages)")
        Card {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "Bundled from public BLS CPI-U data. Tap a year to override; overrides are marked •",
                    style = MaterialTheme.typography.bodySmall,
                    color = chart.secondaryInk,
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = chart.gridline)
                for ((year, value) in cpi.toSortedMap().entries.reversed()) {
                    val overridden = InflationEngine.DEFAULT_CPI[year] != value
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "$year${if (overridden) " •" else ""}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text("%.3f".format(value), style = MaterialTheme.typography.bodyMedium, color = chart.secondaryInk)
                        TextButton(onClick = { editCpiYear = year }) { Text("Edit") }
                    }
                }
                OutlinedButton(onClick = { editCpiYear = -1 }) { Text("Add year") }
            }
        }

        SectionTitle("Privacy")
        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "All data stays in a local database on this device. The app makes no network calls.",
                    style = MaterialTheme.typography.bodySmall,
                    color = chart.secondaryInk,
                )
                OutlinedButton(
                    onClick = { confirmWipe = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Delete all imported data", color = chart.critical)
                }
            }
        }
    }

    editCpiYear?.let { year ->
        NumberEntryDialog(
            title = if (year == -1) "Add CPI year" else "Override CPI for $year",
            fields = if (year == -1) {
                listOf("Year" to "", "CPI index (annual avg)" to "")
            } else {
                listOf("CPI index (annual avg)" to (cpi[year]?.toString() ?: ""))
            },
            onConfirm = { values ->
                if (year == -1) vm.setCpiOverride(values[0].toInt(), values[1])
                else vm.setCpiOverride(year, values[0])
                editCpiYear = null
            },
            onDismiss = { editCpiYear = null },
        )
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("Delete all data?") },
            text = { Text("Removes every imported balance, transaction, and account from this device. Manual income entries and CPI overrides are kept.") },
            confirmButton = {
                TextButton(onClick = { vm.wipeAll(); confirmWipe = false }) { Text("Delete", color = chart.critical) }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Cancel") } },
        )
    }
}
