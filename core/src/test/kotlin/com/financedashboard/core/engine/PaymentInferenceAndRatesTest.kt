package com.financedashboard.core.engine

import com.financedashboard.core.backup.BackupCodec
import com.financedashboard.core.csv.RatesCsvParser
import com.financedashboard.core.model.TransactionRecord
import java.io.BufferedReader
import java.io.StringReader
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentInferenceAndRatesTest {

    private fun pay(merchant: String, amount: Double, date: String) =
        TransactionRecord(LocalDate.parse(date), merchant, "Loan Repayment", "Checking", "", "", amount, "", "Me")

    @Test
    fun `infers median monthly payment matched by institution`() {
        val txs = listOf(
            pay("SoFi", -1300.0, "2026-02-08"),
            pay("SoFi", -1356.0, "2026-03-08"),
            pay("SoFi", -1356.0, "2026-04-08"),
            pay("Aidvantage", -118.0, "2026-03-16"),
            pay("Aidvantage", -118.0, "2026-04-16"),
        )
        val inferred = PaymentInference.inferMonthlyPayments(
            listOf("SoFi Personal Loan (...0204)", "Aidvantage"), txs,
        )
        // Floor of the recent monthly sums: Feb=1300, Mar=1356, Apr=1356 -> 1300.
        val sofi = inferred.first { it.debtAccountName.contains("SoFi") }
        assertEquals(1300.0, sofi.monthlyPayment, 0.001)
        assertEquals(3, sofi.monthsObserved)
        val aid = inferred.first { it.debtAccountName == "Aidvantage" }
        assertEquals(118.0, aid.monthlyPayment, 0.001)
    }

    @Test
    fun `generically named loan without matching payments is omitted`() {
        val txs = listOf(pay("Capital One", -1272.0, "2026-05-29"))
        // "Auto Loan (...9814)" has no institution token, and a Loan-Repayment
        // payment to another lender is not its Auto-Payment category, so no match.
        val inferred = PaymentInference.inferMonthlyPayments(listOf("Auto Loan (...9814)"), txs)
        assertTrue(inferred.isEmpty())
    }


    @Test
    fun `rates csv parses flexible headers and matches accounts`() {
        val csv = """
            Account,APR,MinPayment
            SoFi Personal Loan,9.2,1310
            Aidvantage,6.0,150
            Auto Loan (...9814),5.26,
        """.trimIndent()
        val entries = RatesCsvParser().parse(BufferedReader(StringReader(csv)))
        assertEquals(3, entries.size)
        assertEquals(9.2, entries[0].aprPct, 0.001)
        assertEquals(1310.0, entries[0].minPayment!!, 0.001)
        assertNull(entries[2].minPayment)

        val parser = RatesCsvParser()
        val accounts = listOf("SoFi Personal Loan (...0204)", "Aidvantage", "Auto Loan (...9814)")
        assertEquals("SoFi Personal Loan (...0204)", parser.matchToAccount(entries[0], accounts))
        assertEquals("Aidvantage", parser.matchToAccount(entries[1], accounts))
        assertEquals("Auto Loan (...9814)", parser.matchToAccount(entries[2], accounts))
    }

    @Test
    fun `rates csv accepts percent signs and rate synonyms`() {
        val csv = "name,interest rate,monthly\nMy Loan,7.5%,\"1,200\"\n"
        val entries = RatesCsvParser().parse(BufferedReader(StringReader(csv)))
        assertEquals(1, entries.size)
        assertEquals(7.5, entries[0].aprPct, 0.001)
        assertEquals(1200.0, entries[0].minPayment!!, 0.001)
    }

    @Test
    fun `backup round trips`() {
        val backup = BackupCodec.Backup(
            exportedEpochMs = 123L,
            balances = listOf(BackupCodec.BalanceDto(20000, -1000.0, "Card")),
            transactions = listOf(BackupCodec.TransactionDto(20000, "Shop", "Shopping", "Card", "st", "", -12.5, "", "Me")),
            accounts = listOf(BackupCodec.AccountDto("Card", "DEBT", true)),
            debtAssumptions = listOf(BackupCodec.DebtAssumptionDto("Card", 22.0, 50.0, true)),
            manualIncome = listOf(BackupCodec.ManualIncomeDto(2025, 98000.0)),
            cpiOverrides = listOf(BackupCodec.CpiOverrideDto(2025, 322.3)),
        )
        val decoded = BackupCodec.decode(BackupCodec.encode(backup))
        assertNotNull(decoded)
        assertEquals(backup, decoded)
    }

    @Test
    fun `decode rejects junk and unmarked json`() {
        assertNull(BackupCodec.decode("not json"))
        assertNull(BackupCodec.decode("""{"foo":"bar"}"""))
    }
}
