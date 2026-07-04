package com.financedashboard.core.engine

import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * "Safe to spend" this month: typical monthly income, minus committed recurring
 * charges and planned debt/savings transfers, minus what's already gone out on
 * discretionary spending. Closes the loop between subscription detection and a
 * usable number.
 */
object SafeToSpendEngine {

    data class Result(
        val typicalIncome: Double,
        val committedRecurring: Double,
        val plannedTransfers: Double,
        val spentSoFar: Double,
        val safeToSpend: Double,
    )

    fun compute(
        typicalMonthlyIncome: Double,
        committedRecurring: Double,
        plannedDebtAndSavings: Double,
        discretionarySpentThisMonth: Double,
    ): Result {
        val safe = typicalMonthlyIncome - committedRecurring - plannedDebtAndSavings - discretionarySpentThisMonth
        return Result(
            typicalIncome = typicalMonthlyIncome,
            committedRecurring = committedRecurring,
            plannedTransfers = plannedDebtAndSavings,
            spentSoFar = discretionarySpentThisMonth,
            safeToSpend = safe,
        )
    }
}

/**
 * Monte Carlo retirement simulation: instead of fixed optimistic/pessimistic
 * bands, run many paths with monthly returns drawn from a normal distribution
 * (user-editable mean and volatility) and report the outcome distribution and
 * the probability of reaching a goal. Deterministic given a seed for testing.
 */
object MonteCarloEngine {

    data class Result(
        val runs: Int,
        val years: Int,
        val p10: Double,
        val p50: Double,
        val p90: Double,
        /** Probability the ending balance meets or beats the goal (0..1). */
        val probabilityOfGoal: Double,
        /** Median balance at each year, for charting. */
        val medianByYear: List<Double>,
    )

    fun simulate(
        principal: Double,
        monthlyContribution: Double,
        annualReturnPct: Double,
        annualVolatilityPct: Double,
        years: Int,
        goal: Double,
        runs: Int = 1000,
        seed: Long = 42L,
    ): Result {
        val rng = Random(seed)
        val monthlyMean = annualReturnPct / 100.0 / 12.0
        val monthlyVol = annualVolatilityPct / 100.0 / sqrt(12.0)
        val n = years * 12

        val endings = DoubleArray(runs)
        // Collect balances at each year boundary across runs for medians.
        val yearlyBalances = Array(years + 1) { DoubleArray(runs) }

        for (r in 0 until runs) {
            var balance = principal
            yearlyBalances[0][r] = balance
            for (month in 1..n) {
                val shock = gaussian(rng) * monthlyVol + monthlyMean
                balance = balance * (1 + shock) + monthlyContribution
                if (month % 12 == 0) yearlyBalances[month / 12][r] = balance
            }
            endings[r] = balance
        }

        endings.sort()
        val hits = endings.count { it >= goal }
        val medianByYear = (0..years).map { y -> percentile(yearlyBalances[y].sortedArray(), 0.5) }

        return Result(
            runs = runs,
            years = years,
            p10 = percentile(endings, 0.10),
            p50 = percentile(endings, 0.50),
            p90 = percentile(endings, 0.90),
            probabilityOfGoal = hits.toDouble() / runs,
            medianByYear = medianByYear,
        )
    }

    private fun percentile(sorted: DoubleArray, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val idx = (p * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    // Box–Muller standard normal.
    private fun gaussian(rng: Random): Double {
        var u1 = rng.nextDouble(); var u2 = rng.nextDouble()
        if (u1 < 1e-12) u1 = 1e-12
        return sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
    }
}

/**
 * Roth vs. traditional contribution comparison. Roth is taxed now (at the
 * marginal rate) then grows tax-free; traditional is deducted now but taxed at
 * withdrawal (at the expected retirement rate). Compares the after-tax value of
 * the SAME gross contribution.
 */
object RothVsTraditionalEngine {

    data class Result(
        val grossContribution: Double,
        val years: Int,
        val rothAfterTax: Double,
        val traditionalAfterTax: Double,
        val rothWins: Boolean,
    )

    fun compare(
        annualGrossContribution: Double,
        currentMarginalRatePct: Double,
        retirementRatePct: Double,
        annualReturnPct: Double,
        years: Int,
    ): Result {
        val growth = (1 + annualReturnPct / 100.0).pow(years)

        // Roth: contribute after-tax now, grows tax-free, withdraw tax-free.
        val rothContribution = annualGrossContribution * (1 - currentMarginalRatePct / 100.0)
        val roth = rothContribution * growth

        // Traditional: contribute the full gross (pre-tax), grows, taxed at withdrawal.
        val traditional = annualGrossContribution * growth * (1 - retirementRatePct / 100.0)

        return Result(
            grossContribution = annualGrossContribution,
            years = years,
            rothAfterTax = roth,
            traditionalAfterTax = traditional,
            rothWins = roth >= traditional,
        )
    }
}
