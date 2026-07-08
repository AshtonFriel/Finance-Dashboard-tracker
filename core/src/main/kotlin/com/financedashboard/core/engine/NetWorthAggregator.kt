package com.financedashboard.core.engine

import com.financedashboard.core.classify.DebtExtractor
import com.financedashboard.core.model.BalanceRecord
import java.time.YearMonth

/**
 * Month-end assets and debts across all accounts, following the same rules as
 * debt identification so totals agree across the app:
 *
 * - Balance sign, not account type, decides the side.
 * - Within an account's life, missing months carry the last known balance
 *   forward (sparse manual snapshots).
 * - An account stops contributing [GRACE_MONTHS] after its last snapshot:
 *   exports stop producing rows for closed accounts, so an old balance must
 *   not be carried forward forever.
 * - A suspected-duplicate account (per [DebtExtractor]) stops contributing
 *   the month its replacement starts reporting, so relinked loans are never
 *   double-counted while their genuine pre-link history still counts.
 */
object NetWorthAggregator {

    const val GRACE_MONTHS = 2L

    data class MonthlyNetWorth(
        val month: YearMonth,
        val assets: Double,
        val debts: Double,
    ) {
        val net: Double get() = assets - debts
    }

    fun monthlySeries(balances: List<BalanceRecord>): List<MonthlyNetWorth> {
        if (balances.isEmpty()) return emptyList()

        // Last snapshot per (account, month).
        val perAccountMonth = balances
            .groupBy { it.account to YearMonth.from(it.date) }
            .mapValues { (_, rows) -> rows.maxBy { it.date }.balance }

        val lastMonthOf = balances.groupBy { it.account }
            .mapValues { (_, rows) -> YearMonth.from(rows.maxBy { it.date }.date) }
        val firstMonthOf = balances.groupBy { it.account }
            .mapValues { (_, rows) -> YearMonth.from(rows.minBy { it.date }.date) }

        // A duplicate stops counting when the account it duplicates starts.
        val suppressFrom: Map<String, YearMonth> = DebtExtractor.extract(balances)
            .needsReview
            .filter { it.reason == DebtExtractor.ReviewReason.SUSPECTED_DUPLICATE && it.duplicateOf != null }
            .mapNotNull { r -> firstMonthOf[r.duplicateOf]?.let { r.candidate.accountName to it } }
            .toMap()

        val allMonths = perAccountMonth.keys.map { it.second }
        val months = generateSequence(allMonths.min()) { it.plusMonths(1) }
            .takeWhile { it <= allMonths.max() }
            .toList()
        val accountNames = balances.map { it.account }.distinct()

        val lastKnown = mutableMapOf<String, Double>()
        return months.map { m ->
            var assets = 0.0
            var debts = 0.0
            for (name in accountNames) {
                perAccountMonth[name to m]?.let { lastKnown[name] = it }
                val bal = lastKnown[name] ?: continue
                val closed = m > (lastMonthOf.getValue(name)).plusMonths(GRACE_MONTHS)
                val suppressed = suppressFrom[name]?.let { m >= it } == true
                if (closed || suppressed) continue
                if (bal >= 0) assets += bal else debts += -bal
            }
            MonthlyNetWorth(m, assets, debts)
        }
    }

    /**
     * Year-over-year net-worth momentum, computed like-for-like: only accounts
     * with a snapshot on or before BOTH the current date and one year earlier
     * are compared. An account linked partway through the year (a loan or
     * brokerage the user connected mid-window) would otherwise appear as a
     * sudden loss or gain and distort the trend — here it is simply excluded
     * from the comparison until it has a full year of its own history.
     *
     * Returns the fractional change (0.1 = +10%), or null when there isn't a
     * year of history or no account spans both endpoints.
     */
    fun comparableYoYMomentum(balances: List<BalanceRecord>, asOf: java.time.LocalDate): Double? {
        if (balances.isEmpty()) return null
        val yearAgo = asOf.minusYears(1)
        if (balances.minOf { it.date }.isAfter(yearAgo)) return null

        fun snapshotAsOf(d: java.time.LocalDate): Map<String, Double> =
            balances.filter { !it.date.isAfter(d) }
                .groupBy { it.account }
                .mapValues { (_, rows) -> rows.maxBy { it.date }.balance }

        val now = snapshotAsOf(asOf)
        val then = snapshotAsOf(yearAgo)
        val common = now.keys intersect then.keys
        if (common.isEmpty()) return null
        val nowNet = common.sumOf { now.getValue(it) }
        val thenNet = common.sumOf { then.getValue(it) }
        if (kotlin.math.abs(thenNet) < 0.005) return null
        return (nowNet - thenNet) / kotlin.math.abs(thenNet)
    }
}
