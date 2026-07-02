package com.financedashboard.core.csv

import com.financedashboard.core.classify.AccountClassifier
import com.financedashboard.core.engine.IncomeAggregator
import com.financedashboard.core.model.AccountType
import java.io.BufferedReader
import java.io.StringReader
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class CsvParserTest {

    private fun reader(s: String) = BufferedReader(StringReader(s))

    @Test
    fun `parses balances rows`() {
        val csv = """
            Date,Balance,Account
            2026-01-01,1234.56,Checking (...0001)
            2026-01-01,-500.00,Sample Card
        """.trimIndent()
        val rows = BalancesCsvParser().parse(reader(csv))
        assertEquals(2, rows.size)
        assertEquals(LocalDate.of(2026, 1, 1), rows[0].date)
        assertEquals(1234.56, rows[0].balance, 0.001)
        assertEquals("Sample Card", rows[1].account)
    }

    @Test
    fun `handles quoted fields with commas and embedded quotes`() {
        val csv = "Date,Merchant,Category,Account,Original Statement,Notes,Amount,Tags,Owner,Reviewed\n" +
            "2026-01-02,\"Store, Inc.\",Shopping,Card,\"He said \"\"hi\"\", twice\",,-10.50,,Shared,\n"
        val rows = TransactionsCsvParser().parse(reader(csv))
        assertEquals(1, rows.size)
        assertEquals("Store, Inc.", rows[0].merchant)
        assertEquals("He said \"hi\", twice", rows[0].statement)
        assertEquals(-10.50, rows[0].amount, 0.001)
    }

    @Test
    fun `skips malformed rows without failing the import`() {
        val csv = """
            Date,Balance,Account
            not-a-date,100.00,Checking
            2026-01-01,oops,Checking
            2026-01-01,100.00,Checking
        """.trimIndent()
        val rows = BalancesCsvParser().parse(reader(csv))
        assertEquals(1, rows.size)
    }

    @Test
    fun `paycheck income aggregates by year and ignores negatives`() {
        val csv = "Date,Merchant,Category,Account,Original Statement,Notes,Amount,Tags,Owner,Reviewed\n" +
            "2025-01-15,Employer,Paychecks,Checking,DEP,,2000.00,,Me,\n" +
            "2025-02-15,Employer,Paychecks,Checking,DEP,,2000.00,,Me,\n" +
            "2025-03-01,Employer,Paychecks,Checking,REVERSAL,,-500.00,,Me,\n" +
            "2024-12-15,Employer,Paychecks,Checking,DEP,,1500.00,,Me,\n" +
            "2025-04-01,Cafe,Restaurants & Bars,Card,POS,,-12.00,,Me,\n"
        val tx = TransactionsCsvParser().parse(reader(csv))
        val income = IncomeAggregator.netPaycheckIncomeByYear(tx)
        assertEquals(4000.0, income[2025]!!, 0.001)
        assertEquals(1500.0, income[2024]!!, 0.001)
    }

    @Test
    fun `classifier maps common account names`() {
        assertEquals(AccountType.INVESTMENT, AccountClassifier.classify("EMPLOYER 401(K) SAVINGS PLAN", 1000.0, false))
        assertEquals(AccountType.INVESTMENT, AccountClassifier.classify("Robinhood Roth IRA (...0000)", 0.0, false))
        assertEquals(AccountType.DEBT, AccountClassifier.classify("Personal Loan (...0000)", -100.0, true))
        assertEquals(AccountType.CASH, AccountClassifier.classify("Checking (...0000)", 50.0, false))
        assertEquals(AccountType.DEBT, AccountClassifier.classify("Mystery Account", -42.0, true))
        assertEquals(AccountType.ASSET, AccountClassifier.classify("Watch", 500.0, false))
    }
}
