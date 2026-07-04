package com.financedashboard.core.engine

import com.financedashboard.core.model.AccountType
import com.financedashboard.core.model.BalanceRecord
import com.financedashboard.core.model.Debt
import com.financedashboard.core.model.TransactionRecord
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewEnginesTest {

    private fun bal(account: String, amount: Double, date: String) =
        BalanceRecord(LocalDate.parse(date), amount, account)

    private fun tx(merchant: String, amount: Double, date: String, category: String = "Shopping", account: String = "Checking") =
        TransactionRecord(LocalDate.parse(date), merchant, category, account, "", "", amount, "", "Me")

    // ---- Import health ----

    @Test
    fun `reconciliation flags balance-vs-transaction mismatch`() {
        val balances = listOf(bal("Checking", 1000.0, "2026-01-01"), bal("Checking", 1000.0, "2026-02-01"))
        // Balance flat but a big spend recorded → mismatch.
        val txs = listOf(tx("Store", -800.0, "2026-01-15", account = "Checking"))
        val report = ImportHealthEngine.analyze(balances, txs)
        assertTrue(report.findings.any { it.title.contains("reconcile") })
    }

    @Test
    fun `clean data yields no reconciliation warning`() {
        val balances = listOf(bal("Checking", 1000.0, "2026-01-01"), bal("Checking", 800.0, "2026-02-01"))
        val txs = listOf(tx("Store", -200.0, "2026-01-15", account = "Checking"))
        val report = ImportHealthEngine.analyze(balances, txs)
        assertTrue(report.findings.none { it.title.contains("reconcile") })
    }

    @Test
    fun `wholesale-duplicated file flagged, normal repeats not`() {
        // 15 distinct rows, then the same 15 again → ~50% duplicated.
        val distinct = (1..15).map { tx("Merchant$it", -it.toDouble(), "2026-01-10", account = "Card") }
        val doubled = distinct + distinct
        assertTrue(ImportHealthEngine.analyze(emptyList(), doubled).findings.any { it.title.contains("duplicated") })

        // Normal file with a few legit same-day repeats → no warning.
        val normal = (1..30).map { tx("Merchant$it", -it.toDouble(), "2026-01-10", account = "Card") } +
            listOf(tx("Merchant1", -1.0, "2026-01-10", account = "Card"))
        assertTrue(ImportHealthEngine.analyze(emptyList(), normal).findings.none { it.title.contains("duplicated") })
    }

    // ---- Paycheck wedge ----

    @Test
    fun `paycheck wedge splits retirement and tax`() {
        val wedge = PaycheckWedgeEngine.compute(
            grossByYear = mapOf(2025 to 100_000.0),
            netByYear = mapOf(2025 to 70_000.0),
            retirementByYear = mapOf(2025 to 10_000.0),
        ).single()
        assertEquals(30.0, wedge.wedgePct, 0.001)
        assertEquals(10_000.0, wedge.retirement, 0.001)
        assertEquals(20_000.0, wedge.taxAndOther, 0.001)
    }

    // ---- FIRE ----

    @Test
    fun `fire number is 25x spending at 4 percent`() {
        val r = FireEngine.compute(40_000.0, 200_000.0, 1_000.0, 5.0, 20)
        assertEquals(1_000_000.0, r.fireNumber, 0.01)
        assertEquals(20.0, r.progressPct, 0.01)
        assertNotNull(r.yearsToFire)
    }

    @Test
    fun `coast fire true when portfolio already sufficient`() {
        // Huge portfolio, small target → already coasted.
        val r = FireEngine.compute(20_000.0, 900_000.0, 0.0, 5.0, 20)
        assertTrue(r.hasCoasted)
    }

    // ---- Refinance ----

    @Test
    fun `refinance to lower rate saves interest`() {
        val c = RefinanceEngine.compare(
            balance = 20_000.0, currentAprPct = 9.0, newAprPct = 5.0,
            monthlyPayment = 400.0, fees = 300.0, startMonth = YearMonth.of(2026, 1),
        )
        assertTrue(c.interestSaved > 0)
        assertTrue(c.netSaving > 0)
        assertNotNull(c.breakEvenMonth)
    }

    // ---- Extra-dollar optimizer ----

    @Test
    fun `optimizer favors paydown when apr exceeds return`() {
        val debts = listOf(Debt("Card", 5_000.0, 22.0, 100.0))
        val r = ExtraDollarOptimizer.compare(debts, 200.0, 7.0, 10, YearMonth.of(2026, 1))!!
        assertEquals("Card", r.targetDebtName)
        assertEquals(22.0, r.crossoverReturnPct, 0.001)
        assertTrue(r.recommendation.contains("Paying down"))
    }

    @Test
    fun `optimizer favors investing when return exceeds apr`() {
        val debts = listOf(Debt("Low", 5_000.0, 3.0, 100.0))
        val r = ExtraDollarOptimizer.compare(debts, 200.0, 7.0, 10, YearMonth.of(2026, 1))!!
        assertTrue(r.recommendation.contains("Investing"))
    }

    @Test
    fun `optimizer after-tax crossover raises the bar`() {
        val debts = listOf(Debt("Card", 5_000.0, 6.0, 100.0))
        val r = ExtraDollarOptimizer.compare(debts, 200.0, 7.0, 10, YearMonth.of(2026, 1), marginalTaxRatePct = 25.0)!!
        // 6% / (1-0.25) = 8% crossover; 7% expected < 8% → paydown.
        assertEquals(8.0, r.crossoverReturnPct, 0.01)
        assertTrue(r.recommendation.contains("Paying down"))
    }

    // ---- Attribution ----

    @Test
    fun `attribution separates saving market and debt paydown`() {
        val typeOf: (String) -> AccountType = {
            when (it) {
                "Checking" -> AccountType.CASH
                "Brokerage" -> AccountType.INVESTMENT
                "Card" -> AccountType.DEBT
                else -> AccountType.UNKNOWN
            }
        }
        val balances = listOf(
            bal("Checking", 5_000.0, "2026-01-01"), bal("Checking", 5_500.0, "2026-12-31"),
            bal("Brokerage", 10_000.0, "2026-01-01"), bal("Brokerage", 13_000.0, "2026-12-31"),
            bal("Card", -2_000.0, "2026-01-01"), bal("Card", -1_000.0, "2026-12-31"),
        )
        // $1200 of the brokerage rise came from contributions recorded on the account.
        val txs = listOf(tx("Deposit", 1_200.0, "2026-06-01", category = "Transfer", account = "Brokerage"))
        val a = AttributionEngine.attribute(balances, txs, typeOf, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"))
        assertEquals(1_800.0, a.marketChange, 0.01) // 3000 rise - 1200 contributions
        assertEquals(1_000.0, a.debtPrincipalPaid, 0.01) // -2000 -> -1000
        // saving = checking +500 + contributions 1200 = 1700
        assertEquals(1_700.0, a.cashSaved, 0.01)
        assertEquals(0.0, a.residual, 0.01)
    }

    // ---- Crypto lots ----

    @Test
    fun `crypto lots parse memos and compute fifo gain`() {
        val txs = listOf(
            TransactionRecord(LocalDate.parse("2025-01-01"), "Coinbase", "Transfer", "Crypto", "buy 1.0 BTC for \$30000.00 each", "", -30000.0, "", "Me"),
            TransactionRecord(LocalDate.parse("2025-06-01"), "Coinbase", "Transfer", "Crypto", "buy 1.0 BTC for \$50000.00 each", "", -50000.0, "", "Me"),
            TransactionRecord(LocalDate.parse("2026-01-01"), "Coinbase", "Transfer", "Crypto", "sell 1.0 BTC for \$60000.00 each", "", 60000.0, "", "Me"),
        )
        val trades = CryptoLotEngine.parseTrades(txs)
        assertEquals(3, trades.size)
        val r = CryptoLotEngine.account(trades)
        // FIFO: sold the $30k lot -> gain 30k. One $50k lot remains.
        assertEquals(30_000.0, r.realizedGain, 0.01)
        assertEquals(1.0, r.remainingUnits, 1e-9)
        assertEquals(50_000.0, r.remainingCostBasis, 0.01)
    }

    // ---- Price creep ----

    @Test
    fun `price hikes detected on recurring charges`() {
        // Two charges at the old price, then four at the new price (recent median = new).
        val dates = listOf("2025-01-05", "2025-02-05", "2025-03-05", "2025-04-05", "2025-05-05", "2025-06-05")
        val txs = dates.mapIndexed { i, d ->
            tx("Streamer", -(if (i < 2) 10.0 else 12.0), d, category = "Entertainment & Recreation", account = "Card")
        }
        val subs = RecurringDetector.detect(txs, LocalDate.parse("2025-06-20"))
        val hikes = RecurringDetector.priceHikes(subs, minIncrease = 0.10)
        assertTrue(hikes.isNotEmpty())
        assertTrue(hikes.first().priceChangePct >= 0.10)
    }

    // ---- Sensitivity ----

    @Test
    fun `sensitivity ranks factors by outcome spread`() {
        val factors = listOf(
            SensitivityEngine.Factor("a", 1.0, 0.0, 2.0),
            SensitivityEngine.Factor("b", 1.0, 0.9, 1.1),
        )
        // Outcome depends 10x more on a than b.
        val impacts = SensitivityEngine.analyze(factors) { p -> p.getValue("a") * 10 + p.getValue("b") }
        assertEquals("a", impacts.first().name)
        assertTrue(impacts.first().spread > impacts.last().spread)
    }
}
