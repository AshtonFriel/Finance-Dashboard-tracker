package com.financedashboard.core.engine

import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InvestmentEngineTest {

    @Test
    fun `lump sum matches closed form future value`() {
        val p = InvestmentEngine.project(
            principal = 10_000.0, monthlyContribution = 0.0,
            annualReturnPct = 6.0, years = 10, inflationPct = 0.0,
        )
        val expected = 10_000.0 * (1 + 0.06 / 12).pow(120)
        assertEquals(expected, p.years.last().endBalanceNominal, 0.01)
    }

    @Test
    fun `contributions match closed form annuity`() {
        val p = InvestmentEngine.project(0.0, 100.0, 6.0, 5, 0.0)
        val r = 0.06 / 12
        val n = 60
        val expected = 100.0 * (((1 + r).pow(n) - 1) / r)
        assertEquals(expected, p.years.last().endBalanceNominal, 0.01)
    }

    @Test
    fun `real balance deflates by inflation`() {
        val p = InvestmentEngine.project(10_000.0, 0.0, 7.0, 10, 3.0)
        val last = p.years.last()
        assertEquals(last.endBalanceNominal / 1.03.pow(10), last.endBalanceReal, 0.01)
        assertTrue(last.endBalanceReal < last.endBalanceNominal)
    }

    @Test
    fun `growth plus contributions reconciles year over year`() {
        val p = InvestmentEngine.project(5_000.0, 250.0, 8.0, 3, 2.5)
        var balance = 5_000.0
        for (row in p.years) {
            balance += row.contributions + row.growth
            assertEquals(balance, row.endBalanceNominal, 0.01)
        }
    }

    @Test
    fun `scenario band is ordered pessimistic below optimistic`() {
        val band = InvestmentEngine.scenarioBand(50_000.0, 500.0, 20, 2.7)
        val pess = band.pessimistic.years.last().endBalanceNominal
        val expd = band.expected.years.last().endBalanceNominal
        val opti = band.optimistic.years.last().endBalanceNominal
        assertTrue(pess < expd && expd < opti)
    }

    @Test
    fun `redirect from month zero equals higher flat contribution`() {
        val redirected = InvestmentEngine.project(
            10_000.0, 500.0, 7.0, 10, 0.0,
            redirectFromMonth = 0, redirectAmount = 300.0,
        )
        val flat = InvestmentEngine.project(10_000.0, 800.0, 7.0, 10, 0.0)
        assertEquals(flat.years.last().endBalanceNominal, redirected.years.last().endBalanceNominal, 0.01)
    }

    @Test
    fun `later redirect grows less but still more than none`() {
        val none = InvestmentEngine.project(10_000.0, 500.0, 7.0, 20, 0.0)
        val early = InvestmentEngine.project(10_000.0, 500.0, 7.0, 20, 0.0, redirectFromMonth = 24, redirectAmount = 1_000.0)
        val late = InvestmentEngine.project(10_000.0, 500.0, 7.0, 20, 0.0, redirectFromMonth = 120, redirectAmount = 1_000.0)
        assertTrue(early.years.last().endBalanceNominal > late.years.last().endBalanceNominal)
        assertTrue(late.years.last().endBalanceNominal > none.years.last().endBalanceNominal)
    }

    @Test
    fun `redirect beyond horizon changes nothing`() {
        val none = InvestmentEngine.project(10_000.0, 500.0, 7.0, 5, 0.0)
        val beyond = InvestmentEngine.project(10_000.0, 500.0, 7.0, 5, 0.0, redirectFromMonth = 120, redirectAmount = 1_000.0)
        assertEquals(none.years.last().endBalanceNominal, beyond.years.last().endBalanceNominal, 0.01)
    }

    @Test
    fun `milestone year found or null`() {
        val p = InvestmentEngine.project(50_000.0, 1_000.0, 7.0, 30, 0.0)
        val yr = InvestmentEngine.milestoneYear(p, 250_000.0)
        assertTrue(yr != null && yr in 1..30)
        assertNull(InvestmentEngine.milestoneYear(p, 1e12))
    }
}
