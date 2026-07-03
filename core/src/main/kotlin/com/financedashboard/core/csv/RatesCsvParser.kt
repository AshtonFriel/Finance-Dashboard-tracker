package com.financedashboard.core.csv

import com.financedashboard.core.classify.DebtExtractor
import java.io.BufferedReader

/**
 * Parses a small user-supplied rates file so real APRs can be imported (the
 * standard bank exports never contain them). Flexible header: an account/name
 * column, an APR column, and an optional minimum-payment column.
 *
 * Example:
 *   Account,APR,MinPayment
 *   SoFi Personal Loan,9.2,1310
 *   Auto Loan (...9814),5.26,1150
 *   Aidvantage,6.0,150
 */
class RatesCsvParser {

    data class RateEntry(val matcher: String, val aprPct: Double, val minPayment: Double?)

    private val accountKeys = listOf("account", "name", "debt", "loan", "lender")
    private val aprKeys = listOf("apr", "rate", "interest", "interestrate", "apr%")
    private val minKeys = listOf("minpayment", "min", "payment", "minimum", "monthly")

    fun parse(reader: BufferedReader): List<RateEntry> {
        val out = mutableListOf<RateEntry>()
        var header: Map<String, Int>? = null
        var accountIdx = 0
        var aprIdx = 1
        var minIdx = -1
        CsvParser.parse(reader) { row ->
            if (header == null) {
                val norm = row.map { it.trim().lowercase().replace(" ", "").replace("_", "") }
                header = norm.withIndex().associate { (i, name) -> name to i }
                accountIdx = norm.indexOfFirst { h -> accountKeys.any { h.contains(it) } }.takeIf { it >= 0 } ?: 0
                aprIdx = norm.indexOfFirst { h -> aprKeys.any { h.contains(it) } }.takeIf { it >= 0 } ?: 1
                minIdx = norm.indexOfFirst { h -> minKeys.any { h.contains(it) } }
            } else {
                val matcher = row.getOrNull(accountIdx)?.trim().orEmpty()
                val apr = row.getOrNull(aprIdx)?.trim()?.removeSuffix("%")?.toDoubleOrNull()
                val min = if (minIdx >= 0) {
                    row.getOrNull(minIdx)?.trim()?.replace("$", "")?.replace(",", "")?.toDoubleOrNull()
                } else null
                if (matcher.isNotEmpty() && apr != null && apr in 0.0..100.0) {
                    out.add(RateEntry(matcher, apr, min))
                }
            }
        }
        return out
    }

    /**
     * Resolves a rate entry to one of the actual debt account names, matching on
     * exact name, contained last-4, or shared institution token.
     */
    fun matchToAccount(entry: RateEntry, debtAccountNames: List<String>): String? {
        val m = entry.matcher.lowercase().trim()
        debtAccountNames.firstOrNull { it.lowercase() == m }?.let { return it }
        val entryLast4 = DebtExtractor.last4Of(entry.matcher)
        if (entryLast4 != null) {
            debtAccountNames.firstOrNull { DebtExtractor.last4Of(it) == entryLast4 }?.let { return it }
        }
        val entryInstitution = DebtExtractor.institutionOf(entry.matcher)
        if (entryInstitution != null) {
            debtAccountNames.firstOrNull { it.lowercase().contains(entryInstitution) }?.let { return it }
        }
        // Fall back to substring either direction.
        return debtAccountNames.firstOrNull {
            it.lowercase().contains(m) || m.contains(it.lowercase())
        }
    }
}
