package com.financedashboard.core.engine

import com.financedashboard.core.model.TransactionRecord

/**
 * Derives net income per calendar year from paycheck-category transactions.
 * Net deposits are confounded by withholding and retirement-contribution
 * changes, so the app also lets users enter gross annual earnings manually
 * (e.g. from an SSA statement); manual entries take precedence.
 */
object IncomeAggregator {

    val PAYCHECK_CATEGORIES = setOf("Paychecks", "Paycheck")

    fun netPaycheckIncomeByYear(transactions: List<TransactionRecord>): Map<Int, Double> =
        transactions
            .filter { it.category in PAYCHECK_CATEGORIES && it.amount > 0 }
            .groupBy { it.date.year }
            .mapValues { (_, txs) -> txs.sumOf { it.amount } }

    /** Manual gross entries override derived net figures for the same year. */
    fun mergedIncomeByYear(
        derivedNet: Map<Int, Double>,
        manualGross: Map<Int, Double>,
    ): Map<Int, Double> = derivedNet + manualGross
}
