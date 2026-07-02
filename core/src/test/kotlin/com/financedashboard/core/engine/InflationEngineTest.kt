package com.financedashboard.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InflationEngineTest {

    // Synthetic incomes; CPI is the bundled public BLS table.
    private val income = mapOf(2022 to 50_000.0, 2023 to 51_000.0, 2024 to 52_000.0, 2025 to 53_000.0)

    @Test
    fun `needed income compounds cpi rates from base year`() {
        val impact = InflationEngine.computeImpact(income, baseYear = 2022)!!
        // 2023 rate = 304.702/292.655 - 1 = 4.116%
        val needed2023 = 50_000.0 * (304.702 / 292.655)
        assertEquals(needed2023, impact.years[0].neededIncome, 0.01)
        val needed2024 = needed2023 * (313.689 / 304.702)
        assertEquals(needed2024, impact.years[1].neededIncome, 0.01)
    }

    @Test
    fun `real income deflates by cpi ratio`() {
        val impact = InflationEngine.computeImpact(income, baseYear = 2022)!!
        val real2025 = 53_000.0 * 292.655 / 322.132
        assertEquals(real2025, impact.years.last().realIncome, 0.01)
        assertEquals(real2025 - 50_000.0, impact.years.last().realGap, 0.01)
    }

    @Test
    fun `cumulative gap sums yearly nominal gaps`() {
        val impact = InflationEngine.computeImpact(income, baseYear = 2022)!!
        assertEquals(impact.years.sumOf { it.nominalGap }, impact.cumulativeNominalGap, 0.001)
    }

    @Test
    fun `income that tracks cpi exactly has zero gap`() {
        val tracking = mapOf(
            2022 to 50_000.0,
            2023 to 50_000.0 * 304.702 / 292.655,
            2024 to 50_000.0 * 313.689 / 292.655,
        )
        val impact = InflationEngine.computeImpact(tracking, baseYear = 2022)!!
        assertEquals(0.0, impact.cumulativeNominalGap, 0.01)
        assertEquals(0.0, impact.cumulativeRealGap, 0.01)
    }

    @Test
    fun `dollar value at end reflects cpi growth`() {
        val impact = InflationEngine.computeImpact(income, baseYear = 2022)!!
        assertEquals(292.655 / 322.132, impact.dollarValueAtEnd, 1e-9)
    }

    @Test
    fun `missing base year returns null`() {
        assertNull(InflationEngine.computeImpact(income, baseYear = 1999))
    }

    @Test
    fun `purchasing power series starts at one and declines`() {
        val series = InflationEngine.purchasingPowerSeries(2022, 2025)
        assertEquals(1.0, series.first().second, 1e-9)
        assertEquals(4, series.size)
        series.zipWithNext().forEach { (a, b) -> assert(b.second < a.second) }
    }
}
