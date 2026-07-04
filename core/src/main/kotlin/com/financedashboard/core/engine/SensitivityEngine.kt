package com.financedashboard.core.engine

import kotlin.math.abs

/**
 * Ranks how much each assumption moves an outcome (a tornado analysis). Each
 * factor is swept low/high around its baseline and the resulting spread in the
 * outcome measures its impact. Pure function evaluation — the caller supplies
 * how to compute the outcome for a given parameter set, reusing existing engines.
 */
object SensitivityEngine {

    data class Factor(
        val name: String,
        val baseline: Double,
        val low: Double,
        val high: Double,
    )

    data class Impact(
        val name: String,
        val lowOutcome: Double,
        val highOutcome: Double,
        val baselineOutcome: Double,
    ) {
        /** Total spread in the outcome across the factor's range. */
        val spread: Double get() = abs(highOutcome - lowOutcome)
    }

    /**
     * @param factors the assumptions to sweep, each with a low/baseline/high value
     * @param outcome computes the outcome given the full parameter vector (by name)
     */
    fun analyze(
        factors: List<Factor>,
        outcome: (Map<String, Double>) -> Double,
    ): List<Impact> {
        val baselineVector = factors.associate { it.name to it.baseline }
        val baselineOutcome = outcome(baselineVector)
        return factors.map { f ->
            val low = outcome(baselineVector + (f.name to f.low))
            val high = outcome(baselineVector + (f.name to f.high))
            Impact(f.name, low, high, baselineOutcome)
        }.sortedByDescending { it.spread }
    }
}
