package com.financedashboard.core.engine

import com.financedashboard.core.model.TransactionRecord
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Detects recurring charges (subscriptions, memberships, regular bills) from
 * transaction history so the user can see their fixed monthly commitment.
 *
 * A merchant is "recurring" when it has enough charges at a stable amount and a
 * consistent cadence. Amounts are normalized to a monthly-equivalent cost so a
 * yearly membership and a weekly charge can be compared and summed.
 */
object RecurringDetector {

    enum class Cadence(val label: String, val perMonth: Double, val lowDays: Long, val highDays: Long) {
        WEEKLY("weekly", 4.345, 6, 8),
        BIWEEKLY("every 2 weeks", 2.17, 13, 16),
        MONTHLY("monthly", 1.0, 26, 35),
        QUARTERLY("quarterly", 1.0 / 3, 80, 100),
        ANNUAL("yearly", 1.0 / 12, 350, 380),
    }

    data class Subscription(
        val merchant: String,
        val cadence: Cadence,
        val typicalAmount: Double,
        val monthlyEquivalent: Double,
        val occurrences: Int,
        val lastCharge: LocalDate,
        /** No charge within 1.5x the expected interval → likely ended. */
        val possiblyCancelled: Boolean,
        /** Earliest charge amount in the detection window, for price-creep. */
        val earliestAmount: Double,
        /** Fractional change from earliest to current amount (0.2 = +20%). */
        val priceChangePct: Double,
    )

    private val transferCategories = setOf("Transfer", "Credit Card Payment", "Loan Repayment", "Paychecks", "Paycheck")

    /** Strip trailing store numbers / city fragments so "Store #123" and "Store" group together. */
    fun normalizeMerchant(raw: String): String =
        raw.lowercase()
            .replace(Regex("""#?\d[\d\-*]*"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    fun detect(
        transactions: List<TransactionRecord>,
        asOf: LocalDate,
        minOccurrences: Int = 3,
        /** Only charges within this many months of [asOf] count, so long-dead
         *  merchants don't surface. Wide enough to catch 3 annual cycles. */
        windowMonths: Long = 30,
    ): List<Subscription> {
        val cutoff = asOf.minusMonths(windowMonths)
        val expenses = transactions.filter {
            it.amount < 0 && it.category !in transferCategories && it.date >= cutoff
        }
        return expenses
            .groupBy { normalizeMerchant(it.merchant) }
            .mapNotNull { (_, txs) -> subscriptionFor(txs, asOf, minOccurrences) }
            .sortedByDescending { it.monthlyEquivalent }
    }

    private fun subscriptionFor(
        txs: List<TransactionRecord>,
        asOf: LocalDate,
        minOccurrences: Int,
    ): Subscription? {
        if (txs.size < minOccurrences) return null
        val sorted = txs.sortedBy { it.date }

        // Amount stability and cadence are judged on the most recent charges so
        // a subscription whose price rose over the years (measured against its
        // current price) still qualifies, while variable spending (pharmacy
        // runs, fast food) is excluded. Old price tiers don't poison the median.
        val recent = sorted.takeLast(12)
        val amounts = recent.map { abs(it.amount) }
        val medianAmount = amounts.sorted()[amounts.size / 2]
        if (medianAmount < 0.5) return null
        val within = amounts.count { abs(it - medianAmount) / medianAmount <= 0.12 }
        if (within.toDouble() / amounts.size < 0.65 || within < minOf(minOccurrences, amounts.size)) return null

        // Cadence from the median gap between recent consecutive charges.
        val gaps = recent.zipWithNext { a, b -> ChronoUnit.DAYS.between(a.date, b.date) }.filter { it > 0 }
        if (gaps.size < minOccurrences - 1) return null
        val medianGap = gaps.sorted()[gaps.size / 2]
        val cadence = Cadence.entries.firstOrNull { medianGap in it.lowDays..it.highDays } ?: return null

        val last = sorted.last().date
        val daysSince = ChronoUnit.DAYS.between(last, asOf)
        val earliest = abs(sorted.first().amount)
        return Subscription(
            merchant = sorted.last().merchant,
            cadence = cadence,
            typicalAmount = medianAmount,
            monthlyEquivalent = medianAmount * cadence.perMonth,
            occurrences = sorted.size,
            lastCharge = last,
            possiblyCancelled = daysSince > cadence.highDays * 1.5,
            earliestAmount = earliest,
            priceChangePct = if (earliest > 0.005) (medianAmount - earliest) / earliest else 0.0,
        )
    }

    /** Subscriptions whose current price is meaningfully above their earliest in-window price. */
    fun priceHikes(subscriptions: List<Subscription>, minIncrease: Double = 0.10): List<Subscription> =
        subscriptions.filter { !it.possiblyCancelled && it.priceChangePct >= minIncrease }
            .sortedByDescending { it.priceChangePct }

    /** Total monthly commitment from still-active subscriptions. */
    fun monthlyTotal(subscriptions: List<Subscription>): Double =
        subscriptions.filter { !it.possiblyCancelled }.sumOf { it.monthlyEquivalent }
}
