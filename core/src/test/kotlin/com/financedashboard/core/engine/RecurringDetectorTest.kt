package com.financedashboard.core.engine

import com.financedashboard.core.model.TransactionRecord
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecurringDetectorTest {

    private fun tx(merchant: String, amount: Double, date: String, category: String = "Entertainment & Recreation") =
        TransactionRecord(LocalDate.parse(date), merchant, category, "Card", "", "", amount, "", "Me")

    @Test
    fun `detects a monthly subscription`() {
        val txs = listOf("2026-01-05", "2026-02-05", "2026-03-05", "2026-04-05", "2026-05-05")
            .map { tx("Streamflix", -15.99, it) }
        val subs = RecurringDetector.detect(txs, LocalDate.parse("2026-05-20"))
        assertEquals(1, subs.size)
        val s = subs.first()
        assertEquals(RecurringDetector.Cadence.MONTHLY, s.cadence)
        assertEquals(15.99, s.typicalAmount, 0.001)
        assertEquals(15.99, s.monthlyEquivalent, 0.01)
        assertTrue(!s.possiblyCancelled)
    }

    @Test
    fun `annual membership normalized to monthly equivalent`() {
        val txs = listOf("2024-03-01", "2025-03-01", "2026-03-01").map { tx("Warehouse Club", -120.0, it) }
        val subs = RecurringDetector.detect(txs, LocalDate.parse("2026-04-01"))
        assertEquals(1, subs.size)
        assertEquals(RecurringDetector.Cadence.ANNUAL, subs.first().cadence)
        assertEquals(10.0, subs.first().monthlyEquivalent, 0.01)
    }

    @Test
    fun `one-off purchases are not subscriptions`() {
        val txs = listOf(
            tx("Random Shop", -42.0, "2026-01-03", "Shopping"),
            tx("Another Store", -88.0, "2026-02-14", "Shopping"),
        )
        assertTrue(RecurringDetector.detect(txs, LocalDate.parse("2026-03-01")).isEmpty())
    }

    @Test
    fun `merchant with store numbers still groups`() {
        val txs = listOf(
            tx("Gym #12", -40.0, "2026-01-10", "Fitness"),
            tx("Gym #12", -40.0, "2026-02-10", "Fitness"),
            tx("Gym #340", -40.0, "2026-03-10", "Fitness"),
        )
        val subs = RecurringDetector.detect(txs, LocalDate.parse("2026-03-20"))
        assertEquals(1, subs.size)
        assertEquals(40.0, subs.first().monthlyEquivalent, 0.01)
    }

    @Test
    fun `cancelled subscription flagged after gap`() {
        val txs = listOf("2025-01-01", "2025-02-01", "2025-03-01", "2025-04-01").map { tx("Old Service", -9.99, it) }
        val subs = RecurringDetector.detect(txs, LocalDate.parse("2026-06-01"))
        assertEquals(1, subs.size)
        assertTrue(subs.first().possiblyCancelled)
        assertEquals(0.0, RecurringDetector.monthlyTotal(subs), 0.001)
    }

    @Test
    fun `transfers and paychecks are ignored`() {
        val txs = listOf("2026-01-01", "2026-02-01", "2026-03-01", "2026-04-01", "2026-05-01")
            .flatMap {
                listOf(
                    tx("Employer", 2000.0, it, "Paychecks"),
                    tx("Venmo", -100.0, it, "Transfer"),
                )
            }
        assertTrue(RecurringDetector.detect(txs, LocalDate.parse("2026-06-01")).isEmpty())
    }
}
