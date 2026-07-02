package com.financedashboard.core.engine

import com.financedashboard.core.model.BalanceRecord
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class NetWorthAggregatorTest {

    private fun rec(account: String, balance: Double, date: String) =
        BalanceRecord(LocalDate.parse(date), balance, account)

    @Test
    fun `closed account stops counting after the grace window`() {
        val series = NetWorthAggregator.monthlySeries(
        listOf(
                rec("Checking", 1_000.0, "2026-01-15"),
                rec("Checking", 1_000.0, "2026-07-01"),
                rec("Old BNPL", -5_000.0, "2026-01-15"), // closed: no rows after January
            )
        )
        val jan = series.first { it.month.toString() == "2026-01" }
        assertEquals(5_000.0, jan.debts, 0.001)
        // Within grace (Feb, Mar) it still carries; by April it is gone.
        assertEquals(5_000.0, series.first { it.month.toString() == "2026-03" }.debts, 0.001)
        assertEquals(0.0, series.first { it.month.toString() == "2026-04" }.debts, 0.001)
        assertEquals(0.0, series.last().debts, 0.001)
    }

    @Test
    fun `sparse snapshots carry forward within an account's life`() {
        val series = NetWorthAggregator.monthlySeries(
            listOf(
                rec("Watch", 8_000.0, "2026-01-10"),
                rec("Watch", 8_500.0, "2026-06-10"), // nothing in Feb-May
            )
        )
        assertEquals(8_000.0, series.first { it.month.toString() == "2026-03" }.assets, 0.001)
        assertEquals(8_500.0, series.first { it.month.toString() == "2026-06" }.assets, 0.001)
    }

    @Test
    fun `duplicate stops counting when its replacement starts, history preserved`() {
        val series = NetWorthAggregator.monthlySeries(
            listOf(
                // Manual tracker of a loan, kept updating until mid-June.
                rec("Acme", -61_550.0, "2026-01-10"),
                rec("Acme", -61_550.0, "2026-06-14"),
                // Linked account for the same loan appears mid-June.
                rec("Acme Personal Loan (...0204)", -61_538.0, "2026-06-15"),
                rec("Acme Personal Loan (...0204)", -61_538.0, "2026-07-01"),
                // Anchor account so the month grid spans the whole period.
                rec("Checking", 100.0, "2026-01-01"),
                rec("Checking", 100.0, "2026-07-01"),
            )
        )
        // Before the link: the manual tracker is the loan's history.
        assertEquals(61_550.0, series.first { it.month.toString() == "2026-03" }.debts, 0.001)
        // After the link: only the linked account counts — never both.
        assertEquals(61_538.0, series.first { it.month.toString() == "2026-06" }.debts, 0.001)
        assertEquals(61_538.0, series.last().debts, 0.001)
    }

    @Test
    fun `overpaid card counts as asset, zeroed debt contributes nothing`() {
        val series = NetWorthAggregator.monthlySeries(
            listOf(
                rec("Card A", -100.0, "2026-06-01"),
                rec("Card A", 8.0, "2026-07-01"), // credit balance
                rec("Card B", -50.0, "2026-06-01"),
                rec("Card B", 0.0, "2026-07-01"),
            )
        )
        val last = series.last()
        assertEquals(8.0, last.assets, 0.001)
        assertEquals(0.0, last.debts, 0.001)
    }
}
