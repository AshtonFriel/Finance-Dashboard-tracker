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
    ): Projection {
        val r = annualReturnPct / 100.0 / 12.0
        val rows = mutableListOf<YearRow>()
        var balance = principal
        var totalContrib = 0.0
        for (y in 1..years) {
            val startBalance = balance
            var yearContrib = 0.0
            repeat(12) {
                balance *= (1.0 + r)
                balance += monthlyContribution
                yearContrib += monthlyContribution
            }
            totalContrib += yearContrib
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
    ): ScenarioBand = ScenarioBand(
        pessimistic = project(principal, monthlyContribution, pessimisticPct, years, inflationPct),
        expected = project(principal, monthlyContribution, expectedPct, years, inflationPct),
        optimistic = project(principal, monthlyContribution, optimisticPct, years, inflationPct),
    )

    /** First year index (1-based) at which the expected nominal balance crosses [target], or null. */
    fun milestoneYear(projection: Projection, target: Double): Int? =
        projection.years.firstOrNull { it.endBalanceNominal >= target }?.yearIndex
}
