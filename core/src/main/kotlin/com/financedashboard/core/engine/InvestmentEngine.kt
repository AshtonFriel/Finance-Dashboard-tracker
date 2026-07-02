package com.financedashboard.core.engine

import kotlin.math.pow

/**
 * Compound-growth projections with monthly compounding and monthly
 * contributions, in nominal and real (inflation-deflated) terms.
 *
 * FV = P(1+r/12)^n + PMT x [((1+r/12)^n - 1) / (r/12)]
 */
object InvestmentEngine {

    data class YearRow(
        val yearIndex: Int,
        val contributions: Double,
        val growth: Double,
        val endBalanceNominal: Double,
        /** End balance deflated to today's dollars at the assumed inflation rate. */
        val endBalanceReal: Double,
    )

    data class Projection(
        val annualReturnPct: Double,
        val inflationPct: Double,
        val years: List<YearRow>,
    )

    fun project(
        principal: Double,
        monthlyContribution: Double,
        annualReturnPct: Double,
        years: Int,
        inflationPct: Double,
        /** Extra monthly contribution that begins at this month index (0-based from now), e.g. a freed debt budget. */
        redirectFromMonth: Int? = null,
        redirectAmount: Double = 0.0,
        /** Sequence-of-returns stress: this rate replaces the base rate for the first [stressYears] years. */
        stressRatePct: Double? = null,
        stressYears: Int = 10,
    ): Projection {
        val r = annualReturnPct / 100.0 / 12.0
        val rStress = (stressRatePct ?: annualReturnPct) / 100.0 / 12.0
        val rows = mutableListOf<YearRow>()
        var balance = principal
        var month = 0
        for (y in 1..years) {
            val startBalance = balance
            var yearContrib = 0.0
            repeat(12) {
                balance *= (1.0 + if (stressRatePct != null && y <= stressYears) rStress else r)
                var contrib = monthlyContribution
                if (redirectFromMonth != null && month >= redirectFromMonth) contrib += redirectAmount
                balance += contrib
                yearContrib += contrib
                month++
            }
            val deflator = (1.0 + inflationPct / 100.0).pow(y)
            rows.add(
                YearRow(
                    yearIndex = y,
                    contributions = yearContrib,
                    growth = balance - startBalance - yearContrib,
                    endBalanceNominal = balance,
                    endBalanceReal = balance / deflator,
                )
            )
        }
        return Projection(annualReturnPct, inflationPct, rows)
    }

    data class ScenarioBand(
        val pessimistic: Projection,
        val expected: Projection,
        val optimistic: Projection,
    )

    /** Three-rate band for the confidence-region chart. */
    fun scenarioBand(
        principal: Double,
        monthlyContribution: Double,
        years: Int,
        inflationPct: Double,
        pessimisticPct: Double = 3.0,
        expectedPct: Double = 7.0,
        optimisticPct: Double = 10.0,
        redirectFromMonth: Int? = null,
        redirectAmount: Double = 0.0,
    ): ScenarioBand = ScenarioBand(
        pessimistic = project(principal, monthlyContribution, pessimisticPct, years, inflationPct, redirectFromMonth, redirectAmount),
        expected = project(principal, monthlyContribution, expectedPct, years, inflationPct, redirectFromMonth, redirectAmount),
        optimistic = project(principal, monthlyContribution, optimisticPct, years, inflationPct, redirectFromMonth, redirectAmount),
    )

    /** First year index (1-based) at which the expected nominal balance crosses [target], or null. */
    fun milestoneYear(projection: Projection, target: Double): Int? =
        projection.years.firstOrNull { it.endBalanceNominal >= target }?.yearIndex

    /**
     * A portion of the portfolio with its own return band — e.g. equities with
     * a tight band and crypto with a deliberately wide one. Contributions and
     * redirect flow only to sleeves with [receivesContributions] set.
     */
    data class Sleeve(
        val principal: Double,
        val monthlyContribution: Double,
        val pessimisticPct: Double,
        val expectedPct: Double,
        val optimisticPct: Double,
        val receivesContributions: Boolean = true,
    )

    /** Element-wise sum of projections that share year count and inflation. */
    fun sumProjections(projections: List<Projection>): Projection {
        require(projections.isNotEmpty())
        val years = projections.first().years.size
        return Projection(
            annualReturnPct = projections.first().annualReturnPct,
            inflationPct = projections.first().inflationPct,
            years = (0 until years).map { i ->
                YearRow(
                    yearIndex = i + 1,
                    contributions = projections.sumOf { it.years[i].contributions },
                    growth = projections.sumOf { it.years[i].growth },
                    endBalanceNominal = projections.sumOf { it.years[i].endBalanceNominal },
                    endBalanceReal = projections.sumOf { it.years[i].endBalanceReal },
                )
            },
        )
    }

    /** Band across sleeves: each sleeve projects at its own rates, then sums. */
    fun sleeveBand(
        sleeves: List<Sleeve>,
        years: Int,
        inflationPct: Double,
        redirectFromMonth: Int? = null,
        redirectAmount: Double = 0.0,
        stressRatePct: Double? = null,
        stressYears: Int = 10,
    ): ScenarioBand {
        fun run(rate: (Sleeve) -> Double, stressed: Boolean) = sumProjections(
            sleeves.map { s ->
                project(
                    principal = s.principal,
                    monthlyContribution = if (s.receivesContributions) s.monthlyContribution else 0.0,
                    annualReturnPct = rate(s),
                    years = years,
                    inflationPct = inflationPct,
                    redirectFromMonth = if (s.receivesContributions) redirectFromMonth else null,
                    redirectAmount = redirectAmount,
                    stressRatePct = if (stressed) stressRatePct else null,
                    stressYears = stressYears,
                )
            }
        )
        // The stress scenario replaces the first decade's returns in every band:
        // it asks "what if the early years are bad", not "make pessimistic worse".
        return ScenarioBand(
            pessimistic = run({ it.pessimisticPct }, stressed = true),
            expected = run({ it.expectedPct }, stressed = true),
            optimistic = run({ it.optimisticPct }, stressed = true),
        )
    }
}
