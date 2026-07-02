package com.financedashboard.core.classify

import com.financedashboard.core.model.BalanceRecord
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebtExtractorTest {

    private val asOf = LocalDate.of(2026, 7, 1)
    private fun rec(account: String, balance: Double, daysAgo: Long = 0) =
        BalanceRecord(asOf.minusDays(daysAgo), balance, account)

    @Test
    fun `uses only the latest row per account, never summing snapshots`() {
        val ex = DebtExtractor.extract(
            listOf(
                rec("Personal Loan (...1111)", -10_000.0, 30),
                rec("Personal Loan (...1111)", -9_500.0, 15),
                rec("Personal Loan (...1111)", -9_000.0, 0),
            )
        )
        assertEquals(1, ex.active.size)
        assertEquals(-9_000.0, ex.active[0].balance, 0.001)
    }

    @Test
    fun `paid off and stale liabilities are excluded`() {
        val ex = DebtExtractor.extract(
            listOf(
                rec("Old BNPL Plan", -1_500.0, 380),          // stale -> closed
                rec("Refi'd Auto Loan (...7777)", -9_000.0, 90), // stale -> refinanced away
                rec("Paid Card (...2222)", -500.0, 45),
                rec("Paid Card (...2222)", 0.0, 1),           // ended at zero -> paid off
                rec("Active Loan (...3333)", -5_000.0, 2),
            )
        )
        assertEquals(listOf("Active Loan (...3333)"), ex.active.map { it.accountName })
        val reasons = ex.excluded.associate { it.candidate.accountName to it.reason }
        assertEquals(DebtExtractor.ExclusionReason.STALE, reasons["Old BNPL Plan"])
        assertEquals(DebtExtractor.ExclusionReason.STALE, reasons["Refi'd Auto Loan (...7777)"])
        assertEquals(DebtExtractor.ExclusionReason.PAID_OFF, reasons["Paid Card (...2222)"])
    }

    @Test
    fun `manual and linked versions of the same loan are flagged, newest kept`() {
        val ex = DebtExtractor.extract(
            listOf(
                rec("Acme", -61_550.0, 17),                    // manual tracker, older
                rec("Acme Personal Loan (...0204)", -61_538.0, 0), // linked, current
            )
        )
        assertEquals(listOf("Acme Personal Loan (...0204)"), ex.active.map { it.accountName })
        val dup = ex.needsReview.single()
        assertEquals("Acme", dup.candidate.accountName)
        assertEquals(DebtExtractor.ReviewReason.SUSPECTED_DUPLICATE, dup.reason)
        assertEquals("Acme Personal Loan (...0204)", dup.duplicateOf)
    }

    @Test
    fun `relinked loan with new mask but near-identical balance is flagged`() {
        val ex = DebtExtractor.extract(
            listOf(
                rec("2025 ROADSTER (...2322)", -69_258.0, 17),
                rec("Auto Loan (...9814)", -68_430.0, 0),
            )
        )
        assertEquals(listOf("Auto Loan (...9814)"), ex.active.map { it.accountName })
        assertEquals(DebtExtractor.ReviewReason.SUSPECTED_DUPLICATE, ex.needsReview.single().reason)
    }

    @Test
    fun `distinct loans with distinct masks and different balances both count`() {
        val ex = DebtExtractor.extract(
            listOf(
                rec("Auto Loan (...1111)", -20_000.0, 1),
                rec("Student Loan (...2222)", -10_000.0, 1),
            )
        )
        assertEquals(2, ex.active.size)
        assertTrue(ex.needsReview.isEmpty())
    }

    @Test
    fun `credit cards go to review, never auto-counted`() {
        val ex = DebtExtractor.extract(
            listOf(
                rec("Apple Card", -153.89, 0),
                rec("CREDIT CARD (...2383)", -18.34, 0),
                rec("Personal Loan (...3333)", -5_000.0, 0),
            )
        )
        assertEquals(listOf("Personal Loan (...3333)"), ex.active.map { it.accountName })
        assertEquals(2, ex.needsReview.count { it.reason == DebtExtractor.ReviewReason.CARD_STATEMENT_BALANCE })
    }

    @Test
    fun `asset accounts are never debts`() {
        val ex = DebtExtractor.extract(
            listOf(
                rec("Checking (...0001)", 1_955.0, 0),
                rec("Watch", 8_000.0, 0),
            )
        )
        assertTrue(ex.active.isEmpty() && ex.needsReview.isEmpty() && ex.excluded.isEmpty())
    }

    @Test
    fun `institution and last4 parsing`() {
        assertEquals("0204", DebtExtractor.last4Of("Acme Personal Loan (...0204)"))
        assertEquals(null, DebtExtractor.last4Of("Acme"))
        assertEquals("acme", DebtExtractor.institutionOf("Acme Personal Loan (...0204)"))
        assertEquals(null, DebtExtractor.institutionOf("Auto Loan (...9814)"))
    }
}
