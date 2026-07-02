package com.financedashboard.core.engine

/**
 * Emergency-fund analysis: how long liquid cash lasts at the observed spending
 * rate, and how long until a months-of-expenses target is funded at a given
 * monthly saving rate (with optional yield on the cash).
 */
object EmergencyFundEngine {

    data class State(
        val liquidCash: Double,
        val avgMonthlyExpenses: Double,
        /** How many months current cash covers. */
        val runwayMonths: Double,
        val targetMonths: Int,
        val targetAmount: Double,
        /** targetAmount - liquidCash, floored at zero. */
        val gap: Double,
        /** 0..1 */
        val progress: Double,
        /** Months of saving until the target is reached; null if never (saving <= 0 and gap > 0). */
        val monthsToTarget: Int?,
        /** Projected balance month by month from now until target (or [maxProjectionMonths]). */
        val projection: List<Double>,
    )

    const val MAX_PROJECTION_MONTHS = 120

    fun compute(
        liquidCash: Double,
        avgMonthlyExpenses: Double,
        targetMonths: Int,
        monthlySaving: Double,
        annualYieldPct: Double = 0.0,
    ): State {
        val cash = liquidCash.coerceAtLeast(0.0)
        val expenses = avgMonthlyExpenses.coerceAtLeast(0.0)
        val target = expenses * targetMonths
        val gap = (target - cash).coerceAtLeast(0.0)
        val monthlyRate = annualYieldPct / 100.0 / 12.0

        val projection = mutableListOf(cash)
        var balance = cash
        var monthsToTarget: Int? = if (gap <= 0.005) 0 else null
        var m = 0
        while (monthsToTarget == null && m < MAX_PROJECTION_MONTHS) {
            m++
            balance = balance * (1 + monthlyRate) + monthlySaving
            projection.add(balance)
            if (balance >= target - 0.005) monthsToTarget = m
            if (monthlySaving <= 0.005 && monthlyRate <= 0.0) break
        }

        return State(
            liquidCash = cash,
            avgMonthlyExpenses = expenses,
            runwayMonths = if (expenses > 0.005) cash / expenses else 0.0,
            targetMonths = targetMonths,
            targetAmount = target,
            gap = gap,
            progress = if (target > 0.005) (cash / target).coerceIn(0.0, 1.0) else 0.0,
            monthsToTarget = monthsToTarget,
            projection = projection,
        )
    }

    /** Average of full-month expense totals; ignores months with no spending data. */
    fun averageMonthlyExpenses(monthTotals: List<Double>): Double {
        val nonEmpty = monthTotals.filter { it > 0.005 }
        return if (nonEmpty.isEmpty()) 0.0 else nonEmpty.sum() / nonEmpty.size
    }
}
