package com.financedashboard.core.engine

/**
 * A single "financial health" score (0–100, with a letter grade) built from
 * four independently scored components: debt-to-income, savings rate, emergency
 * -fund runway, and net-worth momentum. Each component maps a real ratio onto
 * 0–100 by linear interpolation between a "poor" and a "strong" anchor, so the
 * score is explainable — every point traces back to a number the user can see.
 */
object FinancialHealthEngine {

    data class Component(
        val label: String,
        /** 0..100 */
        val score: Int,
        /** Human-readable value behind the score, e.g. "18% of income". */
        val detail: String,
        /** Relative weight in the overall score. */
        val weight: Int,
    )

    data class Health(
        val score: Int,
        val grade: String,
        val components: List<Component>,
    )

    private fun lerpScore(value: Double, poor: Double, strong: Double): Int {
        // Handles both "higher is better" (strong > poor) and the reverse.
        val t = if (strong == poor) 1.0 else (value - poor) / (strong - poor)
        return (t.coerceIn(0.0, 1.0) * 100).toInt()
    }

    private fun grade(score: Int): String = when {
        score >= 90 -> "A"
        score >= 80 -> "B"
        score >= 70 -> "C"
        score >= 60 -> "D"
        else -> "F"
    }

    fun compute(
        monthlyIncome: Double,
        monthlyExpenses: Double,
        monthlyDebtPayments: Double,
        liquidCash: Double,
        emergencyFundTargetMonths: Int,
        /**
         * Fractional year-over-year net-worth change (0.1 = +10%), computed
         * like-for-like so mid-year account linking doesn't distort it. Null
         * when there isn't enough history — the component then scores neutral.
         */
        netWorthMomentum: Double?,
    ): Health {
        val income = monthlyIncome.coerceAtLeast(0.0)

        // 1. Debt-to-income: monthly debt payments / gross income. Lower is better.
        //    <=10% is strong, >=40% is poor (a common lending red line).
        val dti = if (income > 0.005) monthlyDebtPayments / income else if (monthlyDebtPayments > 0) 1.0 else 0.0
        val dtiScore = lerpScore(dti, poor = 0.40, strong = 0.10)
        val dtiComponent = Component(
            "Debt-to-income", dtiScore,
            if (income > 0.005) "${(dti * 100).toInt()}% of income to debt" else "no income data", 25,
        )

        // 2. Savings rate: (income - expenses) / income. Higher is better.
        //    >=20% strong, <=0% poor.
        val savingsRate = if (income > 0.005) (income - monthlyExpenses) / income else 0.0
        val savingsScore = lerpScore(savingsRate, poor = 0.0, strong = 0.20)
        val savingsComponent = Component(
            "Savings rate", savingsScore,
            if (income > 0.005) "${(savingsRate * 100).toInt()}% saved / month" else "no income data", 30,
        )

        // 3. Emergency-fund runway vs target: months of expenses in liquid cash.
        val runway = if (monthlyExpenses > 0.005) liquidCash / monthlyExpenses else 0.0
        val target = emergencyFundTargetMonths.coerceAtLeast(1).toDouble()
        val efScore = lerpScore(runway, poor = 0.0, strong = target)
        val efComponent = Component(
            "Emergency fund", efScore,
            "%.1f".format(runway) + " of $emergencyFundTargetMonths mo target", 25,
        )

        // 4. Net-worth momentum: year-over-year growth. >=10% strong, <=-10% poor.
        val momentum = netWorthMomentum
        val momentumScore = momentum?.let { lerpScore(it, poor = -0.10, strong = 0.10) } ?: 50
        val momentumComponent = Component(
            "Net-worth trend", momentumScore,
            momentum?.let { "${if (it >= 0) "+" else ""}${(it * 100).toInt()}% vs last year" } ?: "not enough history", 20,
        )

        val components = listOf(dtiComponent, savingsComponent, efComponent, momentumComponent)
        val totalWeight = components.sumOf { it.weight }
        val overall = (components.sumOf { it.score.toDouble() * it.weight } / totalWeight).toInt()
        return Health(overall, grade(overall), components)
    }
}
