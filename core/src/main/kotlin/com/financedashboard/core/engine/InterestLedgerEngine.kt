package com.financedashboard.core.engine

import com.financedashboard.core.classify.DebtExtractor
import com.financedashboard.core.model.BalanceRecord
import com.financedashboard.core.model.TransactionRecord
import java.time.YearMonth
import kotlin.math.abs

/**
 * Estimates interest actually paid to date on each debt, from its balance
 * history and the payments matched to it. For any month:
 *
 *   interest = payment − principalReduction
 *
 * where principalReduction = (how much the amount owed fell). It's an estimate
 * (payment matching and snapshot timing aren't perfect), so it's presented as
 * "interest paid so far", not an exact figure.
 */
object InterestLedgerEngine {

    data class DebtInterest(
        val account: String,
        val interestPaid: Double,
        val principalPaid: Double,
        val monthsObserved: Int,
    )

    fun compute(
        debtAccounts: List<String>,
        balances: List<BalanceRecord>,
        transactions: List<TransactionRecord>,
    ): List<DebtInterest> {
        val payments = transactions.filter { it.amount < 0 && it.category in PaymentInference.PAYMENT_CATEGORIES }
        return debtAccounts.mapNotNull { account ->
            val series = balances.filter { it.account == account }
                .groupBy { YearMonth.from(it.date) }
                .mapValues { (_, rows) -> rows.maxBy { it.date }.balance }
                .toSortedMap()
            if (series.size < 2) return@mapNotNull null

            val institution = DebtExtractor.institutionOf(account)
            val paymentByMonth = payments
                .filter { tx ->
                    val m = DebtExtractor.institutionOf(tx.merchant)
                    m != null && (account.lowercase().contains(m) || (institution != null && tx.merchant.lowercase().contains(institution)))
                }
                .groupBy { YearMonth.from(it.date) }
                .mapValues { (_, txs) -> txs.sumOf { -it.amount } }

            var interest = 0.0
            var principal = 0.0
            var months = 0
            val entries = series.entries.toList()
            for (i in 1 until entries.size) {
                val prevOwed = -entries[i - 1].value  // debts stored negative → owed positive
                val curOwed = -entries[i].value
                val pay = paymentByMonth[entries[i].key] ?: continue
                val principalReduction = prevOwed - curOwed
                val monthInterest = (pay - principalReduction).coerceAtLeast(0.0)
                interest += monthInterest
                principal += principalReduction.coerceAtLeast(0.0)
                months++
            }
            if (months == 0) null
            else DebtInterest(account, interest, principal, months)
        }.filter { it.interestPaid > 0.5 }
    }

    fun totalInterestPaid(ledger: List<DebtInterest>): Double = ledger.sumOf { it.interestPaid }
}
