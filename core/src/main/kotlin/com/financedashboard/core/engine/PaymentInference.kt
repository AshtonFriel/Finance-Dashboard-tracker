package com.financedashboard.core.engine

import com.financedashboard.core.classify.DebtExtractor
import com.financedashboard.core.model.TransactionRecord
import java.time.YearMonth
import kotlin.math.abs

/**
 * Infers a debt's real minimum monthly payment from actual payment history.
 * Balance-trajectory APR inference is unreliable when loans are only recently
 * linked (little history), but the size of recent payments to a lender is a
 * solid signal, so we derive payments here and leave APR to explicit import.
 */
object PaymentInference {

    val PAYMENT_CATEGORIES = setOf("Loan Repayment", "Auto Payment", "Student Loans", "Credit Card Payment")

    data class InferredPayment(
        val debtAccountName: String,
        val monthlyPayment: Double,
        val monthsObserved: Int,
    )

    /**
     * Matches payment transactions to debts by institution token (a payment to
     * "SoFi" matches the "SoFi Personal Loan" account) and returns the smallest
     * of the most recent monthly totals — the required minimum, since months
     * with extra payments sit above it. Debts whose accounts are generically
     * named (e.g. "Auto Loan (...9814)") won't match and are simply omitted;
     * they fall back to an amortization-based default instead. Category-based
     * matching was tried and rejected: real exports mis-file unrelated charges
     * under payment categories, so it produced spurious minimums.
     */
    fun inferMonthlyPayments(
        debtAccountNames: List<String>,
        transactions: List<TransactionRecord>,
        recentMonths: Int = 6,
    ): List<InferredPayment> {
        val payments = transactions.filter {
            it.amount < 0 && it.category in PAYMENT_CATEGORIES
        }
        return debtAccountNames.mapNotNull { debtName ->
            val debtInstitution = DebtExtractor.institutionOf(debtName) ?: return@mapNotNull null
            val matched = payments.filter { tx ->
                val merchantInstitution = DebtExtractor.institutionOf(tx.merchant)
                merchantInstitution != null &&
                    (debtName.lowercase().contains(merchantInstitution) ||
                        tx.merchant.lowercase().contains(debtInstitution))
            }
            if (matched.isEmpty()) return@mapNotNull null

            // Sum per month, then take the floor (smallest recent monthly total).
            // Months where only the required payment went out reveal the minimum;
            // months with extra payments sit above it, so the minimum monthly
            // sum is the most honest estimate of the required payment.
            val monthlySums = matched
                .groupBy { YearMonth.from(it.date) }
                .mapValues { (_, txs) -> txs.sumOf { abs(it.amount) } }
                .toSortedMap()
            val recent = monthlySums.values.toList().takeLast(recentMonths)
            if (recent.isEmpty()) return@mapNotNull null

            InferredPayment(
                debtAccountName = debtName,
                monthlyPayment = recent.min(),
                monthsObserved = recent.size,
            )
        }
    }
}
