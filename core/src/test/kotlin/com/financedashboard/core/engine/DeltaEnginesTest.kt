package com.financedashboard.core.engine

import com.financedashboard.core.model.BalanceRecord
import com.financedashboard.core.model.TransactionRecord
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeltaEnginesTest {

    // ---- Interest ledger ----

    @Test
    fun `interest ledger estimates interest as payment minus principal reduction`() {
        val account = "Acme Loan (...0204)"
        val balances = listOf(
            BalanceRecord(LocalDate.parse("2026-01-31"), -10_000.0, account),
            BalanceRecord(LocalDate.parse("2026-02-28"), -9_600.0, account), // owed fell 400
        )
        val txs = listOf(
            TransactionRecord(LocalDate.parse("2026-02-15"), "Acme", "Loan Repayment", "Checking", "", "", -500.0, "", "Me"),
        )
        val ledger = InterestLedgerEngine.compute(listOf(account), balances, txs)
        val d = ledger.single()
        // Paid 500, principal fell 400 → 100 interest.
        assertEquals(100.0, d.interestPaid, 0.001)
        assertEquals(400.0, d.principalPaid, 0.001)
    }

    // ---- Safe to spend ----

    @Test
    fun `safe to spend subtracts commitments and spending`() {
        val r = SafeToSpendEngine.compute(6_000.0, 1_500.0, 2_000.0, 800.0)
        assertEquals(1_700.0, r.safeToSpend, 0.001)
    }

    // ---- Monte Carlo ----

    @Test
    fun `monte carlo is deterministic with a seed and ordered percentiles`() {
        val a = MonteCarloEngine.simulate(100_000.0, 1_000.0, 7.0, 15.0, 20, 500_000.0, runs = 500, seed = 7)
        val b = MonteCarloEngine.simulate(100_000.0, 1_000.0, 7.0, 15.0, 20, 500_000.0, runs = 500, seed = 7)
        assertEquals(b.p50, a.p50, 0.001)
        assertTrue(a.p10 <= a.p50 && a.p50 <= a.p90)
        assertTrue(a.probabilityOfGoal in 0.0..1.0)
        assertEquals(21, a.medianByYear.size)
    }

    @Test
    fun `higher volatility widens the outcome spread`() {
        val low = MonteCarloEngine.simulate(100_000.0, 0.0, 7.0, 5.0, 20, 1.0, runs = 800, seed = 3)
        val high = MonteCarloEngine.simulate(100_000.0, 0.0, 7.0, 25.0, 20, 1.0, runs = 800, seed = 3)
        assertTrue((high.p90 - high.p10) > (low.p90 - low.p10))
    }

    @Test
    fun `zero volatility matches deterministic compounding`() {
        val mc = MonteCarloEngine.simulate(10_000.0, 0.0, 6.0, 0.0, 10, 0.0, runs = 50, seed = 1)
        val expected = InvestmentEngine.project(10_000.0, 0.0, 6.0, 10, 0.0).years.last().endBalanceNominal
        assertEquals(expected, mc.p50, 1.0)
    }

    // ---- Roth vs traditional ----

    @Test
    fun `roth wins when retirement rate exceeds current rate`() {
        val r = RothVsTraditionalEngine.compare(10_000.0, 20.0, 30.0, 7.0, 20)
        assertTrue(r.rothWins)
    }

    @Test
    fun `traditional wins when retirement rate is lower`() {
        val r = RothVsTraditionalEngine.compare(10_000.0, 32.0, 15.0, 7.0, 20)
        assertTrue(!r.rothWins)
        assertTrue(r.traditionalAfterTax > r.rothAfterTax)
    }

    @Test
    fun `equal rates make roth and traditional equivalent`() {
        val r = RothVsTraditionalEngine.compare(10_000.0, 24.0, 24.0, 7.0, 20)
        assertEquals(r.traditionalAfterTax, r.rothAfterTax, 0.01)
    }

    // ---- Crypto sell analysis ----

    @Test
    fun `crypto sell analysis computes unrealized gain and ltcg`() {
        val trades = listOf(CryptoLotEngine.Trade(LocalDate.parse("2025-01-01"), "buy", 1.0, 30_000.0, "BTC"))
        val result = CryptoLotEngine.account(trades)
        val sell = CryptoLotEngine.sellAnalysis(result, currentValue = 50_000.0, ltcgRatePct = 15.0)
        assertEquals(20_000.0, sell.unrealizedGain, 0.001)
        assertEquals(3_000.0, sell.ltcgTax, 0.001) // 15% of 20k
        assertEquals(47_000.0, sell.netProceeds, 0.001)
    }

    @Test
    fun `crypto sell at a loss has no tax`() {
        val trades = listOf(CryptoLotEngine.Trade(LocalDate.parse("2025-01-01"), "buy", 1.0, 60_000.0, "BTC"))
        val sell = CryptoLotEngine.sellAnalysis(CryptoLotEngine.account(trades), 40_000.0, 15.0)
        assertEquals(0.0, sell.ltcgTax, 0.001)
        assertTrue(sell.unrealizedGain < 0)
    }
}
