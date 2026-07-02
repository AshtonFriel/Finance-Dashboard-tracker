package com.financedashboard.core.csv

import com.financedashboard.core.model.BalanceRecord
import com.financedashboard.core.model.TransactionRecord
import java.io.BufferedReader
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Minimal RFC-4180 CSV reader: quoted fields, embedded commas, doubled quotes,
 * and newlines inside quotes. Streams row by row so large exports never load
 * fully into memory.
 */
object CsvParser {

    fun parse(reader: BufferedReader, onRow: (List<String>) -> Unit) {
        val field = StringBuilder()
        val row = mutableListOf<String>()
        var inQuotes = false
        var c = reader.read()
        while (c != -1) {
            val ch = c.toChar()
            if (inQuotes) {
                when (ch) {
                    '"' -> {
                        val next = reader.read()
                        if (next == '"'.code) field.append('"')
                        else {
                            inQuotes = false
                            c = next
                            continue
                        }
                    }
                    else -> field.append(ch)
                }
            } else {
                when (ch) {
                    '"' -> inQuotes = true
                    ',' -> { row.add(field.toString()); field.setLength(0) }
                    '\r' -> { /* swallow; \n closes the row */ }
                    '\n' -> {
                        row.add(field.toString()); field.setLength(0)
                        if (row.any { it.isNotBlank() }) onRow(row.toList())
                        row.clear()
                    }
                    else -> field.append(ch)
                }
            }
            c = reader.read()
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            if (row.any { it.isNotBlank() }) onRow(row.toList())
        }
    }
}

class BalancesCsvParser {
    /** Expects header: Date,Balance,Account */
    fun parse(reader: BufferedReader): List<BalanceRecord> {
        val out = mutableListOf<BalanceRecord>()
        var header: Map<String, Int>? = null
        CsvParser.parse(reader) { row ->
            if (header == null) {
                header = row.withIndex().associate { (i, name) -> name.trim() to i }
            } else {
                val h = header!!
                val date = row.getOrNull(h["Date"] ?: 0)?.toLocalDateOrNull() ?: return@parse
                val balance = row.getOrNull(h["Balance"] ?: 1)?.toDoubleOrNull() ?: return@parse
                val account = row.getOrNull(h["Account"] ?: 2)?.trim().orEmpty()
                if (account.isNotEmpty()) out.add(BalanceRecord(date, balance, account))
            }
        }
        return out
    }
}

class TransactionsCsvParser {
    /** Expects header: Date,Merchant,Category,Account,Original Statement,Notes,Amount,Tags,Owner,Reviewed */
    fun parse(reader: BufferedReader): List<TransactionRecord> {
        val out = mutableListOf<TransactionRecord>()
        var header: Map<String, Int>? = null
        CsvParser.parse(reader) { row ->
            if (header == null) {
                header = row.withIndex().associate { (i, name) -> name.trim() to i }
            } else {
                val h = header!!
                fun col(name: String): String = h[name]?.let { row.getOrNull(it) }?.trim().orEmpty()
                val date = col("Date").toLocalDateOrNull() ?: return@parse
                val amount = col("Amount").toDoubleOrNull() ?: return@parse
                out.add(
                    TransactionRecord(
                        date = date,
                        merchant = col("Merchant"),
                        category = col("Category"),
                        account = col("Account"),
                        statement = col("Original Statement"),
                        notes = col("Notes"),
                        amount = amount,
                        tags = col("Tags"),
                        owner = col("Owner"),
                    )
                )
            }
        }
        return out
    }
}

private fun String.toLocalDateOrNull(): LocalDate? =
    try { LocalDate.parse(trim()) } catch (_: DateTimeParseException) { null }
