package com.financedashboard.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyFundEngineTest {

    @Test
    fun `runway and target math`() {
        val s = EmergencyFundEngine.compute(
            liquidCash = 6_000.0, avgMonthlyExpenses = 3_000.0,
            targetMonths = 6, monthlySaving = 500.0,
        )
        assertEquals(2.0, s.runwayMonths, 0.001)
        assertEquals(18_000.0, s.targetAmount, 0.001)
        assertEquals(12_000.0, s.gap, 0.001)
        assertEquals(6_000.0 / 18_000.0, s.progress, 0.001)
    }

    @Test
    fun `months to target with flat saving`() {
        val s = EmergencyFundEngine.compute(6_000.0, 3_000.0, 6, 500.0)
        assertEquals(24, s.monthsToTarget) // 12,000 gap / 500 per month
        assertEquals(18_000.0, s.projection.last(), 0.01)
    }

    @Test
    fun `yield shortens time to target`() {
        val flat = EmergencyFundEngine.compute(6_000.0, 3_000.0, 6, 500.0, annualYieldPct = 0.0)
        val yield4 = EmergencyFundEngine.compute(6_000.0, 3_000.0, 6, 500.0, annualYieldPct = 4.0)
        assertTrue(yield4.monthsToTarget!! <= flat.monthsToTarget!!)
    }

    @Test
    fun `already funded target reports zero months`() {
        val s = EmergencyFundEngine.compute(20_000.0, 3_000.0, 6, 0.0)
        assertEquals(0, s.monthsToTarget)
        assertEquals(0.0, s.gap, 0.001)
        assertEquals(1.0, s.progress, 0.001)
    }

    @Test
    fun `no saving and unfunded target never completes`() {
        val s = EmergencyFundEngine.compute(1_000.0, 3_000.0, 6, 0.0)
        assertNull(s.monthsToTarget)
    }

    @Test
    fun `zero expenses yields zero runway and target`() {
        val s = EmergencyFundEngine.compute(5_000.0, 0.0, 6, 100.0)
        assertEquals(0.0, s.runwayMonths, 0.001)
        assertEquals(0.0, s.targetAmount, 0.001)
    }

    @Test
    fun `average ignores empty months`() {
        assertEquals(3_000.0, EmergencyFundEngine.averageMonthlyExpenses(listOf(2_000.0, 4_000.0, 0.0)), 0.001)
        assertEquals(0.0, EmergencyFundEngine.averageMonthlyExpenses(emptyList()), 0.001)
    }
}
