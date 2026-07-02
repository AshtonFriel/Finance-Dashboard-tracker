package com.financedashboard.core.engine

import com.financedashboard.core.model.Debt
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmortizationEngineTest {

    private val start = YearMonth.of(2026, 1)

    @Test
    fun `single debt amortizes to zero with correct first month interest`() {
        val debt = Debt("Loan", balance = 10_000.0, annualRatePct = 12.0, minPayment = 500.0)
        val plan = AmortizationEngine.computePlan(listOf(debt), PayoffStrategy.AVALANCHE, 0.0, start)
        val first = plan.debts[0].schedule.first()
        assertEquals(100.0, first.interest, 0.001) // 10000 * 1%/mo
        assertEquals(500.0, first.payment, 0.001)
        assertEquals(400.0, first.principal, 0.001)
        assertEquals(9_600.0, first.remainingBalance, 0.001)
        assertNotNull(plan.payoffMonth)
        assertEquals(0.0, plan.combinedBalanceByMonth.last().second, 0.01)
    }

    @Test
    fun `total paid equals principal plus interest`() {
        val debt = Debt("Loan", 10_000.0, 12.0, 500.0)
        val plan = AmortizationEngine.computePlan(listOf(debt), PayoffStrategy.AVALANCHE, 0.0, start)
        assertEquals(10_000.0 + plan.totalInterest, plan.totalPaid, 0.01)
    }

    @Test
    fun `extra payment shortens payoff and reduces interest`() {
        val debt = Debt("Loan", 20_000.0, 10.0, 400.0)
        val base = AmortizationEngine.computePlan(listOf(debt), PayoffStrategy.AVALANCHE, 0.0, start)
        val extra = AmortizationEngine.computePlan(listOf(debt), PayoffStrategy.AVALANCHE, 200.0, start)
        assertTrue(extra.payoffMonth!!.isBefore(base.payoffMonth!!))
        assertTrue(extra.totalInterest < base.totalInterest)
    }

    @Test
    fun `avalanche targets highest apr with surplus`() {
        val high = Debt("High APR", 5_000.0, 20.0, 100.0)
        val low = Debt("Low APR", 5_000.0, 5.0, 100.0)
        val plan = AmortizationEngine.computePlan(listOf(high, low), PayoffStrategy.AVALANCHE, 300.0, start)
        val highFirst = plan.debts.first { it.debt.name == "High APR" }.schedule.first()
        val lowFirst = plan.debts.first { it.debt.name == "Low APR" }.schedule.first()
        assertEquals(400.0, highFirst.payment, 0.001) // min 100 + all 300 extra
        assertEquals(100.0, lowFirst.payment, 0.001)
    }

    @Test
    fun `snowball targets lowest balance and rolls freed minimum`() {
        val small = Debt("Small", 1_000.0, 5.0, 100.0)
        val big = Debt("Big", 10_000.0, 5.0, 200.0)
        val plan = AmortizationEngine.computePlan(listOf(small, big), PayoffStrategy.SNOWBALL, 200.0, start)
        val smallResult = plan.debts.first { it.debt.name == "Small" }
        assertNotNull(smallResult.payoffMonth)
        // After Small retires, Big should receive Small's freed 100 + extra 200 on top of its 200.
        val afterMonth = smallResult.payoffMonth!!.plusMonths(1)
        val bigRow = plan.debts.first { it.debt.name == "Big" }.schedule.first { it.month == afterMonth }
        assertEquals(500.0, bigRow.payment, 0.001)
    }

    @Test
    fun `avalanche never pays more interest than snowball`() {
        val debts = listOf(
            Debt("A", 8_000.0, 22.0, 160.0),
            Debt("B", 3_000.0, 6.0, 60.0),
            Debt("C", 15_000.0, 11.0, 300.0),
        )
        val avalanche = AmortizationEngine.computePlan(debts, PayoffStrategy.AVALANCHE, 250.0, start)
        val snowball = AmortizationEngine.computePlan(debts, PayoffStrategy.SNOWBALL, 250.0, start)
        assertTrue(avalanche.totalInterest <= snowball.totalInterest + 0.01)
    }

    @Test
    fun `comparison reports savings vs baseline`() {
        val debts = listOf(Debt("A", 8_000.0, 22.0, 160.0), Debt("B", 3_000.0, 6.0, 60.0))
        val comparisons = AmortizationEngine.compareStrategies(debts, 250.0, start)
        assertEquals(PayoffStrategy.entries.size, comparisons.size)
        val avalanche = comparisons.first { it.strategy == PayoffStrategy.AVALANCHE }
        assertTrue(avalanche.interestSavedVsBaseline > 0)
        assertTrue(avalanche.monthsSavedVsBaseline > 0)
    }

    @Test
    fun `underwater minimum payment still terminates via cap`() {
        val debt = Debt("Trap", 10_000.0, 30.0, 50.0) // payment below monthly interest
        val plan = AmortizationEngine.computePlan(listOf(debt), PayoffStrategy.AVALANCHE, 0.0, start)
        assertTrue(plan.combinedBalanceByMonth.size <= 12 * 60 + 1)
    }
}
