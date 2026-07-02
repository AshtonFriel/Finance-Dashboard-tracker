package com.financedashboard.core.engine

/**
 * Purchasing-power analysis: compares actual income per year against the income
 * required to keep pace with CPI from a chosen baseline year.
 *
 * real = nominal x CPI(base) / CPI(year)
 * needed(y) = income(base) x Π (1 + rate) for base < r <= y
 */
object InflationEngine {

    /**
     * CPI-U annual averages (1982-84 = 100), US city average, all items — public
     * BLS series CUUR0000SA0. Bundled as editable defaults; the app lets users
     * override any year and add new ones.
     */
    val DEFAULT_CPI: Map<Int, Double> = mapOf(
        2015 to 237.017,
        2016 to 240.007,
        2017 to 245.120,
        2018 to 251.107,
        2019 to 255.657,
        2020 to 258.811,
        2021 to 270.970,
        2022 to 292.655,
        2023 to 304.702,
        2024 to 313.689,
        2025 to 322.132,
    )

    data class YearImpact(
        val year: Int,
        val actualIncome: Double,
        /** Income needed this year to match baseline-year purchasing power. */
        val neededIncome: Double,
        /** actual - needed; negative means purchasing power lost this year. */
        val nominalGap: Double,
        /** Actual income expressed in baseline-year dollars. */
        val realIncome: Double,
        /** realIncome - baseline income. */
        val realGap: Double,
        /** CPI inflation rate applied for this year, as a fraction. */
        val inflationRate: Double,
    )

    data class Impact(
        val baseYear: Int,
        val baseIncome: Double,
        val years: List<YearImpact>,
        /** Sum of negative-and-positive nominal gaps across all years after base. */
        val cumulativeNominalGap: Double,
        val cumulativeRealGap: Double,
        /** Purchasing power of 1.0 base-year dollar in the final year. */
        val dollarValueAtEnd: Double,
    )

    /**
     * @param incomeByYear nominal income per calendar year (only years present are analyzed)
     * @param baseYear baseline; must exist in [incomeByYear] and [cpi]
     * @param cpi CPI index per year; defaults to bundled BLS annual averages
     */
    fun computeImpact(
        incomeByYear: Map<Int, Double>,
        baseYear: Int,
        cpi: Map<Int, Double> = DEFAULT_CPI,
    ): Impact? {
        val baseIncome = incomeByYear[baseYear] ?: return null
        val baseCpi = cpi[baseYear] ?: return null
        val years = incomeByYear.keys.filter { it > baseYear }.sorted()

        var needed = baseIncome
        val rows = mutableListOf<YearImpact>()
        for (y in years) {
            val cpiY = cpi[y] ?: continue
            val cpiPrev = cpi[y - 1] ?: continue
            val rate = cpiY / cpiPrev - 1.0
            needed *= (1.0 + rate)
            val actual = incomeByYear.getValue(y)
            val real = actual * baseCpi / cpiY
            rows.add(
                YearImpact(
                    year = y,
                    actualIncome = actual,
                    neededIncome = needed,
                    nominalGap = actual - needed,
                    realIncome = real,
                    realGap = real - baseIncome,
                    inflationRate = rate,
                )
            )
        }
        if (rows.isEmpty()) return null
        val lastCpi = cpi[rows.last().year] ?: baseCpi
        return Impact(
            baseYear = baseYear,
            baseIncome = baseIncome,
            years = rows,
            cumulativeNominalGap = rows.sumOf { it.nominalGap },
            cumulativeRealGap = rows.sumOf { it.realGap },
            dollarValueAtEnd = baseCpi / lastCpi,
        )
    }

    /** Purchasing power of one base-year dollar for each year in range, for the decay chart. */
    fun purchasingPowerSeries(
        baseYear: Int,
        throughYear: Int,
        cpi: Map<Int, Double> = DEFAULT_CPI,
    ): List<Pair<Int, Double>> {
        val baseCpi = cpi[baseYear] ?: return emptyList()
        return (baseYear..throughYear).mapNotNull { y ->
            cpi[y]?.let { y to baseCpi / it }
        }
    }
}
