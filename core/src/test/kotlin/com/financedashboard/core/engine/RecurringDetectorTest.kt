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

    @Test
    fun `debt servicing and variable spend are not subscriptions`() {
        val dates = listOf("2026-01-05", "2026-02-05", "2026-03-05", "2026-04-05", "2026-05-05")
        val txs = dates.flatMap {
            listOf(
                tx("Auto Payment", -387.0, it, "Auto Payment"),        // handled by debt engine
                tx("Aidvantage", -118.0, it, "Student Loans"),          // handled by debt engine
                tx("McDonald's", -11.0, it, "Restaurants & Bars"),      // variable habit, not a bill
                tx("Cash Advance Fee", -10.0, it, "Financial Fees"),    // fee, not a subscription
            )
        }
        assertTrue(RecurringDetector.detect(txs, LocalDate.parse("2026-05-20")).isEmpty())
    }

    @Test
    fun `descriptor drift for the same payee merges into one subscription`() {
        // Bank re-labels the same therapist mid-history; both must count as one.
        val old = listOf("2026-01-06", "2026-02-06", "2026-03-06").map { tx("Gracefultherapy Gracefultil", -30.0, it, "Personal") }
        val new = listOf("2026-04-06", "2026-05-06", "2026-06-06").map { tx("Graceful Therapy", -30.0, it, "Personal") }
        val subs = RecurringDetector.detect(old + new, LocalDate.parse("2026-06-20"))
        assertEquals(1, subs.size)
        val s = subs.first()
        assertEquals(6, s.occurrences)
        assertTrue(!s.possiblyCancelled)                  // the merge keeps the latest charge date
        assertEquals(LocalDate.parse("2026-06-06"), s.lastCharge)
    }
}
