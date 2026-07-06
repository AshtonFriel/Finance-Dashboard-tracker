package com.financedashboard.core.engine

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetEngineTest {
    @Test
    fun `flags over near and under`() {
        val budgets = listOf(
            BudgetEngine.CategoryBudget("Groceries", 500.0),
            BudgetEngine.CategoryBudget("Restaurants & Bars", 200.0),
            BudgetEngine.CategoryBudget("Shopping", 300.0),
        )
        val spent = mapOf("Groceries" to 520.0, "Restaurants & Bars" to 185.0, "Shopping" to 50.0)
        // Mid-month so pace projection is meaningful.
        val s = BudgetEngine.evaluate(budgets, spent, dayOfMonth = 15, daysInMonth = 30)
        val byCat = s.lines.associateBy { it.category }
        assertEquals(BudgetEngine.Status.OVER, byCat.getValue("Groceries").status)
        assertEquals(BudgetEngine.Status.NEAR, byCat.getValue("Restaurants & Bars").status) // 92.5% used
        assertEquals(BudgetEngine.Status.UNDER, byCat.getValue("Shopping").status)
        assertEquals(1, s.overCount)
        assertEquals(-20.0, byCat.getValue("Groceries").remaining, 0.001)
    }

    @Test
    fun `pace projection flags a category trending over`() {
        val budgets = listOf(BudgetEngine.CategoryBudget("Gas", 100.0))
        // $60 by day 10 of 30 projects to $180 -> NEAR even though only 60% used.
        val s = BudgetEngine.evaluate(budgets, mapOf("Gas" to 60.0), dayOfMonth = 10, daysInMonth = 30)
        assertEquals(BudgetEngine.Status.NEAR, s.lines.first().status)
        assertEquals(180.0, s.lines.first().projectedSpend, 0.001)
    }
}

class CashFlowForecastEngineTest {
    @Test
    fun `projects balance and finds the low point`() {
        val from = LocalDate.parse("2026-07-05")
        val events = listOf(
            CashFlowForecastEngine.Event(from.plusDays(3), -1500.0, "Rent"),
            CashFlowForecastEngine.Event(from.plusDays(10), 2900.0, "Paycheck"),
        )
        val f = CashFlowForecastEngine.project(1000.0, events, from, days = 30)
        assertEquals(31, f.points.size)
        assertEquals(2400.0, f.endingBalance, 0.001)      // 1000 - 1500 + 2900
        assertEquals(-500.0, f.minBalance, 0.001)         // after rent, before paycheck
        assertTrue(f.goesNegative)
        assertEquals(from.plusDays(3), f.minDate)
        assertEquals(2900.0, f.totalIn, 0.001)
        assertEquals(1500.0, f.totalOut, 0.001)
    }

    @Test
    fun `recurring events project forward in phase`() {
        val from = LocalDate.parse("2026-07-05")
        // Last charge June 20, every 14 days -> next on/after Jul 5 is Jul 4? -> Jul 18, Aug 1...
        val evs = CashFlowForecastEngine.recurringEvents(
            lastDate = LocalDate.parse("2026-06-20"), everyDays = 14, amount = -50.0,
            label = "Gym", from = from, days = 30,
        )
        assertTrue(evs.isNotEmpty())
        assertTrue(evs.all { !it.date.isBefore(from) })
        assertEquals(LocalDate.parse("2026-07-18"), evs.first().date)
    }
}

class FinancialHealthEngineTest {
    @Test
    fun `strong finances score high`() {
        val h = FinancialHealthEngine.compute(
            monthlyIncome = 6000.0, monthlyExpenses = 3500.0, monthlyDebtPayments = 600.0,
            liquidCash = 21000.0, emergencyFundTargetMonths = 6,
            netWorthNow = 200000.0, netWorthYearAgo = 170000.0,
        )
        assertTrue("expected strong score, got ${h.score}", h.score >= 75)
        assertEquals(4, h.components.size)
    }

    @Test
    fun `weak finances score low and momentum missing is neutral`() {
        val h = FinancialHealthEngine.compute(
            monthlyIncome = 4000.0, monthlyExpenses = 4200.0, monthlyDebtPayments = 1800.0,
            liquidCash = 500.0, emergencyFundTargetMonths = 6,
            netWorthNow = -5000.0, netWorthYearAgo = null,
        )
        assertTrue("expected weak score, got ${h.score}", h.score <= 45)
        assertEquals(50, h.components.first { it.label == "Net-worth trend" }.score) // neutral w/o history
    }
}

class ImportDigestEngineTest {
    private fun snap(nw: Double, debt: Double, cash: Double, spend: Double, n: Int, latest: Long, taken: Long) =
        ImportDigestEngine.Snapshot(nw, debt, cash, spend, n, latest, taken)

    @Test
    fun `first import has no previous`() {
        val d = ImportDigestEngine.diff(null, snap(100.0, 50.0, 10.0, 5.0, 100, 20000, 20000))
        assertFalse(d.hasPrevious)
        assertFalse(d.hasChanges)
    }

    @Test
    fun `diff reports deltas and new transactions`() {
        val prev = snap(180000.0, 142000.0, 3000.0, 2800.0, 8700, 20270, 20270)
        val cur = snap(188000.0, 140000.0, 3100.0, 3300.0, 8810, 20279, 20279)
        val d = ImportDigestEngine.diff(prev, cur)
        assertTrue(d.hasPrevious)
        assertTrue(d.hasChanges)
        assertEquals(8000.0, d.netWorthDelta, 0.001)
        assertEquals(-2000.0, d.debtDelta, 0.001)   // paid down
        assertEquals(110, d.newTransactions)
        assertEquals(9, d.daysSincePrevious)
    }
}
