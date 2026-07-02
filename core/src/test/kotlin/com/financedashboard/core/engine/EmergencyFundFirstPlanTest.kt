package com.financedashboard.core.engine

import com.financedashboard.core.model.Debt
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyFundFirstPlanTest {

    private val start = YearMonth.of(2026, 7)
    private val debt = Debt("Loan", 10_000.0, 12.0, 500.0)

    @Test
    fun `extra fills the fund before touching debt`() {
        val ef = AmortizationEngine.computePlanWithEmergencyFund(
            debts = listOf(debt), strategy = PayoffStrategy.AVALANCHE,
            extraMonthly = 300.0, startMonth = start,
            efStartBalance = 1_000.0, efTargetAmount = 2_500.0,
        )
        // Gap of 1,500 at 300/mo -> funded at month 5.
        assertEquals(start.plusMonths(5), ef.efFundedMonth)
        assertEquals(2_500.0, ef.efSeries[5].second, 0.001)
        // While funding, debt only receives the minimum: first month = plain amortization.
        val firstRow = ef.plan.debts[0].schedule.first()
        assertEquals(500.0, firstRow.payment, 0.001)
    }

    @Test
    fun `overflow in the funding month goes to debt`() {
        val ef = AmortizationEngine.computePlanWithEmergencyFund(
            debts = listOf(debt), strategy = PayoffStrategy.AVALANCHE,
            extraMonthly = 300.0, startMonth = start,
            efStartBalance = 2_400.0, efTargetAmount = 2_500.0,
        )
        // Month 1: 100 to fund, 200 extra to debt -> payment 700.
        assertEquals(start.plusMonths(1), ef.efFundedMonth)
        assertEquals(700.0, ef.plan.debts[0].schedule.first().payment, 0.001)
        assertEquals(800.0, ef.plan.debts[0].schedule[1].payment, 0.001)
    }

    @Test
    fun `already funded behaves like a plain extra-payment plan`() {
        val ef = AmortizationEngine.computePlanWithEmergencyFund(
            debts = listOf(debt), strategy = PayoffStrategy.AVALANCHE,
            extraMonthly = 300.0, startMonth = start,
            efStartBalance = 5_000.0, efTargetAmount = 2_500.0,
        )
        val plain = AmortizationEngine.computePlan(listOf(debt), PayoffStrategy.AVALANCHE, 300.0, start)
        assertEquals(start, ef.efFundedMonth)
        assertEquals(plain.totalInterest, ef.plan.totalInterest, 0.01)
        assertEquals(plain.payoffMonth, ef.plan.payoffMonth)
    }

    @Test
    fun `ef first delays payoff and costs more interest than debt first`() {
        val ef = AmortizationEngine.computePlanWithEmergencyFund(
            debts = listOf(debt), strategy = PayoffStrategy.AVALANCHE,
            extraMonthly = 300.0, startMonth = start,
            efStartBalance = 0.0, efTargetAmount = 6_000.0,
        )
        val debtFirst = AmortizationEngine.computePlan(listOf(debt), PayoffStrategy.AVALANCHE, 300.0, start)
        assertNotNull(ef.plan.payoffMonth)
        assertTrue(ef.plan.payoffMonth!! > debtFirst.payoffMonth!!)
        assertTrue(ef.plan.totalInterest > debtFirst.totalInterest)
        // The trade-off buys a funded cushion by the funded month.
        assertEquals(start.plusMonths(20), ef.efFundedMonth)
    }

    @Test
    fun `ef series aligns with plan months`() {
        val ef = AmortizationEngine.computePlanWithEmergencyFund(
            debts = listOf(debt), strategy = PayoffStrategy.AVALANCHE,
            extraMonthly = 200.0, startMonth = start,
            efStartBalance = 0.0, efTargetAmount = 1_000.0,
        )
        assertEquals(ef.plan.combinedBalanceByMonth.size, ef.efSeries.size)
        assertEquals(ef.plan.combinedBalanceByMonth.first().first, ef.efSeries.first().first)
        // Fund never exceeds target.
        assertTrue(ef.efSeries.all { it.second <= 1_000.0 + 0.01 })
    }
}
