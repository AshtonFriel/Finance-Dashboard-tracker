package com.financedashboard.core.engine

import com.financedashboard.core.model.AccountType
import com.financedashboard.core.model.BalanceRecord
import com.financedashboard.core.model.TransactionRecord
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Reconciles imported data so silent CSV problems surface before they corrupt
 * net worth and cash flow. Everything here is arithmetic on the rows already
 * being imported — no assumptions about live feeds.
 */
object ImportHealthEngine {

    enum class Severity { INFO, WARNING }

    data class Finding(
        val severity: Severity,
        val title: String,
        val detail: String,
        /** For reconciliation findings: the account and the adjustment that would close the gap. */
        val account: String? = null,
        val suggestedAdjustment: Double? = null,
    )

    data class Report(val findings: List<Finding>) {
        val warnings: Int get() = findings.count { it.severity == Severity.WARNING }
        val isClean: Boolean get() = findings.isEmpty()
    }

    /** Default: an account whose transaction sum and balance delta disagree by more than this fraction is flagged. */
    private const val RECONCILE_TOLERANCE = 0.10
    private const val RECONCILE_MIN_ABSOLUTE = 50.0
    private const val STALE_GAP_DAYS = 45L

    /**
     * @param typeOf classifies accounts so reconciliation only runs where every
     *   change should be explained by a transaction (cash accounts). Investment
     *   and asset accounts move via market/interest with no matching rows, so
     *   flagging them would be a false positive. Defaults to treating everything
     *   as cash for callers that don't classify (e.g. tests).
     */
    fun analyze(
        balances: List<BalanceRecord>,
        transactions: List<TransactionRecord>,
        typeOf: (String) -> AccountType = { AccountType.CASH },
    ): Report {
        val findings = mutableListOf<Finding>()
        findings += reconciliation(balances, transactions, typeOf)
        findings += snapshotGaps(balances)
        findings += duplicateTransactions(transactions)
        // Warnings first, then info; most severe surfaced at the top.
        return Report(findings.sortedByDescending { it.severity == Severity.WARNING })
    }

    private fun reconciliation(
        balances: List<BalanceRecord>,
        transactions: List<TransactionRecord>,
        typeOf: (String) -> AccountType,
    ): List<Finding> {
        val txByAccount = transactions.groupBy { it.account }
        val balByAccount = balances.groupBy { it.account }
        val out = mutableListOf<Finding>()
        for ((account, bals) in balByAccount) {
            // Only cash accounts should reconcile exactly against transactions.
            if (typeOf(account) != AccountType.CASH) continue
            val txs = txByAccount[account] ?: continue
            if (bals.size < 2 || txs.isEmpty()) continue
            val first = bals.minBy { it.date }
            val last = bals.maxBy { it.date }
            if (first.date == last.date) continue
            // Only count transactions inside the balance window.
            val windowTxs = txs.filter { it.date > first.date && it.date <= last.date }
            if (windowTxs.isEmpty()) continue
            val balanceDelta = last.balance - first.balance
            val txSum = windowTxs.sumOf { it.amount }
            val mismatch = abs(balanceDelta - txSum)
            val scale = maxOf(abs(balanceDelta), abs(txSum), 1.0)
            if (mismatch > RECONCILE_MIN_ABSOLUTE && mismatch / scale > RECONCILE_TOLERANCE) {
                out += Finding(
                    Severity.WARNING,
                    "“$account” doesn't reconcile",
                    "Balance moved ${money(balanceDelta)} but transactions sum to ${money(txSum)} " +
                        "(off by ${money(mismatch)}). Some transactions may be missing or miscategorized.",
                    account = account,
                    suggestedAdjustment = balanceDelta - txSum,
                )
            }
        }
        return out
    }

    /** Long gaps between balance snapshots make the net-worth series unreliable. */
    private fun snapshotGaps(balances: List<BalanceRecord>): List<Finding> {
        val dates = balances.map { it.date }.distinct().sorted()
        if (dates.size < 2) return emptyList()
        val biggest = dates.zipWithNext().maxByOrNull { (a, b) -> ChronoUnit.DAYS.between(a, b) } ?: return emptyList()
        val gap = ChronoUnit.DAYS.between(biggest.first, biggest.second)
        return if (gap > STALE_GAP_DAYS) {
            listOf(
                Finding(
                    Severity.INFO,
                    "$gap-day gap in balance history",
                    "No snapshots between ${biggest.first} and ${biggest.second}; net worth is interpolated across it.",
                )
            )
        } else emptyList()
    }

    /**
     * Individual same-day identical rows are common and legitimate (repeated
     * transfers, small recurring charges), so counting them produces noise. The
     * only useful signal is a file that looks *wholesale duplicated* — a large
     * fraction of rows having an exact twin — which indicates a malformed export.
     */
    private const val DUPLICATE_FILE_FRACTION = 0.40

    private fun duplicateTransactions(transactions: List<TransactionRecord>): List<Finding> {
        if (transactions.size < 20) return emptyList()
        val extraCopies = transactions
            .groupBy { listOf(it.account, it.merchant.lowercase().trim(), it.amount, it.date) }
            .values
            .filter { it.size > 1 }
            .sumOf { it.size - 1 }
        val fraction = extraCopies.toDouble() / transactions.size
        return if (fraction >= DUPLICATE_FILE_FRACTION) {
            listOf(
                Finding(
                    Severity.WARNING,
                    "This file may be duplicated",
                    "${(fraction * 100).toInt()}% of rows have an exact same-day twin — the export may " +
                        "contain each transaction twice.",
                )
            )
        } else emptyList()
    }

    private fun money(v: Double): String = (if (v < 0) "-$" else "$") + "%,.0f".format(abs(v))
}
