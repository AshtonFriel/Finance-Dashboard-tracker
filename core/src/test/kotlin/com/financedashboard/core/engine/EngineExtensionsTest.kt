package com.financedashboard.core.engine

import com.financedashboard.core.model.Debt
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineExtensionsTest {

    private val start = YearMonth.of(2026, 7)
    private val debt = Debt("Loan", 20_000.0, 12.0, 400.0)

    // ---- Lump sums ----

    @Test
    fun `lump sum lands in its month and shortens payoff`() {
        val base = AmortizationEngine.computePlan(listOf(debt), PayoffStrategy.AVALANCHE, 0.0, start)
        val withLump = AmortizationEngine.computePlan(
            listOf(debt), PayoffStrategy.AVALANCHE, 0.0, start,
            lumpSums = mapOf(start.plusMonths(3) to 5_000.0),
        )
        val lumpRow = withLump.debts[0].schedule.first { it.month == start.plusMonths(3) }
        assertEquals(5_400.0, lumpRow.payment, 0.001) // min 400 + lump 5000
        assertTrue(withLump.payoffMonth!! < base.payoffMonth!!)
        assertTrue(withLump.totalInterest < base.totalInterest)
    }

    @Test
    fun `lump sum in emergency fund mode fills the fund first`() {
        val plan = AmortizationEngine.computePlanWithEmergencyFund(
            listOf(debt), PayoffStrategy.AVALANCHE, 0.0, start,
            efStartBalance = 0.0, efTargetAmount = 3_000.0,
            lumpSums = mapOf(start.plusMonths(2) to 5_000.0),
        )
        assertEquals(start.plusMonths(2), plan.efFundedMonth)
        // Overflow (5000 - 3000) hits the debt that month.
        val row = plan.plan.debts[0].schedule.first { it.month == start.plusMonths(2) }
        assertEquals(400.0 + 2_000.0, row.payment, 0.001)
    }

    // ---- Extra growth ----

    @Test
    fun `extra payment grows annually`() {
        val plan = AmortizationEngine.computePlan(
            listOf(Debt("Big", 100_000.0, 5.0, 500.0)), PayoffStrategy.AVALANCHE, 100.0, start,
            extraGrowthPctPerYear = 10.0,
        )
        val sched = plan.debts[0].schedule
        assertEquals(600.0, sched[0].payment, 0.001) // year 1: 500 + 100
        assertEquals(610.0, sched[12].payment, 0.001) // year 2: 500 + 110
        assertEquals(621.0, sched[24].payment, 0.001) // year 3: 500 + 121
    }

    @Test
    fun `growth shortens payoff vs flat extra`() {
        val debts = listOf(Debt("Loan", 50_000.0, 8.0, 700.0))
        val flat = AmortizationEngine.computePlan(debts, PayoffStrategy.AVALANCHE, 200.0, start)
        val growing = AmortizationEngine.computePlan(debts, PayoffStrategy.AVALANCHE, 200.0, start, extraGrowthPctPerYear = 5.0)
        assertTrue(growing.payoffMonth!! <= flat.payoffMonth!!)
        assertTrue(growing.totalInterest < flat.totalInterest)
    }

    // ---- Custom order ----

    @Test
    fun `custom order targets listed debt first regardless of apr or balance`() {
        val a = Debt("A high apr", 5_000.0, 25.0, 100.0)
        val b = Debt("B low apr", 5_000.0, 3.0, 100.0)
        val plan = AmortizationEngine.computePlan(
            listOf(a, b), PayoffStrategy.CUSTOM, 300.0, start,
            customOrder = listOf("B low apr", "A high apr"),
        )
        val bFirst = plan.debts.first { it.debt.name == "B low apr" }.schedule.first()
        assertEquals(400.0, bFirst.payment, 0.001) // min + all extra
        val aFirst = plan.debts.first { it.debt.name == "A high apr" }.schedule.first()
        assertEquals(100.0, aFirst.payment, 0.001)
    }

    @Test
    fun `custom order falls back to avalanche for unlisted debts`() {
        val a = Debt("A", 5_000.0, 25.0, 100.0)
        val b = Debt("B", 5_000.0, 3.0, 100.0)
        val plan = AmortizationEngine.computePlan(
            listOf(a, b), PayoffStrategy.CUSTOM, 300.0, start, customOrder = emptyList(),
        )
        // No order given: behaves like avalanche.
        assertEquals(400.0, plan.debts.first { it.debt.name == "A" }.schedule.first().payment, 0.001)
    }

    // ---- Stress test ----

    @Test
    fun `stress rate applies only to early years`() {
        val stressed = InvestmentEngine.project(
            100_000.0, 0.0, 8.0, 15, 0.0,
            stressRatePct = 0.0, stressYears = 10,
        )
        // First 10 years flat, then 8%.
        assertEquals(100_000.0, stressed.years[9].endBalanceNominal, 0.01)
        assertTrue(stressed.years[10].endBalanceNominal > 100_000.0)
        val normal = InvestmentEngine.project(100_000.0, 0.0, 8.0, 15, 0.0)
        assertTrue(stressed.years.last().endBalanceNominal < normal.years.last().endBalanceNominal)
    }

    // ---- Sleeves ----

    @Test
    fun `sleeve band sums sleeves and keeps contributions in the right sleeve`() {
        val sleeves = listOf(
            InvestmentEngine.Sleeve(50_000.0, 500.0, 3.0, 7.0, 10.0, receivesContributions = true),
            InvestmentEngine.Sleeve(20_000.0, 0.0, -10.0, 7.0, 25.0, receivesContributions = false),
        )
        val band = InvestmentEngine.sleeveBand(sleeves, years = 10, inflationPct = 0.0)
        val separate = InvestmentEngine.project(50_000.0, 500.0, 7.0, 10, 0.0).years.last().endBalanceNominal +
            InvestmentEngine.project(20_000.0, 0.0, 7.0, 10, 0.0).years.last().endBalanceNominal
        assertEquals(separate, band.expected.years.last().endBalanceNominal, 0.01)
        // The wide crypto band drags pessimistic below a tight-band-only portfolio of same size.
        assertTrue(band.pessimistic.years.last().endBalanceNominal < band.expected.years.last().endBalanceNominal)
        assertTrue(band.optimistic.years.last().endBalanceNominal > band.expected.years.last().endBalanceNominal)
    }

    @Test
    fun `sum projections adds element wise`() {
        val p1 = InvestmentEngine.project(1_000.0, 100.0, 5.0, 3, 2.0)
        val p2 = InvestmentEngine.project(2_000.0, 200.0, 5.0, 3, 2.0)
        val sum = InvestmentEngine.sumProjections(listOf(p1, p2))
        for (i in 0 until 3) {
            assertEquals(
                p1.years[i].endBalanceNominal + p2.years[i].endBalanceNominal,
                sum.years[i].endBalanceNominal, 0.001,
            )
        }
    }
}
